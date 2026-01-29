package com.kooo.evcam;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;

/**
 * 高优先级 ContentProvider
 * 
 * 作用：在应用启动的最早阶段初始化保活服务
 * 
 * 原理：
 * - ContentProvider 的 onCreate() 在 Application.onCreate() 之前执行
 * - 设置 initOrder="2147483647"（最大值）确保最先执行
 * - 参考应用1的 SecShell 实现
 * 
 * 这是开机自启动成功的关键技术之一
 */
public class KeepAliveProvider extends ContentProvider {
    private static final String TAG = "KeepAliveProvider";

    @Override
    public boolean onCreate() {
        // ContentProvider.onCreate() 在应用启动的最早阶段执行
        // 这里启动前台服务，确保服务尽早运行
        
        Context context = getContext();
        if (context == null) {
            return false;
        }
        
        try {
            AppLog.d(TAG, "KeepAliveProvider onCreate - 应用启动最早阶段");
            
            // 启动前台服务
            startForegroundService(context);
            
            // 注册 TIME_TICK 广播
            registerTimeTick(context);
            
        } catch (Exception e) {
            // Provider 的 onCreate 不能抛出异常，否则应用会崩溃
            try {
                AppLog.e(TAG, "初始化失败: " + e.getMessage(), e);
            } catch (Exception ignored) {}
        }
        
        return false; // 返回 false，因为这个 Provider 不提供实际数据
    }
    
    /**
     * 启动前台服务
     */
    private void startForegroundService(Context context) {
        try {
            // 延迟一小段时间启动，避免在系统初始化完成前启动
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    CameraForegroundService.start(context, "EVCam", "服务运行中");
                    AppLog.d(TAG, "前台服务已从 Provider 启动");
                } catch (Exception e) {
                    AppLog.e(TAG, "从 Provider 启动服务失败: " + e.getMessage(), e);
                }
            }, 1000); // 延迟1秒
        } catch (Exception e) {
            AppLog.e(TAG, "调度启动服务失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 注册 TIME_TICK 广播
     */
    private void registerTimeTick(Context context) {
        try {
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                try {
                    KeepAliveReceiver.registerTimeTick(context);
                    AppLog.d(TAG, "TIME_TICK 已从 Provider 注册");
                } catch (Exception e) {
                    AppLog.e(TAG, "从 Provider 注册 TIME_TICK 失败: " + e.getMessage(), e);
                }
            }, 2000); // 延迟2秒
        } catch (Exception e) {
            AppLog.e(TAG, "调度注册 TIME_TICK 失败: " + e.getMessage(), e);
        }
    }

    // 以下方法必须实现，但我们不提供实际数据功能
    
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, 
                       String[] selectionArgs, String sortOrder) {
        return null;
    }

    @Override
    public String getType(Uri uri) {
        return null;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, 
                     String[] selectionArgs) {
        return 0;
    }
}
