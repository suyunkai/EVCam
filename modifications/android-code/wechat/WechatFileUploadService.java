package com.kooo.evcam.wechat;

import android.content.Context;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信小程序文件上传服务
 * 负责将录制的视频和照片上传到云服务器
 */
public class WechatFileUploadService {
    private static final String TAG = "WechatFileUploadService";

    private final Context context;
    private final WechatMiniApiClient apiClient;
    private final WechatMiniStreamManager streamManager;

    public interface UploadCallback {
        void onProgress(String message);
        void onSuccess(String message, String fileUrl);
        void onError(String error);
    }

    public WechatFileUploadService(Context context, WechatMiniApiClient apiClient, 
                                    WechatMiniStreamManager streamManager) {
        this.context = context;
        this.apiClient = apiClient;
        this.streamManager = streamManager;
    }

    /**
     * 上传视频文件
     * @param videoFiles 视频文件列表
     * @param commandId 关联的指令ID
     * @param callback 上传回调
     */
    public void uploadVideos(List<File> videoFiles, String commandId, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (videoFiles == null || videoFiles.isEmpty()) {
                    callback.onError("没有视频文件可上传");
                    return;
                }

                callback.onProgress("开始上传 " + videoFiles.size() + " 个视频文件...");

                List<String> uploadedUrls = new ArrayList<>();

                for (int i = 0; i < videoFiles.size(); i++) {
                    File videoFile = videoFiles.get(i);

                    if (!videoFile.exists()) {
                        AppLog.w(TAG, "视频文件不存在: " + videoFile.getPath());
                        continue;
                    }

                    callback.onProgress("正在上传 (" + (i + 1) + "/" + videoFiles.size() + "): " + videoFile.getName());

                    try {
                        // 上传视频文件
                        String fileUrl = apiClient.uploadVideo(videoFile, commandId);
                        uploadedUrls.add(fileUrl);

                        AppLog.d(TAG, "视频上传成功: " + videoFile.getName() + " -> " + fileUrl);

                        // 通过 WebSocket 通知小程序
                        if (streamManager != null && streamManager.isRunning()) {
                            streamManager.sendFileUploadNotify(commandId, fileUrl, "video");
                        }

                        // 延迟2秒后再上传下一个
                        if (i < videoFiles.size() - 1) {
                            callback.onProgress("等待2秒后上传下一个视频...");
                            Thread.sleep(2000);
                        }

                    } catch (Exception e) {
                        AppLog.e(TAG, "上传视频失败: " + videoFile.getName(), e);
                        callback.onError("上传失败: " + videoFile.getName() + " - " + e.getMessage());
                    }
                }

                if (uploadedUrls.isEmpty()) {
                    callback.onError("所有视频上传失败");
                } else {
                    String successMessage = "视频上传完成！共上传 " + uploadedUrls.size() + " 个文件";
                    callback.onSuccess(successMessage, uploadedUrls.get(0));

                    // 发送指令结果
                    if (streamManager != null && streamManager.isRunning()) {
                        streamManager.sendCommandResult(commandId, true, successMessage);
                    }
                }

            } catch (Exception e) {
                AppLog.e(TAG, "上传过程出错", e);
                callback.onError("上传过程出错: " + e.getMessage());

                // 发送失败结果
                if (streamManager != null && streamManager.isRunning()) {
                    streamManager.sendCommandResult(commandId, false, "上传失败: " + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 上传单个视频文件
     */
    public void uploadVideo(File videoFile, String commandId, UploadCallback callback) {
        List<File> files = new ArrayList<>();
        files.add(videoFile);
        uploadVideos(files, commandId, callback);
    }

    /**
     * 上传图片文件
     * @param photoFiles 图片文件列表
     * @param commandId 关联的指令ID
     * @param callback 上传回调
     */
    public void uploadPhotos(List<File> photoFiles, String commandId, UploadCallback callback) {
        new Thread(() -> {
            try {
                if (photoFiles == null || photoFiles.isEmpty()) {
                    callback.onError("没有图片文件可上传");
                    return;
                }

                callback.onProgress("开始上传 " + photoFiles.size() + " 张照片...");

                List<String> uploadedUrls = new ArrayList<>();

                for (int i = 0; i < photoFiles.size(); i++) {
                    File photoFile = photoFiles.get(i);

                    if (!photoFile.exists()) {
                        AppLog.w(TAG, "图片文件不存在: " + photoFile.getPath());
                        continue;
                    }

                    callback.onProgress("正在上传 (" + (i + 1) + "/" + photoFiles.size() + "): " + photoFile.getName());

                    try {
                        // 上传图片文件
                        String fileUrl = apiClient.uploadImage(photoFile, commandId);
                        uploadedUrls.add(fileUrl);

                        AppLog.d(TAG, "图片上传成功: " + photoFile.getName() + " -> " + fileUrl);

                        // 通过 WebSocket 通知小程序
                        if (streamManager != null && streamManager.isRunning()) {
                            streamManager.sendFileUploadNotify(commandId, fileUrl, "image");
                        }

                        // 延迟1秒后再上传下一张
                        if (i < photoFiles.size() - 1) {
                            callback.onProgress("等待1秒后上传下一张照片...");
                            Thread.sleep(1000);
                        }

                    } catch (Exception e) {
                        AppLog.e(TAG, "上传图片失败: " + photoFile.getName(), e);
                        callback.onError("上传失败: " + photoFile.getName() + " - " + e.getMessage());
                    }
                }

                if (uploadedUrls.isEmpty()) {
                    callback.onError("所有图片上传失败");
                } else {
                    String successMessage = "图片上传完成！共上传 " + uploadedUrls.size() + " 张照片";
                    callback.onSuccess(successMessage, uploadedUrls.get(0));

                    // 发送指令结果
                    if (streamManager != null && streamManager.isRunning()) {
                        streamManager.sendCommandResult(commandId, true, successMessage);
                    }
                }

            } catch (Exception e) {
                AppLog.e(TAG, "上传过程出错", e);
                callback.onError("上传过程出错: " + e.getMessage());

                // 发送失败结果
                if (streamManager != null && streamManager.isRunning()) {
                    streamManager.sendCommandResult(commandId, false, "上传失败: " + e.getMessage());
                }
            }
        }).start();
    }

    /**
     * 上传单张图片
     */
    public void uploadPhoto(File photoFile, String commandId, UploadCallback callback) {
        List<File> files = new ArrayList<>();
        files.add(photoFile);
        uploadPhotos(files, commandId, callback);
    }
}
