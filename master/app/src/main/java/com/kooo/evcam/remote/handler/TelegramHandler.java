package com.kooo.evcam.remote.handler;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.remote.core.ChatIdentifier;
import com.kooo.evcam.remote.core.RemotePlatform;
import com.kooo.evcam.remote.core.RemoteUploadCallback;
import com.kooo.evcam.remote.upload.MediaUploadService;
import com.kooo.evcam.telegram.TelegramApiClient;
import com.kooo.evcam.telegram.TelegramPhotoUploadService;
import com.kooo.evcam.telegram.TelegramVideoUploadService;

import java.io.File;
import java.util.List;

/**
 * Telegram 远程命令处理器
 * 实现 Telegram 平台特定的功能
 */
public class TelegramHandler extends RemoteCommandHandler {
    private static final String TAG = "TelegramHandler";
    
    // Telegram 文件大小限制
    private static final long MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024; // 50MB
    
    private TelegramApiClient apiClient;
    
    public TelegramHandler(Context context) {
        super(context);
    }
    
    public void setApiClient(TelegramApiClient apiClient) {
        this.apiClient = apiClient;
    }
    
    @Override
    protected String getPlatformName() {
        return "Telegram";
    }
    
    @Override
    protected RemotePlatform getPlatform() {
        return RemotePlatform.TELEGRAM;
    }
    
    @Override
    protected boolean isApiClientReady() {
        return apiClient != null;
    }
    
    @Override
    public void sendMessage(ChatIdentifier chatId, String message) {
        if (apiClient == null) {
            AppLog.e(TAG, "Telegram API 客户端未初始化");
            return;
        }
        
        long telegramChatId = ((ChatIdentifier.TelegramChatId) chatId).getChatId();
        new Thread(() -> {
            try {
                apiClient.sendMessage(telegramChatId, message);
            } catch (Exception e) {
                AppLog.e(TAG, "发送 Telegram 消息失败", e);
            }
        }).start();
    }
    
    @Override
    public void sendError(ChatIdentifier chatId, String error) {
        sendMessage(chatId, "❌ " + error);
    }
    
    @Override
    protected MediaUploadService createVideoUploadService() {
        return new TelegramVideoUploadAdapter(context, apiClient);
    }
    
    @Override
    protected MediaUploadService createPhotoUploadService() {
        return new TelegramPhotoUploadAdapter(context, apiClient);
    }
    
    /**
     * 处理上传错误 - Telegram 特有的文件大小限制提示
     */
    @Override
    protected void handleUploadError(ChatIdentifier chatId, String error) {
        if (error.contains("413") || 
            error.toLowerCase().contains("too large") || 
            error.toLowerCase().contains("file is too big")) {
            sendMessage(chatId, "提示：Telegram Bot API 限制上传文件不能超过50MB，该文件大小已超出。");
        }
    }
    
    // ==================== 上传服务适配器 ====================
    
    /**
     * Telegram 视频上传适配器
     */
    private static class TelegramVideoUploadAdapter implements MediaUploadService {
        private final TelegramVideoUploadService uploadService;
        
        TelegramVideoUploadAdapter(Context context, TelegramApiClient apiClient) {
            this.uploadService = new TelegramVideoUploadService(context, apiClient);
        }
        
        @Override
        public void uploadVideos(List<File> videoFiles, ChatIdentifier chatId, RemoteUploadCallback callback) {
            long telegramChatId = ((ChatIdentifier.TelegramChatId) chatId).getChatId();
            uploadService.uploadVideos(videoFiles, telegramChatId,
                    new TelegramVideoUploadService.UploadCallback() {
                        @Override
                        public void onProgress(String message) {
                            callback.onProgress(message);
                        }
                        
                        @Override
                        public void onSuccess(String message) {
                            callback.onSuccess(message);
                        }
                        
                        @Override
                        public void onError(String error) {
                            callback.onError(error);
                        }
                    });
        }
        
        @Override
        public void uploadPhotos(List<File> photoFiles, ChatIdentifier chatId, RemoteUploadCallback callback) {
            // 视频上传服务不处理照片
        }
    }
    
    /**
     * Telegram 照片上传适配器
     */
    private static class TelegramPhotoUploadAdapter implements MediaUploadService {
        private final TelegramPhotoUploadService uploadService;
        
        TelegramPhotoUploadAdapter(Context context, TelegramApiClient apiClient) {
            this.uploadService = new TelegramPhotoUploadService(context, apiClient);
        }
        
        @Override
        public void uploadVideos(List<File> videoFiles, ChatIdentifier chatId, RemoteUploadCallback callback) {
            // 照片上传服务不处理视频
        }
        
        @Override
        public void uploadPhotos(List<File> photoFiles, ChatIdentifier chatId, RemoteUploadCallback callback) {
            long telegramChatId = ((ChatIdentifier.TelegramChatId) chatId).getChatId();
            uploadService.uploadPhotos(photoFiles, telegramChatId,
                    new TelegramPhotoUploadService.UploadCallback() {
                        @Override
                        public void onProgress(String message) {
                            callback.onProgress(message);
                        }
                        
                        @Override
                        public void onSuccess(String message) {
                            callback.onSuccess(message);
                        }
                        
                        @Override
                        public void onError(String error) {
                            callback.onError(error);
                        }
                    });
        }
    }
}
