package com.kooo.evcam;

import android.content.Context;
import android.util.AttributeSet;
import androidx.appcompat.widget.AppCompatImageView;

/**
 * 自定义ImageView，保持16:10的宽高比
 */
public class AspectRatioImageView extends AppCompatImageView {
    private static final float ASPECT_RATIO = 10.0f / 16.0f; // 高度/宽度 = 10/16

    public AspectRatioImageView(Context context) {
        super(context);
    }

    public AspectRatioImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AspectRatioImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = getMeasuredWidth();
        int height = (int) (width * ASPECT_RATIO);

        setMeasuredDimension(width, height);
    }
}
