package com.kooo.evcam.v2.ui

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.kooo.evcam.v2.log.V2AppLog

class V2BlindSpotOverlay(
    private val context: Context,
    private val attachPreview: (Int, Surface) -> Unit,
    private val detachPreview: (Int) -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var textureView: TextureView? = null
    private var previewSurface: Surface? = null
    private var cameraIndex: Int = -1

    fun show(side: String, index: Int) {
        if (root != null && cameraIndex == index) return
        hide()
        cameraIndex = index
        val texture = TextureView(context)
        textureView = texture
        root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 28f
            }
            clipToOutline = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
            addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(TextView(context).apply {
                text = if (side == "left") "左侧补盲" else "右侧补盲"
                textSize = 18f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(0x66000000)
                setPadding(24, 12, 24, 12)
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                attachSurface(index, surfaceTexture)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                detachCurrentSurface()
                return true
            }
        }
        runCatching { windowManager.addView(root, layoutParams()) }
            .onFailure {
                V2AppLog.e("V2BlindSpotOverlay", "add overlay failed", it)
                root = null
                textureView = null
                cameraIndex = -1
                return
            }
        if (texture.isAvailable && texture.surfaceTexture != null) attachSurface(index, texture.surfaceTexture!!)
        V2AppLog.i("V2BlindSpotOverlay", "show side=$side index=$index")
    }

    fun hide() {
        val oldRoot = root ?: return
        detachCurrentSurface()
        runCatching { windowManager.removeView(oldRoot) }
            .onFailure { V2AppLog.e("V2BlindSpotOverlay", "remove overlay failed", it) }
        root = null
        textureView = null
        cameraIndex = -1
        V2AppLog.i("V2BlindSpotOverlay", "hide")
    }

    private fun attachSurface(index: Int, surfaceTexture: SurfaceTexture) {
        detachCurrentSurface()
        val surface = Surface(surfaceTexture)
        previewSurface = surface
        attachPreview(index, surface)
        V2AppLog.i("V2BlindSpotOverlay", "attach preview index=$index valid=${surface.isValid}")
    }

    private fun detachCurrentSurface() {
        val index = cameraIndex
        if (index >= 0) detachPreview(index)
        previewSurface?.release()
        previewSurface = null
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        return WindowManager.LayoutParams(
            (metrics.widthPixels * 0.30f).toInt(),
            (metrics.heightPixels * 0.84f).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            x = (metrics.widthPixels * 0.03f).toInt()
            y = 0
        }
    }
}
