package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2CustomKeySettings {
    private const val PREFS = "v2_custom_key_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BUTTON_PROP_ID = "button_prop_id"

    const val DEFAULT_BUTTON_PROP_ID = 557872183

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
        V2AppLog.i("V2CustomKeySettings", "enabled=$enabled")
    }

    fun buttonPropId(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_BUTTON_PROP_ID, DEFAULT_BUTTON_PROP_ID)
    }

    fun setButtonPropId(context: Context, propId: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_BUTTON_PROP_ID, propId)
            .apply()
        V2AppLog.i("V2CustomKeySettings", "buttonPropId=$propId")
    }
}
