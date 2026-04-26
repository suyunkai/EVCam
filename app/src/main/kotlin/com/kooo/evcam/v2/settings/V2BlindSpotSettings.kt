package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2BlindSpotSettings {
    private const val PREFS = "evcam_v2_blind_spot_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_TURN_SIGNAL_PROP_ID = "turn_signal_prop_id"

    const val DEFAULT_TURN_SIGNAL_PROP_ID = 289408008
    const val LEFT_VALUE = 1
    const val RIGHT_VALUE = 2
    const val OFF_VALUE = 0
    const val HIDE_DELAY_MS = 1_000L

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        V2AppLog.i("V2BlindSpotSettings", "enabled=$enabled")
    }

    fun turnSignalPropId(context: Context): Int = prefs(context).getInt(KEY_TURN_SIGNAL_PROP_ID, DEFAULT_TURN_SIGNAL_PROP_ID)

    fun setTurnSignalPropId(context: Context, propId: Int) {
        prefs(context).edit().putInt(KEY_TURN_SIGNAL_PROP_ID, propId).apply()
        V2AppLog.i("V2BlindSpotSettings", "turnSignalPropId=$propId")
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
