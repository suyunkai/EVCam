package com.kooo.evcam.camera;


import com.kooo.evcam.AppLog;
import android.media.MediaRecorder;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 视频录制管理类
 */
public class VideoRecorder {
    private static final String TAG = "VideoRecorder";

    private final String cameraId;
    private MediaRecorder mediaRecorder;
    private RecordCallback callback;
    private boolean isRecording = false;
    private boolean waitingForSessionReconfiguration = false;  // 等待会话重新配置
    private String currentFilePath;

    // 分段录制相关
    private static final long SEGMENT_DURATION_MS = 60000;  // 1分钟
    private static final long FILE_SIZE_CHECK_INTERVAL_MS = 5000;  // 每5秒检查一次文件大小
    private android.os.Handler segmentHandler;
    private Runnable segmentRunnable;
    private Runnable fileSizeCheckRunnable;  // 文件大小检查任务
    private int segmentIndex = 0;
    private String saveDirectory;  // 保存目录
    private String cameraPosition;  // 摄像头位置（front/back/left/right）
    private int recordWidth;
    private int recordHeight;
    private long lastFileSize = 0;  // 上次检查的文件大小

    public VideoRecorder(String cameraId) {
        this.cameraId = cameraId;
        this.segmentHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    }

    public void setCallback(RecordCallback callback) {
        this.callback = callback;
    }

    public boolean isRecording() {
        return isRecording;
    }

    public Surface getSurface() {
        if (mediaRecorder != null) {
            Surface surface = mediaRecorder.getSurface();
            if (surface != null) {
                AppLog.d(TAG, "Camera " + cameraId + " getSurface: " + surface + ", isValid=" + surface.isValid());
            } else {
                AppLog.w(TAG, "Camera " + cameraId + " getSurface returned NULL");
            }
            return surface;
        }
        AppLog.w(TAG, "Camera " + cameraId + " getSurface: mediaRecorder is NULL");
        return null;
    }

    /**
     * 获取当前段索引
     */
    public int getCurrentSegmentIndex() {
        return segmentIndex;
    }

    /**
     * 获取当前文件路径
     */
    public String getCurrentFilePath() {
        return currentFilePath;
    }

    /**
     * 检查是否正在等待会话重新配置
     */
    public boolean isWaitingForSessionReconfiguration() {
        return waitingForSessionReconfiguration;
    }

    /**
     * 清除等待会话重新配置的标志
     */
    public void clearWaitingForSessionReconfiguration() {
        waitingForSessionReconfiguration = false;
    }

    /**
     * 准备录制器
     */
    private void prepareMediaRecorder(String filePath, int width, int height) throws IOException {
        mediaRecorder = new MediaRecorder();
        
        // 添加监听器以监控 MediaRecorder 状态（调试用）
        mediaRecorder.setOnInfoListener((mr, what, extra) -> {
            String info = "UNKNOWN";
            switch (what) {
                case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                    info = "MAX_DURATION_REACHED";
                    break;
                case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                    info = "MAX_FILESIZE_REACHED";
                    break;
                case MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN:
                    info = "INFO_UNKNOWN";
                    break;
            }
            AppLog.d(TAG, "Camera " + cameraId + " MediaRecorder INFO: " + info + " (what=" + what + ", extra=" + extra + ")");
        });
        
        mediaRecorder.setOnErrorListener((mr, what, extra) -> {
            String error = "UNKNOWN";
            switch (what) {
                case MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN:
                    error = "ERROR_UNKNOWN";
                    break;
                case MediaRecorder.MEDIA_ERROR_SERVER_DIED:
                    error = "SERVER_DIED";
                    break;
            }
            AppLog.e(TAG, "Camera " + cameraId + " MediaRecorder ERROR: " + error + " (what=" + what + ", extra=" + extra + ")");
        });
        
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(filePath);
        mediaRecorder.setVideoEncodingBitRate(1000000); // 降低到1Mbps以减少资源消耗
        mediaRecorder.setVideoFrameRate(30);
        mediaRecorder.setVideoSize(width, height);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.prepare();
        
        // 验证 Surface 是否有效（调试用）
        Surface surface = mediaRecorder.getSurface();
        if (surface != null) {
            AppLog.d(TAG, "Camera " + cameraId + " MediaRecorder Surface created: " + surface + 
                    ", isValid=" + surface.isValid());
        } else {
            AppLog.e(TAG, "Camera " + cameraId + " MediaRecorder Surface is NULL after prepare!");
        }
    }

    /**
     * 准备录制器（不启动）
     */
    public boolean prepareRecording(String filePath, int width, int height) {
        if (isRecording) {
            AppLog.w(TAG, "Camera " + cameraId + " is already recording");
            return false;
        }

        // 先释放旧的 MediaRecorder（如果存在）
        releaseMediaRecorder();

        try {
            // 保存录制参数用于分段
            this.recordWidth = width;
            this.recordHeight = height;
            this.segmentIndex = 0;

            // 从文件路径中提取保存目录和摄像头位置
            File file = new File(filePath);
            this.saveDirectory = file.getParent();
            String fileName = file.getName();
            // 文件名格式：日期_时间_摄像头位置.mp4
            // 提取摄像头位置（最后一个下划线后的部分，去掉.mp4）
            int lastUnderscoreIndex = fileName.lastIndexOf('_');
            if (lastUnderscoreIndex > 0 && fileName.endsWith(".mp4")) {
                this.cameraPosition = fileName.substring(lastUnderscoreIndex + 1, fileName.length() - 4);
            } else {
                this.cameraPosition = "unknown";
            }

            // 使用传入的文件路径作为第一段
            prepareMediaRecorder(filePath, width, height);
            currentFilePath = filePath;
            AppLog.d(TAG, "Camera " + cameraId + " prepared recording to: " + filePath);
            return true;
        } catch (IOException e) {
            AppLog.e(TAG, "Failed to prepare recording for camera " + cameraId, e);
            releaseMediaRecorder();
            // 确保状态被重置
            isRecording = false;
            waitingForSessionReconfiguration = false;
            currentFilePath = null;
            segmentIndex = 0;
            if (callback != null) {
                callback.onRecordError(cameraId, e.getMessage());
            }
            return false;
        }
    }

    /**
     * 生成新的分段文件路径（使用当前时间戳）
     */
    private String generateSegmentPath() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = timestamp + "_" + cameraPosition + ".mp4";
        return new File(saveDirectory, fileName).getAbsolutePath();
    }

    /**
     * 启动录制（必须先调用 prepareRecording）
     */
    public boolean startRecording() {
        if (mediaRecorder == null) {
            AppLog.e(TAG, "Camera " + cameraId + " MediaRecorder not prepared");
            return false;
        }

        if (isRecording) {
            AppLog.w(TAG, "Camera " + cameraId + " is already recording");
            return false;
        }

        // 诊断：检查 Surface 状态
        Surface surface = mediaRecorder.getSurface();
        if (surface == null) {
            AppLog.e(TAG, "Camera " + cameraId + " CRITICAL: Surface is NULL before start!");
        } else if (!surface.isValid()) {
            AppLog.e(TAG, "Camera " + cameraId + " CRITICAL: Surface is INVALID before start! Surface=" + surface);
        } else {
            AppLog.d(TAG, "Camera " + cameraId + " Surface OK before start: " + surface + ", isValid=true");
        }

        try {
            AppLog.d(TAG, "Camera " + cameraId + " calling mediaRecorder.start()...");
            mediaRecorder.start();
            isRecording = true;
            lastFileSize = 0;  // 重置文件大小计数
            AppLog.d(TAG, "Camera " + cameraId + " started recording segment " + segmentIndex);
            
            // 诊断：start() 后再次检查 Surface 状态
            Surface surfaceAfterStart = mediaRecorder.getSurface();
            if (surfaceAfterStart != null) {
                AppLog.d(TAG, "Camera " + cameraId + " Surface after start: " + surfaceAfterStart + 
                        ", isValid=" + surfaceAfterStart.isValid());
            }
            
            if (callback != null && segmentIndex == 0) {
                // 只在第一段时通知开始录制
                callback.onRecordStart(cameraId);
            }

            // 启动分段定时器
            scheduleNextSegment();
            
            // 启动文件大小检查（用于诊断 MediaRecorder 是否在接收帧）
            scheduleFileSizeCheck();

            return true;
        } catch (RuntimeException e) {
            AppLog.e(TAG, "Failed to start recording for camera " + cameraId, e);
            releaseMediaRecorder();
            if (callback != null) {
                callback.onRecordError(cameraId, e.getMessage());
            }
            return false;
        }
    }

    /**
     * 调度下一段录制
     */
    private void scheduleNextSegment() {
        // 取消之前的定时器
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
        }

        // 创建新的分段任务
        segmentRunnable = () -> {
            if (isRecording) {
                AppLog.d(TAG, "Camera " + cameraId + " switching to next segment");
                switchToNextSegment();
            }
        };

        // 延迟执行（1分钟后）
        segmentHandler.postDelayed(segmentRunnable, SEGMENT_DURATION_MS);
        AppLog.d(TAG, "Camera " + cameraId + " scheduled next segment in " + (SEGMENT_DURATION_MS / 1000) + " seconds");
    }

    /**
     * 调度文件大小检查（诊断用）
     */
    private void scheduleFileSizeCheck() {
        // 取消之前的检查
        if (fileSizeCheckRunnable != null) {
            segmentHandler.removeCallbacks(fileSizeCheckRunnable);
        }

        fileSizeCheckRunnable = () -> {
            if (isRecording && currentFilePath != null) {
                File file = new File(currentFilePath);
                long currentSize = file.exists() ? file.length() : 0;
                long sizeIncrease = currentSize - lastFileSize;
                
                if (sizeIncrease == 0 && lastFileSize > 0) {
                    // 文件大小没有增长，可能有问题
                    AppLog.w(TAG, "Camera " + cameraId + " WARNING: File size not growing! Current: " + currentSize + " bytes, Last: " + lastFileSize + " bytes");
                } else if (currentSize == 0) {
                    // 文件大小为 0，MediaRecorder 可能没有接收到帧
                    AppLog.e(TAG, "Camera " + cameraId + " ERROR: File size is 0! MediaRecorder is NOT receiving frames!");
                } else {
                    // 正常情况
                    AppLog.d(TAG, "Camera " + cameraId + " file size check: " + currentSize + " bytes (" + (currentSize / 1024) + " KB), increase: " + sizeIncrease + " bytes");
                }
                
                lastFileSize = currentSize;
                
                // 继续下一次检查
                scheduleFileSizeCheck();
            }
        };

        segmentHandler.postDelayed(fileSizeCheckRunnable, FILE_SIZE_CHECK_INTERVAL_MS);
    }

    /**
     * 取消文件大小检查
     */
    private void cancelFileSizeCheck() {
        if (fileSizeCheckRunnable != null) {
            segmentHandler.removeCallbacks(fileSizeCheckRunnable);
            fileSizeCheckRunnable = null;
        }
    }

    /**
     * 切换到下一段
     * 注意：这个方法需要通过回调通知外部重新配置相机会话
     */
    private void switchToNextSegment() {
        try {
            // 停止当前录制
            if (mediaRecorder != null) {
                // 诊断：在 stop() 之前检查文件大小
                long fileSizeBeforeStop = 0;
                if (currentFilePath != null) {
                    File file = new File(currentFilePath);
                    fileSizeBeforeStop = file.exists() ? file.length() : 0;
                    AppLog.d(TAG, "Camera " + cameraId + " file size before stop: " + fileSizeBeforeStop + " bytes (" + (fileSizeBeforeStop / 1024) + " KB)");
                }
                
                try {
                    // 如果文件太小，说明 MediaRecorder 没有接收到帧，跳过 stop()
                    if (fileSizeBeforeStop < 1024) {
                        AppLog.e(TAG, "Camera " + cameraId + " file size too small (" + fileSizeBeforeStop + " bytes), MediaRecorder may not be receiving frames. Skipping stop().");
                        isRecording = false;
                    } else {
                        mediaRecorder.stop();
                        isRecording = false;  // 立即更新状态
                        AppLog.d(TAG, "Camera " + cameraId + " stopped segment " + segmentIndex + ": " + currentFilePath);

                        // 验证并清理损坏的文件
                        validateAndCleanupFile(currentFilePath);
                    }
                } catch (RuntimeException e) {
                    AppLog.e(TAG, "Error stopping segment for camera " + cameraId + " (file size was: " + fileSizeBeforeStop + " bytes)", e);
                    isRecording = false;  // 即使失败也更新状态

                    // 停止失败，删除损坏的文件
                    if (currentFilePath != null) {
                        File file = new File(currentFilePath);
                        if (file.exists()) {
                            file.delete();
                            AppLog.w(TAG, "Deleted corrupted segment file: " + currentFilePath);
                        }
                    }
                }
                releaseMediaRecorder();
            }

            // 准备下一段（使用新的时间戳）
            segmentIndex++;
            String nextSegmentPath = generateSegmentPath();
            prepareMediaRecorder(nextSegmentPath, recordWidth, recordHeight);
            currentFilePath = nextSegmentPath;

            // 设置等待会话重新配置的标志
            waitingForSessionReconfiguration = true;

            // 通知外部需要重新配置相机会话（因为 MediaRecorder 的 Surface 已经改变）
            // 外部需要调用 startRecording() 来启动新段的录制
            if (callback != null) {
                callback.onSegmentSwitch(cameraId, segmentIndex);
            }

            // 注意：不在这里调用 start()，而是等待外部重新配置相机会话后调用 startRecording()
            // 这样可以确保新的 Surface 已经添加到 CaptureSession 中
            AppLog.d(TAG, "Camera " + cameraId + " prepared segment " + segmentIndex + ": " + nextSegmentPath + ", waiting for session reconfiguration");

        } catch (Exception e) {
            AppLog.e(TAG, "Failed to switch segment for camera " + cameraId, e);
            isRecording = false;
            waitingForSessionReconfiguration = false;
            if (callback != null) {
                callback.onRecordError(cameraId, "Failed to switch segment: " + e.getMessage());
            }
        }
    }

    /**
     * 开始录制（旧方法，保持兼容性）
     */
    public boolean startRecording(String filePath, int width, int height) {
        if (prepareRecording(filePath, width, height)) {
            return startRecording();
        }
        return false;
    }

    /**
     * 停止录制
     */
    public void stopRecording() {
        // 取消分段定时器
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }
        
        // 取消文件大小检查
        cancelFileSizeCheck();

        // 如果正在等待会话重新配置，说明MediaRecorder已经stop过了，只需要清理状态
        if (waitingForSessionReconfiguration) {
            AppLog.d(TAG, "Camera " + cameraId + " is waiting for session reconfiguration, skipping stop");
            isRecording = false;
            waitingForSessionReconfiguration = false;
            releaseMediaRecorder();

            // 验证并清理损坏的文件
            validateAndCleanupFile(currentFilePath);

            currentFilePath = null;
            segmentIndex = 0;
            if (callback != null) {
                callback.onRecordStop(cameraId);
            }
            return;
        }

        if (!isRecording) {
            AppLog.w(TAG, "Camera " + cameraId + " is not recording");
            return;
        }

        // 诊断：在 stop() 之前检查文件大小
        long fileSizeBeforeStop = 0;
        if (currentFilePath != null) {
            File file = new File(currentFilePath);
            fileSizeBeforeStop = file.exists() ? file.length() : 0;
            AppLog.d(TAG, "Camera " + cameraId + " file size before stop: " + fileSizeBeforeStop + " bytes (" + (fileSizeBeforeStop / 1024) + " KB)");
        }

        try {
            if (mediaRecorder != null) {
                // 如果文件太小，说明 MediaRecorder 没有接收到帧，跳过 stop()
                if (fileSizeBeforeStop < 1024) {
                    AppLog.e(TAG, "Camera " + cameraId + " file size too small (" + fileSizeBeforeStop + " bytes), MediaRecorder may not be receiving frames. Skipping stop().");
                } else {
                    mediaRecorder.stop();
                    AppLog.d(TAG, "Camera " + cameraId + " stopped recording: " + currentFilePath + " (total segments: " + (segmentIndex + 1) + ")");
                }
            }
            isRecording = false;

            // 验证并清理损坏的文件
            validateAndCleanupFile(currentFilePath);

            if (callback != null) {
                callback.onRecordStop(cameraId);
            }
        } catch (RuntimeException e) {
            AppLog.e(TAG, "Failed to stop recording for camera " + cameraId + " (file size was: " + fileSizeBeforeStop + " bytes)", e);
            isRecording = false;

            // 录制失败，删除损坏的文件
            if (currentFilePath != null) {
                File file = new File(currentFilePath);
                if (file.exists()) {
                    file.delete();
                    AppLog.w(TAG, "Deleted corrupted video file: " + currentFilePath);
                }
            }
        } finally {
            releaseMediaRecorder();
            currentFilePath = null;
            segmentIndex = 0;
        }
    }

    /**
     * 验证并清理损坏的视频文件
     */
    private void validateAndCleanupFile(String filePath) {
        if (filePath == null) {
            return;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return;
        }

        // 验证文件大小（至少应该有 50KB，否则可能是损坏的视频）
        final long MIN_VALID_SIZE = 50 * 1024; // 50KB
        long fileSize = file.length();

        if (fileSize < MIN_VALID_SIZE) {
            AppLog.w(TAG, "Video file too small: " + filePath + " (size: " + fileSize + " bytes, minimum: " + MIN_VALID_SIZE + " bytes). Deleting...");
            file.delete();
        } else {
            AppLog.d(TAG, "Video file validated: " + filePath + " (size: " + (fileSize / 1024) + " KB)");
        }
    }

    /**
     * 释放录制器
     */
    private void releaseMediaRecorder() {
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        // 取消分段定时器
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }
        
        // 取消文件大小检查
        cancelFileSizeCheck();

        // 只有在真正录制中且mediaRecorder不为null时才调用stopRecording
        if (isRecording && mediaRecorder != null) {
            stopRecording();
        } else {
            // 直接清理状态
            isRecording = false;
            waitingForSessionReconfiguration = false;
            releaseMediaRecorder();
            currentFilePath = null;
            segmentIndex = 0;
        }
    }
}
