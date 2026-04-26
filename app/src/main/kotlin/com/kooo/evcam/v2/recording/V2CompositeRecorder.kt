package com.kooo.evcam.v2.recording

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.nativebridge.VulkanNative
import com.kooo.evcam.v2.storage.V2StorageCleaner
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class V2CompositeRecorder(
    private val context: Context,
    private val outputDir: File,
    private val debugListener: DebugListener,
    private val nativeHandle: Long,
    private val renderHandler: Handler,
    private val outputWidth: Int,
    private val outputHeight: Int,
    private val videoBitrate: Int,
) {
    interface DebugListener { fun onCompositeDebug(message: String) }

    companion object {
        private const val RECORDING_FPS = 15
        private const val SEGMENT_DURATION_MS = 60_000L
        private const val TICK_SHOULD_RENDER = 1L
        private const val TICK_DROPPED = 2L
        private const val TICK_SEGMENT_DUE = 4L
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val metrics = RecordingMetrics()
    private var recording = false
    private var generation = 0L
    private var startedAtMs = 0L
    private var writer: EncoderSegmentWriter? = null
    private val releaseExecutor = Executors.newSingleThreadExecutor()
    private val cleanupExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var cleanupFuture: Future<V2StorageCleaner.CleanupResult>? = null

    fun start(): Boolean {
        V2AppLog.i("V2CompositeRecorder", "start output=${outputDir.absolutePath} size=${outputWidth}x${outputHeight} bitrate=$videoBitrate fps=$RECORDING_FPS")
        startedAtMs = SystemClock.elapsedRealtime()
        metrics.apply {
            requestedFrames = 0; renderedFrames = 0; encodedSamples = 0; droppedFrames = 0
            segmentIndex = 0; segmentSwitchMs = 0; firstSampleLatencyMs = -1; lastError = "无"
        }
        runStorageCleanupBlocking()
        val firstSegmentWallClockMs = VulkanNative.startRecordingSession(nativeHandle, RECORDING_FPS, SEGMENT_DURATION_MS, System.currentTimeMillis())
        recording = true
        generation += 1
        val startGeneration = generation
        val startResult = runOnCaptureSync { startNewSegment(0, firstSegmentWallClockMs) }
        startResult.onFailure {
            metrics.lastError = it.javaClass.simpleName + ": " + (it.message ?: "启动失败")
            recording = false
            VulkanNative.stopRecordingSession(nativeHandle)
            V2AppLog.e("V2CompositeRecorder", "start failed", it)
            publishDebug()
            return false
        }
        scheduleRecordingTick(startGeneration, 0L)
        publishDebug()
        return true
    }

    fun stop() {
        V2AppLog.i("V2CompositeRecorder", "stop requested segment=${metrics.segmentIndex} requested=${metrics.requestedFrames} rendered=${metrics.renderedFrames} encoded=${metrics.encodedSamples} dropped=${metrics.droppedFrames}")
        recording = false
        VulkanNative.stopRecordingSession(nativeHandle)
        val stopGeneration = ++generation
        val writerToStop = writer
        writer = null
        val releaseQueued = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        renderHandler.post {
            try {
                if (stopGeneration != generation) return@post
                writerToStop?.requestDrain()
                if (writerToStop != null) {
                    runCatching {
                        if (VulkanNative.renderCompositor(nativeHandle)) {
                            metrics.renderedFrames += 1
                            writerToStop.requestDrain()
                        }
                    }.onFailure { t ->
                        metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "停止补帧失败")
                        V2AppLog.e("V2CompositeRecorder", "stop final render failed", t)
                    }
                }
                runCatching { VulkanNative.detachEncoderSurface(nativeHandle) }
                    .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder surface on stop failed", it) }
                publishDebug()
            } finally {
                latch.countDown()
                writerToStop?.let {
                    if (releaseQueued.compareAndSet(false, true)) releaseWriterAsync(it, finish = true)
                }
                releaseExecutor.shutdown()
            }
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            if (!latch.await(2_000L, TimeUnit.MILLISECONDS)) {
                V2AppLog.e("V2CompositeRecorder", "stop timed out; release writer without final render")
                generation += 1
                writerToStop?.let {
                    if (releaseQueued.compareAndSet(false, true)) releaseWriterAsync(it, finish = true)
                }
                releaseExecutor.shutdown()
            }
        } else {
            V2AppLog.i("V2CompositeRecorder", "stop queued without blocking main thread")
        }
        cleanupFuture?.cancel(false)
        cleanupFuture = null
        cleanupExecutor.shutdown()
    }

    fun metricsSnapshot(): RecordingMetrics = metrics.copy()

    private fun startNewSegment(segmentIndex: Int, segmentWallClockMs: Long) {
        V2AppLog.i("V2CompositeRecorder", "start segment index=$segmentIndex wallClockMs=$segmentWallClockMs")
        consumeFinishedCleanupResult()
        if (writer != null) {
            runCatching { VulkanNative.detachEncoderSurface(nativeHandle) }
                .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder surface before segment switch failed", it) }
            writer?.let { releaseWriterAsync(it, finish = true) }
        }
        writer = EncoderSegmentWriter(outputDir, metrics, outputWidth, outputHeight, RECORDING_FPS, videoBitrate)
        writer?.startSegment(segmentIndex, segmentWallClockMs)
        val surface = writer?.surface ?: throw IllegalStateException("Encoder surface unavailable")
        if (nativeHandle == 0L) throw java.lang.IllegalStateException(VulkanNative.getLastError())
        if (!VulkanNative.attachEncoderSurface(nativeHandle, surface)) throw java.lang.IllegalStateException(VulkanNative.getLastError())
        metrics.segmentIndex = segmentIndex
        V2AppLog.i("V2CompositeRecorder", "segment attached index=$segmentIndex file=${writer?.currentFile()?.name}")
        scheduleStorageCleanup()
    }

    private fun scheduleRecordingTick(tickGeneration: Long, delayMs: Long) {
        renderHandler.postDelayed({ drawTick(tickGeneration) }, delayMs.coerceAtLeast(0L))
    }

    private fun drawTick(tickGeneration: Long) {
        if (!recording || tickGeneration != generation || writer == null) return
        try {
            metrics.requestedFrames += 1
            val tick = VulkanNative.requestRecordingTick(nativeHandle, System.currentTimeMillis())
            val shouldRender = tick and TICK_SHOULD_RENDER != 0L
            val segmentDue = tick and TICK_SEGMENT_DUE != 0L
            if (tick and TICK_DROPPED != 0L) metrics.droppedFrames += 1

            if (shouldRender) {
                writer?.requestDrain()
                when (VulkanNative.renderScheduledEncoderResult(nativeHandle)) {
                    1 -> {
                        metrics.renderedFrames += 1
                        writer?.requestDrain()
                    }
                    0 -> metrics.droppedFrames += 1
                    else -> throw java.lang.IllegalStateException(VulkanNative.getLastError())
                }
            }

            if (segmentDue) {
                val nextIndex = (tick ushr 32).toInt().coerceAtLeast(metrics.segmentIndex + 1)
                val switchStartedMs = SystemClock.elapsedRealtime()
                val segmentWallClockMs = VulkanNative.beginNextRecordingSegment(nativeHandle)
                runCatching { startNewSegment(nextIndex, segmentWallClockMs) }
                    .onSuccess { VulkanNative.completeRecordingSegmentSwitch(nativeHandle, true) }
                    .onFailure {
                        VulkanNative.completeRecordingSegmentSwitch(nativeHandle, false)
                        throw it
                    }
                metrics.segmentSwitchMs = SystemClock.elapsedRealtime() - switchStartedMs
            }
        } catch (t: Throwable) {
            metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "渲染失败")
            V2AppLog.e("V2CompositeRecorder", "render tick failed segment=${metrics.segmentIndex}", t)
            failStopOnRenderThread()
        } finally {
            publishMaybe()
            if (recording && tickGeneration == generation) {
                val nextDelay = VulkanNative.getRecordingNextTickDelayMs(nativeHandle).takeIf { it >= 0L } ?: (1000L / RECORDING_FPS)
                scheduleRecordingTick(tickGeneration, nextDelay)
            }
        }
    }

    private fun failStopOnRenderThread() {
        if (!recording) return
        recording = false
        generation += 1
        runCatching { VulkanNative.stopRecordingSession(nativeHandle) }
            .onFailure { V2AppLog.e("V2CompositeRecorder", "stop native after render failure failed", it) }
        runCatching { VulkanNative.detachEncoderSurface(nativeHandle) }
            .onFailure { V2AppLog.e("V2CompositeRecorder", "detach encoder after render failure failed", it) }
        writer?.let { releaseWriterAsync(it, finish = false) }
        writer = null
    }

    private fun runStorageCleanupBlocking() {
        runCatching { V2StorageCleaner.cleanupForReservedSpace(context, outputDir) }
            .onSuccess { logCleanupResult("before start", it) }
            .onFailure { V2AppLog.w("V2CompositeRecorder", "storage cleanup before start failed", it) }
    }

    private fun scheduleStorageCleanup() {
        val future = cleanupFuture
        if (future != null && !future.isDone) return
        cleanupFuture = cleanupExecutor.submit<V2StorageCleaner.CleanupResult> {
            runCatching { V2StorageCleaner.cleanupForReservedSpace(context, outputDir) }
                .onSuccess { logCleanupResult("background", it) }
                .getOrElse {
                    V2AppLog.w("V2CompositeRecorder", "storage cleanup background failed", it)
                    V2StorageCleaner.CleanupResult(0, 0L, outputDir.usableSpace, 0L)
                }
        }
    }

    private fun consumeFinishedCleanupResult() {
        val future = cleanupFuture ?: return
        if (!future.isDone) return
        runCatching { future.get() }.onFailure { V2AppLog.w("V2CompositeRecorder", "storage cleanup result failed", it) }
        cleanupFuture = null
    }

    private fun logCleanupResult(stage: String, result: V2StorageCleaner.CleanupResult) {
        if (result.deletedCount > 0) {
            V2AppLog.w("V2CompositeRecorder", "storage cleanup $stage deleted=${result.deletedCount} freed=${V2StorageCleaner.formatBytes(result.deletedBytes)} available=${V2StorageCleaner.formatBytes(result.availableBytes)} reserve=${V2StorageCleaner.formatBytes(result.reservedBytes)}")
        }
    }

    private fun releaseWriterAsync(writer: EncoderSegmentWriter, finish: Boolean) {
        releaseExecutor.execute {
            V2AppLog.i("V2CompositeRecorder", "release writer finish=$finish file=${writer.currentFile()?.name}")
            if (finish) writer.finishAndReleaseBlocking() else writer.releaseBlocking()
        }
    }

    private fun runOnCaptureSync(block: () -> Unit): Result<Unit> {
        val latch = CountDownLatch(1)
        val result = AtomicReference<Result<Unit>>()
        renderHandler.post {
            result.set(runCatching(block))
            latch.countDown()
        }
        latch.await()
        return result.get() ?: Result.failure(java.lang.IllegalStateException("capture init failed"))
    }

    private fun publishMaybe() {
        if (metrics.requestedFrames % RECORDING_FPS.toLong() == 0L) publishDebug()
    }

    private fun publishDebug() {
        val elapsedSeconds = if (startedAtMs > 0L) (SystemClock.elapsedRealtime() - startedAtMs) / 1000L else 0L
        val file = writer?.currentFile()
        val sizeKb = writer?.currentSizeBytes()?.div(1024L) ?: 0L
        val error = metrics.lastError.takeIf { it != "无" }?.let { " | err=$it" }.orEmpty()
        debugListener.onCompositeDebug(
            if (recording) "${elapsedSeconds}s | ${file?.name ?: "无"} ${sizeKb}KB$error" else "已停止"
        )
    }
}
