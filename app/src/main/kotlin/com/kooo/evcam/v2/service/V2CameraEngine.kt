package com.kooo.evcam.v2.service

import android.content.Context
import android.graphics.Point
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.WindowManager
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.VulkanNative
import com.kooo.evcam.v2.settings.V2FisheyeSettings
import com.kooo.evcam.v2.settings.V2VehicleModelSettings
import com.kooo.evcam.v2.ui.V2CompositeRecorder
import java.io.File
import java.util.concurrent.Executor

class V2CameraEngine(private val context: Context, private val listener: Listener? = null) : V2CompositeRecorder.DebugListener {
    interface Listener { fun onStatusChanged(status: String) }

    companion object {
        private const val PREVIEW_MAX_FPS = 25
        private const val RECORDING_FPS = 15
    }

    private val specs = cameraSpecsForCurrentModel()
    private val slots = specs.mapIndexed { index, spec -> Slot(index, spec) }
    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val renderThread = HandlerThread("V2GlesComposite").also { it.start() }
    private val renderHandler = Handler(renderThread.looper)
    private val recordingSize = detectScreenSize()
    private val recordingBitrate = bitrateFor(recordingSize)
    private val pipelineHandle = createCompositorSafely(recordingSize)
    private var recording = false
    private var compositor: V2CompositeRecorder? = null
    private var recorderDebug = "draw=0 enc=0 0s | 无 0KB | err=无"
    private var lastPreviewDebugUpdateMs = 0L
    @Volatile private var cameraAccessAllowed = true
    @Volatile private var released = false

    init {
        V2AppLog.i("V2CameraEngine", "init model=${V2VehicleModelSettings.getModel(context).label} specs=${specs.joinToString { "${it.label}:${it.cameraId}/rot${it.rotation}" }} recordingSize=${recordingSize.width}x${recordingSize.height} bitrate=$recordingBitrate pipelineHandle=$pipelineHandle nativeLoaded=${VulkanNative.isLoaded}")
        if (pipelineHandle == 0L) V2AppLog.e("V2CameraEngine", "create compositor failed: ${VulkanNative.summaryOrFallback()} lastError=${VulkanNative.getLastError()}")
        configureNativeRuntime(logPrefix = "init")
    }

    fun applyFisheyeSettings() {
        if (pipelineHandle == 0L) return
        configureNativeRuntime(logPrefix = "fisheye")
        publishStatus()
    }

    private fun configureNativeRuntime(logPrefix: String) {
        if (pipelineHandle == 0L) return
        val enabled = V2FisheyeSettings.isEnabled(context)
        val params = slots.map { V2FisheyeSettings.paramsForIndex(context, it.index) }
        val ok = VulkanNative.setCompositorRuntimeConfig(
            pipelineHandle,
            recordingSize.width,
            recordingSize.height,
            PREVIEW_MAX_FPS,
            RECORDING_FPS,
            270,
            90,
            0,
            BooleanArray(4) { enabled },
            FloatArray(4) { params.getOrNull(it)?.k1 ?: V2FisheyeSettings.DEFAULT_K1 },
            FloatArray(4) { params.getOrNull(it)?.k2 ?: V2FisheyeSettings.DEFAULT_K2 },
            FloatArray(4) { params.getOrNull(it)?.zoom ?: V2FisheyeSettings.DEFAULT_ZOOM },
            FloatArray(4) { params.getOrNull(it)?.centerX ?: V2FisheyeSettings.DEFAULT_CENTER_X },
            FloatArray(4) { params.getOrNull(it)?.centerY ?: V2FisheyeSettings.DEFAULT_CENTER_Y }
        )
        val results = slots.map { slot ->
            val p = params.getOrNull(slot.index) ?: V2FisheyeSettings.defaultParamsForIndex(slot.index)
            "${slot.spec.label}:${p.k1}/${p.k2}/${p.zoom}"
        }
        V2AppLog.i("V2CameraEngine", "$logPrefix runtimeConfig ok=$ok previewFps=$PREVIEW_MAX_FPS encoderFps=$RECORDING_FPS fisheye=$enabled perCamera=${results.joinToString()}")
    }

    fun setCameraAccessAllowed(allowed: Boolean) {
        if (cameraAccessAllowed == allowed) return
        cameraAccessAllowed = allowed
        V2AppLog.i("V2CameraEngine", "cameraAccessAllowed=$allowed")
        if (!allowed) {
            stopRecording()
            stopCameras()
        } else {
            startCameras()
        }
        publishStatus()
    }

    fun startCameras() {
        if (released) {
            V2AppLog.w("V2CameraEngine", "startCameras skipped: engine released")
            return
        }
        if (pipelineHandle == 0L) {
            V2AppLog.e("V2CameraEngine", "startCameras skipped: native compositor unavailable")
            return
        }
        if (!cameraAccessAllowed) {
            V2AppLog.w("V2CameraEngine", "startCameras skipped: screen is off")
            return
        }
        V2AppLog.i("V2CameraEngine", "startCameras specs=${specs.joinToString { "${it.label}:${it.cameraId}" }}")
        slots.forEach { slot -> slot.ensureInputSurface(); openCamera(slot) }
    }

    fun stopCameras() {
        V2AppLog.i("V2CameraEngine", "stopCameras release all camera devices and previews")
        mainHandler.removeCallbacksAndMessages(null)
        slots.forEach { slot ->
            if (slot.previewAttached) {
                runCatching { VulkanNative.detachPreviewSurface(pipelineHandle, slot.index) }
                slot.previewAttached = false
            }
            slot.close()
        }
        publishStatus()
    }

    fun attachPreviewSurface(index: Int, surface: Surface) {
        val slot = slot(index) ?: return
        if (released || pipelineHandle == 0L) return
        if (!cameraAccessAllowed) {
            V2AppLog.w("V2CameraEngine", "attach preview skipped: screen is off ${slot.spec.name}/${slot.spec.cameraId}")
            return
        }

        V2AppLog.i("V2CameraEngine", "attach preview ${slot.spec.name}/${slot.spec.cameraId}")
        if (!VulkanNative.attachPreviewSurface(pipelineHandle, index, surface)) {
            slot.previewAttached = false
            V2AppLog.e("V2CameraEngine", "attach preview failed ${slot.spec.name}/${slot.spec.cameraId}: ${VulkanNative.getLastError()}")
            publishStatus()
            return
        }
        slot.previewAttached = true
        schedulePreviewWarmup(slot)
        publishStatus()
    }

    fun detachPreviewSurface(index: Int) {
        val slot = slot(index) ?: return
        if (pipelineHandle == 0L) return
        slot.previewAttached = false
        V2AppLog.i("V2CameraEngine", "detach preview ${slot.spec.name}/${slot.spec.cameraId}")
        VulkanNative.detachPreviewSurface(pipelineHandle, index)
        slot.resetFps()
        publishStatus()
    }

    fun previewIndexForPosition(position: String): Int? {
        return slots.firstOrNull { it.spec.name == position }?.index
    }

    fun previewIndexForCameraId(cameraId: String): Int? {
        return slots.firstOrNull { it.spec.cameraId == cameraId }?.index
    }

    fun previewDescription(index: Int): String {
        val slot = slot(index) ?: return "unknown"
        return "${slot.spec.name}/${slot.spec.label}/cameraId=${slot.spec.cameraId}/slot=$index"
    }

    fun startRecording() {
        if (!cameraAccessAllowed) {
            V2AppLog.w("V2CameraEngine", "startRecording skipped: screen is off")
            return
        }
        if (released || pipelineHandle == 0L) {
            V2AppLog.e("V2CameraEngine", "startRecording skipped: native compositor unavailable")
            return
        }
        if (recording) {
            V2AppLog.i("V2CameraEngine", "startRecording ignored: already recording")
            return
        }

        V2AppLog.i("V2CameraEngine", "startRecording size=${recordingSize.width}x${recordingSize.height} bitrate=$recordingBitrate")
        val next = V2CompositeRecorder(
            context = context,
            outputDir = outputDir(),
            debugListener = this,
            nativeHandle = pipelineHandle,
            renderHandler = renderHandler,
            outputWidth = recordingSize.width,
            outputHeight = recordingSize.height,
            videoBitrate = recordingBitrate
        )
        if (!next.start()) {
            V2AppLog.e("V2CameraEngine", "startRecording failed: compositor start returned false")
            next.stop()
            publishStatus()
            return
        }

        compositor = next
        recording = true
        publishStatus()
    }
    fun stopRecording() {
        if (!recording && compositor == null) return
        V2AppLog.i("V2CameraEngine", "stopRecording")
        recording = false
        compositor?.stop()
        compositor = null
        recorderDebug = "draw=0 enc=0 0s | 无 0KB | err=已停止"
        slots.forEach { if (it.previewAttached) restartPreviewAfterRecordingStop(it) }
        publishStatus()
    }
    fun toggleRecording(): Boolean {
        if (recording) stopRecording() else startRecording()
        return recording
    }
    fun isRecording() = recording
    fun statusText() = status()
    fun healthSnapshot(): HealthSnapshot = HealthSnapshot(
        cameraAccessAllowed = cameraAccessAllowed,
        released = released,
        recording = recording,
        slots = slots.map { slot ->
            SlotHealth(
                index = slot.index,
                label = slot.spec.label,
                cameraId = slot.spec.cameraId,
                deviceOpen = slot.device != null,
                sessionOpen = slot.session != null,
                inputReady = slot.inputSurface != null,
                previewAttached = slot.previewAttached,
                frameSignals = slot.frameSignals,
                renderedFrames = slot.renderedFrames,
                renderFailures = slot.renderFailures,
                lastRenderMs = slot.lastRenderMs,
                lastError = slot.lastPreviewError
            )
        },
        recordingMetrics = compositor?.metricsSnapshot()
    )
    fun release() {
        V2AppLog.i("V2CameraEngine", "release")
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        stopRecording()
        renderHandler.removeCallbacksAndMessages(null)
        slots.forEach { it.close() }
        runCatching { VulkanNative.releaseCompositor(pipelineHandle) }
        runCatching { renderThread.quitSafely() }
    }

    override fun onCompositeDebug(message: String) { recorderDebug = message; publishStatus() }

    private fun slot(index: Int) = slots.getOrNull(index)
    private fun outputDir() = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "EVCamV2").apply { mkdirs() }

    private fun detectScreenSize(): Size {
        val fallback = Size(2560, 1600)
        return runCatching {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val rawSize = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                Size(bounds.width(), bounds.height())
            } else {
                @Suppress("DEPRECATION")
                val display = windowManager.defaultDisplay
                val point = Point()
                @Suppress("DEPRECATION")
                display.getRealSize(point)
                Size(point.x, point.y)
            }
            val width = rawSize.width.coerceAtLeast(2).let { it - (it % 2) }
            val height = rawSize.height.coerceAtLeast(2).let { it - (it % 2) }
            Size(width, height)
        }.getOrDefault(fallback)
    }

    private fun createCompositorSafely(size: Size): Long {
        if (!VulkanNative.isLoaded) {
            V2AppLog.e("V2CameraEngine", "native library unavailable: ${VulkanNative.summaryOrFallback()}")
            return 0L
        }
        return runCatching { VulkanNative.createCompositor(size.width, size.height) }
            .onFailure { V2AppLog.e("V2CameraEngine", "create compositor crashed", it) }
            .getOrDefault(0L)
    }

    private fun schedulePreviewWarmup(slot: Slot) { repeat(20) { attempt -> mainHandler.postDelayed({ requestPreviewRender(slot) }, attempt * 100L) } }
    private fun requestPreviewRender(slot: Slot) {
        if (released || pipelineHandle == 0L) return
        if (!cameraAccessAllowed) return
        slot.frameSignals += 1
        val delayMs = VulkanNative.signalPreviewFrame(pipelineHandle, slot.index)
        if (!slot.previewAttached) {
            publishStatusIfNeeded()
            return
        }
        if (delayMs < 0L) {
            publishStatusIfNeeded()
            return
        }
        postPreviewRender(slot, delayMs)
    }

    private fun postPreviewRender(slot: Slot, delayMs: Long = 0L) {
        renderHandler.postDelayed({
            try {
                if (slot.previewAttached) {
                    slot.lastPreviewPostMs = SystemClock.elapsedRealtime()
                    val started = SystemClock.elapsedRealtime()
                    val ok = VulkanNative.renderScheduledPreview(pipelineHandle, slot.index)
                    slot.lastRenderMs = SystemClock.elapsedRealtime() - started
                    if (ok) {
                        slot.renderedFrames += 1
                        slot.lastPreviewError = "无"
                    } else {
                        slot.renderFailures += 1
                        slot.lastPreviewError = VulkanNative.getLastError()
                        V2AppLog.e("V2CameraEngine", "preview render failed ${slot.spec.name}: ${slot.lastPreviewError}")
                    }
                }
            } finally {
                publishStatusIfNeeded()
            }
        }, delayMs.coerceAtLeast(0L))
    }

    private fun restartPreviewAfterRecordingStop(slot: Slot) {
        if (!cameraAccessAllowed) return
        slot.ensureInputSurface()
        val device = slot.device
        if (device == null) {
            V2AppLog.w("V2CameraEngine", "preview recovery opening camera ${slot.spec.name}/${slot.spec.cameraId}")
            openCamera(slot)
            schedulePreviewWarmup(slot)
            return
        }

        val slotHandler = slot.ensureThread()
        slotHandler.post {
            V2AppLog.d("V2CameraEngine", "preview recovery restarting session ${slot.spec.name}/${slot.spec.cameraId}")
            runCatching { slot.session?.stopRepeating() }
            runCatching { slot.session?.close() }
            slot.session = null
            slotHandler.postDelayed({
                startPreview(slot)
                schedulePreviewWarmup(slot)
            }, 120L)
        }
    }

    private fun openCamera(slot: Slot) {
        if (released) return
        if (!cameraAccessAllowed) return
        val cameraId = slot.spec.cameraId
        val availableIds = cameraIds()
        if (!availableIds.contains(cameraId)) {
            V2AppLog.e("V2CameraEngine", "openCamera skipped: cameraId=$cameraId unavailable available=$availableIds spec=${slot.spec.name}")
            return
        }
        if (slot.inputSurface == null) {
            V2AppLog.e("V2CameraEngine", "openCamera skipped: input surface missing ${slot.spec.name}/$cameraId")
            return
        }
        if (slot.device != null) return
        val slotHandler = slot.ensureThread()
        try { V2AppLog.i("V2CameraEngine", "openCamera ${slot.spec.name}/$cameraId"); cameraManager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                if (released || !cameraAccessAllowed || slot.inputSurface == null) {
                    V2AppLog.w("V2CameraEngine", "camera opened after release/disable, closing ${slot.spec.name}/${slot.spec.cameraId}")
                    camera.close()
                    return
                }
                V2AppLog.i("V2CameraEngine", "camera opened ${slot.spec.name}/${slot.spec.cameraId}")
                slot.device = camera
                startPreview(slot)
            }
            override fun onDisconnected(camera: CameraDevice) { V2AppLog.w("V2CameraEngine", "camera disconnected ${slot.spec.name}/${slot.spec.cameraId}"); camera.close(); slot.device = null }
            override fun onError(camera: CameraDevice, error: Int) { V2AppLog.e("V2CameraEngine", "camera error ${slot.spec.name}/${slot.spec.cameraId}: $error"); camera.close(); slot.device = null }
        }, slotHandler) } catch (error: Exception) { V2AppLog.e("V2CameraEngine", "openCamera failed ${slot.spec.name}/$cameraId", error) }
    }

    private fun startPreview(slot: Slot) {
        if (released) return
        if (!cameraAccessAllowed) return
        try {
            val builder = slot.device?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW) ?: return
            val inputSurface = slot.inputSurface ?: return
            builder.addTarget(inputSurface)
            chooseFpsRange(slot.spec.cameraId)?.let { builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            createSession(slot.device ?: return, listOf(inputSurface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (released || !cameraAccessAllowed || slot.device == null || slot.inputSurface == null) {
                        V2AppLog.w("V2CameraEngine", "preview session configured after release/disable, closing ${slot.spec.name}/${slot.spec.cameraId}")
                        runCatching { session.close() }
                        return
                    }
                    slot.session?.close()
                    slot.session = session
                    session.setRepeatingRequest(builder.build(), null, slot.handler)
                    V2AppLog.d("V2CameraEngine", "preview session configured ${slot.spec.name}/${slot.spec.cameraId}")
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    V2AppLog.e("V2CameraEngine", "preview session configure failed ${slot.spec.name}/${slot.spec.cameraId}")
                    runCatching { session.close() }
                    slot.handler?.postDelayed({ if (!released && cameraAccessAllowed && slot.device != null && slot.inputSurface != null) startPreview(slot) }, 300L)
                }
            }, slot)
        } catch (t: Exception) {
            V2AppLog.e("V2CameraEngine", "startPreview failed ${slot.spec.name}/${slot.spec.cameraId}", t)
        }
    }

    private fun createSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        callback: CameraCaptureSession.StateCallback,
        slot: Slot
    ) {
        val slotHandler = slot.ensureThread()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputConfigs = surfaces.map { OutputConfiguration(it) }
            val executor = Executor { command -> slotHandler.post(command) }
            device.createCaptureSession(
                SessionConfiguration(SessionConfiguration.SESSION_REGULAR, outputConfigs, executor, callback)
            )
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, callback, slotHandler)
        }
    }
    private fun choosePreviewSize(cameraId: String, targetWidth: Int, targetHeight: Int): Size? = runCatching {
        val map = cameraManager.getCameraCharacteristics(cameraId)
            .get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return@runCatching null
        val sizes = map.getOutputSizes(SurfaceTexture::class.java)?.toList().orEmpty()
        if (sizes.isEmpty()) return@runCatching null

        sizes.firstOrNull { it.width == targetWidth && it.height == targetHeight }
            ?: sizes.filter { it.width <= targetWidth && it.height <= targetHeight }
                .maxWithOrNull(compareBy<Size> { it.width * it.height }.thenBy { it.width })
            ?: sizes.minByOrNull { kotlin.math.abs(it.width - targetWidth) + kotlin.math.abs(it.height - targetHeight) }
    }.onFailure { V2AppLog.e("V2CameraEngine", "choosePreviewSize failed camera=$cameraId", it) }.getOrNull()
    private fun bitrateFor(size: Size): Int {
        val basePixels = 1280L * 720L
        val pixels = size.width.toLong() * size.height.toLong()
        return ((2_500_000L * pixels) / basePixels)
            .coerceAtLeast(2_500_000L)
            .coerceAtMost(30_000_000L)
            .toInt()
    }
    private fun chooseFpsRange(cameraId: String): Range<Int>? = runCatching {
        val ranges = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.toList()
            .orEmpty()
        ranges.firstOrNull { it.lower == 30 && it.upper == 30 }
            ?: ranges.firstOrNull { it.lower == it.upper }
            ?: ranges.maxByOrNull { it.upper }
    }.getOrNull()
    private fun cameraIds(): List<String> = try { cameraManager.cameraIdList.toList() } catch (error: CameraAccessException) { V2AppLog.e("V2CameraEngine", "read cameraIdList failed", error); emptyList() }
    private fun status(): String {
        val line1 = "屏幕:${if (cameraAccessAllowed) "亮" else "灭"} 摄像头:${cameraIds().size} 录制:${if (recording) "ON" else "OFF"} ${recordingSize.width}x${recordingSize.height} | $recorderDebug"
        val metrics = if (pipelineHandle != 0L) {
            runCatching { nativeMetricsDebug(VulkanNative.getMetricsSnapshot(pipelineHandle)) }
                .getOrElse { runCatching { VulkanNative.getMetrics(pipelineHandle) }.getOrDefault("metrics=unavailable") }
        } else "metrics=unavailable"
        return "$line1\n${previewPairDebug(0, 1)}\n${previewPairDebug(2, 3)}\n$metrics"
    }

    private fun nativeMetricsDebug(values: LongArray): String {
        if (values.size < 48) return "metrics=short"
        return "native p=${values[0]} e=${values[1]}/${values[2]} ns=${values[3]} ms=${values[4]} " +
            "rec=${values[5]}/${values[6]}/${values[7]}/seg${values[8]}/pending${values[9]} " +
            "enc=${values[12]}/${values[13]}/${values[14]} pfps=${values[15]}/${values[16]} cfg=${values[17]} " +
            "i0=${values[20]}/${values[21]}/${values[23]}/${values[25]}/${values[26]} " +
            "i1=${values[27]}/${values[28]}/${values[30]}/${values[32]}/${values[33]} " +
            "i2=${values[34]}/${values[35]}/${values[37]}/${values[39]}/${values[40]} " +
            "i3=${values[41]}/${values[42]}/${values[44]}/${values[46]}/${values[47]}"
    }
    private fun previewPairDebug(first: Int, second: Int): String = listOfNotNull(slotDebug(first), slotDebug(second)).joinToString("  ")
    private fun slotDebug(index: Int): String? = slots.getOrNull(index)?.let {
        val error = if (it.renderFailures > 0L && it.lastPreviewError != "无") "/err:${it.lastPreviewError}" else ""
        "${it.spec.label}:r${it.renderedFrames}/sig${it.frameSignals}/e${it.renderFailures}/${it.lastRenderMs}ms$error"
    }
    private fun publishStatusIfNeeded() { if (SystemClock.elapsedRealtime() - lastPreviewDebugUpdateMs < 1000L) return; lastPreviewDebugUpdateMs = SystemClock.elapsedRealtime(); publishStatus() }
    private fun publishStatus() { listener?.onStatusChanged(status()) }

    private data class CameraSpec(val name: String, val label: String, val cameraId: String, val rotation: Int)

    data class HealthSnapshot(
        val cameraAccessAllowed: Boolean,
        val released: Boolean,
        val recording: Boolean,
        val slots: List<SlotHealth>,
        val recordingMetrics: com.kooo.evcam.v2.ui.RecordingMetrics?
    )

    data class SlotHealth(
        val index: Int,
        val label: String,
        val cameraId: String,
        val deviceOpen: Boolean,
        val sessionOpen: Boolean,
        val inputReady: Boolean,
        val previewAttached: Boolean,
        val frameSignals: Long,
        val renderedFrames: Long,
        val renderFailures: Long,
        val lastRenderMs: Long,
        val lastError: String
    )

    private fun cameraSpecsForCurrentModel(): List<CameraSpec> {
        val mapping = V2VehicleModelSettings.getModel(context).mapping
        return listOf(
            CameraSpec("front", "前", mapping.front, 0),
            CameraSpec("back", "后", mapping.back, 0),
            CameraSpec("left", "左", mapping.left, 270),
            CameraSpec("right", "右", mapping.right, 90)
        )
    }

    private inner class Slot(val index: Int, val spec: CameraSpec) {
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        var inputSurfaceTexture: SurfaceTexture? = null
        var inputSurface: Surface? = null
        var previewAttached = false

        var frameSignals = 0L
        var renderedFrames = 0L
        var renderFailures = 0L
        var lastRenderMs = 0L
        var lastPreviewError = "无"
        var lastPreviewPostMs = 0L

        private var thread: HandlerThread? = null
        var handler: Handler? = null
        private var fpsFrames = 0
        private var fpsWindowStartedMs = 0L

        fun ensureThread(): Handler {
            handler?.let { return it }
            val next = HandlerThread("V2Camera-${spec.name}-${spec.cameraId}").also { it.start() }
            thread = next
            return Handler(next.looper).also { handler = it }
        }

        fun close() {
            V2AppLog.i(
                "V2CameraEngine",
                "close slot ${spec.name}/${spec.cameraId} hasSession=${session != null} hasDevice=${device != null} hasInput=${inputSurface != null}"
            )
            resetRenderState()
            session?.close()
            session = null
            device?.close()
            device = null
            inputSurface?.release()
            inputSurface = null
            inputSurfaceTexture?.release()
            inputSurfaceTexture = null
            thread?.quitSafely()
            runCatching { thread?.join(500L) }
                .onFailure { V2AppLog.w("V2CameraEngine", "join camera thread failed ${spec.name}/${spec.cameraId}", it) }
            thread = null
            handler = null
        }

        fun resetRenderState() {
            lastPreviewPostMs = 0L
            lastRenderMs = 0L
            lastPreviewError = "无"
        }

        fun resetFps() {
            fpsFrames = 0
            fpsWindowStartedMs = SystemClock.elapsedRealtime()
        }
    }

    private fun Slot.ensureInputSurface() {
        if (inputSurface != null) return
        resetRenderState()

        val textureId = VulkanNative.createOesTexture(pipelineHandle, index)
        if (textureId <= 0) {
            V2AppLog.e("V2CameraEngine", "create OES texture failed ${spec.name}/${spec.cameraId}: ${VulkanNative.getLastError()}")
            return
        }

        val size = choosePreviewSize(spec.cameraId, 1280, 720)
            ?: Size(1280, 720).also {
                V2AppLog.w("V2CameraEngine", "preview size fallback ${spec.name}/${spec.cameraId} ${it.width}x${it.height}")
            }
        V2AppLog.d("V2CameraEngine", "${spec.label}/${spec.cameraId} OES input size ${size.width}x${size.height}")

        val texture = SurfaceTexture(textureId).apply {
            setDefaultBufferSize(size.width, size.height)
        }
        texture.setOnFrameAvailableListener(
            {
                requestPreviewRender(this)
            },
            ensureThread()
        )

        inputSurfaceTexture = texture
        inputSurface = Surface(texture)
        if (!VulkanNative.createOesInput(pipelineHandle, index, texture)) {
            V2AppLog.e("V2CameraEngine", "create OES input failed ${spec.name}/${spec.cameraId}: ${VulkanNative.getLastError()}")
            inputSurface?.release()
            inputSurface = null
            inputSurfaceTexture?.release()
            inputSurfaceTexture = null
        }
    }
}
