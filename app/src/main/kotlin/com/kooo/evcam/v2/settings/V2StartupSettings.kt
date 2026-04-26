package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2StartupSettings {
    private const val PREFS_NAME = "evcam_v2_startup_settings"
    private const val KEY_AUTO_START_ON_BOOT = "auto_start_on_boot"
    private const val KEY_AUTO_START_RECORDING = "auto_start_recording"

    fun isAutoStartOnBoot(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START_ON_BOOT, true)

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply()
        V2AppLog.i("V2StartupSettings", "autoStartOnBoot=$enabled")
    }

    fun isAutoStartRecording(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_START_RECORDING, false)

    fun setAutoStartRecording(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_START_RECORDING, enabled).apply()
        V2AppLog.i("V2StartupSettings", "autoStartRecording=$enabled")
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
