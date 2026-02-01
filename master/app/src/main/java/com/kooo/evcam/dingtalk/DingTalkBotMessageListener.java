package com.kooo.evcam.dingtalk;


import com.kooo.evcam.AppLog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;
import com.dingtalk.open.app.api.callback.OpenDingTalkCallbackListener;
import com.dingtalk.open.app.api.models.bot.ChatbotMessage;
import com.dingtalk.open.app.api.models.bot.MessageContent;

/**
 * 钉钉机器人消息回调监听器（使用官方 SDK）
 */
public class DingTalkBotMessageListener implements OpenDingTalkCallbackListener<ChatbotMessage, JSONObject> {
    private static final String TAG = "DingTalkBotListener";

    private final Context context;
    private final DingTalkApiClient apiClient;
    private final CommandCallback callback;
    private final Handler mainHandler;

    public interface CommandCallback {
        void onRecordCommand(String conversationId, String userId, int durationSeconds);
        void onConnectionStatusChanged(boolean connected);
    }

    public DingTalkBotMessageListener(Context context, DingTalkApiClient apiClient, CommandCallback callback) {
        this.context = context;
        this.apiClient = apiClient;
        this.callback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public JSONObject execute(ChatbotMessage message) {
        try {
            MessageContent text = message.getText();
            if (text != null) {
                String msg = text.getContent();
                String senderId = message.getSenderId();
                String conversationId = message.getConversationId();

                AppLog.d(TAG, "收到机器人消息 - senderId: " + senderId);
                AppLog.d(TAG, "收到机器人消息 - conversationId: " + conversationId);
                AppLog.d(TAG, "收到机器人消息 - text: " + msg);

                // 解析指令
                String command = parseCommand(msg);

                // 解析录制时长（秒）
                int durationSeconds = parseRecordDuration(command);

                if (command.startsWith("录制") || command.toLowerCase().startsWith("record")) {
                    AppLog.d(TAG, "收到录制指令，时长: " + durationSeconds + " 秒");

                    // 发送确认消息，传递 senderId
                    String confirmMsg = String.format("收到录制指令，开始录制 %d 秒视频...", durationSeconds);
                    sendResponse(conversationId, senderId, confirmMsg);

                    // 通知监听器执行录制，传递 senderId 和时长
                    mainHandler.post(() -> callback.onRecordCommand(conversationId, senderId, durationSeconds));
                } else {
                    AppLog.d(TAG, "未识别的指令: " + command);
                    sendResponse(conversationId, senderId, "未识别的指令。请发送「录制」或「录制+数字」开始录制视频（如：录制30 表示录制30秒，默认60秒）。");
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "处理机器人消息失败", e);
        }

        return new JSONObject();
    }

    /**
     * 解析指令文本
     * 移除 @机器人 的部分，提取实际指令
     */
    private String parseCommand(String text) {
        if (text == null) {
            return "";
        }

        // 移除 @xxx 部分
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
     * 发送响应消息到钉钉
     */
    public void sendResponse(String conversationId, String userId, String message) {
        new Thread(() -> {
            try {
                apiClient.sendTextMessage(conversationId, message, userId);
                AppLog.d(TAG, "响应消息已发送: " + message);
            } catch (Exception e) {
                AppLog.e(TAG, "发送响应消息失败", e);
            }
        }).start();
    }
}
