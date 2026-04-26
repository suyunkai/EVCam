package com.kooo.evcam.v2.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.kooo.evcam.v2.log.V2BroadcastLogger
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2KeepAliveSettings

class V2KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        V2AppLog.init(context)
        V2BroadcastLogger.logReceive(TAG, intent)
        if (!V2KeepAliveSettings.isKeepAliveEnabled(context)) {
            V2AppLog.i(TAG, "skip broadcast: keep alive disabled action=$action")
            return
        }
        if (action == Intent.ACTION_TIME_TICK) {
            V2AppLog.d(TAG, "time tick keep alive")
            ensureServicesRunning(context, "time_tick", quiet = true, preferActivity = false)
            return
        }
        ensureServicesRunning(
            context,
            reasonFor(action),
            quiet = isQuietAction(action),
            preferActivity = shouldStartThroughActivity(action),
        )
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) registerTimeTick(context)
    }

    private fun ensureServicesRunning(
        context: Context,
        reason: String,
        quiet: Boolean = false,
        preferActivity: Boolean = false,
    ) {
        val now = System.currentTimeMillis()
        if (now - lastTriggerMs < MIN_TRIGGER_INTERVAL_MS) return
        lastTriggerMs = now
        if (!quiet) V2AppLog.i(TAG, "keep alive trigger reason=$reason preferActivity=$preferActivity")
        V2KeepAliveStarter.requestStart(context.applicationContext, reason, preferActivity = preferActivity)
    }

    companion object {
        const val ACTION_KEEP_ALIVE = "com.kooo.evcam.v2.action.KEEP_ALIVE"
        private const val TAG = "V2KeepAliveReceiver"
        private const val MIN_TRIGGER_INTERVAL_MS = 3_000L
        private val dynamicReceivers = mutableListOf<V2KeepAliveReceiver>()
        private var dynamicRegistered = false
        private var timeTickReceiver: V2KeepAliveReceiver? = null
        private var timeTickRegistered = false
        private var lastTriggerMs = 0L

        fun registerDynamic(context: Context) {
            if (dynamicRegistered) return
            runCatching {
                registerDynamicFilter(context, keepAliveFilter(includeTimeTick = true))
                registerDynamicFilter(context, mediaFilter())
                registerDynamicFilter(context, packageFilter())
                dynamicRegistered = true
                V2AppLog.i(TAG, "dynamic keep alive broadcasts registered")
            }.onFailure { error -> V2AppLog.e(TAG, "dynamic keep alive broadcasts register failed", error) }
        }

        fun unregisterDynamic(context: Context) {
            if (!dynamicRegistered) return
            dynamicReceivers.forEach { receiver ->
                runCatching { context.applicationContext.unregisterReceiver(receiver) }
                    .onFailure { error -> V2AppLog.e(TAG, "dynamic keep alive broadcasts unregister failed", error) }
            }
            dynamicReceivers.clear()
            dynamicRegistered = false
            V2AppLog.i(TAG, "dynamic keep alive broadcasts unregistered")
        }

        private fun registerDynamicFilter(context: Context, filter: IntentFilter) {
            val receiver = V2KeepAliveReceiver()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.applicationContext.registerReceiver(receiver, filter)
            }
            dynamicReceivers.add(receiver)
        }

        fun registerTimeTick(context: Context) {
            if (timeTickRegistered) return
            runCatching {
                val receiver = V2KeepAliveReceiver()
                val filter = IntentFilter(Intent.ACTION_TIME_TICK)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.applicationContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    context.applicationContext.registerReceiver(receiver, filter)
                }
                timeTickReceiver = receiver
                timeTickRegistered = true
                V2AppLog.i(TAG, "TIME_TICK registered")
            }.onFailure { error -> V2AppLog.e(TAG, "TIME_TICK register failed", error) }
        }

        fun unregisterTimeTick(context: Context) {
            val receiver = timeTickReceiver ?: return
            runCatching { context.applicationContext.unregisterReceiver(receiver) }
                .onFailure { error -> V2AppLog.e(TAG, "TIME_TICK unregister failed", error) }
            timeTickReceiver = null
            timeTickRegistered = false
        }

        fun sendKeepAliveCheck(context: Context) {
            runCatching {
                context.sendBroadcast(Intent(ACTION_KEEP_ALIVE).setPackage(context.packageName))
            }.onFailure { error -> V2AppLog.e(TAG, "send keep alive broadcast failed", error) }
        }

        private fun reasonFor(action: String): String = when (action) {
            Intent.ACTION_SCREEN_ON -> "screen_on"
            Intent.ACTION_SCREEN_OFF -> "screen_off"
            Intent.ACTION_USER_PRESENT -> "user_present"
            Intent.ACTION_POWER_CONNECTED -> "power_connected"
            Intent.ACTION_POWER_DISCONNECTED -> "power_disconnected"
            Intent.ACTION_BATTERY_LOW -> "battery_low"
            Intent.ACTION_BATTERY_OKAY -> "battery_okay"
            Intent.ACTION_MEDIA_MOUNTED -> "media_mounted"
            Intent.ACTION_MEDIA_UNMOUNTED -> "media_unmounted"
            Intent.ACTION_MEDIA_REMOVED -> "media_removed"
            Intent.ACTION_MEDIA_EJECT -> "media_eject"
            Intent.ACTION_TIMEZONE_CHANGED -> "timezone_changed"
            Intent.ACTION_TIME_CHANGED -> "time_changed"
            Intent.ACTION_DATE_CHANGED -> "date_changed"
            Intent.ACTION_LOCALE_CHANGED -> "locale_changed"
            Intent.ACTION_AIRPLANE_MODE_CHANGED -> "airplane_mode"
            Intent.ACTION_HEADSET_PLUG -> "headset_plug"
            Intent.ACTION_MY_PACKAGE_REPLACED -> "package_replaced"
            ACTION_KEEP_ALIVE -> "manual_keep_alive"
            else -> action.substringAfterLast('.')
        }

        private fun isQuietAction(action: String): Boolean = action == Intent.ACTION_BATTERY_CHANGED ||
            action == "android.net.wifi.SCAN_RESULTS" ||
            action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REPLACED

        private fun shouldStartThroughActivity(action: String): Boolean =
            action == Intent.ACTION_MY_PACKAGE_REPLACED

        private fun keepAliveFilter(includeTimeTick: Boolean): IntentFilter = IntentFilter().apply {
            if (includeTimeTick) addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(Intent.ACTION_HEADSET_PLUG)
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.adapter.action.CONNECTION_STATE_CHANGED")
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.hardware.usb.action.USB_DEVICE_ATTACHED")
            addAction("android.hardware.usb.action.USB_DEVICE_DETACHED")
            addAction("android.net.conn.CONNECTIVITY_CHANGE")
            addAction("android.net.wifi.STATE_CHANGE")
            addAction("android.net.wifi.SCAN_RESULTS")
            addAction("android.media.AUDIO_BECOMING_NOISY")
            addAction(Intent.ACTION_MY_PACKAGE_REPLACED)
            addAction(ACTION_KEEP_ALIVE)
        }

        private fun mediaFilter(): IntentFilter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addDataScheme("file")
        }

        private fun packageFilter(): IntentFilter = IntentFilter().apply {
            addDataScheme("package")
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
        }
    }
}
