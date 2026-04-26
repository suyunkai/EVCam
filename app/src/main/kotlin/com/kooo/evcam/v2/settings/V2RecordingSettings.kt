package com.kooo.evcam.v2.settings

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Size
import com.kooo.evcam.v2.log.V2AppLog

object V2RecordingSettings {
    private const val PREFS = "evcam_v2_recording_settings"
    private const val KEY_RESOLUTION = "resolution"
    private const val KEY_BITRATE_LEVEL = "bitrate_level"
    private const val KEY_FPS = "fps"
    private const val KEY_SEGMENT_MINUTES = "segment_minutes"

    private const val DEFAULT_RESOLUTION = "1280x720"
    const val BITRATE_LOW = "low"
    const val BITRATE_MEDIUM = "medium"
    const val BITRATE_HIGH = "high"

    val bitrateOptions = listOf(
        Option(BITRATE_LOW, "低"),
        Option(BITRATE_MEDIUM, "标准"),
        Option(BITRATE_HIGH, "高")
    )
    val fpsOptions = listOf(10, 15, 20, 25)
    val segmentMinuteOptions = listOf(1, 3, 5, 10)

    fun resolution(context: Context): String = prefs(context).getString(KEY_RESOLUTION, DEFAULT_RESOLUTION) ?: DEFAULT_RESOLUTION
    fun bitrateLevel(context: Context): String = prefs(context).getString(KEY_BITRATE_LEVEL, BITRATE_MEDIUM) ?: BITRATE_MEDIUM
    fun fps(context: Context): Int = prefs(context).getInt(KEY_FPS, 15).coerceIn(1, 60)
    fun segmentMinutes(context: Context): Int = prefs(context).getInt(KEY_SEGMENT_MINUTES, 1).coerceAtLeast(1)
    fun segmentDurationMs(context: Context): Long = segmentMinutes(context) * 60_000L

    fun setResolution(context: Context, value: String) {
        val next = supportedResolutionOptions(context).firstOrNull { it.value == value }?.value
            ?: supportedResolutionOptions(context).firstOrNull()?.value
            ?: DEFAULT_RESOLUTION
        prefs(context).edit().putString(KEY_RESOLUTION, next).apply()
        V2AppLog.i("V2RecordingSettings", "resolution=$next")
    }

    fun setBitrateLevel(context: Context, value: String) {
        val next = bitrateOptions.firstOrNull { it.value == value }?.value ?: BITRATE_MEDIUM
        prefs(context).edit().putString(KEY_BITRATE_LEVEL, next).apply()
        V2AppLog.i("V2RecordingSettings", "bitrateLevel=$next")
    }

    fun setFps(context: Context, value: Int) {
        val next = fpsOptions.minByOrNull { kotlin.math.abs(it - value) } ?: 15
        prefs(context).edit().putInt(KEY_FPS, next).apply()
        V2AppLog.i("V2RecordingSettings", "fps=$next")
    }

    fun setSegmentMinutes(context: Context, value: Int) {
        val next = segmentMinuteOptions.minByOrNull { kotlin.math.abs(it - value) } ?: 1
        prefs(context).edit().putInt(KEY_SEGMENT_MINUTES, next).apply()
        V2AppLog.i("V2RecordingSettings", "segmentMinutes=$next")
    }

    fun supportedResolutionOptions(context: Context): List<Option> {
        val supported = commonSupportedSurfaceTextureSizes(context)
        val options = supported.map { Option(valueForSize(it), "${it.width}×${it.height}") }
        if (options.isNotEmpty()) return options
        val fallback = listOf(Size(1280, 720), Size(1920, 1080))
        return fallback.map { Option(valueForSize(it), "${it.width}×${it.height}") }
    }

    fun recordingSize(context: Context, screenSize: Size): Size {
        val selected = parseSize(resolution(context))
        val supported = supportedResolutionOptions(context).mapNotNull { parseSize(it.value) }
        return when {
            selected != null && supported.any { it.width == selected.width && it.height == selected.height } -> selected
            supported.isNotEmpty() -> supported.first()
            selected != null -> selected
            else -> evenSize(screenSize)
        }
    }

    fun bitrate(context: Context, size: Size): Int = bitrateForLevel(size, bitrateLevel(context))

    fun bitrateOptionsWithMbps(context: Context): List<Option> {
        val size = recordingSize(context, Size(1280, 720))
        return bitrateOptions.map { option ->
            Option(option.value, "${option.label}（${formatMbps(bitrateForLevel(size, option.value))}Mbps）")
        }
    }

    fun summary(context: Context): String {
        val res = labelFor(supportedResolutionOptions(context), resolution(context))
        val br = labelFor(bitrateOptionsWithMbps(context), bitrateLevel(context))
        return "分辨率：$res；码率：$br；帧率：${fps(context)}fps；分段：${segmentMinutes(context)}分钟\n更改后重启应用/服务生效"
    }

    private fun bitrateForLevel(size: Size, level: String): Int {
        val basePixels = 1280L * 720L
        val pixels = size.width.toLong() * size.height.toLong()
        val auto = ((2_500_000L * pixels) / basePixels).coerceAtLeast(2_500_000L).coerceAtMost(30_000_000L)
        val scaled = when (level) {
            BITRATE_LOW -> (auto * 0.7).toLong()
            BITRATE_HIGH -> (auto * 1.5).toLong()
            else -> auto
        }
        return scaled.coerceAtLeast(1_500_000L).coerceAtMost(45_000_000L).toInt()
    }

    private fun formatMbps(bitsPerSecond: Int): String {
        val mbps = bitsPerSecond / 1_000_000.0
        return if (mbps >= 10 || mbps % 1.0 == 0.0) String.format(java.util.Locale.US, "%.0f", mbps) else String.format(java.util.Locale.US, "%.1f", mbps)
    }

    private fun commonSupportedSurfaceTextureSizes(context: Context): List<Size> {
        val app = context.applicationContext
        val manager = app.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val ids = V2VehicleModelSettings.getModel(app).mapping.run { listOf(front, back, left, right) }.distinct()
        val availableIds = runCatching { manager.cameraIdList.toSet() }.getOrElse {
            V2AppLog.e("V2RecordingSettings", "read cameraIdList failed", it)
            emptySet()
        }
        val perCamera = ids.filter { it in availableIds }.mapNotNull { id -> supportedSizesForCamera(manager, id) }
        val common = perCamera.reduceOrNull { acc, sizes -> acc.intersect(sizes).toSet() }.orEmpty()
        val source = if (common.isNotEmpty()) common else perCamera.flatten().toSet()
        return source
            .filter { it.width > 0 && it.height > 0 }
            .map { normalizeLandscape(it) }
            .distinctBy { valueForSize(it) }
            .sortedWith(compareByDescending<Size> { it.width.toLong() * it.height }.thenByDescending { it.width })
            .take(12)
    }

    private fun supportedSizesForCamera(manager: CameraManager, cameraId: String): Set<Size>? = try {
        val map = manager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return emptySet()
        map.getOutputSizes(SurfaceTexture::class.java)
            ?.map { normalizeLandscape(it) }
            ?.toSet()
            .orEmpty()
    } catch (error: CameraAccessException) {
        V2AppLog.e("V2RecordingSettings", "read supported sizes failed camera=$cameraId", error)
        null
    } catch (error: RuntimeException) {
        V2AppLog.e("V2RecordingSettings", "read supported sizes failed camera=$cameraId", error)
        null
    }

    private fun labelFor(options: List<Option>, value: String): String = options.firstOrNull { it.value == value }?.label ?: value
    private fun parseSize(value: String): Size? {
        val parts = value.lowercase().split('x')
        if (parts.size != 2) return null
        val width = parts[0].toIntOrNull() ?: return null
        val height = parts[1].toIntOrNull() ?: return null
        return if (width > 0 && height > 0) Size(width, height) else null
    }
    private fun normalizeLandscape(size: Size): Size = if (size.width >= size.height) size else Size(size.height, size.width)
    private fun valueForSize(size: Size) = "${size.width}x${size.height}"
    private fun evenSize(size: Size) = Size(size.width - size.width % 2, size.height - size.height % 2)
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class Option(val value: String, val label: String)
}
