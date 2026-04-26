package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2FisheyeSettings {
    private const val PREFS = "evcam_v2_fisheye_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_K1 = "k1_"
    private const val KEY_K2 = "k2_"
    private const val KEY_ZOOM = "zoom_"
    private const val KEY_CENTER_X = "center_x_"
    private const val KEY_CENTER_Y = "center_y_"

    data class Params(
        val label: String,
        val k1: Float,
        val k2: Float,
        val zoom: Float,
        val centerX: Float = DEFAULT_CENTER_X,
        val centerY: Float = DEFAULT_CENTER_Y
    )

    const val DEFAULT_K1 = 0.82f
    const val DEFAULT_K2 = 0.22f
    const val DEFAULT_ZOOM = 1.42f
    const val DEFAULT_CENTER_X = 0.5f
    const val DEFAULT_CENTER_Y = 0.5f

    private val DEFAULT_PARAMS = listOf(
        Params(label = "前", k1 = 0.82f, k2 = 0.22f, zoom = 1.42f),
        Params(label = "后", k1 = 0.78f, k2 = 0.20f, zoom = 1.38f),
        Params(label = "左", k1 = 0.64f, k2 = 0.16f, zoom = 1.30f),
        Params(label = "右", k1 = 0.66f, k2 = 0.17f, zoom = 1.32f)
    )

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        V2AppLog.i("V2FisheyeSettings", "enabled=$enabled params=${paramsSummary()}")
    }

    fun paramsForIndex(context: Context, index: Int): Params {
        val defaults = defaultParamsForIndex(index)
        val prefs = prefs(context)
        return defaults.copy(
            k1 = prefs.getFloat(KEY_K1 + index, defaults.k1),
            k2 = prefs.getFloat(KEY_K2 + index, defaults.k2),
            zoom = prefs.getFloat(KEY_ZOOM + index, defaults.zoom),
            centerX = prefs.getFloat(KEY_CENTER_X + index, defaults.centerX),
            centerY = prefs.getFloat(KEY_CENTER_Y + index, defaults.centerY)
        )
    }

    fun defaultParamsForIndex(index: Int): Params = DEFAULT_PARAMS.getOrElse(index) { DEFAULT_PARAMS.first() }

    fun setParams(context: Context, index: Int, k1: Float, k2: Float, zoom: Float, centerX: Float = DEFAULT_CENTER_X, centerY: Float = DEFAULT_CENTER_Y) {
        val label = defaultParamsForIndex(index).label
        prefs(context).edit()
            .putFloat(KEY_K1 + index, k1)
            .putFloat(KEY_K2 + index, k2)
            .putFloat(KEY_ZOOM + index, zoom.coerceAtLeast(0.1f))
            .putFloat(KEY_CENTER_X + index, centerX)
            .putFloat(KEY_CENTER_Y + index, centerY)
            .apply()
        V2AppLog.i("V2FisheyeSettings", "params index=$index label=$label k1=$k1 k2=$k2 zoom=$zoom center=$centerX,$centerY")
    }

    fun resetAllParams(context: Context) {
        val editor = prefs(context).edit()
        DEFAULT_PARAMS.indices.forEach { index ->
            editor
                .remove(KEY_K1 + index)
                .remove(KEY_K2 + index)
                .remove(KEY_ZOOM + index)
                .remove(KEY_CENTER_X + index)
                .remove(KEY_CENTER_Y + index)
        }
        editor.apply()
        V2AppLog.i("V2FisheyeSettings", "reset all params defaults=${paramsSummary()}")
    }

    fun paramsSummary(context: Context? = null): String = DEFAULT_PARAMS.indices.joinToString("；") { index ->
        val params = if (context == null) defaultParamsForIndex(index) else paramsForIndex(context, index)
        "${params.label}:k1=${params.k1},k2=${params.k2},zoom=${params.zoom}"
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
