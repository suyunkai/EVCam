package com.kooo.evcam;

import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * 无障碍服务辅助类
 * 提供无障碍服务状态检查和跳转功能
 */
public class AccessibilityHelper {
    private static final String TAG = "AccessibilityHelper";

    /**
     * 检查无障碍服务是否已启用
     * @param context 上下文
     * @return true 表示已启用
     */
    public static boolean isAccessibilityServiceEnabled(Context context) {
        try {
            int accessibilityEnabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            
            if (accessibilityEnabled == 1) {
                String services = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                
                if (services != null) {
                    String serviceName = context.getPackageName() + "/" + KeepAliveAccessibilityService.class.getName();
                    boolean enabled = services.contains(serviceName);
                    AppLog.d(TAG, "无障碍服务状态: " + (enabled ? "已启用" : "未启用"));
                    return enabled;
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "检查无障碍服务状态失败", e);
        }
        return false;
    }

    /**
     * 打开无障碍设置页面
     * @param context 上下文
     */
    public static void openAccessibilitySettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            AppLog.d(TAG, "打开无障碍设置页面");
        } catch (Exception e) {
            AppLog.e(TAG, "打开无障碍设置失败", e);
        }
    }
}
