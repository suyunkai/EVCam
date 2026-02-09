package com.kooo.evcam;

import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.view.TextureView;

public final class BlindSpotCorrection {
    private static final float MIN_SCALE = 0.1f;
    private static final float MAX_SCALE = 8.0f;
    private static final float MIN_TRANSLATE = -5.0f;
    private static final float MAX_TRANSLATE = 5.0f;

    private BlindSpotCorrection() {}

    /**
     * 便捷重载：窗口未互换宽高时使用（如副屏 secondaryTextureView）。
     * 矫正旋转仅做纯旋转，不做比例修正，可能出现黑角和轻微形变。
     */
    public static void apply(TextureView textureView, AppConfig appConfig, String cameraPos, int baseRotation) {
        apply(textureView, appConfig, cameraPos, baseRotation, false);
    }

    /**
     * @param windowSwapped 调用方是否已因矫正旋转互换了窗口宽高
     *                      （MainFloatingWindowView / BlindSpotFloatingWindowView 会互换，副屏不会）。
     *                      互换后需要将 correctionRotation 纳入 center-crop 计算并做比例修正，
     *                      否则 90° 时画面会被错误放大裁切。
     */
    public static void apply(TextureView textureView, AppConfig appConfig, String cameraPos, int baseRotation, boolean windowSwapped) {
        if (textureView == null || appConfig == null) return;

        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();
            if (viewWidth <= 0 || viewHeight <= 0) return;

            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            Matrix matrix = new Matrix();

            // 获取预览尺寸
            int previewW = 0, previewH = 0;
            if (cameraPos != null) {
                com.kooo.evcam.camera.MultiCameraManager cm = com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    com.kooo.evcam.camera.SingleCamera camera = cm.getCamera(cameraPos);
                    if (camera != null) {
                        android.util.Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            previewW = previewSize.getWidth();
                            previewH = previewSize.getHeight();
                        }
                    }
                }
            }

            // 获取矫正旋转角度（0~360 任意角度）
            int correctionRotation = 0;
            if (appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null) {
                correctionRotation = appConfig.getBlindSpotCorrectionRotation(cameraPos);
            }

            // center-crop 判断有效宽高比时使用的旋转角度：
            // - 悬浮窗（windowSwapped=true）：用 totalRotation（base + correction），
            //   因为悬浮窗已根据矫正旋转互换宽高，center-crop 必须匹配，
            //   否则 90° 时会误判为横版预览放进竖版窗口而放大 3x。
            // - 副屏（windowSwapped=false）：仅用 baseRotation，
            //   副屏不互换宽高，如果也用 totalRotation 会在 45° 处突变。
            int cropRotation = windowSwapped
                    ? ((baseRotation + correctionRotation) % 360 + 360) % 360
                    : ((baseRotation % 360) + 360) % 360;
            boolean isMorePortrait = isCloserToPortrait(cropRotation);

            // 居中填充（center-crop）
            if (previewW > 0 && previewH > 0) {
                float effectivePreviewW = isMorePortrait ? previewH : previewW;
                float effectivePreviewH = isMorePortrait ? previewW : previewH;
                float previewAspect = effectivePreviewW / effectivePreviewH;
                float viewAspect = (float) viewWidth / viewHeight;
                float scaleXFill, scaleYFill;
                if (previewAspect > viewAspect) {
                    scaleYFill = 1.0f;
                    scaleXFill = previewAspect / viewAspect;
                } else {
                    scaleXFill = 1.0f;
                    scaleYFill = viewAspect / previewAspect;
                }
                matrix.postScale(scaleXFill, scaleYFill, centerX, centerY);
            }

            // 应用 baseRotation（副屏方向补偿）
            if (baseRotation != 0) {
                matrix.postRotate(baseRotation, centerX, centerY);
                if (baseRotation == 90 || baseRotation == 270) {
                    float scale = (float) viewWidth / (float) viewHeight;
                    matrix.postScale(1f / scale, scale, centerX, centerY);
                }
            }

            // 应用矫正参数
            if (appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null) {
                float scaleX = clamp(appConfig.getBlindSpotCorrectionScaleX(cameraPos), MIN_SCALE, MAX_SCALE);
                float scaleY = clamp(appConfig.getBlindSpotCorrectionScaleY(cameraPos), MIN_SCALE, MAX_SCALE);
                float translateX = clamp(appConfig.getBlindSpotCorrectionTranslateX(cameraPos), MIN_TRANSLATE, MAX_TRANSLATE);
                float translateY = clamp(appConfig.getBlindSpotCorrectionTranslateY(cameraPos), MIN_TRANSLATE, MAX_TRANSLATE);

                if (correctionRotation != 0 && windowSwapped && previewW > 0 && previewH > 0) {
                    // 悬浮窗已互换宽高且有旋转 → 在 buffer 空间中旋转，保证画面比例不变形
                    //
                    // TextureView 默认将 buffer（如 1920×1080）非等比拉伸到 view（如 360×640）。
                    // 直接 postRotate 会混合 X/Y 方向不同的拉伸比，导致画面变菱形。
                    //
                    // 正确做法：重建整个矩阵
                    // 1. 还原到 buffer 坐标系（消除非等比拉伸，像素变正方形）
                    // 2. 在 buffer 空间旋转（比例绝对正确）
                    // 3. 等比缩放回 view（保持比例，填充窗口）
                    //
                    // 缩放因子用 90° 时的填充值（= max(vW/bH, vH/bW)），
                    // 保证 0°/90°/180°/270° 完美填满，中间角度保持同等大小，
                    // 超出窗口的部分自然裁切。
                    matrix.reset();

                    // 还原到 buffer 坐标系
                    matrix.postScale((float) previewW / viewWidth, (float) previewH / viewHeight, centerX, centerY);

                    // 在 buffer 空间中应用 baseRotation
                    if (baseRotation != 0) {
                        matrix.postRotate(baseRotation, centerX, centerY);
                    }

                    // 在 buffer 空间中应用矫正旋转
                    matrix.postRotate(correctionRotation, centerX, centerY);

                    // 等比缩放填充 view（用 90° 的填充因子作为常量）
                    float fillScale = Math.max((float) viewWidth / previewH, (float) viewHeight / previewW);
                    matrix.postScale(fillScale, fillScale, centerX, centerY);

                    // 用户矫正参数
                    matrix.postScale(scaleX, scaleY, centerX, centerY);
                    matrix.postTranslate(translateX * viewWidth, translateY * viewHeight);
                } else {
                    // 未互换 / 无旋转：沿用原有逻辑
                    matrix.postScale(scaleX, scaleY, centerX, centerY);

                    if (correctionRotation != 0) {
                        // 副屏窗口不互换 → 纯旋转，保留黑角
                        matrix.postRotate(correctionRotation, centerX, centerY);
                    }

                    matrix.postTranslate(translateX * viewWidth, translateY * viewHeight);
                }
            }

            textureView.setTransform(matrix);
        });
    }

    /**
     * 判断旋转角度是否更接近竖屏（即需要交换宽高）
     * 45°~135° 和 225°~315° 范围视为更接近竖屏
     */
    public static boolean isCloserToPortrait(int rotation) {
        int normalized = ((rotation % 360) + 360) % 360; // 归一化到 0~359
        int mod180 = normalized % 180; // 映射到 0~179
        return (mod180 >= 45 && mod180 < 135);
    }

    private static float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }
}

