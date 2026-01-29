package com.kooo.evcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;

/**
 * 保活广播接收器（增强版）
 * 
 * 策略：广撒网，只要系统有任何动静就唤醒（参考 Macrodroid）
 * 
 * 监听的广播类型：
 * 
 * 【屏幕相关】- 车机点火必亮屏，最稳的触发信号
 * - SCREEN_ON: 屏幕亮起
 * - SCREEN_OFF: 屏幕关闭
 * - USER_PRESENT: 用户解锁
 * 
 * 【电源相关】- 车机点火必通电，非常可靠
 * - ACTION_POWER_CONNECTED: 电源接通
 * - ACTION_POWER_DISCONNECTED: 电源断开
 * - BATTERY_CHANGED/LOW/OKAY: 电池状态
 * 
 * 【蓝牙相关】- 车机启动会自动连手机蓝牙
 * - STATE_CHANGED: 蓝牙开/关
 * - CONNECTION_STATE_CHANGED: 蓝牙连接状态
 * - ACL_CONNECTED/DISCONNECTED: 蓝牙设备连接/断开
 * 
 * 【USB/存储相关】- 插U盘触发
 * - MEDIA_MOUNTED/UNMOUNTED: 存储挂载/卸载
 * - USB_DEVICE_ATTACHED/DETACHED: USB设备
 * 
 * 【网络相关】
 * - CONNECTIVITY_CHANGE: 网络状态变化
 * - WIFI_STATE_CHANGE: WiFi状态
 * 
 * 【其他】
 * - TIME_TICK: 每分钟触发（需动态注册）
 * - TIMEZONE_CHANGED/TIME_SET: 时间变化
 * - LOCALE_CHANGED: 语言变化
 * - AIRPLANE_MODE: 飞行模式
 * - HEADSET_PLUG: 耳机插拔
 * - MY_PACKAGE_REPLACED: 应用更新后重新激活
 */
public class KeepAliveReceiver extends BroadcastReceiver {
    private static final String TAG = "KeepAliveReceiver";
    
    // 自定义广播 Action，用于手动触发保活检查
    public static final String ACTION_KEEP_ALIVE = "com.kooo.evcam.ACTION_KEEP_ALIVE";
    
    private static KeepAliveReceiver timeTickReceiver;
    private static boolean isTimeTickRegistered = false;
    
    // 上次触发时间，用于防止短时间内重复触发
    private static long lastTriggerTime = 0;
    private static final long MIN_TRIGGER_INTERVAL = 3000; // 最小触发间隔 3 秒

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }
        
        String action = intent.getAction();
        
        // 保活功能已改为始终开启（车机必需）
        
        switch (action) {
            // ========== 屏幕相关（车机点火必亮屏，最稳触发） ==========
            case Intent.ACTION_SCREEN_ON:
                AppLog.d(TAG, "【屏幕】屏幕亮起（点火信号）");
                ensureServicesRunning(context, "屏幕亮起");
                break;
                
            case Intent.ACTION_SCREEN_OFF:
                AppLog.d(TAG, "【屏幕】屏幕关闭（熄火/息屏）");
                ensureServicesRunning(context, "屏幕关闭");
                break;
                
            case Intent.ACTION_USER_PRESENT:
                AppLog.d(TAG, "【屏幕】用户解锁屏幕");
                ensureServicesRunning(context, "用户解锁");
                break;
                
            // ========== 电源相关（车机点火必通电） ==========
            case Intent.ACTION_POWER_CONNECTED:
                AppLog.d(TAG, "【电源】电源接通（点火信号）");
                ensureServicesRunning(context, "电源接通");
                break;
                
            case Intent.ACTION_POWER_DISCONNECTED:
                AppLog.d(TAG, "【电源】电源断开（熄火信号）");
                ensureServicesRunning(context, "电源断开");
                break;
                
            case Intent.ACTION_BATTERY_CHANGED:
                // 电池状态变化频繁，静默处理
                ensureServicesRunningQuiet(context);
                break;
                
            case Intent.ACTION_BATTERY_LOW:
                AppLog.d(TAG, "【电源】电量低");
                ensureServicesRunning(context, "电量低");
                break;
                
            case Intent.ACTION_BATTERY_OKAY:
                AppLog.d(TAG, "【电源】电量恢复正常");
                ensureServicesRunning(context, "电量正常");
                break;
                
            // ========== 蓝牙相关（车机启动会自动连蓝牙） ==========
            case "android.bluetooth.adapter.action.STATE_CHANGED":
                AppLog.d(TAG, "【蓝牙】蓝牙状态改变");
                ensureServicesRunning(context, "蓝牙状态变化");
                break;
                
            case "android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED":
                AppLog.d(TAG, "【蓝牙】蓝牙连接状态改变");
                ensureServicesRunning(context, "蓝牙连接变化");
                break;
                
            case "android.bluetooth.device.action.ACL_CONNECTED":
                AppLog.d(TAG, "【蓝牙】蓝牙设备已连接");
                ensureServicesRunning(context, "蓝牙设备连接");
                break;
                
            case "android.bluetooth.device.action.ACL_DISCONNECTED":
                AppLog.d(TAG, "【蓝牙】蓝牙设备已断开");
                ensureServicesRunning(context, "蓝牙设备断开");
                break;
                
            // ========== USB/存储相关（插U盘触发） ==========
            case Intent.ACTION_MEDIA_MOUNTED:
                AppLog.d(TAG, "【存储】存储已挂载（U盘/SD卡插入）");
                ensureServicesRunning(context, "存储挂载");
                break;
                
            case Intent.ACTION_MEDIA_UNMOUNTED:
                AppLog.d(TAG, "【存储】存储已卸载");
                ensureServicesRunning(context, "存储卸载");
                break;
                
            case Intent.ACTION_MEDIA_REMOVED:
                AppLog.d(TAG, "【存储】存储已移除");
                ensureServicesRunning(context, "存储移除");
                break;
                
            case Intent.ACTION_MEDIA_EJECT:
                AppLog.d(TAG, "【存储】存储弹出请求");
                ensureServicesRunning(context, "存储弹出");
                break;
                
            case "android.hardware.usb.action.USB_DEVICE_ATTACHED":
                AppLog.d(TAG, "【USB】USB设备已连接");
                ensureServicesRunning(context, "USB连接");
                break;
                
            case "android.hardware.usb.action.USB_DEVICE_DETACHED":
                AppLog.d(TAG, "【USB】USB设备已断开");
                ensureServicesRunning(context, "USB断开");
                break;
                
            // ========== 网络相关 ==========
            case "android.net.conn.CONNECTIVITY_CHANGE":
                AppLog.d(TAG, "【网络】网络状态变化");
                ensureServicesRunning(context, "网络变化");
                break;
                
            case "android.net.wifi.STATE_CHANGE":
                AppLog.d(TAG, "【网络】WiFi状态变化");
                ensureServicesRunning(context, "WiFi变化");
                break;
                
            case "android.net.wifi.SCAN_RESULTS":
                // WiFi扫描结果，静默处理
                ensureServicesRunningQuiet(context);
                break;
                
            // ========== 音频相关 ==========
            case Intent.ACTION_HEADSET_PLUG:
                AppLog.d(TAG, "【音频】耳机插拔");
                ensureServicesRunning(context, "耳机插拔");
                break;
                
            case "android.media.AUDIO_BECOMING_NOISY":
                AppLog.d(TAG, "【音频】音频输出设备变化");
                ensureServicesRunning(context, "音频设备变化");
                break;
                
            // ========== 时间/时区相关 ==========
            case Intent.ACTION_TIMEZONE_CHANGED:
                AppLog.d(TAG, "【时间】时区变化");
                ensureServicesRunning(context, "时区变化");
                break;
                
            case Intent.ACTION_TIME_CHANGED:
                AppLog.d(TAG, "【时间】时间设置变化");
                ensureServicesRunning(context, "时间变化");
                break;
                
            case Intent.ACTION_DATE_CHANGED:
                AppLog.d(TAG, "【时间】日期变化（跨天）");
                ensureServicesRunning(context, "日期变化");
                break;
                
            // ========== 系统配置相关 ==========
            case Intent.ACTION_LOCALE_CHANGED:
                AppLog.d(TAG, "【系统】语言/区域变化");
                ensureServicesRunning(context, "语言变化");
                break;
                
            case Intent.ACTION_AIRPLANE_MODE_CHANGED:
                AppLog.d(TAG, "【系统】飞行模式切换");
                ensureServicesRunning(context, "飞行模式");
                break;
                
            // ========== 应用相关 ==========
            case Intent.ACTION_MY_PACKAGE_REPLACED:
                AppLog.d(TAG, "【应用】应用已更新，重新激活服务");
                ensureServicesRunning(context, "应用更新");
                // 应用更新后重新注册 TIME_TICK
                registerTimeTick(context);
                break;
                
            case Intent.ACTION_PACKAGE_ADDED:
            case Intent.ACTION_PACKAGE_REPLACED:
                // 其他应用安装/更新，静默处理
                ensureServicesRunningQuiet(context);
                break;
                
            // ========== 每分钟定时 ==========
            case Intent.ACTION_TIME_TICK:
                onTimeTick(context);
                break;
                
            // ========== 自定义保活广播 ==========
            case ACTION_KEEP_ALIVE:
                AppLog.d(TAG, "【保活】收到手动保活检查请求");
                ensureServicesRunning(context, "手动保活");
                break;
                
            default:
                // 其他未知广播也触发保活检查
                AppLog.d(TAG, "【其他】收到广播: " + action);
                ensureServicesRunningQuiet(context);
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
                AppLog.d(TAG, "【定时】无障碍服务已运行 " + runningMinutes + " 分钟");
            }
        } else {
            // 无障碍服务未运行，尝试拉起前台服务
            AppLog.d(TAG, "【定时】无障碍服务未运行，尝试拉起前台服务");
            ensureServicesRunning(context, "定时检查");
        }
    }
    
    /**
     * 确保所有保活服务正在运行（带触发原因）
     * @param context 上下文
     * @param reason 触发原因（用于通知显示）
     */
    private void ensureServicesRunning(Context context, String reason) {
        // 防止短时间内重复触发
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < MIN_TRIGGER_INTERVAL) {
            return;
        }
        lastTriggerTime = now;
        
        try {
            // 启动前台服务
            CameraForegroundService.start(context, "EVCam 后台运行中", "触发: " + reason);
            AppLog.d(TAG, "已请求启动前台服务 (触发: " + reason + ")");
        } catch (Exception e) {
            AppLog.e(TAG, "启动服务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 静默确保服务运行（不输出日志，用于频繁触发的广播）
     * @param context 上下文
     */
    private void ensureServicesRunningQuiet(Context context) {
        // 防止短时间内重复触发
        long now = System.currentTimeMillis();
        if (now - lastTriggerTime < MIN_TRIGGER_INTERVAL) {
            return;
        }
        lastTriggerTime = now;
        
        try {
            // 启动前台服务
            CameraForegroundService.start(context, "EVCam 后台运行中", "点击返回应用");
        } catch (Exception e) {
            // 静默失败，不输出日志
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
