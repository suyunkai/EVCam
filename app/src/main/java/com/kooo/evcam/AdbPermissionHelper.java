package com.kooo.evcam;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ADB 权限授予助手
 * 通过 ADB TCP 协议连接 localhost:5555，自动授予所有需要的权限。
 *
 * 工作原理：
 * 1. 通过 TCP 连接到设备本地的 ADB 守护进程 (端口 5555)
 * 2. 使用 ADB 协议进行握手（支持认证）
 * 3. 以 shell 用户身份执行 pm grant / appops / settings 等命令
 * 4. 通过回调报告执行进度和结果
 */
public class AdbPermissionHelper {
    private static final String TAG = "AdbPermissionHelper";

    // ==================== ADB 协议常量 ====================
    private static final int A_CNXN = 0x4e584e43; // CNXN
    private static final int A_AUTH = 0x48545541; // AUTH
    private static final int A_OPEN = 0x4e45504f; // OPEN
    private static final int A_OKAY = 0x59414b4f; // OKAY
    private static final int A_CLSE = 0x45534c43; // CLSE
    private static final int A_WRTE = 0x45545257; // WRTE

    private static final int ADB_AUTH_TOKEN = 1;
    private static final int ADB_AUTH_SIGNATURE = 2;
    private static final int ADB_AUTH_RSAPUBLICKEY = 3;

    private static final int A_VERSION = 0x01000000;
    private static final int MAX_PAYLOAD = 4096;

    // 连接参数
    private static final String ADB_HOST = "127.0.0.1";
    private static final int ADB_PORT = 5555;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;
    private static final int AUTH_ACCEPT_TIMEOUT_MS = 30000;
    private static final int INSTALL_TIMEOUT_MS = 120000; // pm install 最多等 2 分钟

    // RSA 密钥参数
    private static final int RSA_KEY_BITS = 2048;
    private static final String PRIVATE_KEY_FILE = "adb_private_key";
    private static final String PUBLIC_KEY_FILE = "adb_public_key";

    // SHA-1 AlgorithmIdentifier DigestInfo 前缀 (PKCS#1 v1.5)
    private static final byte[] SHA1_DIGEST_INFO_PREFIX = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
    };

    // ==================== 实例状态 ====================
    private final Context context;
    private final String packageName;
    private final Handler mainHandler;
    private final ExecutorService executor;
    private KeyPair keyPair;

    // 连接状态
    private Socket socket;
    private InputStream socketIn;
    private OutputStream socketOut;
    private int serverMaxData = MAX_PAYLOAD;
    private int localIdCounter = 1;

    // 执行计数
    private int successCount = 0;
    private int failCount = 0;

    private volatile boolean cancelled = false;

    // ==================== 回调接口 ====================
    public interface Callback {
        /** 日志输出（在主线程调用） */
        void onLog(String message);

        /** 执行完成（在主线程调用） */
        void onComplete(boolean allSuccess);
    }

    // ==================== ADB 消息 ====================
    private static class AdbMessage {
        int command;
        int arg0;
        int arg1;
        byte[] data;
    }

    // ==================== 权限命令 ====================
    private static class PermissionCommand {
        final String description;
        final String shellCommand;

        PermissionCommand(String description, String shellCommand) {
            this.description = description;
            this.shellCommand = shellCommand;
        }
    }

    // ==================== 构造方法 ====================
    public AdbPermissionHelper(Context context) {
        this.context = context.getApplicationContext();
        this.packageName = context.getPackageName();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
    }

    // ==================== 公开 API ====================

    /**
     * 开始自动授予所有权限
     */
    public void grantAllPermissions(Callback callback) {
        cancelled = false;
        localIdCounter = 1;
        successCount = 0;
        failCount = 0;
        executor.execute(() -> doGrantAll(callback));
    }

    /**
     * 取消执行
     */
    public void cancel() {
        cancelled = true;
        closeSocket();
    }

    /**
     * 通过 ADB 安装 APK 文件
     * @param apkPath APK 文件在设备上的绝对路径
     */
    public void installApk(String apkPath, Callback callback) {
        cancelled = false;
        localIdCounter = 1;
        successCount = 0;
        failCount = 0;
        executor.execute(() -> doInstallApk(apkPath, callback));
    }

    // ==================== 主流程 ====================

    private void doGrantAll(Callback callback) {
        log(callback, "=== ADB 一键获取权限 ===");

        try {
            // 0. 先断开重连，避免 ADB 被占用
            resetAdbConnection(callback);

            // 1. 加载或生成 RSA 密钥对（用于 ADB 认证）
            loadOrGenerateKeyPair();

            // 2. TCP 连接（尝试多个地址）
            socket = tryConnect(callback);
            if (socket == null) {
                notifyComplete(callback, false);
                return;
            }
            socketIn = socket.getInputStream();
            socketOut = socket.getOutputStream();

            // 3. ADB 握手
            if (!performHandshake(callback)) {
                log(callback, "\n✗ ADB 连接握手失败");
                notifyComplete(callback, false);
                return;
            }

            log(callback, "✓ ADB 连接成功");
            log(callback, "");

            // 4. 构建并执行权限命令
            List<PermissionCommand> commands = buildCommandList();

            for (PermissionCommand cmd : commands) {
                if (cancelled || socket == null || socket.isClosed()) {
                    log(callback, "已取消");
                    break;
                }
                executePermissionCommand(cmd, callback);
            }

            // 5. 处理无障碍服务（需要先查询再设置）
            if (!cancelled && socket != null && !socket.isClosed()) {
                handleAccessibilityService(callback);
            }

            // 6. 电池优化白名单
            if (!cancelled && socket != null && !socket.isClosed()) {
                handleBatteryWhitelist(callback);
            }

            // 7. 输出统计
            log(callback, "");
            log(callback, "=== 执行完成 ===");
            log(callback, "成功: " + successCount + "  失败: " + failCount);
            if (failCount == 0) {
                log(callback, "所有权限已授予，请返回查看状态");
            } else {
                log(callback, "部分权限授予失败，请检查上方日志");
            }

            notifyComplete(callback, failCount == 0);

        } catch (java.net.SocketTimeoutException e) {
            log(callback, "");
            log(callback, "✗ 连接超时");
            AppLog.e(TAG, "ADB timeout", e);
            notifyComplete(callback, false);
        } catch (Exception e) {
            log(callback, "");
            log(callback, "✗ 错误: " + e.getMessage());
            AppLog.e(TAG, "ADB grant all failed", e);
            notifyComplete(callback, false);
        } finally {
            closeSocket();
        }
    }

    /**
     * 重置 ADB 连接：先断开可能存在的旧连接，再短暂等待 ADB daemon 释放资源。
     * 在每次 ADB 操作前调用，避免 ADB 被其他进程或残留连接占用导致失败。
     */
    private void resetAdbConnection(Callback callback) {
        // 1. 关闭自身可能残留的旧连接
        closeSocket();

        // 2. 尝试连接后立即断开，迫使 ADB daemon 释放现有会话
        log(callback, "重置 ADB 连接...");
        Socket probe = null;
        try {
            probe = new Socket();
            probe.connect(new InetSocketAddress(ADB_HOST, ADB_PORT), CONNECT_TIMEOUT_MS);
            probe.close();
            probe = null;
            // 等待 ADB daemon 完成清理
            Thread.sleep(800);
            log(callback, "✓ ADB 连接已重置");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            // 连接失败说明端口空闲或 ADB 未开启，无需重置
            AppLog.d(TAG, "ADB reset: port not occupied or not available");
        } finally {
            if (probe != null) {
                try { probe.close(); } catch (Exception ignored) {}
            }
        }
    }

    /**
     * 尝试连接 ADB，依次尝试多个地址：
     * 1. 127.0.0.1 (localhost)
     * 2. 设备自身的 WiFi/以太网 IP 地址
     */
    private Socket tryConnect(Callback callback) {
        List<String> hosts = new ArrayList<>();
        hosts.add("127.0.0.1");

        // 收集设备自身的非回环 IPv4 地址
        try {
            java.util.Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp()) continue;
                java.util.Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    if (!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !hosts.contains(ip)) {
                            hosts.add(ip);
                        }
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "Failed to enumerate network interfaces", e);
        }

        log(callback, "尝试连接 ADB (端口 " + ADB_PORT + ")...");

        IOException lastException = null;
        for (String host : hosts) {
            if (cancelled) break;
            try {
                log(callback, "  → " + host + ":" + ADB_PORT + " ...");
                Socket s = new Socket();
                s.connect(new InetSocketAddress(host, ADB_PORT), CONNECT_TIMEOUT_MS);
                s.setSoTimeout(READ_TIMEOUT_MS);
                log(callback, "  ✓ 已连接 " + host + ":" + ADB_PORT);
                return s;
            } catch (IOException e) {
                String reason = e.getMessage();
                if (reason == null) reason = e.getClass().getSimpleName();
                log(callback, "  ✗ " + host + " - " + reason);
                lastException = e;
            }
        }

        // 所有地址都失败
        log(callback, "");
        log(callback, "✗ 无法连接到 ADB (所有地址均失败)");
        log(callback, "");
        log(callback, "请确认：");
        log(callback, "  1. 开发者选项中已开启 USB 调试");
        log(callback, "  2. 通过 PC 执行: adb tcpip 5555");
        log(callback, "  3. 设备已连接 WiFi（部分设备需要）");
        if (lastException != null) {
            AppLog.e(TAG, "ADB connect failed (all hosts)", lastException);
        }
        return null;
    }

    // ==================== APK 安装流程 ====================

    private void doInstallApk(String apkPath, Callback callback) {
        log(callback, "=== ADB 安装更新 ===");

        try {
            // 先断开重连，避免 ADB 被占用
            resetAdbConnection(callback);

            loadOrGenerateKeyPair();

            socket = tryConnect(callback);
            if (socket == null) {
                notifyComplete(callback, false);
                return;
            }
            socketIn = socket.getInputStream();
            socketOut = socket.getOutputStream();

            if (!performHandshake(callback)) {
                log(callback, "\n✗ ADB 连接握手失败");
                notifyComplete(callback, false);
                return;
            }

            log(callback, "✓ ADB 连接成功");
            log(callback, "");

            // /storage/emulated/<userId>/ 是 FUSE 挂载点，跨 mount namespace 不可访问
            // 底层真实路径是 /data/media/<userId>/，system_server 可以直接访问
            String installPath = apkPath;
            if (installPath.startsWith("/storage/emulated/")) {
                installPath = "/data/media/" + installPath.substring("/storage/emulated/".length());
            }

            log(callback, "正在安装...");
            log(callback, "  $ pm install -r " + installPath);
            log(callback, "  (安装过程可能需要 30-60 秒，请耐心等待)");

            // 安装命令需要更长的超时时间
            socket.setSoTimeout(INSTALL_TIMEOUT_MS);

            try {
                String result = executeShellCommand("pm install -r " + installPath);
                result = (result != null) ? result.trim() : "";

                if (result.toLowerCase().contains("success")) {
                    log(callback, "");
                    log(callback, "✓ 安装成功！");
                    log(callback, "  应用即将自动重启...");
                    notifyComplete(callback, true);
                } else {
                    log(callback, "");
                    log(callback, "✗ 安装失败: " + result);
                    log(callback, "  请尝试手动安装");
                    notifyComplete(callback, false);
                }
            } finally {
                socket.setSoTimeout(READ_TIMEOUT_MS);
            }

        } catch (java.net.SocketTimeoutException e) {
            log(callback, "");
            log(callback, "✗ 安装超时 (超过 120 秒)");
            log(callback, "  请尝试手动安装");
            AppLog.e(TAG, "ADB install timeout", e);
            notifyComplete(callback, false);
        } catch (Exception e) {
            log(callback, "");
            log(callback, "✗ 错误: " + e.getMessage());
            AppLog.e(TAG, "ADB install failed", e);
            notifyComplete(callback, false);
        } finally {
            closeSocket();
        }
    }

    /**
     * 执行单条权限命令
     */
    private void executePermissionCommand(PermissionCommand cmd, Callback callback) {
        log(callback, "[" + cmd.description + "]");
        log(callback, "  $ " + cmd.shellCommand);

        try {
            String result = executeShellCommand(cmd.shellCommand);
            result = (result != null) ? result.trim() : "";

            if (result.isEmpty()) {
                log(callback, "  ✓ 成功");
                successCount++;
            } else if (isErrorResult(result)) {
                log(callback, "  ✗ " + result);
                failCount++;
            } else {
                log(callback, "  → " + result);
                successCount++;
            }
        } catch (Exception e) {
            log(callback, "  ✗ " + e.getMessage());
            failCount++;
        }
    }

    // ==================== ADB 握手 ====================

    private boolean performHandshake(Callback callback) throws Exception {
        // 发送 CNXN 消息
        byte[] banner = "host::\0".getBytes("UTF-8");
        sendMessage(A_CNXN, A_VERSION, MAX_PAYLOAD, banner);

        // 读取响应
        AdbMessage msg = readMessage();

        // 情况1: 直接连接成功（无需认证）
        if (msg.command == A_CNXN) {
            serverMaxData = msg.arg1;
            logDeviceInfo(callback, msg.data);
            return true;
        }

        // 情况2: 需要认证
        if (msg.command == A_AUTH && msg.arg0 == ADB_AUTH_TOKEN) {
            log(callback, "ADB 需要认证...");

            // 尝试用已有密钥签名 token
            byte[] signedToken = signToken(msg.data);
            sendMessage(A_AUTH, ADB_AUTH_SIGNATURE, 0, signedToken);

            msg = readMessage();

            if (msg.command == A_CNXN) {
                serverMaxData = msg.arg1;
                logDeviceInfo(callback, msg.data);
                log(callback, "✓ 认证成功（已知密钥）");
                return true;
            }

            // 密钥未被识别，发送公钥请求授权
            if (msg.command == A_AUTH) {
                log(callback, "发送公钥，请在设备上确认 USB 调试授权...");
                byte[] pubKeyData = getAdbPublicKeyBytes();
                sendMessage(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, pubKeyData);

                // 延长超时等待用户确认
                socket.setSoTimeout(AUTH_ACCEPT_TIMEOUT_MS);
                try {
                    msg = readMessage();
                } finally {
                    socket.setSoTimeout(READ_TIMEOUT_MS);
                }

                if (msg.command == A_CNXN) {
                    serverMaxData = msg.arg1;
                    logDeviceInfo(callback, msg.data);
                    log(callback, "✓ 认证成功（用户已授权）");
                    return true;
                }
            }

            log(callback, "✗ 认证失败");
            log(callback, "  请在设备上允许 USB 调试，或检查 ADB 安全设置");
            return false;
        }

        log(callback, "✗ 未知响应: 0x" + Integer.toHexString(msg.command));
        return false;
    }

    private void logDeviceInfo(Callback callback, byte[] data) {
        if (data != null && data.length > 0) {
            String info = new String(data).replace("\0", "").trim();
            if (!info.isEmpty()) {
                log(callback, "设备: " + info);
            }
        }
    }

    // ==================== 脚本执行（供外部调用） ====================

    /**
     * 通过 ADB 协议执行一个 shell 脚本文件，实时流式输出日志。
     * 用于系统白名单配置等需要执行完整脚本的场景。
     *
     * @param scriptPath 脚本在设备上的绝对路径
     * @param callback   实时日志和完成回调
     */
    public void executeScriptFile(String scriptPath, Callback callback) {
        cancelled = false;
        localIdCounter = 1;
        executor.execute(() -> doExecuteScript(scriptPath, callback));
    }

    private void doExecuteScript(String scriptPath, Callback callback) {
        try {
            // 先断开重连，避免 ADB 被占用
            resetAdbConnection(callback);

            loadOrGenerateKeyPair();

            socket = tryConnect(callback);
            if (socket == null) {
                notifyComplete(callback, false);
                return;
            }
            socketIn = socket.getInputStream();
            socketOut = socket.getOutputStream();

            if (!performHandshake(callback)) {
                log(callback, "\n✗ ADB 连接握手失败");
                notifyComplete(callback, false);
                return;
            }

            log(callback, "✓ ADB 连接成功");
            log(callback, "");

            // 脚本可能执行较长时间，延长超时
            socket.setSoTimeout(60000);

            // 执行脚本并流式输出
            boolean success = executeShellCommandStreaming("sh " + scriptPath, callback);

            log(callback, "");
            if (success) {
                log(callback, "✓ 脚本执行成功");
            } else {
                log(callback, "✗ 脚本执行过程中出现错误，请检查日志");
            }

            notifyComplete(callback, success);

        } catch (java.net.SocketTimeoutException e) {
            log(callback, "");
            log(callback, "✗ 执行超时");
            AppLog.e(TAG, "Script execution timeout", e);
            notifyComplete(callback, false);
        } catch (Exception e) {
            log(callback, "");
            log(callback, "✗ 错误: " + e.getMessage());
            AppLog.e(TAG, "Script execution failed", e);
            notifyComplete(callback, false);
        } finally {
            closeSocket();
        }
    }

    /**
     * 执行 shell 命令并通过回调实时输出每一行日志。
     * 通过检测输出中是否包含 [ERROR] 来判断成功与否。
     *
     * @return true 如果输出中没有 [ERROR] 标记
     */
    private boolean executeShellCommandStreaming(String command, Callback callback) throws Exception {
        int localId = localIdCounter++;
        byte[] openData = ("shell:" + command + "\0").getBytes("UTF-8");
        sendMessage(A_OPEN, localId, 0, openData);

        StringBuilder lineBuffer = new StringBuilder();
        boolean hasError = false;
        boolean streamOpen = true;

        while (streamOpen) {
            AdbMessage msg = readMessage();

            switch (msg.command) {
                case A_OKAY:
                    break;

                case A_WRTE:
                    sendMessage(A_OKAY, localId, msg.arg0, null);
                    if (msg.data != null) {
                        String chunk = new String(msg.data, "UTF-8");
                        lineBuffer.append(chunk);

                        // 逐行输出已完成的行
                        int nlIndex;
                        while ((nlIndex = lineBuffer.indexOf("\n")) >= 0) {
                            String line = lineBuffer.substring(0, nlIndex);
                            lineBuffer.delete(0, nlIndex + 1);
                            log(callback, line);
                            if (line.contains("[ERROR]")) {
                                hasError = true;
                            }
                        }
                    }
                    break;

                case A_CLSE:
                    sendMessage(A_CLSE, localId, msg.arg0, null);
                    streamOpen = false;
                    break;

                default:
                    streamOpen = false;
                    break;
            }
        }

        // 输出缓冲区中剩余的内容
        if (lineBuffer.length() > 0) {
            String remaining = lineBuffer.toString();
            log(callback, remaining);
            if (remaining.contains("[ERROR]")) {
                hasError = true;
            }
        }

        return !hasError;
    }

    // ==================== Shell 命令执行（内部） ====================

    /**
     * 通过 ADB 协议执行一条 shell 命令并返回输出
     */
    private String executeShellCommand(String command) throws Exception {
        int localId = localIdCounter++;
        byte[] openData = ("shell:" + command + "\0").getBytes("UTF-8");
        sendMessage(A_OPEN, localId, 0, openData);

        StringBuilder output = new StringBuilder();
        boolean streamOpen = true;

        while (streamOpen) {
            AdbMessage msg = readMessage();

            switch (msg.command) {
                case A_OKAY:
                    // 流已打开，remoteId = msg.arg0
                    break;

                case A_WRTE:
                    // 接收命令输出数据，回复 OKAY 确认
                    sendMessage(A_OKAY, localId, msg.arg0, null);
                    if (msg.data != null) {
                        output.append(new String(msg.data, "UTF-8"));
                    }
                    break;

                case A_CLSE:
                    // 流已关闭，回复 CLSE
                    sendMessage(A_CLSE, localId, msg.arg0, null);
                    streamOpen = false;
                    break;

                default:
                    // 未预期的消息，终止
                    streamOpen = false;
                    break;
            }
        }

        return output.toString();
    }

    // ==================== 权限命令列表 ====================

    private List<PermissionCommand> buildCommandList() {
        List<PermissionCommand> commands = new ArrayList<>();
        int sdk = Build.VERSION.SDK_INT;

        // === 基础运行时权限 (pm grant) ===
        commands.add(new PermissionCommand("相机权限",
                "pm grant " + packageName + " android.permission.CAMERA"));

        commands.add(new PermissionCommand("麦克风权限",
                "pm grant " + packageName + " android.permission.RECORD_AUDIO"));

        // 存储权限（按 API 版本区分）
        if (sdk >= 33) {
            // Android 13+: 媒体权限
            commands.add(new PermissionCommand("媒体视频权限",
                    "pm grant " + packageName + " android.permission.READ_MEDIA_VIDEO"));
            commands.add(new PermissionCommand("媒体图片权限",
                    "pm grant " + packageName + " android.permission.READ_MEDIA_IMAGES"));
        }
        if (sdk <= 32) {
            // Android 12 及以下: 传统存储权限
            commands.add(new PermissionCommand("读取存储权限",
                    "pm grant " + packageName + " android.permission.READ_EXTERNAL_STORAGE"));
            commands.add(new PermissionCommand("写入存储权限",
                    "pm grant " + packageName + " android.permission.WRITE_EXTERNAL_STORAGE"));
        }

        // 日志读取权限
        commands.add(new PermissionCommand("日志读取权限",
                "pm grant " + packageName + " android.permission.READ_LOGS"));

        // 通知权限 (Android 13+)
        if (sdk >= 33) {
            commands.add(new PermissionCommand("通知权限",
                    "pm grant " + packageName + " android.permission.POST_NOTIFICATIONS"));
        }

        // 蓝牙连接权限 (Android 12+)
        if (sdk >= 31) {
            commands.add(new PermissionCommand("蓝牙连接权限",
                    "pm grant " + packageName + " android.permission.BLUETOOTH_CONNECT"));
        }

        // === 特殊权限 (appops) ===
        commands.add(new PermissionCommand("悬浮窗权限",
                "appops set " + packageName + " SYSTEM_ALERT_WINDOW allow"));

        // 所有文件访问 (Android 11+)
        if (sdk >= 30) {
            commands.add(new PermissionCommand("所有文件访问权限",
                    "appops set " + packageName + " MANAGE_EXTERNAL_STORAGE allow"));
        }

        // 使用情况访问权限（全景影像避让需要）
        commands.add(new PermissionCommand("使用情况访问权限",
                "appops set " + packageName + " android:get_usage_stats allow"));

        return commands;
    }

    // ==================== 无障碍服务处理 ====================

    /**
     * 处理无障碍服务权限（需要先查询当前值再追加设置）
     */
    private void handleAccessibilityService(Callback callback) {
        String serviceName = packageName + "/" + packageName + ".KeepAliveAccessibilityService";

        log(callback, "[无障碍服务]");

        try {
            // 查询当前已启用的无障碍服务
            String getCmd = "settings get secure enabled_accessibility_services";
            log(callback, "  $ " + getCmd);
            String current = executeShellCommand(getCmd);
            current = (current != null) ? current.trim() : "";

            String display = (current.isEmpty() || current.equals("null")) ? "(无)" : current;
            log(callback, "  → 当前: " + display);

            // 检查是否已启用
            if (current.contains(packageName)) {
                log(callback, "  ✓ 已启用");
                successCount++;
                return;
            }

            // 构建新值（追加而非覆盖）
            String newValue;
            if (current.isEmpty() || current.equals("null")) {
                newValue = serviceName;
            } else {
                newValue = current + ":" + serviceName;
            }

            // 设置无障碍服务列表
            String putCmd = "settings put secure enabled_accessibility_services " + newValue;
            log(callback, "  $ " + putCmd);
            String result = executeShellCommand(putCmd);
            if (result != null && !result.trim().isEmpty() && isErrorResult(result.trim())) {
                log(callback, "  ✗ " + result.trim());
                failCount++;
                return;
            }

            // 启用无障碍功能
            String enableCmd = "settings put secure accessibility_enabled 1";
            log(callback, "  $ " + enableCmd);
            executeShellCommand(enableCmd);

            log(callback, "  ✓ 成功");
            successCount++;

        } catch (Exception e) {
            log(callback, "  ✗ " + e.getMessage());
            failCount++;
        }
    }

    // ==================== 电池优化处理 ====================

    private void handleBatteryWhitelist(Callback callback) {
        log(callback, "[电池优化白名单]");
        String cmd = "dumpsys deviceidle whitelist +" + packageName;
        log(callback, "  $ " + cmd);

        try {
            String result = executeShellCommand(cmd);
            result = (result != null) ? result.trim() : "";

            if (result.isEmpty()) {
                log(callback, "  ✓ 成功");
                successCount++;
            } else if (result.toLowerCase().contains("added") || result.toLowerCase().contains("already")) {
                log(callback, "  ✓ " + result);
                successCount++;
            } else if (isErrorResult(result)) {
                log(callback, "  ✗ " + result);
                failCount++;
            } else {
                log(callback, "  → " + result);
                successCount++;
            }
        } catch (Exception e) {
            log(callback, "  ✗ " + e.getMessage());
            failCount++;
        }
    }

    // ==================== ADB 协议 - 消息收发 ====================

    /**
     * 发送 ADB 协议消息
     * 消息格式: command(4) + arg0(4) + arg1(4) + data_length(4) + data_checksum(4) + magic(4) + data
     */
    private void sendMessage(int command, int arg0, int arg1, byte[] data) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(24);
        header.order(ByteOrder.LITTLE_ENDIAN);
        header.putInt(command);
        header.putInt(arg0);
        header.putInt(arg1);
        header.putInt(data != null ? data.length : 0);
        header.putInt(data != null ? dataChecksum(data) : 0);
        header.putInt(command ^ 0xFFFFFFFF);

        socketOut.write(header.array());
        if (data != null && data.length > 0) {
            socketOut.write(data);
        }
        socketOut.flush();
    }

    /**
     * 读取一条 ADB 协议消息
     */
    private AdbMessage readMessage() throws IOException {
        byte[] headerBytes = readFully(24);
        ByteBuffer buf = ByteBuffer.wrap(headerBytes);
        buf.order(ByteOrder.LITTLE_ENDIAN);

        AdbMessage msg = new AdbMessage();
        msg.command = buf.getInt();
        msg.arg0 = buf.getInt();
        msg.arg1 = buf.getInt();
        int dataLength = buf.getInt();
        int dataCrc = buf.getInt();
        int magic = buf.getInt();

        if (dataLength > 0) {
            msg.data = readFully(dataLength);
        }

        return msg;
    }

    /**
     * 从输入流中完整读取指定字节数
     */
    private byte[] readFully(int length) throws IOException {
        byte[] data = new byte[length];
        int offset = 0;
        while (offset < length) {
            int read = socketIn.read(data, offset, length - offset);
            if (read == -1) {
                throw new IOException("ADB 连接已断开");
            }
            offset += read;
        }
        return data;
    }

    /**
     * 计算数据校验和（所有字节之和）
     */
    private static int dataChecksum(byte[] data) {
        int sum = 0;
        for (byte b : data) {
            sum += (b & 0xFF);
        }
        return sum;
    }

    // ==================== RSA 认证 ====================

    /**
     * 加载或生成 RSA 密钥对
     */
    private void loadOrGenerateKeyPair() throws Exception {
        File privateFile = new File(context.getFilesDir(), PRIVATE_KEY_FILE);
        File publicFile = new File(context.getFilesDir(), PUBLIC_KEY_FILE);

        if (privateFile.exists() && publicFile.exists()) {
            // 加载已有密钥
            byte[] privateBytes = readFileBytes(privateFile);
            byte[] publicBytes = readFileBytes(publicFile);

            KeyFactory kf = KeyFactory.getInstance("RSA");
            keyPair = new KeyPair(
                    kf.generatePublic(new X509EncodedKeySpec(publicBytes)),
                    kf.generatePrivate(new PKCS8EncodedKeySpec(privateBytes))
            );
        } else {
            // 生成新密钥对
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(RSA_KEY_BITS);
            keyPair = kpg.generateKeyPair();

            // 持久化保存
            writeFileBytes(privateFile, keyPair.getPrivate().getEncoded());
            writeFileBytes(publicFile, keyPair.getPublic().getEncoded());
        }
    }

    /**
     * 使用 RSA 私钥签名 ADB 认证 token
     *
     * ADB 协议中，token 被当作 SHA-1 摘要直接签名（PKCS#1 v1.5 + SHA-1 DigestInfo）
     */
    private byte[] signToken(byte[] token) throws Exception {
        // 构建 DigestInfo(SHA-1, token) 结构
        byte[] digestInfo = new byte[SHA1_DIGEST_INFO_PREFIX.length + token.length];
        System.arraycopy(SHA1_DIGEST_INFO_PREFIX, 0, digestInfo, 0, SHA1_DIGEST_INFO_PREFIX.length);
        System.arraycopy(token, 0, digestInfo, SHA1_DIGEST_INFO_PREFIX.length, token.length);

        // 使用 NONEwithRSA（不再哈希，直接 PKCS#1 v1.5 签名）
        Signature sig = Signature.getInstance("NONEwithRSA");
        sig.initSign(keyPair.getPrivate());
        sig.update(digestInfo);
        return sig.sign();
    }

    /**
     * 获取 ADB 格式的公钥数据
     *
     * 格式: Base64(AndroidRSAPublicKey struct) + " user@host\0"
     */
    private byte[] getAdbPublicKeyBytes() throws Exception {
        RSAPublicKey pubKey = (RSAPublicKey) keyPair.getPublic();
        byte[] encoded = encodeAndroidRsaPublicKey(pubKey);
        String base64 = Base64.encodeToString(encoded, Base64.NO_WRAP);
        String keyStr = base64 + " adb@evcam\0";
        return keyStr.getBytes("UTF-8");
    }

    /**
     * 将 RSA 公钥编码为 Android ADB 格式
     *
     * struct RSAPublicKey {
     *     uint32_t modulus_size_words;    // 模数长度（uint32_t 为单位）
     *     uint32_t n0inv;                 // -(n^(-1)) mod 2^32
     *     uint8_t  modulus[256];          // 模数（小端序）
     *     uint8_t  rr[256];              // R^2 mod n（小端序）
     *     uint32_t exponent;              // 公钥指数
     * }
     */
    private byte[] encodeAndroidRsaPublicKey(RSAPublicKey publicKey) {
        BigInteger modulus = publicKey.getModulus();
        BigInteger exponent = publicKey.getPublicExponent();

        int modulusBytes = RSA_KEY_BITS / 8; // 256
        int modulusWords = modulusBytes / 4;  // 64

        // 计算 n0inv = -(n^(-1)) mod 2^32
        BigInteger TWO32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0 = modulus.mod(TWO32);
        BigInteger n0inv = n0.modInverse(TWO32).negate().mod(TWO32);

        // 计算 rr = (2^(2*modulusBits)) mod n
        BigInteger rr = BigInteger.ONE.shiftLeft(RSA_KEY_BITS * 2).mod(modulus);

        // 编码为小端序字节数组
        byte[] modulusLE = bigIntToLittleEndian(modulus, modulusBytes);
        byte[] rrLE = bigIntToLittleEndian(rr, modulusBytes);

        // 打包结构体
        ByteBuffer buf = ByteBuffer.allocate(4 + 4 + modulusBytes + modulusBytes + 4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        buf.putInt(modulusWords);
        buf.putInt(n0inv.intValue());
        buf.put(modulusLE);
        buf.put(rrLE);
        buf.putInt(exponent.intValue());

        return buf.array();
    }

    /**
     * 将 BigInteger 转换为小端序字节数组
     */
    private static byte[] bigIntToLittleEndian(BigInteger value, int length) {
        byte[] bigEndian = value.toByteArray(); // 大端序，可能有前导 0x00
        byte[] result = new byte[length];

        // 跳过 BigInteger 可能添加的符号位前导零
        int srcLen = bigEndian.length;
        if (srcLen > length && bigEndian[0] == 0) {
            srcLen--;
        }

        // 逆序复制（大端 → 小端）
        int copyLen = Math.min(srcLen, length);
        for (int i = 0; i < copyLen; i++) {
            result[i] = bigEndian[bigEndian.length - 1 - i];
        }

        return result;
    }

    // ==================== 工具方法 ====================

    private boolean isErrorResult(String result) {
        String lower = result.toLowerCase();
        return lower.contains("exception") ||
                lower.contains("error") ||
                lower.contains("unknown permission") ||
                lower.contains("not found") ||
                lower.contains("failure") ||
                lower.contains("security") ||
                lower.contains("not allowed");
    }

    private void log(Callback callback, String message) {
        mainHandler.post(() -> callback.onLog(message));
    }

    private void notifyComplete(Callback callback, boolean allSuccess) {
        mainHandler.post(() -> callback.onComplete(allSuccess));
    }

    private void closeSocket() {
        try {
            if (socket != null) socket.close();
        } catch (Exception ignored) {
        }
        socket = null;
        socketIn = null;
        socketOut = null;
    }

    private static byte[] readFileBytes(File file) throws IOException {
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream fis = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int read = fis.read(data, offset, data.length - offset);
                if (read == -1) break;
                offset += read;
            }
        }
        return data;
    }

    private static void writeFileBytes(File file, byte[] data) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(data);
        }
    }
}
