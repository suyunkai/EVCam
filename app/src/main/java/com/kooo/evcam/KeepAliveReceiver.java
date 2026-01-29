package com.kooo.evcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 保活广播接收器
 * 
 * 监听多种系统广播，确保应用服务持续运行：
 * - TIME_TICK: 每分钟触发（需动态注册）
 * - USER_PRESENT: 用户解锁屏幕
 * - SCREEN_ON: 屏幕亮起
 * - CONNECTIVITY_CHANGE: 网络状态变化
 * - BOOT_COMPLETED: 开机完成（由 BootReceiver 处理）
 * 
 * 策略：每次收到广播时检查并确保服务运行
 */
public class KeepAliveReceiver extends BroadcastReceiver {
    private static final String TAG = "KeepAliveReceiver";
    
    // 自定义广播 Action，用于手动触发保活检查
    public static final String ACTION_KEEP_ALIVE = "com.kooo.evcam.ACTION_KEEP_ALIVE";
    
    private static KeepAliveReceiver timeTickReceiver;
    private static boolean isTimeTickRegistered = false;

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        
        String action = intent.getAction();
        AppLog.d(TAG, "收到广播: " + action);
        
        // 保活功能已改为始终开启（车机必需）
        
        switch (action) {
            case Intent.ACTION_TIME_TICK:
                // 每分钟触发，轻量级检查
                onTimeTick(context);
                break;
                
            case Intent.ACTION_USER_PRESENT:
                // 用户解锁屏幕
                AppLog.d(TAG, "用户解锁屏幕，检查服务状态");
                ensureServicesRunning(context);
                break;
                
            case Intent.ACTION_SCREEN_ON:
                // 屏幕亮起
                AppLog.d(TAG, "屏幕亮起，检查服务状态");
                ensureServicesRunning(context);
                break;
                
            case "android.net.conn.CONNECTIVITY_CHANGE":
                // 网络状态变化
                AppLog.d(TAG, "网络状态变化，检查服务状态");
                ensureServicesRunning(context);
                break;
                
            case ACTION_KEEP_ALIVE:
                // 手动触发的保活检查
                AppLog.d(TAG, "收到保活检查请求");
                ensureServicesRunning(context);
                break;
                
            default:
                AppLog.d(TAG, "收到其他广播: " + action);
                ensureServicesRunning(context);
                break;
        }
    }
    
    /**
     * TIME_TICK 处理（每分钟调用）
     * 使用轻量级检查，避免频繁操作
     */
    private void onTimeTick(Context context) {
        // 检查无障碍服务状态
        boolean accessibilityRunning = KeepAliveAccessibilityService.isRunning();
        
        if (accessibilityRunning) {
            // 无障碍服务运行中，只需简单日志
            long runningMinutes = KeepAliveAccessibilityService.getRunningMinutes();
            if (runningMinutes % 5 == 0) {  // 每5分钟输出一次详细日志
                AppLog.d(TAG, "TIME_TICK: 无障碍服务已运行 " + runningMinutes + " 分钟");
            }
        } else {
            // 无障碍服务未运行，尝试拉起前台服务
            AppLog.d(TAG, "TIME_TICK: 无障碍服务未运行，尝试拉起前台服务");
            ensureServicesRunning(context);
        }
    }
    
    /**
     * 确保所有保活服务正在运行
     */
    private void ensureServicesRunning(Context context) {
        try {
            // 启动前台服务
            CameraForegroundService.start(context, "EVCam 后台运行中", "点击返回应用");
            AppLog.d(TAG, "已请求启动前台服务");
        } catch (Exception e) {
            AppLog.e(TAG, "启动服务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 动态注册 TIME_TICK 广播接收器
     * TIME_TICK 在 Android 8.0+ 只能动态注册
     * 
     * @param context 上下文
     */
    public static synchronized void registerTimeTick(Context context) {
        if (isTimeTickRegistered) {
            AppLog.d(TAG, "TIME_TICK 接收器已注册，跳过");
            return;
        }
        
        try {
            timeTickReceiver = new KeepAliveReceiver();
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getApplicationContext().registerReceiver(
                        timeTickReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.getApplicationContext().registerReceiver(timeTickReceiver, filter);
            }
            
            isTimeTickRegistered = true;
            AppLog.d(TAG, "TIME_TICK 接收器已动态注册（每分钟触发）");
        } catch (Exception e) {
            AppLog.e(TAG, "注册 TIME_TICK 接收器失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 注销 TIME_TICK 广播接收器
     * 
     * @param context 上下文
     */
    public static synchronized void unregisterTimeTick(Context context) {
        if (!isTimeTickRegistered || timeTickReceiver == null) {
            return;
        }
        
        try {
            context.getApplicationContext().unregisterReceiver(timeTickReceiver);
            timeTickReceiver = null;
            isTimeTickRegistered = false;
            AppLog.d(TAG, "TIME_TICK 接收器已注销");
        } catch (Exception e) {
            AppLog.e(TAG, "注销 TIME_TICK 接收器失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 检查 TIME_TICK 是否已注册
     */
    public static boolean isTimeTickRegistered() {
        return isTimeTickRegistered;
    }
    
    /**
     * 发送保活检查广播
     * 可以在任何地方调用此方法触发保活检查
     * 
     * @param context 上下文
     */
    public static void sendKeepAliveCheck(Context context) {
        try {
            Intent intent = new Intent(ACTION_KEEP_ALIVE);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
        } catch (Exception e) {
            AppLog.e(TAG, "发送保活检查广播失败: " + e.getMessage(), e);
        }
    }
}
