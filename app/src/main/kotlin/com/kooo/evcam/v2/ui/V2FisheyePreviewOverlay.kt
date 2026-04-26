package com.kooo.evcam.v2.ui

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.kooo.evcam.v2.log.V2AppLog

class V2FisheyePreviewOverlay(
    private val context: Context,
    private val attachPreview: (Int, Surface) -> Unit,
    private val detachPreview: (Int) -> Unit,
    private val onClose: () -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var previewSurface: Surface? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var cameraIndex: Int = -1

    fun show(label: String, index: Int) {
        if (root != null && cameraIndex == index) return
        hide()
        cameraIndex = index

        val texture = TextureView(context)
        val previewAspect = previewAspectFor(index)
        root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 24f
            }
            addView(texture, textureLayoutParams(previewAspect))
            addView(FrameLayout(context).apply {
                setBackgroundColor(0x77000000)
                setOnTouchListener(DragTouchListener())
                addView(TextView(context).apply {
                    text = "$label 鱼眼调参预览"
                    textSize = 16f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setPadding(20, 10, 20, 10)
                }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                addView(TextView(context).apply {
                    text = "×"
                    textSize = 26f
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    setOnClickListener { onClose() }
                }, FrameLayout.LayoutParams(56, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 56, Gravity.TOP))
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) = attachSurface(index, surfaceTexture)
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                detachCurrentSurface()
                return true
            }
        }
        windowParams = layoutParams()
        runCatching { windowManager.addView(root, windowParams) }
            .onFailure {
                V2AppLog.e("V2FisheyePreviewOverlay", "add overlay failed", it)
                root = null
                cameraIndex = -1
                return
            }
        if (texture.isAvailable && texture.surfaceTexture != null) attachSurface(index, texture.surfaceTexture!!)
        V2AppLog.i("V2FisheyePreviewOverlay", "show label=$label index=$index")
    }

    fun hide() {
        val oldRoot = root ?: return
        detachCurrentSurface()
        runCatching { windowManager.removeView(oldRoot) }
            .onFailure { V2AppLog.e("V2FisheyePreviewOverlay", "remove overlay failed", it) }
        root = null
        windowParams = null
        cameraIndex = -1
        V2AppLog.i("V2FisheyePreviewOverlay", "hide")
    }

    private fun attachSurface(index: Int, surfaceTexture: SurfaceTexture) {
        detachCurrentSurface()
        val surface = Surface(surfaceTexture)
        previewSurface = surface
        attachPreview(index, surface)
        V2AppLog.i("V2FisheyePreviewOverlay", "attach preview index=$index valid=${surface.isValid}")
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
            (metrics.heightPixels * 0.42f).toInt(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            x = rightSafeMargin(metrics.widthPixels)
            y = 0
        }
    }

    private fun textureLayoutParams(aspect: Float): FrameLayout.LayoutParams {
        val metrics = context.resources.displayMetrics
        val windowWidth = (metrics.widthPixels * 0.30f).toInt()
        val windowHeight = (metrics.heightPixels * 0.42f).toInt()
        val headerHeight = 56
        val availableHeight = (windowHeight - headerHeight).coerceAtLeast(1)
        val fitByWidthHeight = (windowWidth / aspect).toInt()
        val width: Int
        val height: Int
        if (fitByWidthHeight <= availableHeight) {
            width = windowWidth
            height = fitByWidthHeight
        } else {
            height = availableHeight
            width = (height * aspect).toInt()
        }
        return FrameLayout.LayoutParams(width, height, Gravity.CENTER).apply { topMargin = headerHeight / 2 }
    }

    private fun previewAspectFor(index: Int): Float = if (index == 2 || index == 3) 9f / 16f else 16f / 9f

    private inner class DragTouchListener : android.view.View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: android.view.View?, event: MotionEvent): Boolean {
            val params = windowParams ?: return false
            val view = root ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startX = params.x
                    startY = params.y
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = (startX - (event.rawX - startRawX).toInt()).coerceAtLeast(rightSafeMargin(context.resources.displayMetrics.widthPixels))
                    params.y = startY + (event.rawY - startRawY).toInt()
                    runCatching { windowManager.updateViewLayout(view, params) }
                    return true
                }
            }
            return true
        }
    }

    private fun rightSafeMargin(screenWidth: Int): Int = (screenWidth * 0.08f).toInt()
}
