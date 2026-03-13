package com.kooo.evcam;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * 扁平化 HSV 色盘选色器。上方色相条 + 下方饱和度/明度面板 + 底部预览条。
 */
public class ColorPickerView extends View {

    private float hue = 45f;
    private float sat = 0.75f;
    private float val = 1.0f;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF hueRect = new RectF();
    private final RectF svRect = new RectF();
    private final RectF previewRect = new RectF();

    private float dp;
    private boolean draggingHue = false;
    private boolean draggingSV = false;

    private OnColorChangedListener listener;

    public interface OnColorChangedListener {
        void onColorChanged(int color);
    }

    public ColorPickerView(Context context) {
        super(context);
        init();
    }

    public ColorPickerView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        dp = getResources().getDisplayMetrics().density;
        selectorPaint.setStyle(Paint.Style.STROKE);
        selectorPaint.setStrokeWidth(2.5f * dp);
    }

    public void setOnColorChangedListener(OnColorChangedListener l) {
        this.listener = l;
    }

    public void setColor(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hue = hsv[0];
        sat = hsv[1];
        val = hsv[2];
        invalidate();
    }

    public int getColor() {
        return Color.HSVToColor(new float[]{hue, sat, val});
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (w * 0.85f);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;

        float pad = 12 * dp;
        float hueH = 36 * dp;
        float gap = 10 * dp;
        float previewH = 28 * dp;
        float corner = 6 * dp;

        hueRect.set(pad, pad, w - pad, pad + hueH);
        float svTop = hueRect.bottom + gap;
        float svBot = h - pad - previewH - gap;
        svRect.set(pad, svTop, w - pad, svBot);
        previewRect.set(pad, svBot + gap, w - pad, h - pad);

        drawHueBar(canvas, corner);
        drawSVPanel(canvas, corner);
        drawPreview(canvas, corner);
        drawHueSelector(canvas);
        drawSVSelector(canvas);
    }

    private void drawHueBar(Canvas canvas, float corner) {
        int[] colors = new int[7];
        float[] hsv = {0, 1, 1};
        for (int i = 0; i < 7; i++) {
            hsv[0] = i * 60f;
            colors[i] = Color.HSVToColor(hsv);
        }
        paint.setShader(new LinearGradient(
                hueRect.left, 0, hueRect.right, 0,
                colors, null, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(hueRect, corner, corner, paint);
        paint.setShader(null);
    }

    private void drawSVPanel(Canvas canvas, float corner) {
        int hueColor = Color.HSVToColor(new float[]{hue, 1f, 1f});

        paint.setShader(new LinearGradient(
                svRect.left, 0, svRect.right, 0,
                Color.WHITE, hueColor, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(svRect, corner, corner, paint);
        paint.setShader(null);

        paint.setShader(new LinearGradient(
                0, svRect.top, 0, svRect.bottom,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP));
        canvas.drawRoundRect(svRect, corner, corner, paint);
        paint.setShader(null);
    }

    private void drawPreview(Canvas canvas, float corner) {
        paint.setColor(getColor());
        canvas.drawRoundRect(previewRect, corner, corner, paint);
    }

    private void drawHueSelector(Canvas canvas) {
        float x = hueRect.left + (hue / 360f) * hueRect.width();
        float cy = hueRect.centerY();
        float r = hueRect.height() / 2f + 2 * dp;
        selectorPaint.setColor(Color.WHITE);
        canvas.drawCircle(x, cy, r, selectorPaint);
        selectorPaint.setColor(0x40000000);
        canvas.drawCircle(x, cy, r + 1.5f * dp, selectorPaint);
    }

    private void drawSVSelector(Canvas canvas) {
        float x = svRect.left + sat * svRect.width();
        float y = svRect.top + (1f - val) * svRect.height();
        float r = 8 * dp;
        selectorPaint.setColor(Color.WHITE);
        canvas.drawCircle(x, y, r, selectorPaint);
        selectorPaint.setColor(0x40000000);
        canvas.drawCircle(x, y, r + 1.5f * dp, selectorPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX(), y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (hueRect.contains(x, y) || Math.abs(y - hueRect.centerY()) < 20 * dp) {
                    draggingHue = true;
                    updateHue(x);
                    return true;
                } else if (y >= svRect.top - 10 * dp && y <= svRect.bottom + 10 * dp) {
                    draggingSV = true;
                    updateSV(x, y);
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (draggingHue) { updateHue(x); return true; }
                if (draggingSV) { updateSV(x, y); return true; }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                draggingHue = false;
                draggingSV = false;
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateHue(float x) {
        hue = clamp01((x - hueRect.left) / hueRect.width()) * 360f;
        notifyChanged();
    }

    private void updateSV(float x, float y) {
        sat = clamp01((x - svRect.left) / svRect.width());
        val = 1f - clamp01((y - svRect.top) / svRect.height());
        notifyChanged();
    }

    private void notifyChanged() {
        invalidate();
        if (listener != null) listener.onColorChanged(getColor());
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }
}
