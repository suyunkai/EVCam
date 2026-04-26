package com.kooo.evcam.v2.nativebridge

import android.view.Surface
import com.kooo.evcam.v2.log.V2AppLog

object VulkanNative {
    val isLoaded: Boolean
    val loadError: Throwable?

    init {
        var error: Throwable? = null
        val loaded = try {
            System.loadLibrary("evcam_gles_compositor")
            true
        } catch (t: Throwable) {
            error = t
            false
        }
        isLoaded = loaded
        loadError = error
        if (loaded) {
            V2AppLog.i("VulkanNative", "native library loaded")
        } else {
            V2AppLog.e("VulkanNative", "native library load failed", error)
        }
    }

    external fun getNativeVersion(): String
    external fun isVulkanAvailable(): Boolean
    external fun getVulkanSummary(): String
    external fun createCompositor(width: Int, height: Int): Long
    external fun createOesTexture(handle: Long, index: Int): Int
    external fun attachEncoderSurface(handle: Long, surface: Surface): Boolean
    external fun setCompositeConfig(handle: Long, width: Int, height: Int, sideLeftRotation: Int, sideRightRotation: Int, layoutMode: Int): Boolean
    external fun setCompositorRuntimeConfig(
        handle: Long,
        width: Int,
        height: Int,
        previewFps: Int,
        encoderFps: Int,
        sideLeftRotation: Int,
        sideRightRotation: Int,
        layoutMode: Int,
        fisheyeEnabled: BooleanArray,
        k1: FloatArray,
        k2: FloatArray,
        zoom: FloatArray,
        centerX: FloatArray,
        centerY: FloatArray
    ): Boolean
    external fun setPreviewMaxFps(handle: Long, fps: Int): Boolean
    external fun setEncoderFps(handle: Long, fps: Int): Boolean
    external fun startRecordingSession(handle: Long, fps: Int, segmentDurationMs: Long, wallClockMs: Long): Long
    external fun stopRecordingSession(handle: Long): Boolean
    external fun requestRecordingTick(handle: Long, wallClockMs: Long): Long
    external fun getRecordingNextTickDelayMs(handle: Long): Long
    external fun beginNextRecordingSegment(handle: Long): Long
    external fun completeRecordingSegmentSwitch(handle: Long, success: Boolean): Boolean
    external fun markRecordingFrameRendered(handle: Long): Boolean
    external fun createOesInput(handle: Long, index: Int, surfaceTexture: android.graphics.SurfaceTexture): Boolean
    external fun attachPreviewSurface(handle: Long, index: Int, surface: Surface): Boolean
    external fun detachPreviewSurface(handle: Long, index: Int): Boolean
    external fun setFisheyeCorrection(handle: Long, enabled: Boolean, k1: Float, k2: Float, zoom: Float, centerX: Float, centerY: Float): Boolean
    external fun setFisheyeCorrectionForCamera(handle: Long, index: Int, enabled: Boolean, k1: Float, k2: Float, zoom: Float, centerX: Float, centerY: Float): Boolean
    external fun detachEncoderSurface(handle: Long): Boolean
    external fun renderPreview(handle: Long, index: Int): Boolean
    external fun requestPreviewRender(handle: Long, index: Int): Long
    external fun signalPreviewFrame(handle: Long, index: Int): Long
    external fun renderScheduledPreview(handle: Long, index: Int): Boolean
    external fun requestEncoderRender(handle: Long): Boolean
    external fun renderScheduledEncoder(handle: Long): Boolean
    external fun renderScheduledEncoderResult(handle: Long): Int
    external fun renderCompositor(handle: Long): Boolean
    external fun releaseCompositor(handle: Long)
    external fun getMetrics(handle: Long): String
    external fun getMetricsSnapshot(handle: Long): LongArray
    external fun getLastError(): String

    fun summaryOrFallback(): String {
        return if (isLoaded) {
            runCatching { getVulkanSummary() }.getOrElse { "Vulkan native error: ${it.message}" }
        } else {
            "Vulkan native not loaded: ${loadError?.message ?: "unknown"}"
        }
    }
}
