package com.kooo.evcam;


import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.MultiCameraManager;
// import android.app.AlarmManager;  // 已移除，使用 TIME_TICK 替代
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
// import android.os.SystemClock;  // 已移除 AlarmManager
import android.util.Log;

import androidx.core.app.NotificationCompat;

/**
 * 前台服务，用于在后台使用摄像头
 * Android 11+ 要求后台使用摄像头时必须有前台服务
 * 
 * 增强保活功能：
 * - 当无障碍服务未开启时，此服务会动态注册 TIME_TICK 广播
 * - TIME_TICK 每分钟触发一次，可以保持应用活跃
 * - onTaskRemoved: 用户滑动清除应用时自动重启
 * - onDestroy: 服务被杀时发送延迟重启广播
 * - WakeLock: 防止系统休眠（需用户开启）
 */
public class CameraForegroundService extends Service {
    private static final String TAG = "CameraForegroundService";
    private static final String CHANNEL_ID = "camera_service_channel";
    private static final int NOTIFICATION_ID = 1001;
    
    // 服务重启延迟时间
    private static final long RESTART_DELAY_MS = 1000;

    private static final long CAMERA_REPAIR_INTERVAL_MS = 10000;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Runnable cameraRepairRunnable;

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.d(TAG, "Service created");
        createNotificationChannel();
        
        // 如果无障碍服务未运行，则在此注册 TIME_TICK 广播
        registerTimeTickIfNeeded();
        
        // 获取 WakeLock 防止系统休眠（车机必须）
        acquireWakeLock();
        
        // 启动远程服务（钉钉/Telegram）
        // 这样远程服务不依赖 MainActivity，即使 Activity 被杀也能继续运行
        startRemoteServicesIfNeeded();

        startCameraRepairLoop();
    }
    
    /**
     * 启动远程服务（如果配置了自动启动）
     * 这是轻量优化的核心：远程服务在 Service 中启动，不依赖 MainActivity
     */
    private void startRemoteServicesIfNeeded() {
        try {
            AppConfig appConfig = new AppConfig(this);
            // 只有开启了开机自启动才启动远程服务和悬浮窗
            if (appConfig.isAutoStartOnBoot()) {
                AppLog.d(TAG, "开机自启动已开启，从 Service 启动远程服务...");
                RemoteServiceManager.getInstance().startRemoteServicesFromService(this);
                
                // 启动悬浮窗（如果已启用）
                if (appConfig.isFloatingWindowEnabled()) {
                    AppLog.d(TAG, "悬浮窗已启用，从 Service 启动悬浮窗...");
                    FloatingWindowService.start(this);
                }
                
                // 启动补盲选项服务 (副屏/主屏悬浮窗/转向灯联动)
                if (appConfig.isSecondaryDisplayEnabled() || appConfig.isMainFloatingEnabled() || appConfig.isTurnSignalLinkageEnabled()) {
                    AppLog.d(TAG, "补盲选项已启用，从 Service 启动...");
                    BlindSpotService.update(this);
                }
                
                // 如果启用了自动录制，启动 MainActivity
                // 这确保杀后台重启后也能自动录制（与开机启动行为一致）
                if (appConfig.isAutoStartRecording()) {
                    startMainActivityForAutoRecording();
                }
            } else {
                AppLog.d(TAG, "开机自启动未开启，跳过远程服务启动");
            }
        } catch (Exception e) {
            AppLog.e(TAG, "启动远程服务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 启动 MainActivity 进行自动录制
     * 用于：
     * 1. 杀后台后服务重启时恢复自动录制
     * 2. 与开机启动（TransparentBootActivity）行为保持一致
     */
    private void startMainActivityForAutoRecording() {
        try {
            // 检查 MainActivity 是否已经在运行
            // 通过检查静态引用判断（避免重复启动）
            if (MainActivity.getInstance() != null) {
                AppLog.d(TAG, "MainActivity 已在运行，跳过启动");
                return;
            }
            
            AppLog.d(TAG, "自动录制已启用，启动 MainActivity（后台模式）...");
            
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            mainIntent.putExtra("auto_start_from_boot", true);  // 复用开机自启动的逻辑
            mainIntent.putExtra("silent_mode", true);
            mainIntent.putExtra("from_service_restart", true);  // 标记来自服务重启
            startActivity(mainIntent);
            
            AppLog.d(TAG, "MainActivity 已启动（用于自动录制）");
        } catch (Exception e) {
            AppLog.e(TAG, "启动 MainActivity 失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 获取 WakeLock 防止系统休眠
     * 只有开启"开机自启动"时才获取，因为 WakeLock 会阻止 CPU 休眠
     * 用途：
     * 1. 息屏状态下继续录制
     * 2. 保持 WebSocket 连接接收远程命令
     * 3. 执行远程拍照/录制任务
     */
    private void acquireWakeLock() {
        try {
            AppConfig appConfig = new AppConfig(this);
            if (appConfig.isAutoStartOnBoot()) {
                WakeUpHelper.acquirePersistentWakeLock(this);
                AppLog.d(TAG, "WakeLock acquired (开机自启动已开启)");
            } else {
                // 如果开机自启动关闭，释放可能存在的 WakeLock
                WakeUpHelper.releasePersistentWakeLock();
                AppLog.d(TAG, "WakeLock not acquired (开机自启动未开启)");
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to handle WakeLock: " + e.getMessage(), e);
        }
    }
    
    /**
     * 如果无障碍服务未运行，则注册 TIME_TICK 广播
     * 作为备份保活方案（每分钟触发，比 AlarmManager 更频繁更可靠）
     */
    private void registerTimeTickIfNeeded() {
        if (!KeepAliveAccessibilityService.isRunning() && !KeepAliveReceiver.isTimeTickRegistered()) {
            AppLog.d(TAG, "无障碍服务未运行，在前台服务中注册 TIME_TICK");
            KeepAliveReceiver.registerTimeTick(this);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.d(TAG, "Service started");
        
        // 每次启动时检查并注册 TIME_TICK
        registerTimeTickIfNeeded();
        
        // 确保 WakeLock 已获取
        acquireWakeLock();
        
        // 确保远程服务和悬浮窗已启动（处理 START_STICKY 自动重启的情况）
        // onCreate 可能不会被调用（服务自动恢复时），所以这里也要检查
        ensureRemoteServicesStarted();
        startCameraRepairLoop();

        // 从Intent获取通知内容，如果没有则使用默认内容
        String title = intent != null ? intent.getStringExtra("title") : null;
        String content = intent != null ? intent.getStringExtra("content") : null;

        if (title == null) {
            title = "摄像头服务运行中";
        }
        if (content == null) {
            content = "正在处理远程拍照/录制请求";
        }

        // 创建通知
        Notification notification = createNotification(title, content);

        // 启动前台服务
        startForeground(NOTIFICATION_ID, notification);

        return START_STICKY;
    }
    
    /**
     * 确保远程服务和悬浮窗已启动
     * 用于处理 START_STICKY 自动重启的情况（此时 onCreate 不会被调用）
     */
    private void ensureRemoteServicesStarted() {
        try {
            AppConfig appConfig = new AppConfig(this);
            if (!appConfig.isAutoStartOnBoot()) {
                return;  // 未开启开机自启动，跳过
            }
            
            // 检查并启动悬浮窗
            if (appConfig.isFloatingWindowEnabled() && !FloatingWindowService.isRunning()) {
                AppLog.d(TAG, "悬浮窗未运行，重新启动...");
                FloatingWindowService.start(this);
            }
            
            // 检查并启动远程服务（如果未运行）
            RemoteServiceManager serviceManager = RemoteServiceManager.getInstance();
            if (!serviceManager.hasAnyServiceRunning()) {
                AppLog.d(TAG, "远程服务未运行，重新启动...");
                serviceManager.startRemoteServicesFromService(this);
            }
            
            // 检查并启动 MainActivity（如果启用了自动录制且 Activity 未运行）
            if (appConfig.isAutoStartRecording() && MainActivity.getInstance() == null) {
                startMainActivityForAutoRecording();
            }
        } catch (Exception e) {
            AppLog.e(TAG, "确保服务启动失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void onDestroy() {
        AppLog.d(TAG, "Service destroyed - 尝试重启...");
        stopCameraRepairLoop();
        
        // 服务被杀时，发送延迟重启广播
        scheduleServiceRestart();
        
        super.onDestroy();
    }

    private void startCameraRepairLoop() {
        stopCameraRepairLoop();
        cameraRepairRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    MainActivity mainActivity = MainActivity.getInstance();
                    if (mainActivity != null) {
                        MultiCameraManager cameraManager = mainActivity.getCameraManager();
                        if (cameraManager != null) {
                            int repaired = cameraManager.checkAndRepairCameras();
                            if (repaired > 0) {
                                AppLog.w(TAG, "Camera repair triggered for " + repaired + " cameras");
                            }
                        }
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "Camera repair loop error: " + e.getMessage(), e);
                }
                mainHandler.postDelayed(this, CAMERA_REPAIR_INTERVAL_MS);
            }
        };
        mainHandler.postDelayed(cameraRepairRunnable, CAMERA_REPAIR_INTERVAL_MS);
    }

    private void stopCameraRepairLoop() {
        if (cameraRepairRunnable != null) {
            mainHandler.removeCallbacks(cameraRepairRunnable);
            cameraRepairRunnable = null;
        }
    }
    
    /**
     * 当用户从最近任务中滑动清除应用时调用
     * 这是保活的关键：在被清除时重新启动服务
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        AppLog.d(TAG, "onTaskRemoved - 应用被从最近任务清除，尝试重启服务...");
        
        // 立即重启服务
        scheduleServiceRestart();
        
        super.onTaskRemoved(rootIntent);
    }
    
    /**
     * 调度服务重启
     * 使用 Handler 延迟重启，避免立即重启被系统拦截
     */
    private void scheduleServiceRestart() {
        try {
            // 方案1：使用 Handler 延迟重启
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                try {
                    AppLog.d(TAG, "执行延迟重启...");
                    start(getApplicationContext(), "EVCam", "服务自动重启");
                } catch (Exception e) {
                    AppLog.e(TAG, "延迟重启失败: " + e.getMessage(), e);
                }
            }, RESTART_DELAY_MS);
            
            // 方案2：发送保活广播（备份）
            KeepAliveReceiver.sendKeepAliveCheck(getApplicationContext());
            
        } catch (Exception e) {
            AppLog.e(TAG, "调度重启失败: " + e.getMessage(), e);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 创建通知渠道（Android 8.0+）
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "摄像头服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("用于后台拍照和录制");
            channel.setShowBadge(false);

            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }

    /**
     * 创建通知
     */
    private Notification createNotification(String title, String content) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                notificationIntent,
                PendingIntent.FLAG_IMMUTABLE
        );

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    /**
     * 更新通知内容
     */
    public void updateNotification(String title, String content) {
        Notification notification = createNotification(title, content);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, notification);
        }
    }

    /**
     * 静态方法：启动前台服务
     * @param context 上下文
     * @param title 通知标题
     * @param content 通知内容
     */
    public static void start(Context context, String title, String content) {
        Intent intent = new Intent(context, CameraForegroundService.class);
        intent.putExtra("title", title);
        intent.putExtra("content", content);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
        AppLog.d(TAG, "Starting foreground service: " + title);
    }

    /**
     * 静态方法：停止前台服务
     * @param context 上下文
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, CameraForegroundService.class);
        context.stopService(intent);
        AppLog.d(TAG, "Stopping foreground service");
    }
}
