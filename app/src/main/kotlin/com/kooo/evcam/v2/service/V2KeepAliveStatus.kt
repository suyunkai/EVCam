package com.kooo.evcam.v2.service

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object V2KeepAliveStatus {
    private const val PREFS = "evcam_v2_keep_alive_status"
    private const val KEY_LAST_REASON = "last_reason"
    private const val KEY_LAST_SOURCE = "last_source"
    private const val KEY_LAST_TRIGGER_MS = "last_trigger_ms"
    private const val KEY_PROVIDER_MS = "provider_ms"
    private const val KEY_WORKER_MS = "worker_ms"
    private const val KEY_SERVICE_CREATE_MS = "service_create_ms"
    private const val KEY_SERVICE_DESTROY_MS = "service_destroy_ms"
    private const val KEY_TIME_TICK_REGISTERED = "time_tick_registered"
    private const val KEY_DYNAMIC_REGISTERED = "dynamic_registered"

    fun recordTrigger(context: Context, source: String, reason: String) {
        prefs(context).edit()
            .putString(KEY_LAST_SOURCE, source)
            .putString(KEY_LAST_REASON, reason)
            .putLong(KEY_LAST_TRIGGER_MS, System.currentTimeMillis())
            .apply()
    }

    fun recordProvider(context: Context) = prefs(context).edit().putLong(KEY_PROVIDER_MS, System.currentTimeMillis()).apply()
    fun recordWorker(context: Context) = prefs(context).edit().putLong(KEY_WORKER_MS, System.currentTimeMillis()).apply()
    fun recordServiceCreated(context: Context) = prefs(context).edit().putLong(KEY_SERVICE_CREATE_MS, System.currentTimeMillis()).apply()
    fun recordServiceDestroyed(context: Context) = prefs(context).edit().putLong(KEY_SERVICE_DESTROY_MS, System.currentTimeMillis()).apply()
    fun setTimeTickRegistered(context: Context, registered: Boolean) = prefs(context).edit().putBoolean(KEY_TIME_TICK_REGISTERED, registered).apply()
    fun setDynamicRegistered(context: Context, registered: Boolean) = prefs(context).edit().putBoolean(KEY_DYNAMIC_REGISTERED, registered).apply()

    fun summary(context: Context): String {
        val p = prefs(context)
        return "最后触发：${p.getString(KEY_LAST_SOURCE, "无")}/${p.getString(KEY_LAST_REASON, "无")} ${fmt(p.getLong(KEY_LAST_TRIGGER_MS, 0L))}\n" +
            "Provider：${fmt(p.getLong(KEY_PROVIDER_MS, 0L))}；Worker：${fmt(p.getLong(KEY_WORKER_MS, 0L))}\n" +
            "TIME_TICK：${if (p.getBoolean(KEY_TIME_TICK_REGISTERED, false)) "已注册" else "未注册"}；动态广播：${if (p.getBoolean(KEY_DYNAMIC_REGISTERED, false)) "已注册" else "未注册"}\n" +
            "无障碍：${if (V2KeepAliveAccessibilityService.isRunning()) "运行 ${V2KeepAliveAccessibilityService.runningMinutes()} 分钟" else "未运行"}\n" +
            "服务创建：${fmt(p.getLong(KEY_SERVICE_CREATE_MS, 0L))}；销毁：${fmt(p.getLong(KEY_SERVICE_DESTROY_MS, 0L))}"
    }

    private fun fmt(ms: Long): String = if (ms <= 0L) "无" else SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(ms))
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
