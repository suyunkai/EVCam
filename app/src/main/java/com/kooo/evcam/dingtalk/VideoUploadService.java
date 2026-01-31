package com.kooo.evcam.dingtalk;


import com.kooo.evcam.AppLog;
import android.content.Context;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 视频上传服务
 * 负责将录制的视频上传到钉钉
 */
public class VideoUploadService {
    private static final String TAG = "VideoUploadService";

    private final Context context;
    private final DingTalkApiClient apiClient;

    public interface UploadCallback {
        void onProgress(String message);
        void onSuccess(String message);
        void onError(String error);
    }

    public VideoUploadService(Context context, DingTalkApiClient apiClient) {
        this.context = context;
        this.apiClient = apiClient;
    }

    /**
     * 上传视频文件到钉钉
     * @param videoFiles 视频文件列表
     * @param conversationId 钉钉会话 ID
     * @param conversationType 会话类型（"1"=单聊，"2"=群聊）
     * @param userId 钉钉用户 ID（用于发送视频消息）
     * @param callback 上传回调
     */
    public void uploadVideos(List<File> videoFiles, String conversationId, String conversationType, String userId, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (videoFiles == null || videoFiles.isEmpty()) {
                    callback.onError("没有视频文件可上传");
                    return;
                }

                callback.onProgress("开始上传 " + videoFiles.size() + " 个视频文件...");

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
                            AppLog.w(TAG, "封面提取失败，跳过视频: " + videoFile.getName());
                            callback.onError("封面提取失败: " + videoFile.getName());
                            continue;
                        }

                        // 2. 获取视频时长
                        int duration = VideoThumbnailExtractor.getVideoDuration(videoFile);
                        if (duration == 0) {
                            duration = 60; // 默认 60 秒
                        }

                        // 3. 上传视频文件到钉钉
                        callback.onProgress("正在上传视频 (" + (i + 1) + "/" + videoFiles.size() + ")...");
                        String videoMediaId = apiClient.uploadFile(videoFile);

                        // 4. 上传封面图到钉钉
                        callback.onProgress("正在上传封面 (" + (i + 1) + "/" + videoFiles.size() + ")...");
                        String picMediaId = apiClient.uploadImage(thumbnailFile);

                        // 5. 发送视频消息
                        callback.onProgress("正在发送视频消息 (" + (i + 1) + "/" + videoFiles.size() + ")...");
                        apiClient.sendVideoMessage(conversationId, conversationType, videoMediaId, picMediaId, duration, userId);

                        uploadedFiles.add(videoFile.getName());
                        AppLog.d(TAG, "视频上传成功: " + videoFile.getName());

                        // 6. 清理临时封面文件
                        if (thumbnailFile.exists()) {
                            thumbnailFile.delete();
                        }

                        // 7. 延迟2秒后再上传下一个视频，减少网络和系统压力
                        if (i < videoFiles.size() - 1) {  // 不是最后一个视频
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
                    String successMessage = "视频上传完成！共上传 " + uploadedFiles.size() + " 个文件";
                    callback.onSuccess(successMessage);

                    // 等待5秒，确保视频消息被钉钉服务器处理完毕后再发送完成消息
                    // 视频处理比图片更慢，需要更长的等待时间
                    try {
                        Thread.sleep(5000);
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
     * 上传单个视频文件
     */
    public void uploadVideo(File videoFile, String conversationId, String conversationType, String userId, UploadCallback callback) {
        List<File> files = new ArrayList<>();
        files.add(videoFile);
        uploadVideos(files, conversationId, conversationType, userId, callback);
    }
}
