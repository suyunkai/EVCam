package com.kooo.evcam.camera;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/**
 * 单个摄像头管理类
 */
public class SingleCamera {
    private static final String TAG = "SingleCamera";

    private final Context context;
    private final String cameraId;
    private final TextureView textureView;
    private CameraCallback callback;
    private String cameraPosition;  // 摄像头位置（front/back/left/right）

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private Size previewSize;
    private Surface recordSurface;  // 录制Surface
    private Surface previewSurface;  // 预览Surface（缓存以避免重复创建）
    private ImageReader imageReader;  // 用于拍照的ImageReader

    private boolean shouldReconnect = false;  // 是否应该重连
    private int reconnectAttempts = 0;  // 重连尝试次数
    private static final int MAX_RECONNECT_ATTEMPTS = 30;  // 最大重连次数（30次 = 1分钟）
    private static final long RECONNECT_DELAY_MS = 2000;  // 重连延迟（毫秒）
    private Runnable reconnectRunnable;  // 重连任务

    public SingleCamera(Context context, String cameraId, TextureView textureView) {
        this.context = context;
        this.cameraId = cameraId;
        this.textureView = textureView;
        this.cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
    }

    public void setCallback(CameraCallback callback) {
        this.callback = callback;
    }

    public void setCameraPosition(String position) {
        this.cameraPosition = position;
    }

    public String getCameraId() {
        return cameraId;
    }

    /**
     * 设置录制Surface
     */
    public void setRecordSurface(Surface surface) {
        this.recordSurface = surface;
        Log.d(TAG, "Record surface set for camera " + cameraId);
    }

    /**
     * 清除录制Surface
     */
    public void clearRecordSurface() {
        this.recordSurface = null;
        Log.d(TAG, "Record surface cleared for camera " + cameraId);
    }

    public Surface getSurface() {
        if (textureView != null && textureView.isAvailable()) {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture != null) {
                // 缓存 Surface 以避免重复创建和资源泄漏
                if (previewSurface == null) {
                    previewSurface = new Surface(surfaceTexture);
                    Log.d(TAG, "Camera " + cameraId + " created new preview surface");
                }
                return previewSurface;
            }
        }
        return null;
    }

    /**
     * 选择最优分辨率（参考guardapp使用1280x800）
     */
    private Size chooseOptimalSize(Size[] sizes) {
        // 目标分辨率：1280x800 (guardapp使用的分辨率)
        final int TARGET_WIDTH = 1280;
        final int TARGET_HEIGHT = 800;

        // 首先尝试找到精确匹配 1280x800
        for (Size size : sizes) {
            if (size.getWidth() == TARGET_WIDTH && size.getHeight() == TARGET_HEIGHT) {
                Log.d(TAG, "Camera " + cameraId + " found exact 1280x800 match");
                return size;
            }
        }

        // 找到最接近 1280x800 的分辨率
        Size bestSize = null;
        int minDiff = Integer.MAX_VALUE;

        for (Size size : sizes) {
            int width = size.getWidth();
            int height = size.getHeight();

            // 计算与目标分辨率的差距
            int diff = Math.abs(TARGET_WIDTH - width) + Math.abs(TARGET_HEIGHT - height);
            if (diff < minDiff) {
                minDiff = diff;
                bestSize = size;
            }
        }

        if (bestSize == null) {
            // 如果还是没找到，使用第一个可用分辨率
            bestSize = sizes[0];
            Log.d(TAG, "Camera " + cameraId + " using first available size");
        }

        return bestSize;
    }

    /**
     * 启动后台线程
     */
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("Camera-" + cameraId);
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    /**
     * 停止后台线程
     */
    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
                backgroundThread = null;
                backgroundHandler = null;
            } catch (InterruptedException e) {
                Log.e(TAG, "Error stopping background thread", e);
            }
        }
    }

    /**
     * 打开摄像头
     */
    public void openCamera() {
        try {
            Log.d(TAG, "openCamera: Starting for camera " + cameraId);
            shouldReconnect = true;  // 启用自动重连
            reconnectAttempts = 0;  // 重置重连计数
            startBackgroundThread();

            // 获取摄像头特性
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                // 优先使用 SurfaceTexture 的输出尺寸
                Size[] sizes = map.getOutputSizes(ImageFormat.PRIVATE);
                if (sizes == null || sizes.length == 0) {
                    sizes = map.getOutputSizes(SurfaceTexture.class);
                    if (sizes != null && sizes.length > 0) {
                        Log.w(TAG, "Camera " + cameraId + " no PRIVATE sizes, fallback to SurfaceTexture sizes");
                    }
                }
                if (sizes == null || sizes.length == 0) {
                    Log.e(TAG, "Camera " + cameraId + " has no output sizes for PRIVATE/SurfaceTexture");
                    return;
                }

                // 打印所有可用分辨率
                Log.d(TAG, "Camera " + cameraId + " available sizes:");
                for (int i = 0; i < Math.min(sizes.length, 10); i++) {
                    Log.d(TAG, "  [" + i + "] " + sizes[i].getWidth() + "x" + sizes[i].getHeight());
                }

                // 选择合适的分辨率
                previewSize = chooseOptimalSize(sizes);
                Log.d(TAG, "Camera " + cameraId + " selected preview size: " + previewSize);

                // 不在这里初始化ImageReader，改为拍照时按需创建
                // 这样可以避免占用额外的缓冲区，防止超过系统限制(4个buffer)
                Log.d(TAG, "Camera " + cameraId + " ImageReader will be created on demand when taking picture");

                // 通知回调预览尺寸已确定
                if (callback != null && previewSize != null) {
                    callback.onPreviewSizeChosen(cameraId, previewSize);
                }
            } else {
                Log.e(TAG, "Camera " + cameraId + " StreamConfigurationMap is null!");
            }

            // 检查 TextureView 状态
            Log.d(TAG, "Camera " + cameraId + " TextureView available: " + textureView.isAvailable());
            if (textureView.getSurfaceTexture() != null) {
                Log.d(TAG, "Camera " + cameraId + " SurfaceTexture exists");
            } else {
                Log.e(TAG, "Camera " + cameraId + " SurfaceTexture is NULL!");
            }

            // 打开摄像头
            Log.d(TAG, "Camera " + cameraId + " calling openCamera...");
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to open camera " + cameraId, e);
            if (callback != null) {
                callback.onCameraError(cameraId, -1);
            }
            // 尝试重连
            if (shouldReconnect) {
                scheduleReconnect();
            }
        } catch (SecurityException e) {
            Log.e(TAG, "No camera permission", e);
            if (callback != null) {
                callback.onCameraError(cameraId, -2);
            }
        }
    }

    /**
     * 调度重连任务
     */
    private void scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            Log.e(TAG, "Camera " + cameraId + " max reconnect attempts reached (" + MAX_RECONNECT_ATTEMPTS + "), giving up");
            shouldReconnect = false;
            return;
        }

        reconnectAttempts++;
        Log.d(TAG, "Camera " + cameraId + " scheduling reconnect attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + " in " + (RECONNECT_DELAY_MS / 1000) + " seconds");

        // 取消之前的重连任务
        if (reconnectRunnable != null && backgroundHandler != null) {
            backgroundHandler.removeCallbacks(reconnectRunnable);
        }

        // 创建新的重连任务
        reconnectRunnable = () -> {
            Log.d(TAG, "Camera " + cameraId + " attempting to reconnect (attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")...");
            try {
                // 确保之前的资源已清理（捕获并忽略异常）
                try {
                    if (captureSession != null) {
                        captureSession.close();
                        captureSession = null;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Camera " + cameraId + " exception while closing session during reconnect (expected): " + e.getMessage());
                }

                try {
                    if (cameraDevice != null) {
                        cameraDevice.close();
                        cameraDevice = null;
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Camera " + cameraId + " exception while closing device during reconnect (expected): " + e.getMessage());
                }

                // 重新打开摄像头
                cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to reconnect camera " + cameraId + ": " + e.getMessage());
                // 继续尝试重连
                if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    scheduleReconnect();
                }
            } catch (SecurityException e) {
                Log.e(TAG, "No camera permission during reconnect", e);
                shouldReconnect = false;
            }
        };

        // 延迟执行重连
        if (backgroundHandler != null) {
            backgroundHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
        }
    }

    /**
     * 摄像头状态回调
     */
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            reconnectAttempts = 0;  // 重置重连计数
            Log.d(TAG, "Camera " + cameraId + " opened");
            if (callback != null) {
                callback.onCameraOpened(cameraId);
            }
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            try {
                camera.close();
            } catch (Exception e) {
                Log.w(TAG, "Camera " + cameraId + " exception while closing on disconnect (expected): " + e.getMessage());
            }
            cameraDevice = null;
            Log.w(TAG, "Camera " + cameraId + " DISCONNECTED - will attempt to reconnect...");
            if (callback != null) {
                callback.onCameraError(cameraId, -4); // 自定义错误码：断开连接
            }

            // 启动自动重连
            if (shouldReconnect) {
                scheduleReconnect();
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            try {
                camera.close();
            } catch (Exception e) {
                Log.w(TAG, "Camera " + cameraId + " exception while closing on error (expected): " + e.getMessage());
            }
            cameraDevice = null;
            String errorMsg = "UNKNOWN";
            boolean shouldRetry = false;

            switch (error) {
                case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
                    errorMsg = "ERROR_CAMERA_IN_USE (1) - Camera is being used by another app";
                    shouldRetry = true;  // 摄像头被占用，可以重试
                    break;
                case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                    errorMsg = "ERROR_MAX_CAMERAS_IN_USE (2) - Too many cameras open";
                    shouldRetry = true;  // 摄像头数量超限，可以重试
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                    errorMsg = "ERROR_CAMERA_DISABLED (3) - Camera disabled by policy";
                    shouldRetry = false;  // 摄像头被禁用，不应重试
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                    errorMsg = "ERROR_CAMERA_DEVICE (4) - Fatal device error!";
                    shouldRetry = false;  // 设备错误，不应重试
                    break;
                case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                    errorMsg = "ERROR_CAMERA_SERVICE (5) - Camera service error";
                    shouldRetry = true;  // 服务错误，可以重试
                    break;
            }

            Log.e(TAG, "Camera " + cameraId + " error: " + errorMsg);
            if (callback != null) {
                callback.onCameraError(cameraId, error);
            }

            // 如果应该重试且允许重连，则启动自动重连
            if (shouldRetry && shouldReconnect) {
                scheduleReconnect();
            }
        }
    };

    /**
     * 创建预览会话
     */
    private void createCameraPreviewSession() {
        try {
            Log.d(TAG, "createCameraPreviewSession: Starting for camera " + cameraId);

            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) {
                Log.e(TAG, "Surface not available for camera " + cameraId);
                Log.e(TAG, "TextureView available: " + textureView.isAvailable());
                Log.e(TAG, "SurfaceTexture: " + textureView.getSurfaceTexture());
                return;
            }


            // 设置预览尺寸为最小值以减少资源消耗
            if (previewSize != null) {
                // 使用最小的预览尺寸 (例如 320x240)
                surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                Log.d(TAG, "Camera " + cameraId + " buffer size set to: " + previewSize);
            } else {
                Log.e(TAG, "Camera " + cameraId + " Cannot set buffer size - previewSize: " + previewSize + ", SurfaceTexture: " + surfaceTexture);
            }

            // 创建预览请求
            Surface surface = new Surface(surfaceTexture);
            Log.d(TAG, "Camera " + cameraId + " Surface obtained: " + surface);

            Log.d(TAG, "Camera " + cameraId + " Creating capture request...");
            int template = (recordSurface != null) ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            final CaptureRequest.Builder previewRequestBuilder = cameraDevice.createCaptureRequest(template);
            previewRequestBuilder.addTarget(surface);
            Log.d(TAG, "Camera " + cameraId + " Added preview surface to request");

            // 准备所有输出Surface
            java.util.List<Surface> surfaces = new java.util.ArrayList<>();
            surfaces.add(surface);

            // 如果有录制Surface，也添加到输出目标
            if (recordSurface != null) {
                surfaces.add(recordSurface);
                previewRequestBuilder.addTarget(recordSurface);
                Log.d(TAG, "Added record surface to camera " + cameraId);
            }

            // 不再在预览会话中添加ImageReader Surface
            // ImageReader将在拍照时按需创建，避免占用额外缓冲区

            Log.d(TAG, "Camera " + cameraId + " Total surfaces: " + surfaces.size());

            // 创建会话
            Log.d(TAG, "Camera " + cameraId + " Creating capture session...");
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    Log.d(TAG, "Camera " + cameraId + " Session configured!");
                    if (cameraDevice == null) {
                        Log.e(TAG, "Camera " + cameraId + " cameraDevice is null in onConfigured");
                        return;
                    }

                    captureSession = session;
                    try {
                        // 开始预览
                        Log.d(TAG, "Camera " + cameraId + " Setting repeating request...");
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, backgroundHandler);

                        Log.d(TAG, "Camera " + cameraId + " preview started successfully!");
                        if (callback != null) {
                            callback.onCameraConfigured(cameraId);
                        }
                    } catch (CameraAccessException e) {
                        Log.e(TAG, "Failed to start preview for camera " + cameraId, e);
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    Log.e(TAG, "Failed to configure camera " + cameraId + " session!");
                    if (callback != null) {
                        callback.onCameraError(cameraId, -3);
                    }
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            Log.e(TAG, "Failed to create preview session for camera " + cameraId, e);
            Log.e(TAG, "Exception details: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception creating session for camera " + cameraId, e);
            Log.e(TAG, "Exception details: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 重新创建会话（用于开始/停止录制时）
     */
    public void recreateSession() {
        if (cameraDevice != null) {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            // 清除旧的预览 Surface 缓存，强制重新创建
            if (previewSurface != null) {
                try {
                    previewSurface.release();
                } catch (Exception e) {
                    Log.w(TAG, "Camera " + cameraId + " exception while releasing old preview surface: " + e.getMessage());
                }
                previewSurface = null;
            }
            createCameraPreviewSession();
        }
    }

    /**
     * 拍照（直接从TextureView截取画面，避免使用ImageReader）
     */
    public void takePicture() {
        if (textureView == null || !textureView.isAvailable()) {
            Log.e(TAG, "Camera " + cameraId + " TextureView not available");
            return;
        }

        if (previewSize == null) {
            Log.e(TAG, "Camera " + cameraId + " preview size not available");
            return;
        }

        // 在后台线程中处理截图和保存
        if (backgroundHandler != null) {
            backgroundHandler.post(() -> {
                try {
                    // 从TextureView获取Bitmap，使用原始预览分辨率避免变形
                    // 这样可以获取未经变换的原始画面
                    android.graphics.Bitmap bitmap = textureView.getBitmap(
                            previewSize.getWidth(),
                            previewSize.getHeight()
                    );
                    if (bitmap != null) {
                        saveBitmapAsJPEG(bitmap);
                        bitmap.recycle();
                        Log.d(TAG, "Camera " + cameraId + " picture captured from TextureView (" +
                              bitmap.getWidth() + "x" + bitmap.getHeight() + ")");
                    } else {
                        Log.e(TAG, "Camera " + cameraId + " failed to get bitmap from TextureView");
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Camera " + cameraId + " error capturing picture", e);
                }
            });
        }
    }

    /**
     * 将Bitmap保存为JPEG文件
     */
    private void saveBitmapAsJPEG(android.graphics.Bitmap bitmap) {
        File photoDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM), "EVCam_Photo");
        if (!photoDir.exists()) {
            photoDir.mkdirs();
        }

        // 使用与视频相同的命名格式：yyyyMMdd_HHmmss_摄像头位置.jpg
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String position = (cameraPosition != null) ? cameraPosition : cameraId;
        File photoFile = new File(photoDir, timestamp + "_" + position + ".jpg");

        FileOutputStream output = null;
        try {
            output = new FileOutputStream(photoFile);
            bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output);
            output.flush();
            Log.d(TAG, "Photo saved: " + photoFile.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to save photo", e);
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    Log.e(TAG, "Failed to close output stream", e);
                }
            }
        }
    }

    /**
     * 关闭摄像头
     */
    public void closeCamera() {
        shouldReconnect = false;  // 禁用自动重连
        reconnectAttempts = 0;  // 重置重连计数

        // 取消待处理的重连任务
        if (reconnectRunnable != null && backgroundHandler != null) {
            backgroundHandler.removeCallbacks(reconnectRunnable);
            reconnectRunnable = null;
        }

        // 关闭会话（捕获异常）
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception e) {
                Log.w(TAG, "Camera " + cameraId + " exception while closing session (expected): " + e.getMessage());
            }
            captureSession = null;
        }

        // 关闭设备（捕获异常）
        if (cameraDevice != null) {
            try {
                cameraDevice.close();
            } catch (Exception e) {
                Log.w(TAG, "Camera " + cameraId + " exception while closing device (expected): " + e.getMessage());
            }
            cameraDevice = null;
        }

        // 释放预览 Surface
        if (previewSurface != null) {
            try {
                previewSurface.release();
                Log.d(TAG, "Camera " + cameraId + " released preview surface");
            } catch (Exception e) {
                Log.w(TAG, "Camera " + cameraId + " exception while releasing preview surface: " + e.getMessage());
            }
            previewSurface = null;
        }

        // 释放ImageReader
        if (imageReader != null) {
            try {
                imageReader.close();
                Log.d(TAG, "Camera " + cameraId + " released image reader");
            } catch (Exception e) {
                Log.w(TAG, "Camera " + cameraId + " exception while closing image reader: " + e.getMessage());
            }
            imageReader = null;
        }

        stopBackgroundThread();

        Log.d(TAG, "Camera " + cameraId + " closed");
        if (callback != null) {
            callback.onCameraClosed(cameraId);
        }
    }

    /**
     * 手动触发重连（重置重连计数）
     */
    public void reconnect() {
        Log.d(TAG, "Camera " + cameraId + " manual reconnect requested");
        reconnectAttempts = 0;
        shouldReconnect = true;
        closeCamera();
        openCamera();
    }

    /**
     * 检查摄像头是否已连接
     */
    public boolean isConnected() {
        return cameraDevice != null;
    }
}
