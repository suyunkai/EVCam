package com.kooo.evcam.v2.ui

import android.Manifest
import android.content.Intent
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kooo.evcam.R
import com.kooo.evcam.databinding.ActivityV2MainA7Binding
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.ui.playback.V2VideoPlaybackActivity
import com.kooo.evcam.v2.ui.settings.V2SettingsActivity
import kotlin.math.roundToInt

class V2MainActivity : AppCompatActivity() {
    companion object {
        const val EXTRA_AUTO_START_FROM_BOOT = "auto_start_from_boot"
        const val EXTRA_SILENT_MODE = "silent_mode"
        private const val BOOT_RECORDING_DELAY_MS = 3_000L
        private const val BOOT_MOVE_BACK_DELAY_MS = 1_500L
        @Volatile private var lastKnownRecording = false
    }

    private lateinit var binding: ActivityV2MainA7Binding
    private val mainHandler = Handler(Looper.getMainLooper())
    private var service: V2CameraForegroundService? = null
    private var bound = false
    private val fpsCounters = Array(4) { FpsCounter() }
    private val previewSizeLabels = Array(4) { "--×--" }
    private val previewSurfaces = arrayOfNulls<Surface>(4)
    private var autoStartFromBoot = false
    private var silentMode = false
    private var autoRecordingRequested = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = (binder as? V2CameraForegroundService.LocalBinder)?.service()
            bound = true
            V2AppLog.i("V2MainActivity", "service connected name=$name serviceReady=${service != null}")
            service?.setUiStatusListener { status ->
                binding.tvRecordingStats.post {
                    binding.tvRecordingStats.text = status
                    updatePreviewPlaceholders(service?.isPreviewPausedByAvoidance() == true)
                    syncRecordButtonFromService()
                }
            }
            service?.setUiVisibility(true) { moveTaskToBack(true) }
            bindPreviews()
            updatePreviewPlaceholders(service?.isPreviewPausedByAvoidance() == true)
            syncRecordButtonFromService()
            maybeStartBootRecording()
        }
        override fun onServiceDisconnected(name: ComponentName?) { V2AppLog.w("V2MainActivity", "service disconnected name=$name"); bound = false; service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        V2AppLog.init(this)
        V2AppLog.i("V2MainActivity", "onCreate")
        binding = ActivityV2MainA7Binding.inflate(layoutInflater)
        setContentView(binding.root)
        consumeBootIntent(intent)
        binding.btnStartRecord.setOnClickListener { toggleRecordingWithToast() }
        binding.btnExit.setOnClickListener { shutdownApp() }
        binding.btnVideoPlayback.setOnClickListener {
            startActivity(Intent(this, V2VideoPlaybackActivity::class.java))
        }
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, V2SettingsActivity::class.java))
        }
        updateRecordButton(lastKnownRecording)
        ensurePermissions()
    }

    override fun onResume() {
        super.onResume()
        V2AppLog.i("V2MainActivity", "onResume hasPermissions=${hasPermissions()}")
        if (hasPermissions()) startAndBindService()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        consumeBootIntent(intent)
        maybeStartBootRecording()
    }

    override fun onPause() {
        V2AppLog.i("V2MainActivity", "onPause")
        unbindPreviews()
        service?.setUiStatusListener(null)
        service?.setUiVisibility(false)
        if (bound) { unbindService(connection); bound = false; service = null }
        super.onPause()
    }

    override fun onDestroy() {
        V2AppLog.i("V2MainActivity", "onDestroy finishing=$isFinishing bound=$bound")
        mainHandler.removeCallbacksAndMessages(null)
        V2AppLog.saveToPersistentLog(this)
        super.onDestroy()
    }

    private fun consumeBootIntent(intent: Intent?) {
        val fromBoot = intent?.getBooleanExtra(EXTRA_AUTO_START_FROM_BOOT, false) == true
        if (!fromBoot) return
        autoStartFromBoot = true
        silentMode = intent.getBooleanExtra(EXTRA_SILENT_MODE, false)
        autoRecordingRequested = true
        intent.removeExtra(EXTRA_AUTO_START_FROM_BOOT)
        intent.removeExtra(EXTRA_SILENT_MODE)
        V2AppLog.i("V2MainActivity", "boot auto start intent consumed silent=$silentMode")
    }

    private fun maybeStartBootRecording() {
        if (!autoStartFromBoot || !autoRecordingRequested || !bound) return
        val cameraService = service ?: return
        autoRecordingRequested = false
        V2AppLog.i("V2MainActivity", "schedule boot auto recording alreadyRecording=${cameraService.isRecording()}")
        mainHandler.postDelayed({
            val readyService = service
            if (readyService == null || !bound) {
                V2AppLog.w("V2MainActivity", "boot auto recording skipped: service unavailable")
                autoRecordingRequested = true
                return@postDelayed
            }
            if (!readyService.isRecording()) {
                V2AppLog.i("V2MainActivity", "boot auto recording start")
                readyService.startRecording()
                updateRecordButton(readyService.isRecording())
            }
            mainHandler.postDelayed({
                if (silentMode && service?.isRecording() == true) {
                    V2AppLog.i("V2MainActivity", "boot auto recording active, move task to back")
                    moveTaskToBack(true)
                }
            }, BOOT_MOVE_BACK_DELAY_MS)
        }, BOOT_RECORDING_DELAY_MS)
    }

    private fun updateRecordButton(recording: Boolean) {
        lastKnownRecording = recording
        binding.btnStartRecord.setBackgroundResource(
            if (recording) R.drawable.v2_record_control_recording else R.drawable.v2_record_control_idle
        )
        binding.btnStartRecord.contentDescription = if (recording) "停止录制" else "开始录制"
    }

    private fun syncRecordButtonFromService() {
        service?.let { updateRecordButton(it.isRecording()) }
    }

    private fun toggleRecordingWithToast() {
        val cameraService = service
        if (cameraService == null) {
            V2AppLog.w("V2MainActivity", "toggle recording skipped: service null")
            Toast.makeText(this, "相机服务启动中", Toast.LENGTH_SHORT).show()
            return
        }
        val wasRecording = cameraService.isRecording()
        val isRecording = cameraService.toggleRecording()
        V2AppLog.i("V2MainActivity", "toggle recording was=$wasRecording now=$isRecording")
        updateRecordButton(isRecording)
        val message = when {
            !wasRecording && isRecording -> "开始录制"
            wasRecording && !isRecording -> "停止录制"
            !wasRecording && !isRecording -> "录制启动失败"
            else -> if (isRecording) "正在录制" else "已停止录制"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun shutdownApp() {
        V2AppLog.w("V2MainActivity", "shutdown app requested")
        Toast.makeText(this, "正在退出并停止服务", Toast.LENGTH_SHORT).show()
        val cameraService = service
        unbindPreviews()
        cameraService?.setUiStatusListener(null)
        cameraService?.setUiVisibility(false)
        cameraService?.shutdownFromUi()
        if (bound) {
            runCatching { unbindService(connection) }
            bound = false
        }
        service = null
        stopService(Intent(this, V2CameraForegroundService::class.java))
        finishAndRemoveTask()
    }

    private fun ensurePermissions() {
        val perms = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        val missing = perms.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        V2AppLog.i("V2MainActivity", "ensurePermissions missing=$missing")
        if (missing) ActivityCompat.requestPermissions(this, perms, 2001) else startAndBindService()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 2001) {
            V2AppLog.i("V2MainActivity", "permission result grants=${grantResults.joinToString()} hasPermissions=${hasPermissions()}")
            if (hasPermissions()) startAndBindService() else Toast.makeText(this, "相机和录音权限未授予", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hasPermissions() = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun startAndBindService() {
        V2AppLog.i("V2MainActivity", "startAndBindService bound=$bound")
        val intent = Intent(this, V2CameraForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
        if (!bound) bindService(intent, connection, BIND_AUTO_CREATE)
    }

    private fun bindPreviews() {
        val sizeViews = listOf(binding.fpsFront, binding.fpsBack, binding.fpsLeft, binding.fpsRight)
        listOf(binding.textureFront, binding.textureBack, binding.textureLeft, binding.textureRight).forEachIndexed { index, texture ->
            texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                    V2AppLog.i("V2MainActivity", "preview surface available index=$index size=${width}x$height")
                    fpsCounters[index].reset()
                    previewSizeLabels[index] = service?.previewInputSizeLabel(index) ?: "--×--"
                    sizeViews[index].text = "${previewSizeLabels[index]}\n-- fps"
                    attachPreviewSurface(index, surface)
                }
                override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
                override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean { V2AppLog.i("V2MainActivity", "preview surface destroyed index=$index"); detachPreviewSurface(index); return true }
                override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {
                    fpsCounters[index].onFrame()?.let { fps -> sizeViews[index].text = "${previewSizeLabels[index]}\n$fps fps" }
                }
            }
            if (texture.isAvailable && texture.surfaceTexture != null) attachPreviewSurface(index, texture.surfaceTexture!!)
        }
    }

    private fun attachPreviewSurface(index: Int, surfaceTexture: android.graphics.SurfaceTexture) {
        detachPreviewSurface(index)
        val surface = Surface(surfaceTexture)
        previewSurfaces[index] = surface
        V2AppLog.d("V2MainActivity", "attachPreviewSurface index=$index valid=${surface.isValid}")
        service?.attachPreviewSurface(index, surface)
        updatePreviewPlaceholders(service?.isPreviewPausedByAvoidance() == true)
        previewSizeLabels[index] = service?.previewInputSizeLabel(index) ?: "--×--"
        listOf(binding.fpsFront, binding.fpsBack, binding.fpsLeft, binding.fpsRight).getOrNull(index)?.text = "${previewSizeLabels[index]}\n-- fps"
    }

    private fun detachPreviewSurface(index: Int) {
        V2AppLog.d("V2MainActivity", "detachPreviewSurface index=$index hadSurface=${previewSurfaces[index] != null}")
        service?.detachPreviewSurface(index)
        previewSurfaces[index]?.release()
        previewSurfaces[index] = null
    }

    private fun unbindPreviews() { repeat(4) { detachPreviewSurface(it) } }

    private fun updatePreviewPlaceholders(paused: Boolean) {
        val visibility = if (paused) View.VISIBLE else View.GONE
        listOf(
            binding.previewPlaceholderFront,
            binding.previewPlaceholderBack,
            binding.previewPlaceholderLeft,
            binding.previewPlaceholderRight
        ).forEach { it.visibility = visibility }
    }

    private class FpsCounter {
        private var frames = 0
        private var startedMs = 0L

        fun reset() {
            frames = 0
            startedMs = SystemClock.elapsedRealtime()
        }

        fun onFrame(): Int? {
            val now = SystemClock.elapsedRealtime()
            if (startedMs == 0L) startedMs = now
            frames += 1
            val elapsed = now - startedMs
            if (elapsed < 1000L) return null
            val value = ((frames * 1000f) / elapsed).roundToInt().coerceAtLeast(1)
            frames = 0
            startedMs = now
            return value
        }
    }
}
