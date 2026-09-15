package com.kooo.evcam.heartbeat;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.SingleCamera;

import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 心跳推图管理器
 * 负责定时调度和生命周期管理
 */
public class HeartbeatManager {
    private static final String TAG = "HeartbeatManager";
    
    private final Context context;
    private final HeartbeatConfig config;
    private final HeartbeatImageProcessor imageProcessor;
    private final HeartbeatApiClient apiClient;
    private final Handler mainHandler;
    private final ExecutorService executor;
    
    // 状态
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean isForeground = new AtomicBoolean(false);
    private final AtomicBoolean isExecuting = new AtomicBoolean(false);
    private final AtomicBoolean isScreenOn = new AtomicBoolean(true);  // 屏幕是否亮起
    
    private Runnable heartbeatRunnable;
    
    // 相机列表（由 MainActivity 设置，使用强引用避免被GC）
    private List<SingleCamera> camerasList;
    
    // 状态提供者（由 MainActivity 设置，使用强引用避免被GC）
    private StatusProvider statusProvider;
    
    // Activity 控制器（用于息屏推图时控制 Activity）
    private ActivityController activityController;
    
    // 状态监听器
    private WeakReference<HeartbeatListener> listenerRef;
    
    // 息屏推图相关
    private Runnable screenOffHeartbeatRunnable;
    private static final long SCREEN_OFF_HEARTBEAT_DELAY_MS = 30000; // 息屏后30秒开始推图
    private volatile boolean wakeUpByHeartbeat = false;  // 是否由息屏推图唤醒的
    
    /**
     * App 状态提供者接口
     */
    public interface StatusProvider {
        /**
         * 获取 App 状态 JSON 字符串
         */
        String getAppStatusJson();
    }
    
    /**
     * Activity 控制器接口
     * 用于在息屏推图时控制 Activity 的唤醒和退后台
     */
    public interface ActivityController {
        /** 是否在后台 */
        boolean isInBackground();
        /** 是否正在录制 */
        boolean isRecording();
        /** 是否应该保持前台（如息屏录制模式） */
        boolean shouldKeepForeground();
        /** 唤醒 Activity 到前台 */
        void wakeUpToForeground();
        /** 退到后台 */
        void moveToBackground();
        /** 打开所有相机 */
        void openCameras();
        /** 关闭所有相机 */
        void closeCameras();
        /** 检查相机是否已连接 */
        boolean hasCamerasConnected();
    }
    
    /**
     * 心跳状态监听器
     */
    public interface HeartbeatListener {
        void onHeartbeatStarted();
        void onHeartbeatStopped();
        void onHeartbeatSuccess(long timestamp);
        void onHeartbeatFailed(String error);
    }
    
    public HeartbeatManager(Context context) {
        this.context = context.getApplicationContext();
        this.config = new HeartbeatConfig(context);
        this.imageProcessor = new HeartbeatImageProcessor();
        this.apiClient = new HeartbeatApiClient();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.executor = Executors.newSingleThreadExecutor();
        
        // 检测当前屏幕状态（开机时可能是息屏状态）
        detectInitialScreenState();
    }
    
    /**
     * 检测初始屏幕状态
     * 解决开机时屏幕已息屏但不会收到 ACTION_SCREEN_OFF 广播的问题
     */
    private void detectInitialScreenState() {
        try {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                boolean interactive = pm.isInteractive();
                isScreenOn.set(interactive);
                AppLog.d(TAG, "检测初始屏幕状态: " + (interactive ? "亮屏" : "息屏"));
            }
        } catch (Exception e) {
            AppLog.e(TAG, "检测屏幕状态失败", e);
        }
    }
    
    /**
     * 设置相机列表
     */
    public void setCameras(List<SingleCamera> cameras) {
        this.camerasList = cameras;
    }
    
    /**
     * 设置状态提供者
     */
    public void setStatusProvider(StatusProvider provider) {
        this.statusProvider = provider;
    }
    
    /**
     * 设置 Activity 控制器（用于息屏推图）
     */
    public void setActivityController(ActivityController controller) {
        this.activityController = controller;
    }
    
    /**
     * 设置监听器
     */
    public void setListener(HeartbeatListener listener) {
        this.listenerRef = new WeakReference<>(listener);
    }
    
    /**
     * 获取配置对象
     */
    public HeartbeatConfig getConfig() {
        return config;
    }
    
    /**
     * 配置变更时调用
     * 根据当前屏幕状态重新启动相应的推图模式
     */
    public void onConfigChanged() {
        AppLog.d(TAG, "配置变更，重新评估推图状态");
        
        // 停止所有推图
        stop();
        stopScreenOffHeartbeat();
        
        // 如果配置不完整，直接返回
        if (!config.isConfigured()) {
            AppLog.d(TAG, "配置不完整");
            return;
        }
        
        // 如果开启了"自动启动服务"，自动设置 enabled = true
        if (config.isAutoStartEnabled() && !config.isEnabled()) {
            AppLog.d(TAG, "自动启动服务已开启，自动启用心跳服务");
            config.setEnabled(true);
        }
        
        if (!config.isEnabled()) {
            AppLog.d(TAG, "心跳服务未启用");
            return;
        }
        
        // 根据屏幕状态启动相应推图
        if (isScreenOn.get()) {
            // 亮屏状态
            if (config.isScreenOnPushEnabled() && activityController != null && !activityController.isInBackground()) {
                mainHandler.postDelayed(this::start, 500);
            }
        } else {
            // 息屏状态
            if (config.isScreenOffPushEnabled()) {
                startScreenOffHeartbeat();
            }
        }
    }
    
    /**
     * 检查是否正在运行（亮屏推图或息屏推图）
     */
    public boolean isRunning() {
        // 亮屏推图定时器运行中，或息屏推图定时器运行中
        return isRunning.get() || screenOffHeartbeatRunnable != null;
    }
    
    /**
     * 检查亮屏推图是否正在运行
     */
    public boolean isScreenOnPushRunning() {
        return isRunning.get();
    }
    
    /**
     * 检查息屏推图是否正在运行
     */
    public boolean isScreenOffPushRunning() {
        return screenOffHeartbeatRunnable != null;
    }
    
    /**
     * 屏幕关闭时调用
     * 停止亮屏推图，启动息屏推图定时器
     */
    public void onScreenOff() {
        isScreenOn.set(false);
        AppLog.d(TAG, "屏幕状态: 息屏");
        
        // 停止亮屏推图
        pause();
        
        // 启动息屏推图
        startScreenOffHeartbeat();
    }
    
    /**
     * 屏幕打开时调用
     * 停止息屏推图，根据配置启动亮屏推图
     */
    public void onScreenOn() {
        isScreenOn.set(true);
        AppLog.d(TAG, "屏幕状态: 亮屏");
        
        // 停止息屏推图
        stopScreenOffHeartbeat();
    }
    
    /**
     * 获取屏幕状态
     */
    public boolean isScreenOn() {
        return isScreenOn.get();
    }
    
    // ==================== 息屏推图 ====================
    
    /**
     * 启动息屏推图定时器
     * 息屏后30秒开始，按设定间隔定时推图
     */
    private void startScreenOffHeartbeat() {
        if (!config.isEnabled() || !config.isConfigured() || !config.isScreenOffPushEnabled()) {
            AppLog.d(TAG, "息屏推图未启用或配置不完整");
            return;
        }
        
        // 取消已有的定时器
        stopScreenOffHeartbeat();
        
        AppLog.d(TAG, "息屏推图将在30秒后启动");
        
        // 30秒后开始第一次推图
        screenOffHeartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (isScreenOn.get()) {
                    AppLog.d(TAG, "屏幕已亮起，停止息屏推图");
                    return;
                }
                
                if (!config.isEnabled() || !config.isScreenOffPushEnabled()) {
                    AppLog.d(TAG, "息屏推图已禁用");
                    return;
                }
                
                // 执行推图
                executeScreenOffHeartbeat();
                
                // 调度下一次
                if (!isScreenOn.get() && config.isScreenOffPushEnabled()) {
                    mainHandler.postDelayed(this, config.getIntervalSeconds() * 1000L);
                }
            }
        };
        
        mainHandler.postDelayed(screenOffHeartbeatRunnable, SCREEN_OFF_HEARTBEAT_DELAY_MS);
    }
    
    /**
     * 停止息屏推图定时器
     */
    private void stopScreenOffHeartbeat() {
        if (screenOffHeartbeatRunnable != null) {
            mainHandler.removeCallbacks(screenOffHeartbeatRunnable);
            screenOffHeartbeatRunnable = null;
            AppLog.d(TAG, "息屏推图定时器已停止");
        }
    }
    
    /**
     * 执行息屏推图
     * 如果在后台，先唤醒到前台，推图完成后退回后台
     */
    private void executeScreenOffHeartbeat() {
        if (activityController == null) {
            AppLog.w(TAG, "ActivityController 未设置，无法执行息屏推图");
            return;
        }
        
        boolean inBackground = activityController.isInBackground();
        boolean hasCameras = activityController.hasCamerasConnected();
        
        AppLog.d(TAG, "执行息屏推图, isInBackground=" + inBackground + ", hasCameras=" + hasCameras);
        
        // 如果相机已连接，说明应用实际上在前台工作状态
        // （可能是被钉钉等远程命令唤醒的，但 isInBackground 值不准确）
        // 此时直接推图，不需要退后台
        if (hasCameras) {
            wakeUpByHeartbeat = false;
            AppLog.d(TAG, "息屏推图：相机已连接，直接推图不退后台");
            executeOnceInternal(false);
            return;
        }
        
        if (inBackground) {
            // 在后台且相机未连接：需要唤醒到前台，标记为息屏推图唤醒
            wakeUpByHeartbeat = true;
            wakeUpForHeartbeat();
        } else {
            // 在前台：直接推图，不需要退后台
            wakeUpByHeartbeat = false;
            executeOnceInternal(false);
        }
    }
    
    /**
     * 为息屏推图唤醒到前台
     * 流程：唤醒Activity → 等待 onResume 自动打开相机 → 推图 → 关闭相机 → 退后台
     */
    private void wakeUpForHeartbeat() {
        if (activityController == null) {
            return;
        }
        
        AppLog.d(TAG, "息屏推图：唤醒到前台");
        activityController.wakeUpToForeground();
        
        // 等待 Activity.onResume() 自动打开相机（onResume 中有 500ms 延迟打开）
        // 这里等待 2 秒，让 onResume 的相机打开流程完成，避免重复调用
        mainHandler.postDelayed(() -> {
            if (activityController == null) return;
            
            AppLog.d(TAG, "息屏推图：检查相机状态");
            
            // 检查相机是否已连接（由 onResume 打开）
            if (activityController.hasCamerasConnected()) {
                // 相机已就绪，直接推图
                AppLog.d(TAG, "息屏推图：相机已就绪");
                doHeartbeatAndReturnToBackground();
            } else {
                // 相机还没准备好，再等 2 秒
                AppLog.d(TAG, "息屏推图：等待相机就绪...");
                mainHandler.postDelayed(() -> {
                    if (activityController != null && activityController.hasCamerasConnected()) {
                        doHeartbeatAndReturnToBackground();
                    } else {
                        AppLog.w(TAG, "息屏推图：相机未能就绪，跳过本次推图");
                        returnToBackgroundAfterHeartbeat();
                    }
                }, 2000);
            }
        }, 2000);  // 等待 onResume 完成相机打开
    }
    
    /**
     * 执行推图并退回后台
     */
    private void doHeartbeatAndReturnToBackground() {
        AppLog.d(TAG, "息屏推图：执行推图");
        
        // 执行推图
        executor.execute(() -> {
            isExecuting.set(true);
            try {
                doHeartbeat();
            } finally {
                isExecuting.set(false);
                // 推图完成后退后台
                mainHandler.postDelayed(this::returnToBackgroundAfterHeartbeat, 2000);
            }
        });
    }
    
    /**
     * 推图完成后退回后台
     */
    private void returnToBackgroundAfterHeartbeat() {
        if (activityController == null) return;
        
        // 重置标志
        boolean wasWakeUpByHeartbeat = wakeUpByHeartbeat;
        wakeUpByHeartbeat = false;
        
        boolean currentlyInBackground = activityController.isInBackground();
        boolean screenOn = isScreenOn.get();
        boolean recording = activityController.isRecording();
        boolean keepForeground = activityController.shouldKeepForeground();
        
        AppLog.d(TAG, "returnToBackground 检查: wakeUpByHB=" + wasWakeUpByHeartbeat + 
                ", inBackground=" + currentlyInBackground + 
                ", screenOn=" + screenOn + 
                ", recording=" + recording + 
                ", keepForeground=" + keepForeground);
        
        // 只有由息屏推图唤醒的才考虑退后台
        if (!wasWakeUpByHeartbeat) {
            AppLog.d(TAG, "非息屏推图唤醒，不退后台");
            return;
        }
        
        // 检查应用当前是否在前台（如果在前台就不退）
        // 这可以防止：钉钉唤醒后，息屏推图又把应用退回后台
        if (!currentlyInBackground) {
            AppLog.d(TAG, "应用当前在前台，不退后台");
            return;
        }
        
        // 检查是否仍然息屏
        if (screenOn) {
            AppLog.d(TAG, "屏幕已亮起，不退后台");
            return;
        }
        
        // 检查是否正在录制
        if (recording) {
            AppLog.d(TAG, "正在录制中，不退后台");
            return;
        }
        
        // 检查是否应该保持前台（如息屏录制模式）
        if (keepForeground) {
            AppLog.d(TAG, "息屏录制模式，保持前台不退后台");
            return;
        }
        
        AppLog.d(TAG, "息屏推图完成，关闭相机并退后台");
        
        // 关闭相机
        activityController.closeCameras();
        
        // 退后台
        activityController.moveToBackground();
    }
    
    /**
     * 启动心跳（App 进入前台时调用）
     * 此方法用于亮屏状态下的推图，由 MainActivity.onResume() 调用
     * 注意：息屏状态下不会启动，息屏推图由 screenOffHeartbeat 单独管理
     */
    public void start() {
        AppLog.d(TAG, "start() 调用, enabled=" + config.isEnabled() + 
                ", configured=" + config.isConfigured() + 
                ", screenOn=" + isScreenOn.get() +
                ", screenOnPush=" + config.isScreenOnPushEnabled());
        
        if (!config.isEnabled()) {
            AppLog.d(TAG, "心跳推图功能未启用");
            return;
        }
        
        if (!config.isConfigured()) {
            AppLog.w(TAG, "心跳推图配置不完整: " + config.getConfigStatus());
            return;
        }
        
        // 息屏状态下不启动（息屏推图由 screenOffHeartbeat 管理）
        if (!isScreenOn.get()) {
            AppLog.d(TAG, "息屏状态，不启动亮屏推图");
            return;
        }
        
        // 检查亮屏推图开关
        if (!config.isScreenOnPushEnabled()) {
            AppLog.d(TAG, "亮屏推图未启用，跳过启动");
            return;
        }
        
        isForeground.set(true);
        
        if (isRunning.get()) {
            AppLog.d(TAG, "心跳已在运行中");
            return;
        }
        
        isRunning.set(true);
        AppLog.i(TAG, "心跳推图服务启动, 间隔: " + config.getIntervalSeconds() + "秒");
        
        notifyStarted();
        scheduleNextHeartbeat(true); // 立即执行第一次
    }
    
    /**
     * 暂停心跳（App 进入后台时调用）
     */
    public void pause() {
        AppLog.d(TAG, "pause() 调用");
        
        isForeground.set(false);
        
        if (heartbeatRunnable != null) {
            mainHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
        
        if (isRunning.getAndSet(false)) {
            AppLog.i(TAG, "心跳推图服务已暂停（进入后台）");
            notifyStopped();
        }
    }
    
    /**
     * 停止心跳（用户手动关闭或销毁时）
     */
    public void stop() {
        AppLog.d(TAG, "stop() 调用");
        
        isForeground.set(false);
        
        if (heartbeatRunnable != null) {
            mainHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
        
        if (isRunning.getAndSet(false)) {
            AppLog.i(TAG, "心跳推图服务已停止");
            notifyStopped();
        }
    }
    
    /**
     * 销毁管理器
     */
    public void destroy() {
        stop();
        stopScreenOffHeartbeat();
        executor.shutdown();
    }
    
    /**
     * 调度下一次心跳（仅用于亮屏推图）
     * 
     * @param immediate 是否立即执行（用于启动时）
     */
    private void scheduleNextHeartbeat(boolean immediate) {
        if (!isForeground.get() || !config.isEnabled()) {
            isRunning.set(false);
            return;
        }
        
        // 息屏状态下停止（息屏推图由 screenOffHeartbeat 管理）
        if (!isScreenOn.get()) {
            AppLog.d(TAG, "息屏状态，停止亮屏推图调度");
            isRunning.set(false);
            notifyStopped();
            return;
        }
        
        // 检查亮屏推图开关
        if (!config.isScreenOnPushEnabled()) {
            AppLog.d(TAG, "亮屏推图未启用，停止调度");
            isRunning.set(false);
            notifyStopped();
            return;
        }
        
        heartbeatRunnable = () -> {
            if (!isForeground.get() || !config.isEnabled()) {
                isRunning.set(false);
                notifyStopped();
                return;
            }
            
            // 息屏状态下停止
            if (!isScreenOn.get()) {
                AppLog.d(TAG, "息屏状态，停止亮屏推图");
                isRunning.set(false);
                notifyStopped();
                return;
            }
            
            // 检查亮屏推图开关
            if (!config.isScreenOnPushEnabled()) {
                AppLog.d(TAG, "亮屏推图未启用，停止");
                isRunning.set(false);
                notifyStopped();
                return;
            }
            
            executeHeartbeat();
            scheduleNextHeartbeat(false); // 递归调度
        };
        
        long delay = immediate ? 0 : config.getIntervalSeconds() * 1000L;
        mainHandler.postDelayed(heartbeatRunnable, delay);
    }
    
    /**
     * 执行一次心跳
     */
    private void executeHeartbeat() {
        // 防止重复执行
        if (isExecuting.getAndSet(true)) {
            AppLog.w(TAG, "上一次心跳还在执行中，跳过本次");
            return;
        }
        
        executor.execute(() -> {
            try {
                doHeartbeat();
            } finally {
                isExecuting.set(false);
            }
        });
    }
    
    /**
     * 实际执行心跳逻辑
     */
    private void doHeartbeat() {
        long startTime = System.currentTimeMillis();
        AppLog.d(TAG, "开始执行心跳...");
        
        try {
            // 1. 获取相机列表
            List<SingleCamera> cameras = camerasList;
            AppLog.d(TAG, "相机列表: " + (cameras == null ? "null" : cameras.size() + "个"));
            
            if (cameras == null || cameras.isEmpty()) {
                AppLog.w(TAG, "相机列表为空，跳过本次心跳");
                notifyFailed("Camera not ready");
                return;
            }
            
            // 过滤已连接的相机
            cameras = filterConnectedCameras(cameras);
            AppLog.d(TAG, "已连接相机: " + cameras.size() + "个");
            
            if (cameras.isEmpty()) {
                AppLog.w(TAG, "没有已连接的相机，跳过本次心跳");
                notifyFailed("Camera not connected");
                return;
            }
            
            // 2. 在主线程捕获图片（必须在主线程操作 TextureView）
            final List<SingleCamera> finalCameras = cameras;
            final Bitmap[] mergedHolder = new Bitmap[1];
            final boolean[] completed = new boolean[1];
            
            mainHandler.post(() -> {
                synchronized (mergedHolder) {
                    try {
                        mergedHolder[0] = imageProcessor.captureAndMerge(finalCameras);
                    } catch (Exception e) {
                        AppLog.e(TAG, "捕获图片异常: " + e.getMessage());
                    }
                    completed[0] = true;
                    mergedHolder.notifyAll();
                }
            });
            
            // 等待主线程完成
            synchronized (mergedHolder) {
                if (!completed[0]) {
                    try {
                        mergedHolder.wait(5000); // 最多等待5秒
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        notifyFailed("Capture interrupted");
                        return;
                    }
                }
            }
            
            Bitmap merged = mergedHolder[0];
            if (merged == null) {
                AppLog.w(TAG, "Photo capture failed");
                notifyFailed("Photo capture failed");
                return;
            }
            
            int imageWidth = merged.getWidth();
            int imageHeight = merged.getHeight();
            int cameraCount = cameras.size();
            
            // 3. 压缩图片
            byte[] imageBytes = imageProcessor.compressToTargetSize(merged, config.getTargetSizeKB());
            merged.recycle();
            
            if (imageBytes == null || imageBytes.length == 0) {
                AppLog.w(TAG, "Photo compression failed");
                notifyFailed("Photo compression failed");
                return;
            }
            
            // 4. 获取 App 状态
            String appStatus = null;
            if (statusProvider != null) {
                appStatus = statusProvider.getAppStatusJson();
            }
            
            // 5. 发送请求
            HeartbeatApiClient.HeartbeatResult result = apiClient.sendHeartbeat(
                    config.getServerUrl(),
                    config.getVehicleId(),
                    config.getSecretKey(),
                    imageBytes,
                    imageWidth,
                    imageHeight,
                    cameraCount,
                    appStatus
            );
            
            // 6. 更新统计
            long now = System.currentTimeMillis();
            config.setLastUploadTime(now);
            
            if (result.success) {
                config.incrementSuccessCount();
                long duration = now - startTime;
                AppLog.i(TAG, "心跳成功，耗时: " + duration + "ms, 图片: " + (imageBytes.length / 1024) + "KB");
                notifySuccess(now);
            } else {
                config.incrementFailCount();
                config.setLastError(result.message);
                AppLog.w(TAG, "心跳失败: " + result.message);
                notifyFailed(result.message);
            }
            
        } catch (Exception e) {
            config.incrementFailCount();
            config.setLastError(e.getMessage());
            AppLog.e(TAG, "心跳执行异常: " + e.getMessage(), e);
            notifyFailed(e.getMessage());
        }
    }
    
    /**
     * 过滤已连接的相机
     */
    private List<SingleCamera> filterConnectedCameras(List<SingleCamera> cameras) {
        java.util.ArrayList<SingleCamera> connected = new java.util.ArrayList<>();
        for (SingleCamera camera : cameras) {
            if (camera != null && camera.isConnected()) {
                connected.add(camera);
            }
        }
        return connected;
    }
    
    /**
     * 手动执行一次心跳（用于测试）
     */
    /**
     * 执行一次心跳（供手动测试按钮调用）
     */
    public void executeOnce() {
        executeOnceInternal(true);
    }
    
    /**
     * 内部执行一次心跳
     * @param isManualTest 是否是手动测试
     */
    private void executeOnceInternal(boolean isManualTest) {
        if (isExecuting.get()) {
            AppLog.w(TAG, "心跳正在执行中");
            return;
        }
        
        if (isManualTest) {
            AppLog.i(TAG, "手动执行心跳测试");
        }
        
        executor.execute(() -> {
            isExecuting.set(true);
            try {
                doHeartbeat();
            } finally {
                isExecuting.set(false);
            }
        });
    }
    
    // ==================== 通知方法 ====================
    
    private void notifyStarted() {
        mainHandler.post(() -> {
            HeartbeatListener listener = listenerRef != null ? listenerRef.get() : null;
            if (listener != null) {
                listener.onHeartbeatStarted();
            }
        });
    }
    
    private void notifyStopped() {
        mainHandler.post(() -> {
            HeartbeatListener listener = listenerRef != null ? listenerRef.get() : null;
            if (listener != null) {
                listener.onHeartbeatStopped();
            }
        });
    }
    
    private void notifySuccess(long timestamp) {
        mainHandler.post(() -> {
            HeartbeatListener listener = listenerRef != null ? listenerRef.get() : null;
            if (listener != null) {
                listener.onHeartbeatSuccess(timestamp);
            }
        });
    }
    
    private void notifyFailed(String error) {
        mainHandler.post(() -> {
            HeartbeatListener listener = listenerRef != null ? listenerRef.get() : null;
            if (listener != null) {
                listener.onHeartbeatFailed(error);
            }
        });
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 格式化时间戳
     */
    public static String formatTimestamp(long timestamp) {
        if (timestamp <= 0) {
            return "-";
        }
        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
        return sdf.format(new Date(timestamp));
    }
}
