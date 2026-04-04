package com.kooo.evcam.service;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.PixelFormat;
import android.graphics.SurfaceTexture;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.R;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.config.BlindSpotConfig;

/**
 * 补盲悬浮窗服务
 * 显示补盲画面叠加层
 * 按照D:\yuan参考实现重构
 */
public class BlindSpotOverlayService extends Service {
    private static final String TAG = "BlindSpotOverlay";

    // 服务命令
    public static final String ACTION_SHOW_OVERLAY = "com.kooo.evcam.action.SHOW_BLIND_SPOT_OVERLAY";
    public static final String ACTION_HIDE_OVERLAY = "com.kooo.evcam.action.HIDE_BLIND_SPOT_OVERLAY";
    public static final String ACTION_UPDATE_LAYOUT = "com.kooo.evcam.action.UPDATE_BLIND_SPOT_LAYOUT";

    // 额外参数
    public static final String EXTRA_SIDE = "side";
    public static final String EXTRA_X = "x";
    public static final String EXTRA_Y = "y";
    public static final String EXTRA_WIDTH = "width";
    public static final String EXTRA_HEIGHT = "height";

    // 方向常量
    public static final String SIDE_LEFT = "left";
    public static final String SIDE_RIGHT = "right";

    private final IBinder binder = new LocalBinder();
    private Handler mainHandler;

    // 配置
    private AppConfig appConfig;
    private BlindSpotConfig blindSpotConfig;

    // 窗口管理
    private WindowManager windowManager;
    private WindowManager.LayoutParams layoutParams;

    // 视图组件
    private FrameLayout containerView;
    private TextureView blindSpotTextureView;
    private LinearLayout controlBar;

    // 状态
    private boolean isOverlayShowing = false;
    private String currentSide = SIDE_LEFT;
    private int screenWidth;
    private int screenHeight;

    // 原始位置和大小（用于恢复）
    private int origX, origY, origW, origH;

    // 录制服务连接
    private CameraRecordingService recordingService;
    private boolean isServiceBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            CameraRecordingService.LocalBinder binder = (CameraRecordingService.LocalBinder) service;
            recordingService = binder.getService();
            isServiceBound = true;
            AppLog.d(TAG, "录制服务已连接");

            // 连接成功后设置补盲表面
            if (blindSpotTextureView != null && blindSpotTextureView.isAvailable()) {
                setupBlindSpotSurface();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            recordingService = null;
            isServiceBound = false;
            AppLog.d(TAG, "录制服务已断开");
        }
    };

    public class LocalBinder extends Binder {
        public BlindSpotOverlayService getService() {
            return BlindSpotOverlayService.this;
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.d(TAG, "补盲悬浮窗服务创建");

        mainHandler = new Handler(Looper.getMainLooper());
        appConfig = new AppConfig(this);
        blindSpotConfig = new BlindSpotConfig(
                getSharedPreferences("blind_spot_config", Context.MODE_PRIVATE)
        );

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);

        // 获取屏幕尺寸
        DisplayMetrics metrics = new DisplayMetrics();
        windowManager.getDefaultDisplay().getMetrics(metrics);
        screenWidth = metrics.widthPixels;
        screenHeight = metrics.heightPixels;

        // 绑定录制服务
        bindRecordingService();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_STICKY;
        }

        String action = intent.getAction();
        if (action == null) {
            return START_STICKY;
        }

        switch (action) {
            case ACTION_SHOW_OVERLAY:
                String side = intent.getStringExtra(EXTRA_SIDE);
                showOverlay(side);
                break;
            case ACTION_HIDE_OVERLAY:
                hideOverlay();
                break;
            case ACTION_UPDATE_LAYOUT:
                int x = intent.getIntExtra(EXTRA_X, origX);
                int y = intent.getIntExtra(EXTRA_Y, origY);
                int width = intent.getIntExtra(EXTRA_WIDTH, origW);
                int height = intent.getIntExtra(EXTRA_HEIGHT, origH);
                updateLayout(x, y, width, height);
                break;
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLog.d(TAG, "补盲悬浮窗服务销毁");

        hideOverlay();

        if (isServiceBound) {
            unbindService(serviceConnection);
            isServiceBound = false;
        }
    }

    // ========== 服务绑定 ==========

    private void bindRecordingService() {
        Intent intent = new Intent(this, CameraRecordingService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    // ========== 悬浮窗管理 ==========

    private void showOverlay(String side) {
        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "没有悬浮窗权限");
            Toast.makeText(this, "请授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            stopSelf();
            return;
        }

        if (isOverlayShowing) {
            if (currentSide.equals(side)) {
                return; // 已经显示相同方向的补盲
            } else {
                hideOverlay(); // 切换方向，先隐藏再显示
            }
        }

        currentSide = side != null ? side : SIDE_LEFT;
        createOverlayWindow();
        isOverlayShowing = true;

        AppLog.d(TAG, "补盲悬浮窗已显示: " + currentSide);
    }

    private void hideOverlay() {
        if (!isOverlayShowing) {
            return;
        }

        if (containerView != null && windowManager != null) {
            windowManager.removeView(containerView);
            containerView = null;
        }

        blindSpotTextureView = null;
        controlBar = null;
        isOverlayShowing = false;

        AppLog.d(TAG, "补盲悬浮窗已隐藏");
    }

    private void createOverlayWindow() {
        // 获取配置的大小和位置
        float density = getResources().getDisplayMetrics().density;
        int widthDp = blindSpotConfig.getWindowWidthDp();
        int heightDp = blindSpotConfig.getWindowHeightDp();

        origW = Math.round(widthDp * density);
        origH = Math.round(heightDp * density);
        origX = blindSpotConfig.getWindowX();
        origY = blindSpotConfig.getWindowY();

        // 创建布局参数
        int layoutType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            layoutType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            layoutType = WindowManager.LayoutParams.TYPE_SYSTEM_ALERT;
        }

        layoutParams = new WindowManager.LayoutParams(
                origW,
                origH,
                origX,
                origY,
                layoutType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);

        layoutParams.gravity = Gravity.TOP | Gravity.START;

        // 创建容器视图
        containerView = new FrameLayout(this);
        containerView.setBackgroundColor(0xFF1A1A1A);

        // 创建TextureView用于显示补盲画面
        blindSpotTextureView = new TextureView(this);
        blindSpotTextureView.setOpaque(true);
        blindSpotTextureView.setSurfaceTextureListener(surfaceTextureListener);

        FrameLayout.LayoutParams textureParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT);
        containerView.addView(blindSpotTextureView, textureParams);

        // 添加控制栏
        addControlBar();

        // 添加窗口
        windowManager.addView(containerView, layoutParams);
    }

    private void addControlBar() {
        // 动态创建控制栏
        controlBar = new LinearLayout(this);
        controlBar.setOrientation(LinearLayout.HORIZONTAL);
        controlBar.setBackgroundColor(0xCC000000); // 半透明黑色背景

        // 创建关闭按钮
        ImageButton closeButton = new ImageButton(this);
        closeButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        closeButton.setBackgroundColor(0x00000000); // 透明背景
        closeButton.setPadding(10, 10, 10, 10);
        closeButton.setOnClickListener(v -> hideOverlay());

        // 创建切换方向按钮
        ImageButton switchButton = new ImageButton(this);
        switchButton.setImageResource(android.R.drawable.ic_menu_rotate);
        switchButton.setBackgroundColor(0x00000000); // 透明背景
        switchButton.setPadding(10, 10, 10, 10);
        switchButton.setOnClickListener(v -> {
            String newSide = SIDE_LEFT.equals(currentSide) ? SIDE_RIGHT : SIDE_LEFT;
            showOverlay(newSide);
        });

        // 添加按钮到控制栏
        controlBar.addView(closeButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        controlBar.addView(switchButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // 添加控制栏到容器
        FrameLayout.LayoutParams controlParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT);
        controlParams.gravity = Gravity.TOP | Gravity.END;
        containerView.addView(controlBar, controlParams);
    }

    // ========== SurfaceTexture监听器 ==========

    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                    AppLog.d(TAG, "SurfaceTexture 可用: " + width + "x" + height);
                    setupBlindSpotSurface();
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                    AppLog.d(TAG, "SurfaceTexture 大小变化: " + width + "x" + height);
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                    AppLog.d(TAG, "SurfaceTexture 销毁");
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surface) {
                    // 帧更新
                }
            };

    private void setupBlindSpotSurface() {
        if (recordingService != null && blindSpotTextureView != null) {
            SurfaceTexture surfaceTexture = blindSpotTextureView.getSurfaceTexture();
            if (surfaceTexture != null) {
                recordingService.setBlindSpotSurfaceTexture(surfaceTexture);
            }
        }
    }

    // ========== 布局更新 ==========

    public void updateLayout(int x, int y, int width, int height) {
        if (!isOverlayShowing || layoutParams == null) {
            return;
        }

        // 限制位置和大小
        x = clampX(x, width);
        y = clampY(y, height);
        width = Math.max(100, Math.min(width, screenWidth));
        height = Math.max(60, Math.min(height, screenHeight));

        layoutParams.x = x;
        layoutParams.y = y;
        layoutParams.width = width;
        layoutParams.height = height;

        windowManager.updateViewLayout(containerView, layoutParams);

        // 保存配置
        blindSpotConfig.setWindowPosition(x, y);
        blindSpotConfig.setWindowSize(
                Math.round(width / getResources().getDisplayMetrics().density),
                Math.round(height / getResources().getDisplayMetrics().density));
    }

    private int clampX(int x, int width) {
        int minX = -width / 2;
        int maxX = screenWidth - width / 2;
        return Math.max(minX, Math.min(x, maxX));
    }

    private int clampY(int y, int height) {
        int minY = -height / 2;
        int maxY = screenHeight - height / 2;
        return Math.max(minY, Math.min(y, maxY));
    }

    // ========== 公共方法 ==========

    public boolean isOverlayShowing() {
        return isOverlayShowing;
    }

    public String getCurrentSide() {
        return currentSide;
    }

    public void setControlBarVisible(boolean visible) {
        if (controlBar != null) {
            controlBar.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
}
