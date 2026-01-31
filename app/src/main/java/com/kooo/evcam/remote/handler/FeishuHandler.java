package com.kooo.evcam.remote.handler;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.feishu.FeishuApiClient;
import com.kooo.evcam.feishu.FeishuPhotoUploadService;
import com.kooo.evcam.feishu.FeishuVideoUploadService;
import com.kooo.evcam.remote.core.ChatIdentifier;
import com.kooo.evcam.remote.core.RemotePlatform;
import com.kooo.evcam.remote.core.RemoteUploadCallback;
import com.kooo.evcam.remote.upload.MediaUploadService;

import java.io.File;
import java.util.List;

/**
 * 飞书远程命令处理器
 * 实现飞书平台特定的功能
 */
public class FeishuHandler extends RemoteCommandHandler {
    private static final String TAG = "FeishuHandler";
    
    // 飞书文件大小限制
    private static final long MAX_FILE_SIZE_BYTES = 30 * 1024 * 1024; // 30MB
    
    private FeishuApiClient apiClient;
    
    public FeishuHandler(Context context) {
        super(context);
    }
    
    public void setApiClient(FeishuApiClient apiClient) {
        this.apiClient = apiClient;
    }
    
    @Override
    protected String getPlatformName() {
        return "飞书";
    }
    
    @Override
    protected RemotePlatform getPlatform() {
        return RemotePlatform.FEISHU;
    }
    
    @Override
    protected boolean isApiClientReady() {
        return apiClient != null;
    }
    
    @Override
    public void sendMessage(ChatIdentifier chatId, String message) {
        if (apiClient == null) {
            AppLog.e(TAG, "飞书 API 客户端未初始化");
            return;
        }
        
        String feishuChatId = ((ChatIdentifier.FeishuChatId) chatId).getChatId();
        new Thread(() -> {
            try {
                apiClient.sendTextMessage("chat_id", feishuChatId, message);
            } catch (Exception e) {
                AppLog.e(TAG, "发送飞书消息失败", e);
            }
        }).start();
    }
    
    @Override
    public void sendError(ChatIdentifier chatId, String error) {
        sendMessage(chatId, "❌ " + error);
    }
    
    @Override
    protected MediaUploadService createVideoUploadService() {
        return new FeishuVideoUploadAdapter(context, apiClient);
    }
    
    @Override
    protected MediaUploadService createPhotoUploadService() {
        return new FeishuPhotoUploadAdapter(context, apiClient);
    }
    
    /**
     * 处理上传错误 - 飞书特有的文件大小限制提示
     */
    @Override
    protected void handleUploadError(ChatIdentifier chatId, String error) {
        if (error.contains("413") || 
            error.contains("99991663") || 
            error.contains("file size")) {
            sendMessage(chatId, "提示：飞书限制上传文件不能超过30MB，该文件大小已超出。");
        }
    }
    
    // ==================== 上传服务适配器 ====================
    
    /**
     * 飞书视频上传适配器
     */
    private static class FeishuVideoUploadAdapter implements MediaUploadService {
        private final FeishuVideoUploadService uploadService;
        
        FeishuVideoUploadAdapter(Context context, FeishuApiClient apiClient) {
            this.uploadService = new FeishuVideoUploadService(context, apiClient);
        }
        
        @Override
        public void uploadVideos(List<File> videoFiles, ChatIdentifier chatId, RemoteUploadCallback callback) {
            String feishuChatId = ((ChatIdentifier.FeishuChatId) chatId).getChatId();
            uploadService.uploadVideos(videoFiles, feishuChatId,
                    new FeishuVideoUploadService.UploadCallback() {
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
     * 飞书照片上传适配器
     */
    private static class FeishuPhotoUploadAdapter implements MediaUploadService {
        private final FeishuPhotoUploadService uploadService;
        
        FeishuPhotoUploadAdapter(Context context, FeishuApiClient apiClient) {
            this.uploadService = new FeishuPhotoUploadService(context, apiClient);
        }
        
        @Override
        public void uploadVideos(List<File> videoFiles, ChatIdentifier chatId, RemoteUploadCallback callback) {
            // 照片上传服务不处理视频
        }
        
        @Override
        public void uploadPhotos(List<File> photoFiles, ChatIdentifier chatId, RemoteUploadCallback callback) {
            String feishuChatId = ((ChatIdentifier.FeishuChatId) chatId).getChatId();
            uploadService.uploadPhotos(photoFiles, feishuChatId,
                    new FeishuPhotoUploadService.UploadCallback() {
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
