package com.kooo.evcam.dingtalk;


import com.kooo.evcam.AppLog;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 图片上传服务
 * 负责将拍摄的照片上传到钉钉
 */
public class PhotoUploadService {
    private static final String TAG = "PhotoUploadService";

    private final Context context;
    private final DingTalkApiClient apiClient;

    public interface UploadCallback {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String error);
    }

    public PhotoUploadService(Context context, DingTalkApiClient apiClient) {
        this.context = context;
        this.apiClient = apiClient;
    }

    /**
     * 上传图片文件到钉钉
     * @param photoFiles 图片文件列表
     * @param conversationId 钉钉会话 ID
     * @param conversationType 会话类型（"1"=单聊，"2"=群聊）
     * @param userId 钉钉用户 ID（用于发送图片消息）
     * @param callback 上传回调
     */
    public void uploadPhotos(List<File> photoFiles, String conversationId, String conversationType, String userId, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (photoFiles == null || photoFiles.isEmpty()) {
                    callback.onError("没有图片文件可上传");
                    return;
                }

                callback.onProgress("开始上传 " + photoFiles.size() + " 张照片...");

                List<String> uploadedFiles = new ArrayList<>();

                for (int i = 0; i < photoFiles.size(); i++) {
                    File photoFile = photoFiles.get(i);

                    if (!photoFile.exists()) {
                        AppLog.w(TAG, "图片文件不存在: " + photoFile.getPath());
                        continue;
                    }

                    callback.onProgress("正在上传 (" + (i + 1) + "/" + photoFiles.size() + "): " + photoFile.getName());

                    try {
                        // 1. 上传图片到钉钉（使用 image 类型）
                        callback.onProgress("正在上传图片 (" + (i + 1) + "/" + photoFiles.size() + ")...");
                        String mediaId = apiClient.uploadImage(photoFile);
                        AppLog.d(TAG, "图片上传成功，mediaId: " + mediaId);

                        // 2. 尝试使用 mediaId 发送图片消息
                        callback.onProgress("正在发送图片消息 (" + (i + 1) + "/" + photoFiles.size() + ")...");
                        try {
                            // 尝试直接使用 mediaId 作为 photoURL (可能钉钉会自动处理)
                            apiClient.sendImageMessage(conversationId, conversationType, mediaId, userId);
                            AppLog.d(TAG, "图片消息发送成功: " + photoFile.getName());
                        } catch (Exception imageError) {
                            // 如果图片消息失败,降级为文件消息
                            AppLog.w(TAG, "图片消息发送失败,降级为文件消息: " + imageError.getMessage());
                            apiClient.sendFileMessage(conversationId, conversationType, mediaId, photoFile.getName(), userId);
                            AppLog.d(TAG, "文件消息发送成功: " + photoFile.getName());
                        }

                        uploadedFiles.add(photoFile.getName());

                        // 3. 延迟2秒后再上传下一张照片，减少网络和系统压力
                        if (i < photoFiles.size() - 1) {  // 不是最后一张照片
                            callback.onProgress("等待2秒后上传下一张照片...");
                            Thread.sleep(2000);
                        }

                    } catch (Exception e) {
                        AppLog.e(TAG, "上传图片失败: " + photoFile.getName(), e);
                        callback.onError("上传失败: " + photoFile.getName() + " - " + e.getMessage());
                    }
                }

                if (uploadedFiles.isEmpty()) {
                    callback.onError("所有图片上传失败");
                } else {
                    String successMessage = "图片上传完成！共上传 " + uploadedFiles.size() + " 张照片";
                    callback.onSuccess(successMessage);

                    // 等待3秒，确保图片消息被钉钉服务器处理完毕后再发送完成消息
                    // 避免"上传完成"消息比图片先到达用户端
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {}

                    // 发送完成消息，传递 conversationType 和 userId
                    apiClient.sendTextMessage(conversationId, conversationType, successMessage, userId);
                }

            } catch (Exception e) {
                AppLog.e(TAG, "上传过程出错", e);
                callback.onError("上传过程出错: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 上传单张图片
     */
    public void uploadPhoto(File photoFile, String conversationId, String conversationType, String userId, UploadCallback callback) {
        List<File> files = new ArrayList<>();
        files.add(photoFile);
        uploadPhotos(files, conversationId, conversationType, userId, callback);
    }
}
