package com.kooo.evcam;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;

import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

import java.util.Locale;

public class FullscreenPreviewDialog extends Dialog {
    private static final String TAG = "FullscreenPreviewDialog";

    private static final float K1_MIN = -2.00f;
    private static final float K1_MAX = 2.00f;
    private static final float K1_STEP = 0.01f;

    private static final float K2_MIN = -2.00f;
    private static final float K2_MAX = 2.00f;
    private static final float K2_STEP = 0.01f;

    private static final float ZOOM_MIN = 0.50f;
    private static final float ZOOM_MAX = 3.00f;
    private static final float ZOOM_STEP = 0.01f;

    private static final float CENTER_MIN = 0.00f;
    private static final float CENTER_MAX = 1.00f;
    private static final float CENTER_STEP = 0.01f;

    private static final int LONG_PRESS_TIMEOUT = 500;

    private final String cameraPosition;
    private final AppConfig appConfig;
    private final Context context;

    private AutoFitTextureView textureView;
    private TextView tvCameraLabel;
    private TextView tvHint;
    private ScrollView panelFisheyeSettings;
    private SeekBar seekPosX, seekPosY, seekWidth, seekHeight;
    private SeekBar seekK1, seekK2, seekZoom, seekCenterX, seekCenterY, seekRotation;
    private TextView tvPosX, tvPosY, tvWidth, tvHeight;
    private TextView tvK1, tvK2, tvZoom, tvCenterX, tvCenterY, tvRotation;
    private Button btnReset, btnSave;
    private ImageButton btnClose, btnHideSettings;
    private CardView cardVideo;
    private FrameLayout videoContainer;

    private int currentPosX, currentPosY, currentWidth, currentHeight;
    private int savedPosX, savedPosY, savedWidth, savedHeight;
    private float currentK1, currentK2, currentZoom, currentCenterX, currentCenterY;
    private int currentRotation;
    private float savedK1, savedK2, savedZoom, savedCenterX, savedCenterY;
    private int savedRotation;

    private boolean isSettingsPanelVisible = false;
    private boolean isLongPressTriggered = false;
    private long touchDownTime = 0;
    private float touchDownX, touchDownY;
    private static final float TOUCH_SLOP = 20f;

    private final Matrix transformMatrix = new Matrix();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable longPressRunnable = () -> {
        if (!isLongPressTriggered) {
            isLongPressTriggered = true;
            showFisheyeSettingsPanel();
        }
    };

    private OnDismissListener onDismissListener;

    public interface OnParamsSavedListener {
        void onParamsSaved(String cameraPosition, float k1, float k2, float zoom, float centerX, float centerY, int rotation);
    }

    private OnParamsSavedListener onParamsSavedListener;

    public FullscreenPreviewDialog(@NonNull Context context, String cameraPosition) {
        super(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        this.context = context;
        this.cameraPosition = cameraPosition;
        this.appConfig = new AppConfig(context);
    }

    public void setOnParamsSavedListener(OnParamsSavedListener listener) {
        this.onParamsSavedListener = listener;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.dialog_fullscreen_preview);

        getWindow().setLayout(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT
        );
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR
        );
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        initViews();
        loadFullscreenWindowParams();
        loadSavedParams();
        setupListeners();

        if (textureView.isAvailable()) {
            setupCameraPreview();
        } else {
            textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
                    setupCameraPreview();
                }

                @Override
                public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {}

                @Override
                public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {}
            });
        }
    }

    private void initViews() {
        textureView = findViewById(R.id.texture_fullscreen);
        tvCameraLabel = findViewById(R.id.tv_camera_label);
        tvHint = findViewById(R.id.tv_hint);
        panelFisheyeSettings = findViewById(R.id.panel_fisheye_settings);
        videoContainer = findViewById(R.id.video_container);

        AppLog.d(TAG, "initViews: tvCameraLabel=" + tvCameraLabel + ", tvHint=" + tvHint + ", panelFisheyeSettings=" + panelFisheyeSettings);

        seekPosX = findViewById(R.id.seek_pos_x);
        seekPosY = findViewById(R.id.seek_pos_y);
        seekWidth = findViewById(R.id.seek_width);
        seekHeight = findViewById(R.id.seek_height);
        tvPosX = findViewById(R.id.tv_pos_x);
        tvPosY = findViewById(R.id.tv_pos_y);
        tvWidth = findViewById(R.id.tv_width);
        tvHeight = findViewById(R.id.tv_height);

        seekK1 = findViewById(R.id.seek_k1);
        seekK2 = findViewById(R.id.seek_k2);
        seekZoom = findViewById(R.id.seek_zoom);
        seekCenterX = findViewById(R.id.seek_center_x);
        seekCenterY = findViewById(R.id.seek_center_y);
        seekRotation = findViewById(R.id.seek_rotation);

        tvK1 = findViewById(R.id.tv_k1);
        tvK2 = findViewById(R.id.tv_k2);
        tvZoom = findViewById(R.id.tv_zoom);
        tvCenterX = findViewById(R.id.tv_center_x);
        tvCenterY = findViewById(R.id.tv_center_y);
        tvRotation = findViewById(R.id.tv_rotation);

        btnClose = findViewById(R.id.btn_close);
        btnReset = findViewById(R.id.btn_reset);
        btnSave = findViewById(R.id.btn_save);
        btnHideSettings = findViewById(R.id.btn_hide_settings);

        String label = getCameraLabel(cameraPosition);
        tvCameraLabel.setText(label);
        AppLog.d(TAG, "Camera label set to: " + label);

        initSeekBars();
    }

    private void initSeekBars() {
        int screenWidth = getContext().getResources().getDisplayMetrics().widthPixels;
        int screenHeight = getContext().getResources().getDisplayMetrics().heightPixels;

        seekPosX.setMax(screenWidth);
        seekPosY.setMax(screenHeight);
        seekWidth.setMax(screenWidth);
        seekHeight.setMax(screenHeight);

        seekK1.setMax(Math.round((K1_MAX - K1_MIN) / K1_STEP));
        seekK2.setMax(Math.round((K2_MAX - K2_MIN) / K2_STEP));
        seekZoom.setMax(Math.round((ZOOM_MAX - ZOOM_MIN) / ZOOM_STEP));
        seekCenterX.setMax(Math.round((CENTER_MAX - CENTER_MIN) / CENTER_STEP));
        seekCenterY.setMax(Math.round((CENTER_MAX - CENTER_MIN) / CENTER_STEP));
        seekRotation.setMax(360);
    }

    private void loadSavedParams() {
        savedPosX = appConfig.getFullscreenWindowX(cameraPosition);
        savedPosY = appConfig.getFullscreenWindowY(cameraPosition);
        savedWidth = appConfig.getFullscreenWindowWidth(cameraPosition);
        savedHeight = appConfig.getFullscreenWindowHeight(cameraPosition);

        int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
        int screenHeight = context.getResources().getDisplayMetrics().heightPixels;

        if (savedWidth <= 0) savedWidth = screenWidth * 2 / 3;
        if (savedHeight <= 0) savedHeight = screenHeight * 2 / 3;

        savedK1 = appConfig.getFisheyeCorrectionK1(cameraPosition);
        savedK2 = appConfig.getFisheyeCorrectionK2(cameraPosition);
        savedZoom = appConfig.getFisheyeCorrectionZoom(cameraPosition);
        savedCenterX = appConfig.getFisheyeCorrectionCenterX(cameraPosition);
        savedCenterY = appConfig.getFisheyeCorrectionCenterY(cameraPosition);
        savedRotation = appConfig.getFisheyeCorrectionRotation(cameraPosition);

        currentPosX = savedPosX > 0 ? savedPosX : 0;
        currentPosY = savedPosY > 0 ? savedPosY : 0;
        currentWidth = savedWidth;
        currentHeight = savedHeight;
        currentK1 = savedK1;
        currentK2 = savedK2;
        currentZoom = savedZoom;
        currentCenterX = savedCenterX;
        currentCenterY = savedCenterY;
        currentRotation = savedRotation;

        updateSeekBarsFromCurrentParams();
        updateTextViewsFromCurrentParams();
        updateWindowFromParams();
    }

    private void updateSeekBarsFromCurrentParams() {
        seekPosX.setProgress(currentPosX);
        seekPosY.setProgress(currentPosY);
        seekWidth.setProgress(currentWidth);
        seekHeight.setProgress(currentHeight);
        seekK1.setProgress(k1ToProgress(currentK1));
        seekK2.setProgress(k2ToProgress(currentK2));
        seekZoom.setProgress(zoomToProgress(currentZoom));
        seekCenterX.setProgress(centerToProgress(currentCenterX));
        seekCenterY.setProgress(centerToProgress(currentCenterY));
        seekRotation.setProgress(currentRotation);
    }

    private void updateTextViewsFromCurrentParams() {
        tvPosX.setText(String.valueOf(currentPosX));
        tvPosY.setText(String.valueOf(currentPosY));
        tvWidth.setText(String.valueOf(currentWidth));
        tvHeight.setText(String.valueOf(currentHeight));
        tvK1.setText(format2(currentK1));
        tvK2.setText(format2(currentK2));
        tvZoom.setText(format2(currentZoom));
        tvCenterX.setText(format2(currentCenterX));
        tvCenterY.setText(format2(currentCenterY));
        tvRotation.setText(currentRotation + "°");
    }

    private void updateWindowFromParams() {
        if (videoContainer == null) {
            AppLog.w(TAG, "updateWindowFromParams: videoContainer is null");
            return;
        }

        AppLog.d(TAG, "updateWindowFromParams: posX=" + currentPosX + ", posY=" + currentPosY +
                ", width=" + currentWidth + ", height=" + currentHeight);

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(currentWidth, currentHeight);
        params.setMargins(currentPosX, currentPosY, 0, 0);
        videoContainer.setLayoutParams(params);
        videoContainer.requestLayout();
    }

    private void setupListeners() {
        btnClose.setOnClickListener(v -> dismiss());

        btnHideSettings.setOnClickListener(v -> hideFisheyeSettingsPanel());

        btnReset.setOnClickListener(v -> {
            currentPosX = 0;
            currentPosY = 0;
            currentWidth = 0;
            currentHeight = 0;
            currentK1 = 0.0f;
            currentK2 = 0.0f;
            currentZoom = 1.0f;
            currentCenterX = 0.5f;
            currentCenterY = 0.5f;
            currentRotation = 0;
            updateSeekBarsFromCurrentParams();
            updateTextViewsFromCurrentParams();
            updateWindowFromParams();
            applyTransform();
        });

        btnSave.setOnClickListener(v -> {
            savedPosX = currentPosX;
            savedPosY = currentPosY;
            savedWidth = currentWidth;
            savedHeight = currentHeight;
            savedK1 = currentK1;
            savedK2 = currentK2;
            savedZoom = currentZoom;
            savedCenterX = currentCenterX;
            savedCenterY = currentCenterY;
            savedRotation = currentRotation;

            appConfig.setFullscreenWindowX(cameraPosition, savedPosX);
            appConfig.setFullscreenWindowY(cameraPosition, savedPosY);
            appConfig.setFullscreenWindowWidth(cameraPosition, savedWidth);
            appConfig.setFullscreenWindowHeight(cameraPosition, savedHeight);
            appConfig.setFisheyeCorrectionK1(cameraPosition, savedK1);
            appConfig.setFisheyeCorrectionK2(cameraPosition, savedK2);
            appConfig.setFisheyeCorrectionZoom(cameraPosition, savedZoom);
            appConfig.setFisheyeCorrectionCenterX(cameraPosition, savedCenterX);
            appConfig.setFisheyeCorrectionCenterY(cameraPosition, savedCenterY);
            appConfig.setFisheyeCorrectionRotation(cameraPosition, savedRotation);

            if (onParamsSavedListener != null) {
                onParamsSavedListener.onParamsSaved(cameraPosition, savedK1, savedK2, savedZoom, savedCenterX, savedCenterY, savedRotation);
            }

            hideFisheyeSettingsPanel();
        });

        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;

                if (seekBar == seekPosX) {
                    currentPosX = progress;
                    tvPosX.setText(String.valueOf(progress));
                    updateWindowFromParams();
                } else if (seekBar == seekPosY) {
                    currentPosY = progress;
                    tvPosY.setText(String.valueOf(progress));
                    updateWindowFromParams();
                } else if (seekBar == seekWidth) {
                    currentWidth = progress;
                    tvWidth.setText(String.valueOf(progress));
                    updateWindowFromParams();
                } else if (seekBar == seekHeight) {
                    currentHeight = progress;
                    tvHeight.setText(String.valueOf(progress));
                    updateWindowFromParams();
                } else if (seekBar == seekK1) {
                    currentK1 = progressToK1(progress);
                    tvK1.setText(format2(currentK1));
                    applyTransform();
                } else if (seekBar == seekK2) {
                    currentK2 = progressToK2(progress);
                    tvK2.setText(format2(currentK2));
                    applyTransform();
                } else if (seekBar == seekZoom) {
                    currentZoom = progressToZoom(progress);
                    tvZoom.setText(format2(currentZoom));
                    applyTransform();
                } else if (seekBar == seekCenterX) {
                    currentCenterX = progressToCenter(progress);
                    tvCenterX.setText(format2(currentCenterX));
                    applyTransform();
                } else if (seekBar == seekCenterY) {
                    currentCenterY = progressToCenter(progress);
                    tvCenterY.setText(format2(currentCenterY));
                    applyTransform();
                } else if (seekRotation == seekBar) {
                    currentRotation = progress;
                    tvRotation.setText(currentRotation + "°");
                    applyTransform();
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        seekPosX.setOnSeekBarChangeListener(seekListener);
        seekPosY.setOnSeekBarChangeListener(seekListener);
        seekWidth.setOnSeekBarChangeListener(seekListener);
        seekHeight.setOnSeekBarChangeListener(seekListener);
        seekK1.setOnSeekBarChangeListener(seekListener);
        seekK2.setOnSeekBarChangeListener(seekListener);
        seekZoom.setOnSeekBarChangeListener(seekListener);
        seekCenterX.setOnSeekBarChangeListener(seekListener);
        seekCenterY.setOnSeekBarChangeListener(seekListener);
        seekRotation.setOnSeekBarChangeListener(seekListener);

        textureView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    touchDownTime = System.currentTimeMillis();
                    touchDownX = event.getX();
                    touchDownY = event.getY();
                    isLongPressTriggered = false;
                    handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT);
                    return true;

                case MotionEvent.ACTION_MOVE:
                    float dx = Math.abs(event.getX() - touchDownX);
                    float dy = Math.abs(event.getY() - touchDownY);
                    if (dx > TOUCH_SLOP || dy > TOUCH_SLOP) {
                        handler.removeCallbacks(longPressRunnable);
                    }
                    return true;

                case MotionEvent.ACTION_UP:
                    handler.removeCallbacks(longPressRunnable);
                    if (!isLongPressTriggered) {
                        long touchDuration = System.currentTimeMillis() - touchDownTime;
                        if (touchDuration < LONG_PRESS_TIMEOUT) {
                            if (isSettingsPanelVisible) {
                                hideFisheyeSettingsPanel();
                            } else {
                                dismiss();
                            }
                        }
                    }
                    return true;

                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longPressRunnable);
                    return true;
            }
            return false;
        });
    }

    private void setupCameraPreview() {
        MultiCameraManager cameraManager = CameraManagerHolder.getInstance().getCameraManager();
        if (cameraManager == null) {
            AppLog.e(TAG, "CameraManager is null");
            return;
        }

        SingleCamera camera = cameraManager.getCamera(cameraPosition);
        if (camera == null) {
            AppLog.e(TAG, "Camera not found: " + cameraPosition);
            return;
        }

        AppLog.d(TAG, "Setting up fullscreen preview for " + cameraPosition);

        android.util.Size previewSize = camera.getPreviewSize();
        if (previewSize != null) {
            AppLog.d(TAG, "Preview size: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            textureView.getSurfaceTexture().setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
        }

        Surface surface = new Surface(textureView.getSurfaceTexture());
        AppLog.d(TAG, "Created fullscreen surface: " + surface + ", isValid=" + surface.isValid());

        camera.setFullscreenPreviewSurface(surface, textureView.getSurfaceTexture());

        applyTransform();
    }

    private void restoreMainPreview() {
        MultiCameraManager cameraManager = CameraManagerHolder.getInstance().getCameraManager();
        if (cameraManager == null) return;

        SingleCamera camera = cameraManager.getCamera(cameraPosition);
        if (camera == null) return;

        camera.clearFullscreenPreviewSurface();
    }

    private void applyTransform() {
        if (textureView == null || !textureView.isAvailable()) return;

        int viewWidth = textureView.getWidth();
        int viewHeight = textureView.getHeight();
        if (viewWidth == 0 || viewHeight == 0) return;

        transformMatrix.reset();

        float centerX = viewWidth / 2f;
        float centerY = viewHeight / 2f;

        transformMatrix.postTranslate(-centerX, -centerY);

        transformMatrix.postRotate(currentRotation);

        float scale = currentZoom;
        transformMatrix.postScale(scale, scale);

        float dx = (currentCenterX - 0.5f) * viewWidth * 2f;
        float dy = (currentCenterY - 0.5f) * viewHeight * 2f;
        transformMatrix.postTranslate(centerX + dx, centerY + dy);

        textureView.setTransform(transformMatrix);
    }

    private void showFisheyeSettingsPanel() {
        if (!isSettingsPanelVisible) {
            isSettingsPanelVisible = true;
            panelFisheyeSettings.setVisibility(View.VISIBLE);
            panelFisheyeSettings.setAlpha(0f);
            panelFisheyeSettings.animate()
                    .alpha(1f)
                    .setDuration(200)
                    .start();
            tvHint.setVisibility(View.GONE);
        }
    }

    private void hideFisheyeSettingsPanel() {
        if (isSettingsPanelVisible) {
            isSettingsPanelVisible = false;
            panelFisheyeSettings.animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction(() -> {
                        panelFisheyeSettings.setVisibility(View.GONE);
                        tvHint.setVisibility(View.VISIBLE);
                    })
                    .start();
        }
    }

    private String getCameraLabel(String position) {
        switch (position) {
            case "front": return "前";
            case "back": return "后";
            case "left": return "左";
            case "right": return "右";
            default: return position;
        }
    }

    private int k1ToProgress(float k1) {
        float clamped = Math.max(K1_MIN, Math.min(K1_MAX, k1));
        return Math.round((clamped - K1_MIN) / K1_STEP);
    }

    private float progressToK1(int progress) {
        return K1_MIN + progress * K1_STEP;
    }

    private int k2ToProgress(float k2) {
        float clamped = Math.max(K2_MIN, Math.min(K2_MAX, k2));
        return Math.round((clamped - K2_MIN) / K2_STEP);
    }

    private float progressToK2(int progress) {
        return K2_MIN + progress * K2_STEP;
    }

    private int zoomToProgress(float zoom) {
        float clamped = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, zoom));
        return Math.round((clamped - ZOOM_MIN) / ZOOM_STEP);
    }

    private float progressToZoom(int progress) {
        return ZOOM_MIN + progress * ZOOM_STEP;
    }

    private int centerToProgress(float center) {
        float clamped = Math.max(CENTER_MIN, Math.min(CENTER_MAX, center));
        return Math.round((clamped - CENTER_MIN) / CENTER_STEP);
    }

    private float progressToCenter(int progress) {
        return CENTER_MIN + progress * CENTER_STEP;
    }

    private String format2(float v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private void loadFullscreenWindowParams() {
    }

    private void saveFullscreenWindowParams() {
        if (videoContainer == null) return;

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) videoContainer.getLayoutParams();
        appConfig.setFullscreenWindowX(cameraPosition, params.leftMargin);
        appConfig.setFullscreenWindowY(cameraPosition, params.topMargin);
        appConfig.setFullscreenWindowWidth(cameraPosition, params.width);
        appConfig.setFullscreenWindowHeight(cameraPosition, params.height);
    }

    @Override
    public void dismiss() {
        handler.removeCallbacks(longPressRunnable);

        restoreMainPreview();
        saveFullscreenWindowParams();

        if (onDismissListener != null) {
            onDismissListener.onDismiss(this);
        }

        super.dismiss();
    }

    @Override
    public void setOnDismissListener(OnDismissListener listener) {
        this.onDismissListener = listener;
    }
}
