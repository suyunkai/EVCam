package com.kooo.evcam;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

/**
 * 悬浮窗服务
 * 显示一个悬浮按钮，与录制状态同步（录制时绿色闪烁，未录制时红色）
 * 点击可打开应用，支持自由拖动
 */
public class FloatingWindowService extends Service {
    private static final String TAG = "FloatingWindowService";
    
    // 广播动作
    public static final String ACTION_RECORDING_STATE_CHANGED = "com.kooo.evcam.RECORDING_STATE_CHANGED";
    public static final String EXTRA_IS_RECORDING = "is_recording";
    public static final String ACTION_UPDATE_FLOATING_WINDOW = "com.kooo.evcam.UPDATE_FLOATING_WINDOW";
    public static final String ACTION_APP_FOREGROUND_STATE = "com.kooo.evcam.APP_FOREGROUND_STATE";
    public static final String EXTRA_IS_FOREGROUND = "is_foreground";
    
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams layoutParams;
    private AppConfig appConfig;
    
    // 录制状态
    private boolean isRecording = false;
    
    // 闪烁动画
    private Handler blinkHandler;
    private Runnable blinkRunnable;
    private boolean isBlinkOn = true;
    
    // 拖动相关
    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private boolean isDragging = false;
    private static final int CLICK_THRESHOLD = 10;  // 点击阈值（像素）
    
    // 广播接收器
    private BroadcastReceiver recordingStateReceiver;
    private BroadcastReceiver updateReceiver;
    private BroadcastReceiver foregroundStateReceiver;
    
    // 悬浮窗显示状态
    private boolean isFloatingWindowVisible = true;
    
    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.d(TAG, "FloatingWindowService onCreate");
        
        appConfig = new AppConfig(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        blinkHandler = new Handler(Looper.getMainLooper());
        
        // 创建悬浮窗
        createFloatingWindow();
        
        // 注册录制状态广播接收器
        registerRecordingStateReceiver();
        
        // 注册更新悬浮窗广播接收器
        registerUpdateReceiver();
        
        // 注册前台状态广播接收器
        registerForegroundStateReceiver();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.d(TAG, "FloatingWindowService onStartCommand");
        return START_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLog.d(TAG, "FloatingWindowService onDestroy");
        
        // 保存当前位置
        if (layoutParams != null) {
            appConfig.setFloatingWindowPosition(layoutParams.x, layoutParams.y);
        }
        
        // 停止闪烁动画
        stopBlinkAnimation();
        
        // 移除悬浮窗
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                AppLog.e(TAG, "移除悬浮窗失败", e);
            }
        }
        
        // 注销广播接收器
        if (recordingStateReceiver != null) {
            try {
                unregisterReceiver(recordingStateReceiver);
            } catch (Exception e) {
                AppLog.e(TAG, "注销录制状态接收器失败", e);
            }
        }
        
        if (updateReceiver != null) {
            try {
                unregisterReceiver(updateReceiver);
            } catch (Exception e) {
                AppLog.e(TAG, "注销更新接收器失败", e);
            }
        }
        
        if (foregroundStateReceiver != null) {
            try {
                unregisterReceiver(foregroundStateReceiver);
            } catch (Exception e) {
                AppLog.e(TAG, "注销前台状态接收器失败", e);
            }
        }
    }
    
    /**
     * 创建悬浮窗
     */
    private void createFloatingWindow() {
        // 检查悬浮窗权限
        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限");
            stopSelf();
            return;
        }
        
        // 获取配置
        int sizeDp = appConfig.getFloatingWindowSize();
        int alpha = appConfig.getFloatingWindowAlpha();
        int savedX = appConfig.getFloatingWindowX();
        int savedY = appConfig.getFloatingWindowY();
        
        // 转换dp到px
        float density = getResources().getDisplayMetrics().density;
        int sizePx = (int) (sizeDp * density);
        
        // 创建自定义绘制的View
        floatingView = new FloatingButtonView(this, sizePx);
        floatingView.setAlpha(alpha / 100f);
        
        // 设置布局参数
        int windowType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            windowType = WindowManager.LayoutParams.TYPE_PHONE;
        }
        
        layoutParams = new WindowManager.LayoutParams(
                sizePx,
                sizePx,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        
        // 恢复保存的位置或使用默认位置
        if (savedX >= 0 && savedY >= 0) {
            layoutParams.x = savedX;
            layoutParams.y = savedY;
        } else {
            // 默认位置：右侧中间
            DisplayMetrics metrics = getResources().getDisplayMetrics();
            layoutParams.x = metrics.widthPixels - sizePx - 20;
            layoutParams.y = metrics.heightPixels / 2 - sizePx / 2;
        }
        
        // 设置触摸事件
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) (event.getRawX() - initialTouchX);
                        int deltaY = (int) (event.getRawY() - initialTouchY);
                        
                        // 判断是否为拖动
                        if (Math.abs(deltaX) > CLICK_THRESHOLD || Math.abs(deltaY) > CLICK_THRESHOLD) {
                            isDragging = true;
                        }
                        
                        if (isDragging) {
                            // 获取屏幕尺寸
                            DisplayMetrics metrics = getResources().getDisplayMetrics();
                            int screenWidth = metrics.widthPixels;
                            int screenHeight = metrics.heightPixels;
                            int viewSize = layoutParams.width;
                            
                            // 计算新位置
                            int newX = initialX + deltaX;
                            int newY = initialY + deltaY;
                            
                            // 边界限制：确保悬浮窗不会超出屏幕
                            newX = Math.max(0, Math.min(newX, screenWidth - viewSize));
                            newY = Math.max(0, Math.min(newY, screenHeight - viewSize));
                            
                            layoutParams.x = newX;
                            layoutParams.y = newY;
                            windowManager.updateViewLayout(floatingView, layoutParams);
                        }
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            // 点击事件：打开应用
                            openApp();
                        } else {
                            // 拖动结束：保存位置
                            appConfig.setFloatingWindowPosition(layoutParams.x, layoutParams.y);
                        }
                        return true;
                }
                return false;
            }
        });
        
        // 添加到窗口
        try {
            windowManager.addView(floatingView, layoutParams);
            AppLog.d(TAG, "悬浮窗创建成功，大小: " + sizePx + "px, 透明度: " + alpha + "%");
        } catch (Exception e) {
            AppLog.e(TAG, "添加悬浮窗失败", e);
            stopSelf();
        }
    }
    
    /**
     * 注册录制状态广播接收器
     */
    private void registerRecordingStateReceiver() {
        recordingStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_RECORDING_STATE_CHANGED.equals(intent.getAction())) {
                    boolean recording = intent.getBooleanExtra(EXTRA_IS_RECORDING, false);
                    updateRecordingState(recording);
                }
            }
        };
        
        IntentFilter filter = new IntentFilter(ACTION_RECORDING_STATE_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(recordingStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recordingStateReceiver, filter);
        }
    }
    
    /**
     * 注册更新悬浮窗广播接收器
     */
    private void registerUpdateReceiver() {
        updateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_UPDATE_FLOATING_WINDOW.equals(intent.getAction())) {
                    // 重新创建悬浮窗以应用新设置
                    updateFloatingWindow();
                }
            }
        };
        
        IntentFilter filter = new IntentFilter(ACTION_UPDATE_FLOATING_WINDOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(updateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(updateReceiver, filter);
        }
    }
    
    /**
     * 注册前台状态广播接收器
     */
    private void registerForegroundStateReceiver() {
        foregroundStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_APP_FOREGROUND_STATE.equals(intent.getAction())) {
                    boolean isForeground = intent.getBooleanExtra(EXTRA_IS_FOREGROUND, false);
                    if (isForeground) {
                        hideFloatingWindow();
                    } else {
                        showFloatingWindow();
                    }
                }
            }
        };
        
        IntentFilter filter = new IntentFilter(ACTION_APP_FOREGROUND_STATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(foregroundStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(foregroundStateReceiver, filter);
        }
    }
    
    /**
     * 隐藏悬浮窗
     */
    private void hideFloatingWindow() {
        if (floatingView != null && isFloatingWindowVisible) {
            floatingView.setVisibility(View.GONE);
            isFloatingWindowVisible = false;
            AppLog.d(TAG, "悬浮窗已隐藏（应用在前台）");
        }
    }
    
    /**
     * 显示悬浮窗
     */
    private void showFloatingWindow() {
        if (floatingView != null && !isFloatingWindowVisible) {
            floatingView.setVisibility(View.VISIBLE);
            isFloatingWindowVisible = true;
            AppLog.d(TAG, "悬浮窗已显示（应用在后台）");
        }
    }
    
    /**
     * 更新录制状态
     */
    private void updateRecordingState(boolean recording) {
        if (isRecording == recording) {
            return;
        }
        
        isRecording = recording;
        AppLog.d(TAG, "录制状态更新: " + (recording ? "录制中" : "未录制"));
        
        if (recording) {
            startBlinkAnimation();
        } else {
            stopBlinkAnimation();
        }
        
        // 刷新视图
        if (floatingView != null) {
            floatingView.invalidate();
        }
    }
    
    /**
     * 开始闪烁动画
     */
    private void startBlinkAnimation() {
        if (blinkRunnable != null) {
            blinkHandler.removeCallbacks(blinkRunnable);
        }
        
        isBlinkOn = true;
        blinkRunnable = new Runnable() {
            @Override
            public void run() {
                isBlinkOn = !isBlinkOn;
                if (floatingView != null) {
                    floatingView.invalidate();
                }
                blinkHandler.postDelayed(this, 1000);  // 1秒闪烁一次
            }
        };
        
        blinkHandler.post(blinkRunnable);
    }
    
    /**
     * 停止闪烁动画
     */
    private void stopBlinkAnimation() {
        if (blinkRunnable != null) {
            blinkHandler.removeCallbacks(blinkRunnable);
            blinkRunnable = null;
        }
        isBlinkOn = true;
    }
    
    /**
     * 更新悬浮窗（应用新设置）
     */
    private void updateFloatingWindow() {
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                AppLog.e(TAG, "移除旧悬浮窗失败", e);
            }
        }
        
        createFloatingWindow();
    }
    
    /**
     * 打开应用
     * 双保险策略：同时使用 moveTaskToFront 和 startActivity
     * 因为 moveTaskToFront 可能静默失败（不抛异常但不生效）
     */
    private void openApp() {
        AppLog.d(TAG, "点击悬浮窗，打开应用");
        
        boolean moveToFrontAttempted = false;
        
        // 尝试使用 moveTaskToFront 快速恢复
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am != null) {
            java.util.List<android.app.ActivityManager.AppTask> tasks = am.getAppTasks();
            if (tasks != null && !tasks.isEmpty()) {
                try {
                    tasks.get(0).moveToFront();
                    moveToFrontAttempted = true;
                    AppLog.d(TAG, "已尝试 moveTaskToFront");
                } catch (Exception e) {
                    AppLog.w(TAG, "moveTaskToFront 异常: " + e.getMessage());
                }
            }
        }
        
        // 始终同时发送 startActivity 作为备份
        // moveTaskToFront 可能静默失败，startActivity 确保 Intent 被系统处理
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK 
                | Intent.FLAG_ACTIVITY_SINGLE_TOP 
                | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(intent);
        
        AppLog.d(TAG, "已发送 startActivity" + (moveToFrontAttempted ? "（双保险模式）" : ""));
    }
    
    /**
     * 自定义悬浮按钮视图
     */
    private class FloatingButtonView extends View {
        private Paint backgroundPaint;
        private Paint circlePaint;
        private Paint fillPaint;
        private int size;
        private float cornerRadius;
        
        // 日间模式颜色
        private static final int COLOR_BG_DAY = 0xFFF9FAFB;      // 浅灰白色背景
        // 夜间模式颜色
        private static final int COLOR_BG_NIGHT = 0xFF060809;    // 深黑色背景
        
        public FloatingButtonView(Context context, int size) {
            super(context);
            this.size = size;
            this.cornerRadius = size * 0.15f;  // 圆角半径
            
            backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            backgroundPaint.setStyle(Paint.Style.FILL);
            
            circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            circlePaint.setStyle(Paint.Style.STROKE);
            circlePaint.setStrokeWidth(size * 0.06f);  // 边框宽度
            
            fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            fillPaint.setStyle(Paint.Style.FILL);
        }
        
        /**
         * 判断当前是否为夜间模式
         */
        private boolean isNightMode() {
            int nightModeFlags = getContext().getResources().getConfiguration().uiMode 
                    & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
            return nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        }
        
        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            
            // 绘制方形圆角背景
            int bgColor = isNightMode() ? COLOR_BG_NIGHT : COLOR_BG_DAY;
            backgroundPaint.setColor(bgColor);
            android.graphics.RectF bgRect = new android.graphics.RectF(0, 0, size, size);
            canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, backgroundPaint);
            
            float centerX = size / 2f;
            float centerY = size / 2f;
            float radius = size * 0.28f;  // 主圆半径（缩小）
            float innerRadius = size * 0.09f;  // 内圆半径（缩小）
            
            if (isRecording) {
                // 录制中：绿色，闪烁
                int color = isBlinkOn ? 0xFF00FF00 : 0xFF006400;  // 亮绿/深绿
                circlePaint.setColor(color);
                fillPaint.setColor(color);
                
                // 绘制外圈
                canvas.drawCircle(centerX, centerY, radius, circlePaint);
                
                // 绘制内部实心圆
                canvas.drawCircle(centerX, centerY, innerRadius, fillPaint);
            } else {
                // 未录制：根据主题显示黑色或白色空心圆
                int circleColor = isNightMode() ? 0xFFFFFFFF : 0xFF000000;  // 夜间白色，日间黑色
                circlePaint.setColor(circleColor);
                
                // 绘制外圈
                canvas.drawCircle(centerX, centerY, radius, circlePaint);
            }
        }
    }
    
    /**
     * 启动悬浮窗服务
     */
    public static void start(Context context) {
        if (!WakeUpHelper.hasOverlayPermission(context)) {
            AppLog.w(TAG, "没有悬浮窗权限，无法启动服务");
            return;
        }
        
        Intent intent = new Intent(context, FloatingWindowService.class);
        context.startService(intent);
    }
    
    /**
     * 停止悬浮窗服务
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, FloatingWindowService.class);
        context.stopService(intent);
    }
    
    /**
     * 发送录制状态变化广播
     */
    public static void sendRecordingStateChanged(Context context, boolean isRecording) {
        Intent intent = new Intent(ACTION_RECORDING_STATE_CHANGED);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_IS_RECORDING, isRecording);
        context.sendBroadcast(intent);
    }
    
    /**
     * 发送更新悬浮窗广播
     */
    public static void sendUpdateFloatingWindow(Context context) {
        Intent intent = new Intent(ACTION_UPDATE_FLOATING_WINDOW);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
    
    /**
     * 发送应用前台状态广播
     */
    public static void sendAppForegroundState(Context context, boolean isForeground) {
        Intent intent = new Intent(ACTION_APP_FOREGROUND_STATE);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_IS_FOREGROUND, isForeground);
        context.sendBroadcast(intent);
    }
}
