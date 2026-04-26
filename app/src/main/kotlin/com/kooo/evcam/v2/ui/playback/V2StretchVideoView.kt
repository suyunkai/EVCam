package com.kooo.evcam.v2.ui.playback

import android.content.Context
import android.util.AttributeSet
import android.widget.VideoView

/** VideoView 默认会按视频比例测量，v2 回放需要强制拉伸铺满播放区。 */
class V2StretchVideoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : VideoView(context, attrs, defStyleAttr) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            MeasureSpec.getSize(heightMeasureSpec)
        )
    }
}
