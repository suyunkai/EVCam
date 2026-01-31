package com.kooo.evcam.feishu;

import com.kooo.evcam.AppLog;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞书图片上传服务
 * 负责将拍摄的照片上传到飞书
 */
public class FeishuPhotoUploadService {
    private static final String TAG = "FeishuPhotoUpload";

    private final Context context;
    private final FeishuApiClient apiClient;

    public interface UploadCallback {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String error);
    }

    public FeishuPhotoUploadService(Context context, FeishuApiClient apiClient) {
        this.context = context;
        this.apiClient = apiClient;
    }

    /**
     * 上传图片文件到飞书
     * @param photoFiles 图片文件列表
     * @param chatId 飞书会话 ID
     * @param callback 上传回调
     */
    public void uploadPhotos(List<File> photoFiles, String chatId, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (photoFiles == null || photoFiles.isEmpty()) {
                    callback.onError("没有图片文件可上传");
                    return;
                }

                callback.onProgress("开始上传 " + photoFiles.size() + " 张照片...");

                List<String> uploadedFiles = new ArrayList<>();
                List<String> failedFiles = new ArrayList<>();

                for (int i = 0; i < photoFiles.size(); i++) {
                    File photoFile = photoFiles.get(i);

                    if (!photoFile.exists()) {
                        AppLog.w(TAG, "图片文件不存在: " + photoFile.getPath());
                        failedFiles.add(photoFile.getName() + " (文件不存在)");
                        continue;
                    }

                    callback.onProgress("正在上传 (" + (i + 1) + "/" + photoFiles.size() + "): " + photoFile.getName());

                    // 重试上传（最多2次）
                    boolean uploadSuccess = false;
                    int retryCount = 0;
                    int maxRetries = 2;
                    String lastError = "";

                    while (!uploadSuccess && retryCount < maxRetries) {
                        try {
                            if (retryCount > 0) {
                                callback.onProgress("重试第 " + retryCount + " 次: " + photoFile.getName());
                                Thread.sleep(1500);
                            }

                            // 1. 上传图片获取 image_key
                            String imageKey = apiClient.uploadImage(photoFile);

                            // 2. 发送图片消息
                            apiClient.sendImageMessage("chat_id", chatId, imageKey);

                            uploadedFiles.add(photoFile.getName());
                            AppLog.d(TAG, "图片上传成功: " + photoFile.getName());
                            uploadSuccess = true;

                        } catch (Exception e) {
                            retryCount++;
                            lastError = e.getMessage();
                            AppLog.e(TAG, "上传图片失败 (尝试 " + retryCount + "/" + maxRetries + "): " + photoFile.getName(), e);

                            if (retryCount >= maxRetries) {
                                failedFiles.add(photoFile.getName() + " (" + (lastError != null ? lastError : "未知错误") + ")");
                                break;
                            }
                        }
                    }

                    // 延迟500ms后再上传下一张照片
                    if (i < photoFiles.size() - 1) {
                        Thread.sleep(500);
                    }
                }

                // 统一处理上传结果
                if (uploadedFiles.isEmpty()) {
                    String errorMsg = "❌ 所有图片上传失败\n失败列表:\n" + String.join("\n", failedFiles);
                    callback.onError(errorMsg);
                    apiClient.sendTextMessage("chat_id", chatId, errorMsg);
                } else if (failedFiles.isEmpty()) {
                    String successMessage = "✅ 图片上传完成！共上传 " + uploadedFiles.size() + " 张照片";
                    callback.onSuccess(successMessage);
                    Thread.sleep(2000);
                    apiClient.sendTextMessage("chat_id", chatId, successMessage);
                } else {
                    String mixedMessage = "⚠️ 上传完成（部分失败）\n" +
                            "成功: " + uploadedFiles.size() + " 张\n" +
                            "失败: " + failedFiles.size() + " 张\n\n" +
                            "失败列表:\n" + String.join("\n", failedFiles);
                    callback.onSuccess(mixedMessage);
                    Thread.sleep(2000);
                    apiClient.sendTextMessage("chat_id", chatId, mixedMessage);
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
    public void uploadPhoto(File photoFile, String chatId, UploadCallback callback) {
        List<File> files = new ArrayList<>();
        files.add(photoFile);
        uploadPhotos(files, chatId, callback);
    }
}
