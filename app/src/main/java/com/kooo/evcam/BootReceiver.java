package com.kooo.evcam;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * 开机启动广播接收器
 * 监听系统开机广播，自动启动必要的服务
 */
public class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || intent.getAction() == null) {
            return;
        }

        String action = intent.getAction();
        AppLog.d(TAG, "收到广播: " + action);

        // 监听开机完成广播
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            AppLog.d(TAG, "系统开机完成，开始初始化应用服务...");
            
            // 检查用户是否启用了开机自启动
            AppConfig appConfig = new AppConfig(context);
            if (!appConfig.isAutoStartOnBoot()) {
                AppLog.d(TAG, "用户已禁用开机自启动，跳过初始化");
                return;
            }
            
            AppLog.d(TAG, "开机自启动已启用，启动透明 Activity 初始化服务...");
            
            // 启动透明 Activity 来初始化服务
            // 透明 Activity 完全不可见，用户无感知
            // 它会启动前台服务、WorkManager 保活任务和远程查看服务
            try {
                Intent transparentIntent = new Intent(context, TransparentBootActivity.class);
                transparentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                context.startActivity(transparentIntent);
                AppLog.d(TAG, "透明 Activity 已启动");
            } catch (Exception e) {
                AppLog.e(TAG, "启动透明 Activity 失败: " + e.getMessage(), e);
                
                // 降级方案：直接启动前台服务和 WorkManager
                AppLog.d(TAG, "使用降级方案：直接启动后台服务");
                if (appConfig.isKeepAliveEnabled()) {
                    KeepAliveManager.startKeepAliveWork(context);
                }
                CameraForegroundService.start(context, "开机自启动", "应用已在后台运行");
            }

            AppLog.d(TAG, "开机自启动初始化完成");
        }
    }
}
