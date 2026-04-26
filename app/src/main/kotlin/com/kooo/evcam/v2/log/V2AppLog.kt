package com.kooo.evcam.v2.log

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object V2AppLog {
    private const val MAX_BUFFER_LINES = 5000
    private const val CURRENT_SESSION_LOG = "current_session.log"
    private const val PREVIOUS_SESSION_LOG = "previous_session.log"
    private val lock = Any()
    private val buffer = ArrayList<String>()
    @Volatile private var appContext: Context? = null
    @Volatile private var initialized = false
    private var defaultCrashHandler: Thread.UncaughtExceptionHandler? = null

    fun init(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            appContext = context.applicationContext
            rotateSessionLogs(context.applicationContext)
            installCrashHandler()
            initialized = true
        }
        i("V2AppLog", "log initialized, dir=${logDirectory(context).absolutePath}")
    }

    fun d(tag: String, message: String, throwable: Throwable? = null) = log(Log.DEBUG, tag, message, throwable)
    fun i(tag: String, message: String, throwable: Throwable? = null) = log(Log.INFO, tag, message, throwable)
    fun w(tag: String, message: String, throwable: Throwable? = null) = log(Log.WARN, tag, message, throwable)
    fun e(tag: String, message: String, throwable: Throwable? = null) = log(Log.ERROR, tag, message, throwable)

    fun saveToPersistentLog(context: Context) {
        val snapshot = synchronized(lock) { buffer.toList() }
        if (snapshot.isEmpty()) return
        writeLines(File(logDirectory(context), CURRENT_SESSION_LOG), snapshot)
    }

    fun saveToPersistentLog() {
        appContext?.let { saveToPersistentLog(it) }
    }

    fun exportCurrentLogs(context: Context): File? {
        saveToPersistentLog(context)
        val snapshot = synchronized(lock) { buffer.toList() }
        if (snapshot.isEmpty()) return null
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "EVCam_Log")
        val file = File(dir, "evcam_v2_log_$timestamp.txt")
        return if (writeLines(file, snapshot)) file else null
    }

    private fun log(level: Int, tag: String, message: String, throwable: Throwable?) {
        val fullMessage = if (throwable == null) message else message + "\n" + Log.getStackTraceString(throwable)
        Log.println(level, tag, fullMessage)
        addToBuffer(level, tag, fullMessage)
    }

    private fun addToBuffer(level: Int, tag: String, message: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        val line = "$timestamp ${levelLabel(level)}/$tag: $message"
        synchronized(lock) {
            buffer.add(line)
            if (buffer.size > MAX_BUFFER_LINES) {
                buffer.subList(0, buffer.size - MAX_BUFFER_LINES).clear()
            }
        }
    }

    private fun rotateSessionLogs(context: Context) {
        val dir = logDirectory(context)
        val current = File(dir, CURRENT_SESSION_LOG)
        val previous = File(dir, PREVIOUS_SESSION_LOG)
        if (current.exists() && current.length() > 0L) {
            if (previous.exists()) previous.delete()
            current.renameTo(previous)
        }
    }

    private fun installCrashHandler() {
        defaultCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            e("V2Crash", "uncaught exception on ${thread.name}", throwable)
            appContext?.let { saveToPersistentLog(it) }
            defaultCrashHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun logDirectory(context: Context): File = File(context.filesDir, "logs").apply { mkdirs() }

    private fun writeLines(file: File, lines: List<String>): Boolean = runCatching {
        file.parentFile?.mkdirs()
        OutputStreamWriter(FileOutputStream(file), Charsets.UTF_8).use { writer ->
            lines.forEach { line ->
                writer.write(line)
                writer.write('\n'.code)
            }
        }
        true
    }.getOrElse { error ->
        Log.w("V2AppLog", "write log failed: ${file.absolutePath}", error)
        false
    }

    private fun levelLabel(level: Int): String = when (level) {
        Log.ERROR -> "E"
        Log.WARN -> "W"
        Log.INFO -> "I"
        Log.DEBUG -> "D"
        else -> level.toString()
    }
}
