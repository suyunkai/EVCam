package com.kooo.evcam.v2.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.Surface
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2BroadcastLogger
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2AvoidanceSettings
import com.kooo.evcam.v2.settings.V2BlindSpotSettings
import com.kooo.evcam.v2.settings.V2CustomKeySettings
import com.kooo.evcam.v2.settings.V2FisheyeSettings
import com.kooo.evcam.v2.settings.V2StartupSettings
import com.kooo.evcam.v2.ui.V2BlindSpotOverlay
import com.kooo.evcam.v2.ui.V2FisheyePreviewOverlay
import com.kooo.evcam.v2.ui.V2MainActivity

class V2CameraForegroundService : Service(), V2CameraEngine.Listener {
    companion object {
        const val ACTION_AUTO_START_RECORDING = "com.kooo.evcam.v2.action.AUTO_START_RECORDING"
        const val ACTION_REFRESH_CUSTOM_KEY = "com.kooo.evcam.v2.action.REFRESH_CUSTOM_KEY"
        const val ACTION_REFRESH_BLIND_SPOT = "com.kooo.evcam.v2.action.REFRESH_BLIND_SPOT"
        const val ACTION_REFRESH_FISHEYE = "com.kooo.evcam.v2.action.REFRESH_FISHEYE"
        const val ACTION_SHOW_FISHEYE_PREVIEW = "com.kooo.evcam.v2.action.SHOW_FISHEYE_PREVIEW"
        const val ACTION_HIDE_FISHEYE_PREVIEW = "com.kooo.evcam.v2.action.HIDE_FISHEYE_PREVIEW"
        const val EXTRA_CAMERA_INDEX = "camera_index"
        internal const val AUTO_START_RECORDING_DELAY_MS = 3_000L
        private const val AVOIDANCE_CHECK_INTERVAL_MS = 1_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 15_000L
        private const val WATCHDOG_GRACE_MS = 20_000L
        private const val WATCHDOG_FAILURE_THRESHOLD = 2
        private const val WATCHDOG_RECORDING_RESTART_DELAY_MS = 3_000L
        private const val BLIND_SPOT_HIDE_TOKEN = "blind_spot_hide"
    }

    inner class LocalBinder : Binder() {
        fun service() = this@V2CameraForegroundService
    }

    private val binder = LocalBinder()
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var engine: V2CameraEngine
    private val wakeLockHolder = V2WakeLockHolder(this)
    private val autoRecordingController = V2AutoRecordingController(
        service = this,
        handler = mainHandler,
        isDisplayPowerOn = { isDisplayPowerOn() },
        isAutoStartEnabled = { V2StartupSettings.isAutoStartRecording(this) },
        isRecording = { engine.isRecording() },
        startRecording = { engine.startRecording() },
        showToast = { showServiceToast(it) }
    )
    private val displayPowerCoordinator = V2DisplayPowerCoordinator(
        service = this,
        onDisplayOff = { action -> handleDisplayOff(action) },
        onDisplayOn = { action -> handleDisplayOn(action) }
    )
    private var uiStatusListener: ((String) -> Unit)? = null
    private var uiHideListener: (() -> Unit)? = null
    private var uiVisible = false
    private var manualShutdown = false
    private var customKeyObserver: V2VhalCustomKeyObserver? = null
    private var turnSignalObserver: V2VhalTurnSignalObserver? = null
    private var blindSpotOverlay: V2BlindSpotOverlay? = null
    private var blindSpotCameraIndex = -1
    private var restoreUiAfterBlindSpot = false
    private var fisheyePreviewOverlay: V2FisheyePreviewOverlay? = null
    private var fisheyePreviewCameraIndex = -1
    @Volatile private var blindSpotSignalIsOff = true
    private lateinit var foregroundAppMonitor: V2ForegroundAppMonitor
    private var avoidanceSnapshot: AvoidanceSnapshot? = null
    private var activeAvoidanceTarget: String? = null
    private var lastToastText: String? = null
    private var lastToastMs = 0L
    private val previewSurfaces = arrayOfNulls<Surface>(4)
    @Volatile private var displayPowerOn = true
    private var watchdogLastSnapshot: V2CameraEngine.HealthSnapshot? = null
    private var watchdogFailureCount = 0
    private var watchdogLastResetMs = 0L

    override fun onCreate() {
        super.onCreate()
        V2AppLog.init(this)
        displayPowerOn = V2DisplayPowerState.initialValue(isSystemInteractive())
        V2AppLog.i("V2CameraService", "onCreate autoRecord=${V2StartupSettings.isAutoStartRecording(this)} displayPowerOn=${isDisplayPowerOn()} systemInteractive=${isSystemInteractive()}")
        engine = V2CameraEngine(this, this)
        foregroundAppMonitor = V2ForegroundAppMonitor(this)
        startForeground(2001, buildNotification("camera ready"))
        V2AppLog.i("V2CameraService", "foreground notification started")
        displayPowerCoordinator.register()
        engine.setCameraAccessAllowed(isDisplayPowerOn())
        if (isDisplayPowerOn()) engine.startCameras()
        wakeLockHolder.acquire()
        startCustomKeyObserver()
        startBlindSpotObserver()
        startAvoidanceMonitor()
        startWatchdog()
        V2KeepAliveScheduler.schedule(this)
        V2KeepAliveReceiver.registerDynamic(this)
        autoRecordingController.scheduleIfEnabled()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        V2BroadcastLogger.logServiceStart("V2CameraService", intent, flags, startId)
        val action = intent?.action
        when {
            action == ACTION_AUTO_START_RECORDING -> autoRecordingController.scheduleIfEnabled()
            action == ACTION_REFRESH_CUSTOM_KEY -> restartCustomKeyObserver()
            action == ACTION_REFRESH_BLIND_SPOT -> restartBlindSpotObserver()
            action == ACTION_REFRESH_FISHEYE -> engine.applyFisheyeSettings()
            action == ACTION_SHOW_FISHEYE_PREVIEW -> showFisheyePreview(intent.getIntExtra(EXTRA_CAMERA_INDEX, 0))
            action == ACTION_HIDE_FISHEYE_PREVIEW -> hideFisheyePreview()
            V2DisplayPowerActions.isDisplayOff(action) -> handleDisplayOff(action)
            V2DisplayPowerActions.isDisplayOn(action) -> handleDisplayOn(action)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        V2AppLog.i("V2CameraService", "onDestroy recording=${engine.isRecording()}")
        mainHandler.removeCallbacksAndMessages(null)
        displayPowerCoordinator.unregister()
        V2KeepAliveReceiver.unregisterDynamic(this)
        stopBlindSpotObserver()
        hideBlindSpotOverlay()
        hideFisheyePreview()
        stopCustomKeyObserver()
        releaseWakeLock()
        engine.release()
        V2AppLog.saveToPersistentLog(this)
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (manualShutdown) {
            V2AppLog.i("V2CameraService", "task removed ignored: manual shutdown")
            super.onTaskRemoved(rootIntent)
            return
        }
        V2AppLog.w("V2CameraService", "task removed, requesting service restart")
        val restartIntent = Intent(applicationContext, V2CameraForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(restartIntent)
            } else {
                applicationContext.startService(restartIntent)
            }
        } catch (error: Exception) {
            V2AppLog.e("V2CameraService", "restart after task removed failed", error)
        }
        super.onTaskRemoved(rootIntent)
    }

    fun toggleRecording(): Boolean {
        if (!isDisplayPowerOn()) {
            V2AppLog.w("V2CameraService", "toggleRecording skipped: display off")
            return false
        }
        val result = engine.toggleRecording()
        V2AppLog.i("V2CameraService", "toggleRecording result=$result")
        return result
    }
    fun isRecording(): Boolean = engine.isRecording()
    fun statusText(): String = engine.statusText()
    fun attachPreviewSurface(index: Int, surface: Surface) {
        previewSurfaces[index] = surface
        if (!isDisplayPowerOn()) {
            V2AppLog.w("V2CameraService", "attachPreviewSurface cached but skipped: display off index=$index")
            return
        }
        engine.attachPreviewSurface(index, surface)
    }
    fun detachPreviewSurface(index: Int) {
        engine.detachPreviewSurface(index)
        previewSurfaces[index] = null
    }
    fun startRecording() {
        V2AppLog.i("V2CameraService", "manual startRecording displayPowerOn=${isDisplayPowerOn()} systemInteractive=${isSystemInteractive()}")
        if (isDisplayPowerOn()) {
            engine.startRecording()
        } else {
            V2AppLog.w("V2CameraService", "manual startRecording skipped: display off")
        }
    }
    fun stopRecording() { V2AppLog.i("V2CameraService", "manual stopRecording"); engine.stopRecording() }
    fun shutdownFromUi() {
        V2AppLog.w("V2CameraService", "manual shutdown from UI")
        manualShutdown = true
        mainHandler.removeCallbacksAndMessages(null)
        engine.stopRecording()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    fun setUiStatusListener(listener: ((String) -> Unit)?) {
        uiStatusListener = listener
        listener?.invoke(engine.statusText())
    }
    fun setUiVisibility(visible: Boolean, hideListener: (() -> Unit)? = null) {
        uiVisible = visible
        uiHideListener = if (visible) hideListener else null
        V2AppLog.i("V2CameraService", "uiVisible=$uiVisible")
    }

    private fun handleDisplayOff(action: String?) {
        displayPowerOn = V2DisplayPowerState.updateFromAction(action) ?: false
        V2AppLog.i("V2CameraService", "display off action=$action: stop recording, detach preview, release cameras")
        resetWatchdogState("display_off")
        autoRecordingController.cancelPending()
        if (avoidanceSnapshot != null) V2AppLog.i("V2CameraService", "display off clears active avoidance target=$activeAvoidanceTarget")
        avoidanceSnapshot = null
        activeAvoidanceTarget = null
        hideBlindSpotOverlay()
        hideFisheyePreview()
        pauseCameraForDisplayOff()
        uiStatusListener?.invoke(engine.statusText())
        V2AppLog.saveToPersistentLog(this)
    }

    private fun handleDisplayOn(action: String?) {
        displayPowerOn = V2DisplayPowerState.updateFromAction(action) ?: true
        V2AppLog.i("V2CameraService", "display on action=$action: allow cameras and reconnect previews")
        resumeCameraForDisplayOn()
        previewSurfaces.forEachIndexed { index, surface ->
            if (surface?.isValid == true) engine.attachPreviewSurface(index, surface)
        }
        autoRecordingController.scheduleIfEnabled()
        resetWatchdogState("display_on")
        startWatchdog()
        uiStatusListener?.invoke(engine.statusText())
    }

    private fun startCustomKeyObserver() {
        if (customKeyObserver != null) return
        if (!V2CustomKeySettings.isEnabled(this)) {
            V2AppLog.i("V2CameraService", "VHAL custom key observer skipped: disabled")
            return
        }
        val buttonPropId = V2CustomKeySettings.buttonPropId(this)
        customKeyObserver = V2VhalCustomKeyObserver(buttonPropId) { handleCustomKeyToggle() }.also { it.start() }
        V2AppLog.i("V2CameraService", "VHAL custom key observer started buttonPropId=$buttonPropId")
    }

    private fun restartCustomKeyObserver() {
        V2AppLog.i("V2CameraService", "refresh VHAL custom key observer")
        stopCustomKeyObserver()
        startCustomKeyObserver()
    }

    private fun stopCustomKeyObserver() {
        customKeyObserver?.stop()
        customKeyObserver = null
        V2AppLog.i("V2CameraService", "VHAL custom key observer stopped")
    }

    private fun startBlindSpotObserver() {
        if (turnSignalObserver != null) return
        if (!V2BlindSpotSettings.isEnabled(this)) {
            V2AppLog.i("V2CameraService", "blind spot observer skipped: disabled")
            return
        }
        val propId = V2BlindSpotSettings.turnSignalPropId(this)
        turnSignalObserver = V2VhalTurnSignalObserver(
            propId,
            V2BlindSpotSettings.LEFT_VALUE,
            V2BlindSpotSettings.RIGHT_VALUE,
            V2BlindSpotSettings.OFF_VALUE
        ) { side, on -> handleTurnSignalForBlindSpot(side, on) }.also { it.start() }
        V2AppLog.i("V2CameraService", "blind spot observer started propId=$propId left=${V2BlindSpotSettings.LEFT_VALUE} right=${V2BlindSpotSettings.RIGHT_VALUE}")
    }

    private fun stopBlindSpotObserver() {
        turnSignalObserver?.stop()
        turnSignalObserver = null
        V2AppLog.i("V2CameraService", "blind spot observer stopped")
    }

    private fun restartBlindSpotObserver() {
        V2AppLog.i("V2CameraService", "refresh blind spot observer")
        stopBlindSpotObserver()
        hideBlindSpotOverlay()
        startBlindSpotObserver()
    }

    private fun handleTurnSignalForBlindSpot(side: String, on: Boolean) {
        mainHandler.post {
            if (!V2BlindSpotSettings.isEnabled(this)) return@post
            if (on) {
                blindSpotSignalIsOff = false
                mainHandler.removeCallbacksAndMessages(BLIND_SPOT_HIDE_TOKEN)
                showBlindSpotOverlay(side)
            } else {
                blindSpotSignalIsOff = true
                V2AppLog.i("V2CameraService", "blind spot signal off side=$side, hide after ${V2BlindSpotSettings.HIDE_DELAY_MS}ms")
                mainHandler.removeCallbacksAndMessages(BLIND_SPOT_HIDE_TOKEN)
                mainHandler.postDelayed({
                    if (blindSpotSignalIsOff) hideBlindSpotOverlay()
                    else V2AppLog.i("V2CameraService", "blind spot hide canceled: signal is active again")
                }, BLIND_SPOT_HIDE_TOKEN, V2BlindSpotSettings.HIDE_DELAY_MS)
            }
        }
    }

    private fun showBlindSpotOverlay(side: String) {
        if (avoidanceSnapshot != null) {
            V2AppLog.i("V2CameraService", "blind spot show skipped: avoidance active target=$activeAvoidanceTarget side=$side")
            return
        }
        if (!isDisplayPowerOn()) {
            V2AppLog.w("V2CameraService", "blind spot show skipped: display off side=$side")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            V2AppLog.w("V2CameraService", "blind spot show skipped: overlay permission missing")
            Toast.makeText(this, "补盲悬浮窗需要悬浮窗权限", Toast.LENGTH_SHORT).show()
            return
        }
        if (uiVisible) {
            V2AppLog.i("V2CameraService", "blind spot hide preview UI before overlay side=$side")
            restoreUiAfterBlindSpot = true
            uiHideListener?.invoke()
        }
        val index = engine.previewIndexForPosition(side) ?: run {
            V2AppLog.w("V2CameraService", "blind spot show skipped: no preview index for side=$side")
            return
        }
        val previousIndex = blindSpotCameraIndex
        blindSpotCameraIndex = index
        if (blindSpotOverlay == null) {
            blindSpotOverlay = V2BlindSpotOverlay(
                this,
                attachPreview = { cameraIndex, surface -> engine.attachPreviewSurface(cameraIndex, surface) },
                detachPreview = { cameraIndex -> engine.detachPreviewSurface(cameraIndex) }
            )
        }
        V2AppLog.i("V2CameraService", "blind spot show side=$side ${engine.previewDescription(index)}")
        blindSpotOverlay?.show(side, index)
        if (previousIndex >= 0 && previousIndex != index && isDisplayPowerOn()) {
            previewSurfaces.getOrNull(previousIndex)?.takeIf { it.isValid }?.let { engine.attachPreviewSurface(previousIndex, it) }
        }
    }

    private fun hideBlindSpotOverlay() {
        val index = blindSpotCameraIndex
        blindSpotOverlay?.hide()
        blindSpotCameraIndex = -1
        if (index >= 0 && isDisplayPowerOn()) {
            previewSurfaces.getOrNull(index)?.takeIf { it.isValid }?.let { engine.attachPreviewSurface(index, it) }
        }
        restoreUiAfterBlindSpotIfNeeded()
        V2AppLog.i("V2CameraService", "blind spot overlay hidden index=$index")
    }

    private fun restoreUiAfterBlindSpotIfNeeded() {
        if (!restoreUiAfterBlindSpot) return
        restoreUiAfterBlindSpot = false
        if (!isDisplayPowerOn() || uiVisible) return
        val intent = Intent(this, V2MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        V2AppLog.i("V2CameraService", "blind spot restore preview UI")
        runCatching { startActivity(intent) }
            .onFailure { V2AppLog.e("V2CameraService", "blind spot restore UI failed", it) }
    }

    private fun showFisheyePreview(index: Int) {
        if (!isDisplayPowerOn()) {
            V2AppLog.w("V2CameraService", "fisheye preview skipped: display off index=$index")
            showServiceToast("屏幕关闭，无法打开鱼眼预览")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            V2AppLog.w("V2CameraService", "fisheye preview skipped: overlay permission missing")
            showServiceToast("鱼眼预览需要悬浮窗权限")
            return
        }
        val params = V2FisheyeSettings.defaultParamsForIndex(index)
        if (fisheyePreviewOverlay == null) {
            fisheyePreviewOverlay = V2FisheyePreviewOverlay(
                this,
                attachPreview = { cameraIndex, surface -> engine.attachPreviewSurface(cameraIndex, surface) },
                detachPreview = { cameraIndex -> engine.detachPreviewSurface(cameraIndex) },
                onClose = { hideFisheyePreview() }
            )
        }
        val previousIndex = fisheyePreviewCameraIndex
        fisheyePreviewCameraIndex = index
        engine.applyFisheyeSettings()
        fisheyePreviewOverlay?.show(params.label, index)
        if (previousIndex >= 0 && previousIndex != index && isDisplayPowerOn()) {
            previewSurfaces.getOrNull(previousIndex)?.takeIf { it.isValid }?.let { engine.attachPreviewSurface(previousIndex, it) }
        }
        V2AppLog.i("V2CameraService", "fisheye preview show index=$index label=${params.label}")
    }

    private fun hideFisheyePreview() {
        val index = fisheyePreviewCameraIndex
        fisheyePreviewOverlay?.hide()
        fisheyePreviewCameraIndex = -1
        if (index >= 0 && isDisplayPowerOn()) {
            previewSurfaces.getOrNull(index)?.takeIf { it.isValid }?.let { engine.attachPreviewSurface(index, it) }
        }
        V2AppLog.i("V2CameraService", "fisheye preview hidden index=$index")
    }

    private fun handleCustomKeyToggle() {
        mainHandler.post {
            if (uiVisible) {
                V2AppLog.i("V2CameraService", "custom key value 4: hide UI")
                uiHideListener?.invoke()
            } else {
                V2AppLog.i("V2CameraService", "custom key value 4: show UI")
                val intent = Intent(this, V2MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                runCatching { startActivity(intent) }
                    .onFailure { V2AppLog.e("V2CameraService", "custom key show UI failed", it) }
            }
        }
    }

    private fun startAvoidanceMonitor() {
        mainHandler.removeCallbacks(avoidanceRunnable)
        mainHandler.post(avoidanceRunnable)
        V2AppLog.i("V2CameraService", "avoidance monitor started targets=${V2AvoidanceSettings.targetValues().joinToString()} behavior=${V2AvoidanceSettings.behaviorLabels(V2AvoidanceSettings.behaviorMask(this))}")
    }

    private val avoidanceRunnable = object : Runnable {
        override fun run() {
            runCatching { checkAvoidanceTarget() }
                .onFailure { V2AppLog.e("V2CameraService", "avoidance monitor tick failed", it) }
            mainHandler.postDelayed(this, AVOIDANCE_CHECK_INTERVAL_MS)
        }
    }

    private fun checkAvoidanceTarget() {
        val behaviorMask = V2AvoidanceSettings.behaviorMask(this)
        val target = if (behaviorMask == 0 || !isDisplayPowerOn()) null else foregroundAppMonitor.findForegroundTarget(V2AvoidanceSettings.targetValues())
        if (target != null && avoidanceSnapshot == null) {
            enterAvoidance(target, behaviorMask)
        } else if (target == null && avoidanceSnapshot != null) {
            exitAvoidance()
        } else if (target != null && target != activeAvoidanceTarget) {
            V2AppLog.i("V2CameraService", "avoidance target changed $activeAvoidanceTarget -> $target")
            activeAvoidanceTarget = target
        }
    }

    private fun enterAvoidance(target: String, behaviorMask: Int) {
        val snapshot = AvoidanceSnapshot(
            behaviorMask = behaviorMask,
            wasRecording = engine.isRecording(),
            wasUiVisible = uiVisible,
            stoppedPreview = behaviorMask and V2AvoidanceSettings.BEHAVIOR_STOP_PREVIEW != 0
        )
        avoidanceSnapshot = snapshot
        activeAvoidanceTarget = target
        V2AppLog.i("V2CameraService", "enter avoidance target=$target behavior=${V2AvoidanceSettings.behaviorLabels(behaviorMask)} wasRecording=${snapshot.wasRecording} wasUiVisible=${snapshot.wasUiVisible}")
        mainHandler.removeCallbacksAndMessages(BLIND_SPOT_HIDE_TOKEN)
        hideBlindSpotOverlay()
        hideFisheyePreview()
        showServiceToast("避让中")

        if (behaviorMask and V2AvoidanceSettings.BEHAVIOR_EXIT_FOREGROUND != 0 && uiVisible) {
            V2AppLog.i("V2CameraService", "avoidance hide UI")
            uiHideListener?.invoke()
        }
        if (behaviorMask and V2AvoidanceSettings.BEHAVIOR_STOP_PREVIEW != 0) {
            V2AppLog.i("V2CameraService", "avoidance stop preview and release cameras")
            engine.setCameraAccessAllowed(false)
        } else if (behaviorMask and V2AvoidanceSettings.BEHAVIOR_STOP_RECORDING != 0 && engine.isRecording()) {
            V2AppLog.i("V2CameraService", "avoidance stop recording")
            engine.stopRecording()
            uiStatusListener?.invoke(engine.statusText())
        }
    }

    private fun exitAvoidance() {
        val snapshot = avoidanceSnapshot ?: return
        val target = activeAvoidanceTarget
        avoidanceSnapshot = null
        activeAvoidanceTarget = null
        V2AppLog.i("V2CameraService", "exit avoidance target=$target restoreRecording=${snapshot.wasRecording} restoreUi=${snapshot.wasUiVisible} restorePreview=${snapshot.stoppedPreview}")
        if (snapshot.stoppedPreview && isDisplayPowerOn()) {
            engine.setCameraAccessAllowed(true)
            previewSurfaces.forEachIndexed { index, surface ->
                if (surface?.isValid == true) engine.attachPreviewSurface(index, surface)
            }
        }
        if (snapshot.wasRecording && isDisplayPowerOn() && !engine.isRecording()) {
            engine.startRecording()
            uiStatusListener?.invoke(engine.statusText())
        }
        showServiceToast("避让结束")
        if (snapshot.wasUiVisible) {
            val intent = Intent(this, V2MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            runCatching { startActivity(intent) }
                .onFailure { V2AppLog.e("V2CameraService", "avoidance restore UI failed", it) }
        }
    }

    private data class AvoidanceSnapshot(
        val behaviorMask: Int,
        val wasRecording: Boolean,
        val wasUiVisible: Boolean,
        val stoppedPreview: Boolean
    )

    private fun startWatchdog() {
        mainHandler.removeCallbacks(watchdogRunnable)
        watchdogLastResetMs = android.os.SystemClock.elapsedRealtime()
        watchdogLastSnapshot = engine.healthSnapshot()
        watchdogFailureCount = 0
        mainHandler.postDelayed(watchdogRunnable, WATCHDOG_CHECK_INTERVAL_MS)
        V2AppLog.i("V2CameraService", "watchdog started interval=${WATCHDOG_CHECK_INTERVAL_MS}ms")
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            runCatching { checkCameraWatchdog() }
                .onFailure { V2AppLog.e("V2CameraService", "watchdog tick failed", it) }
            mainHandler.postDelayed(this, WATCHDOG_CHECK_INTERVAL_MS)
        }
    }

    private fun checkCameraWatchdog() {
        if (!isDisplayPowerOn()) {
            resetWatchdogState("display_off_skip")
            return
        }
        if (avoidanceSnapshot?.stoppedPreview == true) {
            resetWatchdogState("avoidance_skip")
            return
        }
        val now = android.os.SystemClock.elapsedRealtime()
        val snapshot = engine.healthSnapshot()
        val previous = watchdogLastSnapshot
        watchdogLastSnapshot = snapshot
        if (now - watchdogLastResetMs < WATCHDOG_GRACE_MS) return

        val issues = mutableListOf<String>()
        val brokenSlots = snapshot.slots.filter { !it.inputReady || !it.deviceOpen || !it.sessionOpen }
        if (brokenSlots.isNotEmpty()) {
            issues += "camera=${brokenSlots.joinToString { "${it.label}(input=${it.inputReady},dev=${it.deviceOpen},sess=${it.sessionOpen})" }}"
        }

        if (previous != null) {
            val stalledPreview = snapshot.slots.filter { slot ->
                slot.previewAttached && previous.slots.firstOrNull { it.index == slot.index }?.let { prev ->
                    slot.frameSignals <= prev.frameSignals || slot.renderedFrames <= prev.renderedFrames
                } == true
            }
            if (stalledPreview.isNotEmpty()) {
                issues += "preview=${stalledPreview.joinToString { "${it.label}(sig=${it.frameSignals},r=${it.renderedFrames},err=${it.lastError})" }}"
            }
            if (snapshot.recording) {
                val metrics = snapshot.recordingMetrics
                val prevMetrics = previous.recordingMetrics
                when {
                    metrics == null -> issues += "recording=metrics_missing"
                    prevMetrics != null && metrics.requestedFrames <= prevMetrics.requestedFrames -> issues += "recording=request_stalled(${metrics.requestedFrames})"
                    prevMetrics != null && metrics.renderedFrames <= prevMetrics.renderedFrames -> issues += "recording=render_stalled(${metrics.renderedFrames})"
                    prevMetrics != null && metrics.encodedSamples <= prevMetrics.encodedSamples -> issues += "recording=encode_stalled(${metrics.encodedSamples})"
                    metrics.lastError != "无" -> issues += "recording=err:${metrics.lastError}"
                }
            }
        }

        if (issues.isEmpty()) {
            if (watchdogFailureCount > 0) V2AppLog.i("V2CameraService", "watchdog recovered")
            watchdogFailureCount = 0
            return
        }

        watchdogFailureCount += 1
        V2AppLog.w("V2CameraService", "watchdog issue count=$watchdogFailureCount/$WATCHDOG_FAILURE_THRESHOLD ${issues.joinToString("; ")}")
        if (watchdogFailureCount >= WATCHDOG_FAILURE_THRESHOLD) {
            restartCamerasFromWatchdog(issues.joinToString("; "))
        }
    }

    private fun restartCamerasFromWatchdog(reason: String) {
        if (!isDisplayPowerOn()) return
        V2AppLog.e("V2CameraService", "watchdog restarting cameras reason=$reason")
        val wasRecording = engine.isRecording()
        resetWatchdogState("restart")
        runCatching {
            if (wasRecording) engine.stopRecording()
            engine.stopCameras()
            engine.startCameras()
            previewSurfaces.forEachIndexed { index, surface ->
                if (surface?.isValid == true) engine.attachPreviewSurface(index, surface)
            }
            if (wasRecording && isDisplayPowerOn()) {
                mainHandler.postDelayed({
                    if (isDisplayPowerOn() && !engine.isRecording()) {
                        V2AppLog.w("V2CameraService", "watchdog restarting recording")
                        engine.startRecording()
                    }
                }, WATCHDOG_RECORDING_RESTART_DELAY_MS)
            }
            uiStatusListener?.invoke(engine.statusText())
        }.onFailure { V2AppLog.e("V2CameraService", "watchdog camera restart failed", it) }
    }

    private fun resetWatchdogState(reason: String) {
        watchdogLastResetMs = android.os.SystemClock.elapsedRealtime()
        watchdogFailureCount = 0
        watchdogLastSnapshot = if (::engine.isInitialized) engine.healthSnapshot() else null
        V2AppLog.i("V2CameraService", "watchdog reset reason=$reason")
    }

    private fun showServiceToast(message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (message == lastToastText && now - lastToastMs < 5_000L) return
        lastToastText = message
        lastToastMs = now
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun isSystemInteractive(): Boolean {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            powerManager.isInteractive
        } else {
            @Suppress("DEPRECATION")
            powerManager.isScreenOn
        }
    }

    private fun isDisplayPowerOn(): Boolean = displayPowerOn

    private fun pauseCameraForDisplayOff() {
        engine.setCameraAccessAllowed(false)
    }

    private fun resumeCameraForDisplayOn() {
        engine.setCameraAccessAllowed(true)
    }

    private fun releaseWakeLock() = wakeLockHolder.release()

    override fun onStatusChanged(status: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(2001, buildNotification(status))
        uiStatusListener?.invoke(status)
    }

    private fun buildNotification(text: String) : android.app.Notification {
        val channelId = "v2_camera"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(channelId, "V2 Camera", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("EVCam V2")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }
}
