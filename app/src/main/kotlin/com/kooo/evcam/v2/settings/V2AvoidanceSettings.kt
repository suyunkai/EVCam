package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2AvoidanceSettings {
    const val BEHAVIOR_EXIT_FOREGROUND = 1 shl 0
    const val BEHAVIOR_STOP_RECORDING = 1 shl 1
    const val BEHAVIOR_STOP_PREVIEW = 1 shl 2

    private const val PREFS_NAME = "evcam_v2_avoidance_settings"
    private const val KEY_BEHAVIOR_MASK = "behavior_mask"

    val defaultTargets = listOf(
        AvoidanceTarget("com.geely.parking", "泊车/APA 包名"),
        AvoidanceTarget("com.geely.parking.parking.ParkingActivity", "实际泊车界面"),
        AvoidanceTarget("com.geely.parking.BlankActivity", "泊车启动入口"),
        AvoidanceTarget("com.geely.parking.BlankHpaActivity", "记忆泊车入口"),
        AvoidanceTarget("com.geely.avm_app", "全景/AVM 包名"),
        AvoidanceTarget("com.geely.avm_app.MainActivity", "全景主界面"),
        AvoidanceTarget("com.geely.avm_app.AvmWindowActivity", "全景小窗界面")
    )

    data class AvoidanceTarget(val value: String, val label: String)

    fun behaviorMask(context: Context): Int = prefs(context).getInt(KEY_BEHAVIOR_MASK, 0)

    fun setBehaviorEnabled(context: Context, behavior: Int, enabled: Boolean) {
        val current = behaviorMask(context)
        val next = if (enabled) current or behavior else current and behavior.inv()
        prefs(context).edit().putInt(KEY_BEHAVIOR_MASK, next).apply()
        V2AppLog.i("V2AvoidanceSettings", "behaviorMask=$next labels=${behaviorLabels(next)}")
    }

    fun isBehaviorEnabled(context: Context, behavior: Int): Boolean = behaviorMask(context) and behavior != 0

    fun isEnabled(context: Context): Boolean = behaviorMask(context) != 0

    fun targetValues(): List<String> = defaultTargets.map { it.value }

    fun behaviorLabels(mask: Int): String = buildList {
        if (mask and BEHAVIOR_EXIT_FOREGROUND != 0) add("退出前台")
        if (mask and BEHAVIOR_STOP_RECORDING != 0) add("停止录制")
        if (mask and BEHAVIOR_STOP_PREVIEW != 0) add("停止预览")
    }.ifEmpty { listOf("不避让") }.joinToString("/")

    fun targetsSummary(): String = defaultTargets.joinToString("\n") { "${it.label}: ${it.value}" }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
