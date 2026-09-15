package com.kooo.evcam.dingtalk;


import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;

import com.dingtalk.open.app.api.OpenDingTalkClient;
import com.dingtalk.open.app.api.OpenDingTalkStreamClientBuilder;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.security.AuthClientCredential;
import com.dingtalk.open.app.stream.protocol.event.EventAckStatus;

import org.json.JSONObject;

/**
 * 钉钉 Stream 客户端管理器
 * 使用官方 app-stream-client SDK
 */
public class DingTalkStreamManager {
    private static final String TAG = "DingTalkStreamManager";
    private static final long RECONNECT_DELAY_MS = 5000; // 初始重连延迟5秒
    private static final long MAX_RECONNECT_DELAY_MS = 60_000; // 最大重连延迟60秒（指数退避上限）

    // 钉钉官方事件主题
    private static final String BOT_MESSAGE_TOPIC = "/v1.0/im/bot/messages/get";

    private final Context context;
    private final DingTalkConfig config;
    private final DingTalkApiClient apiClient;
    private final ConnectionCallback callback;
    private final Handler mainHandler;

    private OpenDingTalkClient streamClient;
    private ChatbotMessageListener messageListener;
    private boolean isRunning = false;
    private boolean autoReconnect = false;
    private int reconnectAttempts = 0;
    private CommandCallback currentCommandCallback;

    // 网络状态监控（用于深度休眠唤醒后自动重连）
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean networkWasLost = false;
    private Runnable pendingReconnectRunnable;
    private Runnable reconnectCheckRunnable;
    private static final long RECONNECT_AFTER_NETWORK_DELAY_MS = 10_000; // 网络恢复后等10秒再重连（深度休眠唤醒后网络栈需要时间就绪）
    private static final long RECONNECT_CHECK_INTERVAL_MS = 120_000; // 2分钟安全网检查

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public interface CommandCallback {
        void onRecordCommand(String conversationId, String conversationType, String userId, int durationSeconds);
        void onPhotoCommand(String conversationId, String conversationType, String userId);
        
        /**
         * 获取应用状态信息
         * @return 状态信息字符串
         */
        default String getStatusInfo() {
            return "Status information unavailable";
        }
        
        /**
         * 启动持续录制（模拟点击录制按钮）
         * @return 执行结果消息
         */
        default String onStartRecordingCommand() {
            return "Feature unavailable";
        }
        
        /**
         * 停止录制并退到后台
         * @return 执行结果消息
         */
        default String onStopRecordingCommand() {
            return "Feature unavailable";
        }
        
        /**
         * 退出应用（需二次确认）
         * @param confirmed 是否已确认
         * @return 执行结果消息
         */
        default String onExitCommand(boolean confirmed) {
            return "Feature unavailable";
        }
        
        /**
         * 切换到前台
         * @return 执行结果消息
         */
        default String onForegroundCommand() {
            return "Feature unavailable";
        }
        
        /**
         * 切换到后台
         * @return 执行结果消息
         */
        default String onBackgroundCommand() {
            return "Feature unavailable";
        }
    }

    public DingTalkStreamManager(Context context, DingTalkConfig config,
                                  DingTalkApiClient apiClient, ConnectionCallback callback) {
        this.context = context;
        this.config = config;
        this.apiClient = apiClient;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 启动 Stream 连接
     * @param commandCallback 指令回调
     */
    public void start(CommandCallback commandCallback) {
        start(commandCallback, false);
    }

    /**
     * 启动 Stream 连接
     * @param commandCallback 指令回调
     * @param enableAutoReconnect 是否启用自动重连
     */
    public void start(CommandCallback commandCallback, boolean enableAutoReconnect) {
        if (isRunning) {
            AppLog.w(TAG, "Stream 客户端已在运行");
            return;
        }

        this.currentCommandCallback = commandCallback;
        this.autoReconnect = enableAutoReconnect;
        this.reconnectAttempts = 0;

        startConnection();
    }

    /**
     * 内部方法：启动连接
     */
    private void startConnection() {
        if (isRunning) {
            AppLog.w(TAG, "Stream 客户端已在运行");
            return;
        }

        new Thread(() -> {
            try {
                AppLog.d(TAG, "正在初始化钉钉 Stream 客户端...");

                // 创建消息监听器
                messageListener = new ChatbotMessageListener(context, apiClient, currentCommandCallback, mainHandler);

                // 使用官方 SDK 构建客户端
                streamClient = OpenDingTalkStreamClientBuilder.custom()
                        .credential(new AuthClientCredential(
                                config.getClientId(),
                                config.getClientSecret()
                        ))
                        .registerCallbackListener(BOT_MESSAGE_TOPIC, messageListener)
                        .build();

                AppLog.d(TAG, "Stream 客户端已创建，正在启动连接...");

                // 启动连接
                streamClient.start();

                isRunning = true;
                reconnectAttempts = 0; // 重置重连计数
                AppLog.d(TAG, "Stream 客户端已启动");

                // 通知连接成功
                mainHandler.post(() -> {
                    callback.onConnected();
                    // 注册网络状态监控（用于深度休眠唤醒后自动重连）
                    registerNetworkCallback();
                    startReconnectCheck();
                });

            } catch (Exception e) {
                AppLog.e(TAG, "启动 Stream 客户端失败", e);
                isRunning = false;

                // 如果启用了自动重连，使用指数退避无限重试
                if (autoReconnect) {
                    reconnectAttempts++;
                    // 指数退避：5s, 10s, 20s, 40s, 60s, 60s, ...
                    long delay = Math.min(RECONNECT_DELAY_MS * (1L << Math.min(reconnectAttempts - 1, 4)), MAX_RECONNECT_DELAY_MS);
                    AppLog.d(TAG, "将在 " + delay + "ms 后尝试第 " + reconnectAttempts + " 次重连");
                    mainHandler.postDelayed(() -> {
                        if (autoReconnect) {
                            startConnection();
                        }
                    }, delay);
                } else {
                    mainHandler.post(() -> callback.onError("启动失败: " + e.getMessage()));
                }
            }
        }).start();
    }

    /**
     * 停止 Stream 连接
     */
    public void stop() {
        if (!isRunning) {
            return;
        }

        // 禁用自动重连
        autoReconnect = false;
        reconnectAttempts = 0;

        // 清理网络监控和定时检查
        unregisterNetworkCallback();
        stopReconnectCheck();
        networkWasLost = false;
        if (pendingReconnectRunnable != null) {
            mainHandler.removeCallbacks(pendingReconnectRunnable);
            pendingReconnectRunnable = null;
        }

        new Thread(() -> {
            try {
                if (streamClient != null) {
                    AppLog.d(TAG, "正在停止 Stream 客户端...");
                    // OpenDingTalkClient doesn't have a close() method
                    // Just set to null to allow garbage collection
                    streamClient = null;
                }

                isRunning = false;
                AppLog.d(TAG, "Stream 客户端已停止");

                mainHandler.post(() -> callback.onDisconnected());

            } catch (Exception e) {
                AppLog.e(TAG, "停止 Stream 客户端失败", e);
            }
        }).start();
    }

    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return isRunning;
    }

    /**
     * 强制重连（用于深度休眠唤醒后连接丢失的场景）
     * @param reason 重连原因（用于日志）
     */
    public synchronized void forceReconnect(String reason) {
        if (!autoReconnect) {
            AppLog.w(TAG, "自动重连未启用，跳过强制重连");
            return;
        }

        AppLog.d(TAG, "强制重连钉钉 Stream (" + reason + ")");

        // 清理旧连接的网络监控（安全网检查保留，让它持续守护）
        unregisterNetworkCallback();

        // 销毁旧连接
        try {
            streamClient = null;
        } catch (Exception e) {
            AppLog.e(TAG, "销毁旧 Stream 客户端失败", e);
        }

        boolean wasRunning = isRunning;
        isRunning = false;
        reconnectAttempts = 0;

        // 仅之前在运行时通知断开
        if (wasRunning) {
            mainHandler.post(() -> callback.onDisconnected());
        }

        // 取消之前可能存在的重连任务
        if (pendingReconnectRunnable != null) {
            mainHandler.removeCallbacks(pendingReconnectRunnable);
        }

        // 延迟后启动新连接
        pendingReconnectRunnable = () -> {
            if (autoReconnect && !isRunning) {
                AppLog.d(TAG, "开始重新建立 Stream 连接...");
                startConnection();
            }
        };
        mainHandler.postDelayed(pendingReconnectRunnable, RECONNECT_DELAY_MS);
    }

    /**
     * 注册网络状态回调
     * 用于检测深度休眠唤醒后网络恢复，自动触发重连
     */
    private void registerNetworkCallback() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                AppLog.w(TAG, "ConnectivityManager 不可用，跳过网络监控");
                return;
            }

            unregisterNetworkCallback();

            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    if (networkWasLost && autoReconnect) {
                        AppLog.d(TAG, "网络恢复（深度休眠唤醒），" + RECONNECT_AFTER_NETWORK_DELAY_MS + "ms 后重连");
                        networkWasLost = false;
                        mainHandler.postDelayed(() -> forceReconnect("Network restored (woke from deep sleep)"), RECONNECT_AFTER_NETWORK_DELAY_MS);
                    }
                }

                @Override
                public void onLost(Network network) {
                    AppLog.d(TAG, "网络连接丢失（可能进入深度休眠），标记需要重连");
                    networkWasLost = true;
                }
            };

            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            cm.registerNetworkCallback(request, networkCallback);
            AppLog.d(TAG, "网络状态回调已注册（监控深度休眠唤醒）");

        } catch (Exception e) {
            AppLog.e(TAG, "注册网络状态回调失败", e);
        }
    }

    /**
     * 注销网络状态回调
     */
    private void unregisterNetworkCallback() {
        try {
            if (networkCallback != null) {
                ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                if (cm != null) {
                    cm.unregisterNetworkCallback(networkCallback);
                }
                networkCallback = null;
            }
        } catch (Exception e) {
            AppLog.e(TAG, "注销网络状态回调失败", e);
        }
    }

    /**
     * 启动定时重连安全网检查
     * 每隔一段时间检查网络状态，防止 onAvailable 回调被遗漏
     */
    private void startReconnectCheck() {
        stopReconnectCheck();
        reconnectCheckRunnable = new Runnable() {
            @Override
            public void run() {
                if (!autoReconnect) return;

                try {
                    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
                    boolean hasNetwork = cm != null && cm.getActiveNetwork() != null;

                    if (!isRunning && hasNetwork) {
                        // 连接断了但网络可用 → 可能重连失败了，再次触发重连
                        AppLog.w(TAG, "安全网检查：连接未运行但网络可用，触发重连");
                        networkWasLost = false;
                        forceReconnect("Safety check (disconnected)");
                    } else if (isRunning && networkWasLost && hasNetwork) {
                        // 运行中但网络曾丢失且已恢复 → onAvailable 可能被遗漏
                        AppLog.w(TAG, "安全网检查：网络已恢复但未收到回调，强制重连");
                        networkWasLost = false;
                        forceReconnect("Safety check (network restore missed)");
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "安全网检查失败", e);
                }

                // 无论如何都继续下一轮检查
                mainHandler.postDelayed(this, RECONNECT_CHECK_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(reconnectCheckRunnable, RECONNECT_CHECK_INTERVAL_MS);
    }

    /**
     * 停止定时重连安全网检查
     */
    private void stopReconnectCheck() {
        if (reconnectCheckRunnable != null) {
            mainHandler.removeCallbacks(reconnectCheckRunnable);
            reconnectCheckRunnable = null;
        }
    }

    /**
     * 机器人消息监听器
     * 实现官方 SDK 的回调接口
     */
    private static class ChatbotMessageListener implements OpenDingTalkCallbackListener<String, EventAckStatus> {
        private static final String TAG = "ChatbotMessageListener";

        private final Context context;
        private final DingTalkApiClient apiClient;
        private final CommandCallback commandCallback;
        private final Handler mainHandler;

        public ChatbotMessageListener(Context context, DingTalkApiClient apiClient,
                                       CommandCallback commandCallback, Handler mainHandler) {
            this.context = context;
            this.apiClient = apiClient;
            this.commandCallback = commandCallback;
            this.mainHandler = mainHandler;
        }

        @Override
        public EventAckStatus execute(String messageJson) {
            try {
                // 记录原始消息用于调试
                AppLog.d(TAG, "收到原始消息JSON: " + messageJson);

                // 解析 JSON 字符串
                JSONObject message = new JSONObject(messageJson);
                AppLog.d(TAG, "解析后的消息对象: " + message.toString());

                String content = null;
                String conversationId = null;
                String conversationType = null;
                String senderId = null;
                String sessionWebhook = null;

                // 解析文本内容 - 钉钉机器人消息格式
                if (message.has("text")) {
                    JSONObject textObj = message.getJSONObject("text");
                    content = textObj.optString("content", "");
                } else if (message.has("content")) {
                    // 有些情况下可能直接是 content 字段
                    JSONObject contentObj = message.getJSONObject("content");
                    if (contentObj.has("text")) {
                        content = contentObj.optString("text", "");
                    }
                }

                // 解析会话ID、会话类型和发送者ID
                conversationId = message.optString("conversationId", "");
                if (conversationId.isEmpty()) {
                    conversationId = message.optString("openConversationId", "");
                }

                // 解析会话类型：1=单聊，2=群聊
                conversationType = message.optString("conversationType", "");

                senderId = message.optString("senderStaffId", "");
                if (senderId.isEmpty()) {
                    senderId = message.optString("senderId", "");
                }

                // 获取 sessionWebhook（用于回复消息）
                sessionWebhook = message.optString("sessionWebhook", "");

                // 如果消息为空，可能是其他类型的事件（如加入群聊等），直接返回成功
                if (content == null || content.isEmpty()) {
                    AppLog.d(TAG, "消息内容为空，可能是非文本消息或系统事件");
                    AppLog.d(TAG, "完整消息结构: " + message.toString(2));
                    return EventAckStatus.SUCCESS;
                }

                AppLog.d(TAG, "解析成功 - 内容: " + content);
                AppLog.d(TAG, "解析成功 - 会话ID: " + conversationId);
                AppLog.d(TAG, "解析成功 - 会话类型: " + conversationType);
                AppLog.d(TAG, "解析成功 - 发送者ID: " + senderId);
                AppLog.d(TAG, "解析成功 - SessionWebhook: " + sessionWebhook);

                // 检查 sessionWebhook 是否有效
                if (sessionWebhook.isEmpty()) {
                    AppLog.w(TAG, "SessionWebhook 为空，无法回复");
                    return EventAckStatus.SUCCESS;
                }

                // 解析指令
                String command = parseCommand(content);
                AppLog.d(TAG, "解析的指令: " + command);

                // 判断是否是录制指令，只有录制指令才解析时长
                if (command.startsWith("record") || command.toLowerCase().startsWith("record")) {
                    int durationSeconds = parseRecordDuration(command);
                    AppLog.d(TAG, "收到录制指令，时长: " + durationSeconds + " 秒");

                    // 发送确认消息，并在发送完成后执行录制命令
                    String confirmMsg = String.format("Received record command, recording %d second video...", durationSeconds);
                    String finalConversationId = conversationId;
                    String finalConversationType = conversationType;
                    String finalSenderId = senderId;
                    int finalDuration = durationSeconds;
                    
                    sendResponseAndThen(sessionWebhook, confirmMsg, () -> {
                        // 使用 WakeUpHelper 唤醒屏幕并启动 Activity
                        // 这样可以确保在后台时也能正常录制
                        AppLog.d(TAG, "使用 WakeUpHelper 启动录制...");
                        WakeUpHelper.launchForRecording(context, 
                            finalConversationId, finalConversationType, finalSenderId, finalDuration);
                    });

                } else if ("photo".equals(command) || "photo".equalsIgnoreCase(command)) {
                    AppLog.d(TAG, "收到拍照指令");

                    // 发送确认消息，并在发送完成后执行拍照命令
                    String finalConversationId = conversationId;
                    String finalConversationType = conversationType;
                    String finalSenderId = senderId;
                    
                    sendResponseAndThen(sessionWebhook, "Received photo command, taking photo...", () -> {
                        // 使用 WakeUpHelper 唤醒屏幕并启动 Activity
                        // 这样可以确保在后台时也能正常拍照
                        AppLog.d(TAG, "使用 WakeUpHelper 启动拍照...");
                        WakeUpHelper.launchForPhoto(context, 
                            finalConversationId, finalConversationType, finalSenderId);
                    });

                } else if ("status".equals(command) || "status".equalsIgnoreCase(command)) {
                    // 状态指令：显示应用状态
                    AppLog.d(TAG, "收到状态指令");
                    String statusInfo = commandCallback != null ? 
                            commandCallback.getStatusInfo() : "Status information unavailable";
                    sendResponse(sessionWebhook, statusInfo);

                } else if ("start recording".equals(command) || "start record".equals(command) || 
                           "start".equalsIgnoreCase(command)) {
                    // 启动录制指令：唤醒到前台并开始持续录制
                    AppLog.d(TAG, "收到启动录制指令");
                    if (commandCallback != null) {
                        String result = commandCallback.onStartRecordingCommand();
                        sendResponse(sessionWebhook, result);
                    } else {
                        sendResponse(sessionWebhook, "❌ Feature unavailable");
                    }

                } else if ("stop recording".equals(command) || "stop record".equals(command) || 
                           "stop".equalsIgnoreCase(command)) {
                    // 结束录制指令：停止录制并退到后台
                    AppLog.d(TAG, "收到结束录制指令");
                    if (commandCallback != null) {
                        String result = commandCallback.onStopRecordingCommand();
                        sendResponse(sessionWebhook, result);
                    } else {
                        sendResponse(sessionWebhook, "❌ Feature unavailable");
                    }

                } else if ("exit".equals(command) || "exit".equalsIgnoreCase(command)) {
                    // 退出指令：需要二次确认
                    AppLog.d(TAG, "收到退出指令（需二次确认）");
                    sendResponse(sessionWebhook, 
                        "⚠️ Are you sure you want to exit EVDashcam?\n\n" +
                        "All recording and remote services will stop.\n" +
                        "Send \"confirm exit\" to proceed.");

                } else if ("confirm exit".equals(command)) {
                    // 确认退出指令：执行退出
                    AppLog.d(TAG, "收到确认退出指令");
                    if (commandCallback != null) {
                        String result = commandCallback.onExitCommand(true);
                        sendResponse(sessionWebhook, result);
                    } else {
                        sendResponse(sessionWebhook, "❌ Feature unavailable");
                    }

                } else if ("foreground".equals(command) || "foreground".equalsIgnoreCase(command)) {
                    // 前台指令：将应用切换到前台
                    AppLog.d(TAG, "收到前台指令");
                    if (commandCallback != null) {
                        String result = commandCallback.onForegroundCommand();
                        sendResponse(sessionWebhook, result);
                    } else {
                        sendResponse(sessionWebhook, "❌ Feature unavailable");
                    }

                } else if ("background".equals(command) || "background".equalsIgnoreCase(command)) {
                    // 后台指令：将应用切换到后台
                    AppLog.d(TAG, "收到后台指令");
                    if (commandCallback != null) {
                        String result = commandCallback.onBackgroundCommand();
                        sendResponse(sessionWebhook, result);
                    } else {
                        sendResponse(sessionWebhook, "❌ Feature unavailable");
                    }

                } else if ("help".equals(command) || "help".equalsIgnoreCase(command)) {
                    sendResponse(sessionWebhook,
                        "Available commands:\n" +
                        "• status - View app status\n" +
                        "• foreground - Switch app to foreground\n" +
                        "• background - Switch app to background\n" +
                        "• start recording - Start continuous recording\n" +
                        "• stop recording - Stop recording and return to background\n" +
                        "• record - Record 60-second video\n" +
                        "• record+seconds - Record specified duration (e.g. record 30)\n" +
                        "• photo - Take photo\n" +
                        "• exit - Exit app (requires confirmation)\n" +
                        "• help - Show this help");

                } else {
                    AppLog.d(TAG, "未识别的指令: " + command);
                    sendResponse(sessionWebhook,
                        "Unrecognized command. Send \"help\" to view available commands.");
                }

                return EventAckStatus.SUCCESS;

            } catch (Exception e) {
                AppLog.e(TAG, "处理机器人消息失败", e);
                return EventAckStatus.LATER;
            }
        }

        /**
         * 解析指令文本
         * 移除 @机器人 的部分，提取实际指令
         */
        private String parseCommand(String text) {
            if (text == null) {
                return "";
            }

            // 移除 @xxx 部分和多余空格
            String command = text.replaceAll("@\\S+\\s*", "").trim();
            return command;
        }

        /**
         * 解析录制时长（秒）
         * 支持格式：录制、录制30、录制 30、record、record 30
         * 默认返回 60 秒（1分钟）
         */
        private int parseRecordDuration(String command) {
            if (command == null || command.isEmpty()) {
                return 60;
            }

            // 移除"record"或"record"关键字，提取数字
            String durationStr = command.replaceAll("(?i)(录制|record)", "").trim();

            if (durationStr.isEmpty()) {
                return 60; // 默认 1 分钟
            }

            try {
                int duration = Integer.parseInt(durationStr);
                // 限制范围：最少 5 秒，最多 600 秒（10分钟）
                if (duration < 5) {
                    return 5;
                } else if (duration > 600) {
                    return 600;
                }
                return duration;
            } catch (NumberFormatException e) {
                AppLog.w(TAG, "无法解析录制时长: " + durationStr + "，使用默认值 60 秒");
                return 60;
            }
        }

        /**
         * 发送响应消息到钉钉（使用 sessionWebhook）
         */
        private void sendResponse(String sessionWebhook, String message) {
            new Thread(() -> {
                try {
                    apiClient.sendMessageViaWebhook(sessionWebhook, message);
                    AppLog.d(TAG, "响应消息已发送: " + message);
                } catch (Exception e) {
                    AppLog.e(TAG, "发送响应消息失败", e);
                }
            }).start();
        }

        /**
         * 发送响应消息到钉钉，并在发送完成后执行回调
         * @param sessionWebhook Webhook URL
         * @param message 消息内容
         * @param callback 发送完成后的回调
         */
        private void sendResponseAndThen(String sessionWebhook, String message, Runnable callback) {
            new Thread(() -> {
                try {
                    // 发送确认消息
                    apiClient.sendMessageViaWebhook(sessionWebhook, message);
                    AppLog.d(TAG, "响应消息已发送: " + message);
                    
                    // 发送成功后执行回调
                    if (callback != null) {
                        callback.run();
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "发送响应消息失败", e);
                    // 即使发送失败，也执行回调（避免命令被阻塞）
                    if (callback != null) {
                        callback.run();
                    }
                }
            }).start();
        }
    }
}
