package com.kooo.evcam.v2.ui.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class V2VideoGroup(
    val timestamp: String,
    val composite: File?,
    val thumbnail: Bitmap? = null
) {
    val files: List<File> = listOfNotNull(composite)
    val count: Int = files.size
    val totalBytes: Long = files.sumOf { it.length() }
    private val parsedDate: Date = V2VideoScanner.parseTimestamp(timestamp) ?: Date(files.maxOfOrNull { it.lastModified() } ?: 0L)
    val displayYear: String = runCatching {
        SimpleDateFormat("yyyy", Locale.getDefault()).format(parsedDate)
    }.getOrDefault("")
    val displayDate: String = runCatching {
        SimpleDateFormat("MM-dd", Locale.getDefault()).format(parsedDate)
    }.getOrDefault("")
    val displayTime: String = runCatching {
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(parsedDate)
    }.getOrDefault(timestamp)
}

object V2VideoScanner {
    private val fileNamePattern = Regex("^(\\d{8}_\\d{4})_(composite(?:_\\d{3})?)\\.mp4$", RegexOption.IGNORE_CASE)

    fun parseTimestamp(timestamp: String): Date? {
        val pattern = if (timestamp.length == 13) "yyyyMMdd_HHmm" else "yyyyMMdd_HHmmss"
        return runCatching { SimpleDateFormat(pattern, Locale.US).parse(timestamp) }.getOrNull()
    }

    fun scanGroups(context: Context, loadThumbnails: Boolean = false): List<V2VideoGroup> {
        val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "EVCamV2")
        val files = dir.listFiles { f ->
            f.isFile && f.exists() && f.canRead() && f.length() > 0L &&
                f.extension.equals("mp4", ignoreCase = true) &&
                !f.name.endsWith(".recording", ignoreCase = true)
        }.orEmpty()

        val grouped = linkedMapOf<String, MutableMap<String, File>>()
        files.forEach { file ->
            val match = fileNamePattern.matchEntire(file.name) ?: return@forEach
            val timestamp = match.groupValues[1]
            val position = match.groupValues[2].lowercase(Locale.US)
            grouped.getOrPut(timestamp) { mutableMapOf() }[position] = file
        }

        return grouped.map { (timestamp, map) ->
            val composite = map.entries.firstOrNull { it.key.startsWith("composite") }?.value
            V2VideoGroup(
                timestamp = timestamp,
                composite = composite,
                thumbnail = if (loadThumbnails) composite?.let { cachedThumbnail(it) } else null
            )
        }.filter { it.count > 0 }.sortedByDescending { it.timestamp }
    }

    fun scanGroupsIncremental(
        context: Context,
        isCancelled: () -> Boolean = { false },
        onGroup: (V2VideoGroup) -> Unit
    ): Int {
        val dir = File(context.getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES), "EVCamV2")
        val files = dir.listFiles { f ->
            f.isFile && f.exists() && f.canRead() && f.length() > 0L &&
                f.extension.equals("mp4", ignoreCase = true) &&
                !f.name.endsWith(".recording", ignoreCase = true)
        }.orEmpty()

        val orderedFiles = files.mapNotNull { file ->
            val match = fileNamePattern.matchEntire(file.name) ?: return@mapNotNull null
            Triple(match.groupValues[1], match.groupValues[2].lowercase(Locale.US), file)
        }.sortedWith(compareByDescending<Triple<String, String, File>> { it.first }.thenBy { it.second })

        val emitted = HashSet<String>()
        var count = 0
        var lastYieldMs = SystemClock.uptimeMillis()
        orderedFiles.forEach { (timestamp, _, file) ->
            if (isCancelled()) return count
            if (!emitted.add(timestamp)) return@forEach
            onGroup(V2VideoGroup(timestamp = timestamp, composite = file))
            count += 1
            val now = SystemClock.uptimeMillis()
            if (now - lastYieldMs >= 8L) {
                Thread.yield()
                lastYieldMs = now
            }
        }
        return count
    }

    fun cachedThumbnail(file: File): Bitmap? {
        val thumb = File(file.parentFile, file.nameWithoutExtension + ".jpg")
        if (!thumb.isFile || !thumb.canRead() || thumb.length() <= 0L) return null
        return runCatching {
            BitmapFactory.Options().run {
                inJustDecodeBounds = true
                BitmapFactory.decodeFile(thumb.absolutePath, this)
                inSampleSize = thumbnailSampleSize(outWidth, outHeight, 240, 160)
                inJustDecodeBounds = false
                BitmapFactory.decodeFile(thumb.absolutePath, this)
            }
        }.getOrNull()
    }

    private fun thumbnailSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sample = 1
        if (height > reqHeight || width > reqWidth) {
            var halfHeight = height / 2
            var halfWidth = width / 2
            while (halfHeight / sample >= reqHeight && halfWidth / sample >= reqWidth) {
                sample *= 2
            }
        }
        return sample.coerceAtLeast(1)
    }
}
