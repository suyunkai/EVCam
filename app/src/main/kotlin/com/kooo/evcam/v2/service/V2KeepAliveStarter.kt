package com.kooo.evcam.v2.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2StartupSettings
import com.kooo.evcam.v2.ui.V2TransparentBootActivity

internal object V2KeepAliveStarter {
    fun requestStart(context: Context, reason: String, preferActivity: Boolean = true) {
        if (!V2StartupSettings.isAutoStartOnBoot(context)) {
            V2AppLog.i(TAG, "skip keep alive start: auto start disabled reason=$reason")
            return
        }
        if (preferActivity && Settings.canDrawOverlays(context)) {
            startTransparentActivity(context, reason)
        } else {
            startForegroundService(context, reason)
        }
    }

    private fun startTransparentActivity(context: Context, reason: String) {
        runCatching {
            val intent = Intent(context, V2TransparentBootActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("keep_alive_reason", reason)
            }
            context.startActivity(intent)
            V2AppLog.i(TAG, "transparent activity start requested reason=$reason")
        }.onFailure { error ->
            V2AppLog.e(TAG, "transparent activity start failed, fallback service reason=$reason", error)
            startForegroundService(context, reason)
        }
    }

    private fun startForegroundService(context: Context, reason: String) {
        runCatching {
            val intent = Intent(context, V2CameraForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            V2AppLog.i(TAG, "foreground service start requested reason=$reason")
        }.onFailure { error ->
            V2AppLog.e(TAG, "foreground service start failed reason=$reason", error)
        }
    }

    private const val TAG = "V2KeepAliveStarter"
}
