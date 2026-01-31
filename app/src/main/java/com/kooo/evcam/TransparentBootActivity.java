package com.kooo.evcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.kooo.evcam.dingtalk.DingTalkConfig;
import com.kooo.evcam.telegram.TelegramConfig;

/**
 * 透明启动 Activity
 * 用于开机自启动时在后台初始化服务，用户完全无感知
 * 
 * 特点：
 * 1. 完全透明，用户看不到任何界面
 * 2. 启动后立即初始化服务并 finish
 * 3. 不会在最近任务中显示
 */
public class TransparentBootActivity extends Activity {
    private static final String TAG = "TransparentBootActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.d(TAG, "透明启动 Activity 已创建");
        
        // 不设置任何布局，保持完全透明
        
        // 初始化服务
        initServices();
        
        // 立即结束，用户完全无感知
        finish();
        
        // 禁用退出动画
        overridePendingTransition(0, 0);
        
        AppLog.d(TAG, "透明启动 Activity 已结束");
    }
    
    /**
     * 初始化必要的服务
     */
    private void initServices() {
        AppLog.d(TAG, "开始初始化后台服务...");
        
        // 1. 启动前台服务保持进程活跃
        // 【重要】远程服务（钉钉/Telegram）现在在 CameraForegroundService.onCreate() 中启动
        // 不再需要 MainActivity 来启动远程服务
        CameraForegroundService.start(this, 
            "开机自启动", 
            "应用已在后台运行");
        AppLog.d(TAG, "前台服务已启动（远程服务将在其中启动）");
        
        // 2. 启动 WorkManager 保活任务（车机必需，始终开启）
        KeepAliveManager.startKeepAliveWork(this);
        AppLog.d(TAG, "WorkManager 保活任务已启动");
        
        // 3. 检查是否需要启动 MainActivity
        // 【优化后】只有以下情况需要启动 MainActivity：
        // - 用户启用了"启动自动录制"功能（需要摄像头，必须启动 Activity）
        // 【不再需要启动 MainActivity】：
        // - 远程服务（钉钉/Telegram）已在 CameraForegroundService 中启动
        // - 悬浮窗已在 CameraForegroundService 中启动
        AppConfig appConfig = new AppConfig(this);
        
        boolean shouldAutoRecord = appConfig.isAutoStartRecording();
        boolean shouldShowFloatingWindow = appConfig.isFloatingWindowEnabled();
        
        // 仅用于日志记录
        DingTalkConfig dingTalkConfig = new DingTalkConfig(this);
        TelegramConfig telegramConfig = new TelegramConfig(this);
        boolean hasRemoteService = (dingTalkConfig.isConfigured() && dingTalkConfig.isAutoStart()) ||
                                   (telegramConfig.isConfigured() && telegramConfig.isAutoStart());
        
        if (hasRemoteService) {
            AppLog.d(TAG, "远程服务已在 CameraForegroundService 中启动，无需启动 MainActivity");
        }
        if (shouldShowFloatingWindow) {
            AppLog.d(TAG, "悬浮窗已在 CameraForegroundService 中启动，无需启动 MainActivity");
        }
        
        // 只有自动录制需要启动 MainActivity（因为需要摄像头）
        if (shouldAutoRecord) {
            AppLog.d(TAG, "启动自动录制功能已启用，需要启动 MainActivity（摄像头需要 Activity）");
            AppLog.d(TAG, "启动 MainActivity（后台模式）...");
            
            // 启动 MainActivity 初始化摄像头（后台模式）
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            mainIntent.putExtra("auto_start_from_boot", true);
            mainIntent.putExtra("silent_mode", true);
            startActivity(mainIntent);
            
            AppLog.d(TAG, "MainActivity 已启动（后台模式）");
        } else {
            AppLog.d(TAG, "无需启动 MainActivity（自动录制未启用），仅保持后台运行");
        }
    }
    
    @Override
    public void onBackPressed() {
        // 禁用返回键
        finish();
    }
}
