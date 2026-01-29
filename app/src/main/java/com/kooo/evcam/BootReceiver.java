package com.kooo.evcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

/**
 * 开机启动广播接收器
 * 监听系统开机广播，自动启动必要的服务
 * 
 * 关键改进（参考保活效果好的应用）：
 * 1. 直接启动前台服务，不依赖 Activity（Android 10+ 后台启动 Activity 受限）
 * 2. 简化启动逻辑，减少失败点
 * 3. 延迟启动，等待系统稳定
 * 4. 注册 TIME_TICK 广播，建立保活机制
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";
    
    // 开机后延迟启动时间（毫秒），等待系统稳定
    private static final long BOOT_DELAY_MS = 5000;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        AppLog.d(TAG, "收到广播: " + action);

        // 监听开机完成广播
        if (Intent.ACTION_BOOT_COMPLETED.equals(action) || 
            "android.intent.action.QUICKBOOT_POWERON".equals(action)) {
            
            AppLog.d(TAG, "系统开机完成！");
            
            // 立即启动前台服务（最重要！参考应用0的做法）
            startForegroundServiceImmediately(context);
            
            // 延迟执行其他初始化（等待系统稳定）
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                performDelayedInit(context);
            }, BOOT_DELAY_MS);
        }
    }
    
    /**
     * 立即启动前台服务（关键！）
     * 参考应用0：收到广播后直接启动服务，不做任何检查
     */
    private void startForegroundServiceImmediately(Context context) {
        try {
            AppLog.d(TAG, "立即启动前台服务...");
            
            // 直接启动前台服务，不检查任何配置
            // 这是保活应用的关键做法：无条件启动
            Intent serviceIntent = new Intent(context, CameraForegroundService.class);
            serviceIntent.putExtra("title", "EVCam 开机启动");
            serviceIntent.putExtra("content", "服务正在运行");
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent);
            } else {
                context.startService(serviceIntent);
            }
            
            AppLog.d(TAG, "前台服务启动成功");
        } catch (Exception e) {
            AppLog.e(TAG, "启动前台服务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 延迟执行的初始化任务
     * 等待系统稳定后再执行复杂的初始化
     */
    private void performDelayedInit(Context context) {
        AppLog.d(TAG, "执行延迟初始化...");
        
        try {
            // 注册 TIME_TICK 广播（建立每分钟唤醒机制）
            KeepAliveReceiver.registerTimeTick(context);
            AppLog.d(TAG, "TIME_TICK 广播已注册");
        } catch (Exception e) {
            AppLog.e(TAG, "注册 TIME_TICK 失败: " + e.getMessage(), e);
        }
        
        try {
            // 检查是否需要启动其他服务
            AppConfig appConfig = new AppConfig(context);
            
            // 启动 WorkManager 保活任务（车机必需，始终开启）
            KeepAliveManager.startKeepAliveWork(context);
            AppLog.d(TAG, "WorkManager 保活任务已启动");
            
            // 如果用户启用了开机自启动，尝试启动完整应用
            if (appConfig.isAutoStartOnBoot()) {
                AppLog.d(TAG, "尝试启动完整应用...");
                tryStartMainActivity(context);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "延迟初始化失败: " + e.getMessage(), e);
        }
        
        AppLog.d(TAG, "开机自启动初始化完成");
    }
    
    /**
     * 尝试启动 MainActivity
     * Android 10+ 后台启动 Activity 受限，可能失败，但不影响服务运行
     */
    private void tryStartMainActivity(Context context) {
        try {
            // 方案1：尝试启动透明 Activity
            Intent transparentIntent = new Intent(context, TransparentBootActivity.class);
            transparentIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK | 
                Intent.FLAG_ACTIVITY_NO_ANIMATION |
                Intent.FLAG_ACTIVITY_CLEAR_TOP
            );
            context.startActivity(transparentIntent);
            AppLog.d(TAG, "透明 Activity 已启动");
        } catch (Exception e) {
            AppLog.w(TAG, "启动 Activity 失败（Android 10+ 后台限制）: " + e.getMessage());
            // 失败也没关系，前台服务已经在运行了
        }
    }
}
