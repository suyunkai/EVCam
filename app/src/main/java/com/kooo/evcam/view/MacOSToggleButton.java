package com.kooo.evcam.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * macOS 风格的扁平化开关按钮
 * 简单、干净的设计，支持平滑动画
 */
public class MacOSToggleButton extends View {
    
    private static final int TRACK_WIDTH_DP = 44;
    private static final int TRACK_HEIGHT_DP = 24;
    private static final int THUMB_SIZE_DP = 20;
    private static final int PADDING_DP = 2;
    
    private Paint trackPaint;
    private Paint thumbPaint;
    private RectF trackRect;
    
    private boolean isChecked = true;
    private float thumbPosition = 1.0f; // 0.0 = off, 1.0 = on
    private ValueAnimator animator;
    
    private OnCheckedChangeListener listener;
    
    // macOS 风格颜色
    private static final int COLOR_TRACK_OFF = 0x80FFFFFF; // 50% 白色透明
    private static final int COLOR_TRACK_ON = 0xFF34C759;  // macOS 绿色
    private static final int COLOR_THUMB = Color.WHITE;
    
    public interface OnCheckedChangeListener {
        void onCheckedChanged(MacOSToggleButton button, boolean isChecked);
    }
    
    public MacOSToggleButton(Context context) {
        super(context);
        init();
    }
    
    public MacOSToggleButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    public MacOSToggleButton(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }
    
    private void init() {
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        thumbPaint.setColor(COLOR_THUMB);
        
        trackRect = new RectF();
        
        // 默认开启状态
        thumbPosition = 1.0f;
        isChecked = true;
    }
    
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = (int) (TRACK_WIDTH_DP * getResources().getDisplayMetrics().density);
        int height = (int) (TRACK_HEIGHT_DP * getResources().getDisplayMetrics().density);
        setMeasuredDimension(width, height);
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        
        float density = getResources().getDisplayMetrics().density;
        float padding = PADDING_DP * density;
        float thumbSize = THUMB_SIZE_DP * density;
        
        // 绘制轨道背景
        int trackColor = interpolateColor(COLOR_TRACK_OFF, COLOR_TRACK_ON, thumbPosition);
        trackPaint.setColor(trackColor);
        
        float cornerRadius = getHeight() / 2f;
        trackRect.set(0, 0, getWidth(), getHeight());
        canvas.drawRoundRect(trackRect, cornerRadius, cornerRadius, trackPaint);
        
        // 计算滑块位置
        float thumbOffset = padding + (thumbPosition * (getWidth() - thumbSize - 2 * padding));
        float thumbY = (getHeight() - thumbSize) / 2f;
        
        // 绘制滑块阴影（macOS 风格微妙阴影）
        thumbPaint.setColor(0x20000000);
        canvas.drawCircle(thumbOffset + thumbSize/2 + 1, thumbY + thumbSize/2 + 1, thumbSize/2, thumbPaint);
        
        // 绘制滑块
        thumbPaint.setColor(COLOR_THUMB);
        canvas.drawCircle(thumbOffset + thumbSize/2, thumbY + thumbSize/2, thumbSize/2, thumbPaint);
    }
    
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                // 按下时轻微缩小效果
                animateScale(0.95f);
                return true;
                
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // 恢复大小并切换状态
                animateScale(1.0f);
                toggle();
                return true;
        }
        return super.onTouchEvent(event);
    }
    
    private void animateScale(float scale) {
        animate().scaleX(scale).scaleY(scale).setDuration(100).start();
    }
    
    public void toggle() {
        setChecked(!isChecked);
    }
    
    public void setChecked(boolean checked) {
        if (this.isChecked != checked) {
            this.isChecked = checked;
            animateThumb(checked ? 1.0f : 0.0f);
            
            if (listener != null) {
                listener.onCheckedChanged(this, isChecked);
            }
        }
    }
    
    public boolean isChecked() {
        return isChecked;
    }
    
    private void animateThumb(float targetPosition) {
        if (animator != null && animator.isRunning()) {
            animator.cancel();
        }
        
        animator = ValueAnimator.ofFloat(thumbPosition, targetPosition);
        animator.setDuration(200);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(animation -> {
            thumbPosition = (float) animation.getAnimatedValue();
            invalidate();
        });
        animator.start();
    }
    
    private int interpolateColor(int startColor, int endColor, float fraction) {
        int startA = (startColor >> 24) & 0xFF;
        int startR = (startColor >> 16) & 0xFF;
        int startG = (startColor >> 8) & 0xFF;
        int startB = startColor & 0xFF;
        
        int endA = (endColor >> 24) & 0xFF;
        int endR = (endColor >> 16) & 0xFF;
        int endG = (endColor >> 8) & 0xFF;
        int endB = endColor & 0xFF;
        
        int a = (int) (startA + (endA - startA) * fraction);
        int r = (int) (startR + (endR - startR) * fraction);
        int g = (int) (startG + (endG - startG) * fraction);
        int b = (int) (startB + (endB - startB) * fraction);
        
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
    
    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        this.listener = listener;
    }
}
