package com.kooo.evcam;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

/**
 * 自动适配宽高比的 TextureView
 * 根据设置的宽高比自动调整视图尺寸，避免画面拉伸
 */
public class AutoFitTextureView extends TextureView {

    private int ratioWidth = 0;
    private int ratioHeight = 0;
    private boolean fillContainer = false;  // 是否填满容器（而不是适应容器）

    public AutoFitTextureView(Context context) {
        this(context, null);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * 设置此视图的宽高比
     *
     * @param width  相对宽度
     * @param height 相对高度
     */
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        ratioWidth = width;
        ratioHeight = height;
        requestLayout();
    }

    /**
     * 设置是否填满容器
     *
     * @param fill true=填满容器（可能裁切），false=适应容器（可能有黑边）
     */
    public void setFillContainer(boolean fill) {
        this.fillContainer = fill;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (ratioWidth == 0 || ratioHeight == 0) {
            // 如果没有设置宽高比，使用默认测量
            setMeasuredDimension(width, height);
        } else {
            // 根据宽高比调整尺寸
            int newWidth, newHeight;

            // 方案1：基于容器宽度计算高度
            newWidth = width;
            newHeight = width * ratioHeight / ratioWidth;

            if (fillContainer) {
                // 填满模式：如果高度不足，放大以填满容器
                if (newHeight < height) {
                    newHeight = height;
                    newWidth = height * ratioWidth / ratioHeight;
                }
            } else {
                // 适应模式：如果高度超出，缩小以适应容器
                if (newHeight > height) {
                    newHeight = height;
                    newWidth = height * ratioWidth / ratioHeight;
                }
            }

            setMeasuredDimension(newWidth, newHeight);
        }
    }
}
