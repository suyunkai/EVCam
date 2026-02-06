package com.kooo.evcam;

import android.graphics.Matrix;
import android.view.TextureView;

public final class BlindSpotCorrection {
    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 8.0f;
    private static final float MIN_TRANSLATE = -5.0f;
    private static final float MAX_TRANSLATE = 5.0f;

    private BlindSpotCorrection() {}

    public static void apply(TextureView textureView, AppConfig appConfig, String cameraPos, int baseRotation) {
        if (textureView == null || appConfig == null) return;

        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) return;

            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            Matrix matrix = new Matrix();
            if (baseRotation != 0) {
                matrix.postRotate(baseRotation, centerX, centerY);
                if (baseRotation == 90 || baseRotation == 270) {
                    float scale = (float) viewWidth / (float) viewHeight;
                    matrix.postScale(1f / scale, scale, centerX, centerY);
                }
            }

            if (appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null) {
                float scaleX = clamp(appConfig.getBlindSpotCorrectionScaleX(cameraPos), MIN_SCALE, MAX_SCALE);
                float scaleY = clamp(appConfig.getBlindSpotCorrectionScaleY(cameraPos), MIN_SCALE, MAX_SCALE);
                float translateX = clamp(appConfig.getBlindSpotCorrectionTranslateX(cameraPos), MIN_TRANSLATE, MAX_TRANSLATE);
                float translateY = clamp(appConfig.getBlindSpotCorrectionTranslateY(cameraPos), MIN_TRANSLATE, MAX_TRANSLATE);

                matrix.postScale(scaleX, scaleY, centerX, centerY);
                matrix.postTranslate(translateX * viewWidth, translateY * viewHeight);
            }

            textureView.setTransform(matrix);
        });
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}

