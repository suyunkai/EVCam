package com.kooo.evcam;

import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

/**
 * 独立补盲悬浮窗视图
 * 支持拖动和边缘缩放
 */
public class BlindSpotFloatingWindowView extends FrameLayout {
    private static final String TAG = "BlindSpotFloatingWindowView";
    private static final int RESIZE_THRESHOLD = 50;

    private WindowManager windowManager;
    private WindowManager.LayoutParams params;
    private AppConfig appConfig;
    private TextureView textureView;
    private Surface cachedSurface;
    private SingleCamera currentCamera;
    private String cameraPos = "right"; // 默认用右摄像头测试
    private boolean isSetupMode = false;
    private int currentRotation = 0;
    private boolean isAdjustPreviewMode = false;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int retryBindCount = 0;
    private Runnable retryBindRunnable;
    private android.animation.ValueAnimator windowAnimator;
    private boolean pendingShowAnimation = false;
    private Runnable showAnimFallback;

    private float lastX, lastY;
    private float initialX, initialY;
    private int initialWidth, initialHeight;
    private boolean isResizing = false;
    private int resizeMode = 0;
    private boolean isCurrentlySwapped = false;
    private boolean hasUnsavedResize = false;

    public BlindSpotFloatingWindowView(Context context, boolean isSetupMode) {
        super(context);
        this.isSetupMode = isSetupMode;
        appConfig = new AppConfig(context);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        init();
    }

    private void init() {
        LayoutInflater.from(getContext()).inflate(R.layout.view_blind_spot_floating, this);
        textureView = findViewById(R.id.blind_spot_texture_view);
        View saveLayout = findViewById(R.id.layout_save_config);
        View saveButton = findViewById(R.id.btn_save_blind_spot_config);
        View rotateButton = findViewById(R.id.btn_rotate_blind_spot);

        currentRotation = appConfig.getTurnSignalFloatingRotation();
        applyTransformNow();

        if (isSetupMode) {
            saveLayout.setVisibility(View.VISIBLE);
            saveButton.setOnClickListener(v -> {
                hasUnsavedResize = false;
                // 若宽高因矫正旋转而交换过，保存前还原为基础值
                int saveW = params.width;
                int saveH = params.height;
                if (isCurrentlySwapped) {
                    saveW = params.height;
                    saveH = params.width;
                }
                appConfig.setTurnSignalFloatingBounds(params.x, params.y, saveW, saveH);
                appConfig.setTurnSignalFloatingRotation(currentRotation);
                dismiss();
            });
            rotateButton.setOnClickListener(v -> {
                currentRotation = (currentRotation + 90) % 360;
                applyTransformNow();
            });
        }

        params = new WindowManager.LayoutParams(
                appConfig.getTurnSignalFloatingWidth(),
                appConfig.getTurnSignalFloatingHeight(),
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = appConfig.getTurnSignalFloatingX();
        params.y = appConfig.getTurnSignalFloatingY();

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    SingleCamera camera = cm.getCamera(cameraPos);
                    if (camera != null) {
                        Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            surface.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                        }
                    }
                }
                if (cachedSurface != null) cachedSurface.release();
                cachedSurface = new Surface(surface);
                startCameraPreview(cachedSurface);
                applyTransformNow();
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                applyTransformNow();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                cancelRetryBind();
                stopCameraPreview();
                if (cachedSurface != null) {
                    cachedSurface.release();
                    cachedSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
                if (pendingShowAnimation) {
                    pendingShowAnimation = false;
                    if (showAnimFallback != null) {
                        mainHandler.removeCallbacks(showAnimFallback);
                        showAnimFallback = null;
                    }
                    playShowAnimation();
                }
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isSetupMode && !isAdjustPreviewMode) return super.onTouchEvent(event);

        float x = event.getRawX();
        float y = event.getRawY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x;
                lastY = y;
                initialX = params.x;
                initialY = params.y;
                initialWidth = params.width;
                initialHeight = params.height;

                if (isSetupMode) {
                    float localX = event.getX();
                    float localY = event.getY();
                    resizeMode = 0;
                    if (localX < RESIZE_THRESHOLD) resizeMode |= 1;
                    if (localX > getWidth() - RESIZE_THRESHOLD) resizeMode |= 2;
                    if (localY < RESIZE_THRESHOLD) resizeMode |= 4;
                    if (localY > getHeight() - RESIZE_THRESHOLD) resizeMode |= 8;
                    isResizing = resizeMode != 0;
                } else {
                    isResizing = false;
                    resizeMode = 0;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX;
                float dy = y - lastY;

                if (isSetupMode && isResizing) {
                    if ((resizeMode & 1) != 0) {
                        int newWidth = (int) (initialWidth - dx);
                        if (newWidth > 200) {
                            params.width = newWidth;
                            params.x = (int) (initialX + dx);
                        }
                    }
                    if ((resizeMode & 2) != 0) {
                        int newWidth = (int) (initialWidth + dx);
                        if (newWidth > 200) params.width = newWidth;
                    }
                    if ((resizeMode & 4) != 0) {
                        int newHeight = (int) (initialHeight - dy);
                        if (newHeight > 200) {
                            params.height = newHeight;
                            params.y = (int) (initialY + dy);
                        }
                    }
                    if ((resizeMode & 8) != 0) {
                        int newHeight = (int) (initialHeight + dy);
                        if (newHeight > 200) params.height = newHeight;
                    }
                } else {
                    params.x = (int) (initialX + dx);
                    params.y = (int) (initialY + dy);
                }
                
                windowManager.updateViewLayout(this, params);
                return true;

            case MotionEvent.ACTION_UP:
                if (isResizing) {
                    hasUnsavedResize = true;
                }
                isResizing = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    /**
     * 仅设置摄像头位置（不触发预览切换）。
     * 用于 show() 前设定初始摄像头，避免 onSurfaceTextureAvailable 使用默认值。
     */
    public void setCameraPos(String cameraPos) {
        this.cameraPos = cameraPos;
    }

    public void setCamera(String cameraPos) {
        this.cameraPos = cameraPos;
        stopCameraPreview(true); // 切换摄像头时使用紧急模式清除旧surface
        applyTransformNow();

        // 更新 SurfaceTexture 的 buffer size 以匹配新摄像头的预览分辨率
        // 避免 Surface 尺寸与摄像头配置不匹配导致 session 创建失败
        if (textureView.isAvailable()) {
            android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    SingleCamera camera = cm.getCamera(cameraPos);
                    if (camera != null) {
                        Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                            AppLog.d(TAG, "Updated buffer size to " + previewSize.getWidth() + "x" + previewSize.getHeight() + " for " + cameraPos);
                        }
                    }
                }
            }
        }

        if (textureView.isAvailable() && cachedSurface != null && cachedSurface.isValid()) {
            startCameraPreview(cachedSurface, true);
        } else {
            scheduleRetryBind();
        }
    }

    private void startCameraPreview(Surface surface) {
        startCameraPreview(surface, false);
    }

    private void startCameraPreview(Surface surface, boolean urgent) {
        MultiCameraManager cameraManager = CameraManagerHolder.getInstance().getCameraManager();
        if (cameraManager == null) {
            cameraManager = CameraManagerHolder.getInstance().getOrInit(getContext());
            if (cameraManager == null) {
                scheduleRetryBind();
                return;
            }
        }

        currentCamera = cameraManager.getCamera(cameraPos);
        if (currentCamera == null) {
            scheduleRetryBind();
            return;
        }
        currentCamera.setMainFloatingSurface(surface);

        // 如果摄像头硬件还未打开（后台初始化时不打开），先打开
        if (!currentCamera.isCameraOpened()) {
            AppLog.d(TAG, "Camera not opened yet, opening now for " + cameraPos);
            // 先打开当前需要的摄像头
            currentCamera.openCamera();
            // 延迟打开其他摄像头
            final MultiCameraManager cm = cameraManager;
            mainHandler.postDelayed(() -> {
                AppLog.d(TAG, "Deferred opening remaining cameras");
                cm.openAllCameras();
            }, 500);
        } else {
            currentCamera.recreateSession(urgent);
        }
        cancelRetryBind();
    }

    private void stopCameraPreview() {
        stopCameraPreview(false);
    }

    private void stopCameraPreview(boolean urgent) {
        if (currentCamera != null) {
            // 立即停止推帧，防止 Surface 销毁后 queueBuffer abandoned 刷屏
            currentCamera.stopRepeatingNow();
            currentCamera.setMainFloatingSurface(null);
            currentCamera.recreateSession(urgent);
            currentCamera = null;
        }
    }

    public void show() {
        try {
            if (this.getParent() == null) {
                boolean animEnabled = appConfig.isFloatingWindowAnimationEnabled();

                // 等待首帧画面到达后再显示，避免黑屏闪烁
                if (animEnabled) {
                    setScaleX(0.85f);
                    setScaleY(0.85f);
                }
                params.alpha = 0f;
                pendingShowAnimation = true;

                windowManager.addView(this, params);
                if (isAdjustPreviewMode) {
                    moveToAdjustPreviewDefaultPosition();
                }
                applyTransformNow();

                // 安全超时：如果摄像头迟迟没有推帧，最多等 800ms 后也直接显示
                showAnimFallback = () -> {
                    if (pendingShowAnimation) {
                        pendingShowAnimation = false;
                        playShowAnimation();
                    }
                };
                mainHandler.postDelayed(showAnimFallback, 800);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Error showing blind spot floating window: " + e.getMessage());
        }
    }

    private void playShowAnimation() {
        boolean animEnabled = appConfig.isFloatingWindowAnimationEnabled();

        if (!animEnabled) {
            // 无动效：直接显示
            setScaleX(1f);
            setScaleY(1f);
            params.alpha = 1f;
            try {
                if (getParent() != null) {
                    windowManager.updateViewLayout(BlindSpotFloatingWindowView.this, params);
                }
            } catch (Exception e) {}
            return;
        }

        // 有动效：缩放 + 淡入
        if (windowAnimator != null) windowAnimator.cancel();
        windowAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        windowAnimator.setDuration(250);
        windowAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        windowAnimator.addUpdateListener(animation -> {
            float val = (float) animation.getAnimatedValue();
            setScaleX(0.85f + 0.15f * val);
            setScaleY(0.85f + 0.15f * val);
            params.alpha = val;
            try {
                if (getParent() != null) {
                    windowManager.updateViewLayout(BlindSpotFloatingWindowView.this, params);
                }
            } catch (Exception e) {}
        });
        windowAnimator.start();
    }

    public void applyTransformNow() {
        // 矫正旋转更接近竖屏时，悬浮窗宽高互换，让画面自然填满不裁切
        int correctionRotation = 0;
        if (appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null) {
            correctionRotation = appConfig.getBlindSpotCorrectionRotation(cameraPos);
        }
        int baseW = appConfig.getTurnSignalFloatingWidth();
        int baseH = appConfig.getTurnSignalFloatingHeight();
        boolean shouldSwap = BlindSpotCorrection.isCloserToPortrait(correctionRotation);
        isCurrentlySwapped = shouldSwap;
        int targetW = shouldSwap ? baseH : baseW;
        int targetH = shouldSwap ? baseW : baseH;

        // 用户正在拖动缩放或有未保存的缩放时，不覆盖 params，以免打断手势或丢失调整
        if (!isResizing && !hasUnsavedResize
                && params != null && (params.width != targetW || params.height != targetH)) {
            params.width = targetW;
            params.height = targetH;
            try {
                if (getParent() != null) {
                    windowManager.updateViewLayout(this, params);
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        BlindSpotCorrection.apply(textureView, appConfig, cameraPos, currentRotation, isCurrentlySwapped);
    }

    public void enableAdjustPreviewMode() {
        isAdjustPreviewMode = true;
        if (params != null) {
            int targetW = dpToPx(320);
            int targetH = dpToPx(180);
            if (targetW > 0 && targetH > 0) {
                params.width = targetW;
                params.height = targetH;
            }
        }
        moveToAdjustPreviewDefaultPosition();
    }

    private void moveToAdjustPreviewDefaultPosition() {
        if (params == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        } catch (Exception e) {
            metrics = getResources().getDisplayMetrics();
        }
        int margin = dpToPx(16);
        int w = params.width > 0 ? params.width : dpToPx(320);
        int h = params.height > 0 ? params.height : dpToPx(180);
        int x = metrics.widthPixels - w - margin;
        int y = metrics.heightPixels - h - margin;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        setPosition(x, y);
    }

    public void setPosition(int x, int y) {
        if (params == null) return;
        params.x = x;
        params.y = y;
        if (getParent() != null) {
            try {
                windowManager.updateViewLayout(this, params);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    public void dismiss() {
        cancelRetryBind();
        stopCameraPreview();
        pendingShowAnimation = false;
        if (showAnimFallback != null) {
            mainHandler.removeCallbacks(showAnimFallback);
            showAnimFallback = null;
        }

        if (getParent() == null) return;

        boolean animEnabled = appConfig.isFloatingWindowAnimationEnabled();

        if (!animEnabled) {
            // 无动效：直接移除
            params.alpha = 1f;
            try {
                windowManager.removeView(this);
            } catch (Exception e) {}
            return;
        }

        // 关闭动效：缩放 + 淡出
        if (windowAnimator != null) {
            windowAnimator.cancel();
            windowAnimator = null;
        }
        windowAnimator = android.animation.ValueAnimator.ofFloat(1f, 0f);
        windowAnimator.setDuration(200);
        windowAnimator.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));
        windowAnimator.addUpdateListener(animation -> {
            float val = (float) animation.getAnimatedValue();
            setScaleX(0.85f + 0.15f * val);
            setScaleY(0.85f + 0.15f * val);
            params.alpha = val;
            try {
                if (getParent() != null) {
                    windowManager.updateViewLayout(BlindSpotFloatingWindowView.this, params);
                }
            } catch (Exception e1) {}
        });
        windowAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                params.alpha = 1f;
                try {
                    if (getParent() != null) {
                        windowManager.removeView(BlindSpotFloatingWindowView.this);
                    }
                } catch (Exception e) {}
                windowAnimator = null;
            }
        });
        windowAnimator.start();
    }

    private void scheduleRetryBind() {
        cancelRetryBind();
        retryBindCount++;
        long delayMs;
        if (retryBindCount <= 10) {
            delayMs = 500;
        } else if (retryBindCount <= 30) {
            delayMs = 1000;
        } else {
            delayMs = 3000;
        }
        retryBindRunnable = () -> {
            if (getParent() == null) return;
            if (textureView == null || !textureView.isAvailable()) {
                scheduleRetryBind();
                return;
            }
            if (cachedSurface == null || !cachedSurface.isValid()) {
                android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
                if (st == null) {
                    scheduleRetryBind();
                    return;
                }
                if (cachedSurface != null) {
                    try { cachedSurface.release(); } catch (Exception e) {}
                }
                cachedSurface = new Surface(st);
            }
            startCameraPreview(cachedSurface);
        };
        mainHandler.postDelayed(retryBindRunnable, delayMs);
    }

    private void cancelRetryBind() {
        if (retryBindRunnable != null) {
            mainHandler.removeCallbacks(retryBindRunnable);
            retryBindRunnable = null;
        }
        retryBindCount = 0;
    }
}
