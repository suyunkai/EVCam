package com.kooo.evcam.camera;


import com.kooo.evcam.AppLog;
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
    private static final int RECORD_WIDTH = 1280;
    private static final int RECORD_HEIGHT = 800;

    private final Context context;
    private final Map<String, SingleCamera> cameras = new LinkedHashMap<>();
    private final Map<String, VideoRecorder> recorders = new LinkedHashMap<>();
    private final List<String> activeCameraKeys = new ArrayList<>();
    private int maxOpenCameras = DEFAULT_MAX_OPEN_CAMERAS;

    private boolean isRecording = false;
    private StatusCallback statusCallback;
    private PreviewSizeCallback previewSizeCallback;
    private int sessionConfiguredCount = 0;
    private int expectedSessionCount = 0;
    private Runnable pendingRecordingStart = null;
    private android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable sessionTimeoutRunnable = null;

    public interface StatusCallback {
        void onCameraStatusUpdate(String cameraId, String status);
    }

    public interface PreviewSizeCallback {
        void onPreviewSizeChosen(String cameraKey, String cameraId, Size previewSize);
    }

    public MultiCameraManager(Context context) {
        this.context = context;
    }

    public void setStatusCallback(StatusCallback callback) {
        this.statusCallback = callback;
    }

    public void setPreviewSizeCallback(PreviewSizeCallback callback) {
        this.previewSizeCallback = callback;
    }

    public void setMaxOpenCameras(int maxOpenCameras) {
        this.maxOpenCameras = Math.max(1, maxOpenCameras);
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

                // 检查是否所有会话都已配置完成
                if (expectedSessionCount > 0) {
                    sessionConfiguredCount++;
                    AppLog.d(TAG, "Session configured: " + sessionConfiguredCount + "/" + expectedSessionCount);

                    if (sessionConfiguredCount >= expectedSessionCount) {
                        // 所有会话都已配置完成，执行待处理的录制启动
                        if (pendingRecordingStart != null) {
                            AppLog.d(TAG, "All sessions configured, starting recording...");
                            // 取消超时任务
                            if (sessionTimeoutRunnable != null) {
                                mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                sessionTimeoutRunnable = null;
                            }
                            pendingRecordingStart.run();
                            pendingRecordingStart = null;
                        }
                        sessionConfiguredCount = 0;
                        expectedSessionCount = 0;
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

                // 如果在等待会话配置期间发生错误，减少期望计数
                if (expectedSessionCount > 0 && errorCode == -3) {
                    expectedSessionCount--;
                    AppLog.d(TAG, "Session configuration failed, adjusted expected count: " + sessionConfiguredCount + "/" + expectedSessionCount);

                    // 检查是否所有剩余会话都已配置完成
                    if (sessionConfiguredCount >= expectedSessionCount && expectedSessionCount > 0) {
                        if (pendingRecordingStart != null) {
                            AppLog.d(TAG, "Remaining sessions configured, starting recording...");
                            // 取消超时任务
                            if (sessionTimeoutRunnable != null) {
                                mainHandler.removeCallbacks(sessionTimeoutRunnable);
                                sessionTimeoutRunnable = null;
                            }
                            pendingRecordingStart.run();
                            pendingRecordingStart = null;
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
            public void onSegmentSwitch(String cameraId, int newSegmentIndex) {
                AppLog.d(TAG, "Segment switch for camera " + cameraId + " to segment " + newSegmentIndex);
                // 找到对应的 camera key 和 camera
                for (Map.Entry<String, SingleCamera> entry : cameras.entrySet()) {
                    if (entry.getValue().getCameraId().equals(cameraId)) {
                        String key = entry.getKey();
                        SingleCamera camera = entry.getValue();
                        VideoRecorder recorder = recorders.get(key);

                        if (camera != null && recorder != null) {
                            // 更新录制 Surface 并重新创建会话
                            camera.setRecordSurface(recorder.getSurface());
                            camera.recreateSession();
                            AppLog.d(TAG, "Recreated session for camera " + cameraId + " after segment switch");
                        }
                        break;
                    }
                }
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

        AppLog.d(TAG, "Starting recording with timestamp: " + timestamp);

        File saveDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "EVCam_Video");
        if (!saveDir.exists()) {
            saveDir.mkdirs();
        }

        List<String> keys = getActiveCameraKeys();
        if (keys.isEmpty()) {
            AppLog.e(TAG, "No active cameras for recording");
            return false;
        }

        // 第一步：准备所有 MediaRecorder（但不启动）
        boolean prepareSuccess = true;
        for (String key : keys) {
            VideoRecorder recorder = recorders.get(key);
            if (recorder == null) {
                continue;
            }
            // 所有摄像头使用统一的时间戳：日期_时间_摄像头位置.mp4
            String path = new File(saveDir, timestamp + "_" + key + ".mp4").getAbsolutePath();
            // 只准备 MediaRecorder，获取 Surface
            if (!recorder.prepareRecording(path, RECORD_WIDTH, RECORD_HEIGHT)) {
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
        sessionConfiguredCount = 0;
        expectedSessionCount = keys.size();

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
                    }
                }
            }

            if (startSuccess) {
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
            if (pendingRecordingStart != null) {
                pendingRecordingStart.run();
                pendingRecordingStart = null;
            }
            sessionConfiguredCount = 0;
            expectedSessionCount = 0;
        };
        mainHandler.postDelayed(sessionTimeoutRunnable, 3000);

        return true;
    }

    /**
     * 停止录制所有摄像头
     */
    public void stopRecording() {
        AppLog.d(TAG, "stopRecording called, isRecording=" + isRecording);

        // 清理待处理的录制启动任务
        if (pendingRecordingStart != null) {
            AppLog.d(TAG, "Cancelling pending recording start");
            pendingRecordingStart = null;
        }

        // 清理超时任务
        if (sessionTimeoutRunnable != null) {
            mainHandler.removeCallbacks(sessionTimeoutRunnable);
            sessionTimeoutRunnable = null;
        }

        // 重置会话计数器
        sessionConfiguredCount = 0;
        expectedSessionCount = 0;

        if (!isRecording) {
            AppLog.w(TAG, "Not recording, but cleaning up anyway");
            // 即使不在录制状态，也尝试清理录制器
            List<String> keys = getActiveCameraKeys();
            for (String key : keys) {
                VideoRecorder recorder = recorders.get(key);
                if (recorder != null) {
                    recorder.release();
                }
            }
            return;
        }

        List<String> keys = getActiveCameraKeys();
        for (String key : keys) {
            VideoRecorder recorder = recorders.get(key);
            if (recorder != null && recorder.isRecording()) {
                recorder.stopRecording();
            }
        }

        for (String key : keys) {
            SingleCamera camera = cameras.get(key);
            if (camera != null) {
                camera.clearRecordSurface();
                camera.recreateSession();
            }
        }

        isRecording = false;
        AppLog.d(TAG, "All cameras stopped recording");
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

        cameras.clear();
        recorders.clear();
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
}
