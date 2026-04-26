package com.kooo.evcam.v2.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2KeepAliveSettings

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
    }

    override fun onDestroy() {
        V2AppLog.w(TAG, "onDestroy")
        handler.removeCallbacksAndMessages(null)
        V2KeepAliveReceiver.unregisterTimeTick(this)
        running = false
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "V2KeepAliveAccessibility"
        private const val INITIAL_HEARTBEAT_DELAY_MS = 5_000L
        private const val HEARTBEAT_INTERVAL_MS = 60_000L
        @Volatile private var running = false
        @Volatile private var instance: V2KeepAliveAccessibilityService? = null
        fun isRunning(): Boolean = running
        fun runningMinutes(): Long = instance?.let { (System.currentTimeMillis() - it.startMs) / 60_000L } ?: 0L
    }
}
