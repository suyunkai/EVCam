package com.kooo.evcam.v2.ui

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.service.V2CameraForegroundService
import com.kooo.evcam.v2.settings.V2StartupSettings

class V2TransparentBootActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        V2AppLog.init(this)
        V2AppLog.i(TAG, "onCreate autoRecord=${V2StartupSettings.isAutoStartRecording(this)}")

        startCameraServiceFromForegroundActivity()
        if (V2StartupSettings.isAutoStartRecording(this)) {
            launchMainForAutoRecording()
            finishQuietly()
        } else {
            handler.postDelayed({ finishQuietly() }, FINISH_DELAY_MS)
        }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        V2AppLog.i(TAG, "onDestroy")
        super.onDestroy()
    }

    private fun startCameraServiceFromForegroundActivity() {
        runCatching {
            val intent = Intent(this, V2CameraForegroundService::class.java)
            ContextCompat.startForegroundService(this, intent)
            V2AppLog.i(TAG, "foreground service start requested from transparent activity")
        }.onFailure { error ->
            V2AppLog.e(TAG, "start foreground service failed", error)
        }
    }

    private fun launchMainForAutoRecording() {
        runCatching {
            val intent = Intent(this, V2MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(V2MainActivity.EXTRA_AUTO_START_FROM_BOOT, true)
                putExtra(V2MainActivity.EXTRA_SILENT_MODE, true)
            }
            startActivity(intent)
            V2AppLog.i(TAG, "main activity start requested for auto recording")
        }.onFailure { error ->
            V2AppLog.e(TAG, "start main activity failed", error)
        }
    }

    private fun finishQuietly() {
        finish()
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val TAG = "V2TransparentBootActivity"
        private const val FINISH_DELAY_MS = 1_500L
    }
}
