package com.kooo.evcam.camera;


import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.FileTransferManager;
import com.kooo.evcam.StorageHelper;
import android.content.Context;
import android.os.Environment;
import android.util.Log;
import android.util.Size;
import android.view.TextureView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 四路摄像头管理器
 */
public class MultiCameraManager {
    private static final String TAG = "MultiCameraManager";

    private static final int DEFAULT_MAX_OPEN_CAMERAS = 4;
    // 录制分辨率将使用预览的实际分辨率，不再硬编码

    private final Context context;
    private final Map<String, SingleCamera> cameras = new LinkedHashMap<>();
    private final Map<String, VideoRecorder> recorders = new LinkedHashMap<>();
    private final Map<String, CodecVideoRecorder> codecRecorders = new LinkedHashMap<>();  // 软编码录制器
    private final List<String> activeCameraKeys = new ArrayList<>();
    private int maxOpenCameras = DEFAULT_MAX_OPEN_CAMERAS;

    private boolean isRecording = false;
    private boolean useCodecRecording = false;  // 是否使用软编码录制（用于 L6/L7）
    private boolean useRelayWrite = false;      // 是否使用中转写入（录制到内部存储，异步传输到U盘）
    private File finalSaveDir = null;           // 最终存储目录（用于中转写入模式）
    private volatile int lastNotifiedSegmentIndex = -1;  // 已通知的分段索引，避免重复通知
    
    // Watchdog 回退相关
    private String currentRecordingTimestamp = null;  // 当前录制的时间戳（用于重建时继续录制）
    private Set<String> currentEnabledCameras = null;  // 当前启用的摄像头集合
    private int rebuildAttemptCount = 0;  // 重建尝试次数（0=首次, 1=重建MediaRecorder, 2+=回退Codec）
    private static final int CODEC_FALLBACK_THRESHOLD = 2;  // 触发 Codec 回退的阈值
    private StatusCallback statusCallback;
    private PreviewSizeCallback previewSizeCallback;
    private volatile int sessionConfiguredCount = 0;
    private volatile int expectedSessionCount = 0;
    private Runnable pendingRecordingStart = null;
    private android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sessionTimeoutRunnable = null;
    private final Object sessionLock = new Object();  // 用于同步 session 配置计数

    public interface StatusCallback {
        void onCameraStatusUpdate(String cameraId, String status);
    }

    public interface PreviewSizeCallback {
        void onPreviewSizeChosen(String cameraKey, String cameraId, Size previewSize);
    }
    
    public interface SegmentSwitchCallback {
        void onSegmentSwitch(int newSegmentIndex);
    }

    /**
     * 损坏文件删除回调
     */
    public interface CorruptedFilesCallback {
        void onCorruptedFilesDeleted(List<String> deletedFiles);
    }

    /**
     * Codec 回退通知回调
     */
    public interface CodecFallbackCallback {
        void onCodecFallback();
    }

    public MultiCameraManager(Context context) {
        this.context = context;
    }
    
    private SegmentSwitchCallback segmentSwitchCallback;
    private CorruptedFilesCallback corruptedFilesCallback;
    private CodecFallbackCallback codecFallbackCallback;

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    public void setPreviewSizeCallback(PreviewSizeCallback callback) {
        this.previewSizeCallback = callback;
    }
    
    public void setCorruptedFilesCallback(CorruptedFilesCallback callback) {
        this.corruptedFilesCallback = callback;
    }
    
    public void setSegmentSwitchCallback(SegmentSwitchCallback callback) {
        this.segmentSwitchCallback = callback;
    }

    public void setCodecFallbackCallback(CodecFallbackCallback callback) {
        this.codecFallbackCallback = callback;
    }

    public void setMaxOpenCameras(int maxOpenCameras) {
        this.maxOpenCameras = Math.max(1, maxOpenCameras);
    }

    /**
     * 设置单一输出模式（用于不支持多路输出的车机平台，如 L6/L7）
     * 在此模式下，录制时只使用 MediaRecorder Surface，不使用 TextureView Surface
     * 这会导致录制期间预览冻结，但能确保录制正常工作
     * 
     * @param enabled true 表示启用单一输出模式
     * @deprecated 请使用 setCodecRecordingMode(true) 代替，它使用 OpenGL 渲染方案
     */
    @Deprecated
    public void setSingleOutputMode(boolean enabled) {
        AppLog.d(TAG, "Setting single output mode: " + (enabled ? "ENABLED" : "DISABLED"));
        for (SingleCamera camera : cameras.values()) {
            camera.setSingleOutputMode(enabled);
        }
    }

    /**
     * 设置软编码录制模式（用于 L6/L7 等不支持 MediaRecorder 直接录制的车机平台）
     * 在此模式下，使用 OpenGL 渲染 + MediaCodec 编码 + MediaMuxer 写入文件
     * 
     * 优点：
     * - 预览保持流畅，不会冻结
     * - 不依赖硬件对 MediaRecorder Surface 的支持
     * 
     * @param enabled true 表示启用软编码录制模式
     */
    public void setCodecRecordingMode(boolean enabled) {
        this.useCodecRecording = enabled;
        AppLog.d(TAG, "Codec recording mode: " + (enabled ? "ENABLED" : "DISABLED"));
    }

    /**
     * 检查是否使用软编码录制模式
     */
    public boolean isCodecRecordingMode() {
        return useCodecRecording;
    }

    /**
     * 获取指定位置的摄像头实例
     * @param position 位置（front/back/left/right）
     * @return SingleCamera实例，如果不存在则返回null
     */
    public SingleCamera getCamera(String position) {
        return cameras.get(position);
    }

    /**
     * 初始化摄像头
     * 支持 null 参数以适配不同数量的摄像头配置（1摄/2摄/4摄）
     */
    public void initCameras(String frontId, TextureView frontView,
                           String backId, TextureView backView,
                           String leftId, TextureView leftView,
                           String rightId, TextureView rightView) {

        // 清空之前的摄像头实例
        cameras.clear();
        
        // 根据参数创建摄像头实例（支持 null 参数以跳过某些摄像头）
        if (frontId != null && frontView != null) {
            SingleCamera frontCamera = new SingleCamera(context, frontId, frontView);
            frontCamera.setCameraPosition("front");
            cameras.put("front", frontCamera);
            AppLog.d(TAG, "初始化前摄像头: ID=" + frontId);
        }

        if (backId != null && backView != null) {
            SingleCamera backCamera = new SingleCamera(context, backId, backView);
            backCamera.setCameraPosition("back");
            cameras.put("back", backCamera);
            AppLog.d(TAG, "初始化后摄像头: ID=" + backId);
        }

        if (leftId != null && leftView != null) {
            SingleCamera leftCamera = new SingleCamera(context, leftId, leftView);
            leftCamera.setCameraPosition("left");
            cameras.put("left", leftCamera);
            AppLog.d(TAG, "初始化左摄像头: ID=" + leftId);
        }

        if (rightId != null && rightView != null) {
            SingleCamera rightCamera = new SingleCamera(context, rightId, rightView);
            rightCamera.setCameraPosition("right");
            cameras.put("right", rightCamera);
            AppLog.d(TAG, "初始化右摄像头: ID=" + rightId);
        }
        
        AppLog.d(TAG, "共初始化 " + cameras.size() + " 个摄像头");

        // 检测重复的cameraId，只让第一个实例成为主实例
        Set<String> primaryIds = new HashSet<>();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            SingleCamera camera = entry.getValue();
            String id = camera.getCameraId();
            
            if (primaryIds.add(id)) {
                // 第一次遇到这个ID，设为主实例
                camera.setPrimaryInstance(true);
                AppLog.d(TAG, "Camera " + id + " at position " + entry.getKey() + " set as PRIMARY");
            } else {
                // 重复的ID，设为从属实例
                camera.setPrimaryInstance(false);
                AppLog.d(TAG, "Camera " + id + " at position " + entry.getKey() + " set as SECONDARY (sharing with primary)");
            }
        }

        // 为每个摄像头设置回调
        CameraCallback callback = new CameraCallback() {
            @Override
            public void onCameraOpened(String cameraId) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " opened");
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, "已打开");
                }
            }

            @Override
            public void onCameraConfigured(String cameraId) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " configured");
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, "预览已启动");
                }

                // 检查是否有录制器正在等待会话重新配置（分段切换）
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        String key = entry.getKey();
                        VideoRecorder recorder = recorders.get(key);

                        if (recorder != null && recorder.isWaitingForSessionReconfiguration()) {
                            AppLog.d(TAG, "Camera " + cameraId + " session reconfigured, starting next segment recording");
                            recorder.clearWaitingForSessionReconfiguration();
                            recorder.startRecording();
                        }
                        break;
                    }
                }

                // 检查是否所有会话都已配置完成（线程安全处理）
                synchronized (sessionLock) {
                    if (expectedSessionCount > 0) {
                        sessionConfiguredCount++;
                        AppLog.d(TAG, "Session configured: " + sessionConfiguredCount + "/" + expectedSessionCount);

                        if (sessionConfiguredCount >= expectedSessionCount) {
                            // 所有会话都已配置完成，执行待处理的录制启动
                            final Runnable recordingTask = pendingRecordingStart;
                            if (recordingTask != null) {
                                AppLog.d(TAG, "All sessions configured, starting recording...");
                                // 取消超时任务
                                if (sessionTimeoutRunnable != null) {
                                    mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                    sessionTimeoutRunnable = null;
                                }
                                pendingRecordingStart = null;
                                // 延迟 300ms 再启动录制，让 Camera Session 稳定
                                // 某些车机设备需要这个延迟才能正确将帧发送到 MediaRecorder Surface
                                mainHandler.postDelayed(recordingTask, 300);
                            }
                            sessionConfiguredCount = 0;
                            expectedSessionCount = 0;
                        }
                    }
                }
            }

            @Override
            public void onCameraClosed(String cameraId) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " closed");
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, "已关闭");
                }
            }

            @Override
            public void onCameraError(String cameraId, int errorCode) {
                String errorMsg = getErrorMessage(errorCode);
                AppLog.e(TAG, "Callback: Camera " + cameraId + " error: " + errorCode + " - " + errorMsg);
                if (statusCallback != null) {
                    statusCallback.onCameraStatusUpdate(cameraId, "错误: " + errorMsg);
                }

                // 如果在等待会话配置期间发生错误，减少期望计数（线程安全处理）
                synchronized (sessionLock) {
                    if (expectedSessionCount > 0 && errorCode == -3) {
                        expectedSessionCount--;
                        AppLog.d(TAG, "Session configuration failed, adjusted expected count: " + sessionConfiguredCount + "/" + expectedSessionCount);

                        // 检查是否所有剩余会话都已配置完成
                        if (sessionConfiguredCount >= expectedSessionCount && expectedSessionCount > 0) {
                            final Runnable recordingTask = pendingRecordingStart;
                            if (recordingTask != null) {
                                AppLog.d(TAG, "Remaining sessions configured, starting recording...");
                                // 取消超时任务
                                if (sessionTimeoutRunnable != null) {
                                    mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                    sessionTimeoutRunnable = null;
                                }
                                pendingRecordingStart = null;
                                // 延迟 300ms 再启动录制，让 Camera Session 稳定
                                mainHandler.postDelayed(recordingTask, 300);
                            }
                            sessionConfiguredCount = 0;
                            expectedSessionCount = 0;
                        } else if (expectedSessionCount == 0) {
                            // 所有会话都失败了
                            AppLog.e(TAG, "All sessions failed to configure");
                            if (sessionTimeoutRunnable != null) {
                                mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                sessionTimeoutRunnable = null;
                            }
                            sessionConfiguredCount = 0;
                            expectedSessionCount = 0;
                            pendingRecordingStart = null;
                        }
                    }
                }
            }

            @Override
            public void onPreviewSizeChosen(String cameraId, Size previewSize) {
                AppLog.d(TAG, "Callback: Camera " + cameraId + " preview size: " + previewSize);
                // 找到对应的 camera key
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        if (previewSizeCallback != null) {
                            previewSizeCallback.onPreviewSizeChosen(entry.getKey(), cameraId, previewSize);
                        }
                    }
                }
            }
        };

        // 为已初始化的摄像头设置回调
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            entry.getValue().setCallback(callback);
        }

        // 为已初始化的摄像头创建录制器实例
        recorders.clear();
        if (frontId != null && cameras.containsKey("front")) {
            recorders.put("front", new VideoRecorder(frontId));
        }
        if (backId != null && cameras.containsKey("back")) {
            recorders.put("back", new VideoRecorder(backId));
        }
        if (leftId != null && cameras.containsKey("left")) {
            recorders.put("left", new VideoRecorder(leftId));
        }
        if (rightId != null && cameras.containsKey("right")) {
            recorders.put("right", new VideoRecorder(rightId));
        }

        // 为每个录制器设置回调
        RecordCallback recordCallback = new RecordCallback() {
            @Override
            public void onRecordStart(String cameraId) {
                AppLog.d(TAG, "Recording started for camera " + cameraId);
            }

            @Override
            public void onRecordStop(String cameraId) {
                AppLog.d(TAG, "Recording stopped for camera " + cameraId);
            }

            @Override
            public void onRecordError(String cameraId, String error) {
                AppLog.e(TAG, "Recording error for camera " + cameraId + ": " + error);
            }

            @Override
            public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {
                AppLog.d(TAG, "Prepare segment switch for camera " + cameraId + " (current segment: " + currentSegmentIndex + ")");
                // 找到对应的 camera 并暂停向录制 Surface 发送帧
                // 这是为了避免在 MediaRecorder 被释放后继续向旧 Surface 发送帧导致 CAPTURE FAILED
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        SingleCamera camera = entry.getValue();
                        camera.pauseRecordSurface();
                        AppLog.d(TAG, "Camera " + cameraId + " recording surface paused before segment switch");
                        break;
                    }
                }
            }

            @Override
            public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {
                AppLog.d(TAG, "Segment switch for camera " + cameraId + " to segment " + newSegmentIndex);
                // 找到对应的 camera key 和 camera
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        String key = entry.getKey();
                        SingleCamera camera = entry.getValue();
                        VideoRecorder recorder = recorders.get(key);

                        if (camera != null && recorder != null) {
                            // 如果使用中转写入，将上一个分段的文件传输到最终目录
                            if (useRelayWrite && finalSaveDir != null && newSegmentIndex > 0 && completedFilePath != null) {
                                // 传输已完成的文件（由回调提供确切路径，避免传输正在录制的新文件）
                                scheduleRelayTransfer(completedFilePath);
                            }
                            
                            // 更新录制 Surface 并重新创建会话
                            camera.setRecordSurface(recorder.getSurface());
                            camera.recreateSession();
                            AppLog.d(TAG, "Recreated session for camera " + cameraId + " after segment switch");
                        }
                        
                        // 通知分段切换回调（只通知一次，第一个触发的摄像头会通知）
                        if (segmentSwitchCallback != null && newSegmentIndex > lastNotifiedSegmentIndex) {
                            lastNotifiedSegmentIndex = newSegmentIndex;
                            segmentSwitchCallback.onSegmentSwitch(newSegmentIndex);
                        }
                        break;
                    }
                }
            }

            @Override
            public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {
                if (deletedFiles != null && !deletedFiles.isEmpty()) {
                    AppLog.w(TAG, "Corrupted files deleted for camera " + cameraId + ": " + deletedFiles.size() + " file(s)");
                    for (String file : deletedFiles) {
                        AppLog.d(TAG, "  Deleted: " + file);
                    }
                    // 通知 MainActivity 显示弹窗
                    if (corruptedFilesCallback != null) {
                        mainHandler.post(() -> corruptedFilesCallback.onCorruptedFilesDeleted(deletedFiles));
                    }
                }
            }

            @Override
            public void onRecordingRebuildRequested(String cameraId, String reason) {
                AppLog.e(TAG, "Recording rebuild requested for camera " + cameraId + ", reason: " + reason);
                handleRecordingRebuildRequest(cameraId, reason);
            }
        };

        // 为已创建的录制器设置回调
        for (Map.Entry<String, VideoRecorder> entry : recorders.entrySet()) {
            entry.getValue().setCallback(recordCallback);
        }

        AppLog.d(TAG, "Cameras initialized");
    }

    /**
     * 获取错误信息描述
     */
    private String getErrorMessage(int errorCode) {
        switch (errorCode) {
            case 1: // ERROR_CAMERA_IN_USE
                return "摄像头正在被使用";
            case 2: // ERROR_MAX_CAMERAS_IN_USE
                return "已达到最大摄像头数量";
            case 3: // ERROR_CAMERA_DISABLED
                return "摄像头被禁用";
            case 4: // ERROR_CAMERA_DEVICE
                return "摄像头设备错误(资源不足?)";
            case 5: // ERROR_CAMERA_SERVICE
                return "摄像头服务错误";
            case -1:
                return "访问失败";
            case -2:
                return "权限不足";
            case -3:
                return "会话配置失败";
            case -4:
                return "摄像头断开连接(资源耗尽)";
            default:
                return "未知错误(" + errorCode + ")";
        }
    }

    /**
     * 打开所有摄像头
     */
    public void openAllCameras() {
        AppLog.d(TAG, "Opening all cameras...");

        activeCameraKeys.clear();
        int opened = 0;
        Set<String> openedIds = new HashSet<>();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            if (opened >= maxOpenCameras) {
                break;
            }
            SingleCamera camera = entry.getValue();
            String id = camera.getCameraId();
            if (!openedIds.add(id)) {
                continue;
            }
            activeCameraKeys.add(entry.getKey());
            camera.openCamera();
            opened++;
        }

        AppLog.d(TAG, "Requested open cameras: " + activeCameraKeys);
    }

    /**
     * 关闭所有摄像头
     */
    public void closeAllCameras() {
        for (SingleCamera camera : cameras.values()) {
            camera.closeCamera();
        }
        AppLog.d(TAG, "All cameras closed");
    }

    /**
     * 开始录制所有摄像头（自动生成时间戳）
     */
    public boolean startRecording() {
        // 生成统一的时间戳
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        return startRecording(timestamp);
    }

    /**
     * 开始录制所有摄像头（使用指定的时间戳）
     * @param timestamp 统一的时间戳，用于所有摄像头的文件命名
     */
    public boolean startRecording(String timestamp) {
        if (isRecording) {
            AppLog.w(TAG, "Already recording");
            return false;
        }

        // 根据模式选择录制方式
        if (useCodecRecording) {
            return startCodecRecording(timestamp, null);
        } else {
            return startMediaRecorderRecording(timestamp, null);
        }
    }

    /**
     * 开始录制指定的摄像头（使用指定的时间戳和摄像头列表）
     * @param timestamp 统一的时间戳，用于所有摄像头的文件命名
     * @param enabledCameras 要录制的摄像头位置集合（如 ["front", "back"]），为 null 时录制所有摄像头
     */
    public boolean startRecording(String timestamp, Set<String> enabledCameras) {
        if (isRecording) {
            AppLog.w(TAG, "Already recording");
            return false;
        }

        // 根据模式选择录制方式
        if (useCodecRecording) {
            return startCodecRecording(timestamp, enabledCameras);
        } else {
            return startMediaRecorderRecording(timestamp, enabledCameras);
        }
    }

    /**
     * 使用 MediaRecorder 开始录制（标准模式）
     * @param timestamp 时间戳
     * @param enabledCameras 要录制的摄像头位置集合，为 null 时录制所有摄像头
     */
    private boolean startMediaRecorderRecording(String timestamp, Set<String> enabledCameras) {
        AppLog.d(TAG, "Starting MediaRecorder recording with timestamp: " + timestamp);

        // 记录当前录制参数（用于 Watchdog 重建）
        currentRecordingTimestamp = timestamp;
        currentEnabledCameras = enabledCameras;

        // 检查是否使用中转写入模式
        AppConfig appConfig = new AppConfig(context);
        useRelayWrite = appConfig.shouldUseRelayWrite();
        
        // 获取录制目录（可能是临时目录或最终目录）
        File saveDir = StorageHelper.getRecordingDir(context);
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }
        
        // 如果使用中转写入，记录最终目录
        if (useRelayWrite) {
            finalSaveDir = StorageHelper.getFinalVideoDir(context);
            if (!finalSaveDir.exists()) {
                finalSaveDir.mkdirs();
            }
            AppLog.d(TAG, "Relay write mode: recording to " + saveDir.getAbsolutePath() + 
                    ", will transfer to " + finalSaveDir.getAbsolutePath());
        } else {
            finalSaveDir = null;
        }

        List<String> allKeys = getActiveCameraKeys();
        if (allKeys.isEmpty()) {
            AppLog.e(TAG, "No active cameras for recording");
            return false;
        }

        // 如果指定了摄像头列表，过滤 keys
        final List<String> keys;
        if (enabledCameras != null && !enabledCameras.isEmpty()) {
            List<String> filteredKeys = new ArrayList<>();
            for (String key : allKeys) {
                if (enabledCameras.contains(key)) {
                    filteredKeys.add(key);
                }
            }
            keys = filteredKeys;
            AppLog.d(TAG, "Filtered recording cameras: " + keys);
        } else {
            keys = allKeys;
        }

        if (keys.isEmpty()) {
            AppLog.e(TAG, "No enabled cameras for recording after filtering");
            return false;
        }

        // 获取录制配置（使用上面已创建的 appConfig）
        long segmentDurationMs = appConfig.getSegmentDurationMs();
        AppLog.d(TAG, "Segment duration: " + (segmentDurationMs / 1000) + " seconds (" + appConfig.getSegmentDurationMinutes() + " minutes)");
        
        // 获取帧率配置（根据帧率等级设置计算）
        int targetFrameRate = appConfig.getActualFrameRate(30);  // 假设硬件支持30fps
        AppLog.d(TAG, "Target frame rate: " + targetFrameRate + " fps (level: " + appConfig.getFramerateLevel() + ")");

        // 第一步：准备所有 MediaRecorder（但不启动）
        // 使用每个摄像头的实际预览分辨率，而不是硬编码的值
        boolean prepareSuccess = true;
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            VideoRecorder recorder = recorders.get(key);
            if (camera == null || recorder == null) {
                continue;
            }
            
            // 获取摄像头的实际预览分辨率
            Size previewSize = camera.getPreviewSize();
            if (previewSize == null) {
                AppLog.e(TAG, "Camera " + key + " preview size not available, using fallback 1280x720");
                previewSize = new Size(1280, 720);  // 回退到常见分辨率
            }
            
            // 计算码率（基于分辨率和帧率）
            int bitrate = appConfig.getActualBitrate(
                    previewSize.getWidth(), 
                    previewSize.getHeight(), 
                    targetFrameRate);
            
            // 设置录制参数
            recorder.setSegmentDuration(segmentDurationMs);
            recorder.setVideoBitrate(bitrate);
            recorder.setVideoFrameRate(targetFrameRate);
            
            AppLog.d(TAG, "Recording params for " + key + ": " + 
                    previewSize.getWidth() + "x" + previewSize.getHeight() + 
                    " @ " + targetFrameRate + "fps, " + AppConfig.formatBitrate(bitrate));
            
            // 所有摄像头使用统一的时间戳：日期_时间_摄像头位置.mp4
            String path = new File(saveDir, timestamp + "_" + key + ".mp4").getAbsolutePath();
            // 只准备 MediaRecorder，获取 Surface，使用预览的实际分辨率
            AppLog.d(TAG, "Preparing recording for " + key + " with size: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            if (!recorder.prepareRecording(path, previewSize.getWidth(), previewSize.getHeight())) {
                prepareSuccess = false;
                break;
            }
        }

        if (!prepareSuccess) {
            AppLog.e(TAG, "Failed to prepare recording");
            // 清理已准备的录制器
            for (String key : keys) {
                VideoRecorder recorder = recorders.get(key);
                if (recorder != null) {
                    recorder.release();
                }
            }
            return false;
        }

        // 第二步：将录制 Surface 添加到摄像头会话并重新创建会话
        synchronized (sessionLock) {
            sessionConfiguredCount = 0;
            expectedSessionCount = keys.size();
        }

        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            VideoRecorder recorder = recorders.get(key);
            if (camera == null || recorder == null) {
                continue;
            }
            camera.setRecordSurface(recorder.getSurface());
            camera.recreateSession();
        }

        // 第三步：设置待处理的录制启动任务，等待所有会话配置完成后执行
        pendingRecordingStart = () -> {
            AppLog.d(TAG, "Attempting to start recording...");
            boolean startSuccess = false;
            int successCount = 0;

            for (String key : keys) {
                VideoRecorder recorder = recorders.get(key);
                if (recorder != null) {
                    if (recorder.startRecording()) {
                        successCount++;
                        startSuccess = true;  // 至少有一个成功
                    } else {
                        AppLog.e(TAG, "Failed to start recording for " + key);
                    }
                }
            }

            if (startSuccess) {
                lastNotifiedSegmentIndex = -1;  // 重置分段通知计数
                isRecording = true;
                AppLog.d(TAG, successCount + " camera(s) started recording successfully");
            } else {
                AppLog.e(TAG, "Failed to start recording on all cameras");
                isRecording = false;
                // 清理所有录制器
                for (String key : keys) {
                    VideoRecorder recorder = recorders.get(key);
                    if (recorder != null) {
                        recorder.release();
                    }
                }
            }
        };

        // 设置超时机制：如果 3 秒内没有所有会话配置完成，强制启动录制
        sessionTimeoutRunnable = () -> {
            AppLog.w(TAG, "Session configuration timeout, starting recording with available cameras");
            synchronized (sessionLock) {
                final Runnable recordingTask = pendingRecordingStart;
                if (recordingTask != null) {
                    pendingRecordingStart = null;
                    recordingTask.run();
                }
                sessionConfiguredCount = 0;
                expectedSessionCount = 0;
            }
        };
        mainHandler.postDelayed(sessionTimeoutRunnable, 3000);

        return true;
    }

    /**
     * 使用软编码开始录制（L6/L7 模式）
     * 使用 OpenGL 渲染 + MediaCodec 编码 + MediaMuxer 写入
     * @param timestamp 时间戳
     * @param enabledCameras 要录制的摄像头位置集合，为 null 时录制所有摄像头
     */
    private boolean startCodecRecording(String timestamp, Set<String> enabledCameras) {
        AppLog.d(TAG, "Starting CODEC recording with timestamp: " + timestamp);

        // 检查是否使用中转写入模式
        AppConfig appConfig = new AppConfig(context);
        useRelayWrite = appConfig.shouldUseRelayWrite();
        
        // 获取录制目录（可能是临时目录或最终目录）
        File saveDir = StorageHelper.getRecordingDir(context);
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }
        
        // 如果使用中转写入，记录最终目录
        if (useRelayWrite) {
            finalSaveDir = StorageHelper.getFinalVideoDir(context);
            if (!finalSaveDir.exists()) {
                finalSaveDir.mkdirs();
            }
            AppLog.d(TAG, "Codec relay write mode: recording to " + saveDir.getAbsolutePath() + 
                    ", will transfer to " + finalSaveDir.getAbsolutePath());
        } else {
            finalSaveDir = null;
        }

        List<String> allKeys = getActiveCameraKeys();
        if (allKeys.isEmpty()) {
            AppLog.e(TAG, "No active cameras for codec recording");
            return false;
        }

        // 如果指定了摄像头列表，过滤 keys
        final List<String> keys;
        if (enabledCameras != null && !enabledCameras.isEmpty()) {
            List<String> filteredKeys = new ArrayList<>();
            for (String key : allKeys) {
                if (enabledCameras.contains(key)) {
                    filteredKeys.add(key);
                }
            }
            keys = filteredKeys;
            AppLog.d(TAG, "Filtered codec recording cameras: " + keys);
        } else {
            keys = allKeys;
        }

        if (keys.isEmpty()) {
            AppLog.e(TAG, "No enabled cameras for codec recording after filtering");
            return false;
        }

        // 获取录制配置（使用上面已创建的 appConfig）
        long segmentDurationMs = appConfig.getSegmentDurationMs();
        AppLog.d(TAG, "Codec segment duration: " + (segmentDurationMs / 1000) + " seconds (" + appConfig.getSegmentDurationMinutes() + " minutes)");
        
        // 获取帧率配置（根据帧率等级设置计算）
        int targetFrameRate = appConfig.getActualFrameRate(30);
        AppLog.d(TAG, "Codec target frame rate: " + targetFrameRate + " fps (level: " + appConfig.getFramerateLevel() + ")");

        // 清理之前的软编码录制器
        for (CodecVideoRecorder recorder : codecRecorders.values()) {
            recorder.release();
        }
        codecRecorders.clear();

        // 为每个摄像头创建软编码录制器并准备
        boolean prepareSuccess = true;
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera == null) {
                continue;
            }

            // 获取摄像头的实际预览分辨率
            Size previewSize = camera.getPreviewSize();
            if (previewSize == null) {
                AppLog.e(TAG, "Camera " + key + " preview size not available, using fallback 1280x800");
                previewSize = new Size(1280, 800);
            }
            
            // 计算码率（基于分辨率和帧率）
            int bitrate = appConfig.getActualBitrate(
                    previewSize.getWidth(), 
                    previewSize.getHeight(), 
                    targetFrameRate);

            // 创建软编码录制器
            CodecVideoRecorder codecRecorder = new CodecVideoRecorder(
                    camera.getCameraId(), 
                    previewSize.getWidth(), 
                    previewSize.getHeight()
            );

            // 设置录制参数
            codecRecorder.setSegmentDuration(segmentDurationMs);
            codecRecorder.setBitRate(bitrate);
            codecRecorder.setFrameRate(targetFrameRate);
            
            AppLog.d(TAG, "Codec recording params for " + key + ": " + 
                    previewSize.getWidth() + "x" + previewSize.getHeight() + 
                    " @ " + targetFrameRate + "fps, " + AppConfig.formatBitrate(bitrate));

            // 设置时间水印（从配置读取，使用方法开头已创建的 appConfig）
            codecRecorder.setWatermarkEnabled(appConfig.isTimestampWatermarkEnabled());

            // 设置回调
            codecRecorder.setCallback(new RecordCallback() {
                @Override
                public void onRecordStart(String cameraId) {
                    AppLog.d(TAG, "Codec recording started for camera " + cameraId);
                }

                @Override
                public void onRecordStop(String cameraId) {
                    AppLog.d(TAG, "Codec recording stopped for camera " + cameraId);
                }

                @Override
                public void onRecordError(String cameraId, String error) {
                    AppLog.e(TAG, "Codec recording error for camera " + cameraId + ": " + error);
                }

                @Override
                public void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex) {
                    AppLog.d(TAG, "Codec prepare segment switch for camera " + cameraId + " (current segment: " + currentSegmentIndex + ")");
                    // 软编码录制器使用独立的 SurfaceTexture，不需要暂停 Camera CaptureSession
                    // 但为了一致性，我们记录日志
                }

                @Override
                public void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath) {
                    AppLog.d(TAG, "Codec segment switch for camera " + cameraId + " to segment " + newSegmentIndex);
                    
                    // 如果使用中转写入，将上一个分段的文件传输到最终目录
                    if (useRelayWrite && finalSaveDir != null && newSegmentIndex > 0 && completedFilePath != null) {
                        // 传输已完成的文件（由回调提供确切路径，避免传输正在录制的新文件）
                        scheduleRelayTransfer(completedFilePath);
                    }
                    
                    // 通知分段切换回调（只通知一次，第一个触发的摄像头会通知）
                    if (segmentSwitchCallback != null && newSegmentIndex > lastNotifiedSegmentIndex) {
                        lastNotifiedSegmentIndex = newSegmentIndex;
                        segmentSwitchCallback.onSegmentSwitch(newSegmentIndex);
                    }
                }

                @Override
                public void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles) {
                    if (deletedFiles != null && !deletedFiles.isEmpty()) {
                        AppLog.w(TAG, "Corrupted files deleted for codec camera " + cameraId + ": " + deletedFiles.size() + " file(s)");
                        for (String file : deletedFiles) {
                            AppLog.d(TAG, "  Deleted: " + file);
                        }
                        // 通知 MainActivity 显示弹窗
                        if (corruptedFilesCallback != null) {
                            mainHandler.post(() -> corruptedFilesCallback.onCorruptedFilesDeleted(deletedFiles));
                        }
                    }
                }

                @Override
                public void onRecordingRebuildRequested(String cameraId, String reason) {
                    // CodecVideoRecorder 通常不会触发此回调，但为了接口完整性实现
                    AppLog.e(TAG, "Codec recording rebuild requested for camera " + cameraId + ", reason: " + reason);
                    // Codec 模式不需要回退，记录日志即可
                }
            });

            // 准备录制
            String path = new File(saveDir, timestamp + "_" + key + ".mp4").getAbsolutePath();
            AppLog.d(TAG, "Preparing codec recording for " + key + " with size: " + previewSize.getWidth() + "x" + previewSize.getHeight());

            android.graphics.SurfaceTexture surfaceTexture = codecRecorder.prepareRecording(path);
            if (surfaceTexture == null) {
                AppLog.e(TAG, "Failed to prepare codec recording for " + key);
                prepareSuccess = false;
                break;
            }

            // 将 SurfaceTexture 设置给 Camera（通过 Surface）
            android.view.Surface recordSurface = new android.view.Surface(surfaceTexture);
            camera.setRecordSurface(recordSurface);

            codecRecorders.put(key, codecRecorder);
        }

        if (!prepareSuccess) {
            AppLog.e(TAG, "Failed to prepare codec recording");
            // 清理已准备的录制器
            for (CodecVideoRecorder recorder : codecRecorders.values()) {
                recorder.release();
            }
            codecRecorders.clear();
            return false;
        }

        // 重新创建摄像头会话
        synchronized (sessionLock) {
            sessionConfiguredCount = 0;
            expectedSessionCount = keys.size();
        }

        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera != null) {
                camera.recreateSession();
            }
        }

        // 设置待处理的录制启动任务
        pendingRecordingStart = () -> {
            AppLog.d(TAG, "Attempting to start codec recording...");
            boolean startSuccess = false;
            int successCount = 0;

            for (String key : keys) {
                CodecVideoRecorder codecRecorder = codecRecorders.get(key);
                if (codecRecorder != null) {
                    if (codecRecorder.startRecording()) {
                        successCount++;
                        startSuccess = true;
                    } else {
                        AppLog.e(TAG, "Failed to start codec recording for " + key);
                    }
                }
            }

            if (startSuccess) {
                lastNotifiedSegmentIndex = -1;  // 重置分段通知计数
                isRecording = true;
                AppLog.d(TAG, successCount + " camera(s) started codec recording successfully");
            } else {
                AppLog.e(TAG, "Failed to start codec recording on all cameras");
                isRecording = false;
                // 清理所有录制器
                for (CodecVideoRecorder recorder : codecRecorders.values()) {
                    recorder.release();
                }
                codecRecorders.clear();
            }
        };

        // 设置超时机制
        sessionTimeoutRunnable = () -> {
            AppLog.w(TAG, "Session configuration timeout, starting codec recording with available cameras");
            synchronized (sessionLock) {
                final Runnable recordingTask = pendingRecordingStart;
                if (recordingTask != null) {
                    pendingRecordingStart = null;
                    recordingTask.run();
                }
                sessionConfiguredCount = 0;
                expectedSessionCount = 0;
            }
        };
        mainHandler.postDelayed(sessionTimeoutRunnable, 3000);

        return true;
    }

    /**
     * 停止录制所有摄像头
     */
    public void stopRecording() {
        stopRecording(false);
    }

    /**
     * 停止录制所有摄像头
     * @param skipRelayTransfer 是否跳过自动传输（用于远程录制，上传完成后再传输）
     */
    public void stopRecording(boolean skipRelayTransfer) {
        AppLog.d(TAG, "stopRecording called, isRecording=" + isRecording + ", useCodecRecording=" + useCodecRecording + ", skipRelayTransfer=" + skipRelayTransfer);

        // 清理待处理的录制启动任务和会话计数器（线程安全处理）
        synchronized (sessionLock) {
            if (pendingRecordingStart != null) {
                AppLog.d(TAG, "Cancelling pending recording start");
                pendingRecordingStart = null;
            }

            // 重置会话计数器
            sessionConfiguredCount = 0;
            expectedSessionCount = 0;
        }

        // 清理超时任务
        if (sessionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(sessionTimeoutRunnable);
            sessionTimeoutRunnable = null;
        }

        List<String> keys = getActiveCameraKeys();

        if (!isRecording) {
            AppLog.w(TAG, "Not recording, but cleaning up anyway");
            // 即使不在录制状态，也尝试清理录制器
            for (String key : keys) {
                VideoRecorder recorder = recorders.get(key);
                if (recorder != null) {
                    recorder.release();
                }
                CodecVideoRecorder codecRecorder = codecRecorders.get(key);
                if (codecRecorder != null) {
                    codecRecorder.release();
                }
            }
            codecRecorders.clear();
            return;
        }

        // 停止软编码录制
        if (!codecRecorders.isEmpty()) {
            AppLog.d(TAG, "Stopping codec recorders...");
            for (String key : keys) {
                CodecVideoRecorder codecRecorder = codecRecorders.get(key);
                if (codecRecorder != null && codecRecorder.isRecording()) {
                    codecRecorder.stopRecording();
                }
            }
            // 释放软编码录制器
            for (CodecVideoRecorder recorder : codecRecorders.values()) {
                recorder.release();
            }
            codecRecorders.clear();
        }

        // 停止 MediaRecorder 录制
        for (String key : keys) {
            VideoRecorder recorder = recorders.get(key);
            if (recorder != null && recorder.isRecording()) {
                recorder.stopRecording();
            }
        }

        // 清理摄像头会话
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera != null) {
                camera.clearRecordSurface();
                camera.recreateSession();
            }
        }

        // 如果使用中转写入，将临时目录中的所有文件传输到最终目录
        // 如果 skipRelayTransfer=true（远程录制），则跳过自动传输，由上传逻辑负责传输
        if (useRelayWrite && finalSaveDir != null && !skipRelayTransfer) {
            AppLog.d(TAG, "Scheduling relay transfer for remaining files...");
            // 保存引用，因为 finalSaveDir 会在延迟执行前被清空
            final File savedFinalDir = finalSaveDir;
            
            // 【重要】立即收集当前需要传输的文件列表，避免延迟执行时误传输新创建的文件
            File tempDir = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
            final File[] filesToTransfer;
            if (tempDir.exists()) {
                filesToTransfer = tempDir.listFiles((dir, name) -> name.endsWith(".mp4"));
            } else {
                filesToTransfer = null;
            }
            
            // 延迟一点执行，确保文件已经写入完成
            mainHandler.postDelayed(() -> {
                transferSpecificTempFiles(savedFinalDir, filesToTransfer);
            }, 500);
        } else if (useRelayWrite && skipRelayTransfer) {
            AppLog.d(TAG, "Skipping relay transfer (will be handled after upload)");
        }

        isRecording = false;
        useRelayWrite = false;
        finalSaveDir = null;
        
        // 清理 Watchdog 回退状态
        currentRecordingTimestamp = null;
        currentEnabledCameras = null;
        rebuildAttemptCount = 0;
        
        AppLog.d(TAG, "All cameras stopped recording");
    }

    /**
     * 处理录制重建请求（Watchdog 触发）
     * 
     * 重建策略：
     * 1. 第一次触发：尝试重建 MediaRecorder（不切换模式）
     * 2. 第二次触发：如果录制模式为"自动"，则切换到 Codec 模式
     * 3. 已在 Codec 模式或非自动模式：不再处理
     * 
     * @param cameraId 触发重建的相机ID
     * @param reason 重建原因
     */
    private void handleRecordingRebuildRequest(String cameraId, String reason) {
        rebuildAttemptCount++;
        AppLog.w(TAG, "Handling recording rebuild request from camera " + cameraId + 
                ", reason: " + reason + ", attempt: " + rebuildAttemptCount);
        
        // 如果已经在 Codec 模式，则不再处理
        if (useCodecRecording) {
            AppLog.w(TAG, "Already using Codec recording, no further fallback available");
            return;
        }
        
        // 保存当前录制参数
        final String savedTimestamp = currentRecordingTimestamp;
        final Set<String> savedEnabledCameras = currentEnabledCameras;
        
        if (savedTimestamp == null) {
            AppLog.w(TAG, "No recording timestamp saved, cannot rebuild");
            return;
        }
        
        // 停止当前录制（不清理状态）
        stopRecordingForRebuild();
        
        // 检查是否需要回退到 Codec
        if (rebuildAttemptCount >= CODEC_FALLBACK_THRESHOLD) {
            // 达到阈值，检查是否可以回退到 Codec
            AppConfig appConfig = new AppConfig(context);
            String recordingMode = appConfig.getRecordingMode();
            
            if (AppConfig.RECORDING_MODE_AUTO.equals(recordingMode)) {
                // 自动模式：切换到 Codec 录制
                AppLog.w(TAG, "Rebuild attempt " + rebuildAttemptCount + " failed, switching to Codec mode...");
                
                mainHandler.postDelayed(() -> {
                    // 生成新的时间戳（避免文件名冲突）
                    String newTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                    
                    AppLog.d(TAG, "Restarting recording with Codec mode, new timestamp: " + newTimestamp);
                    useCodecRecording = true;  // 切换到 Codec 模式
                    startCodecRecording(newTimestamp, savedEnabledCameras);
                    
                    // 通知外部发生了 Codec 回退
                    if (codecFallbackCallback != null) {
                        codecFallbackCallback.onCodecFallback();
                    }
                }, 500);
            } else {
                // 非自动模式，只能再次尝试 MediaRecorder
                AppLog.w(TAG, "Recording mode is '" + recordingMode + "' (not auto), retrying MediaRecorder...");
                
                mainHandler.postDelayed(() -> {
                    String newTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                    AppLog.d(TAG, "Retrying MediaRecorder recording, new timestamp: " + newTimestamp);
                    startMediaRecorderRecording(newTimestamp, savedEnabledCameras);
                }, 500);
            }
        } else {
            // 未达到阈值，先尝试重建 MediaRecorder
            AppLog.w(TAG, "Rebuild attempt " + rebuildAttemptCount + ", retrying MediaRecorder first...");
            
            mainHandler.postDelayed(() -> {
                // 生成新的时间戳（避免文件名冲突）
                String newTimestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
                
                AppLog.d(TAG, "Restarting recording with MediaRecorder, new timestamp: " + newTimestamp);
                startMediaRecorderRecording(newTimestamp, savedEnabledCameras);
            }, 500);
        }
    }
    
    /**
     * 为重建停止录制（不清理 Watchdog 状态）
     */
    private void stopRecordingForRebuild() {
        AppLog.d(TAG, "Stopping recording for rebuild...");
        
        List<String> keys = getActiveCameraKeys();
        
        // 停止 MediaRecorder 录制
        for (String key : keys) {
            VideoRecorder recorder = recorders.get(key);
            if (recorder != null) {
                recorder.release();  // 直接释放，不调用 stopRecording
            }
        }
        
        // 清理摄像头会话
        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera != null) {
                camera.clearRecordSurface();
                camera.recreateSession();
            }
        }
        
        isRecording = false;
    }
    
    /**
     * 调度将指定的已完成文件传输到最终目录
     * @param completedFilePath 已完成录制的文件完整路径
     */
    private void scheduleRelayTransfer(String completedFilePath) {
        if (finalSaveDir == null || completedFilePath == null) {
            return;
        }
        
        File tempFile = new File(completedFilePath);
        if (!tempFile.exists()) {
            AppLog.w(TAG, "Completed file does not exist: " + completedFilePath);
            return;
        }
        
        // 检查文件大小，避免传输空文件或损坏文件
        if (tempFile.length() < 1024) {
            AppLog.w(TAG, "Completed file too small, skipping transfer: " + completedFilePath + " (" + tempFile.length() + " bytes)");
            return;
        }
        
        File targetFile = new File(finalSaveDir, tempFile.getName());
        
        AppLog.d(TAG, "Scheduling relay transfer: " + tempFile.getName() + 
                " -> " + targetFile.getAbsolutePath());
        
        FileTransferManager transferManager = FileTransferManager.getInstance(context);
        transferManager.addTransferTask(tempFile, targetFile, 
                new FileTransferManager.TransferCallback() {
            @Override
            public void onTransferComplete(File sourceFile, File targetFile) {
                AppLog.d(TAG, "Relay transfer complete: " + targetFile.getName());
            }
            
            @Override
            public void onTransferFailed(File sourceFile, File targetFile, String error) {
                AppLog.e(TAG, "Relay transfer failed: " + sourceFile.getName() + " - " + error);
            }
        });
    }
    
    /**
     * 将临时目录中的所有视频文件传输到最终目录
     * @param targetDir 目标目录
     */
    private void transferAllTempFiles(File targetDir) {
        if (targetDir == null) {
            AppLog.w(TAG, "Target directory is null, skipping transfer");
            return;
        }
        
        File tempDir = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
        if (!tempDir.exists()) {
            AppLog.d(TAG, "Temp directory does not exist");
            return;
        }
        
        File[] files = tempDir.listFiles((dir, name) -> name.endsWith(".mp4"));
        transferSpecificTempFiles(targetDir, files);
    }
    
    /**
     * 将指定的临时视频文件传输到最终目录
     * 【重要】此方法只传输预先指定的文件列表，避免传输在调用后新创建的文件
     * @param targetDir 目标目录
     * @param files 要传输的文件列表（在调用前收集）
     */
    private void transferSpecificTempFiles(File targetDir, File[] files) {
        if (targetDir == null) {
            AppLog.w(TAG, "Target directory is null, skipping transfer");
            return;
        }
        
        if (files == null || files.length == 0) {
            AppLog.d(TAG, "No temp files to transfer");
            return;
        }
        
        AppLog.d(TAG, "Transferring " + files.length + " temp file(s) to " + targetDir.getAbsolutePath());
        
        FileTransferManager transferManager = FileTransferManager.getInstance(context);
        
        for (File tempFile : files) {
            // 检查文件是否仍然存在（可能已经被删除或移动）
            if (!tempFile.exists()) {
                AppLog.d(TAG, "Skipping non-existent file: " + tempFile.getName());
                continue;
            }
            
            // 跳过空文件（可能是正在被其他录制使用的新文件）
            if (tempFile.length() == 0) {
                AppLog.d(TAG, "Skipping empty file (may be in use): " + tempFile.getName());
                continue;
            }
            
            File targetFile = new File(targetDir, tempFile.getName());
            
            transferManager.addTransferTask(tempFile, targetFile, 
                    new FileTransferManager.TransferCallback() {
                @Override
                public void onTransferComplete(File sourceFile, File targetFile) {
                    AppLog.d(TAG, "Transfer complete: " + targetFile.getName());
                }
                
                @Override
                public void onTransferFailed(File sourceFile, File targetFile, String error) {
                    AppLog.e(TAG, "Transfer failed: " + sourceFile.getName() + " - " + error);
                }
            });
        }
    }

    /**
     * 释放所有资源
     */
    public void release() {
        stopRecording();
        closeAllCameras();

        for (VideoRecorder recorder : recorders.values()) {
            recorder.release();
        }

        for (CodecVideoRecorder codecRecorder : codecRecorders.values()) {
            codecRecorder.release();
        }

        cameras.clear();
        recorders.clear();
        codecRecorders.clear();
        AppLog.d(TAG, "All resources released");
    }

    /**
     * 是否正在录制
     */
    public boolean isRecording() {
        return isRecording;
    }

    /**
     * 拍照（所有活动的摄像头顺序拍照，避免资源耗尽）
     */
    /**
     * 拍照（所有摄像头，自动生成时间戳）
     */
    public void takePicture() {
        // 生成统一的时间戳
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        takePicture(timestamp);
    }

    /**
     * 拍照（所有摄像头，使用指定的时间戳）
     * @param timestamp 统一的时间戳，用于所有摄像头的文件命名
     */
    public void takePicture(String timestamp) {
        List<String> keys = getActiveCameraKeys();
        if (keys.isEmpty()) {
            AppLog.e(TAG, "No active cameras for taking picture");
            return;
        }

        AppLog.d(TAG, "Taking picture with " + keys.size() + " camera(s) using timestamp: " + timestamp);

        // 快速拍照，每个摄像头间隔300ms触发拍照，但保存文件时按顺序延迟1秒
        for (int i = 0; i < keys.size(); i++) {
            final String key = keys.get(i);
            final int captureDelay = i * 300;      // 拍照触发延迟：300ms（快速抓拍画面）
            final int saveDelay = i * 1000;        // 文件保存延迟：1秒（分散磁盘I/O）

            mainHandler.postDelayed(() -> {
                SingleCamera camera = cameras.get(key);
                if (camera != null && camera.isConnected()) {
                    AppLog.d(TAG, "Taking picture with camera " + key);
                    camera.takePicture(timestamp, saveDelay);  // 传递统一时间戳和保存延迟
                } else {
                    AppLog.w(TAG, "Camera " + key + " not available for taking picture");
                }
            }, captureDelay);
        }
    }

    private List<String> getActiveCameraKeys() {
        if (!activeCameraKeys.isEmpty()) {
            return new ArrayList<>(activeCameraKeys);
        }
        List<String> keys = new ArrayList<>();
        int opened = 0;
        Set<String> openedIds = new HashSet<>();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            if (opened >= maxOpenCameras) {
                break;
            }
            SingleCamera camera = entry.getValue();
            String id = camera.getCameraId();
            if (!openedIds.add(id)) {
                continue;
            }
            keys.add(entry.getKey());
            opened++;
        }
        return keys;
    }

    /**
     * 检查是否有已连接的相机
     */
    public boolean hasConnectedCameras() {
        for (SingleCamera camera : cameras.values()) {
            if (camera.isConnected()) {
                return true;
            }
        }
        return false;
    }

    /**
     * 获取已连接的相机数量
     */
    public int getConnectedCameraCount() {
        int count = 0;
        for (SingleCamera camera : cameras.values()) {
            if (camera.isConnected()) {
                count++;
            }
        }
        return count;
    }

    /**
     * 生命周期：暂停所有摄像头（App退到后台时调用）
     * 注意：如果正在录制，不应该调用此方法
     */
    public void pauseAllCamerasByLifecycle() {
        AppLog.d(TAG, "Pausing all cameras by lifecycle");
        for (SingleCamera camera : cameras.values()) {
            camera.pauseByLifecycle();
        }
    }

    /**
     * 生命周期：恢复所有摄像头（App返回前台时调用）
     */
    public void resumeAllCamerasByLifecycle() {
        AppLog.d(TAG, "Resuming all cameras by lifecycle");
        for (SingleCamera camera : cameras.values()) {
            camera.resumeByLifecycle();
        }
    }

    /**
     * 检查并修复摄像头连接（返回前台时调用）
     * 如果发现摄像头断开，自动重新打开
     * @return 需要重新打开的摄像头数量
     */
    public int checkAndRepairCameras() {
        int disconnectedCount = 0;
        
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            SingleCamera camera = entry.getValue();
            if (!camera.isConnected()) {
                disconnectedCount++;
                AppLog.d(TAG, "Camera " + entry.getKey() + " reconnecting...");
                camera.forceReopen();
            }
        }
        
        if (disconnectedCount > 0) {
            AppLog.d(TAG, disconnectedCount + " camera(s) reconnecting");
        }
        
        return disconnectedCount;
    }

    /**
     * 强制重新打开所有摄像头（用于从后台返回前台时）
     */
    public void forceReopenAllCameras() {
        AppLog.d(TAG, "Force reopening all cameras...");
        for (SingleCamera camera : cameras.values()) {
            camera.forceReopen();
        }
    }

    /**
     * 获取所有摄像头当前使用的分辨率信息
     * @return 格式化的分辨率信息字符串
     */
    public String getCameraResolutionsInfo() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
            String key = entry.getKey();
            SingleCamera camera = entry.getValue();
            String cameraId = camera.getCameraId();
            Size previewSize = camera.getPreviewSize();
            
            if (sb.length() > 0) {
                sb.append("\n");
            }
            
            sb.append(key).append(" (摄像头").append(cameraId).append("): ");
            if (previewSize != null) {
                sb.append(previewSize.getWidth()).append("×").append(previewSize.getHeight());
            } else {
                sb.append("未初始化");
            }
        }
        return sb.toString();
    }
}
