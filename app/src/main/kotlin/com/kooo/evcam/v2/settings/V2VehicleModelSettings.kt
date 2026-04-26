package com.kooo.evcam.v2.settings

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog

object V2VehicleModelSettings {
    const val MODEL_E5_2025 = "galaxy_e5_2025"
    const val MODEL_XINGHAN_7_2026 = "xinghan_7_2026"
    const val MODEL_A7_2025 = "galaxy_a7_2025"

    private const val PREFS_NAME = "evcam_v2_vehicle_settings"
    private const val KEY_VEHICLE_MODEL = "vehicle_model"

    data class CameraMapping(val front: String, val back: String, val left: String, val right: String)
    data class VehicleModel(val id: String, val label: String, val mapping: CameraMapping)

    val models = listOf(
        VehicleModel(MODEL_E5_2025, "25款E5", CameraMapping(front = "2", back = "1", left = "3", right = "0")),
        VehicleModel(MODEL_XINGHAN_7_2026, "26款星舰7", CameraMapping(front = "3", back = "2", left = "4", right = "1")),
        VehicleModel(MODEL_A7_2025, "25款A7", CameraMapping(front = "2", back = "1", left = "3", right = "0"))
    )

    fun getModelId(context: Context): String = prefs(context).getString(KEY_VEHICLE_MODEL, MODEL_E5_2025) ?: MODEL_E5_2025

    fun setModelId(context: Context, modelId: String) {
        val model = models.firstOrNull { it.id == modelId }
        prefs(context).edit().putString(KEY_VEHICLE_MODEL, modelId).apply()
        V2AppLog.i("V2VehicleModelSettings", "vehicleModel=$modelId label=${model?.label ?: "unknown"} mapping=${model?.mapping}")
    }

    fun getModel(context: Context): VehicleModel {
        val modelId = getModelId(context)
        val model = models.firstOrNull { it.id == modelId }
        if (model == null) V2AppLog.w("V2VehicleModelSettings", "unknown vehicle model=$modelId, fallback=${models.first().id}")
        return model ?: models.first()
    }

    fun mappingSummary(context: Context): String {
        val model = getModel(context)
        val mapping = model.mapping
        return "${model.label}\n前:${mapping.front} 后:${mapping.back} 左:${mapping.left} 右:${mapping.right}"
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
