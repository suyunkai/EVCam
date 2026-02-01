package com.kooo.evcam.feishu;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.dingtalk.VideoThumbnailExtractor;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 飞书视频上传服务
 * 负责将录制的视频上传到飞书
 */
public class FeishuVideoUploadService {
    private static final String TAG = "FeishuVideoUpload";

    private final Context context;
    private final FeishuApiClient apiClient;

    public interface UploadCallback {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String error);
    }

    public FeishuVideoUploadService(Context context, FeishuApiClient apiClient) {
        this.context = context;
        this.apiClient = apiClient;
    }

    /**
     * 上传视频文件到飞书
     * @param videoFiles 视频文件列表
     * @param chatId 飞书会话 ID
     * @param callback 上传回调
     */
    public void uploadVideos(List<File> videoFiles, String chatId, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (videoFiles == null || videoFiles.isEmpty()) {
                    callback.onError("没有视频文件可上传");
                    return;
                }

                callback.onProgress("开始上传 " + videoFiles.size() + " 个视频文件...");

                List<String> uploadedFiles = new ArrayList<>();
                List<String> failedFiles = new ArrayList<>();

                for (int i = 0; i < videoFiles.size(); i++) {
                    File videoFile = videoFiles.get(i);

                    if (!videoFile.exists()) {
                        AppLog.w(TAG, "视频文件不存在: " + videoFile.getPath());
                        failedFiles.add(videoFile.getName() + " (文件不存在)");
                        continue;
                    }

                    callback.onProgress("正在处理 (" + (i + 1) + "/" + videoFiles.size() + "): " + videoFile.getName());

                    File thumbnailFile = null;
                    try {
                        // 1. 提取视频封面缩略图和获取时长
                        callback.onProgress("正在提取视频信息 (" + (i + 1) + "/" + videoFiles.size() + ")...");
                        thumbnailFile = new File(videoFile.getParent(),
                                videoFile.getName().replace(".mp4", "_thumb.jpg"));
                        boolean thumbnailExtracted = VideoThumbnailExtractor.extractThumbnail(videoFile, thumbnailFile);
                        if (!thumbnailExtracted) {
                            AppLog.w(TAG, "无法提取视频缩略图，将不显示封面");
                            thumbnailFile = null;
                        }

                        // 获取视频时长（秒），转换为毫秒
                        int durationSec = VideoThumbnailExtractor.getVideoDuration(videoFile);
                        int durationMs = durationSec * 1000;
                        AppLog.d(TAG, "视频时长: " + durationSec + " 秒 (" + durationMs + " 毫秒)");

                        // 2. 上传视频文件获取 file_key（带时长参数）
                        callback.onProgress("正在上传视频 (" + (i + 1) + "/" + videoFiles.size() + ")...");
                        String fileKey = apiClient.uploadFile(videoFile, "mp4", durationMs);

                        // 3. 上传封面图片获取 image_key（如果有）
                        String imageKey = null;
                        if (thumbnailFile != null && thumbnailFile.exists()) {
                            callback.onProgress("正在上传视频封面...");
                            try {
                                imageKey = apiClient.uploadImage(thumbnailFile);
                                AppLog.d(TAG, "封面上传成功: " + imageKey);
                            } catch (Exception e) {
                                AppLog.w(TAG, "封面上传失败，视频将没有封面", e);
                            }
                        }

                        // 4. 发送视频消息（带封面）
                        apiClient.sendVideoMessage("chat_id", chatId, fileKey, imageKey);

                        uploadedFiles.add(videoFile.getName());
                        AppLog.d(TAG, "视频上传成功: " + videoFile.getName());

                        // 5. 延迟2秒后再上传下一个视频
                        if (i < videoFiles.size() - 1) {
                            callback.onProgress("等待2秒后上传下一个视频...");
                            Thread.sleep(2000);
                        }

                    } catch (Exception e) {
                        AppLog.e(TAG, "上传视频失败: " + videoFile.getName(), e);
                        failedFiles.add(videoFile.getName() + " (" + e.getMessage() + ")");
                    } finally {
                        // 清理临时缩略图文件
                        if (thumbnailFile != null && thumbnailFile.exists()) {
                            thumbnailFile.delete();
                        }
                    }
                }

                // 统一处理上传结果
                if (uploadedFiles.isEmpty()) {
                    String errorMsg = "❌ 所有视频上传失败\n失败列表:\n" + String.join("\n", failedFiles);
                    callback.onError(errorMsg);
                    apiClient.sendTextMessage("chat_id", chatId, errorMsg);
                } else if (failedFiles.isEmpty()) {
                    String successMessage = "✅ 视频上传完成！共上传 " + uploadedFiles.size() + " 个文件";
                    callback.onSuccess(successMessage);
                    Thread.sleep(3000);
                    apiClient.sendTextMessage("chat_id", chatId, successMessage);
                } else {
                    String mixedMessage = "⚠️ 上传完成（部分失败）\n" +
                            "成功: " + uploadedFiles.size() + " 个\n" +
                            "失败: " + failedFiles.size() + " 个\n\n" +
                            "失败列表:\n" + String.join("\n", failedFiles);
                    callback.onSuccess(mixedMessage);
                    Thread.sleep(3000);
                    apiClient.sendTextMessage("chat_id", chatId, mixedMessage);
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
    public void uploadVideo(File videoFile, String chatId, UploadCallback callback) {
        List<File> files = new ArrayList<>();
        files.add(videoFile);
        uploadVideos(files, chatId, callback);
    }
}
