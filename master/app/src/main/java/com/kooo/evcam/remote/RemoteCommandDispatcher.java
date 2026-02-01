package com.kooo.evcam.remote;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.dingtalk.DingTalkApiClient;
import com.kooo.evcam.feishu.FeishuApiClient;
import com.kooo.evcam.remote.core.ChatIdentifier;
import com.kooo.evcam.remote.core.RemotePlatform;
import com.kooo.evcam.remote.handler.DingTalkHandler;
import com.kooo.evcam.remote.handler.FeishuHandler;
import com.kooo.evcam.remote.handler.RemoteCommandHandler;
import com.kooo.evcam.remote.handler.TelegramHandler;
import com.kooo.evcam.telegram.TelegramApiClient;

import java.util.EnumMap;
import java.util.Map;

/**
 * 远程命令分发器
 * 作为 MainActivity 调用远程功能的统一入口
 * 负责将命令分发到对应平台的处理器
 */
public class RemoteCommandDispatcher {
    private static final String TAG = "RemoteCommandDispatcher";
    
    private final Context context;
    private final Map<RemotePlatform, RemoteCommandHandler> handlers;
    
    // 摄像头控制器和状态监听器（由 MainActivity 提供）
    private RemoteCommandHandler.CameraController cameraController;
    private RemoteCommandHandler.RecordingStateListener recordingStateListener;
    
    public RemoteCommandDispatcher(Context context) {
        this.context = context.getApplicationContext();
        this.handlers = new EnumMap<>(RemotePlatform.class);
        
        // 预创建各平台处理器（但不设置 API 客户端）
        handlers.put(RemotePlatform.DINGTALK, new DingTalkHandler(context));
        handlers.put(RemotePlatform.TELEGRAM, new TelegramHandler(context));
        handlers.put(RemotePlatform.FEISHU, new FeishuHandler(context));
        
        AppLog.d(TAG, "RemoteCommandDispatcher 初始化完成");
    }
    
    // ==================== 依赖注入 ====================
    
    /**
     * 设置摄像头控制器
     * 必须在使用前调用
     */
    public void setCameraController(RemoteCommandHandler.CameraController controller) {
        this.cameraController = controller;
        // 传递给所有处理器
        for (RemoteCommandHandler handler : handlers.values()) {
            handler.setCameraController(controller);
        }
        AppLog.d(TAG, "CameraController 已设置");
    }
    
    /**
     * 设置录制状态监听器
     */
    public void setRecordingStateListener(RemoteCommandHandler.RecordingStateListener listener) {
        this.recordingStateListener = listener;
        // 传递给所有处理器
        for (RemoteCommandHandler handler : handlers.values()) {
            handler.setRecordingStateListener(listener);
        }
        AppLog.d(TAG, "RecordingStateListener 已设置");
    }
    
    /**
     * 设置钉钉 API 客户端
     */
    public void setDingTalkApiClient(DingTalkApiClient apiClient) {
        DingTalkHandler handler = (DingTalkHandler) handlers.get(RemotePlatform.DINGTALK);
        if (handler != null) {
            handler.setApiClient(apiClient);
            AppLog.d(TAG, "DingTalk API 客户端已设置");
        }
    }
    
    /**
     * 设置 Telegram API 客户端
     */
    public void setTelegramApiClient(TelegramApiClient apiClient) {
        TelegramHandler handler = (TelegramHandler) handlers.get(RemotePlatform.TELEGRAM);
        if (handler != null) {
            handler.setApiClient(apiClient);
            AppLog.d(TAG, "Telegram API 客户端已设置");
        }
    }
    
    /**
     * 设置飞书 API 客户端
     */
    public void setFeishuApiClient(FeishuApiClient apiClient) {
        FeishuHandler handler = (FeishuHandler) handlers.get(RemotePlatform.FEISHU);
        if (handler != null) {
            handler.setApiClient(apiClient);
            AppLog.d(TAG, "Feishu API 客户端已设置");
        }
    }
    
    // ==================== 命令分发 ====================
    
    /**
     * 启动远程录制
     */
    public void startRemoteRecording(RemotePlatform platform, ChatIdentifier chatId, int durationSeconds) {
        RemoteCommandHandler handler = getHandler(platform);
        if (handler != null) {
            AppLog.d(TAG, "分发远程录制命令到 " + platform.getDisplayName());
            handler.startRemoteRecording(chatId, durationSeconds);
        } else {
            AppLog.e(TAG, "未找到 " + platform.getDisplayName() + " 处理器");
        }
    }
    
    /**
     * 启动远程拍照
     */
    public void startRemotePhoto(RemotePlatform platform, ChatIdentifier chatId) {
        RemoteCommandHandler handler = getHandler(platform);
        if (handler != null) {
            AppLog.d(TAG, "分发远程拍照命令到 " + platform.getDisplayName());
            handler.startRemotePhoto(chatId);
        } else {
            AppLog.e(TAG, "未找到 " + platform.getDisplayName() + " 处理器");
        }
    }
    
    /**
     * 发送消息
     */
    public void sendMessage(RemotePlatform platform, ChatIdentifier chatId, String message) {
        RemoteCommandHandler handler = getHandler(platform);
        if (handler != null) {
            handler.sendMessage(chatId, message);
        }
    }
    
    /**
     * 发送错误消息
     */
    public void sendError(RemotePlatform platform, ChatIdentifier chatId, String error) {
        RemoteCommandHandler handler = getHandler(platform);
        if (handler != null) {
            handler.sendError(chatId, error);
        }
    }
    
    // ==================== 便捷方法 - 钉钉 ====================
    
    /**
     * 钉钉远程录制（便捷方法）
     */
    public void startDingTalkRecording(String conversationId, String conversationType, 
            String userId, int durationSeconds) {
        ChatIdentifier chatId = ChatIdentifier.dingtalk(conversationId, conversationType, userId);
        startRemoteRecording(RemotePlatform.DINGTALK, chatId, durationSeconds);
    }
    
    /**
     * 钉钉远程拍照（便捷方法）
     */
    public void startDingTalkPhoto(String conversationId, String conversationType, String userId) {
        ChatIdentifier chatId = ChatIdentifier.dingtalk(conversationId, conversationType, userId);
        startRemotePhoto(RemotePlatform.DINGTALK, chatId);
    }
    
    // ==================== 便捷方法 - Telegram ====================
    
    /**
     * Telegram 远程录制（便捷方法）
     */
    public void startTelegramRecording(long chatId, int durationSeconds) {
        ChatIdentifier id = ChatIdentifier.telegram(chatId);
        startRemoteRecording(RemotePlatform.TELEGRAM, id, durationSeconds);
    }
    
    /**
     * Telegram 远程拍照（便捷方法）
     */
    public void startTelegramPhoto(long chatId) {
        ChatIdentifier id = ChatIdentifier.telegram(chatId);
        startRemotePhoto(RemotePlatform.TELEGRAM, id);
    }
    
    // ==================== 便捷方法 - 飞书 ====================
    
    /**
     * 飞书远程录制（便捷方法）
     */
    public void startFeishuRecording(String chatId, int durationSeconds) {
        ChatIdentifier id = ChatIdentifier.feishu(chatId);
        startRemoteRecording(RemotePlatform.FEISHU, id, durationSeconds);
    }
    
    /**
     * 飞书远程拍照（便捷方法）
     */
    public void startFeishuPhoto(String chatId) {
        ChatIdentifier id = ChatIdentifier.feishu(chatId);
        startRemotePhoto(RemotePlatform.FEISHU, id);
    }
    
    // ==================== 状态查询 ====================
    
    /**
     * 检查是否有任何平台正在进行远程录制
     */
    public boolean isAnyRemoteRecording() {
        for (RemoteCommandHandler handler : handlers.values()) {
            if (handler.isRemoteRecording()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 检查是否正在准备录制
     */
    public boolean isAnyPreparingRecording() {
        for (RemoteCommandHandler handler : handlers.values()) {
            if (handler.isPreparingRecording()) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 获取当前正在进行远程录制的平台
     */
    public RemotePlatform getActiveRecordingPlatform() {
        for (Map.Entry<RemotePlatform, RemoteCommandHandler> entry : handlers.entrySet()) {
            if (entry.getValue().isRemoteRecording()) {
                return entry.getKey();
            }
        }
        return null;
    }
    
    /**
     * 通知首次数据写入完成
     * 由 MainActivity 在检测到录制数据写入时调用
     */
    public void onFirstDataWritten() {
        for (RemoteCommandHandler handler : handlers.values()) {
            if (handler.isRemoteRecording()) {
                handler.onFirstDataWritten();
            }
        }
    }
    
    /**
     * 通知时间戳更新（Watchdog 重建录制后调用）
     * 由 MainActivity 在录制时间戳变化时调用
     */
    public void onTimestampUpdated(String newTimestamp) {
        for (RemoteCommandHandler handler : handlers.values()) {
            if (handler.isRemoteRecording()) {
                handler.onTimestampUpdated(newTimestamp);
            }
        }
    }
    
    // ==================== 辅助方法 ====================
    
    private RemoteCommandHandler getHandler(RemotePlatform platform) {
        return handlers.get(platform);
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        for (RemoteCommandHandler handler : handlers.values()) {
            handler.cleanup();
        }
        AppLog.d(TAG, "RemoteCommandDispatcher 资源已清理");
    }
}
