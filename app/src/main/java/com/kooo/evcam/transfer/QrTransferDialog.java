package com.kooo.evcam.transfer;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * 扫码互传对话框 V2
 * 支持 IP 地址选择和文件管理器
 */
public class QrTransferDialog extends Dialog {

    private ImageView qrCodeImage;
    private ProgressBar qrLoading;
    private TextView serverStatus;
    private TextView transferStatus;
    private TextView urlText;
    private Button btnCopyUrl;
    private Spinner ipSpinner;
    private ListView fileListView;
    private Button btnRefreshIp;
    private Button btnStopServer;

    private FileTransferServer fileServer;
    private Handler mainHandler = new Handler(Looper.getMainLooper());
    private List<File> filesToTransfer;
    private List<IpAddressInfo> availableIps = new ArrayList<>();
    private FileListAdapter fileListAdapter;
    private String currentIp = null;
    private int currentPort = 0;
    
    // 标记对话框是否正在被销毁，避免在回调中访问已销毁的视图
    private volatile boolean isDestroying = false;

    /**
     * 检查对话框是否正在被销毁
     */
    private boolean isDestructionStarted() {
        return isDestroying || !isShowing();
    }

    // IP 地址信息类
    private static class IpAddressInfo {
        String ip;
        String description;
        String interfaceName;

        IpAddressInfo(String ip, String description, String interfaceName) {
            this.ip = ip;
            this.description = description;
            this.interfaceName = interfaceName;
        }

        @Override
        public String toString() {
            return description + " (" + ip + ")";
        }
    }

    public QrTransferDialog(@NonNull Context context, List<File> files) {
        super(context);
        this.filesToTransfer = files;
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("文件列表不能为空");
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置对话框属性：点击外部不关闭，按返回键有确认提示
        setCancelable(false);
        setCanceledOnTouchOutside(false);
        
        setContentView(R.layout.dialog_qr_transfer_v2);

        initViews();
        setupListeners();
        initFileList();
        scanIpAddresses();
        
        // 首次初始化时，优先选择 WiFi 热点 IP
        selectDefaultHotspotIp();
        
        startServer();
    }

    /**
     * 选择默认的热点 IP（优先 WiFi 热点）
     */
    private void selectDefaultHotspotIp() {
        if (availableIps.isEmpty()) return;
        
        // 优先查找 WiFi 热点相关的 IP
        int hotspotIndex = -1;
        for (int i = 0; i < availableIps.size(); i++) {
            IpAddressInfo info = availableIps.get(i);
            // wlan 接口通常是 WiFi 热点
            if (info.interfaceName.startsWith("wlan")) {
                hotspotIndex = i;
                break;
            }
        }
        
        // 如果没有找到 wlan，查找 ap 接口
        if (hotspotIndex == -1) {
            for (int i = 0; i < availableIps.size(); i++) {
                IpAddressInfo info = availableIps.get(i);
                if (info.interfaceName.startsWith("ap")) {
                    hotspotIndex = i;
                    break;
                }
            }
        }
        
        // 选择找到的索引，如果没有找到则选择第一个
        int selectedIndex = (hotspotIndex != -1) ? hotspotIndex : 0;
        ipSpinner.setSelection(selectedIndex);
        
        // 更新当前 IP
        IpAddressInfo selectedInfo = availableIps.get(selectedIndex);
        currentIp = selectedInfo.ip;
    }

    private void initViews() {
        qrCodeImage = findViewById(R.id.qr_code_image);
        qrLoading = findViewById(R.id.qr_loading);
        serverStatus = findViewById(R.id.server_status);
        transferStatus = findViewById(R.id.transfer_status);
        urlText = findViewById(R.id.url_text);
        btnCopyUrl = findViewById(R.id.btn_copy_url);
        ipSpinner = findViewById(R.id.ip_spinner);
        fileListView = findViewById(R.id.file_list_view);
        btnRefreshIp = findViewById(R.id.btn_refresh_ip);
        btnStopServer = findViewById(R.id.btn_stop_server);

        // 设置对话框宽度
        if (getWindow() != null) {
            getWindow().setLayout(
                    (int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.85),
                    android.view.WindowManager.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private void setupListeners() {
        btnRefreshIp.setOnClickListener(v -> {
            // 保存当前选中的 IP
            String previousIp = currentIp;
            scanIpAddresses();
            
            // 尝试恢复之前选中的 IP
            if (previousIp != null) {
                boolean found = false;
                for (int i = 0; i < availableIps.size(); i++) {
                    if (availableIps.get(i).ip.equals(previousIp)) {
                        ipSpinner.setSelection(i);
                        found = true;
                        break;
                    }
                }
                // 如果没找到之前的 IP，选择第一个
                if (!found && !availableIps.isEmpty()) {
                    ipSpinner.setSelection(0);
                }
            }
            
            Toast.makeText(getContext(), "已刷新 IP 列表", Toast.LENGTH_SHORT).show();
        });

        btnStopServer.setOnClickListener(v -> {
            stopServer();
            dismiss();
        });

        // 复制 URL 按钮
        btnCopyUrl.setOnClickListener(v -> {
            String url = urlText.getText().toString();
            if (!url.equals("http://...") && !url.isEmpty()) {
                ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                ClipData clip = ClipData.newPlainText("EVCam URL", url);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(getContext(), "URL 已复制到剪贴板", Toast.LENGTH_SHORT).show();
            }
        });

        ipSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position >= 0 && position < availableIps.size()) {
                    IpAddressInfo selectedIp = availableIps.get(position);
                    AppLog.i("QrTransferDialog", "用户选择 IP: " + selectedIp.ip + " (" + selectedIp.description + ")");
                    
                    // 更新当前 IP
                    currentIp = selectedIp.ip;
                    
                    // 如果服务器已启动，更新服务器的指定 IP
                    if (fileServer != null && fileServer.isRunning()) {
                        fileServer.setSpecifiedIpAddress(selectedIp.ip);
                    }
                    
                    // 更新二维码
                    updateQrCode(selectedIp.ip);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void initFileList() {
        fileListAdapter = new FileListAdapter(getContext(), filesToTransfer);
        fileListView.setAdapter(fileListAdapter);
    }

    /**
     * 扫描所有可用的 IP 地址
     */
    private void scanIpAddresses() {
        availableIps.clear();
        List<IpAddressInfo> hotspotIps = new ArrayList<>();
        List<IpAddressInfo> otherIps = new ArrayList<>();

        try {
            // 遍历所有网络接口
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                String interfaceName = networkInterface.getName();

                if (networkInterface.isLoopback()) continue;
                if (!networkInterface.isUp()) continue;
                if (interfaceName.startsWith("rmnet") || interfaceName.startsWith("ccmni")) continue;

                Enumeration<InetAddress> addresses = networkInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        String ip = address.getHostAddress();
                        String description = getInterfaceDescription(interfaceName);
                        IpAddressInfo info = new IpAddressInfo(ip, description, interfaceName);
                        
                        // 将热点相关的 IP 放在前面
                        if (interfaceName.startsWith("wlan") || interfaceName.startsWith("ap")) {
                            hotspotIps.add(info);
                        } else {
                            otherIps.add(info);
                        }
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 合并列表：热点 IP 在前，其他在后
        availableIps.addAll(hotspotIps);
        availableIps.addAll(otherIps);

        // 如果没有找到任何 IP，添加默认地址
        if (availableIps.isEmpty()) {
            availableIps.add(new IpAddressInfo("192.168.43.1", "热点默认", "hotspot"));
        }

        // 更新 Spinner
        ArrayAdapter<IpAddressInfo> adapter = new ArrayAdapter<>(getContext(),
                android.R.layout.simple_spinner_item, availableIps);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        ipSpinner.setAdapter(adapter);
        
        // 注意：不在这里自动选择 IP，由调用方决定
    }

    /**
     * 获取接口描述
     */
    private String getInterfaceDescription(String interfaceName) {
        if (interfaceName.startsWith("wlan")) return "WiFi热点";
        if (interfaceName.startsWith("ap")) return "热点";
        if (interfaceName.startsWith("p2p")) return "WiFi直连";
        if (interfaceName.startsWith("eth")) return "以太网";
        if (interfaceName.startsWith("usb")) return "USB网络";
        return interfaceName;
    }

    /**
     * 更新二维码
     */
    private void updateQrCode(String ip) {
        if (ip == null || fileServer == null) return;

        currentIp = ip;
        String qrUrl = "http://" + ip + ":" + currentPort;

        // 更新 URL 文本显示
        if (urlText != null) {
            urlText.setText(qrUrl);
        }

        // 生成二维码
        int qrSize = (int) (getContext().getResources().getDisplayMetrics().density * 180);
        Bitmap qrBitmap = QrCodeGenerator.generateQRCode(qrUrl, qrSize, qrSize);

        if (qrBitmap != null) {
            qrCodeImage.setImageBitmap(qrBitmap);
            qrLoading.setVisibility(View.GONE);
            serverStatus.setText("服务器: " + ip + ":" + currentPort);
        }
    }

    private void startServer() {
        AppLog.i("QrTransferDialog", "开始启动文件传输服务器...");
        fileServer = new FileTransferServer(getContext());
        
        // 设置指定的 IP 地址（用于 QR 码生成）
        String ipForQr = null;
        if (ipSpinner.getSelectedItem() != null) {
            IpAddressInfo selectedIp = (IpAddressInfo) ipSpinner.getSelectedItem();
            ipForQr = selectedIp.ip;
        } else if (currentIp != null) {
            ipForQr = currentIp;
        } else if (!availableIps.isEmpty()) {
            ipForQr = availableIps.get(0).ip;
        }
        
        if (ipForQr != null) {
            AppLog.i("QrTransferDialog", "设置服务器使用 IP: " + ipForQr);
            fileServer.setSpecifiedIpAddress(ipForQr);
        }
        
        fileServer.setCallback(new FileTransferServer.TransferCallback() {
            @Override
            public void onServerStarted(String ipAddress, int port, String qrUrl) {
                mainHandler.post(() -> {
                    try {
                        if (isDestructionStarted()) return;
                        currentPort = port;
                        transferStatus.setVisibility(View.VISIBLE);
                        transferStatus.setText("等待连接...");

                        AppLog.i("QrTransferDialog", "服务器已启动在端口: " + port + ", QR URL: " + qrUrl);

                        // 使用当前选中的 IP 生成二维码
                        if (ipSpinner.getSelectedItem() != null) {
                            IpAddressInfo selectedIp = (IpAddressInfo) ipSpinner.getSelectedItem();
                            AppLog.i("QrTransferDialog", "使用选中的 IP 生成二维码: " + selectedIp.ip);
                            updateQrCode(selectedIp.ip);
                        } else if (currentIp != null) {
                            // 如果 spinner 没有选中项，使用 currentIp
                            AppLog.i("QrTransferDialog", "使用 currentIp 生成二维码: " + currentIp);
                            updateQrCode(currentIp);
                        } else if (!availableIps.isEmpty()) {
                            // 使用第一个可用的 IP
                            AppLog.i("QrTransferDialog", "使用第一个可用 IP 生成二维码: " + availableIps.get(0).ip);
                            updateQrCode(availableIps.get(0).ip);
                        } else {
                            AppLog.e("QrTransferDialog", "没有可用的 IP 地址");
                        }
                    } catch (Exception e) {
                        AppLog.e("QrTransferDialog", "onServerStarted 回调异常", e);
                    }
                });
            }

            @Override
            public void onServerStopped() {
                mainHandler.post(() -> {
                    try {
                        if (isDestructionStarted()) return;
                        serverStatus.setText("服务器已停止");
                    } catch (Exception e) {
                        AppLog.e("QrTransferDialog", "onServerStopped 回调异常", e);
                    }
                });
            }

            @Override
            public void onFileRequested(String fileName) {
                mainHandler.post(() -> {
                    try {
                        if (isDestructionStarted()) return;
                        transferStatus.setText("正在传输: " + fileName);
                        updateFileStatus(fileName, "传输中...");
                    } catch (Exception e) {
                        AppLog.e("QrTransferDialog", "onFileRequested 回调异常", e);
                    }
                });
            }

            @Override
            public void onFileTransferred(String fileName, boolean success) {
                mainHandler.post(() -> {
                    try {
                        if (isDestructionStarted()) return;
                        if (success) {
                            transferStatus.setText("传输完成: " + fileName);
                            updateFileStatus(fileName, "完成");
                        } else {
                            transferStatus.setText("传输失败: " + fileName);
                            updateFileStatus(fileName, "失败");
                        }
                    } catch (Exception e) {
                        AppLog.e("QrTransferDialog", "onFileTransferred 回调异常", e);
                    }
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    try {
                        if (isDestructionStarted()) return;
                        serverStatus.setText("错误: " + error);
                        qrLoading.setVisibility(View.GONE);
                        Toast.makeText(getContext(), error, Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        AppLog.e("QrTransferDialog", "onError 回调异常", e);
                    }
                });
            }
        });

        // 添加文件到服务器
        if (filesToTransfer != null) {
            for (int i = 0; i < filesToTransfer.size(); i++) {
                fileServer.addFile("file_" + i, filesToTransfer.get(i));
            }
        }

        // 启动服务器
        AppLog.i("QrTransferDialog", "正在启动服务器...");
        try {
            fileServer.start();
            AppLog.i("QrTransferDialog", "服务器启动成功");
        } catch (Exception e) {
            AppLog.e("QrTransferDialog", "服务器启动失败", e);
            serverStatus.setText("启动服务器失败");
            qrLoading.setVisibility(View.GONE);
            Toast.makeText(getContext(), "无法启动文件传输服务器: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void updateFileStatus(String fileName, String status) {
        for (int i = 0; i < filesToTransfer.size(); i++) {
            if (filesToTransfer.get(i).getName().equals(fileName)) {
                fileListAdapter.updateStatus(i, status);
                break;
            }
        }
    }

    private void stopServer() {
        if (fileServer != null) {
            fileServer.stop();
            fileServer = null;
        }
    }

    @Override
    public void dismiss() {
        isDestroying = true;
        stopServer();
        super.dismiss();
    }

    @Override
    public void onBackPressed() {
        new MaterialAlertDialogBuilder(getContext())
                .setTitle("确认关闭")
                .setMessage("关闭后将停止文件传输，是否继续？")
                .setPositiveButton("停止传输", (dialog, which) -> {
                    stopServer();
                    super.onBackPressed();
                })
                .setNegativeButton("继续传输", null)
                .show();
    }

    /**
     * 文件列表适配器
     */
    private static class FileListAdapter extends BaseAdapter {
        private Context context;
        private List<File> files;
        private List<String> statuses;

        FileListAdapter(Context context, List<File> files) {
            this.context = context;
            this.files = files;
            this.statuses = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                statuses.add("等待");
            }
        }

        void updateStatus(int position, String status) {
            if (position >= 0 && position < statuses.size()) {
                statuses.set(position, status);
                notifyDataSetChanged();
            }
        }

        @Override
        public int getCount() {
            return files.size();
        }

        @Override
        public Object getItem(int position) {
            return files.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(context).inflate(R.layout.item_transfer_file, parent, false);
            }

            File file = files.get(position);

            TextView fileName = convertView.findViewById(R.id.file_name);
            TextView fileSize = convertView.findViewById(R.id.file_size);
            TextView fileStatus = convertView.findViewById(R.id.file_status);

            fileName.setText(file.getName());
            fileSize.setText(formatFileSize(file.length()));
            fileStatus.setText(statuses.get(position));

            // 根据状态设置颜色
            String status = statuses.get(position);
            if ("完成".equals(status)) {
                fileStatus.setTextColor(0xFF4CAF50); // 绿色
            } else if ("失败".equals(status)) {
                fileStatus.setTextColor(0xFFD32F2F); // 红色
            } else {
                fileStatus.setTextColor(0xFF1976D2); // 蓝色
            }

            return convertView;
        }

        private String formatFileSize(long size) {
            if (size < 1024) {
                return size + " B";
            } else if (size < 1024 * 1024) {
                return String.format(Locale.getDefault(), "%.2f KB", size / 1024.0);
            } else if (size < 1024L * 1024 * 1024) {
                return String.format(Locale.getDefault(), "%.2f MB", size / (1024.0 * 1024.0));
            } else {
                return String.format(Locale.getDefault(), "%.2f GB", size / (1024.0 * 1024.0 * 1024.0));
            }
        }
    }
}
