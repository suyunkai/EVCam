package com.kooo.evcam.v2.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.kooo.evcam.v2.log.V2BroadcastLogger
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2StartupSettings

class V2DisplayPowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (!V2DisplayPowerActions.isDisplayOff(action) && !V2DisplayPowerActions.isDisplayOn(action)) return

        V2AppLog.init(context)
        V2BroadcastLogger.logReceive(TAG, intent)
        V2DisplayPowerState.updateFromAction(action)
        V2AppLog.i(TAG, "display power broadcast received: action=$action")
        if (!V2StartupSettings.isAutoStartOnBoot(context)) {
            V2AppLog.i(TAG, "display cold start skipped: auto start disabled")
            return
        }
        startCameraService(context, action)
    }

    private fun startCameraService(context: Context, action: String) {
        val serviceIntent = Intent(context, V2CameraForegroundService::class.java).setAction(action)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            V2AppLog.i(TAG, "display foreground service start requested action=$action")
        }.onFailure { error ->
            V2AppLog.e(TAG, "start display foreground service failed", error)
        }
    }

    private companion object {
        private const val TAG = "V2DisplayPowerReceiver"
    }
}
