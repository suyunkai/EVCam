package com.kooo.evcam;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.kooo.evcam.dingtalk.DingTalkConfig;

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
        CameraForegroundService.start(this, 
            "开机自启动", 
            "应用已在后台运行");
        AppLog.d(TAG, "前台服务已启动");
        
        // 2. 启动 WorkManager 保活任务（车机必需，始终开启）
        KeepAliveManager.startKeepAliveWork(this);
        AppLog.d(TAG, "WorkManager 保活任务已启动");
        
        // 3. 检查是否需要启动远程查看服务
        DingTalkConfig dingTalkConfig = new DingTalkConfig(this);
        if (dingTalkConfig.isConfigured() && dingTalkConfig.isAutoStart()) {
            AppLog.d(TAG, "远程查看服务配置为自动启动，启动 MainActivity（后台模式）...");
            
            // 启动 MainActivity 初始化远程查看服务（后台模式）
            Intent mainIntent = new Intent(this, MainActivity.class);
            mainIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
            mainIntent.putExtra("auto_start_from_boot", true);
            mainIntent.putExtra("silent_mode", true);
            startActivity(mainIntent);
            
            AppLog.d(TAG, "MainActivity 已启动（后台模式）");
        } else {
            AppLog.d(TAG, "远程查看服务未配置或未启用自动启动，仅保持后台运行");
        }
    }
    
    @Override
    public void onBackPressed() {
        // 禁用返回键
        finish();
    }
}
