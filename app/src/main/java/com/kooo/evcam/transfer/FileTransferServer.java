package com.kooo.evcam.transfer;

import android.content.Context;
import android.net.wifi.WifiManager;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.URLEncoder;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * 文件传输 HTTP 服务器 - 使用 NanoHTTPD
 * 提供类似微信文件传输助手的扫码互传功能
 */
public class FileTransferServer extends NanoHTTPD {
    private static final String TAG = "FileTransferServer";
    
    private Context context;
    private WifiManager wifiManager;
    private Map<String, File> pendingFiles = new HashMap<>();
    private TransferCallback callback;
    private int currentPort;
    private String currentIp;

    public interface TransferCallback {
        void onServerStarted(String ipAddress, int port, String qrUrl);
        void onServerStopped();
        void onFileRequested(String fileName);
        void onFileTransferred(String fileName, boolean success);
        void onError(String error);
    }

    public FileTransferServer(Context context) {
        super(0); // 端口 0 表示自动分配
        this.context = context.getApplicationContext();
        this.wifiManager = (WifiManager) this.context.getSystemService(Context.WIFI_SERVICE);
    }

    public void setCallback(TransferCallback callback) {
        this.callback = callback;
    }

    public void setSpecifiedIpAddress(String ipAddress) {
        this.currentIp = ipAddress;
    }

    @Override
    public void start() throws IOException {
        super.start();
        currentPort = getListeningPort();
        currentIp = getHotspotIpAddress();
        
        AppLog.i(TAG, "文件传输服务器启动成功: " + currentIp + ":" + currentPort);
        
        if (callback != null) {
            String qrUrl = "http://" + currentIp + ":" + currentPort;
            callback.onServerStarted(currentIp, currentPort, qrUrl);
        }
    }

    @Override
    public void stop() {
        super.stop();
        AppLog.i(TAG, "文件传输服务器已停止");
        if (callback != null) {
            callback.onServerStopped();
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Method method = session.getMethod();
        
        AppLog.i(TAG, "收到请求: " + method + " " + uri + " 来自 " + session.getRemoteIpAddress());

        try {
            // 处理 OPTIONS 请求 (CORS 预检)
            if (Method.OPTIONS.equals(method)) {
                Response response = newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "");
                addCorsHeaders(response);
                return response;
            }

            // 主页 - 文件列表
            if ("/".equals(uri) || "/index.html".equals(uri)) {
                return serveFileListPage();
            }

            // API: 获取文件列表 JSON
            if ("/api/files".equals(uri)) {
                return serveFileListJson();
            }

            // API: 浏览目录
            if (uri.startsWith("/api/browse")) {
                Map<String, String> params = session.getParms();
                String path = params.get("path");
                if (path == null || path.isEmpty()) {
                    path = "/";
                }
                return serveBrowseRequest(path);
            }

            // 下载文件
            if (uri.startsWith("/download/")) {
                String fileId = uri.substring("/download/".length());
                try {
                    fileId = java.net.URLDecoder.decode(fileId, "UTF-8");
                } catch (Exception e) {
                    AppLog.e(TAG, "URL解码失败: " + fileId, e);
                    Response response = newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, 
                            "Invalid file ID encoding");
                    addCorsHeaders(response);
                    return response;
                }
                return serveFile(fileId);
            }

            // 404
            Response response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_HTML, 
                    "<html><body><h1>404 Not Found</h1></body></html>");
            addCorsHeaders(response);
            return response;
            
        } catch (Exception e) {
            AppLog.e(TAG, "处理请求时出错: " + uri, e);
            Response response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, 
                    "Error: " + e.getMessage());
            addCorsHeaders(response);
            return response;
        }
    }

    private void addCorsHeaders(Response response) {
        response.addHeader("Access-Control-Allow-Origin", "*");
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        response.addHeader("Access-Control-Allow-Headers", "Content-Type");
    }

    private Response serveFileListPage() {
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>\n");
        html.append("<html lang=\"zh-CN\">\n");
        html.append("<head>\n");
        html.append("<meta charset='UTF-8'>\n");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n");
        html.append("<meta http-equiv=\"Cache-Control\" content=\"no-cache, no-store, must-revalidate\">\n");
        html.append("<meta http-equiv=\"Pragma\" content=\"no-cache\">\n");
        html.append("<meta http-equiv=\"Expires\" content=\"0\">\n");
        html.append("<title>文件传输 - EVCam</title>\n");
        html.append(getMacOSStyles());
        html.append(getFileBrowserStyles());
        html.append(getCombinedScript());
        html.append("</head>\n");
        html.append("<body>\n");

        // 微信提示框
        html.append("<div id=\"wechat-tip\" class=\"wechat-tip\" style=\"display:none;\" onclick=\"this.style.display='none'\">\n");
        html.append("<div class=\"wechat-tip-content\">\n");
        html.append("<div class=\"wechat-tip-icon\">&#8599;</div>\n");
        html.append("<div class=\"wechat-tip-text\">请点击右上角菜单<br>选择\"在浏览器打开\"以下载文件</div>\n");
        html.append("</div>\n");
        html.append("</div>\n");

        // macOS 风格窗口
        html.append("<div class=\"window\">\n");

        // 标题栏
        html.append("<div class=\"titlebar\">\n");
        html.append("<div class=\"traffic-lights\">\n");
        html.append("<span class=\"light close\"></span>\n");
        html.append("<span class=\"light minimize\"></span>\n");
        html.append("<span class=\"light maximize\"></span>\n");
        html.append("</div>\n");
        html.append("<div class=\"title\">文件传输</div>\n");
        html.append("</div>\n");

        // 标签页切换
        html.append("<div class=\"tabs\">\n");
        html.append("<div class=\"tab active\" onclick=\"switchTab('preset')\" id=\"tab-preset\">预设文件</div>\n");
        html.append("<div class=\"tab\" onclick=\"switchTab('browser')\" id=\"tab-browser\">文件浏览</div>\n");
        html.append("</div>\n");

        // 预设文件列表
        html.append("<div class=\"tab-content\" id=\"content-preset\">\n");
        html.append("<div class=\"toolbar\">\n");
        html.append("<span class=\"toolbar-item\">").append(pendingFiles.size()).append(" 个项目</span>\n");
        html.append("</div>\n");
        html.append("<div class=\"file-list\" id=\"preset-file-list\">\n");

        if (pendingFiles != null && !pendingFiles.isEmpty()) {
            for (Map.Entry<String, File> entry : pendingFiles.entrySet()) {
                File file = entry.getValue();
                if (file != null && file.exists()) {
                    String fileUrl;
                    String fileId;
                    try {
                        fileId = URLEncoder.encode(entry.getKey(), "UTF-8");
                        fileUrl = "/download/" + fileId;
                    } catch (Exception e) {
                        AppLog.e(TAG, "URL编码失败: " + entry.getKey(), e);
                        continue;
                    }
                    String fileSize = formatFileSize(file.length());
                    String fileIcon = getFileIcon(file.getName());
                    String fileName = escapeHtml(file.getName());
                    String ext = file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase();
                    boolean isImage = ext.matches("jpg|jpeg|png|gif|bmp|webp");
                    boolean isVideo = ext.matches("mp4|avi|mov|mkv|flv|wmv");
                    boolean isAudio = ext.matches("mp3|wav|aac|ogg|flac|m4a");
                    boolean isPdf = ext.equals("pdf");
                    boolean canPreview = isImage || isVideo || isAudio || isPdf;

                    // 文件项容器
                    html.append("<div class=\"file-item\" style=\"display:flex;align-items:center;padding:12px 16px;border-bottom:1px solid #eee;\">\n");

                    // 左侧文件信息（点击查看）
                    html.append("<div onclick=\"");
                    if (canPreview) {
                        html.append("previewPresetFile('").append(fileId).append("','").append(fileName.replace("'", "\\'")).append("')");
                    } else {
                        html.append("downloadPresetFile('").append(fileId).append("','").append(fileName.replace("'", "\\'")).append("')");
                    }
                    html.append("\" style=\"display:flex;align-items:center;flex:1;cursor:pointer;\">\n");
                    html.append("<div class=\"file-icon\">").append(fileIcon).append("</div>\n");
                    html.append("<div class=\"file-info\">\n");
                    html.append("<div class=\"file-name\">").append(fileName).append("</div>\n");
                    html.append("<div class=\"file-size\">").append(fileSize).append("</div>\n");
                    html.append("</div>\n");
                    html.append("</div>\n");

                    // 右侧按钮区域
                    html.append("<div style=\"display:flex;gap:8px;\">\n");

                    // 查看按钮（仅可预览的文件显示）
                    if (canPreview) {
                        html.append("<button onclick=\"event.stopPropagation();previewPresetFile('").append(fileId).append("','").append(fileName.replace("'", "\\'")).append("')\" style=\"padding:6px 12px;background:#4CAF50;color:white;border:none;border-radius:4px;font-size:12px;cursor:pointer;\">查看</button>\n");
                    }

                    // 下载按钮
                    html.append("<button onclick=\"event.stopPropagation();downloadPresetFile('").append(fileId).append("','").append(fileName.replace("'", "\\'")).append("')\" style=\"padding:6px 12px;background:#007aff;color:white;border:none;border-radius:4px;font-size:12px;cursor:pointer;\">下载</button>\n");

                    html.append("</div>\n"); // 按钮区域结束
                    html.append("</div>\n"); // 文件项结束
                }
            }
        } else {
            html.append("<div class=\"empty-state\">\n");
            html.append("<div class=\"empty-icon\">&#128193;</div>\n");
            html.append("<div class=\"empty-text\">暂无预设文件</div>\n");
            html.append("</div>\n");
        }

        html.append("</div>\n"); // file-list
        html.append("</div>\n"); // tab-content preset

        // 文件浏览器
        html.append("<div class=\"tab-content\" id=\"content-browser\" style=\"display:none;\">\n");
        html.append("<div class=\"browser-toolbar\">\n");
        html.append("<button class=\"btn-back\" onclick=\"goBack()\" id=\"btn-back\">&#8592; 返回</button>\n");
        html.append("<span class=\"current-path\" id=\"current-path\">/</span>\n");
        html.append("</div>\n");
        html.append("<div class=\"file-list\" id=\"browser-file-list\">\n");
        html.append("<div class=\"loading\">加载中...</div>\n");
        html.append("</div>\n"); // file-list
        html.append("</div>\n"); // tab-content browser

        html.append("</div>\n"); // window
        html.append("</body>\n");
        html.append("</html>");

        Response response = newFixedLengthResponse(Response.Status.OK, "text/html; charset=UTF-8", html.toString());
        addCorsHeaders(response);
        return response;
    }

    private String getMacOSStyles() {
        return "<style>\n" +
            "* { margin: 0; padding: 0; box-sizing: border-box; }\n" +
            "body {\n" +
            "  font-family: -apple-system, BlinkMacSystemFont, sans-serif;\n" +
            "  background: #f5f5f7;\n" +
            "  min-height: 100vh;\n" +
            "  padding: 0;\n" +
            "}\n" +
            ".window {\n" +
            "  background: #fff;\n" +
            "  width: 100%;\n" +
            "  height: 100vh;\n" +
            "  overflow: hidden;\n" +
            "  display: flex;\n" +
            "  flex-direction: column;\n" +
            "}\n" +
            ".titlebar {\n" +
            "  background: #f6f6f6;\n" +
            "  padding: 12px 16px;\n" +
            "  display: flex;\n" +
            "  align-items: center;\n" +
            "  border-bottom: 1px solid #ddd;\n" +
            "}\n" +
            ".traffic-lights {\n" +
            "  display: flex;\n" +
            "  gap: 8px;\n" +
            "  margin-right: 16px;\n" +
            "}\n" +
            ".light {\n" +
            "  width: 12px;\n" +
            "  height: 12px;\n" +
            "  border-radius: 50%;\n" +
            "}\n" +
            ".light.close { background: #ff5f57; }\n" +
            ".light.minimize { background: #febc2e; }\n" +
            ".light.maximize { background: #28c840; }\n" +
            ".title {\n" +
            "  flex: 1;\n" +
            "  text-align: center;\n" +
            "  font-size: 13px;\n" +
            "  font-weight: 600;\n" +
            "  color: #333;\n" +
            "  margin-right: 52px;\n" +
            "}\n" +
            ".toolbar {\n" +
            "  background: #f5f5f5;\n" +
            "  padding: 10px 16px;\n" +
            "  font-size: 12px;\n" +
            "  color: #666;\n" +
            "  border-bottom: 1px solid #ddd;\n" +
            "}\n" +
            ".file-list {\n" +
            "  flex: 1;\n" +
            "  overflow-y: auto;\n" +
            "}\n" +
            ".file-item {\n" +
            "  display: flex;\n" +
            "  align-items: center;\n" +
            "  padding: 12px 16px;\n" +
            "  text-decoration: none;\n" +
            "  color: inherit;\n" +
            "  border-bottom: 1px solid #eee;\n" +
            "}\n" +
            ".file-item:hover {\n" +
            "  background: #e8f4ff;\n" +
            "}\n" +
            ".file-icon {\n" +
            "  width: 40px;\n" +
            "  height: 40px;\n" +
            "  background: #007aff;\n" +
            "  border-radius: 8px;\n" +
            "  display: flex;\n" +
            "  align-items: center;\n" +
            "  justify-content: center;\n" +
            "  font-size: 20px;\n" +
            "  margin-right: 12px;\n" +
            "  flex-shrink: 0;\n" +
            "}\n" +
            ".file-info {\n" +
            "  flex: 1;\n" +
            "  min-width: 0;\n" +
            "}\n" +
            ".file-name {\n" +
            "  font-size: 14px;\n" +
            "  font-weight: 500;\n" +
            "  color: #1a1a1a;\n" +
            "  white-space: nowrap;\n" +
            "  overflow: hidden;\n" +
            "  text-overflow: ellipsis;\n" +
            "}\n" +
            ".file-size {\n" +
            "  font-size: 12px;\n" +
            "  color: #888;\n" +
            "  margin-top: 2px;\n" +
            "}\n" +
            ".download-icon {\n" +
            "  width: 32px;\n" +
            "  height: 32px;\n" +
            "  background: #007aff;\n" +
            "  color: white;\n" +
            "  border-radius: 6px;\n" +
            "  display: flex;\n" +
            "  align-items: center;\n" +
            "  justify-content: center;\n" +
            "  font-size: 16px;\n" +
            "  flex-shrink: 0;\n" +
            "}\n" +
            ".empty-state {\n" +
            "  padding: 60px 20px;\n" +
            "  text-align: center;\n" +
            "}\n" +
            ".empty-icon {\n" +
            "  font-size: 48px;\n" +
            "  margin-bottom: 12px;\n" +
            "}\n" +
            ".empty-text {\n" +
            "  font-size: 14px;\n" +
            "  color: #999;\n" +
            "}\n" +
            "/* 微信提示样式 */\n" +
            ".wechat-tip {\n" +
            "  position: fixed;\n" +
            "  top: 0;\n" +
            "  left: 0;\n" +
            "  right: 0;\n" +
            "  bottom: 0;\n" +
            "  background: rgba(0,0,0,0.85);\n" +
            "  z-index: 9999;\n" +
            "  display: flex;\n" +
            "  align-items: flex-start;\n" +
            "  justify-content: flex-end;\n" +
            "  padding: 20px;\n" +
            "}\n" +
            ".wechat-tip-content {\n" +
            "  background: #fff;\n" +
            "  border-radius: 12px;\n" +
            "  padding: 20px 30px;\n" +
            "  text-align: center;\n" +
            "  margin-top: 60px;\n" +
            "  margin-right: 10px;\n" +
            "  box-shadow: 0 4px 20px rgba(0,0,0,0.3);\n" +
            "  animation: slideDown 0.3s ease;\n" +
            "}\n" +
            ".wechat-tip-icon {\n" +
            "  font-size: 36px;\n" +
            "  margin-bottom: 10px;\n" +
            "  color: #07C160;\n" +
            "}\n" +
            ".wechat-tip-text {\n" +
            "  font-size: 16px;\n" +
            "  color: #333;\n" +
            "  line-height: 1.6;\n" +
            "}\n" +
            "@keyframes slideDown {\n" +
            "  from { opacity: 0; transform: translateY(-20px); }\n" +
            "  to { opacity: 1; transform: translateY(0); }\n" +
            "}\n" +
            "/* Toast 提示样式 */\n" +
            ".toast {\n" +
            "  position: fixed;\n" +
            "  bottom: 80px;\n" +
            "  left: 50%;\n" +
            "  transform: translateX(-50%) translateY(20px);\n" +
            "  background: rgba(0,0,0,0.8);\n" +
            "  color: #fff;\n" +
            "  padding: 12px 24px;\n" +
            "  border-radius: 24px;\n" +
            "  font-size: 14px;\n" +
            "  z-index: 10000;\n" +
            "  opacity: 0;\n" +
            "  transition: all 0.3s ease;\n" +
            "  pointer-events: none;\n" +
            "  white-space: nowrap;\n" +
            "}\n" +
            ".toast.show {\n" +
            "  opacity: 1;\n" +
            "  transform: translateX(-50%) translateY(0);\n" +
            "}\n" +
            "</style>\n";
    }

    private String getFileBrowserStyles() {
        return "<style>\n" +
            ".tabs {\n" +
            "  display: flex;\n" +
            "  background: #f5f5f5;\n" +
            "  border-bottom: 1px solid #ddd;\n" +
            "}\n" +
            ".tab {\n" +
            "  flex: 1;\n" +
            "  padding: 12px;\n" +
            "  text-align: center;\n" +
            "  cursor: pointer;\n" +
            "  font-size: 14px;\n" +
            "  color: #666;\n" +
            "  transition: all 0.2s;\n" +
            "}\n" +
            ".tab.active {\n" +
            "  color: #007aff;\n" +
            "  border-bottom: 2px solid #007aff;\n" +
            "  background: #fff;\n" +
            "}\n" +
            ".tab-content {\n" +
            "  flex: 1;\n" +
            "  overflow: hidden;\n" +
            "  display: flex;\n" +
            "  flex-direction: column;\n" +
            "}\n" +
            ".browser-toolbar {\n" +
            "  display: flex;\n" +
            "  align-items: center;\n" +
            "  padding: 10px 16px;\n" +
            "  background: #f5f5f5;\n" +
            "  border-bottom: 1px solid #ddd;\n" +
            "  gap: 12px;\n" +
            "}\n" +
            ".btn-back {\n" +
            "  padding: 6px 12px;\n" +
            "  border: 1px solid #ddd;\n" +
            "  background: #fff;\n" +
            "  border-radius: 6px;\n" +
            "  font-size: 13px;\n" +
            "  cursor: pointer;\n" +
            "  color: #333;\n" +
            "}\n" +
            ".btn-back:disabled {\n" +
            "  opacity: 0.5;\n" +
            "  cursor: not-allowed;\n" +
            "}\n" +
            ".current-path {\n" +
            "  flex: 1;\n" +
            "  font-size: 13px;\n" +
            "  color: #666;\n" +
            "  overflow: hidden;\n" +
            "  text-overflow: ellipsis;\n" +
            "  white-space: nowrap;\n" +
            "}\n" +
            ".loading {\n" +
            "  padding: 40px;\n" +
            "  text-align: center;\n" +
            "  color: #999;\n" +
            "}\n" +
            ".folder-icon {\n" +
            "  background: #FFB800 !important;\n" +
            "}\n" +
            ".file-item-folder {\n" +
            "  cursor: pointer;\n" +
            "}\n" +
            "</style>\n";
    }

    private String getFileBrowserScript() {
        return "<script>\n" +
            "var currentPath = '/';\n" +
            "var pathHistory = [];\n" +
            "\n" +
            "function switchTab(tabName) {\n" +
            "  var i;\n" +
            "  var tabs = document.getElementsByClassName('tab');\n" +
            "  for (i = 0; i < tabs.length; i++) {\n" +
            "    tabs[i].className = tabs[i].className.replace(' active', '');\n" +
            "  }\n" +
            "  var contents = document.getElementsByClassName('tab-content');\n" +
            "  for (i = 0; i < contents.length; i++) {\n" +
            "    contents[i].style.display = 'none';\n" +
            "  }\n" +
            "  document.getElementById('tab-' + tabName).className += ' active';\n" +
            "  document.getElementById('content-' + tabName).style.display = 'block';\n" +
            "  if (tabName === 'browser') {\n" +
            "    loadDirectory('/');\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "function loadDirectory(path) {\n" +
            "  var listEl = document.getElementById('browser-file-list');\n" +
            "  listEl.innerHTML = '<div style=\"padding:40px;text-align:center;color:#999;\">加载中...</div>';\n" +
            "  var xhr = new XMLHttpRequest();\n" +
            "  xhr.onreadystatechange = function() {\n" +
            "    if (xhr.readyState == 4) {\n" +
            "      if (xhr.status == 200) {\n" +
            "        var data = JSON.parse(xhr.responseText);\n" +
            "        if (data.success) {\n" +
            "          currentPath = data.path;\n" +
            "          document.getElementById('current-path').innerHTML = data.path;\n" +
            "          renderItems(data.items);\n" +
            "        } else {\n" +
            "          listEl.innerHTML = '<div style=\"padding:40px;text-align:center;\">加载失败</div>';\n" +
            "        }\n" +
            "      } else {\n" +
            "        listEl.innerHTML = '<div style=\"padding:40px;text-align:center;\">错误: ' + xhr.status + '</div>';\n" +
            "      }\n" +
            "    }\n" +
            "  };\n" +
            "  xhr.open('GET', '/api/browse?path=' + encodeURIComponent(path), true);\n" +
            "  xhr.send();\n" +
            "}\n" +
            "\n" +
            "function renderItems(items) {\n" +
            "  var listEl = document.getElementById('browser-file-list');\n" +
            "  if (!items || items.length == 0) {\n" +
            "    listEl.innerHTML = '<div style=\"padding:40px;text-align:center;\"><div style=\"font-size:48px;margin-bottom:12px;\">&#128193;</div><div style=\"color:#999;\">空文件夹</div></div>';\n" +
            "    return;\n" +
            "  }\n" +
            "  var html = '';\n" +
            "  for (var i = 0; i < items.length; i++) {\n" +
            "    var item = items[i];\n" +
            "    if (item.isDirectory) {\n" +
            "      html += '<div onclick=\"enterFolder(this.getAttribute(\\'data-path\\'))\" data-path=\"' + encodeURIComponent(item.path) + '\" style=\"display:flex;align-items:center;padding:12px 16px;border-bottom:1px solid #eee;cursor:pointer;\">';\n" +
            "      html += '<div style=\"width:40px;height:40px;background:#FFB800;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:20px;margin-right:12px;\">&#128194;</div>';\n" +
            "      html += '<div style=\"flex:1;\"><div style=\"font-size:14px;color:#333;\">' + item.name + '</div><div style=\"font-size:12px;color:#888;margin-top:2px;\">文件夹</div></div>';\n" +
            "      html += '<div style=\"font-size:20px;color:#999;\">&#8250;</div>';\n" +
            "      html += '</div>';\n" +
            "    } else {\n" +
            "      var filePath = encodeURIComponent(item.path);\n" +
            "      var fileName = item.name;\n" +
            "      var fileSize = item.sizeFormatted;\n" +
            "      var fileIcon = getIcon(fileName);\n" +
            "      var ext = fileName.split('.').pop().toLowerCase();\n" +
            "      var isImage = ['jpg','jpeg','png','gif','bmp','webp'].indexOf(ext) >= 0;\n" +
            "      var isVideo = ['mp4','avi','mov','mkv','flv','wmv'].indexOf(ext) >= 0;\n" +
            "      var isAudio = ['mp3','wav','aac','ogg','flac','m4a'].indexOf(ext) >= 0;\n" +
            "      var isPdf = ext === 'pdf';\n" +
            "      var canPreview = isImage || isVideo || isAudio || isPdf;\n" +
            "      \n" +
            "      html += '<div style=\"display:flex;align-items:center;padding:12px 16px;border-bottom:1px solid #eee;\">';\n" +
            "      html += '<div onclick=\"' + (canPreview ? 'previewFile' : 'downloadDirect') + '(\\'' + filePath + '\\',\\'' + fileName.replace(/\\'/g, \"\\\\'\") + '\\')\" style=\"display:flex;align-items:center;flex:1;cursor:pointer;\">';\n" +
            "      html += '<div style=\"width:40px;height:40px;background:#007aff;border-radius:8px;display:flex;align-items:center;justify-content:center;font-size:20px;margin-right:12px;color:white;\">' + fileIcon + '</div>';\n" +
            "      html += '<div style=\"flex:1;\"><div style=\"font-size:14px;color:#333;\">' + fileName + '</div><div style=\"font-size:12px;color:#888;margin-top:2px;\">' + fileSize + '</div></div>';\n" +
            "      html += '</div>';\n" +
            "      html += '<div style=\"display:flex;gap:8px;\">';\n" +
            "      if (canPreview) {\n" +
            "        html += '<button onclick=\"event.stopPropagation();previewFile(\\'' + filePath + '\\',\\'' + fileName.replace(/\\'/g, \"\\\\'\") + '\\')\" style=\"padding:6px 12px;background:#4CAF50;color:white;border:none;border-radius:4px;font-size:12px;cursor:pointer;\">查看</button>';\n" +
            "      }\n" +
            "      html += '<button onclick=\"event.stopPropagation();downloadDirect(\\'' + filePath + '\\',\\'' + fileName.replace(/\\'/g, \"\\\\'\") + '\\')\" style=\"padding:6px 12px;background:#007aff;color:white;border:none;border-radius:4px;font-size:12px;cursor:pointer;\">下载</button>';\n" +
            "      html += '</div>';\n" +
            "      html += '</div>';\n" +
            "    }\n" +
            "  }\n" +
            "  listEl.innerHTML = html;\n" +
            "}\n" +
            "\n" +
            "// 预设文件预览\n" +
            "function previewPresetFile(fileId, name) {\n" +
            "  var ext = name.split('.').pop().toLowerCase();\n" +
            "  var isImage = ['jpg','jpeg','png','gif','bmp','webp'].indexOf(ext) >= 0;\n" +
            "  var isVideo = ['mp4','avi','mov','mkv','flv','wmv'].indexOf(ext) >= 0;\n" +
            "  var isAudio = ['mp3','wav','aac','ogg','flac','m4a'].indexOf(ext) >= 0;\n" +
            "  var isPdf = ext === 'pdf';\n" +
            "  var url = '/download/' + encodeURIComponent(fileId);\n" +
            "  \n" +
            "  var previewHtml = '';\n" +
            "  if (isImage) {\n" +
            "    previewHtml = '<img src=\"' + url + '\" style=\"max-width:100%;max-height:70vh;display:block;margin:0 auto;\">';\n" +
            "  } else if (isVideo) {\n" +
            "    previewHtml = '<video controls style=\"max-width:100%;max-height:70vh;display:block;margin:0 auto;\" src=\"' + url + '\"></video>';\n" +
            "  } else if (isAudio) {\n" +
            "    previewHtml = '<audio controls style=\"width:100%;\" src=\"' + url + '\"></audio>';\n" +
            "  } else if (isPdf) {\n" +
            "    previewHtml = '<iframe src=\"' + url + '\" style=\"width:100%;height:70vh;border:none;\"></iframe>';\n" +
            "  }\n" +
            "  \n" +
            "  var overlay = document.createElement('div');\n" +
            "  overlay.id = 'preview-overlay';\n" +
            "  overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.9);z-index:10000;padding:20px;overflow:auto;';\n" +
            "  overlay.innerHTML = '<div style=\"text-align:right;margin-bottom:10px;\"><span onclick=\"closePreview()\" style=\"color:white;font-size:24px;cursor:pointer;padding:10px;\">&#10005;</span></div><div style=\"text-align:center;color:white;margin-bottom:10px;\">' + name + '</div>' + previewHtml;\n" +
            "  document.body.appendChild(overlay);\n" +
            "}\n" +
            "\n" +
            "// 预设文件下载\n" +
            "function downloadPresetFile(fileId, name) {\n" +
            "  var url = '/download/' + encodeURIComponent(fileId);\n" +
            "  if (!isWeixin()) {\n" +
            "    var a = document.createElement('a');\n" +
            "    a.href = url;\n" +
            "    a.download = name;\n" +
            "    a.click();\n" +
            "    return;\n" +
            "  }\n" +
            "  var xhr = new XMLHttpRequest();\n" +
            "  xhr.open('GET', url, true);\n" +
            "  xhr.responseType = 'blob';\n" +
            "  xhr.onload = function() {\n" +
            "    if (xhr.status == 200) {\n" +
            "      var blobUrl = URL.createObjectURL(xhr.response);\n" +
            "      var a = document.createElement('a');\n" +
            "      a.href = blobUrl;\n" +
            "      a.download = name;\n" +
            "      a.click();\n" +
            "      setTimeout(function() { URL.revokeObjectURL(blobUrl); }, 5000);\n" +
            "    }\n" +
            "  };\n" +
            "  xhr.send();\n" +
            "}\n" +
            "\n" +
            "// 文件浏览器预览\n" +
            "function previewFile(path, name) {\n" +
            "  var ext = name.split('.').pop().toLowerCase();\n" +
            "  var isImage = ['jpg','jpeg','png','gif','bmp','webp'].indexOf(ext) >= 0;\n" +
            "  var isVideo = ['mp4','avi','mov','mkv','flv','wmv'].indexOf(ext) >= 0;\n" +
            "  var isAudio = ['mp3','wav','aac','ogg','flac','m4a'].indexOf(ext) >= 0;\n" +
            "  var isPdf = ext === 'pdf';\n" +
            "  var url = '/download/' + path;\n" +
            "  \n" +
            "  var previewHtml = '';\n" +
            "  if (isImage) {\n" +
            "    previewHtml = '<img src=\"' + url + '\" style=\"max-width:100%;max-height:70vh;display:block;margin:0 auto;\">';\n" +
            "  } else if (isVideo) {\n" +
            "    previewHtml = '<video controls style=\"max-width:100%;max-height:70vh;display:block;margin:0 auto;\" src=\"' + url + '\"></video>';\n" +
            "  } else if (isAudio) {\n" +
            "    previewHtml = '<audio controls style=\"width:100%;\" src=\"' + url + '\"></audio>';\n" +
            "  } else if (isPdf) {\n" +
            "    previewHtml = '<iframe src=\"' + url + '\" style=\"width:100%;height:70vh;border:none;\"></iframe>';\n" +
            "  }\n" +
            "  \n" +
            "  var overlay = document.createElement('div');\n" +
            "  overlay.id = 'preview-overlay';\n" +
            "  overlay.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.9);z-index:10000;padding:20px;overflow:auto;';\n" +
            "  overlay.innerHTML = '<div style=\"text-align:right;margin-bottom:10px;\"><span onclick=\"closePreview()\" style=\"color:white;font-size:24px;cursor:pointer;padding:10px;\">&#10005;</span></div><div style=\"text-align:center;color:white;margin-bottom:10px;\">' + name + '</div>' + previewHtml;\n" +
            "  document.body.appendChild(overlay);\n" +
            "}\n" +
            "\n" +
            "function closePreview() {\n" +
            "  var overlay = document.getElementById('preview-overlay');\n" +
            "  if (overlay) overlay.parentNode.removeChild(overlay);\n" +
            "}\n" +
            "\n" +
            "function downloadDirect(path, name) {\n" +
            "  var url = '/download/' + path;\n" +
            "  if (!isWeixin()) {\n" +
            "    var a = document.createElement('a');\n" +
            "    a.href = url;\n" +
            "    a.download = name;\n" +
            "    a.click();\n" +
            "    return;\n" +
            "  }\n" +
            "  var xhr = new XMLHttpRequest();\n" +
            "  xhr.open('GET', url, true);\n" +
            "  xhr.responseType = 'blob';\n" +
            "  xhr.onload = function() {\n" +
            "    if (xhr.status == 200) {\n" +
            "      var blobUrl = URL.createObjectURL(xhr.response);\n" +
            "      var a = document.createElement('a');\n" +
            "      a.href = blobUrl;\n" +
            "      a.download = name;\n" +
            "      a.click();\n" +
            "      setTimeout(function() { URL.revokeObjectURL(blobUrl); }, 5000);\n" +
            "    }\n" +
            "  };\n" +
            "  xhr.send();\n" +
            "}\n" +
            "\n" +
            "function enterFolder(encodedPath) {\n" +
            "  pathHistory.push(currentPath);\n" +
            "  loadDirectory(decodeURIComponent(encodedPath));\n" +
            "}\n" +
            "\n" +
            "function goBack() {\n" +
            "  if (pathHistory.length > 0) {\n" +
            "    loadDirectory(pathHistory.pop());\n" +
            "  }\n" +
            "}\n" +
            "\n" +
            "function getIcon(name) {\n" +
            "  var ext = name.split('.').pop().toLowerCase();\n" +
            "  if (['mp4','avi','mov','mkv'].indexOf(ext) >= 0) return '&#127909;';\n" +
            "  if (['jpg','jpeg','png','gif'].indexOf(ext) >= 0) return '&#128247;';\n" +
            "  if (['mp3','wav','aac'].indexOf(ext) >= 0) return '&#127925;';\n" +
            "  if (ext === 'pdf') return '&#128196;';\n" +
            "  if (['zip','rar','7z'].indexOf(ext) >= 0) return '&#128230;';\n" +
            "  return '&#128196;';\n" +
            "}\n" +
            "\n" +
            "function isWeixin() {\n" +
            "  return navigator.userAgent.toLowerCase().indexOf('micromessenger') >= 0;\n" +
            "}\n" +
            "</script>\n";
    }

    private String getCombinedScript() {
        return getFileBrowserScript();
    }

    private String getFileIcon(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".mp4") || lower.endsWith(".avi") || lower.endsWith(".mov") || lower.endsWith(".mkv")) {
            return "&#127909;"; // 🎬
        } else if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".gif")) {
            return "&#128247;"; // 📷
        } else if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".aac")) {
            return "&#127925;"; // 🎵
        } else if (lower.endsWith(".pdf")) {
            return "&#128196;"; // 📄
        } else if (lower.endsWith(".zip") || lower.endsWith(".rar") || lower.endsWith(".7z")) {
            return "&#128230;"; // 📦
        } else {
            return "&#128196;"; // 📄
        }
    }

    private String escapeHtml(String str) {
        return str.replace("&", "&amp;")
                  .replace("<", "&lt;")
                  .replace(">", "&gt;")
                  .replace("\"", "&quot;")
                  .replace("'", "&#x27;");
    }

    private Response serveFileListJson() {
        StringBuilder json = new StringBuilder();
        json.append("{\"success\":true,\"files\":[");
        
        boolean first = true;
        if (pendingFiles != null) {
            for (Map.Entry<String, File> entry : pendingFiles.entrySet()) {
                File file = entry.getValue();
                if (file == null || !file.exists()) continue;

                String fileUrl;
                try {
                    fileUrl = "/download/" + URLEncoder.encode(entry.getKey(), "UTF-8");
                } catch (Exception e) {
                    AppLog.e(TAG, "URL编码失败: " + entry.getKey(), e);
                    continue;
                }

                if (!first) json.append(",");
                first = false;

                json.append("{");
                json.append("\"id\":\"").append(escapeJson(entry.getKey())).append("\",");
                json.append("\"name\":\"").append(escapeJson(file.getName())).append("\",");
                json.append("\"size\":").append(file.length()).append(",");
                json.append("\"sizeFormatted\":\"").append(formatFileSize(file.length())).append("\",");
                json.append("\"downloadUrl\":\"").append(fileUrl).append("\"");
                json.append("}");
            }
        }
        
        json.append("]}");
        
        Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", json.toString());
        addCorsHeaders(response);
        return response;
    }

    private Response serveBrowseRequest(String path) {
        StringBuilder json = new StringBuilder();
        json.append("{");
        json.append("\"success\":true,");
        json.append("\"path\":\"").append(escapeJson(path)).append("\",");
        json.append("\"items\":[");

        File dir;
        if ("/".equals(path) || path.isEmpty()) {
            // 根目录 - 只显示系统存储设备
            boolean first = true;
            
            // 尝试添加外部存储根目录（Android 10+ 可能无法访问）
            try {
                File externalStorage = android.os.Environment.getExternalStorageDirectory();
                if (externalStorage != null && externalStorage.exists() && externalStorage.canRead()) {
                    File[] rootFiles = externalStorage.listFiles();
                    if (rootFiles != null && rootFiles.length > 0) {
                        if (!first) json.append(",");
                        first = false;
                        json.append("{");
                        json.append("\"name\":\"手机存储\",");
                        json.append("\"path\":\"").append(escapeJson(externalStorage.getAbsolutePath())).append("\",");
                        json.append("\"isDirectory\":true,");
                        json.append("\"size\":0,");
                        json.append("\"sizeFormatted\":\"-\"");
                        json.append("}");
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "无法访问外部存储: " + e.getMessage());
            }
            
            // 添加 /storage/emulated 路径（车机常用路径）
            try {
                File emulatedStorage = new File("/storage/emulated");
                if (emulatedStorage.exists() && emulatedStorage.isDirectory() && emulatedStorage.canRead()) {
                    // 检查是否有子目录（如 0、10 等用户目录）
                    File[] emulatedDirs = emulatedStorage.listFiles();
                    if (emulatedDirs != null && emulatedDirs.length > 0) {
                        for (File userDir : emulatedDirs) {
                            if (userDir.isDirectory() && !userDir.isHidden() && userDir.canRead()) {
                                if (!first) json.append(",");
                                first = false;
                                json.append("{");
                                json.append("\"name\":\"存储空间 " + escapeJson(userDir.getName()) + "\",");
                                json.append("\"path\":\"").append(escapeJson(userDir.getAbsolutePath())).append("\",");
                                json.append("\"isDirectory\":true,");
                                json.append("\"size\":0,");
                                json.append("\"sizeFormatted\":\"-\"");
                                json.append("}");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "无法访问 /storage/emulated: " + e.getMessage());
            }
            
            // 添加 /storage 目录下的其他存储设备
            try {
                File storageDir = new File("/storage");
                if (storageDir.exists() && storageDir.isDirectory() && storageDir.canRead()) {
                    File[] storageList = storageDir.listFiles();
                    if (storageList != null) {
                        for (File s : storageList) {
                            // 跳过已处理的 emulated 目录和 self 目录
                            String name = s.getName();
                            if (name.equals("emulated") || name.equals("self")) continue;
                            if (s.isDirectory() && !s.isHidden() && s.canRead()) {
                                if (!first) json.append(",");
                                first = false;
                                json.append("{");
                                json.append("\"name\":\"").append(escapeJson(name)).append("\",");
                                json.append("\"path\":\"").append(escapeJson(s.getAbsolutePath())).append("\",");
                                json.append("\"isDirectory\":true,");
                                json.append("\"size\":0,");
                                json.append("\"sizeFormatted\":\"-\"");
                                json.append("}");
                            }
                        }
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "无法访问 /storage: " + e.getMessage());
            }
            
            // 自动检测所有存储设备（包括USB、SD卡等）
            first = appendStorageDevices(json, first);
            
        } else {
            dir = new File(path);
            if (dir.exists() && dir.isDirectory() && dir.canRead()) {
                File[] files = null;
                try {
                    files = dir.listFiles();
                } catch (Exception e) {
                    AppLog.e(TAG, "无法列出目录内容: " + path);
                }
                
                if (files != null) {
                    // 先排序：文件夹在前，文件在后
                    java.util.List<File> fileList = new java.util.ArrayList<>();
                    for (File f : files) {
                        if (!f.isHidden()) {
                            fileList.add(f);
                        }
                    }
                    
                    // 排序
                    fileList.sort((a, b) -> {
                        if (a.isDirectory() && !b.isDirectory()) return -1;
                        if (!a.isDirectory() && b.isDirectory()) return 1;
                        return a.getName().compareToIgnoreCase(b.getName());
                    });
                    
                    boolean first = true;
                    for (File file : fileList) {
                        if (!first) json.append(",");
                        first = false;
                        
                        String filePath = file.getAbsolutePath();
                        String fileName = file.getName();
                        boolean isDir = file.isDirectory();
                        long size = isDir ? 0 : file.length();
                        
                        json.append("{");
                        json.append("\"name\":\"").append(escapeJson(fileName)).append("\",");
                        json.append("\"path\":\"").append(escapeJson(filePath)).append("\",");
                        json.append("\"isDirectory\":").append(isDir).append(",");
                        json.append("\"size\":").append(size).append(",");
                        json.append("\"sizeFormatted\":\"").append(isDir ? "-" : formatFileSize(size)).append("\"");
                        json.append("}");
                    }
                }
            }
        }
        
        json.append("]}");
        
        Response response = newFixedLengthResponse(Response.Status.OK, "application/json; charset=UTF-8", json.toString());
        addCorsHeaders(response);
        return response;
    }
    
    /**
     * 自动检测所有可用的存储设备
     */
    private boolean appendStorageDevices(StringBuilder json, boolean first) {
        // 使用 StorageManager 获取所有存储卷（Android 6.0+）
        try {
            android.os.storage.StorageManager storageManager = (android.os.storage.StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (storageManager != null) {
                // 获取所有存储卷
                java.util.List<?> storageVolumes = storageManager.getStorageVolumes();
                if (storageVolumes != null) {
                    for (Object volume : storageVolumes) {
                        try {
                            // 使用反射获取存储卷信息
                            java.lang.reflect.Method getPathMethod = volume.getClass().getMethod("getPath");
                            java.lang.reflect.Method getDescriptionMethod = volume.getClass().getMethod("getDescription", Context.class);
                            java.lang.reflect.Method isRemovableMethod = volume.getClass().getMethod("isRemovable");
                            java.lang.reflect.Method getStateMethod = volume.getClass().getMethod("getState");
                            
                            String path = (String) getPathMethod.invoke(volume);
                            String description = (String) getDescriptionMethod.invoke(volume, context);
                            boolean isRemovable = (Boolean) isRemovableMethod.invoke(volume);
                            String state = (String) getStateMethod.invoke(volume);
                            
                            // 只添加已挂载的存储设备
                            if (path != null && !path.isEmpty() && "mounted".equals(state)) {
                                File storageFile = new File(path);
                                if (storageFile.exists() && storageFile.canRead()) {
                                    // 检查是否已经添加过（避免重复）
                                    if (!isPathAlreadyAdded(json, path)) {
                                        if (!first) json.append(",");
                                        first = false;
                                        
                                        String displayName = isRemovable ? "USB/SD卡: " + description : description;
                                        json.append("{");
                                        json.append("\"name\":\"").append(escapeJson(displayName)).append("\",");
                                        json.append("\"path\":\"").append(escapeJson(path)).append("\",");
                                        json.append("\"isDirectory\":true,");
                                        json.append("\"size\":0,");
                                        json.append("\"sizeFormatted\":\"-\"");
                                        json.append("}");
                                    }
                                }
                            }
                        } catch (Exception e) {
                            AppLog.e(TAG, "获取存储卷信息失败: " + e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "无法使用 StorageManager: " + e.getMessage());
        }
        
        // 备用方案：扫描常见的挂载点
        String[] commonMountPoints = {
            "/mnt",
            "/mnt/usb_storage",
            "/mnt/usb_storage1",
            "/mnt/usb_storage2",
            "/mnt/sdcard",
            "/mnt/external_sd",
            "/mnt/sdcard2",
            "/mnt/sdcard1",
            "/storage/usbdisk",
            "/storage/usbdisk1",
            "/storage/usbdisk2",
            "/storage/sdcard1",
            "/storage/sdcard0",
            "/storage/extSdCard",
        };
        
        for (String mountPoint : commonMountPoints) {
            try {
                File dir = new File(mountPoint);
                if (dir.exists() && dir.isDirectory() && dir.canRead() && dir.listFiles() != null) {
                    // 检查是否已经添加过
                    if (!isPathAlreadyAdded(json, mountPoint)) {
                        // 获取目录名称作为显示名称
                        String name = dir.getName();
                        if (name.equals("mnt")) {
                            // 如果是 /mnt，列出其中的子目录
                            File[] subDirs = dir.listFiles(File::isDirectory);
                            if (subDirs != null) {
                                for (File subDir : subDirs) {
                                    if (subDir.canRead() && !isPathAlreadyAdded(json, subDir.getAbsolutePath())) {
                                        if (!first) json.append(",");
                                        first = false;
                                        json.append("{");
                                        json.append("\"name\":\"").append(escapeJson(subDir.getName())).append("\",");
                                        json.append("\"path\":\"").append(escapeJson(subDir.getAbsolutePath())).append("\",");
                                        json.append("\"isDirectory\":true,");
                                        json.append("\"size\":0,");
                                        json.append("\"sizeFormatted\":\"-\"");
                                        json.append("}");
                                    }
                                }
                            }
                        } else {
                            if (!first) json.append(",");
                            first = false;
                            json.append("{");
                            json.append("\"name\":\"").append(escapeJson(name)).append("\",");
                            json.append("\"path\":\"").append(escapeJson(mountPoint)).append("\",");
                            json.append("\"isDirectory\":true,");
                            json.append("\"size\":0,");
                            json.append("\"sizeFormatted\":\"-\"");
                            json.append("}");
                        }
                    }
                }
            } catch (Exception e) {
                // 忽略无法访问的挂载点
            }
        }
        
        // 尝试读取 /proc/mounts 获取所有挂载的设备
        try {
            File mountsFile = new File("/proc/mounts");
            if (mountsFile.exists() && mountsFile.canRead()) {
                java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.FileReader(mountsFile));
                String line;
                java.util.Set<String> addedPaths = new java.util.HashSet<>();
                
                while ((line = reader.readLine()) != null) {
                    // 解析挂载点
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 2) {
                        String mountPath = parts[1];
                        String fsType = parts[2];
                        
                        // 只关注常见的文件系统类型和挂载点
                        if ((fsType.equals("vfat") || fsType.equals("ntfs") || fsType.equals("exfat") || 
                             fsType.equals("ext4") || fsType.equals("fuse")) &&
                            (mountPath.startsWith("/mnt") || mountPath.startsWith("/storage") || 
                             mountPath.startsWith("/media")) &&
                            !mountPath.contains("emulated") && !mountPath.contains("self")) {
                            
                            if (!addedPaths.contains(mountPath) && !isPathAlreadyAdded(json, mountPath)) {
                                File mountDir = new File(mountPath);
                                if (mountDir.exists() && mountDir.canRead()) {
                                    addedPaths.add(mountPath);
                                    if (!first) json.append(",");
                                    first = false;
                                    
                                    String name = mountDir.getName();
                                    if (name.isEmpty()) name = mountPath;
                                    
                                    json.append("{");
                                    json.append("\"name\":\"").append(escapeJson(name)).append("\",");
                                    json.append("\"path\":\"").append(escapeJson(mountPath)).append("\",");
                                    json.append("\"isDirectory\":true,");
                                    json.append("\"size\":0,");
                                    json.append("\"sizeFormatted\":\"-\"");
                                    json.append("}");
                                }
                            }
                        }
                    }
                }
                reader.close();
            }
        } catch (Exception e) {
            AppLog.e(TAG, "读取 /proc/mounts 失败: " + e.getMessage());
        }
        
        return first;
    }
    
    /**
     * 检查路径是否已经在 JSON 中添加过
     */
    private boolean isPathAlreadyAdded(StringBuilder json, String path) {
        String pathWithQuotes = "\"path\":\"" + escapeJson(path) + "\"";
        return json.indexOf(pathWithQuotes) >= 0;
    }

    private Response serveFile(String fileId) {
        File file = pendingFiles.get(fileId);
        
        // 如果不在预设文件中，尝试作为路径解析
        if (file == null) {
            file = new File(fileId);
            // 安全检查：确保文件存在且不是目录
            if (!file.exists() || file.isDirectory()) {
                file = null;
            }
        }
        
        if (file == null || !file.exists()) {
            Response response = newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "File not found");
            addCorsHeaders(response);
            return response;
        }

        AppLog.i(TAG, "开始传输文件: " + file.getName());
        
        if (callback != null) {
            callback.onFileRequested(file.getName());
        }

        try {
            String mimeType = getMimeType(file.getName());
            FileInputStream fis = new FileInputStream(file);
            
            Response response = newChunkedResponse(Response.Status.OK, mimeType, fis);
            response.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
            addCorsHeaders(response);
            
            if (callback != null) {
                callback.onFileTransferred(file.getName(), true);
            }
            
            return response;
        } catch (Exception e) {
            AppLog.e(TAG, "文件传输失败: " + file.getName(), e);
            if (callback != null) {
                callback.onFileTransferred(file.getName(), false);
            }
            Response response = newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, 
                    "Error: " + e.getMessage());
            addCorsHeaders(response);
            return response;
        }
    }

    public void addFile(String fileId, File file) {
        if (file != null && file.exists()) {
            pendingFiles.put(fileId, file);
        }
    }

    public void removeFile(String fileId) {
        pendingFiles.remove(fileId);
    }

    public void clearFiles() {
        pendingFiles.clear();
    }

    public boolean isRunning() {
        return isAlive();
    }

    public int getPort() {
        return currentPort;
    }

    public String getIp() {
        return currentIp;
    }

    /**
     * 获取热点 IP 地址
     */
    private String getHotspotIpAddress() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (iface.isLoopback() || !iface.isUp()) continue;

                String name = iface.getName().toLowerCase();
                // 热点接口通常是 wlan, ap, p2p
                if (name.contains("wlan") || name.contains("ap") || name.contains("p2p")) {
                    Enumeration<InetAddress> addresses = iface.getInetAddresses();
                    while (addresses.hasMoreElements()) {
                        InetAddress addr = addresses.nextElement();
                        String ip = addr.getHostAddress();
                        // 只返回 IPv4 地址
                        if (!ip.contains(":") && addr instanceof Inet4Address) {
                            AppLog.i(TAG, "找到热点 IP: " + ip + " (接口: " + name + ")");
                            return ip;
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "获取热点 IP 失败", e);
        }
        
        // 默认返回常见的热点地址
        return "192.168.43.1";
    }

    private String getMimeType(String fileName) {
        if (fileName.endsWith(".mp4")) return "video/mp4";
        if (fileName.endsWith(".avi")) return "video/x-msvideo";
        if (fileName.endsWith(".mov")) return "video/quicktime";
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) return "image/jpeg";
        if (fileName.endsWith(".png")) return "image/png";
        if (fileName.endsWith(".gif")) return "image/gif";
        if (fileName.endsWith(".mp3")) return "audio/mpeg";
        return "application/octet-stream";
    }

    private String formatFileSize(long size) {
        if (size < 1024) return size + " B";
        if (size < 1024 * 1024) return String.format("%.1f KB", size / 1024.0);
        if (size < 1024 * 1024 * 1024) return String.format("%.1f MB", size / (1024.0 * 1024));
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private String escapeJson(String str) {
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
}
