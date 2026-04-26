package com.kooo.evcam.v2.ui

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.os.SystemClock
import com.kooo.evcam.v2.log.V2AppLog
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class EncoderSegmentWriter(
    private val outputDir: File,
    private val metrics: RecordingMetrics,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    private val bitrate: Int,
    private val mimeType: String = MediaFormat.MIMETYPE_VIDEO_AVC
) {
    private var codec: MediaCodec? = null
    private var muxer: MediaMuxer? = null
    private var inputSurface: android.view.Surface? = null
    private var trackIndex = -1
    private var muxerStarted = false
    private var writtenSamples = 0L
    private var segmentStartedAtMs = 0L
    private var currentFile: File? = null
    private var tempFile: File? = null
    private val drainExecutor = Executors.newSingleThreadExecutor()
    private val drainPending = AtomicBoolean(false)
    @Volatile private var finishing = false

    val surface: android.view.Surface? get() = inputSurface

    fun startSegment(segmentIndex: Int, segmentWallClockMs: Long): File {
        V2AppLog.i("EncoderSegmentWriter", "startSegment index=$segmentIndex size=${width}x${height} fps=$fps bitrate=$bitrate mime=$mimeType")
        releaseInternal()
        finishing = false
        drainPending.set(false)
        metrics.segmentIndex = segmentIndex
        segmentStartedAtMs = SystemClock.elapsedRealtime()
        val formatStamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date(segmentWallClockMs))
        currentFile = uniqueFile(formatStamp)
        tempFile = File(outputDir, currentFile!!.name + ".recording")
        tempFile?.delete()
        val format = MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = MediaCodec.createEncoderByType(mimeType).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = createInputSurface()
            start()
        }
        muxer = MediaMuxer(tempFile!!.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        trackIndex = -1
        muxerStarted = false
        writtenSamples = 0L
        V2AppLog.i("EncoderSegmentWriter", "segment ready temp=${tempFile?.absolutePath} final=${currentFile?.absolutePath}")
        return currentFile!!
    }

    fun requestDrain() {
        if (finishing) return
        if (!drainPending.compareAndSet(false, true)) return
        drainExecutor.execute {
            try {
                drainInternal(false)
            } catch (t: Throwable) {
                metrics.lastError = t.javaClass.simpleName + ": " + (t.message ?: "drain failed")
                V2AppLog.e("EncoderSegmentWriter", "async drain failed file=${currentFile?.name}", t)
            } finally {
                drainPending.set(false)
            }
        }
    }

    private fun drainInternal(endOfStream: Boolean) {
        val codec = codec ?: return
        val muxer = muxer ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (!muxerStarted) {
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                }
                outIndex >= 0 -> {
                    val encoded = codec.getOutputBuffer(outIndex)
                    val codecConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (encoded != null && info.size > 0 && muxerStarted && !codecConfig) {
                        encoded.position(info.offset)
                        encoded.limit(info.offset + info.size)
                        muxer.writeSampleData(trackIndex, encoded, info)
                        writtenSamples += 1
                        metrics.encodedSamples += 1
                        if (metrics.firstSampleLatencyMs < 0) {
                            metrics.firstSampleLatencyMs = SystemClock.elapsedRealtime() - segmentStartedAtMs
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    fun finishAndReleaseBlocking(timeoutMs: Long = 10_000L) {
        finishing = true
        val latch = CountDownLatch(1)
        drainExecutor.execute {
            try {
                val eosSignaled = runCatching { codec?.signalEndOfInputStream() }
                    .onFailure { V2AppLog.e("EncoderSegmentWriter", "signal EOS failed file=${currentFile?.name}", it) }
                    .isSuccess
                runCatching { drainInternal(eosSignaled) }
                    .onFailure { V2AppLog.e("EncoderSegmentWriter", "final drain failed file=${currentFile?.name}", it) }
                releaseInternal()
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        drainExecutor.shutdown()
    }

    fun releaseBlocking(timeoutMs: Long = 1500L) {
        finishing = true
        val latch = CountDownLatch(1)
        drainExecutor.execute {
            try {
                releaseInternal()
            } finally {
                latch.countDown()
            }
        }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        drainExecutor.shutdown()
    }

    private fun releaseInternal() {
        val muxerStopOk = if (muxerStarted && writtenSamples > 0L) {
            runCatching { muxer?.stop() }
                .onFailure {
                    metrics.lastError = it.javaClass.simpleName + ": " + (it.message ?: "muxer stop failed")
                    V2AppLog.e("EncoderSegmentWriter", "muxer stop failed file=${currentFile?.name} samples=$writtenSamples", it)
                }
                .isSuccess
        } else {
            false
        }
        runCatching { muxer?.release() }
        muxer = null
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
        runCatching { inputSurface?.release() }
        inputSurface = null
        val finishedFile = finalizeTempFile(muxerStopOk)
        V2AppLog.i("EncoderSegmentWriter", "release complete file=${currentFile?.name} temp=${tempFile?.name} muxerStopOk=$muxerStopOk samples=$writtenSamples final=${finishedFile?.name}")
        finishedFile?.let { RecordingThumbnailer.generateFirstFrameAsync(it) }
    }

    fun currentFile(): File? = currentFile
    fun currentSizeBytes(): Long = tempFile?.takeIf { it.exists() }?.length() ?: currentFile?.takeIf { it.exists() }?.length() ?: 0L

    private fun finalizeTempFile(muxerStopOk: Boolean): File? {
        val temp = tempFile ?: return currentFile?.takeIf { it.exists() && it.length() > 0L }
        val final = currentFile ?: return null
        if (!muxerStopOk || writtenSamples <= 0L || !temp.exists() || temp.length() <= 0L) {
            V2AppLog.w("EncoderSegmentWriter", "drop temp segment muxerStopOk=$muxerStopOk samples=$writtenSamples exists=${temp.exists()} size=${temp.length()} temp=${temp.name}")
            runCatching { temp.delete() }
            return null
        }
        final.delete()
        return if (temp.renameTo(final)) {
            V2AppLog.i("EncoderSegmentWriter", "segment finalized ${final.absolutePath} size=${final.length()}")
            final
        } else {
            V2AppLog.w("EncoderSegmentWriter", "segment rename failed, keep temp=${temp.absolutePath}")
            temp
        }
    }

    private fun uniqueFile(timestamp: String): File {
        var index = 0
        while (true) {
            val name = if (index == 0) "${timestamp}_composite.mp4" else String.format(java.util.Locale.US, "%s_composite_%03d.mp4", timestamp, index)
            val file = File(outputDir, name)
            if (!file.exists()) return file
            index++
        }
    }
}

private object RecordingThumbnailer {
    private val executor = Executors.newSingleThreadExecutor()

    fun generateFirstFrameAsync(video: File) {
        executor.execute { generate(video) }
    }

    private fun generate(video: File) {
        runCatching {
            if (!video.isFile || !video.canRead() || video.length() <= 0L) return
            val out = thumbnailFile(video)
            if (out.exists() && out.length() > 0L && out.lastModified() >= video.lastModified()) return

            val retriever = MediaMetadataRetriever()
            val frame = try {
                retriever.setDataSource(video.absolutePath)
                retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } finally {
                retriever.release()
            } ?: return

            out.parentFile?.mkdirs()
            FileOutputStream(out).use { stream ->
                frame.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, stream)
            }
            frame.recycle()
            V2AppLog.i("RecordingThumbnailer", "thumbnail generated ${out.absolutePath}")
        }.onFailure { V2AppLog.w("RecordingThumbnailer", "thumbnail generation failed video=${video.absolutePath}", it) }
    }

    private fun thumbnailFile(video: File): File = File(video.parentFile, video.nameWithoutExtension + ".jpg")
}
