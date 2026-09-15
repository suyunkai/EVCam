package com.kooo.evcam.telegram;

import com.kooo.evcam.AppLog;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Telegram 图片上传服务
 * 负责将拍摄的照片上传到 Telegram
 */
public class TelegramPhotoUploadService {
    private static final String TAG = "TelegramPhotoUpload";

    private final Context context;
    private final TelegramApiClient apiClient;

    public interface UploadCallback {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String error);
    }

    public TelegramPhotoUploadService(Context context, TelegramApiClient apiClient) {
        this.context = context;
        this.apiClient = apiClient;
    }

    /**
     * 上传图片文件到 Telegram
     * @param photoFiles 图片文件列表
     * @param chatId Telegram Chat ID
     * @param callback 上传回调
     */
    public void uploadPhotos(List<File> photoFiles, long chatId, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (photoFiles == null || photoFiles.isEmpty()) {
                    callback.onError("No photo files to upload");
                    return;
                }

                callback.onProgress("Starting upload: " + photoFiles.size() + " photos...");

                // 发送 "Uploading photo" 状态
                apiClient.sendChatAction(chatId, "upload_photo");

                List<String> uploadedFiles = new ArrayList<>();
                List<String> failedFiles = new ArrayList<>();

                for (int i = 0; i < photoFiles.size(); i++) {
                    File photoFile = photoFiles.get(i);

                    if (!photoFile.exists()) {
                        AppLog.w(TAG, "图片文件不存在: " + photoFile.getPath());
                        failedFiles.add(photoFile.getName() + " (File does not exist)");
                        continue;
                    }

                    callback.onProgress("Uploading (" + (i + 1) + "/" + photoFiles.size() + "): " + photoFile.getName());

                    // 重试上传（最多2次，减少等待时间）
                    boolean uploadSuccess = false;
                    int retryCount = 0;
                    int maxRetries = 2;
                    String lastError = "";

                    while (!uploadSuccess && retryCount < maxRetries) {
                        try {
                            if (retryCount > 0) {
                                callback.onProgress("Retry #" + retryCount + ": " + photoFile.getName());
                                Thread.sleep(1500); // 重试前等待1.5秒
                            }

                            // 发送 "Uploading photo" 状态
                            apiClient.sendChatAction(chatId, "upload_photo");

                            // 直接上传并发送图片
                            String caption = "Photo " + (i + 1) + "/" + photoFiles.size();
                            apiClient.sendPhoto(chatId, photoFile, caption);

                            uploadedFiles.add(photoFile.getName());
                            AppLog.d(TAG, "图片上传成功: " + photoFile.getName());
                            uploadSuccess = true;

                        } catch (Exception e) {
                            retryCount++;
                            lastError = e.getMessage();
                            AppLog.e(TAG, "上传图片失败 (尝试 " + retryCount + "/" + maxRetries + "): " + photoFile.getName(), e);

                            if (retryCount >= maxRetries) {
                                // 达到最大重试次数，记录到失败列表
                                failedFiles.add(photoFile.getName() + " (" + (lastError != null ? lastError : "Unknown error") + ")");
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
                    // 所有文件都失败
                    String errorMsg = "❌ All photos failed to upload\nFailure list:\n" + String.join("\n", failedFiles);
                    callback.onError(errorMsg);
                    apiClient.sendMessage(chatId, errorMsg);
                } else if (failedFiles.isEmpty()) {
                    // 全部成功
                    String successMessage = "✅ Photo upload complete! Total uploaded: " + uploadedFiles.size() + " photos";
                    callback.onSuccess(successMessage);
                    // 等待2秒，确保图片消息投递完成后再发送完成消息
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {}
                    apiClient.sendMessage(chatId, successMessage);
                } else {
                    // 部分成功，部分失败
                    String mixedMessage = "⚠️ Upload finished (partial failure)\n" +
                            "Success: " + uploadedFiles.size() + " photos\n" +
                            "Failed: " + failedFiles.size() + " photos\n\n" +
                            "Failure list:\n" + String.join("\n", failedFiles);
                    callback.onSuccess(mixedMessage); // 仍然视为成功（至少有部分上传）
                    // 等待2秒，确保图片消息投递完成后再发送完成消息
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ignored) {}
                    apiClient.sendMessage(chatId, mixedMessage);
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
    public void uploadPhoto(File photoFile, long chatId, UploadCallback callback) {
        List<File> files = new ArrayList<>();
        files.add(photoFile);
        uploadPhotos(files, chatId, callback);
    }
}
