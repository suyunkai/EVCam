package com.kooo.evcam.v2.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.kooo.evcam.R

internal class V2CameraNotificationHelper(private val service: V2CameraForegroundService) {
    fun startForeground(text: String) {
        service.startForeground(NOTIFICATION_ID, build(text))
    }

    fun update(text: String) {
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.notify(NOTIFICATION_ID, build(text))
    }

    private fun build(text: String): Notification {
        ensureChannel()
        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("EVCam V2")
            .setContentText(text)
            .setOngoing(true)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val notificationManager = service.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "V2 Camera", NotificationManager.IMPORTANCE_LOW)
        )
    }

    private companion object {
        private const val CHANNEL_ID = "v2_camera"
        private const val NOTIFICATION_ID = 2001
    }
}
