package com.kooo.evcam.telegram;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * Telegram Bot 消息轮询管理器
 * 使用 Long Polling 方式接收消息
 */
public class TelegramBotManager {
    private static final String TAG = "TelegramBotManager";
    private static final int POLL_TIMEOUT = 30; // 长轮询超时时间（秒）
    private static final int POLL_LIMIT = 5; // 每次拉取的消息数量限制
    private static final int MESSAGE_EXPIRE_SECONDS = 600; // 消息过期时间（10分钟 = 600秒）
    private static final int MAX_RECONNECT_ATTEMPTS = 5;
    private static final long RECONNECT_DELAY_MS = 5000; // 5秒
    private static final long CONFLICT_RETRY_DELAY_MS = 10000; // 409 冲突时等待 10 秒再重试
    private static final int MAX_CONFLICT_RETRIES = 3; // 最大冲突重试次数

    private final Context context;
    private final TelegramConfig config;
    private final TelegramApiClient apiClient;
    private final ConnectionCallback connectionCallback;
    private final Handler mainHandler;

    private volatile boolean isRunning = false;
    private volatile boolean shouldStop = false;
    private Thread pollingThread;
    private int reconnectAttempts = 0;
    private int conflictRetries = 0;
    private CommandCallback currentCommandCallback;

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
    }

    public interface CommandCallback {
        void onRecordCommand(long chatId, int durationSeconds);
        void onPhotoCommand(long chatId);
        String getStatusInfo();
        String onStartRecordingCommand();
        String onStopRecordingCommand();
        String onExitCommand(boolean confirmed);
        
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

    public TelegramBotManager(Context context, TelegramConfig config,
                               TelegramApiClient apiClient, ConnectionCallback callback) {
        this.context = context;
        this.config = config;
        this.apiClient = apiClient;
        this.connectionCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 启动消息轮询
     */
    public synchronized void start(CommandCallback commandCallback) {
        if (isRunning) {
            AppLog.w(TAG, "Bot 已在运行");
            return;
        }

        // 立即设置为运行状态，防止重复启动
        isRunning = true;
        
        this.currentCommandCallback = commandCallback;
        this.shouldStop = false;
        this.reconnectAttempts = 0;
        this.conflictRetries = 0;

        startPolling();
    }

    /**
     * 内部方法：启动轮询线程
     */
    private void startPolling() {
        pollingThread = new Thread(() -> {
            try {
                AppLog.d(TAG, "正在验证 Bot Token...");

                // 验证 Token
                JsonObject botInfo = apiClient.getMe();
                String botUsername = botInfo.get("username").getAsString();
                AppLog.d(TAG, "Bot 验证成功: @" + botUsername);

                // 清除可能存在的旧连接（发送一个无等待的请求来"抢占"连接）
                AppLog.d(TAG, "清除旧连接状态...");
                try {
                    // 使用 timeout=0 立即返回，这会断开其他可能存在的长轮询连接
                    apiClient.getUpdates(-1, 0, 1);
                    Thread.sleep(500); // 短暂等待
                } catch (Exception e) {
                    AppLog.d(TAG, "清除旧连接: " + e.getMessage());
                    // 如果是 409 错误，等待更长时间
                    if (e.getMessage() != null && e.getMessage().contains("409")) {
                        AppLog.d(TAG, "检测到 409 冲突，等待旧连接断开...");
                        Thread.sleep(3000);
                    }
                }

                // isRunning 已在 start() 中设置
                reconnectAttempts = 0;

                // 通知连接成功
                mainHandler.post(() -> connectionCallback.onConnected());

                // 开始长轮询
                long offset = config.getLastUpdateId() + 1;

                while (!shouldStop) {
                    try {
                        JsonArray updates = apiClient.getUpdates(offset, POLL_TIMEOUT, POLL_LIMIT);
                        long currentTime = System.currentTimeMillis() / 1000; // 当前时间（秒）

                        for (int i = 0; i < updates.size(); i++) {
                            JsonObject update = updates.get(i).getAsJsonObject();
                            long updateId = update.get("update_id").getAsLong();

                            // 处理消息
                            if (update.has("message")) {
                                JsonObject message = update.getAsJsonObject("message");

                                // 检查消息时间，忽略超过 10 分钟的旧消息
                                if (message.has("date")) {
                                    long messageTime = message.get("date").getAsLong();
                                    long messageAge = currentTime - messageTime;

                                    if (messageAge > MESSAGE_EXPIRE_SECONDS) {
                                        AppLog.d(TAG, "忽略过期消息，消息时间: " + messageTime +
                                                ", elapsed " + messageAge + " s");
                                        // 仍然更新 offset，避免重复拉取
                                        offset = updateId + 1;
                                        config.saveLastUpdateId(updateId);
                                        continue;
                                    }
                                }

                                processMessage(message);
                            }

                            // 更新 offset
                            offset = updateId + 1;
                            config.saveLastUpdateId(updateId);
                        }

                    } catch (Exception e) {
                        if (!shouldStop) {
                            String errorMsg = e.getMessage();
                            AppLog.e(TAG, "轮询出错: " + errorMsg);
                            
                            // 检查是否是 409 冲突错误
                            if (errorMsg != null && errorMsg.contains("409")) {
                                conflictRetries++;
                                if (conflictRetries >= MAX_CONFLICT_RETRIES) {
                                    // 只记录日志，不弹窗（不影响实际连接）
                                    AppLog.w(TAG, "409 冲突错误达到最大重试次数，可能有其他设备在运行此 Bot");
                                    shouldStop = true;
                                    break;
                                }
                                AppLog.d(TAG, "409 冲突，等待 " + CONFLICT_RETRY_DELAY_MS + "ms 后重试（第 " + conflictRetries + " 次）");
                                Thread.sleep(CONFLICT_RETRY_DELAY_MS);
                            } else {
                                // 其他错误，短暂休眠后继续
                                conflictRetries = 0; // 重置冲突计数
                                Thread.sleep(1000);
                            }
                        }
                    }
                }

            } catch (Exception e) {
                AppLog.e(TAG, "启动 Bot 失败", e);
                isRunning = false;

                // 尝试重连
                if (!shouldStop && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    reconnectAttempts++;
                    AppLog.d(TAG, "将在 " + RECONNECT_DELAY_MS + "ms 后尝试第 " + reconnectAttempts + " 次重连");
                    mainHandler.postDelayed(() -> {
                        if (!shouldStop) {
                            startPolling();
                        }
                    }, RECONNECT_DELAY_MS);
                } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                    // 只记录日志，不弹窗
                    AppLog.w(TAG, "达到最大重连次数（" + MAX_RECONNECT_ATTEMPTS + "），启动失败: " + e.getMessage());
                }
            }

            isRunning = false;
            if (shouldStop) {
                mainHandler.post(() -> connectionCallback.onDisconnected());
            }
        });

        pollingThread.setName("TelegramPolling");
        pollingThread.start();
    }

    /**
     * 处理收到的消息
     */
    private void processMessage(JsonObject message) {
        try {
            // 获取 chat 信息
            JsonObject chat = message.getAsJsonObject("chat");
            long chatId = chat.get("id").getAsLong();
            String chatType = chat.get("type").getAsString(); // private, group, supergroup, channel

            // 检查是否允许此 chat
            if (!config.isChatIdAllowed(chatId)) {
                AppLog.d(TAG, "Chat ID 不在白名单中: " + chatId);
                return;
            }

            // 获取消息文本
            if (!message.has("text")) {
                return; // 非文本消息，忽略
            }

            String text = message.get("text").getAsString();
            AppLog.d(TAG, "收到消息 - chatId: " + chatId + ", type: " + chatType + ", text: " + text);

            // 解析指令
            String command = parseCommand(text);
            AppLog.d(TAG, "解析的指令: " + command);

            // 处理指令
            if (command.startsWith("/record") || command.startsWith("record") ||
                command.toLowerCase().startsWith("record")) {

                int durationSeconds = parseRecordDuration(command);
                AppLog.d(TAG, "收到录制指令，时长: " + durationSeconds + " s");

                // 发送确认消息
                String confirmMsg = String.format("Received record command, recording %d second video...", durationSeconds);
                sendResponseAndThen(chatId, confirmMsg, () -> {
                    // 使用 WakeUpHelper 唤醒并启动录制
                    AppLog.d(TAG, "使用 WakeUpHelper 启动录制...");
                    WakeUpHelper.launchForRecordingTelegram(context, chatId, durationSeconds);
                });

            } else if ("/photo".equals(command) || "photo".equals(command) ||
                       "photo".equalsIgnoreCase(command)) {

                AppLog.d(TAG, "收到拍照指令");

                // 发送确认消息
                sendResponseAndThen(chatId, "Received photo command, taking photo...", () -> {
                    // 使用 WakeUpHelper 唤醒并启动拍照
                    AppLog.d(TAG, "使用 WakeUpHelper 启动拍照...");
                    WakeUpHelper.launchForPhotoTelegram(context, chatId);
                });

            } else if ("/status".equals(command) || "status".equals(command)) {
                // 状态指令：显示应用详细状态
                AppLog.d(TAG, "收到状态指令");
                String statusInfo = currentCommandCallback != null ? 
                        currentCommandCallback.getStatusInfo() : "✅ Bot is running";
                apiClient.sendMessage(chatId, statusInfo);

            } else if ("start recording".equals(command) || "start record".equals(command) || 
                       "/start_rec".equals(command) || "start".equalsIgnoreCase(command)) {
                // 启动录制指令：唤醒到前台并开始持续录制
                AppLog.d(TAG, "收到启动录制指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onStartRecordingCommand();
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ Feature unavailable");
                }

            } else if ("stop recording".equals(command) || "stop record".equals(command) || 
                       "/stop_rec".equals(command) || "stop".equalsIgnoreCase(command)) {
                // 结束录制指令：停止录制并退到后台
                AppLog.d(TAG, "收到结束录制指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onStopRecordingCommand();
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ Feature unavailable");
                }

            } else if ("exit".equals(command) || "/exit".equals(command) || 
                       "exit".equalsIgnoreCase(command)) {
                // 退出指令：需要二次确认
                AppLog.d(TAG, "收到退出指令（需二次确认）");
                apiClient.sendMessage(chatId, 
                    "⚠️ Are you sure you want to exit EVDashcam?\n\n" +
                    "All recording and remote services will stop.\n" +
                    "Send /confirm_exit to proceed.");

            } else if ("confirm exit".equals(command) || "/confirm_exit".equals(command)) {
                // 确认退出指令：执行退出
                AppLog.d(TAG, "收到确认退出指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onExitCommand(true);
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ Feature unavailable");
                }

            } else if ("foreground".equals(command) || "/foreground".equals(command) ||
                       "foreground".equalsIgnoreCase(command)) {
                // 前台指令：将应用切换到前台
                AppLog.d(TAG, "收到前台指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onForegroundCommand();
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ Feature unavailable");
                }

            } else if ("background".equals(command) || "/background".equals(command) ||
                       "background".equalsIgnoreCase(command)) {
                // 后台指令：将应用切换到后台
                AppLog.d(TAG, "收到后台指令");
                if (currentCommandCallback != null) {
                    String result = currentCommandCallback.onBackgroundCommand();
                    apiClient.sendMessage(chatId, result);
                } else {
                    apiClient.sendMessage(chatId, "❌ Feature unavailable");
                }

            } else if ("/help".equals(command) || "help".equals(command) ||
                       "/start".equals(command)) {

                apiClient.sendMessage(chatId,
                    "📋 <b>EVDashcam Remote Control</b>\n" +
                    "━━━━━━━━━━━━━━\n\n" +
                    "📹 <b>Remote Recording</b>\n" +
                    "/record ─ Record 60s video\n" +
                    "/record 30 ─ Record specified duration\n" +
                    "record / record 30 ─ Commands\n\n" +
                    "▶️ <b>Continuous Recording</b>\n" +
                    "/start_rec ─ Start continuous recording\n" +
                    "/stop_rec ─ Stop recording\n" +
                    "start / stop recording\n\n" +
                    "📷 <b>Take Photo</b>\n" +
                    "/photo ─ Take photo\n" +
                    "photo ─ Take photo command\n\n" +
                    "🔄 <b>Foreground / Background</b>\n" +
                    "/foreground ─ Switch to foreground\n" +
                    "/background ─ Switch to background\n" +
                    "foreground / background\n\n" +
                    "ℹ️ <b>Other</b>\n" +
                    "/status ─ View app status\n" +
                    "/exit ─ Exit app\n" +
                    "/help ─ Show this help\n\n" +
                    "━━━━━━━━━━━━━━\n" +
                    "💡 Commands support English");

            } else {
                AppLog.d(TAG, "未识别的指令: " + command);
                apiClient.sendMessage(chatId,
                    "Unrecognized command. Send /help to view available commands.");
            }

        } catch (Exception e) {
            AppLog.e(TAG, "处理消息失败", e);
        }
    }

    /**
     * 解析指令文本
     * 移除 @ 机器人名称部分
     */
    private String parseCommand(String text) {
        if (text == null) {
            return "";
        }

        // 移除 @botname 部分
        String command = text.replaceAll("@\\S+", "").trim();
        return command;
    }

    /**
     * 解析录制时长（秒）
     * 支持格式：/record、/record 30、录制、录制30、录制 30
     */
    private int parseRecordDuration(String command) {
        if (command == null || command.isEmpty()) {
            return 60;
        }

        // 移除指令关键字，提取数字
        String durationStr = command
                .replaceAll("(?i)(/record|录制|record)", "")
                .trim();

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
     * 发送响应消息，并在发送完成后执行回调
     */
    private void sendResponseAndThen(long chatId, String message, Runnable callback) {
        new Thread(() -> {
            try {
                apiClient.sendMessage(chatId, message);
                AppLog.d(TAG, "响应消息已发送: " + message);

                if (callback != null) {
                    callback.run();
                }
            } catch (Exception e) {
                AppLog.e(TAG, "发送响应消息失败", e);
                // 即使发送失败，也执行回调
                if (callback != null) {
                    callback.run();
                }
            }
        }).start();
    }

    /**
     * 停止消息轮询
     */
    public void stop() {
        AppLog.d(TAG, "正在停止 Bot...");
        shouldStop = true;
        isRunning = false;

        if (pollingThread != null) {
            pollingThread.interrupt();
            
            // 等待轮询线程完全结束，最多等待 35 秒（比 POLL_TIMEOUT 稍长）
            // 这样可以避免重启时新旧连接冲突导致 409 错误
            try {
                pollingThread.join(35000);
                if (pollingThread.isAlive()) {
                    AppLog.w(TAG, "轮询线程未能在超时内结束");
                } else {
                    AppLog.d(TAG, "轮询线程已完全停止");
                }
            } catch (InterruptedException e) {
                AppLog.w(TAG, "等待轮询线程停止时被中断");
                Thread.currentThread().interrupt();
            }
            pollingThread = null;
        }

        AppLog.d(TAG, "Bot 已停止");
    }

    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return isRunning;
    }
}
