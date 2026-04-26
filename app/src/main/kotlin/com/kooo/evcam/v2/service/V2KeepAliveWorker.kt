package com.kooo.evcam.v2.service

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.kooo.evcam.v2.settings.V2KeepAliveSettings
import com.kooo.evcam.v2.settings.V2StartupSettings

class V2KeepAliveWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val context = applicationContext
        if (!V2KeepAliveSettings.isKeepAliveEnabled(context)) return Result.success()
        if (!V2StartupSettings.isAutoStartOnBoot(context)) return Result.success()
        if (!hasRequiredPermissions(context)) return Result.retry()

        val intent = Intent(context, V2CameraForegroundService::class.java)
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }

    private fun hasRequiredPermissions(context: Context): Boolean {
        val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val audioGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        return cameraGranted && audioGranted
    }
}
