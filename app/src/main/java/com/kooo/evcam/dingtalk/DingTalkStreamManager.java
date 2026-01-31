package com.kooo.evcam.dingtalk;


import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

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
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 5000; // 5秒

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
            return "状态信息不可用";
        }
        
        /**
         * 启动持续录制（模拟点击录制按钮）
         * @return 执行结果消息
         */
        default String onStartRecordingCommand() {
            return "功能不可用";
        }
        
        /**
         * 停止录制并退到后台
         * @return 执行结果消息
         */
        default String onStopRecordingCommand() {
            return "功能不可用";
        }
        
        /**
         * 退出应用（需二次确认）
         * @param confirmed 是否已确认
         * @return 执行结果消息
         */
        default String onExitCommand(boolean confirmed) {
            return "功能不可用";
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
                mainHandler.post(() -> callback.onConnected());

            } catch (Exception e) {
                AppLog.e(TAG, "启动 Stream 客户端失败", e);
                isRunning = false;

                // 如果启用了自动重连，尝试重连
                if (autoReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++;
                    AppLog.d(TAG, "将在 " + RECONNECT_DELAY_MS + "ms 后尝试第 " + reconnectAttempts + " 次重连");
                    mainHandler.postDelayed(() -> {
                        if (autoReconnect) { // 再次检查是否仍需要重连
                            startConnection();
                        }
                    }, RECONNECT_DELAY_MS);
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
                if (command.startsWith("录制") || command.toLowerCase().startsWith("record")) {
                    int durationSeconds = parseRecordDuration(command);
                    AppLog.d(TAG, "收到录制指令，时长: " + durationSeconds + " 秒");

                    // 发送确认消息，并在发送完成后执行录制命令
                    String confirmMsg = String.format("收到录制指令，开始录制 %d 秒视频...", durationSeconds);
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

                } else if ("拍照".equals(command) || "photo".equalsIgnoreCase(command)) {
                    AppLog.d(TAG, "收到拍照指令");

                    // 发送确认消息，并在发送完成后执行拍照命令
                    String finalConversationId = conversationId;
                    String finalConversationType = conversationType;
                    String finalSenderId = senderId;
                    
                    sendResponseAndThen(sessionWebhook, "收到拍照指令，正在拍照...", () -> {
                        // 使用 WakeUpHelper 唤醒屏幕并启动 Activity
                        // 这样可以确保在后台时也能正常拍照
                        AppLog.d(TAG, "使用 WakeUpHelper 启动拍照...");
                        WakeUpHelper.launchForPhoto(context, 
                            finalConversationId, finalConversationType, finalSenderId);
                    });

                } else if ("状态".equals(command) || "status".equalsIgnoreCase(command)) {
                    // 状态指令：显示应用状态
                    AppLog.d(TAG, "收到状态指令");
                    String statusInfo = commandCallback != null ? 
                            commandCallback.getStatusInfo() : "状态信息不可用";
                    sendResponse(sessionWebhook, statusInfo);

                } else if ("启动录制".equals(command) || "开始录制".equals(command) || 
                           "start".equalsIgnoreCase(command)) {
                    // 启动录制指令：唤醒到前台并开始持续录制
                    AppLog.d(TAG, "收到启动录制指令");
                    if (commandCallback != null) {
                        String result = commandCallback.onStartRecordingCommand();
                        sendResponse(sessionWebhook, result);
                    } else {
                        sendResponse(sessionWebhook, "❌ 功能不可用");
                    }

                } else if ("结束录制".equals(command) || "停止录制".equals(command) || 
                           "stop".equalsIgnoreCase(command)) {
                    // 结束录制指令：停止录制并退到后台
                    AppLog.d(TAG, "收到结束录制指令");
                    if (commandCallback != null) {
                        String result = commandCallback.onStopRecordingCommand();
                        sendResponse(sessionWebhook, result);
                    } else {
                        sendResponse(sessionWebhook, "❌ 功能不可用");
                    }

                } else if ("退出".equals(command) || "exit".equalsIgnoreCase(command)) {
                    // 退出指令：需要二次确认
                    AppLog.d(TAG, "收到退出指令（需二次确认）");
                    sendResponse(sessionWebhook, 
                        "⚠️ 确认要退出 EVCam 吗？\n\n" +
                        "退出后将停止所有录制和远程服务。\n" +
                        "发送「确认退出」执行退出操作。");

                } else if ("确认退出".equals(command)) {
                    // 确认退出指令：执行退出
                    AppLog.d(TAG, "收到确认退出指令");
                    if (commandCallback != null) {
                        String result = commandCallback.onExitCommand(true);
                        sendResponse(sessionWebhook, result);
                    } else {
                        sendResponse(sessionWebhook, "❌ 功能不可用");
                    }

                } else if ("帮助".equals(command) || "help".equalsIgnoreCase(command)) {
                    sendResponse(sessionWebhook,
                        "可用指令：\n" +
                        "• 状态 - 查看应用状态\n" +
                        "• 启动录制 - 开始持续录制\n" +
                        "• 结束录制 - 停止录制并退到后台\n" +
                        "• 录制 - 录制 60 秒视频\n" +
                        "• 录制+数字 - 录制指定秒数（如：录制30）\n" +
                        "• 拍照 - 拍摄照片\n" +
                        "• 退出 - 退出应用（需确认）\n" +
                        "• 帮助 - 显示此帮助");

                } else {
                    AppLog.d(TAG, "未识别的指令: " + command);
                    sendResponse(sessionWebhook,
                        "未识别的指令。发送「帮助」查看可用指令。");
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

            // 移除"录制"或"record"关键字，提取数字
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
