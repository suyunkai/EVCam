package com.kooo.evcam;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Size;
import android.view.Display;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

/**
 * 补盲选项服务
 * 负责管理主屏悬浮窗和副屏显示
 */
public class BlindSpotService extends Service {
    private static final String TAG = "BlindSpotService";

    private WindowManager secondaryWindowManager;
    private View secondaryFloatingView;
    private TextureView secondaryTextureView;
    private Surface secondaryCachedSurface;
    private View secondaryBorderView;
    private SingleCamera secondaryCamera;
    private String secondaryDesiredCameraPos = null; // 目标副屏摄像头位置

    private MainFloatingWindowView mainFloatingWindowView;
    private BlindSpotFloatingWindowView dedicatedBlindSpotWindow;
    private boolean isMainTempShown = false; // 是否为主屏临时显示
    private boolean isSecondaryTempShown = false; // 是否为副屏临时显示

    private LogcatSignalObserver signalObserver;
    private final Handler hideHandler = new Handler(Looper.getMainLooper());
    private Runnable hideRunnable;
    private String currentSignalCamera = null; // 当前转向灯触发的摄像头

    private AppConfig appConfig;
    private DisplayManager displayManager;

    private WindowManager mockControlWindowManager;
    private View mockControlView;
    private WindowManager.LayoutParams mockControlParams;

    @Override
    public void onCreate() {
        super.onCreate();
        appConfig = new AppConfig(this);
        displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        initSignalObserver();
    }

    private void initSignalObserver() {
        signalObserver = new LogcatSignalObserver((line, data1) -> {
            if (!appConfig.isTurnSignalLinkageEnabled()) return;

            String leftKeyword = appConfig.getTurnSignalLeftTriggerLog();
            String rightKeyword = appConfig.getTurnSignalRightTriggerLog();

            boolean matched = false;
            if (leftKeyword != null && !leftKeyword.isEmpty() && line.contains(leftKeyword)) {
                matched = true;
                handleTurnSignal("left");
            } else if (rightKeyword != null && !rightKeyword.isEmpty() && line.contains(rightKeyword)) {
                matched = true;
                handleTurnSignal("right");
            }

            if (matched) return;

            if (line.contains("data1 = 0") || data1 == 0) {
                startHideTimer();
                return;
            }
            if (appConfig.isTurnSignalCustomPreset()) {
                return;
            }
            if (data1 == 85) {
                handleTurnSignal("left");
            } else if (data1 == 170) {
                handleTurnSignal("right");
            }
        });
        signalObserver.start();
    }

    private void handleTurnSignal(String cameraPos) {
        // 取消隐藏计时器
        if (hideRunnable != null) {
            hideHandler.removeCallbacks(hideRunnable);
            hideRunnable = null;
        }

        if (cameraPos.equals(currentSignalCamera)) {
            AppLog.d(TAG, "转向灯相同，不重复切换: " + cameraPos);
            return;
        }

        currentSignalCamera = cameraPos;
        AppLog.d(TAG, "转向灯触发摄像头: " + cameraPos);

        boolean reuseMain = appConfig.isTurnSignalReuseMainFloating();

        if (reuseMain) {
            // --- 复用主屏悬浮窗逻辑 ---
            if (mainFloatingWindowView == null) {
                // 如果没开启，则尝试弹出临时悬浮窗
                if (WakeUpHelper.hasOverlayPermission(this)) {
                    mainFloatingWindowView = new MainFloatingWindowView(this);
                    mainFloatingWindowView.show();
                    isMainTempShown = true;
                    AppLog.d(TAG, "主屏开启临时补盲悬浮窗");
                }
            }
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.updateCamera(cameraPos);
            }
        } else {
            // --- 使用独立补盲悬浮窗逻辑 ---
            // 1. 关闭主屏悬浮窗 (如果有)
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
                isMainTempShown = false;
            }
            // 2. 显示独立补盲窗
            if (dedicatedBlindSpotWindow == null) {
                dedicatedBlindSpotWindow = new BlindSpotFloatingWindowView(this, false);
                dedicatedBlindSpotWindow.show();
            }
            dedicatedBlindSpotWindow.setCamera(cameraPos);
        }

        // --- 副屏显示逻辑 ---
        if (secondaryFloatingView == null) {
            // 如果没开启，则显示临时副屏
            isSecondaryTempShown = true;
            showSecondaryDisplay();
            AppLog.d(TAG, "副屏开启临时补盲显示");
        }
        // 动态更新副屏摄像头
        startSecondaryCameraPreviewDirectly(cameraPos);
    }

    private void startSecondaryCameraPreviewDirectly(String cameraPos) {
        secondaryDesiredCameraPos = cameraPos;
        MainActivity mainActivity = MainActivity.getInstance();
        if (mainActivity == null) return;
        MultiCameraManager cameraManager = mainActivity.getCameraManager();
        if (cameraManager == null) return;

        SingleCamera newCamera = cameraManager.getCamera(cameraPos);
        
        // 只有当 摄像头没变 且 Surface 已经绑定 且 Surface 有效时才跳过
        if (newCamera == secondaryCamera && secondaryTextureView != null && secondaryTextureView.isAvailable() 
            && secondaryCachedSurface != null && secondaryCachedSurface.isValid()) {
            AppLog.d(TAG, "副屏摄像头未变化且 Surface 已就绪，跳过 Session 重建: " + cameraPos);
            return;
        }

        stopSecondaryCameraPreview();
        secondaryCamera = newCamera;
        
        if (secondaryCamera != null && secondaryTextureView != null && secondaryTextureView.isAvailable()) {
            if (secondaryCachedSurface == null || !secondaryCachedSurface.isValid()) {
                if (secondaryCachedSurface != null) secondaryCachedSurface.release();
                android.graphics.SurfaceTexture surfaceTexture = secondaryTextureView.getSurfaceTexture();
                if (surfaceTexture != null) {
                    Size previewSize = secondaryCamera.getPreviewSize();
                    if (previewSize != null) {
                        surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                    }
                }
                secondaryCachedSurface = new Surface(secondaryTextureView.getSurfaceTexture());
            }
            AppLog.d(TAG, "副屏绑定新 Surface 并重建 Session: " + cameraPos);
            secondaryCamera.setSecondaryDisplaySurface(secondaryCachedSurface);
            secondaryCamera.recreateSession();
        } else {
            AppLog.d(TAG, "副屏 TextureView 尚未就绪，暂不绑定 Surface: " + cameraPos);
        }
    }

    private void startHideTimer() {
        if (hideRunnable != null) {
            hideHandler.removeCallbacks(hideRunnable);
        }

        int timeout = appConfig.getTurnSignalTimeout();
        AppLog.d(TAG, "转向灯熄灭，启动隐藏计时器: " + timeout + "s");

        hideRunnable = () -> {
            AppLog.d(TAG, "转向灯超时，隐藏补盲画面");
            currentSignalCamera = null;
            
            // 恢复主屏悬浮窗状态
            if (isMainTempShown && mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
                isMainTempShown = false;
            } else if (mainFloatingWindowView != null) {
                mainFloatingWindowView.updateCamera(appConfig.getMainFloatingCamera());
            }

            // 隐藏独立补盲窗
            if (dedicatedBlindSpotWindow != null) {
                dedicatedBlindSpotWindow.dismiss();
                dedicatedBlindSpotWindow = null;
                
                // 如果原本主屏悬浮窗就是开启的，补盲结束后需要恢复它
                if (appConfig.isMainFloatingEnabled()) {
                    updateMainFloatingWindow();
                }
            }

            // --- 副屏显示恢复 ---
            if (secondaryFloatingView != null) {
                if (isSecondaryTempShown) {
                    // 如果是临时开启的，则销毁
                    AppLog.d(TAG, "销毁临时副屏补盲窗口");
                    removeSecondaryView();
                    isSecondaryTempShown = false;
                } else {
                    // 如果是用户手动开启的，则切回默认摄像头
                    AppLog.d(TAG, "切回用户副屏默认摄像头: " + appConfig.getSecondaryDisplayCamera());
                    startSecondaryCameraPreviewDirectly(appConfig.getSecondaryDisplayCamera());
                }
            }
            hideRunnable = null;
        };

        hideHandler.postDelayed(hideRunnable, timeout * 1000L);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String mockSignal = intent.getStringExtra("mock_turn_signal");
            if (mockSignal != null) {
                triggerMockSignal(mockSignal);
                return START_STICKY;
            }

            String action = intent.getStringExtra("action");
            if ("setup_blind_spot_window".equals(action)) {
                showBlindSpotSetupWindow();
                return START_STICKY;
            }
        }
        updateWindows();
        return START_STICKY;
    }

    private void showBlindSpotSetupWindow() {
        if (dedicatedBlindSpotWindow != null) {
            dedicatedBlindSpotWindow.dismiss();
        }
        dedicatedBlindSpotWindow = new BlindSpotFloatingWindowView(this, true);
        dedicatedBlindSpotWindow.show();
    }

    private void updateWindows() {
        updateSecondaryDisplay();
        updateMainFloatingWindow();
        updateMockControlWindow();
        
        if (appConfig.isSecondaryDisplayEnabled()
                || appConfig.isMainFloatingEnabled()
                || appConfig.isTurnSignalLinkageEnabled()
                || appConfig.isMockTurnSignalFloatingEnabled()
                || currentSignalCamera != null) {
            CameraForegroundService.start(this, "补盲运行中", "正在显示补盲画面");
        }
        
        // 如果两个功能都关闭了，可以考虑停止服务
        // 但若转向灯联动开启，仍需要服务常驻以便“主关副关”时弹出临时补盲窗口
        if (!appConfig.isSecondaryDisplayEnabled()
                && !appConfig.isMainFloatingEnabled()
                && !appConfig.isTurnSignalLinkageEnabled()
                && !appConfig.isMockTurnSignalFloatingEnabled()) {
            stopSelf();
        }
    }

    private void triggerMockSignal(String mockSignal) {
        AppLog.d(TAG, "收到模拟转向灯信号: " + mockSignal);
        handleTurnSignal(mockSignal);

        hideHandler.postDelayed(() -> {
            AppLog.d(TAG, "模拟转向灯结束，执行熄灭");
            startHideTimer();
        }, 3000);
    }

    private void updateSecondaryDisplay() {
        if (appConfig.isSecondaryDisplayEnabled()) {
            isSecondaryTempShown = false; // 用户开启
            if (secondaryFloatingView == null) {
                showSecondaryDisplay();
            } else {
                // 如果窗口已存在，更新其位置、大小和旋转
                updateSecondaryDisplayLayout();
            }
            if (currentSignalCamera == null) {
                startSecondaryCameraPreviewDirectly(appConfig.getSecondaryDisplayCamera());
            }
        } else if (currentSignalCamera == null) {
            removeSecondaryView();
            isSecondaryTempShown = false;
        }
    }

    /**
     * 更新副屏悬浮窗的布局参数和旋转
     */
    private void updateSecondaryDisplayLayout() {
        if (secondaryFloatingView == null || secondaryWindowManager == null) return;

        int x = appConfig.getSecondaryDisplayX();
        int y = appConfig.getSecondaryDisplayY();
        int width = appConfig.getSecondaryDisplayWidth();
        int height = appConfig.getSecondaryDisplayHeight();
        int orientation = appConfig.getSecondaryDisplayOrientation();
        int rotation = appConfig.getSecondaryDisplayRotation();

        AppLog.d(TAG, "更新副屏布局: x=" + x + ", y=" + y + ", w=" + width + ", h=" + height + ", orientation=" + orientation);

        // 如果方向是 90 或 270 度，交换宽高
        int finalWidth = width;
        int finalHeight = height;
        if (orientation == 90 || orientation == 270) {
            finalWidth = height;
            finalHeight = width;
        }

        WindowManager.LayoutParams params = (WindowManager.LayoutParams) secondaryFloatingView.getLayoutParams();
        params.x = x;
        params.y = y;
        params.width = finalWidth > 0 ? finalWidth : WindowManager.LayoutParams.WRAP_CONTENT;
        params.height = finalHeight > 0 ? finalHeight : WindowManager.LayoutParams.WRAP_CONTENT;

        secondaryWindowManager.updateViewLayout(secondaryFloatingView, params);
        secondaryFloatingView.setRotation(orientation);
        applySecondaryRotation(rotation);
        
        // 设置边框
        if (secondaryBorderView != null) {
            secondaryBorderView.setVisibility(appConfig.isSecondaryDisplayBorderEnabled() ? View.VISIBLE : View.GONE);
        }
    }

    private void showSecondaryDisplay() {
        if (secondaryFloatingView != null) return; // 已经显示了

        int displayId = appConfig.getSecondaryDisplayId();
        Display display = displayManager.getDisplay(displayId);
        if (display == null) {
            AppLog.e(TAG, "找不到指定的副屏 Display ID: " + displayId);
            return;
        }

        // 创建对应显示器的 Context
        Context displayContext = createDisplayContext(display);
        secondaryWindowManager = (WindowManager) displayContext.getSystemService(Context.WINDOW_SERVICE);

        // 加载布局
        secondaryFloatingView = LayoutInflater.from(displayContext).inflate(R.layout.presentation_secondary_display, null);
        secondaryTextureView = secondaryFloatingView.findViewById(R.id.secondary_texture_view);
        secondaryBorderView = secondaryFloatingView.findViewById(R.id.secondary_border);

        // 设置边框
        secondaryBorderView.setVisibility(appConfig.isSecondaryDisplayBorderEnabled() ? View.VISIBLE : View.GONE);

        // 设置悬浮窗参数
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;

        int x = appConfig.getSecondaryDisplayX();
        int y = appConfig.getSecondaryDisplayY();
        int width = appConfig.getSecondaryDisplayWidth();
        int height = appConfig.getSecondaryDisplayHeight();
        int orientation = appConfig.getSecondaryDisplayOrientation();
        int rotation = appConfig.getSecondaryDisplayRotation();

        AppLog.d(TAG, "显示副屏: x=" + x + ", y=" + y + ", w=" + width + ", h=" + height + ", orientation=" + orientation + ", rotation=" + rotation);

        // 如果方向是 90 或 270 度，交换宽高
        int finalWidth = width;
        int finalHeight = height;
        if (orientation == 90 || orientation == 270) {
            finalWidth = height;
            finalHeight = width;
        }

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                finalWidth > 0 ? finalWidth : WindowManager.LayoutParams.WRAP_CONTENT,
                finalHeight > 0 ? finalHeight : WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );

        params.gravity = Gravity.TOP | Gravity.START;
        params.x = x;
        params.y = y;

        // 设置屏幕方向 (旋转整个容器)
        // 注意：某些车机系统对 WindowManager 根视图的 setRotation 支持有限
        // 我们尝试同时设置旋转和内部视图的变换
        secondaryFloatingView.setRotation(orientation);

        // 设置内容旋转 (将 orientation 和 rotation 结合处理)
        // 最终旋转角度 = 摄像头内容旋转 + 屏幕方向补偿
        applySecondaryRotation(rotation);

        secondaryTextureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int w, int h) {
                // 如果当前有信号，优先显示信号摄像头，否则显示配置的默认摄像头
                String cameraPos = currentSignalCamera != null ? currentSignalCamera : 
                        (secondaryDesiredCameraPos != null ? secondaryDesiredCameraPos : appConfig.getSecondaryDisplayCamera());
                AppLog.d(TAG, "副屏 Surface 就绪，启动预览: " + cameraPos);
                startSecondaryCameraPreviewDirectly(cameraPos);
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int w, int h) {}

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                stopSecondaryCameraPreview();
                if (secondaryCachedSurface != null) {
                    secondaryCachedSurface.release();
                    secondaryCachedSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {}
        });

        try {
            secondaryWindowManager.addView(secondaryFloatingView, params);
        } catch (Exception e) {
            AppLog.e(TAG, "无法添加副屏悬浮窗: " + e.getMessage());
        }
    }

    /**
     * 应用副屏内容旋转 (使用 Matrix 避免拉伸)
     */
    private void applySecondaryRotation(int rotation) {
        if (secondaryTextureView == null) return;
        
        secondaryTextureView.post(() -> {
            int viewWidth = secondaryTextureView.getWidth();
            int viewHeight = secondaryTextureView.getHeight();
            if (viewWidth == 0 || viewHeight == 0) return;

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            // 1. 设置旋转
            matrix.postRotate(rotation, centerX, centerY);

            // 2. 处理宽高比适配 (避免拉伸)
            // 假设摄像头输出是 1280x720 (16:9)
            // 我们需要根据实际的 view 尺寸和旋转角度来缩放
            if (rotation == 90 || rotation == 270) {
                float scale = (float) viewWidth / viewHeight;
                matrix.postScale(1 / scale, scale, centerX, centerY);
            }

            secondaryTextureView.setTransform(matrix);
            AppLog.d(TAG, "副屏内容旋转应用完成: " + rotation + "°, view=" + viewWidth + "x" + viewHeight);
        });
    }

    private void updateMainFloatingWindow() {
        if (appConfig.isMainFloatingEnabled()) {
            isMainTempShown = false; // 用户开启
            if (mainFloatingWindowView == null) {
                if (WakeUpHelper.hasOverlayPermission(this)) {
                    mainFloatingWindowView = new MainFloatingWindowView(this);
                    mainFloatingWindowView.show();
                }
            } else {
                mainFloatingWindowView.updateLayout();
            }
            if (mainFloatingWindowView != null && currentSignalCamera == null) {
                mainFloatingWindowView.updateCamera(appConfig.getMainFloatingCamera());
            }
        } else if (currentSignalCamera == null) {
            if (mainFloatingWindowView != null) {
                mainFloatingWindowView.dismiss();
                mainFloatingWindowView = null;
            }
            isMainTempShown = false;
        }
    }

    private void stopSecondaryCameraPreview() {
        if (secondaryCamera != null) {
            secondaryCamera.setSecondaryDisplaySurface(null);
            secondaryCamera.recreateSession();
            secondaryCamera = null;
        }
    }

    private void removeSecondaryView() {
        stopSecondaryCameraPreview();
        if (secondaryWindowManager != null && secondaryFloatingView != null) {
            try {
                secondaryWindowManager.removeView(secondaryFloatingView);
            } catch (Exception e) {
                // Ignore
            }
            secondaryFloatingView = null;
            secondaryTextureView = null;
            secondaryBorderView = null;
            secondaryWindowManager = null;
        }
        if (secondaryCachedSurface != null) {
            secondaryCachedSurface.release();
            secondaryCachedSurface = null;
        }
    }

    private void updateMockControlWindow() {
        if (appConfig.isMockTurnSignalFloatingEnabled()) {
            showMockControlWindow();
        } else {
            removeMockControlWindow();
        }
    }

    private void showMockControlWindow() {
        if (mockControlView != null) return;
        if (!WakeUpHelper.hasOverlayPermission(this)) {
            appConfig.setMockTurnSignalFloatingEnabled(false);
            return;
        }

        mockControlWindowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (mockControlWindowManager == null) {
            appConfig.setMockTurnSignalFloatingEnabled(false);
            return;
        }

        mockControlView = LayoutInflater.from(this).inflate(R.layout.view_mock_turn_signal_floating, null);
        Button leftButton = mockControlView.findViewById(R.id.btn_mock_left);
        Button rightButton = mockControlView.findViewById(R.id.btn_mock_right);
        Button closeButton = mockControlView.findViewById(R.id.btn_close);
        TextView dragHandle = mockControlView.findViewById(R.id.tv_drag_handle);

        leftButton.setOnClickListener(v -> triggerMockSignal("left"));
        rightButton.setOnClickListener(v -> triggerMockSignal("right"));
        closeButton.setOnClickListener(v -> {
            appConfig.setMockTurnSignalFloatingEnabled(false);
            updateWindows();
        });

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE;

        int x = appConfig.getMockTurnSignalFloatingX();
        int y = appConfig.getMockTurnSignalFloatingY();

        mockControlParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        mockControlParams.gravity = Gravity.TOP | Gravity.START;
        mockControlParams.x = x;
        mockControlParams.y = y;

        dragHandle.setOnTouchListener(new View.OnTouchListener() {
            private int initialX;
            private int initialY;
            private float initialTouchX;
            private float initialTouchY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (mockControlParams == null || mockControlWindowManager == null || mockControlView == null) return false;
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = mockControlParams.x;
                        initialY = mockControlParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        mockControlParams.x = initialX + (int) (event.getRawX() - initialTouchX);
                        mockControlParams.y = initialY + (int) (event.getRawY() - initialTouchY);
                        try {
                            mockControlWindowManager.updateViewLayout(mockControlView, mockControlParams);
                        } catch (Exception e) {
                            AppLog.e(TAG, "更新模拟悬浮窗位置失败: " + e.getMessage());
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        appConfig.setMockTurnSignalFloatingPosition(mockControlParams.x, mockControlParams.y);
                        return true;
                }
                return false;
            }
        });

        try {
            mockControlWindowManager.addView(mockControlView, mockControlParams);
        } catch (Exception e) {
            AppLog.e(TAG, "无法添加模拟悬浮窗: " + e.getMessage());
            mockControlView = null;
            mockControlWindowManager = null;
            mockControlParams = null;
            appConfig.setMockTurnSignalFloatingEnabled(false);
        }
    }

    private void removeMockControlWindow() {
        if (mockControlWindowManager != null && mockControlView != null) {
            try {
                mockControlWindowManager.removeView(mockControlView);
            } catch (Exception e) {
                // Ignore
            }
        }
        mockControlView = null;
        mockControlWindowManager = null;
        mockControlParams = null;
    }

    @Override
    public void onDestroy() {
        if (signalObserver != null) {
            signalObserver.stop();
        }
        if (hideRunnable != null) {
            hideHandler.removeCallbacks(hideRunnable);
        }
        removeSecondaryView();
        removeMockControlWindow();
        if (mainFloatingWindowView != null) {
            mainFloatingWindowView.dismiss();
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * 更新服务状态
     */
    public static void update(Context context) {
        Intent intent = new Intent(context, BlindSpotService.class);
        context.startService(intent);
    }
}
