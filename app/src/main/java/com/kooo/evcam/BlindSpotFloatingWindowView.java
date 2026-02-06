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

    private float lastX, lastY;
    private float initialX, initialY;
    private int initialWidth, initialHeight;
    private boolean isResizing = false;
    private int resizeMode = 0;

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
                appConfig.setTurnSignalFloatingBounds(params.x, params.y, params.width, params.height);
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
                MainActivity mainActivity = MainActivity.getInstance();
                if (mainActivity != null) {
                    MultiCameraManager cameraManager = mainActivity.getCameraManager();
                    if (cameraManager != null) {
                        SingleCamera camera = cameraManager.getCamera(cameraPos);
                        if (camera != null) {
                            Size previewSize = camera.getPreviewSize();
                            if (previewSize != null) {
                                surface.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                            }
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
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {}
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
                return true;
        }
        return super.onTouchEvent(event);
    }

    public void setCamera(String cameraPos) {
        this.cameraPos = cameraPos;
        stopCameraPreview();
        applyTransformNow();
        if (textureView.isAvailable() && cachedSurface != null && cachedSurface.isValid()) {
            startCameraPreview(cachedSurface);
        } else {
            scheduleRetryBind();
        }
    }

    private void startCameraPreview(Surface surface) {
        MainActivity mainActivity = MainActivity.getInstance();
        if (mainActivity == null) {
            scheduleRetryBind();
            return;
        }
        MultiCameraManager cameraManager = mainActivity.getCameraManager();
        if (cameraManager == null) {
            scheduleRetryBind();
            return;
        }

        currentCamera = cameraManager.getCamera(cameraPos);
        if (currentCamera == null) {
            scheduleRetryBind();
            return;
        }
        currentCamera.setMainFloatingSurface(surface);
        currentCamera.recreateSession();
        cancelRetryBind();
    }

    private void stopCameraPreview() {
        if (currentCamera != null) {
            currentCamera.setMainFloatingSurface(null);
            currentCamera.recreateSession();
            currentCamera = null;
        }
    }

    public void show() {
        try {
            if (this.getParent() == null) {
                windowManager.addView(this, params);
                if (isAdjustPreviewMode) {
                    moveToAdjustPreviewDefaultPosition();
                }
                applyTransformNow();
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Error showing blind spot floating window: " + e.getMessage());
        }
    }

    public void applyTransformNow() {
        BlindSpotCorrection.apply(textureView, appConfig, cameraPos, currentRotation);
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
        try {
            windowManager.removeView(this);
        } catch (Exception e) {
            // Ignore
        }
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
