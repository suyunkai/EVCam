package com.kooo.evcam.camera;


import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.StorageHelper;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
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
    private int customRotation = 0;  // 自定义旋转角度（仅用于自定义车型）

    private CameraManager cameraManager;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private Size previewSize;
    private Surface recordSurface;  // 录制Surface
    private Surface previewSurface;  // 预览Surface（缓存以避免重复创建）
    private ImageReader imageReader;  // 用于拍照的ImageReader
    private boolean singleOutputMode = false;  // 单一输出模式（用于不支持多路输出的车机平台）
    
    // 亮度/降噪调节相关
    private CaptureRequest.Builder currentRequestBuilder;  // 当前的请求构建器（用于实时更新参数）
    private CameraCharacteristics cameraCharacteristics;  // 摄像头特性（缓存）
    private boolean imageAdjustEnabled = false;  // 是否启用亮度/降噪调节
    
    // 当前相机实际使用的参数（从 CaptureResult 读取）
    private int actualExposureCompensation = 0;
    private int actualAwbMode = CameraMetadata.CONTROL_AWB_MODE_AUTO;
    private int actualEdgeMode = CameraMetadata.EDGE_MODE_OFF;
    private int actualNoiseReductionMode = CameraMetadata.NOISE_REDUCTION_MODE_OFF;
    private int actualEffectMode = CameraMetadata.CONTROL_EFFECT_MODE_OFF;
    private int actualTonemapMode = CameraMetadata.TONEMAP_MODE_FAST;
    private boolean hasReadActualParams = false;  // 是否已读取过实际参数

    // 调试：帧捕获监控
    private long frameCount = 0;  // 总帧数
    private long lastFrameLogTime = 0;  // 上次输出帧计数的时间
    private static final long FRAME_LOG_INTERVAL_MS = 5000;  // 每5秒输出一次帧计数

    private boolean shouldReconnect = false;  // 是否应该重连
    private int reconnectAttempts = 0;  // 重连尝试次数
    private static final int MAX_RECONNECT_ATTEMPTS = 90;  // 最大重连次数（90次 × 2秒 = 3分钟）
    private static final long RECONNECT_DELAY_MS = 2000;  // 重连延迟（毫秒）
    private Runnable reconnectRunnable;  // 重连任务
    private boolean isPausedByLifecycle = false;  // 是否因生命周期暂停（用于区分主动关闭和系统剥夺）
    private boolean isReconnecting = false;  // 是否正在重连中（防止多个重连任务同时运行）
    private final Object reconnectLock = new Object();  // 重连锁
    private boolean isPrimaryInstance = true;  // 是否是主实例（用于多实例共享同一个cameraId时，只有主实例负责重连）

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

        // 如果是后摄像头，应用左右镜像变换
        if ("back".equals(position) && textureView != null) {
            applyMirrorTransform();
        }
    }

    /**
     * 设置自定义旋转角度（仅用于自定义车型）
     * @param rotation 旋转角度（0/90/180/270）
     */
    public void setCustomRotation(int rotation) {
        this.customRotation = rotation;
        AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") custom rotation set to " + rotation + "°");

        // 如果TextureView已经可用，立即应用旋转
        if (textureView != null && textureView.isAvailable()) {
            applyCustomRotation();
        }
    }

    /**
     * 设置是否为主实例（用于多实例共享同一个cameraId时）
     * 只有主实例负责打开摄像头和重连，从属实例只负责显示
     */
    public void setPrimaryInstance(boolean isPrimary) {
        this.isPrimaryInstance = isPrimary;
        if (!isPrimary) {
            // 从属实例不需要重连
            synchronized (reconnectLock) {
                shouldReconnect = false;
            }
        }
        AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") set as " + (isPrimary ? "PRIMARY" : "SECONDARY") + " instance");
    }

    /**
     * 检查是否是主实例
     */
    public boolean isPrimaryInstance() {
        return isPrimaryInstance;
    }

    /**
     * 应用左右镜像变换到TextureView
     */
    private void applyMirrorTransform() {
        if (textureView == null) {
            return;
        }

        // 在主线程中执行UI操作
        textureView.post(() -> {
            android.graphics.Matrix matrix = new android.graphics.Matrix();

            // 获取TextureView的中心点
            float centerX = textureView.getWidth() / 2f;
            float centerY = textureView.getHeight() / 2f;

            // 应用水平镜像：scaleX = -1
            matrix.setScale(-1f, 1f, centerX, centerY);

            textureView.setTransform(matrix);
            AppLog.d(TAG, "Camera " + cameraId + " (back) applied mirror transform");
        });
    }

    /**
     * 应用自定义旋转角度（仅用于自定义车型）
     */
    private void applyCustomRotation() {
        if (textureView == null || customRotation == 0) {
            return;
        }

        // 在主线程中执行UI操作
        textureView.post(() -> {
            android.graphics.Matrix matrix = new android.graphics.Matrix();

            // 获取TextureView的中心点
            float centerX = textureView.getWidth() / 2f;
            float centerY = textureView.getHeight() / 2f;

            // 应用旋转
            matrix.setRotate(customRotation, centerX, centerY);

            // 如果是后摄像头，还需要应用镜像
            if ("back".equals(cameraPosition)) {
                matrix.postScale(-1f, 1f, centerX, centerY);
            }

            textureView.setTransform(matrix);
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") applied custom rotation: " + customRotation + "°");
        });
    }

    public String getCameraId() {
        return cameraId;
    }

    /**
     * 获取预览分辨率
     */
    public Size getPreviewSize() {
        return previewSize;
    }

    /**
     * 设置单一输出模式（用于不支持多路输出的车机平台，如 L6/L7）
     * 在此模式下，录制时只使用 MediaRecorder Surface，不使用 TextureView Surface
     * 这会导致录制期间预览冻结，但能确保录制正常工作
     */
    public void setSingleOutputMode(boolean enabled) {
        this.singleOutputMode = enabled;
        AppLog.d(TAG, "Camera " + cameraId + " single output mode: " + (enabled ? "ENABLED" : "DISABLED"));
    }

    /**
     * 检查是否启用了单一输出模式
     */
    public boolean isSingleOutputMode() {
        return singleOutputMode;
    }

    // 当前录制模式（用于调试模式区分）
    private boolean isCodecRecording = false;

    /**
     * 设置录制Surface
     */
    public void setRecordSurface(Surface surface) {
        this.recordSurface = surface;
        if (surface != null) {
            AppLog.d(TAG, "Record surface set for camera " + cameraId + ": " + surface + ", isValid=" + surface.isValid());
        } else {
            AppLog.w(TAG, "Record surface set to NULL for camera " + cameraId);
        }
    }

    /**
     * 设置录制Surface（带模式标识）
     * @param surface 录制Surface
     * @param isCodec true 表示 Codec 模式，false 表示 MediaRecorder 模式
     */
    public void setRecordSurface(Surface surface, boolean isCodec) {
        this.recordSurface = surface;
        this.isCodecRecording = isCodec;
        if (surface != null) {
            AppLog.d(TAG, "Record surface set for camera " + cameraId + ": " + surface + 
                    ", isValid=" + surface.isValid() + ", mode=" + (isCodec ? "Codec" : "MediaRecorder"));
        } else {
            AppLog.w(TAG, "Record surface set to NULL for camera " + cameraId);
        }
    }

    /**
     * 清除录制Surface
     */
    public void clearRecordSurface() {
        this.recordSurface = null;
        AppLog.d(TAG, "Record surface cleared for camera " + cameraId);
    }

    /**
     * 暂停向录制 Surface 发送帧（旧方法，保留兼容性）
     * 注意：此方法会停止整个预览，导致画面卡顿，建议使用 switchToPreviewOnlyMode() 代替
     */
    public void pauseRecordSurface() {
        if (captureSession != null) {
            try {
                // 停止向所有 Surface（包括 recordSurface）发送帧
                captureSession.stopRepeating();
                AppLog.d(TAG, "Camera " + cameraId + " paused recording surface (stopped repeating request)");
            } catch (CameraAccessException e) {
                AppLog.e(TAG, "Camera " + cameraId + " failed to pause recording surface", e);
            } catch (IllegalStateException e) {
                // Session 可能已经关闭
                AppLog.w(TAG, "Camera " + cameraId + " session already closed when trying to pause");
            }
        } else {
            AppLog.w(TAG, "Camera " + cameraId + " captureSession is null, cannot pause recording surface");
        }
    }

    /**
     * 切换到仅预览模式（优化的分段切换方法）
     * 
     * 与 pauseRecordSurface() 不同，此方法不会停止预览，而是：
     * 1. 创建一个只包含预览 Surface 的新请求
     * 2. 继续向预览 Surface 发送帧（预览不卡顿）
     * 3. 停止向录制 Surface 发送帧（安全停止 MediaRecorder）
     * 
     * @return true 如果成功切换，false 如果失败（将回退到 pauseRecordSurface）
     */
    public boolean switchToPreviewOnlyMode() {
        if (captureSession == null || cameraDevice == null || previewSurface == null) {
            AppLog.w(TAG, "Camera " + cameraId + " cannot switch to preview-only mode: session/device/surface not ready");
            // 回退到旧方法
            pauseRecordSurface();
            return false;
        }

        try {
            // 创建一个只包含预览 Surface 的请求
            CaptureRequest.Builder previewOnlyBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewOnlyBuilder.addTarget(previewSurface);
            
            // 应用当前的图像调节参数（如果启用）
            if (imageAdjustEnabled && currentRequestBuilder != null) {
                // 复制关键参数
                try {
                    Integer exposure = currentRequestBuilder.get(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION);
                    if (exposure != null) {
                        previewOnlyBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, exposure);
                    }
                    Integer awbMode = currentRequestBuilder.get(CaptureRequest.CONTROL_AWB_MODE);
                    if (awbMode != null) {
                        previewOnlyBuilder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
                    }
                } catch (Exception e) {
                    // 忽略参数复制错误
                }
            }
            
            // 替换当前的重复请求（预览继续，但不再向录制 Surface 发送帧）
            captureSession.setRepeatingRequest(previewOnlyBuilder.build(), null, backgroundHandler);
            AppLog.d(TAG, "Camera " + cameraId + " switched to preview-only mode (preview continues, recording paused)");
            return true;
            
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to switch to preview-only mode", e);
            // 回退到旧方法
            pauseRecordSurface();
            return false;
        } catch (IllegalStateException e) {
            AppLog.w(TAG, "Camera " + cameraId + " session closed when switching to preview-only mode");
            return false;
        } catch (IllegalArgumentException e) {
            // 某些设备可能不支持动态切换请求目标
            AppLog.w(TAG, "Camera " + cameraId + " device may not support dynamic request change: " + e.getMessage());
            pauseRecordSurface();
            return false;
        }
    }

    public Surface getSurface() {
        if (textureView != null && textureView.isAvailable()) {
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture != null) {
                // 缓存 Surface 以避免重复创建和资源泄漏
                if (previewSurface == null) {
                    previewSurface = new Surface(surfaceTexture);
                    AppLog.d(TAG, "Camera " + cameraId + " created new preview surface");
                }
                return previewSurface;
            }
        }
        return null;
    }

    /**
     * 选择最优分辨率
     * 根据用户配置的目标分辨率进行匹配：
     * - 默认：优先1280x800，否则最接近的
     * - 指定分辨率：优先精确匹配，否则最接近的
     */
    private Size chooseOptimalSize(Size[] sizes) {
        // 从配置获取目标分辨率
        AppConfig appConfig = new AppConfig(context);
        String targetResolution = appConfig.getTargetResolution();
        
        int targetWidth;
        int targetHeight;
        
        if (AppConfig.RESOLUTION_DEFAULT.equals(targetResolution)) {
            // 默认：1280x800 (guardapp使用的分辨率)
            targetWidth = 1280;
            targetHeight = 800;
            AppLog.d(TAG, "Camera " + cameraId + " using default target resolution: " + targetWidth + "x" + targetHeight);
        } else {
            // 用户指定的分辨率
            int[] parsed = AppConfig.parseResolution(targetResolution);
            if (parsed != null) {
                targetWidth = parsed[0];
                targetHeight = parsed[1];
                AppLog.d(TAG, "Camera " + cameraId + " using user-specified target resolution: " + targetWidth + "x" + targetHeight);
            } else {
                // 解析失败，回退到默认
                targetWidth = 1280;
                targetHeight = 800;
                AppLog.w(TAG, "Camera " + cameraId + " failed to parse resolution '" + targetResolution + "', using default 1280x800");
            }
        }

        // 首先尝试找到精确匹配
        for (Size size : sizes) {
            if (size.getWidth() == targetWidth && size.getHeight() == targetHeight) {
                AppLog.d(TAG, "Camera " + cameraId + " found exact match: " + targetWidth + "x" + targetHeight);
                return size;
            }
        }

        // 找到最接近目标分辨率的
        Size bestSize = null;
        int minDiff = Integer.MAX_VALUE;

        for (Size size : sizes) {
            int width = size.getWidth();
            int height = size.getHeight();

            // 计算与目标分辨率的差距
            int diff = Math.abs(targetWidth - width) + Math.abs(targetHeight - height);
            if (diff < minDiff) {
                minDiff = diff;
                bestSize = size;
            }
        }

        if (bestSize == null) {
            // 如果还是没找到，使用第一个可用分辨率
            bestSize = sizes[0];
            AppLog.d(TAG, "Camera " + cameraId + " using first available size: " + bestSize.getWidth() + "x" + bestSize.getHeight());
        } else {
            AppLog.d(TAG, "Camera " + cameraId + " selected closest match: " + bestSize.getWidth() + "x" + bestSize.getHeight() + 
                    " (target was " + targetWidth + "x" + targetHeight + ")");
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
     * 添加超时保护和完善的清理逻辑
     */
    private static final long THREAD_JOIN_TIMEOUT_MS = 2000;  // 2秒超时
    
    private void stopBackgroundThread() {
        if (backgroundThread == null) {
            return;
        }
        
        backgroundThread.quitSafely();
        
        try {
            // 使用超时的 join，避免无限阻塞
            backgroundThread.join(THREAD_JOIN_TIMEOUT_MS);
            
            // 检查线程是否仍在运行
            if (backgroundThread.isAlive()) {
                AppLog.w(TAG, "Camera " + cameraId + " background thread did not terminate in time, interrupting");
                backgroundThread.interrupt();
                // 再给一次机会（短超时）
                backgroundThread.join(500);
                
                if (backgroundThread.isAlive()) {
                    AppLog.e(TAG, "Camera " + cameraId + " background thread still alive after interrupt");
                }
            }
        } catch (InterruptedException e) {
            AppLog.e(TAG, "Camera " + cameraId + " interrupted while stopping background thread", e);
            // 恢复中断标志，让上层知道发生了中断
            Thread.currentThread().interrupt();
        } finally {
            // 无论成功与否都清理引用，避免内存泄漏
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    /**
     * 打开摄像头
     */
    public void openCamera() {
        // 如果不是主实例，不执行打开操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping openCamera");
            return;
        }
        
        synchronized (reconnectLock) {
            // 安全措施：清理可能残留的录制 Surface 引用（防止 Surface abandoned 错误）
            // 放在同步块内，避免与 setRecordSurface() 的竞态条件
            if (recordSurface != null) {
                AppLog.w(TAG, "Camera " + cameraId + " found stale recordSurface on open, clearing it");
                recordSurface = null;
            }
            
            // 如果已经在重连中，忽略新的打开请求
            if (isReconnecting) {
                AppLog.d(TAG, "Camera " + cameraId + " already reconnecting, ignoring openCamera call");
                return;
            }
            
            AppLog.d(TAG, "openCamera: Starting for camera " + cameraId + " (PRIMARY instance)");
            shouldReconnect = true;  // 启用自动重连
            reconnectAttempts = 0;  // 重置重连计数
        }
        
        try {
            startBackgroundThread();

            // 验证摄像头ID是否存在
            String[] availableCameraIds = cameraManager.getCameraIdList();
            boolean cameraExists = false;
            for (String id : availableCameraIds) {
                if (id.equals(cameraId)) {
                    cameraExists = true;
                    break;
                }
            }

            if (!cameraExists) {
                AppLog.e(TAG, "Camera ID " + cameraId + " does not exist on this device. Available IDs: " +
                         java.util.Arrays.toString(availableCameraIds));
                if (callback != null) {
                    callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
                }
                return;
            }

            // 获取摄像头特性（验证摄像头是否真正可用）
            CameraCharacteristics characteristics;
            try {
                characteristics = cameraManager.getCameraCharacteristics(cameraId);
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " failed to get characteristics - camera may be virtual/invalid", e);
                if (callback != null) {
                    callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
                }
                synchronized (reconnectLock) {
                    shouldReconnect = false;  // 无效摄像头不应重连
                }
                return;
            }
            
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (map != null) {
                // 优先使用 SurfaceTexture 的输出尺寸
                Size[] sizes = map.getOutputSizes(ImageFormat.PRIVATE);
                if (sizes == null || sizes.length == 0) {
                    sizes = map.getOutputSizes(SurfaceTexture.class);
                    if (sizes != null && sizes.length > 0) {
                        AppLog.w(TAG, "Camera " + cameraId + " no PRIVATE sizes, fallback to SurfaceTexture sizes");
                    }
                }
                if (sizes == null || sizes.length == 0) {
                    AppLog.e(TAG, "Camera " + cameraId + " has no output sizes for PRIVATE/SurfaceTexture - camera may be virtual/invalid");
                    if (callback != null) {
                        callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
                    }
                    synchronized (reconnectLock) {
                        shouldReconnect = false;  // 无效摄像头不应重连
                    }
                    return;
                }

                // 打印所有可用分辨率
                AppLog.d(TAG, "Camera " + cameraId + " available sizes:");
                for (int i = 0; i < Math.min(sizes.length, 10); i++) {
                    AppLog.d(TAG, "  [" + i + "] " + sizes[i].getWidth() + "x" + sizes[i].getHeight());
                }

                // 选择合适的分辨率
                previewSize = chooseOptimalSize(sizes);
                AppLog.d(TAG, "Camera " + cameraId + " selected preview size: " + previewSize);

                // 不在这里初始化ImageReader，改为拍照时按需创建
                // 这样可以避免占用额外的缓冲区，防止超过系统限制(4个buffer)
                AppLog.d(TAG, "Camera " + cameraId + " ImageReader will be created on demand when taking picture");

                // 通知回调预览尺寸已确定
                if (callback != null && previewSize != null) {
                    callback.onPreviewSizeChosen(cameraId, previewSize);
                }
            } else {
                AppLog.e(TAG, "Camera " + cameraId + " StreamConfigurationMap is null - camera may be virtual/invalid!");
                if (callback != null) {
                    callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
                }
                synchronized (reconnectLock) {
                    shouldReconnect = false;  // 无效摄像头不应重连
                }
                return;
            }

            // 检查 TextureView 状态
            AppLog.d(TAG, "Camera " + cameraId + " TextureView available: " + textureView.isAvailable());
            if (textureView.getSurfaceTexture() != null) {
                AppLog.d(TAG, "Camera " + cameraId + " SurfaceTexture exists");
            } else {
                AppLog.e(TAG, "Camera " + cameraId + " SurfaceTexture is NULL!");
            }

            // 打开摄像头
            AppLog.d(TAG, "Camera " + cameraId + " calling openCamera...");
            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);

        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Failed to open camera " + cameraId, e);
            if (callback != null) {
                callback.onCameraError(cameraId, -1);
            }
            // 尝试重连（检查是否已经在重连中）
            synchronized (reconnectLock) {
                if (shouldReconnect && !isReconnecting) {
                    scheduleReconnect();
                }
            }
        } catch (SecurityException e) {
            AppLog.e(TAG, "No camera permission", e);
            if (callback != null) {
                callback.onCameraError(cameraId, -2);
            }
        } catch (IllegalArgumentException e) {
            // 某些设备在打开无效摄像头时会抛出 IllegalArgumentException
            AppLog.e(TAG, "Camera " + cameraId + " invalid argument - camera may be virtual/invalid", e);
            if (callback != null) {
                callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
            }
            synchronized (reconnectLock) {
                shouldReconnect = false;  // 无效摄像头不应重连
            }
        } catch (RuntimeException e) {
            // 捕获所有其他运行时异常，防止应用崩溃
            AppLog.e(TAG, "Camera " + cameraId + " runtime exception - camera may be virtual/invalid", e);
            if (callback != null) {
                callback.onCameraError(cameraId, CameraDevice.StateCallback.ERROR_CAMERA_DEVICE);
            }
            synchronized (reconnectLock) {
                shouldReconnect = false;  // 异常情况下不应重连
            }
        }
    }

    /**
     * 调度重连任务
     */
    private void scheduleReconnect() {
        // 如果不是主实例，不执行重连
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping reconnect");
            return;
        }
        
        synchronized (reconnectLock) {
            // 检查是否允许重连
            if (!shouldReconnect) {
                AppLog.d(TAG, "Camera " + cameraId + " reconnect disabled, skipping");
                return;
            }
            
            // 如果已经在重连中，忽略新的重连请求
            if (isReconnecting) {
                AppLog.d(TAG, "Camera " + cameraId + " already reconnecting, skipping new request");
                return;
            }

            if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                AppLog.e(TAG, "Camera " + cameraId + " max reconnect attempts reached (" + MAX_RECONNECT_ATTEMPTS + "), giving up");
                shouldReconnect = false;
                isReconnecting = false;
                return;
            }

            reconnectAttempts++;
            isReconnecting = true;
            AppLog.d(TAG, "Camera " + cameraId + " scheduling reconnect attempt " + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + " in " + (RECONNECT_DELAY_MS / 1000) + " seconds");

            // 取消之前的重连任务
            if (reconnectRunnable != null && backgroundHandler != null) {
                backgroundHandler.removeCallbacks(reconnectRunnable);
            }

            // 创建新的重连任务
            reconnectRunnable = () -> {
                synchronized (reconnectLock) {
                    // 仅在首次或最后一次重连时记录日志
                    if (reconnectAttempts == 1 || reconnectAttempts == MAX_RECONNECT_ATTEMPTS) {
                        AppLog.d(TAG, "Camera " + cameraId + " reconnecting (" + reconnectAttempts + "/" + MAX_RECONNECT_ATTEMPTS + ")");
                    }
                    try {
                        // 确保之前的资源已清理（捕获并忽略异常）
                        try {
                            if (captureSession != null) {
                                captureSession.close();
                                captureSession = null;
                            }
                        } catch (Exception e) {
                            // 忽略关闭session时的异常（车机HAL可能不支持某些操作）
                            AppLog.d(TAG, "Camera " + cameraId + " ignored exception while closing session: " + e.getMessage());
                        }

                        try {
                            if (cameraDevice != null) {
                                cameraDevice.close();
                                cameraDevice = null;
                            }
                        } catch (Exception e) {
                            AppLog.d(TAG, "Camera " + cameraId + " ignored exception while closing device: " + e.getMessage());
                        }

                        // 小延迟，确保资源释放完成
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            // Ignore
                        }

                        // 重新打开摄像头
                        cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
                        
                    } catch (CameraAccessException e) {
                        AppLog.e(TAG, "Failed to reconnect camera " + cameraId + ": " + e.getMessage());
                        
                        // 检查是否是后台限制错误
                        if (e.getReason() == CameraAccessException.CAMERA_DISABLED) {
                            AppLog.w(TAG, "Camera " + cameraId + " disabled by policy during reconnect, stopping all attempts");
                            shouldReconnect = false;
                            isReconnecting = false;
                            reconnectAttempts = 0;
                            return;
                        }
                        
                        isReconnecting = false;
                        // 继续尝试重连
                        if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                            scheduleReconnect();
                        }
                    } catch (SecurityException e) {
                        AppLog.e(TAG, "No camera permission during reconnect", e);
                        shouldReconnect = false;
                        isReconnecting = false;
                    }
                }
            };

            // 延迟执行重连
            if (backgroundHandler != null) {
                backgroundHandler.postDelayed(reconnectRunnable, RECONNECT_DELAY_MS);
            } else {
                isReconnecting = false;
            }
        }
    }

    /**
     * 摄像头状态回调
     */
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            synchronized (reconnectLock) {
                cameraDevice = camera;
                reconnectAttempts = 0;  // 重置重连计数
                isReconnecting = false;  // 重连成功，清除重连标志
                AppLog.d(TAG, "Camera " + cameraId + " opened");
                if (callback != null) {
                    callback.onCameraOpened(cameraId);
                }
            }
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice camera) {
            synchronized (reconnectLock) {
                try {
                    camera.close();
                } catch (Exception e) {
                    // 忽略关闭异常
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception while closing on disconnect: " + e.getMessage());
                }
                cameraDevice = null;
                AppLog.w(TAG, "Camera " + cameraId + " DISCONNECTED - will attempt to reconnect...");
                if (callback != null) {
                    callback.onCameraError(cameraId, -4); // 自定义错误码：断开连接
                }

                // 断开连接可能发生在重连过程中（openCamera 后但配置 session 前）
                // 需要重置 isReconnecting 标志以允许继续重试
                if (isReconnecting) {
                    AppLog.d(TAG, "Camera " + cameraId + " disconnected during reconnect, resetting flag");
                    isReconnecting = false;
                }
                
                // 启动自动重连
                if (shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    scheduleReconnect();
                } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                    AppLog.e(TAG, "Camera " + cameraId + " max reconnect attempts reached, giving up");
                }
            }
        }

        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            synchronized (reconnectLock) {
                try {
                    camera.close();
                } catch (Exception e) {
                    // 忽略关闭异常
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception while closing on error: " + e.getMessage());
                }
                cameraDevice = null;
                String errorMsg = "UNKNOWN";
                boolean shouldRetry = false;
                boolean shouldStopReconnect = false;  // 是否应该完全停止重连

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
                        errorMsg = "ERROR_CAMERA_DISABLED (3) - Camera disabled by policy (likely background restriction)";
                        shouldRetry = false;  // 摄像头被禁用（后台限制），不应重试
                        shouldStopReconnect = true;  // 完全停止重连
                        break;
                    case CameraDevice.StateCallback.ERROR_CAMERA_DEVICE:
                        errorMsg = "ERROR_CAMERA_DEVICE (4) - Fatal device error!";
                        shouldRetry = false;  // 设备错误，不应重试
                        shouldStopReconnect = true;  // 完全停止重连
                        break;
                    case CameraDevice.StateCallback.ERROR_CAMERA_SERVICE:
                        errorMsg = "ERROR_CAMERA_SERVICE (5) - Camera service error";
                        shouldRetry = true;  // 服务错误，可以重试
                        break;
                }

                AppLog.e(TAG, "Camera " + cameraId + " error: " + errorMsg);
                if (callback != null) {
                    callback.onCameraError(cameraId, error);
                }

                // 如果需要完全停止重连
                if (shouldStopReconnect) {
                    AppLog.w(TAG, "Camera " + cameraId + " stopping all reconnect attempts due to: " + errorMsg);
                    shouldReconnect = false;
                    isReconnecting = false;
                    reconnectAttempts = 0;
                    
                    // 取消所有待执行的重连任务
                    if (reconnectRunnable != null && backgroundHandler != null) {
                        backgroundHandler.removeCallbacks(reconnectRunnable);
                        reconnectRunnable = null;
                    }
                    return;
                }

                // 重连过程中收到错误，说明 openCamera 已经执行完毕（通过回调返回了错误）
                // 需要重置 isReconnecting 标志，以便可以继续下一次重连尝试
                if (isReconnecting) {
                    AppLog.d(TAG, "Camera " + cameraId + " reconnect attempt completed with error, resetting flag");
                    isReconnecting = false;
                }
                
                // 如果应该重试且允许重连，则启动自动重连
                if (shouldRetry && shouldReconnect && reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
                    scheduleReconnect();
                } else if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
                    AppLog.e(TAG, "Camera " + cameraId + " max reconnect attempts reached, giving up");
                }
            }
        }
    };

    /**
     * 创建预览会话
     */
    private void createCameraPreviewSession() {
        try {
            AppLog.d(TAG, "createCameraPreviewSession: Starting for camera " + cameraId);

            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) {
                AppLog.e(TAG, "Surface not available for camera " + cameraId);
                AppLog.e(TAG, "TextureView available: " + textureView.isAvailable());
                AppLog.e(TAG, "SurfaceTexture: " + textureView.getSurfaceTexture());
                return;
            }


            // 设置预览尺寸为最小值以减少资源消耗
            if (previewSize != null) {
                // 使用最小的预览尺寸 (例如 320x240)
                surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                AppLog.d(TAG, "Camera " + cameraId + " buffer size set to: " + previewSize);
            } else {
                AppLog.e(TAG, "Camera " + cameraId + " Cannot set buffer size - previewSize: " + previewSize + ", SurfaceTexture: " + surfaceTexture);
            }

            // 如果是后摄像头，确保应用镜像变换
            if ("back".equals(cameraPosition)) {
                applyMirrorTransform();
            }

            // 如果设置了自定义旋转角度，应用旋转（仅用于自定义车型）
            if (customRotation != 0) {
                applyCustomRotation();
            }

            // 创建预览请求
            // 注意：必须每次都创建新的 Surface，因为旧的 Surface 可能已被释放
            if (previewSurface != null) {
                try {
                    previewSurface.release();
                } catch (Exception e) {
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception while releasing old preview surface: " + e.getMessage());
                }
                previewSurface = null;
            }
            Surface surface = new Surface(surfaceTexture);
            previewSurface = surface;  // 缓存新的 Surface
            AppLog.d(TAG, "Camera " + cameraId + " Surface obtained: " + surface);

            AppLog.d(TAG, "Camera " + cameraId + " Creating capture request...");
            int template = (recordSurface != null) ? CameraDevice.TEMPLATE_RECORD : CameraDevice.TEMPLATE_PREVIEW;
            final CaptureRequest.Builder previewRequestBuilder = cameraDevice.createCaptureRequest(template);
            
            // 保存请求构建器引用（用于实时更新亮度/降噪参数）
            currentRequestBuilder = previewRequestBuilder;
            
            // 如果启用了亮度/降噪调节，应用配置中保存的参数
            if (imageAdjustEnabled) {
                applyImageAdjustParamsFromConfig(previewRequestBuilder);
            }
            
            // 准备所有输出Surface
            java.util.List<Surface> surfaces = new java.util.ArrayList<>();

            // 单一输出模式处理（用于 L6/L7 等不支持多路输出的车机平台）
            if (singleOutputMode && recordSurface != null && recordSurface.isValid()) {
                // 单一输出模式：录制时只使用 recordSurface，不使用预览 Surface
                // 这会导致预览冻结，但能确保录制正常工作
                AppLog.d(TAG, "Camera " + cameraId + " SINGLE OUTPUT MODE: Using ONLY record surface");
                surfaces.add(recordSurface);
                previewRequestBuilder.addTarget(recordSurface);
                AppLog.d(TAG, "Camera " + cameraId + " Added record surface ONLY: " + recordSurface);
            } else {
                // 正常模式：添加预览 Surface
                previewRequestBuilder.addTarget(surface);
                surfaces.add(surface);
                AppLog.d(TAG, "Camera " + cameraId + " Added preview surface to request");

                // 如果有录制Surface且不是单一输出模式，也添加到输出目标
                if (recordSurface != null) {
                    // 检查 recordSurface 有效性
                    boolean isValid = recordSurface.isValid();
                    AppLog.d(TAG, "Camera " + cameraId + " recordSurface check: " + recordSurface + ", isValid=" + isValid);
                    
                    if (!isValid) {
                        AppLog.e(TAG, "Camera " + cameraId + " CRITICAL: recordSurface is INVALID! Recording will likely fail!");
                        // 如果录制 Surface 无效，不添加到会话中，避免配置失败
                        AppLog.w(TAG, "Camera " + cameraId + " Skipping invalid record surface to avoid session configuration failure");
                    } else {
                        surfaces.add(recordSurface);
                        previewRequestBuilder.addTarget(recordSurface);
                        AppLog.d(TAG, "Added record surface to camera " + cameraId + " (isValid=" + isValid + ")");
                    }
                }
            }

            // 不再在预览会话中添加ImageReader Surface
            // ImageReader将在拍照时按需创建，避免占用额外缓冲区

            AppLog.d(TAG, "Camera " + cameraId + " Total surfaces: " + surfaces.size());
            
            // 诊断：列出所有 surfaces
            for (int i = 0; i < surfaces.size(); i++) {
                Surface s = surfaces.get(i);
                AppLog.d(TAG, "Camera " + cameraId + " Surface[" + i + "]: " + s + ", isValid=" + s.isValid());
            }

            // 创建会话
            AppLog.d(TAG, "Camera " + cameraId + " Creating capture session with " + surfaces.size() + " surfaces...");
            cameraDevice.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    AppLog.d(TAG, "Camera " + cameraId + " Session configured!");
                    if (cameraDevice == null) {
                        AppLog.e(TAG, "Camera " + cameraId + " cameraDevice is null in onConfigured");
                        return;
                    }

                    // 【关键】检查 session 是否仍然有效
                    // 如果在回调执行前 recreateSession() 被再次调用，当前 session 可能已被关闭
                    // 在这种情况下，captureSession 可能已经被设置为 null 或新的 session
                    if (captureSession != null && captureSession != session) {
                        AppLog.w(TAG, "Camera " + cameraId + " Session already replaced by newer session, ignoring this callback");
                        try {
                            session.close();
                        } catch (Exception e) {
                            // 忽略关闭异常
                        }
                        return;
                    }

                    captureSession = session;
                    try {
                        // 重置帧计数
                        frameCount = 0;
                        lastFrameLogTime = System.currentTimeMillis();

                        // 创建 CaptureCallback 来监控帧捕获（调试用）
                        CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
                            @Override
                            public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                          @NonNull CaptureRequest request,
                                                          @NonNull TotalCaptureResult result) {
                                frameCount++;
                                long now = System.currentTimeMillis();
                                
                                // 读取相机实际使用的参数（只读取一次或定期读取）
                                if (!hasReadActualParams || frameCount == 1) {
                                    readActualParamsFromResult(result);
                                    hasReadActualParams = true;
                                }
                                
                                if (now - lastFrameLogTime >= FRAME_LOG_INTERVAL_MS) {
                                    long elapsed = now - lastFrameLogTime;
                                    float fps = frameCount * 1000f / elapsed;
                                    boolean hasRecordSurface = recordSurface != null;
                                    AppLog.d(TAG, "Camera " + cameraId + " FRAME STATS: " + frameCount + " frames in " +
                                            (elapsed / 1000) + "s (FPS: " + String.format("%.1f", fps) + ")" +
                                            ", recordSurface=" + (hasRecordSurface ? "ACTIVE" : "null"));
                                    frameCount = 0;
                                    lastFrameLogTime = now;
                                }
                            }

                            @Override
                            public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull android.hardware.camera2.CaptureFailure failure) {
                                AppLog.e(TAG, "Camera " + cameraId + " CAPTURE FAILED! Reason: " + failure.getReason());
                            }
                        };

                        // 开始预览
                        AppLog.d(TAG, "Camera " + cameraId + " Setting repeating request...");
                        
                        // 再次检查 session 是否仍然有效（防止并发 recreateSession 导致的竞态）
                        if (captureSession != session) {
                            AppLog.w(TAG, "Camera " + cameraId + " Session changed before setRepeatingRequest, aborting");
                            return;
                        }
                        
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(), captureCallback, backgroundHandler);

                        AppLog.d(TAG, "Camera " + cameraId + " preview started successfully!");
                        if (callback != null) {
                            callback.onCameraConfigured(cameraId);
                        }
                    } catch (CameraAccessException e) {
                        AppLog.e(TAG, "Failed to start preview for camera " + cameraId, e);
                    } catch (IllegalStateException e) {
                        // Session 可能在设置 repeating request 前被关闭
                        AppLog.w(TAG, "Camera " + cameraId + " Session closed before setRepeatingRequest: " + e.getMessage());
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    AppLog.e(TAG, "Failed to configure camera " + cameraId + " session!");
                    AppLog.e(TAG, "Camera " + cameraId + " session configuration failed. This may indicate:");
                    AppLog.e(TAG, "  1. Device does not support simultaneous preview and recording surfaces");
                    AppLog.e(TAG, "  2. Resolution mismatch between preview (" + previewSize + ") and recording");
                    AppLog.e(TAG, "  3. Device resource limitations");
                    
                    // 如果是因为录制 Surface 导致的失败，尝试只使用预览 Surface
                    if (recordSurface != null) {
                        AppLog.w(TAG, "Camera " + cameraId + " Retrying with preview-only session (without recording surface)");
                        // 清除录制 Surface，让重试时只使用预览 Surface
                        // 注意：不恢复 recordSurface，让上层（MultiCameraManager）重新管理录制状态
                        recordSurface = null;
                        // 延迟重试，避免立即操作
                        if (backgroundHandler != null) {
                            backgroundHandler.postDelayed(() -> {
                                if (cameraDevice != null) {
                                    AppLog.d(TAG, "Camera " + cameraId + " retrying session with preview-only");
                                    createCameraPreviewSession();
                                }
                            }, 500);
                        }
                    } else {
                        // 没有录制 Surface 也失败，这是严重问题
                        if (callback != null) {
                            callback.onCameraError(cameraId, -3);
                        }
                    }
                }
            }, backgroundHandler);

        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Failed to create preview session for camera " + cameraId, e);
            AppLog.e(TAG, "Exception details: " + e.getMessage());
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            // 特殊处理 "Surface was abandoned" 错误
            String message = e.getMessage();
            if (message != null && message.contains("abandoned")) {
                AppLog.e(TAG, "Camera " + cameraId + " detected abandoned Surface, attempting recovery...");
                // 清理废弃的录制 Surface
                if (recordSurface != null) {
                    AppLog.w(TAG, "Camera " + cameraId + " clearing abandoned recordSurface and retrying");
                    recordSurface = null;
                    // 延迟重试创建会话（只使用预览 Surface）
                    if (backgroundHandler != null) {
                        backgroundHandler.postDelayed(() -> {
                            if (cameraDevice != null) {
                                AppLog.d(TAG, "Camera " + cameraId + " retrying session creation without record surface");
                                createCameraPreviewSession();
                            }
                        }, 100);
                    }
                    return;
                }
            }
            AppLog.e(TAG, "Unexpected IllegalArgumentException creating session for camera " + cameraId, e);
            e.printStackTrace();
        } catch (Exception e) {
            AppLog.e(TAG, "Unexpected exception creating session for camera " + cameraId, e);
            AppLog.e(TAG, "Exception details: " + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 重新创建会话（用于开始/停止录制时）
     */
    public void recreateSession() {
        if (cameraDevice != null) {
            if (captureSession != null) {
                try {
                    captureSession.close();
                } catch (Exception e) {
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception while closing session: " + e.getMessage());
                }
                captureSession = null;
            }
            // 注意：不在这里释放 previewSurface，因为 createCameraPreviewSession 会处理
            // 减少延迟时间：Camera2 的 createCaptureSession 会自动处理旧 session
            // 20ms 足够让系统完成清理
            if (backgroundHandler != null) {
                backgroundHandler.postDelayed(() -> {
                    createCameraPreviewSession();
                }, 20);
            } else {
                createCameraPreviewSession();
            }
        }
    }

    /**
     * 拍照（自动生成时间戳）
     */
    public void takePicture() {
        // 生成时间戳
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        takePicture(timestamp);
    }

    /**
     * 拍照（使用指定的时间戳）
     * @param timestamp 文件命名用的时间戳
     */
    public void takePicture(String timestamp) {
        takePicture(timestamp, 0);  // 默认无延迟
    }

    /**
     * 拍照（使用指定的时间戳和保存延迟）
     * @param timestamp 文件命名用的时间戳
     * @param saveDelayMs 保存文件前的延迟时间（毫秒）
     */
    public void takePicture(String timestamp, int saveDelayMs) {
        if (textureView == null || !textureView.isAvailable()) {
            AppLog.e(TAG, "Camera " + cameraId + " TextureView not available");
            return;
        }

        if (previewSize == null) {
            AppLog.e(TAG, "Camera " + cameraId + " preview size not available");
            return;
        }

        // 在后台线程中处理截图和保存
        if (backgroundHandler != null) {
            backgroundHandler.post(() -> {
                try {
                    // 1. 立即从TextureView获取Bitmap（快速抓拍）
                    android.graphics.Bitmap bitmap = textureView.getBitmap(
                            previewSize.getWidth(),
                            previewSize.getHeight()
                    );
                    
                    if (bitmap != null) {
                        AppLog.d(TAG, "Camera " + cameraId + " picture captured (" +
                              bitmap.getWidth() + "x" + bitmap.getHeight() + "), will save in " + saveDelayMs + "ms");
                        
                        // 2. 延迟后再保存到磁盘（分散I/O压力）
                        if (saveDelayMs > 0) {
                            try {
                                Thread.sleep(saveDelayMs);
                            } catch (InterruptedException e) {
                                AppLog.w(TAG, "Save delay interrupted");
                            }
                        }
                        
                        // 3. 保存文件
                        saveBitmapAsJPEG(bitmap, timestamp);
                        bitmap.recycle();
                        AppLog.d(TAG, "Camera " + cameraId + " picture saved");
                    } else {
                        AppLog.e(TAG, "Camera " + cameraId + " failed to get bitmap from TextureView");
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "Camera " + cameraId + " error capturing picture", e);
                }
            });
        }
    }

    /**
     * 将Bitmap保存为JPEG文件
     */
    private void saveBitmapAsJPEG(android.graphics.Bitmap bitmap) {
        // 生成时间戳
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        saveBitmapAsJPEG(bitmap, timestamp);
    }

    /**
     * 将Bitmap保存为JPEG文件（使用指定的时间戳）
     */
    private void saveBitmapAsJPEG(android.graphics.Bitmap bitmap, String timestamp) {
        File photoDir = StorageHelper.getPhotoDir(context);
        if (!photoDir.exists()) {
            photoDir.mkdirs();
        }

        // 检查存储空间是否充足（至少需要 5MB）
        long availableSpace = StorageHelper.getAvailableSpace(photoDir);
        if (availableSpace >= 0 && availableSpace < 5 * 1024 * 1024) {
            AppLog.w(TAG, "Camera " + cameraId + " 存储空间不足，剩余: " + StorageHelper.formatSize(availableSpace));
            // 仍然尝试保存，因为照片通常只有几百KB
        }

        // 使用传入的时间戳命名：yyyyMMdd_HHmmss_摄像头位置.jpg
        String position = (cameraPosition != null) ? cameraPosition : cameraId;
        File photoFile = new File(photoDir, timestamp + "_" + position + ".jpg");

        // 检查是否需要添加时间角标
        android.graphics.Bitmap finalBitmap = bitmap;
        AppConfig appConfig = new AppConfig(context);
        if (appConfig.isTimestampWatermarkEnabled()) {
            finalBitmap = addTimestampWatermark(bitmap, timestamp);
        }

        FileOutputStream output = null;
        try {
            output = new FileOutputStream(photoFile);
            finalBitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output);
            output.flush();
            AppLog.i(TAG, "Photo saved: " + photoFile.getAbsolutePath());
        } catch (IOException e) {
            if (e.getMessage() != null && e.getMessage().contains("ENOSPC")) {
                AppLog.e(TAG, "Camera " + cameraId + " 保存照片失败：存储空间已满");
            } else {
                AppLog.e(TAG, "Failed to save photo", e);
            }
        } finally {
            if (output != null) {
                try {
                    output.close();
                } catch (IOException e) {
                    // 关闭流时的 ENOSPC 错误通常表示文件已保存，但空间紧张
                    // 降低日志级别，避免误导用户以为保存失败
                    if (e.getMessage() != null && e.getMessage().contains("ENOSPC")) {
                        AppLog.w(TAG, "Camera " + cameraId + " 存储空间已满，请清理存储");
                    } else {
                        AppLog.e(TAG, "Failed to close output stream", e);
                    }
                }
            }
            // 如果创建了新的bitmap用于水印，需要回收
            if (finalBitmap != bitmap && finalBitmap != null) {
                finalBitmap.recycle();
            }
        }
    }

    /**
     * 在Bitmap上添加时间角标
     * @param originalBitmap 原始图片
     * @param timestamp 时间戳字符串（格式：yyyyMMdd_HHmmss）
     * @return 带有时间角标的新Bitmap
     */
    private android.graphics.Bitmap addTimestampWatermark(android.graphics.Bitmap originalBitmap, String timestamp) {
        try {
            // 创建可编辑的副本
            android.graphics.Bitmap mutableBitmap = originalBitmap.copy(android.graphics.Bitmap.Config.ARGB_8888, true);
            android.graphics.Canvas canvas = new android.graphics.Canvas(mutableBitmap);

            // 将时间戳转换为可读格式：yyyyMMdd_HHmmss -> yyyy-MM-dd HH:mm:ss
            String displayTime;
            try {
                java.text.SimpleDateFormat inputFormat = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault());
                java.text.SimpleDateFormat outputFormat = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault());
                java.util.Date date = inputFormat.parse(timestamp);
                displayTime = outputFormat.format(date);
            } catch (Exception e) {
                // 解析失败，使用当前时间
                displayTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new java.util.Date());
            }

            // 根据图片宽度动态计算字体大小（约为图片宽度的3%）
            float textSize = mutableBitmap.getWidth() * 0.03f;
            if (textSize < 16) textSize = 16;  // 最小16像素
            if (textSize > 48) textSize = 48;  // 最大48像素

            // 设置画笔 - 阴影效果
            android.graphics.Paint shadowPaint = new android.graphics.Paint();
            shadowPaint.setColor(android.graphics.Color.BLACK);
            shadowPaint.setTextSize(textSize);
            shadowPaint.setAntiAlias(true);
            shadowPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

            // 设置画笔 - 主文字
            android.graphics.Paint textPaint = new android.graphics.Paint();
            textPaint.setColor(android.graphics.Color.WHITE);
            textPaint.setTextSize(textSize);
            textPaint.setAntiAlias(true);
            textPaint.setTypeface(android.graphics.Typeface.MONOSPACE);

            // 计算位置（左上角，留一定边距）
            float x = textSize * 0.5f;
            float y = textSize * 1.2f;

            // 绘制阴影（偏移2像素）
            canvas.drawText(displayTime, x + 2, y + 2, shadowPaint);
            // 绘制主文字
            canvas.drawText(displayTime, x, y, textPaint);

            AppLog.d(TAG, "Camera " + cameraId + " added timestamp watermark: " + displayTime);
            return mutableBitmap;

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to add timestamp watermark", e);
            return originalBitmap;  // 失败时返回原图
        }
    }

    /**
     * 关闭摄像头
     */
    public void closeCamera() {
        // 如果不是主实例，不执行关闭操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping closeCamera");
            return;
        }
        
        synchronized (reconnectLock) {
            shouldReconnect = false;  // 禁用自动重连
            reconnectAttempts = 0;  // 重置重连计数
            isReconnecting = false;  // 清除重连状态

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
                    // 忽略关闭异常
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception while closing session: " + e.getMessage());
                }
                captureSession = null;
            }

            // 关闭设备（捕获异常）
            if (cameraDevice != null) {
                try {
                    cameraDevice.close();
                } catch (Exception e) {
                    // 忽略关闭异常
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception while closing device: " + e.getMessage());
                }
                cameraDevice = null;
            }

            // 释放预览 Surface
            if (previewSurface != null) {
                try {
                    previewSurface.release();
                    AppLog.d(TAG, "Camera " + cameraId + " released preview surface");
                } catch (Exception e) {
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception while releasing preview surface: " + e.getMessage());
                }
                previewSurface = null;
            }

            // 清理录制 Surface 引用（重要：防止 Surface abandoned 错误）
            // 注意：这里只是清除引用，不 release()，因为 Surface 由 VideoRecorder 管理
            if (recordSurface != null) {
                AppLog.d(TAG, "Camera " + cameraId + " clearing record surface reference");
                recordSurface = null;
            }

            // 释放ImageReader
            if (imageReader != null) {
                try {
                    imageReader.close();
                    AppLog.d(TAG, "Camera " + cameraId + " released image reader");
                } catch (Exception e) {
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception while closing image reader: " + e.getMessage());
                }
                imageReader = null;
            }

            stopBackgroundThread();

            AppLog.d(TAG, "Camera " + cameraId + " closed");
            if (callback != null) {
                callback.onCameraClosed(cameraId);
            }
        }
    }

    /**
     * 手动触发重连（重置重连计数）
     */
    public void reconnect() {
        // 如果不是主实例，不执行重连操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping reconnect");
            return;
        }
        
        synchronized (reconnectLock) {
            AppLog.d(TAG, "Camera " + cameraId + " manual reconnect requested (PRIMARY instance)");
            
            // 取消所有待执行的重连任务
            if (reconnectRunnable != null && backgroundHandler != null) {
                backgroundHandler.removeCallbacks(reconnectRunnable);
                reconnectRunnable = null;
            }
            
            reconnectAttempts = 0;
            shouldReconnect = true;
            isReconnecting = false;
        }
        closeCamera();
        openCamera();
    }

    /**
     * 检查摄像头是否已连接
     */
    public boolean isConnected() {
        return cameraDevice != null;
    }

    /**
     * 生命周期：暂停摄像头（App退到后台时调用）
     * 暂停时不会触发自动重连，因为是主动暂停
     */
    public void pauseByLifecycle() {
        // 如果不是主实例，不执行暂停操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping pauseByLifecycle");
            return;
        }
        
        synchronized (reconnectLock) {
            AppLog.d(TAG, "Camera " + cameraId + " paused by lifecycle (PRIMARY instance)");
            isPausedByLifecycle = true;
            shouldReconnect = false;  // 禁用自动重连，因为是主动暂停
            isReconnecting = false;  // 清除重连状态
            
            // 取消所有待执行的重连任务
            if (reconnectRunnable != null && backgroundHandler != null) {
                backgroundHandler.removeCallbacks(reconnectRunnable);
                reconnectRunnable = null;
            }
        }
        closeCamera();
    }

    /**
     * 生命周期：恢复摄像头（App返回前台时调用）
     * 如果摄像头之前是暂停状态，会自动重新打开
     */
    public void resumeByLifecycle() {
        // 如果不是主实例，不执行恢复操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping resumeByLifecycle");
            return;
        }
        
        boolean shouldOpen = false;
        synchronized (reconnectLock) {
            AppLog.d(TAG, "Camera " + cameraId + " resume by lifecycle (PRIMARY instance)");
            if (isPausedByLifecycle) {
                isPausedByLifecycle = false;
                reconnectAttempts = 0;  // 重置重连计数
                shouldReconnect = true;  // 启用自动重连
                isReconnecting = false;  // 清除重连状态
                shouldOpen = true;
                
                // 取消所有待执行的重连任务
                if (reconnectRunnable != null && backgroundHandler != null) {
                    backgroundHandler.removeCallbacks(reconnectRunnable);
                    reconnectRunnable = null;
                }
            }
        }
        if (shouldOpen) {
            openCamera();
        }
    }

    /**
     * 强制重新打开摄像头（用于从后台返回前台时）
     * 即使摄像头当前是连接状态，也会重新打开
     */
    public void forceReopen() {
        // 如果不是主实例，不执行重开操作
        if (!isPrimaryInstance) {
            AppLog.d(TAG, "Camera " + cameraId + " (" + cameraPosition + ") is SECONDARY instance, skipping forceReopen");
            return;
        }
        
        synchronized (reconnectLock) {
            AppLog.d(TAG, "Camera " + cameraId + " force reopen requested (PRIMARY instance)");
            
            // 取消所有待执行的重连任务
            if (reconnectRunnable != null && backgroundHandler != null) {
                backgroundHandler.removeCallbacks(reconnectRunnable);
                reconnectRunnable = null;
            }
            
            // 重置状态
            reconnectAttempts = 0;
            shouldReconnect = true;
            isReconnecting = false;
            
            // 关闭现有连接
            if (cameraDevice != null) {
                try {
                    if (captureSession != null) {
                        captureSession.close();
                        captureSession = null;
                    }
                } catch (Exception e) {
                    // 忽略关闭异常
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception during session close: " + e.getMessage());
                }
                
                try {
                    cameraDevice.close();
                    cameraDevice = null;
                } catch (Exception e) {
                    AppLog.d(TAG, "Camera " + cameraId + " ignored exception during device close: " + e.getMessage());
                }
            }
            
            // 延迟重新打开，避免立即操作
            if (backgroundHandler != null) {
                backgroundHandler.postDelayed(() -> {
                    synchronized (reconnectLock) {
                        try {
                            cameraManager.openCamera(cameraId, stateCallback, backgroundHandler);
                            AppLog.d(TAG, "Camera " + cameraId + " force reopen initiated");
                        } catch (CameraAccessException e) {
                            AppLog.e(TAG, "Failed to force reopen camera " + cameraId, e);
                            if (shouldReconnect) {
                                scheduleReconnect();
                            }
                        } catch (SecurityException e) {
                            AppLog.e(TAG, "No camera permission during force reopen", e);
                        }
                    }
                }, 300);  // 延迟300ms，给系统时间释放资源
            } else {
                // 如果后台线程不存在，重新启动
                startBackgroundThread();
                backgroundHandler.postDelayed(() -> {
                    openCamera();
                }, 300);
            }
        }
    }
    
    // ==================== 亮度/降噪调节相关方法 ====================
    
    /**
     * 设置是否启用亮度/降噪调节
     * @param enabled true 表示启用
     */
    public void setImageAdjustEnabled(boolean enabled) {
        this.imageAdjustEnabled = enabled;
        AppLog.d(TAG, "Camera " + cameraId + " image adjust: " + (enabled ? "ENABLED" : "DISABLED"));
    }
    
    /**
     * 从配置中读取并应用亮度/降噪调节参数
     * @param requestBuilder 请求构建器
     */
    private void applyImageAdjustParamsFromConfig(CaptureRequest.Builder requestBuilder) {
        try {
            AppConfig appConfig = new AppConfig(context);
            
            // 应用曝光补偿
            int exposureComp = appConfig.getExposureCompensation();
            if (exposureComp != 0) {
                Range<Integer> range = getExposureCompensationRange();
                if (range != null) {
                    int clampedValue = Math.max(range.getLower(), Math.min(exposureComp, range.getUpper()));
                    requestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedValue);
                    AppLog.d(TAG, "Camera " + cameraId + " applied exposure compensation: " + clampedValue);
                }
            }
            
            // 应用白平衡模式
            int awbMode = appConfig.getAwbMode();
            if (awbMode >= 0) {
                int[] supportedModes = getSupportedAwbModes();
                if (supportedModes != null && isModeSupported(supportedModes, awbMode)) {
                    requestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
                    AppLog.d(TAG, "Camera " + cameraId + " applied AWB mode: " + awbMode);
                }
            }
            
            // 应用色调映射模式
            int tonemapMode = appConfig.getTonemapMode();
            if (tonemapMode >= 0) {
                int[] supportedModes = getSupportedTonemapModes();
                if (supportedModes != null && isModeSupported(supportedModes, tonemapMode)) {
                    requestBuilder.set(CaptureRequest.TONEMAP_MODE, tonemapMode);
                    AppLog.d(TAG, "Camera " + cameraId + " applied tonemap mode: " + tonemapMode);
                }
            }
            
            // 应用边缘增强模式
            int edgeMode = appConfig.getEdgeMode();
            if (edgeMode >= 0) {
                int[] supportedModes = getSupportedEdgeModes();
                if (supportedModes != null && isModeSupported(supportedModes, edgeMode)) {
                    requestBuilder.set(CaptureRequest.EDGE_MODE, edgeMode);
                    AppLog.d(TAG, "Camera " + cameraId + " applied edge mode: " + edgeMode);
                }
            }
            
            // 应用降噪模式
            int noiseReductionMode = appConfig.getNoiseReductionMode();
            if (noiseReductionMode >= 0) {
                int[] supportedModes = getSupportedNoiseReductionModes();
                if (supportedModes != null && isModeSupported(supportedModes, noiseReductionMode)) {
                    requestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode);
                    AppLog.d(TAG, "Camera " + cameraId + " applied noise reduction mode: " + noiseReductionMode);
                }
            }
            
            // 应用特效模式
            int effectMode = appConfig.getEffectMode();
            if (effectMode >= 0) {
                int[] supportedModes = getSupportedEffectModes();
                if (supportedModes != null && isModeSupported(supportedModes, effectMode)) {
                    requestBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, effectMode);
                    AppLog.d(TAG, "Camera " + cameraId + " applied effect mode: " + effectMode);
                }
            }
            
            AppLog.d(TAG, "Camera " + cameraId + " image adjust params applied from config");
            
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to apply image adjust params from config", e);
        }
    }
    
    /**
     * 获取是否启用亮度/降噪调节
     */
    public boolean isImageAdjustEnabled() {
        return imageAdjustEnabled;
    }
    
    /**
     * 获取曝光补偿范围
     * @return 曝光补偿范围 [min, max]，如果不支持返回 null
     */
    public Range<Integer> getExposureCompensationRange() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get exposure compensation range", e);
        }
        return null;
    }
    
    /**
     * 获取曝光补偿步长
     * @return 曝光补偿步长（EV 单位），如果不支持返回 null
     */
    public android.util.Rational getExposureCompensationStep() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get exposure compensation step", e);
        }
        return null;
    }
    
    /**
     * 获取支持的白平衡模式
     * @return 支持的白平衡模式数组，如果不支持返回 null
     */
    public int[] getSupportedAwbModes() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get supported AWB modes", e);
        }
        return null;
    }
    
    /**
     * 获取支持的色调映射模式
     * @return 支持的色调映射模式数组，如果不支持返回 null
     */
    public int[] getSupportedTonemapModes() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get supported tonemap modes", e);
        }
        return null;
    }
    
    /**
     * 获取支持的边缘增强模式
     * @return 支持的边缘增强模式数组，如果不支持返回 null
     */
    public int[] getSupportedEdgeModes() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get supported edge modes", e);
        }
        return null;
    }
    
    /**
     * 获取支持的降噪模式
     * @return 支持的降噪模式数组，如果不支持返回 null
     */
    public int[] getSupportedNoiseReductionModes() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get supported noise reduction modes", e);
        }
        return null;
    }
    
    /**
     * 获取支持的特效模式
     * @return 支持的特效模式数组，如果不支持返回 null
     */
    public int[] getSupportedEffectModes() {
        try {
            CameraCharacteristics chars = getCameraCharacteristics();
            if (chars != null) {
                return chars.get(CameraCharacteristics.CONTROL_AVAILABLE_EFFECTS);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get supported effect modes", e);
        }
        return null;
    }
    
    /**
     * 获取摄像头特性（带缓存）
     */
    private CameraCharacteristics getCameraCharacteristics() {
        if (cameraCharacteristics != null) {
            return cameraCharacteristics;
        }
        
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            return cameraCharacteristics;
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to get characteristics", e);
            return null;
        }
    }
    
    /**
     * 实时更新亮度/降噪调节参数
     * 参数会立即应用到预览和录制
     * 
     * @param exposureCompensation 曝光补偿值（Integer.MIN_VALUE 表示不设置）
     * @param awbMode 白平衡模式（-1 表示不设置）
     * @param tonemapMode 色调映射模式（-1 表示不设置）
     * @param edgeMode 边缘增强模式（-1 表示不设置）
     * @param noiseReductionMode 降噪模式（-1 表示不设置）
     * @param effectMode 特效模式（-1 表示不设置）
     * @return true 表示成功，false 表示失败
     */
    public boolean updateImageAdjustParams(int exposureCompensation, int awbMode, int tonemapMode,
                                           int edgeMode, int noiseReductionMode, int effectMode) {
        if (!imageAdjustEnabled) {
            AppLog.d(TAG, "Camera " + cameraId + " image adjust not enabled, skip update");
            return false;
        }
        
        if (cameraDevice == null || captureSession == null || currentRequestBuilder == null) {
            AppLog.w(TAG, "Camera " + cameraId + " not ready for image adjust update");
            return false;
        }
        
        try {
            // 应用曝光补偿
            if (exposureCompensation != Integer.MIN_VALUE) {
                Range<Integer> range = getExposureCompensationRange();
                if (range != null) {
                    // 确保值在有效范围内
                    int clampedValue = Math.max(range.getLower(), Math.min(exposureCompensation, range.getUpper()));
                    currentRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, clampedValue);
                    AppLog.d(TAG, "Camera " + cameraId + " set exposure compensation: " + clampedValue + " (range: " + range + ")");
                }
            }
            
            // 应用白平衡模式
            if (awbMode >= 0) {
                int[] supportedModes = getSupportedAwbModes();
                if (supportedModes != null && isModeSupported(supportedModes, awbMode)) {
                    currentRequestBuilder.set(CaptureRequest.CONTROL_AWB_MODE, awbMode);
                    AppLog.d(TAG, "Camera " + cameraId + " set AWB mode: " + awbMode);
                } else {
                    AppLog.w(TAG, "Camera " + cameraId + " AWB mode " + awbMode + " not supported");
                }
            }
            
            // 应用色调映射模式
            if (tonemapMode >= 0) {
                int[] supportedModes = getSupportedTonemapModes();
                if (supportedModes != null && isModeSupported(supportedModes, tonemapMode)) {
                    currentRequestBuilder.set(CaptureRequest.TONEMAP_MODE, tonemapMode);
                    AppLog.d(TAG, "Camera " + cameraId + " set tonemap mode: " + tonemapMode);
                } else {
                    AppLog.w(TAG, "Camera " + cameraId + " tonemap mode " + tonemapMode + " not supported");
                }
            }
            
            // 应用边缘增强模式
            if (edgeMode >= 0) {
                int[] supportedModes = getSupportedEdgeModes();
                if (supportedModes != null && isModeSupported(supportedModes, edgeMode)) {
                    currentRequestBuilder.set(CaptureRequest.EDGE_MODE, edgeMode);
                    AppLog.d(TAG, "Camera " + cameraId + " set edge mode: " + edgeMode);
                } else {
                    AppLog.w(TAG, "Camera " + cameraId + " edge mode " + edgeMode + " not supported");
                }
            }
            
            // 应用降噪模式
            if (noiseReductionMode >= 0) {
                int[] supportedModes = getSupportedNoiseReductionModes();
                if (supportedModes != null && isModeSupported(supportedModes, noiseReductionMode)) {
                    currentRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode);
                    AppLog.d(TAG, "Camera " + cameraId + " set noise reduction mode: " + noiseReductionMode);
                } else {
                    AppLog.w(TAG, "Camera " + cameraId + " noise reduction mode " + noiseReductionMode + " not supported");
                }
            }
            
            // 应用特效模式
            if (effectMode >= 0) {
                int[] supportedModes = getSupportedEffectModes();
                if (supportedModes != null && isModeSupported(supportedModes, effectMode)) {
                    currentRequestBuilder.set(CaptureRequest.CONTROL_EFFECT_MODE, effectMode);
                    AppLog.d(TAG, "Camera " + cameraId + " set effect mode: " + effectMode);
                } else {
                    AppLog.w(TAG, "Camera " + cameraId + " effect mode " + effectMode + " not supported");
                }
            }
            
            // 重新提交请求（实时生效）
            captureSession.setRepeatingRequest(currentRequestBuilder.build(), null, backgroundHandler);
            AppLog.d(TAG, "Camera " + cameraId + " image adjust params updated successfully");
            return true;
            
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to update image adjust params", e);
            return false;
        } catch (IllegalStateException e) {
            AppLog.e(TAG, "Camera " + cameraId + " session invalid during image adjust update", e);
            return false;
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " unexpected error during image adjust update", e);
            return false;
        }
    }
    
    /**
     * 检查模式是否在支持列表中
     */
    private boolean isModeSupported(int[] supportedModes, int mode) {
        if (supportedModes == null) {
            return false;
        }
        for (int supported : supportedModes) {
            if (supported == mode) {
                return true;
            }
        }
        return false;
    }
    
    /**
     * 获取当前请求构建器（用于外部调试）
     */
    public CaptureRequest.Builder getCurrentRequestBuilder() {
        return currentRequestBuilder;
    }
    
    /**
     * 从 CaptureResult 读取相机实际使用的参数
     */
    private void readActualParamsFromResult(TotalCaptureResult result) {
        try {
            // 曝光补偿
            Integer exposure = result.get(TotalCaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION);
            if (exposure != null) {
                actualExposureCompensation = exposure;
            }
            
            // 白平衡模式
            Integer awb = result.get(TotalCaptureResult.CONTROL_AWB_MODE);
            if (awb != null) {
                actualAwbMode = awb;
            }
            
            // 边缘增强模式
            Integer edge = result.get(TotalCaptureResult.EDGE_MODE);
            if (edge != null) {
                actualEdgeMode = edge;
            }
            
            // 降噪模式
            Integer noise = result.get(TotalCaptureResult.NOISE_REDUCTION_MODE);
            if (noise != null) {
                actualNoiseReductionMode = noise;
            }
            
            // 特效模式
            Integer effect = result.get(TotalCaptureResult.CONTROL_EFFECT_MODE);
            if (effect != null) {
                actualEffectMode = effect;
            }
            
            // 色调映射模式
            Integer tonemap = result.get(TotalCaptureResult.TONEMAP_MODE);
            if (tonemap != null) {
                actualTonemapMode = tonemap;
            }
            
            AppLog.d(TAG, "Camera " + cameraId + " actual params: exposure=" + actualExposureCompensation +
                    ", awb=" + actualAwbMode + ", edge=" + actualEdgeMode + 
                    ", noise=" + actualNoiseReductionMode + ", effect=" + actualEffectMode +
                    ", tonemap=" + actualTonemapMode);
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " failed to read actual params", e);
        }
    }
    
    // ==================== 获取实际参数的方法 ====================
    
    /**
     * 获取相机实际使用的曝光补偿值
     */
    public int getActualExposureCompensation() {
        return actualExposureCompensation;
    }
    
    /**
     * 获取相机实际使用的白平衡模式
     */
    public int getActualAwbMode() {
        return actualAwbMode;
    }
    
    /**
     * 获取相机实际使用的边缘增强模式
     */
    public int getActualEdgeMode() {
        return actualEdgeMode;
    }
    
    /**
     * 获取相机实际使用的降噪模式
     */
    public int getActualNoiseReductionMode() {
        return actualNoiseReductionMode;
    }
    
    /**
     * 获取相机实际使用的特效模式
     */
    public int getActualEffectMode() {
        return actualEffectMode;
    }
    
    /**
     * 获取相机实际使用的色调映射模式
     */
    public int getActualTonemapMode() {
        return actualTonemapMode;
    }
    
    /**
     * 是否已读取过实际参数
     */
    public boolean hasActualParams() {
        return hasReadActualParams;
    }
}
