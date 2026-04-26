package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2KeepAliveSettings {
    private const val PREFS_NAME = "evcam_v2_keep_alive_settings"
    private const val KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled"
    private const val KEY_PREVENT_SLEEP_ENABLED = "prevent_sleep_enabled"

    fun isKeepAliveEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_KEEP_ALIVE_ENABLED, true)

    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_KEEP_ALIVE_ENABLED, enabled).apply()
        V2AppLog.i("V2KeepAliveSettings", "keepAliveEnabled=$enabled")
    }

    fun isPreventSleepEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_PREVENT_SLEEP_ENABLED, true)

    fun setPreventSleepEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_PREVENT_SLEEP_ENABLED, enabled).apply()
        V2AppLog.i("V2KeepAliveSettings", "preventSleepEnabled=$enabled")
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
