package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2KeepAliveSettings {
    fun isKeepAliveEnabled(context: Context): Boolean = true

    fun setKeepAliveEnabled(context: Context, enabled: Boolean) {
        V2AppLog.i("V2KeepAliveSettings", "keepAliveEnabled is fixed true, ignored requested=$enabled")
    }

    fun isPreventSleepEnabled(context: Context): Boolean = true

    fun setPreventSleepEnabled(context: Context, enabled: Boolean) {
        V2AppLog.i("V2KeepAliveSettings", "preventSleepEnabled is fixed true, ignored requested=$enabled")
    }
}
