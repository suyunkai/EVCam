package com.kooo.evcam.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.view.Surface;

import androidx.core.app.NotificationCompat;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.CameraForegroundService;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.config.BlindSpotConfig;
import com.kooo.evcam.config.RecordingConfig;
import com.kooo.evcam.panoramic.PanoramicEngine;
import com.kooo.evcam.recording.RecordingController;

/**
 * 摄像头录制服务
 * 统一管理录制和补盲功能
 * 按照D:\yuan参考实现重构
 */
public class CameraRecordingService extends Service {
    private static final String TAG = "CameraRecordingService";
    private static final String CHANNEL_ID = "recording_service_channel";
    private static final int NOTIFICATION_ID = 1001;

    // 服务命令
    public static final String ACTION_START_RECORDING = "com.kooo.evcam.action.START_RECORDING";
    public static final String ACTION_STOP_RECORDING = "com.kooo.evcam.action.STOP_RECORDING";
    public static final String ACTION_START_BLIND_SPOT = "com.kooo.evcam.action.START_BLIND_SPOT";
    public static final String ACTION_STOP_BLIND_SPOT = "com.kooo.evcam.action.STOP_BLIND_SPOT";

    // 补盲方向
    public static final String EXTRA_BLIND_SPOT_SIDE = "blind_spot_side";
    public static final String SIDE_LEFT = "left";
    public static final String SIDE_RIGHT = "right";

    private final IBinder binder = new LocalBinder();
    private AppConfig appConfig;
    private RecordingConfig recordingConfig;
    private BlindSpotConfig blindSpotConfig;
    private CameraManagerHolder cameraManagerHolder;

    // 核心组件
    private PanoramicEngine engine;
    private RecordingController recordingController;

    // 录制线程
    private HandlerThread recordingThread;
    private Handler recordingHandler;
    
    // 主线程 Handler（用于更新 UI）
    private Handler mainHandler;

    // 补盲相关
    private int blindSpotTargetIndex = -1;
    private String currentBlindSpotSide = null;
    private SurfaceTexture blindSpotSurfaceTexture;
    private Surface blindSpotSurface;

    // 状态
    private boolean isServiceRunning = false;
    private boolean isRecording = false;

    public class LocalBinder extends Binder {
        public CameraRecordingService getService() {
            return CameraRecordingService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.d(TAG, "录制服务创建");

        appConfig = new AppConfig(this);
        
        // 初始化主线程 Handler
        mainHandler = new Handler(Looper.getMainLooper());
        recordingConfig = new RecordingConfig(
                getSharedPreferences("recording_config", Context.MODE_PRIVATE),
                new RecordingConfig.CarModelProvider() {
                    @Override
                    public String getCarModel() {
                        return appConfig.getCarModel();
                    }

                    @Override
                    public boolean isPanoramicMode() {
                        return appConfig.getCameraCount() >= 4;
                    }
                }
        );
        blindSpotConfig = new BlindSpotConfig(
                getSharedPreferences("blind_spot_config", Context.MODE_PRIVATE)
        );

        cameraManagerHolder = CameraManagerHolder.getInstance();
        recordingController = new RecordingController(this, recordingConfig);

        // 创建录制线程
        recordingThread = new HandlerThread("CameraRecording");
        recordingThread.start();
        recordingHandler = new Handler(recordingThread.getLooper());

        // 延迟初始化全景引擎，避免阻塞主线程
        recordingHandler.post(() -> {
            engine = new PanoramicEngine(CameraRecordingService.this, recordingConfig);
            AppLog.d(TAG, "全景引擎延迟初始化完成");
        });

        isServiceRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action == null) {
            return START_STICKY;
        }

        switch (action) {
            case ACTION_START_RECORDING:
                startRecording();
                break;
            case ACTION_STOP_RECORDING:
                stopRecording();
                break;
            case ACTION_START_BLIND_SPOT:
                String side = intent.getStringExtra(EXTRA_BLIND_SPOT_SIDE);
                startBlindSpotOverlay(side);
                break;
            case ACTION_STOP_BLIND_SPOT:
                stopBlindSpotOverlay();
                break;
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLog.d(TAG, "录制服务销毁");

        stopRecording();
        stopBlindSpotOverlay();

        if (engine != null) {
            engine.release();
            engine = null;
        }

        if (recordingThread != null) {
            recordingThread.quitSafely();
            recordingThread = null;
        }

        isServiceRunning = false;
    }

    // ========== 录制控制 ==========

    public void startRecording() {
        if (isRecording) {
            AppLog.w(TAG, "已经在录制中");
            return;
        }

        recordingHandler.post(() -> {
            try {
                AppLog.d(TAG, "开始初始化录制");

                // 获取 MultiCameraManager
                CameraManagerHolder holder = CameraManagerHolder.getInstance();
                MultiCameraManager cameraManager = holder.getOrInit(this);

                if (cameraManager == null) {
                    AppLog.e(TAG, "CameraManager 未初始化");
                    recordingController.onError(1, "CameraManager 未初始化");
                    return;
                }

                // 检查摄像头是否已初始化
                // 注意：Android 系统限制，后台服务不能直接打开摄像头
                // 摄像头必须在 MainActivity 前台运行时打开
                if (cameraManager.isReleased()) {
                    AppLog.e(TAG, "摄像头未初始化，请先打开应用界面");
                    recordingController.onError(3, "摄像头未初始化，请先打开应用界面");
                    return;
                }

                // 启动录制
                AppLog.d(TAG, "调用 cameraManager.startRecording()...");
                boolean success = cameraManager.startRecording();
                AppLog.d(TAG, "cameraManager.startRecording() 返回: " + success);
                if (success) {
                    isRecording = true;
                    recordingController.startRecording();
                    // 设置状态为录制中，触发状态监听器
                    recordingController.setState(RecordingController.RecordingState.RECORDING);
                    startForeground(NOTIFICATION_ID, createNotification());
                    AppLog.d(TAG, "录制已开始, isRecording=" + isRecording);
                } else {
                    AppLog.e(TAG, "启动录制失败");
                    recordingController.onError(2, "启动录制失败");
                }
            } catch (Exception e) {
                AppLog.e(TAG, "开始录制失败", e);
                recordingController.onError(1, e.getMessage());
            }
        });
    }

    public void stopRecording() {
        if (!isRecording) {
            return;
        }

        // 使用独立线程停止录制，避免阻塞
        new Thread(() -> {
            try {
                AppLog.d(TAG, "停止录制（后台线程）");

                // 停止 MultiCameraManager 录制
                CameraManagerHolder holder = CameraManagerHolder.getInstance();
                MultiCameraManager cameraManager = holder.getOrInit(this);
                if (cameraManager != null) {
                    // 使用超时机制等待停止完成
                    final Object stopLock = new Object();
                    final boolean[] stopped = {false};
                    
                    // 在后台线程中停止录制
                    cameraManager.stopRecording();
                    
                    // 等待停止完成（最多5秒）
                    synchronized (stopLock) {
                        try {
                            stopLock.wait(5000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }

                // 在主线程更新 UI 状态
                mainHandler.post(() -> {
                    try {
                        recordingController.stopRecording();
                        recordingController.setState(RecordingController.RecordingState.IDLE);
                        isRecording = false;

                        // 停止前台服务，但保持服务运行
                        stopForeground(true);
                        
                        // 注意：不在此处停止 CameraForegroundService
                        // 让它继续运行以保持应用在后台存活

                        AppLog.d(TAG, "录制已停止");
                    } catch (Exception e) {
                        AppLog.e(TAG, "停止录制后处理失败", e);
                        // 确保状态重置
                        isRecording = false;
                        recordingController.setState(RecordingController.RecordingState.IDLE);
                    }
                });
            } catch (Exception e) {
                AppLog.e(TAG, "停止录制失败", e);
                // 确保状态重置
                mainHandler.post(() -> {
                    isRecording = false;
                    recordingController.setState(RecordingController.RecordingState.IDLE);
                });
            }
        }, "StopRecordingThread").start();
    }

    public boolean isRecording() {
        return isRecording;
    }

    // ========== 补盲控制 ==========

    public void startBlindSpotOverlay(String side) {
        if (!blindSpotConfig.isEnabled()) {
            AppLog.d(TAG, "补盲功能已禁用");
            return;
        }

        if (side == null || (!SIDE_LEFT.equals(side) && !SIDE_RIGHT.equals(side))) {
            AppLog.w(TAG, "无效的补盲方向: " + side);
            return;
        }

        currentBlindSpotSide = side;

        // 设置补盲目标索引（使用左或右摄像头）
        blindSpotTargetIndex = SIDE_LEFT.equals(side) ? 2 : 3; // 2=左, 3=右

        if (engine != null) {
            engine.setBlindSpotTargetIndex(blindSpotTargetIndex);
        }

        // 启动补盲悬浮窗服务
        Intent intent = new Intent(this, BlindSpotOverlayService.class);
        intent.setAction(BlindSpotOverlayService.ACTION_SHOW_OVERLAY);
        intent.putExtra(BlindSpotOverlayService.EXTRA_SIDE, side);
        startService(intent);

        AppLog.d(TAG, "补盲叠加层已启动: " + side);
    }

    public void stopBlindSpotOverlay() {
        currentBlindSpotSide = null;
        blindSpotTargetIndex = -1;

        if (engine != null) {
            engine.setBlindSpotTargetIndex(-1);
        }

        // 停止补盲悬浮窗服务
        Intent intent = new Intent(this, BlindSpotOverlayService.class);
        intent.setAction(BlindSpotOverlayService.ACTION_HIDE_OVERLAY);
        startService(intent);

        AppLog.d(TAG, "补盲叠加层已停止");
    }

    public String getCurrentBlindSpotSide() {
        return currentBlindSpotSide;
    }

    public boolean isBlindSpotActive() {
        return currentBlindSpotSide != null;
    }

    // ========== 引擎配置 ==========

    private void applyEngineConfig() {
        if (engine == null) return;

        // 设置目标分辨率
        String resolution = recordingConfig.getTargetResolution();
        int[] resolutionArray = RecordingConfig.parseResolution(resolution);
        if (resolutionArray != null && resolutionArray.length == 2) {
            engine.setTargetResolution(resolutionArray[0], resolutionArray[1]);
        }

        // 设置FBO缩放
        engine.setFboScale(recordingConfig.getEncodeScaleFactor());

        // 设置裁剪区域
        applyCropRegions();

        // 设置补盲目标
        if (blindSpotTargetIndex >= 0) {
            engine.setBlindSpotTargetIndex(blindSpotTargetIndex);
            applyBlindSpotOverlayStyle(currentBlindSpotSide);
        }
    }

    private void applyCropRegions() {
        if (engine == null) return;

        // 应用补盲裁剪区域
        float[] leftCrop = blindSpotConfig.getCropRegion(SIDE_LEFT);
        float[] rightCrop = blindSpotConfig.getCropRegion(SIDE_RIGHT);

        engine.setCropRegion("left", leftCrop);
        engine.setCropRegion("right", rightCrop);

        // 应用前后摄像头裁剪（使用默认配置）
        engine.setCropRegion("front", new float[]{0.0f, 0.0f, 1.0f, 0.5f});
        engine.setCropRegion("back", new float[]{0.0f, 0.5f, 1.0f, 0.5f});
    }

    private void applyBlindSpotOverlayStyle(String side) {
        if (engine == null || blindSpotTargetIndex < 0) return;

        // 获取旋转角度
        int rotationDegrees = blindSpotConfig.getOverlayRotationDegrees(side);
        float rotationRadians = (float) (rotationDegrees * Math.PI / 180.0);

        // 获取圆角半径
        float cornerRadius = 0f;
        if (blindSpotConfig.isOverlayRoundedCornersEnabled()) {
            int width = blindSpotConfig.getWindowWidthDp();
            int height = blindSpotConfig.getWindowHeightDp();
            float density = getResources().getDisplayMetrics().density;
            int pixelWidth = Math.round(width * density);
            int pixelHeight = Math.round(height * density);
            cornerRadius = Math.min(pixelWidth, pixelHeight) * 0.07f;
        }

        engine.setExternalTargetOverlayStyle(blindSpotTargetIndex, rotationRadians, cornerRadius);
    }

    // ========== 补盲表面管理 ==========

    public void setBlindSpotSurface(Surface surface) {
        this.blindSpotSurface = surface;
        if (engine != null && blindSpotTargetIndex >= 0) {
            engine.setExternalTargetSurface(blindSpotTargetIndex, surface);
        }
    }

    public void setBlindSpotSurfaceTexture(SurfaceTexture surfaceTexture) {
        this.blindSpotSurfaceTexture = surfaceTexture;
        if (engine != null) {
            engine.setBlindSpotSurfaceTexture(surfaceTexture);
        }
    }

    // ========== 通知 ==========

    private Notification createNotification() {
        createNotificationChannel();

        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("EVCam 录制中")
                .setContentText("正在录制行车视频")
                .setSmallIcon(R.drawable.ic_nav_recording)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "录制服务",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("保持录制服务在后台运行");

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    // ========== 公共方法 ==========

    public RecordingController getRecordingController() {
        return recordingController;
    }

    public PanoramicEngine getEngine() {
        return engine;
    }

    public boolean isServiceRunning() {
        return isServiceRunning;
    }
}
