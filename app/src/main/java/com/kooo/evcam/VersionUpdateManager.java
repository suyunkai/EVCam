package com.kooo.evcam;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * 版本更新管理器
 * 负责检查新版本和下载 APK 文件
 */
public class VersionUpdateManager {
    private static final String TAG = "VersionUpdateManager";
    
    // 默认更新服务器配置
    private static final String VERSION_FILE = "version.txt";
    // APK 文件名格式：EVCam-v{版本号}-release.apk
    private static final String APK_FILE_PATTERN = "EVCam-v%s-release.apk";
    
    private final Context context;
    private final AppConfig appConfig;
    private final OkHttpClient httpClient;
    private final Handler mainHandler;
    
    // 当前下载任务，用于取消
    private Call currentDownloadCall;
    
    /**
     * 版本检查回调
     */
    public interface UpdateCheckCallback {
        /**
         * 发现新版本
         * @param newVersion 新版本号
         */
        void onUpdateAvailable(String newVersion);
        
        /**
         * 已是最新版本
         */
        void onNoUpdate();
        
        /**
         * 检查失败
         * @param error 错误信息
         */
        void onError(String error);
    }
    
    /**
     * 下载回调
     */
    public interface DownloadCallback {
        /**
         * 下载进度更新
         * @param progress 进度百分比 (0-100)
         */
        void onProgress(int progress);
        
        /**
         * 下载完成
         * @param apkFile 下载的 APK 文件
         */
        void onComplete(File apkFile);
        
        /**
         * 下载失败
         * @param error 错误信息
         */
        void onError(String error);
    }
    
    public VersionUpdateManager(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
        
        // 配置 OkHttpClient
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }
    
    /**
     * 获取当前应用版本号
     */
    public String getCurrentVersion() {
        try {
            PackageInfo packageInfo = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return packageInfo.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            AppLog.e(TAG, "获取版本号失败: " + e.getMessage());
            return "unknown";
        }
    }
    
    /**
     * 获取更新服务器基础 URL
     */
    private String getBaseUrl() {
        String url = appConfig.getUpdateServerUrl();
        if (url == null || url.isEmpty()) {
            return null;
        }
        // 确保 URL 以 / 结尾
        if (!url.endsWith("/")) {
            url += "/";
        }
        return url;
    }
    
    /**
     * 检查是否配置了更新服务器
     */
    public boolean isUpdateServerConfigured() {
        String url = appConfig.getUpdateServerUrl();
        return url != null && !url.isEmpty();
    }
    
    /**
     * 检查更新
     */
    public void checkUpdate(UpdateCheckCallback callback) {
        String baseUrl = getBaseUrl();
        if (baseUrl == null) {
            mainHandler.post(() -> callback.onError("Update server URL not configured"));
            return;
        }
        
        String versionUrl = baseUrl + VERSION_FILE;
        AppLog.d(TAG, "检查版本更新: " + versionUrl);
        
        Request request = new Request.Builder()
                .url(versionUrl)
                .get()
                .build();
        
        httpClient.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                AppLog.e(TAG, "版本检查失败: " + e.getMessage());
                mainHandler.post(() -> callback.onError("网络错误: " + e.getMessage()));
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    if (!response.isSuccessful()) {
                        AppLog.e(TAG, "版本检查失败，HTTP 状态码: " + response.code());
                        mainHandler.post(() -> callback.onError("Server error: " + response.code()));
                        return;
                    }
                    
                    ResponseBody body = response.body();
                    if (body == null) {
                        mainHandler.post(() -> callback.onError("Server returned empty response"));
                        return;
                    }
                    
                    String remoteVersion = body.string().trim();
                    AppLog.d(TAG, "服务器版本: " + remoteVersion);
                    
                    // 验证版本号格式
                    if (!isValidVersionFormat(remoteVersion)) {
                        mainHandler.post(() -> callback.onError("Invalid version format: " + remoteVersion));
                        return;
                    }
                    
                    String currentVersion = getCurrentVersion();
                    AppLog.d(TAG, "当前版本: " + currentVersion + ", 远程版本: " + remoteVersion);
                    
                    if (isNewerVersion(remoteVersion, currentVersion)) {
                        mainHandler.post(() -> callback.onUpdateAvailable(remoteVersion));
                    } else {
                        mainHandler.post(() -> callback.onNoUpdate());
                    }
                } finally {
                    response.close();
                }
            }
        });
    }
    
    /**
     * 下载 APK 文件
     * @param newVersion 新版本号，用于构建文件名和命名本地文件
     */
    public void downloadApk(String newVersion, DownloadCallback callback) {
        String baseUrl = getBaseUrl();
        if (baseUrl == null) {
            mainHandler.post(() -> callback.onError("Update server URL not configured"));
            return;
        }
        
        // 根据版本号构建 APK 文件名
        String apkFileName = String.format(APK_FILE_PATTERN, newVersion);
        String apkUrl = baseUrl + apkFileName;
        AppLog.d(TAG, "开始下载 APK: " + apkUrl);
        
        Request request = new Request.Builder()
                .url(apkUrl)
                .get()
                .build();
        
        currentDownloadCall = httpClient.newCall(request);
        currentDownloadCall.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                currentDownloadCall = null;
                if (call.isCanceled()) {
                    AppLog.d(TAG, "Download cancelled");
                    mainHandler.post(() -> callback.onError("Download cancelled"));
                } else {
                    AppLog.e(TAG, "下载失败: " + e.getMessage());
                    mainHandler.post(() -> callback.onError("下载失败: " + e.getMessage()));
                }
            }
            
            @Override
            public void onResponse(Call call, Response response) throws IOException {
                currentDownloadCall = null;
                
                if (!response.isSuccessful()) {
                    AppLog.e(TAG, "下载失败，HTTP 状态码: " + response.code());
                    mainHandler.post(() -> callback.onError("Server error: " + response.code()));
                    response.close();
                    return;
                }
                
                ResponseBody body = response.body();
                if (body == null) {
                    mainHandler.post(() -> callback.onError("Server returned empty response"));
                    return;
                }
                
                try {
                    // 获取文件大小
                    long contentLength = body.contentLength();
                    AppLog.d(TAG, "APK 文件大小: " + contentLength + " bytes");
                    
                    // 创建目标文件
                    File downloadDir = Environment.getExternalStoragePublicDirectory(
                            Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadDir.exists()) {
                        downloadDir.mkdirs();
                    }
                    
                    // 文件名：EVCam_版本号.apk
                    String fileName = "EVCam_" + newVersion + ".apk";
                    File apkFile = new File(downloadDir, fileName);
                    
                    // 如果文件已存在，先删除
                    if (apkFile.exists()) {
                        apkFile.delete();
                    }
                    
                    // 写入文件
                    InputStream inputStream = body.byteStream();
                    FileOutputStream outputStream = new FileOutputStream(apkFile);
                    
                    byte[] buffer = new byte[8192];
                    long downloadedBytes = 0;
                    int bytesRead;
                    int lastProgress = 0;
                    
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;
                        
                        // 计算进度
                        if (contentLength > 0) {
                            int progress = (int) (downloadedBytes * 100 / contentLength);
                            if (progress != lastProgress) {
                                lastProgress = progress;
                                final int finalProgress = progress;
                                mainHandler.post(() -> callback.onProgress(finalProgress));
                            }
                        }
                    }
                    
                    outputStream.flush();
                    outputStream.close();
                    inputStream.close();
                    
                    AppLog.d(TAG, "APK 下载完成: " + apkFile.getAbsolutePath());
                    mainHandler.post(() -> callback.onComplete(apkFile));
                    
                } catch (IOException e) {
                    AppLog.e(TAG, "保存文件失败: " + e.getMessage());
                    mainHandler.post(() -> callback.onError("保存文件失败: " + e.getMessage()));
                } finally {
                    response.close();
                }
            }
        });
    }
    
    /**
     * 取消当前下载
     */
    public void cancelDownload() {
        if (currentDownloadCall != null && !currentDownloadCall.isCanceled()) {
            currentDownloadCall.cancel();
            AppLog.d(TAG, "取消下载");
        }
    }
    
    /**
     * 验证版本号格式
     * 支持格式：1.0.0、1.0.0-test-01301530 等
     */
    private boolean isValidVersionFormat(String version) {
        if (version == null || version.isEmpty()) {
            return false;
        }
        // 简单验证：至少包含一个数字和一个点
        return version.matches("^\\d+\\.\\d+.*$");
    }
    
    /**
     * 比较版本号，判断 newVersion 是否比 currentVersion 更新
     * 支持格式：1.0.3、1.0.3-test-01301530
     * 
     * 规则：
     * 1. 主版本号不同时，数字大的更新（1.0.4 > 1.0.3-test-xxx > 1.0.3）
     * 2. 主版本号相同时，有 -test- 后缀的比没有后缀的更新（1.0.3-test-xxx > 1.0.3）
     * 3. 都有 -test- 后缀时，比较时间戳（1.0.3-test-02032310 > 1.0.3-test-02031200）
     */
    private boolean isNewerVersion(String newVersion, String currentVersion) {
        try {
            // 提取主版本号部分（去掉 -test-xxx 后缀）
            String newMain = extractMainVersion(newVersion);
            String currentMain = extractMainVersion(currentVersion);
            
            // 分割版本号
            String[] newParts = newMain.split("\\.");
            String[] currentParts = currentMain.split("\\.");
            
            // 比较主版本号每个部分
            int maxLength = Math.max(newParts.length, currentParts.length);
            for (int i = 0; i < maxLength; i++) {
                int newPart = i < newParts.length ? parseVersionPart(newParts[i]) : 0;
                int currentPart = i < currentParts.length ? parseVersionPart(currentParts[i]) : 0;
                
                if (newPart > currentPart) {
                    return true;
                } else if (newPart < currentPart) {
                    return false;
                }
            }
            
            // 主版本号相同，比较后缀
            boolean newIsTest = newVersion.contains("-test-");
            boolean currentIsTest = currentVersion.contains("-test-");
            
            // 测试版 > 正式版（主版本号相同时）
            if (newIsTest && !currentIsTest) {
                return true;  // 新版本是测试版，当前是正式版，测试版更新
            }
            
            if (!newIsTest && currentIsTest) {
                return false;  // 新版本是正式版，当前是测试版，不算更新
            }
            
            // 两者都是测试版，比较时间戳
            if (newIsTest && currentIsTest) {
                String newTimestamp = extractTestTimestamp(newVersion);
                String currentTimestamp = extractTestTimestamp(currentVersion);
                return newTimestamp.compareTo(currentTimestamp) > 0;
            }
            
            // 两者都是正式版且版本号相同
            return false;
        } catch (Exception e) {
            AppLog.e(TAG, "版本比较失败: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 提取主版本号（去掉 -test-xxx 后缀）
     */
    private String extractMainVersion(String version) {
        int testIndex = version.indexOf("-test-");
        if (testIndex > 0) {
            return version.substring(0, testIndex);
        }
        // 处理其他可能的后缀（如 -alpha、-beta）
        int dashIndex = version.indexOf("-");
        if (dashIndex > 0) {
            return version.substring(0, dashIndex);
        }
        return version;
    }
    
    /**
     * 提取测试版时间戳
     */
    private String extractTestTimestamp(String version) {
        int testIndex = version.indexOf("-test-");
        if (testIndex > 0 && testIndex + 6 < version.length()) {
            return version.substring(testIndex + 6);
        }
        return "";
    }
    
    /**
     * 解析版本号部分为整数
     */
    private int parseVersionPart(String part) {
        try {
            // 处理可能的非数字字符（如 "3a" -> 3）
            StringBuilder digits = new StringBuilder();
            for (char c : part.toCharArray()) {
                if (Character.isDigit(c)) {
                    digits.append(c);
                } else {
                    break;
                }
            }
            return digits.length() > 0 ? Integer.parseInt(digits.toString()) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
