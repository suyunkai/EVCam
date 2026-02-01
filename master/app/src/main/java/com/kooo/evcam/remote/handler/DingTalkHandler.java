package com.kooo.evcam.remote.handler;

import android.content.Context;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.dingtalk.DingTalkApiClient;
import com.kooo.evcam.dingtalk.PhotoUploadService;
import com.kooo.evcam.dingtalk.VideoUploadService;
import com.kooo.evcam.remote.core.ChatIdentifier;
import com.kooo.evcam.remote.core.RemotePlatform;
import com.kooo.evcam.remote.core.RemoteUploadCallback;
import com.kooo.evcam.remote.upload.MediaUploadService;

import java.io.File;
import java.util.List;

/**
 * 钉钉远程命令处理器
 * 实现钉钉平台特定的功能
 */
public class DingTalkHandler extends RemoteCommandHandler {
    private static final String TAG = "DingTalkHandler";
    
    private DingTalkApiClient apiClient;
    
    public DingTalkHandler(Context context) {
        super(context);
    }
    
    public void setApiClient(DingTalkApiClient apiClient) {
        this.apiClient = apiClient;
    }
    
    @Override
    protected String getPlatformName() {
        return "钉钉";
    }
    
    @Override
    protected RemotePlatform getPlatform() {
        return RemotePlatform.DINGTALK;
    }
    
    @Override
    protected boolean isApiClientReady() {
        return apiClient != null;
    }
    
    @Override
    public void sendMessage(ChatIdentifier chatId, String message) {
        if (apiClient == null) {
            AppLog.e(TAG, "钉钉 API 客户端未初始化");
            return;
        }
        
        ChatIdentifier.DingTalkChatId dingTalkId = (ChatIdentifier.DingTalkChatId) chatId;
        new Thread(() -> {
            try {
                apiClient.sendTextMessage(
                    dingTalkId.getConversationId(),
                    dingTalkId.getConversationType(),
                    message
                );
            } catch (Exception e) {
                AppLog.e(TAG, "发送钉钉消息失败", e);
            }
        }).start();
    }
    
    @Override
    public void sendError(ChatIdentifier chatId, String error) {
        sendMessage(chatId, "❌ " + error);
    }
    
    @Override
    protected MediaUploadService createVideoUploadService() {
        return new DingTalkVideoUploadAdapter(context, apiClient);
    }
    
    @Override
    protected MediaUploadService createPhotoUploadService() {
        return new DingTalkPhotoUploadAdapter(context, apiClient);
    }
    
    // ==================== 上传服务适配器 ====================
    
    /**
     * 钉钉视频上传适配器
     */
    private static class DingTalkVideoUploadAdapter implements MediaUploadService {
        private final VideoUploadService uploadService;
        
        DingTalkVideoUploadAdapter(Context context, DingTalkApiClient apiClient) {
            this.uploadService = new VideoUploadService(context, apiClient);
        }
        
        @Override
        public void uploadVideos(List<File> videoFiles, ChatIdentifier chatId, RemoteUploadCallback callback) {
            ChatIdentifier.DingTalkChatId dingTalkId = (ChatIdentifier.DingTalkChatId) chatId;
            uploadService.uploadVideos(videoFiles, 
                    dingTalkId.getConversationId(), 
                    dingTalkId.getConversationType(),
                    dingTalkId.getUserId(),
                    new VideoUploadService.UploadCallback() {
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
     * 钉钉照片上传适配器
     */
    private static class DingTalkPhotoUploadAdapter implements MediaUploadService {
        private final PhotoUploadService uploadService;
        
        DingTalkPhotoUploadAdapter(Context context, DingTalkApiClient apiClient) {
            this.uploadService = new PhotoUploadService(context, apiClient);
        }
        
        @Override
        public void uploadVideos(List<File> videoFiles, ChatIdentifier chatId, RemoteUploadCallback callback) {
            // 照片上传服务不处理视频
        }
        
        @Override
        public void uploadPhotos(List<File> photoFiles, ChatIdentifier chatId, RemoteUploadCallback callback) {
            ChatIdentifier.DingTalkChatId dingTalkId = (ChatIdentifier.DingTalkChatId) chatId;
            uploadService.uploadPhotos(photoFiles,
                    dingTalkId.getConversationId(),
                    dingTalkId.getConversationType(),
                    dingTalkId.getUserId(),
                    new PhotoUploadService.UploadCallback() {
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
