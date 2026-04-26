package com.kooo.evcam.v2.ui

import android.content.Context
import android.graphics.Color
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2BlindSpotSettings

class V2BlindSpotOverlay(
    private val context: Context,
    private val attachPreview: (Int, Surface) -> Unit,
    private val detachPreview: (Int) -> Unit,
) {
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var root: FrameLayout? = null
    private var textureView: TextureView? = null
    private var titleView: TextView? = null
    private var fpsView: TextView? = null
    private var previewSurface: Surface? = null
    private var previewSurfaceTexture: SurfaceTexture? = null
    private var windowParams: WindowManager.LayoutParams? = null
    private var cameraIndex: Int = -1
    private var attachedPreviewIndex: Int = -1
    private var layoutUpdatePending = false
    private var fpsFrames = 0
    private var fpsWindowStartedMs = 0L

    fun show(side: String, index: Int) {
        if (root != null) {
            titleView?.text = titleText(side)
            if (cameraIndex == index && previewSurface?.isValid == true) {
                return
            }
            cameraIndex = index
            refreshCurrentSurface(index)
            V2AppLog.i("V2BlindSpotOverlay", "switch side=$side index=$index")
            return
        }
        hide()
        cameraIndex = index
        val texture = TextureView(context).apply { setOnTouchListener(DragTouchListener()) }
        textureView = texture
        resetFpsCounter()
        val title = TextView(context).apply {
            text = titleText(side)
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(24, 12, 24, 12)
            setOnTouchListener(DragTouchListener())
        }
        titleView = title
        val fps = TextView(context).apply {
            text = "0.0 fps"
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setBackgroundColor(0x66000000)
            setPadding(14, 8, 14, 8)
        }
        fpsView = fps
        root = FrameLayout(context).apply {
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 28f
            }
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            clipToOutline = Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
            addView(texture, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(title, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
            addView(fps, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = 12
                bottomMargin = 12
            })
            addView(TextView(context).apply {
                text = "↘"
                textSize = 24f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setBackgroundColor(0x66000000)
                setOnTouchListener(ResizeTouchListener())
            }, FrameLayout.LayoutParams(72, 72, Gravity.BOTTOM or Gravity.END))
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surfaceTexture: SurfaceTexture, width: Int, height: Int) {
                attachSurface(index, surfaceTexture)
            }
            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) { updateFps() }
            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                detachCurrentSurface()
                return true
            }
        }
        windowParams = layoutParams()
        runCatching { windowManager.addView(root, windowParams) }
            .onFailure {
                V2AppLog.e("V2BlindSpotOverlay", "add overlay failed", it)
                root = null
                textureView = null
                windowParams = null
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
        titleView = null
        fpsView = null
        windowParams = null
        cameraIndex = -1
        resetFpsCounter()
        V2AppLog.i("V2BlindSpotOverlay", "hide")
    }

    private fun attachSurface(index: Int, surfaceTexture: SurfaceTexture) {
        if (attachedPreviewIndex == index && previewSurfaceTexture == surfaceTexture && previewSurface?.isValid == true) return
        detachCurrentSurface()
        val surface = Surface(surfaceTexture)
        previewSurface = surface
        previewSurfaceTexture = surfaceTexture
        attachedPreviewIndex = index
        attachPreview(index, surface)
        V2AppLog.i("V2BlindSpotOverlay", "attach preview index=$index valid=${surface.isValid}")
    }

    private fun refreshCurrentSurface(index: Int) {
        val surfaceTexture = textureView?.surfaceTexture ?: return
        if (textureView?.isAvailable != true) return
        attachSurface(index, surfaceTexture)
    }

    private fun detachCurrentSurface() {
        val index = attachedPreviewIndex
        if (index >= 0) detachPreview(index)
        previewSurface?.release()
        previewSurface = null
        previewSurfaceTexture = null
        attachedPreviewIndex = -1
    }

    private fun titleText(side: String): String = if (side == "left") "左侧补盲" else "右侧补盲"

    private fun resetFpsCounter() {
        fpsFrames = 0
        fpsWindowStartedMs = SystemClock.elapsedRealtime()
        fpsView?.text = "0.0 fps"
    }

    private fun updateFps() {
        fpsFrames += 1
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - fpsWindowStartedMs
        if (elapsed < 900L) return
        val fps = fpsFrames * 1000f / elapsed.coerceAtLeast(1L)
        fpsView?.text = String.format(java.util.Locale.US, "%.1f fps", fps)
        fpsFrames = 0
        fpsWindowStartedMs = now
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val metrics = context.resources.displayMetrics
        val defaultWidth = (metrics.widthPixels * 0.30f).toInt()
        val defaultHeight = (metrics.heightPixels * 0.84f).toInt()
        val width = clampWidth(V2BlindSpotSettings.overlayWidth(context, defaultWidth))
        val height = clampHeight(V2BlindSpotSettings.overlayHeight(context, defaultHeight))
        val defaultX = (metrics.widthPixels * 0.03f).toInt()
        val defaultY = ((metrics.heightPixels - height) / 2).coerceAtLeast(0)
        return WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = clampX(V2BlindSpotSettings.overlayX(context, defaultX), width)
            y = clampY(V2BlindSpotSettings.overlayY(context, defaultY), height)
        }
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startX = 0
        private var startY = 0

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
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
                    params.x = clampX(startX + (event.rawX - startRawX).toInt(), params.width)
                    params.y = clampY(startY + (event.rawY - startRawY).toInt(), params.height)
                    requestWindowLayoutUpdate(view)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    updateWindowLayoutNow(view)
                    V2BlindSpotSettings.setOverlayBounds(context, params.x, params.y, params.width, params.height)
                    return true
                }
            }
            return true
        }
    }

    private inner class ResizeTouchListener : View.OnTouchListener {
        private var startRawX = 0f
        private var startRawY = 0f
        private var startWidth = 0
        private var startHeight = 0

        override fun onTouch(v: View?, event: MotionEvent): Boolean {
            val params = windowParams ?: return false
            val view = root ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = event.rawX
                    startRawY = event.rawY
                    startWidth = params.width
                    startHeight = params.height
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.width = clampWidth(startWidth + (event.rawX - startRawX).toInt())
                    params.height = clampHeight(startHeight + (event.rawY - startRawY).toInt())
                    params.x = clampX(params.x, params.width)
                    params.y = clampY(params.y, params.height)
                    requestWindowLayoutUpdate(view)
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    updateWindowLayoutNow(view)
                    V2BlindSpotSettings.setOverlayBounds(context, params.x, params.y, params.width, params.height)
                    return true
                }
            }
            return true
        }
    }

    private fun clampX(x: Int, windowWidth: Int): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return x.coerceIn(0, (screenWidth - windowWidth).coerceAtLeast(0))
    }

    private fun clampY(y: Int, windowHeight: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return y.coerceIn(0, (screenHeight - windowHeight).coerceAtLeast(0))
    }

    private fun clampWidth(width: Int): Int {
        val screenWidth = context.resources.displayMetrics.widthPixels
        return width.coerceIn((screenWidth * 0.18f).toInt().coerceAtLeast(240), (screenWidth * 0.90f).toInt().coerceAtLeast(240))
    }

    private fun clampHeight(height: Int): Int {
        val screenHeight = context.resources.displayMetrics.heightPixels
        return height.coerceIn((screenHeight * 0.25f).toInt().coerceAtLeast(240), (screenHeight * 0.95f).toInt().coerceAtLeast(240))
    }

    private fun requestWindowLayoutUpdate(view: View) {
        if (layoutUpdatePending) return
        layoutUpdatePending = true
        view.postOnAnimation {
            layoutUpdatePending = false
            updateWindowLayoutNow(view)
        }
    }

    private fun updateWindowLayoutNow(view: View) {
        val params = windowParams ?: return
        runCatching { windowManager.updateViewLayout(view, params) }
            .onFailure { V2AppLog.w("V2BlindSpotOverlay", "update overlay layout failed", it) }
    }
}
