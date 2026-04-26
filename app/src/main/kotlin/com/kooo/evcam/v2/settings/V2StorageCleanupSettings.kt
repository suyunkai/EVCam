package com.kooo.evcam.v2.settings

import android.content.Context
import android.os.Environment
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.storage.V2StorageCleaner

object V2StorageCleanupSettings {
    private const val PREFS = "evcam_v2_storage_cleanup_settings"
    private const val KEY_RESERVED_SPACE_GB = "reserved_space_gb"

    const val DEFAULT_RESERVED_SPACE_GB = 3
    private const val GB_BYTES = 1024L * 1024L * 1024L

    fun reservedSpaceGb(context: Context): Int = prefs(context).getInt(KEY_RESERVED_SPACE_GB, DEFAULT_RESERVED_SPACE_GB)

    fun reservedSpaceBytes(context: Context): Long = reservedSpaceGb(context).coerceAtLeast(0) * GB_BYTES

    fun setReservedSpaceGb(context: Context, value: Int) {
        val next = value.coerceIn(0, 1024)
        prefs(context).edit().putInt(KEY_RESERVED_SPACE_GB, next).apply()
        V2AppLog.i("V2StorageCleanupSettings", "reservedSpaceGb=$next")
    }

    fun summary(context: Context): String {
        val gb = reservedSpaceGb(context)
        val cleanup = if (gb <= 0) "关闭低空间滚动覆盖" else "可用空间低于 ${gb}GB 时，自动删除最旧录像继续录制"
        return "$cleanup\n${systemStorageSummary()}"
    }

    private fun systemStorageSummary(): String {
        val dataDir = Environment.getDataDirectory()
        return "系统可用/总空间：${V2StorageCleaner.formatBytes(dataDir.usableSpace)} / ${V2StorageCleaner.formatBytes(dataDir.totalSpace)}"
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
