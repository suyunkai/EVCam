package com.kooo.evcam.v2.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.log.V2BroadcastLogger

internal class V2DisplayPowerCoordinator(
    private val service: V2CameraForegroundService,
    private val onDisplayOff: (String?) -> Unit,
    private val onDisplayOn: (String?) -> Unit,
) {
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            V2BroadcastLogger.logReceive("V2CameraService", intent)
            val action = intent?.action
            when {
                V2DisplayPowerActions.isDisplayOff(action) -> onDisplayOff(action)
                V2DisplayPowerActions.isDisplayOn(action) -> onDisplayOn(action)
            }
        }
    }

    fun register() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(V2DisplayPowerActions.ECARX_DISPLAY_OFF)
            addAction(V2DisplayPowerActions.ECARX_DISPLAY_ON)
        }
        ContextCompat.registerReceiver(service, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
        V2AppLog.i("V2CameraService", "display power receiver registered")
    }

    fun unregister() {
        if (!registered) return
        runCatching { service.unregisterReceiver(receiver) }
        registered = false
        V2AppLog.i("V2CameraService", "display power receiver unregistered")
    }
}
