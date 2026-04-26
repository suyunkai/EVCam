package com.kooo.evcam.v2.storage

import android.content.Context
import com.kooo.evcam.v2.log.V2AppLog
import com.kooo.evcam.v2.settings.V2StorageCleanupSettings
import java.io.File

object V2StorageCleaner {
    fun cleanupForReservedSpace(context: Context, outputDir: File): CleanupResult {
        outputDir.mkdirs()
        val reservedBytes = V2StorageCleanupSettings.reservedSpaceBytes(context)
        if (reservedBytes <= 0L) return CleanupResult(0, 0L, outputDir.usableSpace, reservedBytes)

        var available = outputDir.usableSpace
        if (available >= reservedBytes) return CleanupResult(0, 0L, available, reservedBytes)

        var deletedCount = 0
        var deletedBytes = 0L
        val videos = outputDir.listFiles { file -> file.isFile && file.extension.equals("mp4", ignoreCase = true) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            .orEmpty()

        for (video in videos) {
            if (available >= reservedBytes) break
            val before = video.length().coerceAtLeast(0L)
            val thumb = File(video.parentFile, video.nameWithoutExtension + ".jpg")
            if (video.delete()) {
                deletedCount += 1
                deletedBytes += before
                if (thumb.exists()) {
                    val thumbBytes = thumb.length().coerceAtLeast(0L)
                    if (thumb.delete()) deletedBytes += thumbBytes
                }
                available = outputDir.usableSpace
                V2AppLog.w("V2StorageCleaner", "deleted old segment ${video.name} freed=${formatBytes(before)} available=${formatBytes(available)} reserve=${formatBytes(reservedBytes)}")
            } else {
                V2AppLog.w("V2StorageCleaner", "delete failed ${video.absolutePath}")
            }
        }

        val result = CleanupResult(deletedCount, deletedBytes, available, reservedBytes)
        if (deletedCount > 0 || available < reservedBytes) {
            V2AppLog.w("V2StorageCleaner", "cleanup result deleted=$deletedCount freed=${formatBytes(deletedBytes)} available=${formatBytes(available)} reserve=${formatBytes(reservedBytes)}")
        }
        return result
    }

    fun formatBytes(bytes: Long): String {
        val gb = bytes / (1024.0 * 1024.0 * 1024.0)
        return String.format(java.util.Locale.US, "%.1fGB", gb)
    }

    data class CleanupResult(
        val deletedCount: Int,
        val deletedBytes: Long,
        val availableBytes: Long,
        val reservedBytes: Long
    )
}
