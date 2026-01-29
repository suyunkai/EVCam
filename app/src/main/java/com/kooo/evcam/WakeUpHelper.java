package com.kooo.evcam;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

/**
 * 唤醒工具类
 * 用于在后台收到钉钉命令时保持CPU运行并启动 Activity
 * 注意：不会亮屏，支持息屏状态下静默拍照/录制
 * 
 * 阻止休眠的关键：
 * 1. PARTIAL_WAKE_LOCK - 保持 CPU 运行
 * 2. 电池优化白名单 - 防止 Doze 模式忽略 WakeLock
 * 3. 在前台服务中持有 WakeLock - 比 Activity 更可靠
 */
public class WakeUpHelper {
    private static final String TAG = "WakeUpHelper";

    // CPU唤醒锁（不亮屏）- 用于远程命令（有超时）
    private static PowerManager.WakeLock wakeLock;
    
    // 持续唤醒锁 - 用于防止休眠（无超时）
    private static PowerManager.WakeLock persistentWakeLock;

    /**
     * 检查是否有悬浮窗权限（用于后台启动Activity）
     * Android 10+ 需要此权限才能从后台启动 Activity
     */
    public static boolean hasOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return Settings.canDrawOverlays(context);
        }
        return true;
    }

    /**
     * 请求悬浮窗权限
     * 需要用户手动授权
     */
    public static void requestOverlayPermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        }
    }

    /**
     * 获取CPU唤醒锁（不亮屏）
     * 确保在息屏状态下CPU保持运行，能够完成拍照/录制
     */
    public static void acquireCpuWakeLock(Context context) {
        AppLog.d(TAG, "Acquiring CPU wake lock (screen stays off)...");

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            AppLog.e(TAG, "PowerManager is null");
            return;
        }

        // 释放之前的唤醒锁
        releaseWakeLock();

        // 创建新的唤醒锁
        // PARTIAL_WAKE_LOCK: 只保持CPU运行，不亮屏
        wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EVCam:RemoteCommand"
        );

        // 持有唤醒锁 5 分钟（足够完成拍照或录制+上传）
        wakeLock.acquire(5 * 60 * 1000);
        AppLog.d(TAG, "CPU WakeLock acquired for 5 minutes");
    }

    /**
     * 释放唤醒锁
     */
    public static void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) {
            try {
                wakeLock.release();
                AppLog.d(TAG, "WakeLock released");
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to release WakeLock", e);
            }
        }
        wakeLock = null;
    }
    
    /**
     * 获取持续唤醒锁（防止系统休眠）
     * 用于需要长期保持CPU运行的场景，如车机熄火后仍需接收远程消息
     * 注意：会增加功耗，需要用户明确开启
     */
    public static void acquirePersistentWakeLock(Context context) {
        AppLog.d(TAG, "Acquiring persistent wake lock (prevent sleep)...");

        PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (pm == null) {
            AppLog.e(TAG, "PowerManager is null");
            return;
        }

        // 如果已经持有，不重复获取
        if (persistentWakeLock != null && persistentWakeLock.isHeld()) {
            AppLog.d(TAG, "Persistent WakeLock already held");
            return;
        }

        // 创建持续唤醒锁
        // PARTIAL_WAKE_LOCK: 只保持CPU运行，不亮屏
        persistentWakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "EVCam:PreventSleep"
        );

        // 持有唤醒锁，不设置超时（直到手动释放）
        persistentWakeLock.acquire();
        AppLog.d(TAG, "Persistent WakeLock acquired (no timeout) - system will not sleep");
    }
    
    /**
     * 释放持续唤醒锁
     */
    public static void releasePersistentWakeLock() {
        if (persistentWakeLock != null && persistentWakeLock.isHeld()) {
            try {
                persistentWakeLock.release();
                AppLog.d(TAG, "Persistent WakeLock released - system can sleep now");
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to release persistent WakeLock", e);
            }
        }
        persistentWakeLock = null;
    }
    
    /**
     * 检查持续唤醒锁是否被持有
     */
    public static boolean isPersistentWakeLockHeld() {
        return persistentWakeLock != null && persistentWakeLock.isHeld();
    }
    
    /**
     * 检查应用是否在电池优化白名单中
     * Android 6.0+ 的 Doze 模式会忽略 WakeLock，只有加入白名单才能真正阻止休眠
     * 
     * @return true 表示已在白名单中（不受 Doze 限制）
     */
    public static boolean isIgnoringBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                return pm.isIgnoringBatteryOptimizations(context.getPackageName());
            }
        }
        return true; // Android 6.0 以下不需要
    }
    
    /**
     * 请求加入电池优化白名单
     * 这是阻止休眠的关键！Doze 模式下只有白名单应用的 WakeLock 才有效
     * 
     * 注意：会弹出系统对话框，需要用户确认
     */
    public static void requestIgnoreBatteryOptimizations(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!isIgnoringBatteryOptimizations(context)) {
                try {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + context.getPackageName()));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                    AppLog.d(TAG, "Requesting battery optimization whitelist");
                } catch (Exception e) {
                    AppLog.e(TAG, "Failed to request battery optimization whitelist", e);
                    // 某些设备可能不支持，尝试打开电池优化设置页面
                    openBatteryOptimizationSettings(context);
                }
            } else {
                AppLog.d(TAG, "Already in battery optimization whitelist");
            }
        }
    }
    
    /**
     * 打开电池优化设置页面（备用方案）
     */
    public static void openBatteryOptimizationSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to open battery optimization settings", e);
        }
    }

    /**
     * 启动 MainActivity 到前台，并传递命令参数
     * 
     * @param context 上下文
     * @param action 动作类型: "record" 或 "photo"
     * @param conversationId 钉钉会话ID
     * @param conversationType 钉钉会话类型
     * @param userId 钉钉用户ID
     * @param duration 录制时长（仅 record 时有效）
     */
    public static void launchMainActivityWithCommand(Context context, String action,
            String conversationId, String conversationType, String userId, int duration) {
        
        AppLog.d(TAG, "Launching MainActivity with command: " + action);

        // 获取CPU唤醒锁，确保息屏状态下也能执行
        acquireCpuWakeLock(context);

        // 创建 Intent
        Intent intent = new Intent(context, MainActivity.class);
        
        // 设置 flags
        // FLAG_ACTIVITY_NEW_TASK: 从非 Activity 上下文启动时必须
        // FLAG_ACTIVITY_CLEAR_TOP: 如果 Activity 已存在，清除其上的所有 Activity
        // FLAG_ACTIVITY_SINGLE_TOP: 如果 Activity 在栈顶，不创建新实例，调用 onNewIntent
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK |
                Intent.FLAG_ACTIVITY_CLEAR_TOP |
                Intent.FLAG_ACTIVITY_SINGLE_TOP);

        // 传递命令参数
        intent.putExtra("remote_action", action);
        intent.putExtra("remote_conversation_id", conversationId);
        intent.putExtra("remote_conversation_type", conversationType);
        intent.putExtra("remote_user_id", userId);
        intent.putExtra("remote_duration", duration);

        // 启动 Activity
        context.startActivity(intent);
        AppLog.d(TAG, "MainActivity launch intent sent");
    }

    /**
     * 启动 MainActivity 执行录制命令
     */
    public static void launchForRecording(Context context, String conversationId,
            String conversationType, String userId, int durationSeconds) {
        launchMainActivityWithCommand(context, "record", conversationId, conversationType, userId, durationSeconds);
    }

    /**
     * 启动 MainActivity 执行拍照命令
     */
    public static void launchForPhoto(Context context, String conversationId,
            String conversationType, String userId) {
        launchMainActivityWithCommand(context, "photo", conversationId, conversationType, userId, 0);
    }

    /**
     * 启动 MainActivity 执行启动持续录制命令（等同点击录制按钮）
     */
    public static void launchForStartRecording(Context context) {
        launchMainActivityWithCommand(context, "start_recording", null, null, null, 0);
    }

    /**
     * 启动 MainActivity 执行停止录制命令
     */
    public static void launchForStopRecording(Context context) {
        launchMainActivityWithCommand(context, "stop_recording", null, null, null, 0);
    }
}
