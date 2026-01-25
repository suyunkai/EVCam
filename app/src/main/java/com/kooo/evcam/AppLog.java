package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class AppLog {
    private static final String PREFS_NAME = "app_settings";
    private static final String KEY_DEBUG_TO_INFO = "debug_to_info";
    private static final int MAX_BUFFER_LINES = 5000;
    private static final Object LOCK = new Object();
    private static final List<String> BUFFER = new ArrayList<>();
    private static volatile boolean debugToInfo = false;

    private AppLog() {
    }

    public static void init(Context context) {
        if (context == null) {
            return;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        debugToInfo = prefs.getBoolean(KEY_DEBUG_TO_INFO, false);
    }

    public static boolean isDebugToInfoEnabled(Context context) {
        if (context != null) {
            init(context);
        }
        return debugToInfo;
    }

    public static void setDebugToInfoEnabled(Context context, boolean enabled) {
        debugToInfo = enabled;
        if (context != null) {
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit().putBoolean(KEY_DEBUG_TO_INFO, enabled).apply();
        }
    }

    public static File saveLogsToFile(Context context) {
        if (context == null) {
            return null;
        }
        List<String> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(BUFFER);
        }
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String fileName = "evcam_log_" + timestamp + ".txt";

        // 保存到 Download/EVCam_Log/ 目录
        File logDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "EVCam_Log");
        File logFile = new File(logDir, fileName);
        return writeLogToFile(logFile, snapshot) ? logFile : null;
    }

    private static boolean writeLogToFile(File logFile, List<String> lines) {
        if (!logFile.getParentFile().exists() && !logFile.getParentFile().mkdirs()) {
            return false;
        }
        try (OutputStreamWriter writer = new OutputStreamWriter(new FileOutputStream(logFile), StandardCharsets.UTF_8)) {
            for (String line : lines) {
                writer.write(line);
                writer.write('\n');
            }
            return true;
        } catch (IOException e) {
            Log.w("AppLog", "Cannot write to " + logFile.getAbsolutePath() + ": " + e.getMessage());
            return false;
        }
    }

    public static void d(String tag, String message) {
        logInternal(Log.DEBUG, tag, message, null);
    }

    public static void d(String tag, String message, Throwable tr) {
        logInternal(Log.DEBUG, tag, message, tr);
    }

    public static void i(String tag, String message) {
        logInternal(Log.INFO, tag, message, null);
    }

    public static void i(String tag, String message, Throwable tr) {
        logInternal(Log.INFO, tag, message, tr);
    }

    public static void w(String tag, String message) {
        logInternal(Log.WARN, tag, message, null);
    }

    public static void w(String tag, String message, Throwable tr) {
        logInternal(Log.WARN, tag, message, tr);
    }

    public static void e(String tag, String message) {
        logInternal(Log.ERROR, tag, message, null);
    }

    public static void e(String tag, String message, Throwable tr) {
        logInternal(Log.ERROR, tag, message, tr);
    }

    private static void logInternal(int level, String tag, String message, Throwable tr) {
        String safeTag = tag == null ? "AppLog" : tag;
        String safeMessage = message == null ? "" : message;
        if (tr != null) {
            safeMessage = safeMessage + "\n" + Log.getStackTraceString(tr);
        }
        int outputLevel = (level == Log.DEBUG && debugToInfo) ? Log.INFO : level;
        Log.println(outputLevel, safeTag, safeMessage);
        addToBuffer(outputLevel, safeTag, safeMessage);
    }

    private static void addToBuffer(int level, String tag, String message) {
        String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
        String levelLabel = levelToLabel(level);
        String line = timestamp + " " + levelLabel + "/" + tag + ": " + message;
        synchronized (LOCK) {
            BUFFER.add(line);
            if (BUFFER.size() > MAX_BUFFER_LINES) {
                int removeCount = BUFFER.size() - MAX_BUFFER_LINES;
                BUFFER.subList(0, removeCount).clear();
            }
        }
    }

    private static String levelToLabel(int level) {
        switch (level) {
            case Log.ERROR:
                return "E";
            case Log.WARN:
                return "W";
            case Log.INFO:
                return "I";
            case Log.DEBUG:
                return "D";
            default:
                return String.valueOf(level);
        }
    }
}
