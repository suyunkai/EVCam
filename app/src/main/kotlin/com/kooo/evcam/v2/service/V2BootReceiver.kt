package com.kooo.evcam.v2.service

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.kooo.evcam.v2.log.V2BroadcastLogger
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2StartupSettings
import com.kooo.evcam.v2.ui.V2TransparentBootActivity

class V2BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        V2AppLog.init(context)
        V2BroadcastLogger.logReceive(TAG, intent)
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != QUICKBOOT_POWERON && action != HTC_QUICKBOOT_POWERON) return
        V2AppLog.i(TAG, "boot broadcast received: action=$action autoStart=${V2StartupSettings.isAutoStartOnBoot(context)} autoRecord=${V2StartupSettings.isAutoStartRecording(context)} permissions=${permissionSummary(context)}")

        if (!V2StartupSettings.isAutoStartOnBoot(context)) {
            V2AppLog.d(TAG, "skip boot start: disabled in settings")
            return
        }

        if (!hasRequiredPermissions(context)) {
            V2AppLog.w(TAG, "skip boot start: required permissions missing ${permissionSummary(context)}")
            return
        }

        startTransparentBootActivity(context, action)
    }

    private fun startTransparentBootActivity(context: Context, action: String) {
        runCatching {
            val activityIntent = Intent(context, V2TransparentBootActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra("boot_action", action)
            }
            context.startActivity(activityIntent)
            V2AppLog.i(TAG, "transparent boot activity start requested action=$action")
        }.onFailure { error ->
            V2AppLog.e(TAG, "start transparent boot activity failed, fallback to foreground service", error)
            val serviceIntent = Intent(context, V2CameraForegroundService::class.java)
            startCameraService(context, serviceIntent, "boot fallback")
        }
    }

    private fun startCameraService(context: Context, serviceIntent: Intent, reason: String) {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            V2AppLog.i(TAG, "$reason foreground service start requested action=${serviceIntent.action}")
        }.onFailure { error ->
            V2AppLog.e(TAG, "start $reason foreground service failed", error)
        }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        return isGranted(context, Manifest.permission.CAMERA) &&
            isGranted(context, Manifest.permission.RECORD_AUDIO)
    }

    private fun permissionSummary(context: Context): String {
        val notificationGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        return "camera=${isGranted(context, Manifest.permission.CAMERA)} audio=${isGranted(context, Manifest.permission.RECORD_AUDIO)} notification=$notificationGranted"
    }

    private fun isGranted(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    }

    private companion object {
        private const val TAG = "V2BootReceiver"
        private const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"
        private const val HTC_QUICKBOOT_POWERON = "com.htc.intent.action.QUICKBOOT_POWERON"
    }
}
