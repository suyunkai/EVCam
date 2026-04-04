package com.kooo.evcam;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * 转向灯箭头视图
 * 在画面上方显示左转或右转箭头，采用标准转向灯样式（绿色箭头）
 * 箭头宽度与悬浮窗宽度比例为 1:12
 */
public class TurnSignalArrowView extends View {

    private static final int ARROW_COLOR = Color.parseColor("#00FF00"); // 标准转向灯绿色
    private static final long BLINK_DURATION = 1000; // 单次闪烁周期 1000ms（亮+灭），1秒闪1次
    private static final int TOP_MARGIN_DP = 20; // 离上边缘的距离
    private static final float ARROW_WIDTH_RATIO = 1f / 12f; // 箭头宽度与视图宽度的比例
    private static final float ARROW_HEIGHT_WIDTH_RATIO = 0.6f; // 箭头高度与宽度的比例

    private Paint arrowPaint;
    private String direction = ""; // "left" 或 "right"
    private boolean isShowing = false;
    private ValueAnimator blinkAnimator;
    private float blinkAlpha = 1f;
    private float dp;

    public TurnSignalArrowView(Context context) {
        super(context);
        init();
    }

    public TurnSignalArrowView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public TurnSignalArrowView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        setBackgroundColor(Color.TRANSPARENT);
        dp = getResources().getDisplayMetrics().density;

        arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        arrowPaint.setStyle(Paint.Style.FILL);
        arrowPaint.setColor(ARROW_COLOR);

        // 初始化闪烁动画 - 全黑到全亮交替，1秒闪2次
        // 500ms一个周期：0-250ms 从黑到亮，250-500ms 从亮到黑
        blinkAnimator = ValueAnimator.ofFloat(0f, 1f);
        blinkAnimator.setDuration(BLINK_DURATION);
        blinkAnimator.setRepeatCount(ValueAnimator.INFINITE);
        blinkAnimator.setRepeatMode(ValueAnimator.RESTART);
        blinkAnimator.setInterpolator(new LinearInterpolator());
        blinkAnimator.addUpdateListener(animation -> {
            float value = (float) animation.getAnimatedValue();
            // 前半周期(0-0.5)从黑到亮，后半周期(0.5-1)从亮到黑
            if (value < 0.5f) {
                blinkAlpha = value * 2f; // 0 -> 1
            } else {
                blinkAlpha = (1f - value) * 2f; // 1 -> 0
            }
            if (isShowing) {
                invalidate();
            }
        });
    }

    /**
     * 显示转向箭头
     * @param dir "left" 表示左转，"right" 表示右转
     */
    public void showArrow(String dir) {
        if (!"left".equals(dir) && !"right".equals(dir)) {
            hideArrow();
            return;
        }

        direction = dir;
        isShowing = true;
        setVisibility(VISIBLE);

        if (!blinkAnimator.isRunning()) {
            blinkAnimator.start();
        }
        invalidate();
    }

    /**
     * 隐藏转向箭头
     */
    public void hideArrow() {
        isShowing = false;
        direction = "";
        blinkAnimator.cancel();
        setVisibility(GONE);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        if (!isShowing || direction.isEmpty()) {
            return;
        }

        int width = getWidth();
        int height = getHeight();

        if (width == 0 || height == 0) {
            return;
        }

        float centerX = width / 2f;
        // 箭头宽度 = 视图宽度 / 12
        float arrowWidth = width * ARROW_WIDTH_RATIO;
        // 箭头高度 = 箭头宽度 * 0.6
        float arrowHeight = arrowWidth * ARROW_HEIGHT_WIDTH_RATIO;
        // 离上边缘30dp的位置（考虑箭头高度的一半，使箭头顶部离上边缘30dp）
        float topMarginPx = TOP_MARGIN_DP * dp;
        float centerY = topMarginPx + arrowHeight / 2f;

        arrowPaint.setAlpha((int) (255 * blinkAlpha));

        if ("left".equals(direction)) {
            drawLeftArrow(canvas, centerX, centerY, arrowWidth, arrowHeight);
        } else if ("right".equals(direction)) {
            drawRightArrow(canvas, centerX, centerY, arrowWidth, arrowHeight);
        }
    }

    private void drawLeftArrow(Canvas canvas, float cx, float cy, float arrowWidth, float arrowHeight) {
        float halfWidth = arrowWidth / 2f;
        float halfHeight = arrowHeight / 2f;
        float headWidth = arrowWidth * 0.4f; // 箭头头部占40%的宽度
        float bodyHeight = arrowHeight * 0.40f; // 箭身高度为箭头高度的40%（增加5%）
        float bodyHalfHeight = bodyHeight / 2f;

        // 计算关键点
        float tipX = cx - halfWidth; // 尖端
        float headRightX = cx - halfWidth + headWidth; // 头部右侧
        float arrowRightX = cx + halfWidth; // 箭头最右侧

        // 使用单个Path绘制完整的箭头形状
        Path arrowPath = new Path();

        // 从尖端开始，顺时针绘制
        arrowPath.moveTo(tipX, cy);
        // 头部上边缘
        arrowPath.lineTo(headRightX, cy - halfHeight);
        // 过渡到箭身（斜边）
        arrowPath.lineTo(headRightX, cy - bodyHalfHeight);
        // 箭身上边缘
        arrowPath.lineTo(arrowRightX, cy - bodyHalfHeight);
        // 箭身右侧
        arrowPath.lineTo(arrowRightX, cy + bodyHalfHeight);
        // 箭身下边缘
        arrowPath.lineTo(headRightX, cy + bodyHalfHeight);
        // 头部下边缘（斜边）
        arrowPath.lineTo(headRightX, cy + halfHeight);
        // 回到尖端
        arrowPath.close();

        canvas.drawPath(arrowPath, arrowPaint);
    }

    private void drawRightArrow(Canvas canvas, float cx, float cy, float arrowWidth, float arrowHeight) {
        float halfWidth = arrowWidth / 2f;
        float halfHeight = arrowHeight / 2f;
        float headWidth = arrowWidth * 0.4f; // 箭头头部占40%的宽度
        float bodyHeight = arrowHeight * 0.40f; // 箭身高度为箭头高度的40%（增加5%）
        float bodyHalfHeight = bodyHeight / 2f;

        // 计算关键点
        float arrowLeftX = cx - halfWidth; // 箭头最左侧
        float headLeftX = cx + halfWidth - headWidth; // 头部左侧
        float tipX = cx + halfWidth; // 尖端

        // 使用单个Path绘制完整的箭头形状
        Path arrowPath = new Path();

        // 从箭身左上角开始，顺时针绘制
        arrowPath.moveTo(arrowLeftX, cy - bodyHalfHeight);
        // 箭身上边缘到头部
        arrowPath.lineTo(headLeftX, cy - bodyHalfHeight);
        // 头部上边缘（斜边）
        arrowPath.lineTo(headLeftX, cy - halfHeight);
        // 到尖端
        arrowPath.lineTo(tipX, cy);
        // 头部下边缘（斜边）
        arrowPath.lineTo(headLeftX, cy + halfHeight);
        // 过渡到箭身
        arrowPath.lineTo(headLeftX, cy + bodyHalfHeight);
        // 箭身下边缘
        arrowPath.lineTo(arrowLeftX, cy + bodyHalfHeight);
        // 回到起点
        arrowPath.close();

        canvas.drawPath(arrowPath, arrowPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (blinkAnimator != null) {
            blinkAnimator.cancel();
        }
    }
}
