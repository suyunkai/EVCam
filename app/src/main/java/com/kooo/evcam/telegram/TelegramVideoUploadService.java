package com.kooo.evcam.telegram;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.dingtalk.VideoThumbnailExtractor;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Telegram 视频上传服务
 * 负责将录制的视频上传到 Telegram
 */
public class TelegramVideoUploadService {
    private static final String TAG = "TelegramVideoUpload";

    private final Context context;
    private final TelegramApiClient apiClient;

    public interface UploadCallback {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String error);
    }

    public TelegramVideoUploadService(Context context, TelegramApiClient apiClient) {
        this.context = context;
        this.apiClient = apiClient;
    }

    /**
     * 上传视频文件到 Telegram
     * @param videoFiles 视频文件列表
     * @param chatId Telegram Chat ID
     * @param callback 上传回调
     */
    public void uploadVideos(List<File> videoFiles, long chatId, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (videoFiles == null || videoFiles.isEmpty()) {
                    callback.onError("没有视频文件可上传");
                    return;
                }

                callback.onProgress("开始上传 " + videoFiles.size() + " 个视频文件...");

                // 发送 "正在上传视频" 状态
                apiClient.sendChatAction(chatId, "upload_video");

                List<String> uploadedFiles = new ArrayList<>();

                for (int i = 0; i < videoFiles.size(); i++) {
                    File videoFile = videoFiles.get(i);

                    if (!videoFile.exists()) {
                        AppLog.w(TAG, "视频文件不存在: " + videoFile.getPath());
                        continue;
                    }

                    callback.onProgress("正在处理 (" + (i + 1) + "/" + videoFiles.size() + "): " + videoFile.getName());

                    try {
                        // 1. 提取视频封面
                        File thumbnailFile = new File(videoFile.getParent(),
                                videoFile.getName().replace(".mp4", "_thumb.jpg"));

                        boolean thumbnailExtracted = VideoThumbnailExtractor.extractThumbnail(videoFile, thumbnailFile);
                        if (!thumbnailExtracted) {
                            AppLog.w(TAG, "封面提取失败，将不使用缩略图");
                            thumbnailFile = null;
                        }

                        // 2. 获取视频时长
                        int duration = VideoThumbnailExtractor.getVideoDuration(videoFile);
                        if (duration == 0) {
                            duration = 60; // 默认 60 秒
                        }

                        // 3. 发送 "正在上传视频" 状态
                        apiClient.sendChatAction(chatId, "upload_video");

                        // 4. 直接上传并发送视频（Telegram API 合并了这两步）
                        callback.onProgress("正在上传视频 (" + (i + 1) + "/" + videoFiles.size() + ")...");

                        String caption = "视频 " + (i + 1) + "/" + videoFiles.size();
                        apiClient.sendVideo(chatId, videoFile, thumbnailFile, duration, caption);

                        uploadedFiles.add(videoFile.getName());
                        AppLog.d(TAG, "视频上传成功: " + videoFile.getName());

                        // 5. 清理临时封面文件
                        if (thumbnailFile != null && thumbnailFile.exists()) {
                            thumbnailFile.delete();
                        }

                        // 6. 延迟2秒后再上传下一个视频
                        if (i < videoFiles.size() - 1) {
                            callback.onProgress("等待2秒后上传下一个视频...");
                            Thread.sleep(2000);
                        }

                    } catch (Exception e) {
                        AppLog.e(TAG, "上传视频失败: " + videoFile.getName(), e);
                        callback.onError("上传失败: " + videoFile.getName() + " - " + e.getMessage());
                    }
                }

                if (uploadedFiles.isEmpty()) {
                    callback.onError("所有视频上传失败");
                } else {
                    String successMessage = "✅ 视频上传完成！共上传 " + uploadedFiles.size() + " 个文件";
                    callback.onSuccess(successMessage);

                    // 等待3秒，确保视频消息投递完成后再发送完成消息
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {}

                    // 发送完成消息
                    apiClient.sendMessage(chatId, successMessage);
                }

            } catch (Exception e) {
                AppLog.e(TAG, "上传过程出错", e);
                callback.onError("上传过程出错: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 上传单个视频文件
     */
    public void uploadVideo(File videoFile, long chatId, UploadCallback callback) {
        List<File> files = new ArrayList<>();
        files.add(videoFile);
        uploadVideos(files, chatId, callback);
    }
}
