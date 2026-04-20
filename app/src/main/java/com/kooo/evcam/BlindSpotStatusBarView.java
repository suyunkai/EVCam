package com.kooo.evcam;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * 补盲悬浮窗状态栏 — 纯动效方向指示视图（无文字），半透明叠加在摄像头画面上方。
 * <p>
 * 5 种扁平化动效 + 关闭模式。设计原则：简洁、明亮、一目了然。
 */
public class BlindSpotStatusBarView extends View {

    public static final int STYLE_OFF = 0;
    public static final int STYLE_SEQUENTIAL = 1;
    public static final int STYLE_COMET = 2;
    public static final int STYLE_RIPPLE = 3;
    public static final int STYLE_GRADIENT_FILL = 4;
    public static final int STYLE_ARROW_RIPPLE = 5;

    private int colorR = 255, colorG = 191, colorB = 64;

    private int animationStyle = STYLE_SEQUENTIAL;
    private String direction = "";

    private ValueAnimator flowAnimator;
    private ValueAnimator pulseAnimator;
    private ValueAnimator fadeAnimator;

    private float flowPhase = 0f;
    private float pulseValue = 1f;
    private float dirAlpha = 0f;

    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chevronPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path chevronPath = new Path();
    private final RectF rectF = new RectF();

    private float dp;

    public BlindSpotStatusBarView(Context context) {
        super(context);
        init();
    }

    public BlindSpotStatusBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public BlindSpotStatusBarView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        init();
    }

    private void init() {
        dp = getResources().getDisplayMetrics().density;

        chevronPaint.setStyle(Paint.Style.STROKE);
        chevronPaint.setStrokeWidth(2.5f * dp);
        chevronPaint.setStrokeCap(Paint.Cap.ROUND);
        chevronPaint.setStrokeJoin(Paint.Join.ROUND);

        flowAnimator = ValueAnimator.ofFloat(0f, 1f);
        flowAnimator.setDuration(1500);
        flowAnimator.setRepeatCount(ValueAnimator.INFINITE);
        flowAnimator.setInterpolator(new LinearInterpolator());
        flowAnimator.addUpdateListener(a -> {
            flowPhase = (float) a.getAnimatedValue();
            invalidate();
        });

        pulseAnimator = ValueAnimator.ofFloat(0.8f, 1f);
        pulseAnimator.setDuration(800);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setRepeatMode(ValueAnimator.REVERSE);
        pulseAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        pulseAnimator.addUpdateListener(a -> pulseValue = (float) a.getAnimatedValue());
    }

    public void setAnimationStyle(int style) {
        if (style == animationStyle) return;
        animationStyle = style;
        updateFlowDuration();
        invalidate();
    }

    public int getAnimationStyle() {
        return animationStyle;
    }

    public void setEffectColor(int color) {
        colorR = Color.red(color);
        colorG = Color.green(color);
        colorB = Color.blue(color);
        invalidate();
    }

    private void updateFlowDuration() {
        long duration;
        switch (animationStyle) {
            case STYLE_SEQUENTIAL:    duration = 1200; break;
            case STYLE_COMET:         duration = 1800; break;
            case STYLE_RIPPLE:        duration = 1500; break;
            case STYLE_GRADIENT_FILL: duration = 1600; break;
            case STYLE_ARROW_RIPPLE:  duration = 1300; break;
            default:                  duration = 1500; break;
        }
        flowAnimator.setDuration(duration);
    }

    public void setDirection(String dir) {
        if (dir == null) dir = "";
        if (dir.equals(direction)) return;
        direction = dir;

        float target = dir.isEmpty() ? 0f : 1f;

        if (fadeAnimator != null) fadeAnimator.cancel();
        fadeAnimator = ValueAnimator.ofFloat(dirAlpha, target);
        fadeAnimator.setDuration(300);
        fadeAnimator.setInterpolator(new AccelerateDecelerateInterpolator());
        fadeAnimator.addUpdateListener(a -> {
            dirAlpha = (float) a.getAnimatedValue();
            invalidate();
        });
        if (target == 0f) {
            fadeAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator a) {
                    if (direction.isEmpty()) {
                        flowAnimator.cancel();
                        pulseAnimator.cancel();
                    }
                }
            });
        }
        fadeAnimator.start();

        if (!dir.isEmpty()) {
            if (!flowAnimator.isRunning()) flowAnimator.start();
            if (!pulseAnimator.isRunning()) pulseAnimator.start();
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0 || dirAlpha < 0.01f) return;

        switch (animationStyle) {
            case STYLE_SEQUENTIAL:    drawSequential(canvas, w, h); break;
            case STYLE_COMET:         drawComet(canvas, w, h); break;
            case STYLE_RIPPLE:        drawRipple(canvas, w, h); break;
            case STYLE_GRADIENT_FILL: drawGradientFill(canvas, w, h); break;
            case STYLE_ARROW_RIPPLE:  drawArrowRipple(canvas, w, h); break;
        }
    }

    // ==================== A: Sequential Segments ====================
    // Clean flat bars that light up one by one in the turn direction.

    private void drawSequential(Canvas canvas, int w, int h) {
        boolean left = "left".equals(direction);
        int segCount = 7;
        float segW = w / (float) segCount;
        float gap = 2 * dp;
        float r = 2.5f * dp;
        float pad = 4 * dp;

        float litCount;
        float fadeMul = 1f;
        if (flowPhase < 0.55f) {
            litCount = (flowPhase / 0.55f) * segCount;
        } else if (flowPhase < 0.65f) {
            litCount = segCount;
        } else {
            litCount = segCount;
            float t = (flowPhase - 0.65f) / 0.35f;
            fadeMul = 1f - t * t;
        }

        for (int i = 0; i < segCount; i++) {
            int idx = left ? (segCount - 1 - i) : i;
            float x1 = idx * segW + gap;
            float x2 = x1 + segW - gap * 2;

            float a;
            if (i < (int) litCount) a = 1f;
            else if (i == (int) litCount) a = litCount - (int) litCount;
            else a = 0f;

            a *= fadeMul * dirAlpha * pulseValue;
            if (a < 0.01f) continue;

            fillPaint.setColor(Color.argb((int) (230 * a), colorR, colorG, colorB));
            rectF.set(x1, pad, x2, h - pad);
            canvas.drawRoundRect(rectF, r, r, fillPaint);
        }
    }

    // ==================== B: Comet Trails ====================
    // Bright streaks with smooth gradient tails sweeping across.

    private void drawComet(Canvas canvas, int w, int h) {
        boolean left = "left".equals(direction);

        for (int i = 0; i < 3; i++) {
            float phase = (flowPhase + i / 3f) % 1f;
            float headX = left ? w * (1f - phase) : w * phase;
            float tailLen = w * 0.4f;
            float tailX = left ? headX + tailLen : headX - tailLen;

            float a = (float) Math.sin(phase * Math.PI);
            a *= a * dirAlpha * pulseValue;
            if (a < 0.01f) continue;

            fillPaint.setShader(new LinearGradient(
                    headX, 0, tailX, 0,
                    Color.argb((int) (220 * a), colorR, colorG, colorB),
                    Color.TRANSPARENT,
                    Shader.TileMode.CLAMP));
            canvas.drawRect(Math.min(headX, tailX), 0, Math.max(headX, tailX), h, fillPaint);
            fillPaint.setShader(null);
        }
    }

    // ==================== C: Ripple Waves ====================
    // Amber light bands radiating outward from the turn side.

    private void drawRipple(Canvas canvas, int w, int h) {
        boolean left = "left".equals(direction);

        for (int i = 0; i < 4; i++) {
            float phase = (flowPhase + i / 4f) % 1f;
            float waveX = left ? w * phase : w * (1f - phase);

            float a = (1f - phase);
            a *= a * dirAlpha * pulseValue;
            if (a < 0.02f) continue;

            float bw = 18 * dp;
            fillPaint.setShader(new LinearGradient(
                    waveX - bw / 2, 0, waveX + bw / 2, 0,
                    new int[]{Color.TRANSPARENT,
                            Color.argb((int) (210 * a), colorR, colorG, colorB),
                            Color.TRANSPARENT},
                    new float[]{0f, 0.5f, 1f},
                    Shader.TileMode.CLAMP));
            canvas.drawRect(waveX - bw / 2, 0, waveX + bw / 2, h, fillPaint);
            fillPaint.setShader(null);
        }
    }

    // ==================== D: Gradient Fill ====================
    // Smooth amber fill that sweeps in then fades out.

    private void drawGradientFill(Canvas canvas, int w, int h) {
        boolean left = "left".equals(direction);

        float progress;
        float fade = 1f;
        if (flowPhase < 0.6f) {
            float t = flowPhase / 0.6f;
            progress = 1f - (1f - t) * (1f - t);
        } else {
            progress = 1f;
            float t = (flowPhase - 0.6f) / 0.4f;
            fade = 1f - t * t;
        }

        float edgeX = left ? w * (1f - progress) : w * progress;
        float startX = left ? w : 0;
        float l = Math.min(startX, edgeX);
        float r = Math.max(startX, edgeX);
        if (r - l < 1) return;

        int alpha = (int) (180 * dirAlpha * pulseValue * fade);
        fillPaint.setShader(new LinearGradient(
                startX, 0, edgeX, 0,
                Color.argb((int) (alpha * 0.3f), colorR, colorG, colorB),
                Color.argb(alpha, colorR, colorG, colorB),
                Shader.TileMode.CLAMP));
        canvas.drawRect(l, 0, r, h, fillPaint);
        fillPaint.setShader(null);
    }

    // ==================== E: Arrow Ripples ====================
    // Clean chevron arrows flying outward from center.

    private void drawArrowRipple(Canvas canvas, int w, int h) {
        boolean left = "left".equals(direction);
        float cy = h / 2f;
        float centerX = w / 2f;
        float travel = w * 0.55f;
        float baseH = 9 * dp;
        float baseW = 6 * dp;

        for (int i = 0; i < 4; i++) {
            float phase = (flowPhase + i / 4f) % 1f;
            float cx = left ? centerX - travel * phase : centerX + travel * phase;
            float scale = 0.7f + 0.4f * phase;

            float a = (float) Math.sin(phase * Math.PI) * dirAlpha * pulseValue;
            if (a < 0.02f) continue;

            float cH = baseH * scale;
            float cW = baseW * scale;

            chevronPaint.setColor(Color.argb((int) (240 * a), colorR, colorG, colorB));
            chevronPaint.setStrokeWidth(2.5f * dp);

            chevronPath.reset();
            if (left) {
                chevronPath.moveTo(cx + cW, cy - cH);
                chevronPath.lineTo(cx - cW, cy);
                chevronPath.lineTo(cx + cW, cy + cH);
            } else {
                chevronPath.moveTo(cx - cW, cy - cH);
                chevronPath.lineTo(cx + cW, cy);
                chevronPath.lineTo(cx - cW, cy + cH);
            }
            canvas.drawPath(chevronPath, chevronPaint);
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (flowAnimator != null) flowAnimator.cancel();
        if (pulseAnimator != null) pulseAnimator.cancel();
        if (fadeAnimator != null) fadeAnimator.cancel();
    }
}
