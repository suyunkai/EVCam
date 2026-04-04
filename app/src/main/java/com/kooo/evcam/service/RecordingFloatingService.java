package com.kooo.evcam.service;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.recording.RecordingController;

/**
 * 录制悬浮按钮服务
 * 在后台显示录制按钮，点击开始/停止录制，并显示录制时间
 */
public class RecordingFloatingService extends Service {
    private static final String TAG = "RecordingFloatingService";
    private static final int NOTIFICATION_ID = 1002;

    public static final String ACTION_SHOW = "com.kooo.evcam.action.SHOW_RECORDING_FLOATING";
    public static final String ACTION_HIDE = "com.kooo.evcam.action.HIDE_RECORDING_FLOATING";
    public static final String ACTION_UPDATE_SIZE = "com.kooo.evcam.action.UPDATE_RECORDING_FLOATING_SIZE";

    public static final String EXTRA_BUTTON_SIZE = "button_size";
    public static final String EXTRA_TEXT_SIZE = "text_size";

    private final IBinder binder = new LocalBinder();
    private Handler mainHandler;
    private AppConfig appConfig;
    private WindowManager windowManager;

    // 视图组件
    private FrameLayout floatingContainer;
    private RecordingButtonView recordingButton;
    private TextView timeTextView;

    // 布局参数
    private WindowManager.LayoutParams layoutParams;
    private int screenWidth;
    private int screenHeight;

    // 拖动相关
    private int initialX, initialY;
    private float initialTouchX, initialTouchY;
    private boolean isDragging = false;
    private static final int CLICK_THRESHOLD = 10;

    // 录制服务
    private CameraRecordingService recordingService;
    private boolean isServiceBound = false;

    // 录制状态
    private boolean isRecording = false;
    private long recordingStartTime = 0;
    private Runnable timeUpdateRunnable;

    // 广播接收器
    private BroadcastReceiver sizeUpdateReceiver;
    private BroadcastReceiver recordingStateReceiver;

    public class LocalBinder extends Binder {
        public RecordingFloatingService getService() {
            return RecordingFloatingService.this;
        }
    }

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CameraRecordingService.LocalBinder binder = (CameraRecordingService.LocalBinder) service;
            recordingService = binder.getService();
            isServiceBound = true;

            // 监听录制状态
            recordingService.getRecordingController().addStateListener(
                    new RecordingController.RecordingStateListener() {
                        @Override
                        public void onStateChanged(RecordingController.RecordingState newState,
                                                   RecordingController.RecordingState oldState) {
                            mainHandler.post(() -> {
                                boolean recording = (newState == RecordingController.RecordingState.RECORDING);
                                updateRecordingState(recording);
                            });
                        }
                    }
            );

            // 同步当前状态
            updateRecordingState(recordingService.isRecording());
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recordingService = null;
            isServiceBound = false;
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.d(TAG, "录制悬浮服务创建");

        mainHandler = new Handler(Looper.getMainLooper());
        appConfig = new AppConfig(this);
        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        // 获取屏幕尺寸
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // 在后台线程绑定录制服务
        new Thread(() -> bindRecordingService()).start();

        // 注册大小更新广播接收器
        registerSizeUpdateReceiver();
        
        // 注册录制状态广播接收器
        registerRecordingStateReceiver();
    }

    private void registerSizeUpdateReceiver() {
        sizeUpdateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (ACTION_UPDATE_SIZE.equals(intent.getAction())) {
                    int buttonSize = intent.getIntExtra(EXTRA_BUTTON_SIZE, -1);
                    int textSize = intent.getIntExtra(EXTRA_TEXT_SIZE, -1);
                    AppLog.d(TAG, "收到大小更新广播: button=" + buttonSize + ", text=" + textSize);
                    updateFloatingSize(buttonSize, textSize);
                }
            }
        };

        IntentFilter filter = new IntentFilter(ACTION_UPDATE_SIZE);
        // Android 14+ 需要指定 RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(sizeUpdateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(sizeUpdateReceiver, filter);
        }
    }
    
    private void registerRecordingStateReceiver() {
        recordingStateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                // 兼容两种广播 action
                if ("com.kooo.evcam.RECORDING_STATE_CHANGED".equals(action) ||
                    "com.kooo.evcam.action.RECORDING_STATE_CHANGED".equals(action)) {
                    boolean recording = intent.getBooleanExtra("is_recording", false);
                    AppLog.d(TAG, "收到录制状态广播: isRecording=" + recording);
                    mainHandler.post(() -> updateRecordingState(recording));
                }
            }
        };

        IntentFilter filter = new IntentFilter();
        filter.addAction("com.kooo.evcam.RECORDING_STATE_CHANGED");
        filter.addAction("com.kooo.evcam.action.RECORDING_STATE_CHANGED");
        // Android 14+ 需要指定 RECEIVER_NOT_EXPORTED
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(recordingStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(recordingStateReceiver, filter);
        }
    }

    private void updateFloatingSize(int buttonSizeDp, int textSizeSp) {
        if (floatingContainer == null || recordingButton == null || timeTextView == null) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;

        // 更新按钮大小
        if (buttonSizeDp > 0) {
            int buttonSize = (int) (buttonSizeDp * density);
            recordingButton.getLayoutParams().width = buttonSize;
            recordingButton.getLayoutParams().height = buttonSize;
            recordingButton.setButtonSize(buttonSize);
            recordingButton.requestLayout();
        }

        // 更新时间文字大小
        if (textSizeSp > 0) {
            timeTextView.setTextSize(textSizeSp);
            // 根据按钮大小调整padding
            int buttonSize = recordingButton.getLayoutParams().width;
            int padding = Math.max(8, buttonSize / 8);
            timeTextView.setPadding(padding, padding / 2, padding, padding / 2);
            timeTextView.requestLayout();
        }

        // 刷新窗口
        windowManager.updateViewLayout(floatingContainer, layoutParams);
        AppLog.d(TAG, "悬浮按钮大小已更新");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_HIDE.equals(action)) {
                hideFloatingWindow();
            } else {
                showFloatingWindow();
            }
        }
        return START_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        hideFloatingWindow();

        if (isServiceBound) {
            unbindService(serviceConnection);
        }

        // 注销广播接收器
        if (sizeUpdateReceiver != null) {
            unregisterReceiver(sizeUpdateReceiver);
        }
        if (recordingStateReceiver != null) {
            unregisterReceiver(recordingStateReceiver);
        }

        stopTimeUpdate();
    }

    // ========== 服务绑定 ==========

    private void bindRecordingService() {
        Intent intent = new Intent(this, CameraRecordingService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // ========== 悬浮窗管理 ==========

    private void showFloatingWindow() {
        if (floatingContainer != null) {
            return; // 已经显示
        }

        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限");
            stopSelf();
            return;
        }

        createFloatingWindow();
    }

    private void hideFloatingWindow() {
        if (floatingContainer != null && windowManager != null) {
            windowManager.removeView(floatingContainer);
            floatingContainer = null;
            recordingButton = null;
            timeTextView = null;
        }
        stopTimeUpdate();
    }

    private void createFloatingWindow() {
        // 获取配置的大小
        int buttonSizeDp = appConfig.getRecordingFloatingButtonSizeDp();
        int timeTextSizeSp = appConfig.getRecordingFloatingTimeTextSizeSp();
        float density = getResources().getDisplayMetrics().density;
        int buttonSize = (int) (buttonSizeDp * density);

        // 创建容器
        floatingContainer = new FrameLayout(this);

        // 创建水平布局容器
        LinearLayout horizontalContainer = new LinearLayout(this);
        horizontalContainer.setOrientation(LinearLayout.HORIZONTAL);
        horizontalContainer.setGravity(Gravity.CENTER_VERTICAL);

        // 创建录制按钮
        recordingButton = new RecordingButtonView(this, buttonSize);
        LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(buttonSize, buttonSize);
        horizontalContainer.addView(recordingButton, buttonParams);

        // 创建时间显示
        timeTextView = new TextView(this);
        timeTextView.setTextColor(Color.WHITE);
        timeTextView.setTextSize(timeTextSizeSp);
        timeTextView.setBackgroundColor(Color.parseColor("#80000000")); // 半透明黑色背景
        // 根据按钮大小调整padding
        int padding = Math.max(8, buttonSize / 8);
        timeTextView.setPadding(padding, padding / 2, padding, padding / 2);
        timeTextView.setVisibility(View.GONE); // 默认隐藏
        LinearLayout.LayoutParams timeParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        timeParams.leftMargin = Math.max(4, buttonSize / 16); // 距离按钮
        horizontalContainer.addView(timeTextView, timeParams);

        // 将水平容器添加到浮动容器
        floatingContainer.addView(horizontalContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));

        // 设置布局参数
        int windowType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            windowType = WindowManager.LayoutParams.TYPE_PHONE;
        }

        layoutParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT);

        layoutParams.gravity = Gravity.TOP | Gravity.START;

        // 默认位置：左侧中间
        layoutParams.x = 20;
        layoutParams.y = screenHeight / 2 - buttonSize / 2;

        // 设置触摸事件
        floatingContainer.setOnTouchListener(new View.OnTouchListener() {
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

                        if (Math.abs(deltaX) > CLICK_THRESHOLD || Math.abs(deltaY) > CLICK_THRESHOLD) {
                            isDragging = true;
                        }

                        if (isDragging) {
                            int newX = initialX + deltaX;
                            int newY = initialY + deltaY;

                            // 边界限制（考虑时间显示展开后的宽度）
                            int maxWidth = screenWidth - 200; // 预留足够空间
                            newX = Math.max(0, Math.min(newX, screenWidth - maxWidth));
                            newY = Math.max(0, Math.min(newY, screenHeight - buttonSize));

                            layoutParams.x = newX;
                            layoutParams.y = newY;
                            windowManager.updateViewLayout(floatingContainer, layoutParams);
                        }
                        return true;

                    case MotionEvent.ACTION_UP:
                        if (!isDragging) {
                            // 点击事件：切换录制状态
                            toggleRecording();
                        }
                        return true;
                }
                return false;
            }
        });

        // 添加到窗口
        try {
            windowManager.addView(floatingContainer, layoutParams);
            AppLog.d(TAG, "录制悬浮窗创建成功");
        } catch (Exception e) {
            AppLog.e(TAG, "添加悬浮窗失败", e);
            stopSelf();
        }
    }

    // ========== 录制控制 ==========

    private void toggleRecording() {
        AppLog.d(TAG, "toggleRecording called, recordingService=" + recordingService + ", isRecording=" + isRecording);

        // 获取应用状态
        AppState appState = getAppState();
        AppLog.d(TAG, "App state: " + appState);

        // 根据应用状态选择录制方式
        switch (appState) {
            case FOREGROUND:
                // 应用在前台，通过 MainActivity 停止录制
                AppLog.d(TAG, "App is in foreground, sending stop recording broadcast to MainActivity");
                Intent stopIntent = new Intent("com.kooo.evcam.action.TOGGLE_RECORDING");
                stopIntent.setPackage(getPackageName());
                sendBroadcast(stopIntent);
                return;
            case BACKGROUND:
                // 应用在后台，通过服务启动/停止录制，不拉起 MainActivity
                AppLog.d(TAG, "App is in background, starting/stopping recording via service...");
                startRecordingViaService();
                return;
            case NOT_RUNNING:
                // 应用未运行，需要启动 MainActivity
                AppLog.d(TAG, "App not running, starting MainActivity with auto_start_recording...");
                Intent intent = new Intent(this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
                intent.putExtra("auto_start_recording", true);
                startActivity(intent);
                Toast.makeText(this, "正在启动录制，请稍候...", Toast.LENGTH_SHORT).show();
                return;
        }
    }

    /**
     * 通过服务启动/停止录制（用于应用在后台时）
     */
    private void startRecordingViaService() {
        AppLog.d(TAG, "Recording via service... isRecording=" + isRecording);

        // 确保录制服务已启动
        Intent serviceIntent = new Intent(this, CameraRecordingService.class);
        startService(serviceIntent);

        // 如果服务未绑定，先绑定
        if (recordingService == null) {
            bindRecordingService();
        }

        // 使用递归检查确保服务绑定完成
        tryStartOrStopRecording(0);
    }

    /**
     * 尝试启动或停止录制（带重试机制）
     * @param retryCount 重试次数
     */
    private void tryStartOrStopRecording(int retryCount) {
        final int MAX_RETRIES = 5;
        final long RETRY_DELAY_MS = 500;

        if (recordingService != null) {
            // 服务已绑定，执行录制操作
            if (isRecording) {
                AppLog.d(TAG, "Stopping recording via service");
                // 在后台线程执行停止操作，避免阻塞主线程
                new Thread(() -> {
                    try {
                        recordingService.stopRecording();
                    } catch (Exception e) {
                        AppLog.e(TAG, "Error stopping recording in background", e);
                        // 确保状态重置
                        mainHandler.post(() -> updateRecordingState(false));
                    }
                }, "StopRecordingBg").start();
            } else {
                AppLog.d(TAG, "Starting recording via service");
                recordingService.startRecording();
            }
        } else if (retryCount < MAX_RETRIES) {
            // 服务未绑定，重试
            AppLog.w(TAG, "Recording service not bound yet, retrying... (" + (retryCount + 1) + "/" + MAX_RETRIES + ")");
            bindRecordingService();
            mainHandler.postDelayed(() -> tryStartOrStopRecording(retryCount + 1), RETRY_DELAY_MS);
        } else {
            // 重试次数耗尽，显示错误
            AppLog.e(TAG, "Failed to bind recording service after " + MAX_RETRIES + " retries");
            Toast.makeText(this, "录制服务启动失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 应用状态枚举
     */
    private enum AppState {
        FOREGROUND,     // 应用在前台运行
        BACKGROUND,     // 应用在后台运行
        NOT_RUNNING     // 应用未运行
    }

    /**
     * 获取应用当前状态
     */
    private AppState getAppState() {
        android.app.ActivityManager activityManager = (android.app.ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            java.util.List<android.app.ActivityManager.RunningTaskInfo> tasks = activityManager.getRunningTasks(10);
            if (tasks != null) {
                for (android.app.ActivityManager.RunningTaskInfo task : tasks) {
                    if (task.baseActivity != null && 
                        task.baseActivity.getPackageName().equals(getPackageName())) {
                        // 应用的任务存在
                        String topActivity = task.topActivity.getClassName();
                        if (topActivity.equals("com.kooo.evcam.MainActivity")) {
                            return AppState.FOREGROUND;
                        } else {
                            return AppState.BACKGROUND;
                        }
                    }
                }
            }
        }
        return AppState.NOT_RUNNING;
    }

    private void updateRecordingState(boolean recording) {
        isRecording = recording;

        if (recordingButton != null) {
            recordingButton.setRecording(recording);
        }

        if (recording) {
            recordingStartTime = System.currentTimeMillis();
            startTimeUpdate();
            if (timeTextView != null) {
                timeTextView.setVisibility(View.VISIBLE);
            }
        } else {
            stopTimeUpdate();
            if (timeTextView != null) {
                timeTextView.setVisibility(View.GONE);
                timeTextView.setText("00:00");
            }
        }
    }

    // ========== 时间更新 ==========

    private void startTimeUpdate() {
        stopTimeUpdate();

        timeUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording && timeTextView != null) {
                    long duration = System.currentTimeMillis() - recordingStartTime;
                    String timeStr = formatDuration(duration);
                    timeTextView.setText(timeStr);
                    mainHandler.postDelayed(this, 1000);
                }
            }
        };

        mainHandler.post(timeUpdateRunnable);
    }

    private void stopTimeUpdate() {
        if (timeUpdateRunnable != null) {
            mainHandler.removeCallbacks(timeUpdateRunnable);
            timeUpdateRunnable = null;
        }
    }

    private String formatDuration(long durationMs) {
        long seconds = durationMs / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;

        seconds = seconds % 60;
        minutes = minutes % 60;

        if (hours > 0) {
            return String.format("%02d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format("%02d:%02d", minutes, seconds);
        }
    }

    // ========== 自定义录制按钮视图（iOS 扁平化风格）==========

    private static class RecordingButtonView extends View {
        private Paint backgroundPaint;
        private Paint iconPaint;
        private Paint shadowPaint;
        private boolean isRecording = false;
        private float centerX, centerY;
        private float radius;
        private int buttonSize;
        private float cornerRadius;

        public RecordingButtonView(Context context, int buttonSize) {
            super(context);
            this.buttonSize = buttonSize;
            init();
        }

        private void init() {
            // 背景画笔 - iOS 扁平化纯色
            backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            backgroundPaint.setStyle(Paint.Style.FILL);

            // 图标画笔 - 白色
            iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            iconPaint.setStyle(Paint.Style.FILL);
            iconPaint.setColor(Color.WHITE);

            // 阴影画笔 - iOS 风格轻微阴影
            shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(Color.parseColor("#20000000")); // 半透明黑色阴影
        }

        public void setRecording(boolean recording) {
            isRecording = recording;
            invalidate();
        }

        public void setButtonSize(int size) {
            this.buttonSize = size;
            invalidate();
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            centerX = w / 2f;
            centerY = h / 2f;
            radius = Math.min(w, h) / 2f - 6;
            // iOS 风格圆角 - 圆形按钮但带轻微圆角
            cornerRadius = radius * 0.25f;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);

            // iOS 扁平化配色
            if (isRecording) {
                // 录制中 - iOS 红色
                backgroundPaint.setColor(Color.parseColor("#FF3B30"));
            } else {
                // 未录制 - iOS 绿色
                backgroundPaint.setColor(Color.parseColor("#34C759"));
            }

            // 绘制阴影（iOS 风格）
            float shadowOffset = radius * 0.08f;
            canvas.drawRoundRect(
                    centerX - radius + shadowOffset,
                    centerY - radius + shadowOffset,
                    centerX + radius + shadowOffset,
                    centerY + radius + shadowOffset,
                    cornerRadius,
                    cornerRadius,
                    shadowPaint
            );

            // 绘制圆角矩形背景（iOS 扁平化风格）
            canvas.drawRoundRect(
                    centerX - radius,
                    centerY - radius,
                    centerX + radius,
                    centerY + radius,
                    cornerRadius,
                    cornerRadius,
                    backgroundPaint
            );

            // 绘制内部图标（iOS 风格简洁图标）
            if (isRecording) {
                // 绘制停止方块（iOS 风格圆角方形）
                float rectSize = radius * 0.45f;
                float iconCornerRadius = rectSize * 0.15f;
                canvas.drawRoundRect(
                        centerX - rectSize / 2,
                        centerY - rectSize / 2,
                        centerX + rectSize / 2,
                        centerY + rectSize / 2,
                        iconCornerRadius,
                        iconCornerRadius,
                        iconPaint
                );
            } else {
                // 绘制录制圆点（实心圆）
                canvas.drawCircle(centerX, centerY, radius * 0.35f, iconPaint);
            }
        }
    }
}
