package com.kooo.evcam.v2.service

import android.os.Handler
import com.kooo.evcam.v2.log.V2AppLog

internal class V2AutoRecordingController(
    private val service: V2CameraForegroundService,
    private val handler: Handler,
    private val isDisplayPowerOn: () -> Boolean,
    private val isAutoStartEnabled: () -> Boolean,
    private val isRecording: () -> Boolean,
    private val startRecording: () -> Unit,
    private val showToast: (String) -> Unit,
) {
    fun scheduleIfEnabled() {
        cancelPending()
        if (!isAutoStartEnabled()) {
            V2AppLog.i("V2CameraService", "auto recording skipped: disabled")
            return
        }
        if (!isDisplayPowerOn()) {
            V2AppLog.i("V2CameraService", "auto recording skipped: display off")
            return
        }
        handler.removeCallbacksAndMessages(V2CameraForegroundService.ACTION_AUTO_START_RECORDING)
        V2AppLog.i("V2CameraService", "auto recording scheduled delay=${V2CameraForegroundService.AUTO_START_RECORDING_DELAY_MS}ms")
        handler.postDelayed({
            if (!isAutoStartEnabled()) {
                V2AppLog.i("V2CameraService", "auto recording skipped at start time: disabled")
                return@postDelayed
            }
            if (!isDisplayPowerOn()) {
                V2AppLog.i("V2CameraService", "auto recording skipped at start time: display off")
                return@postDelayed
            }
            if (!isRecording()) {
                V2AppLog.i("V2CameraService", "auto recording start now")
                startRecording()
                if (isRecording()) showToast("自动录制已开始") else showToast("自动录制启动失败")
            } else {
                V2AppLog.i("V2CameraService", "auto recording skipped: already recording")
            }
        }, V2CameraForegroundService.ACTION_AUTO_START_RECORDING, V2CameraForegroundService.AUTO_START_RECORDING_DELAY_MS)
    }

    fun cancelPending() {
        handler.removeCallbacksAndMessages(V2CameraForegroundService.ACTION_AUTO_START_RECORDING)
    }
}
