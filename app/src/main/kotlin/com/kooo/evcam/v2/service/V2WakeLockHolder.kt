package com.kooo.evcam.v2.service

import android.content.Context
import android.os.PowerManager
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2KeepAliveSettings
import com.kooo.evcam.v2.settings.V2StartupSettings

internal class V2WakeLockHolder(private val context: Context) {
    private var wakeLock: PowerManager.WakeLock? = null

    fun acquire() {
        if (!V2StartupSettings.isAutoStartOnBoot(context)) {
            release()
            V2AppLog.i("V2CameraService", "wake lock skipped: auto start disabled")
            return
        }
        if (!V2KeepAliveSettings.isPreventSleepEnabled(context)) {
            release()
            V2AppLog.i("V2CameraService", "wake lock skipped: prevent sleep disabled")
            return
        }
        if (wakeLock?.isHeld == true) return
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EVCamV2:CameraService").apply {
            setReferenceCounted(false)
            acquire()
        }
        V2AppLog.i("V2CameraService", "wake lock acquired")
    }

    fun release() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        V2AppLog.i("V2CameraService", "wake lock released")
    }
}
