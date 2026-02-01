package com.kooo.evcam;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Build;
import android.view.accessibility.AccessibilityEvent;

import androidx.core.app.NotificationCompat;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 增强版保活无障碍服务
 * 
 * 用途：提高应用后台运行优先级，防止被系统清理
 * 
 * 保活策略：
 * 1. 辅助服务本身由系统管理，具有最高优先级
 * 2. 配合前台通知，双重保护
 * 3. 心跳定时器，防止进程休眠
 * 4. 自动拉起前台服务
 * 
 * 注意：此服务不会读取或操作任何用户界面内容，仅利用权限提升进程优先级
 */
public class KeepAliveAccessibilityService extends AccessibilityService {
    private static final String TAG = "KeepAliveAccessibility";
    private static final String CHANNEL_ID = "keep_alive_channel";
    private static final int NOTIFICATION_ID = 9527;
    private static final long HEARTBEAT_INTERVAL_MS = 60000; // 60秒心跳
    
    private static KeepAliveAccessibilityService instance;
    private static boolean isServiceRunning = false;
    
    private ScheduledExecutorService heartbeatExecutor;
    private long startTime;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        isServiceRunning = true;
        startTime = System.currentTimeMillis();
        
        AppLog.d(TAG, "无障碍服务已启动（增强保活模式）");
        
        // 注意：辅助服务本身就是系统级服务，不需要前台通知也有最高优先级
        // 前台通知由 CameraForegroundService 提供，避免重复通知
        // startForegroundNotification();  // 已移除，减少重复通知
        
        // 启动心跳定时器
        startHeartbeat();
        
        // 动态注册 TIME_TICK 广播（每分钟触发）
        registerTimeTickBroadcast();
        
        // 确保前台服务也在运行
        ensureForegroundServiceRunning();
    }
    
    /**
     * 动态注册 TIME_TICK 广播
     * TIME_TICK 在 Android 8.0+ 只能动态注册，每分钟触发一次
     * 这是保活的关键手段之一
     */
    private void registerTimeTickBroadcast() {
        try {
            KeepAliveReceiver.registerTimeTick(this);
            AppLog.d(TAG, "TIME_TICK 广播已注册");
        } catch (Exception e) {
            AppLog.e(TAG, "注册 TIME_TICK 广播失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 注销 TIME_TICK 广播
     */
    private void unregisterTimeTickBroadcast() {
        try {
            KeepAliveReceiver.unregisterTimeTick(this);
            AppLog.d(TAG, "TIME_TICK 广播已注销");
        } catch (Exception e) {
            AppLog.e(TAG, "注销 TIME_TICK 广播失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建前台通知
     * 辅助服务配合前台通知可以获得最高的进程优先级
     */
    private void startForegroundNotification() {
        try {
            // 创建通知渠道（Android 8.0+）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "保活服务",
                        NotificationManager.IMPORTANCE_LOW  // 低重要性，不打扰用户
                );
                channel.setDescription("维持应用后台运行");
                channel.enableLights(false);
                channel.enableVibration(false);
                channel.setSound(null, null);
                channel.setShowBadge(false);
                
                NotificationManager manager = getSystemService(NotificationManager.class);
                if (manager != null) {
                    manager.createNotificationChannel(channel);
                }
            }
            
            // 创建点击通知时打开应用的 Intent
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            
            // 构建通知
            Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                    .setSmallIcon(android.R.drawable.ic_menu_camera)
                    .setContentTitle("EVCam")
                    .setContentText("保活服务运行中")
                    .setPriority(NotificationCompat.PRIORITY_LOW)
                    .setOngoing(true)
                    .setShowWhen(false)
                    .setContentIntent(pendingIntent)
                    .build();
            
            // 启动前台服务（辅助服务也支持 startForeground）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForeground(NOTIFICATION_ID, notification);
                AppLog.d(TAG, "前台通知已启动");
            }
        } catch (Exception e) {
            AppLog.e(TAG, "启动前台通知失败: " + e.getMessage(), e);
        }
    }

    /**
     * 启动心跳定时器
     * 定期执行任务，防止进程被系统判定为空闲而清理
     */
    private void startHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            return;
        }
        
        heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
        heartbeatExecutor.scheduleWithFixedDelay(() -> {
            try {
                long runningMinutes = (System.currentTimeMillis() - startTime) / 60000;
                AppLog.d(TAG, "心跳: 服务已运行 " + runningMinutes + " 分钟");
                
                // 检查并确保前台服务运行
                ensureForegroundServiceRunning();
            } catch (Exception e) {
                AppLog.e(TAG, "心跳任务异常: " + e.getMessage(), e);
            }
        }, 5000, HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
        
        AppLog.d(TAG, "心跳定时器已启动，间隔: " + (HEARTBEAT_INTERVAL_MS / 1000) + "秒");
    }

    /**
     * 停止心跳定时器
     */
    private void stopHeartbeat() {
        if (heartbeatExecutor != null && !heartbeatExecutor.isShutdown()) {
            heartbeatExecutor.shutdown();
            try {
                if (!heartbeatExecutor.awaitTermination(1, TimeUnit.SECONDS)) {
                    heartbeatExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                heartbeatExecutor.shutdownNow();
            }
            heartbeatExecutor = null;
            AppLog.d(TAG, "心跳定时器已停止");
        }
    }

    /**
     * 确保前台服务正在运行
     * 辅助服务拉起前台服务，形成双重保活
     */
    private void ensureForegroundServiceRunning() {
        try {
            // 启动摄像头前台服务
            CameraForegroundService.start(this, "EVCam 后台运行中", "点击返回应用");
        } catch (Exception e) {
            AppLog.e(TAG, "拉起前台服务失败: " + e.getMessage(), e);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不处理任何无障碍事件，仅用于保活
        // 虽然配置允许获取窗口内容，但代码中不会实际读取
        // 这样既能获得高优先级，又能保护用户隐私
    }

    @Override
    public void onInterrupt() {
        AppLog.d(TAG, "无障碍服务被中断");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.d(TAG, "无障碍服务 onStartCommand");
        return START_STICKY; // 确保被杀后重启
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AppLog.d(TAG, "无障碍服务已连接到系统");
        
        // 服务连接后再次确保所有保活组件运行
        ensureForegroundServiceRunning();
    }

    @Override
    public void onDestroy() {
        AppLog.d(TAG, "无障碍服务正在销毁...");
        
        // 停止心跳
        stopHeartbeat();
        
        // 注销 TIME_TICK 广播
        unregisterTimeTickBroadcast();
        
        // 前台通知由 CameraForegroundService 管理，这里不需要停止
        
        instance = null;
        isServiceRunning = false;
        
        super.onDestroy();
        AppLog.d(TAG, "无障碍服务已销毁");
    }

    /**
     * 检查服务是否正在运行
     */
    public static boolean isRunning() {
        return isServiceRunning && instance != null;
    }

    /**
     * 获取服务实例
     */
    public static KeepAliveAccessibilityService getInstance() {
        return instance;
    }
    
    /**
     * 获取服务运行时长（分钟）
     */
    public static long getRunningMinutes() {
        if (instance != null) {
            return (System.currentTimeMillis() - instance.startTime) / 60000;
        }
        return 0;
    }
}
