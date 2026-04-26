package com.kooo.evcam.v2.service

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.kooo.evcam.R
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2KeepAliveSettings
import com.kooo.evcam.v2.ui.V2MainActivity

class V2KeepAliveAccessibilityService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private var startMs = 0L
    private val heartbeat = object : Runnable {
        override fun run() {
            if (!V2KeepAliveSettings.isKeepAliveEnabled(this@V2KeepAliveAccessibilityService)) {
                V2AppLog.i(TAG, "heartbeat skipped: keep alive disabled")
                handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
                return
            }
            val minutes = ((System.currentTimeMillis() - startMs) / 60_000L).coerceAtLeast(0L)
            V2AppLog.d(TAG, "heartbeat runningMinutes=$minutes")
            V2KeepAliveStatus.recordTrigger(this@V2KeepAliveAccessibilityService, "accessibility", "heartbeat")
            V2KeepAliveStarter.requestStart(this@V2KeepAliveAccessibilityService, "accessibility_heartbeat", preferActivity = false)
            handler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        V2AppLog.init(this)
        instance = this
        running = true
        startMs = System.currentTimeMillis()
        V2AppLog.i(TAG, "onCreate")
        V2KeepAliveStatus.recordTrigger(this, "accessibility", "on_create")
        startForegroundNotification()
        V2KeepAliveReceiver.registerTimeTick(this)
        handler.postDelayed(heartbeat, INITIAL_HEARTBEAT_DELAY_MS)
        V2KeepAliveStarter.requestStart(this, "accessibility_started", preferActivity = false)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            V2AppLog.d(TAG, "window event package=${event.packageName} class=${event.className}")
        }
    }

    override fun onInterrupt() {
        V2AppLog.w(TAG, "onInterrupt")
        V2KeepAliveStatus.recordTrigger(this, "accessibility", "interrupt")
    }

    override fun onDestroy() {
        V2AppLog.w(TAG, "onDestroy")
        handler.removeCallbacksAndMessages(null)
        V2KeepAliveReceiver.unregisterTimeTick(this)
        running = false
        instance = null
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        V2KeepAliveStatus.recordTrigger(this, "accessibility", "start_command")
        V2KeepAliveStarter.requestStart(this, "accessibility_start_command", preferActivity = false)
        return START_STICKY
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        V2AppLog.i(TAG, "onServiceConnected")
        V2KeepAliveStatus.recordTrigger(this, "accessibility", "connected")
        V2KeepAliveStarter.requestStart(this, "accessibility_connected", preferActivity = false)
    }

    private fun startForegroundNotification() {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "V2 KeepAlive", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                })
            }
            val intent = Intent(this, V2MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("EVCam V2 保活")
                .setContentText("无障碍保活服务运行中")
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(pendingIntent)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }.onFailure { V2AppLog.w(TAG, "start foreground notification failed", it) }
    }

    companion object {
        private const val TAG = "V2KeepAliveAccessibility"
        private const val CHANNEL_ID = "v2_keep_alive_accessibility"
        private const val NOTIFICATION_ID = 2002
        private const val INITIAL_HEARTBEAT_DELAY_MS = 5_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        @Volatile private var running = false
        @Volatile private var instance: V2KeepAliveAccessibilityService? = null
        fun isRunning(): Boolean = running
        fun runningMinutes(): Long = instance?.let { (System.currentTimeMillis() - it.startMs) / 60_000L } ?: 0L
    }
}
