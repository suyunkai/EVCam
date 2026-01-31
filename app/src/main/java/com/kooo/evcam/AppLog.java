package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
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
    
    // 会话日志文件名
    private static final String CURRENT_SESSION_LOG = "current_session.log";
    private static final String PREVIOUS_SESSION_LOG = "previous_session.log";
    
    // Application Context 引用（用于崩溃时保存日志）
    private static Context sAppContext = null;
    
    // 原始的 UncaughtExceptionHandler
    private static Thread.UncaughtExceptionHandler sDefaultHandler = null;
    
    // Gotify 服务器配置
    private static final String GOTIFY_SERVER_URL = "http://suyunkai.top:40266";
    private static final String GOTIFY_APP_TOKEN = "ABH7ON6sdUfR4Sc";
    
    /**
     * 日志上传回调接口
     */
    public interface UploadCallback {
        void onSuccess();
        void onError(String error);
    }

    private AppLog() {
    }

    public static void init(Context context) {
        if (context == null) {
            return;
        }
        
        // 保存 Application Context（用于崩溃时保存日志）
        sAppContext = context.getApplicationContext();
        
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        debugToInfo = prefs.getBoolean(KEY_DEBUG_TO_INFO, false);
        
        // 启动时轮换日志文件
        rotateSessionLogs(context);
        
        // 设置崩溃处理器，确保闪退时能保存日志
        setupCrashHandler();
    }
    
    /**
     * 设置崩溃处理器
     * 在应用崩溃时自动保存日志，便于排查闪退问题
     */
    private static void setupCrashHandler() {
        // 保存原始的 handler
        sDefaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                // 记录崩溃信息到日志
                String crashInfo = "!!! APPLICATION CRASH !!!\n" +
                        "Thread: " + thread.getName() + "\n" +
                        "Exception: " + throwable.getClass().getName() + "\n" +
                        "Message: " + throwable.getMessage() + "\n" +
                        "Stack trace:\n" + Log.getStackTraceString(throwable);
                
                // 添加崩溃信息到缓冲区
                addToBuffer(Log.ERROR, "CRASH", crashInfo);
                
                // 保存日志到文件
                if (sAppContext != null) {
                    saveToPersistentLog(sAppContext);
                    Log.e("AppLog", "Crash log saved successfully");
                }
            } catch (Exception e) {
                Log.e("AppLog", "Failed to save crash log", e);
            }
            
            // 调用原始 handler（让系统继续处理崩溃）
            if (sDefaultHandler != null) {
                sDefaultHandler.uncaughtException(thread, throwable);
            }
        });
        
        Log.i("AppLog", "Crash handler installed for automatic log saving");
    }
    
    /**
     * 轮换会话日志文件
     * 将当前日志备份为上次日志，清空当前日志
     */
    private static void rotateSessionLogs(Context context) {
        if (context == null) return;
        
        File logDir = getLogDirectory(context);
        File currentLog = new File(logDir, CURRENT_SESSION_LOG);
        File previousLog = new File(logDir, PREVIOUS_SESSION_LOG);
        
        // 如果当前日志存在，将其备份为上次日志
        if (currentLog.exists() && currentLog.length() > 0) {
            // 删除旧的上次日志
            if (previousLog.exists()) {
                if (!previousLog.delete()) {
                    Log.w("AppLog", "Failed to delete old previous session log");
                }
            }
            // 重命名当前日志为上次日志
            boolean renamed = currentLog.renameTo(previousLog);
            if (renamed) {
                Log.i("AppLog", "Previous session log saved: " + previousLog.getAbsolutePath());
            } else {
                Log.w("AppLog", "Failed to rename current session log to previous");
            }
        }
    }
    
    /**
     * 获取日志存储目录
     */
    private static File getLogDirectory(Context context) {
        // 使用应用私有目录存储会话日志，避免权限问题
        File logDir = new File(context.getFilesDir(), "logs");
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        return logDir;
    }
    
    /**
     * 保存当前日志到持久化文件
     * 建议在 Activity.onStop() 或 onDestroy() 中调用
     */
    public static void saveToPersistentLog(Context context) {
        if (context == null) return;
        
        List<String> snapshot;
        synchronized (LOCK) {
            snapshot = new ArrayList<>(BUFFER);
        }
        
        if (snapshot.isEmpty()) return;
        
        File logDir = getLogDirectory(context);
        File currentLog = new File(logDir, CURRENT_SESSION_LOG);
        
        try (OutputStreamWriter writer = new OutputStreamWriter(
                new FileOutputStream(currentLog), StandardCharsets.UTF_8)) {
            for (String line : snapshot) {
                writer.write(line);
                writer.write('\n');
            }
            Log.d("AppLog", "Current session log saved: " + snapshot.size() + " lines");
        } catch (IOException e) {
            Log.w("AppLog", "Failed to save current session log: " + e.getMessage());
        }
    }
    
    /**
     * 检查是否有上次运行的日志
     */
    public static boolean hasPreviousSessionLogs(Context context) {
        if (context == null) return false;
        
        File logDir = getLogDirectory(context);
        File previousLog = new File(logDir, PREVIOUS_SESSION_LOG);
        return previousLog.exists() && previousLog.length() > 0;
    }
    
    /**
     * 获取上次运行日志的信息（行数和时间）
     */
    public static String getPreviousSessionLogInfo(Context context) {
        if (context == null) return null;
        
        File logDir = getLogDirectory(context);
        File previousLog = new File(logDir, PREVIOUS_SESSION_LOG);
        
        if (!previousLog.exists() || previousLog.length() == 0) {
            return null;
        }
        
        // 读取文件获取行数和首行时间
        int lineCount = 0;
        String firstLine = null;
        String lastLine = null;
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(previousLog), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (lineCount == 0) {
                    firstLine = line;
                }
                lastLine = line;
                lineCount++;
            }
        } catch (IOException e) {
            return null;
        }
        
        // 提取时间信息
        String startTime = extractTimeFromLogLine(firstLine);
        String endTime = extractTimeFromLogLine(lastLine);
        
        if (startTime != null && endTime != null) {
            return lineCount + " 条日志 (" + startTime + " ~ " + endTime + ")";
        } else {
            return lineCount + " 条日志";
        }
    }
    
    /**
     * 从日志行中提取时间部分
     */
    private static String extractTimeFromLogLine(String line) {
        if (line == null || line.length() < 19) return null;
        // 日志格式: "2025-01-31 12:34:56.789 ..."
        // 只取时间部分 "12:34:56"
        try {
            return line.substring(11, 19);
        } catch (Exception e) {
            return null;
        }
    }
    
    /**
     * 获取上次运行的日志内容
     */
    public static List<String> getPreviousSessionLogs(Context context) {
        List<String> logs = new ArrayList<>();
        if (context == null) return logs;
        
        File logDir = getLogDirectory(context);
        File previousLog = new File(logDir, PREVIOUS_SESSION_LOG);
        
        if (!previousLog.exists()) return logs;
        
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.FileInputStream(previousLog), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                logs.add(line);
            }
        } catch (IOException e) {
            Log.w("AppLog", "Failed to read previous session log: " + e.getMessage());
        }
        
        return logs;
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
    
    /**
     * 一键上传日志到 Gotify 服务器（上传当前运行日志）
     * @param context 上下文
     * @param deviceNickname 设备识别名称
     * @param problemDescription 问题描述
     * @param callback 上传结果回调
     */
    public static void uploadLogsToServer(Context context, String deviceNickname, String problemDescription, UploadCallback callback) {
        uploadLogsToServer(context, deviceNickname, problemDescription, false, callback);
    }
    
    /**
     * 一键上传日志到 Gotify 服务器
     * @param context 上下文
     * @param deviceNickname 设备识别名称
     * @param problemDescription 问题描述
     * @param uploadPreviousSession 是否上传上次运行的日志
     * @param callback 上传结果回调
     */
    public static void uploadLogsToServer(Context context, String deviceNickname, String problemDescription, 
                                          boolean uploadPreviousSession, UploadCallback callback) {
        if (context == null || callback == null) {
            if (callback != null) {
                callback.onError("Context is null");
            }
            return;
        }
        
        // 在后台线程执行网络请求
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                // 获取日志内容
                List<String> snapshot;
                if (uploadPreviousSession) {
                    // 获取上次运行日志
                    snapshot = getPreviousSessionLogs(context);
                } else {
                    // 获取当前运行日志
                    synchronized (LOCK) {
                        snapshot = new ArrayList<>(BUFFER);
                    }
                }
                
                if (snapshot.isEmpty()) {
                    callback.onError(uploadPreviousSession ? "上次运行日志为空" : "日志为空");
                    return;
                }
                
                // 获取应用版本信息
                String versionName = "unknown";
                int versionCode = 0;
                try {
                    PackageInfo packageInfo = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
                    versionName = packageInfo.versionName;
                    versionCode = (int) packageInfo.getLongVersionCode();
                } catch (PackageManager.NameNotFoundException e) {
                    // 忽略
                }
                
                // 构建日志内容
                String logType = uploadPreviousSession ? "上次运行日志" : "本次运行日志";
                StringBuilder logContent = new StringBuilder();
                logContent.append("=== EVCam 日志上传 (").append(logType).append(") ===\n");
                logContent.append("用户标识: ").append(deviceNickname != null ? deviceNickname : "未知").append("\n");
                logContent.append("设备型号: ").append(Build.MODEL).append("\n");
                logContent.append("系统版本: Android ").append(Build.VERSION.RELEASE)
                         .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
                logContent.append("应用版本: ").append(versionName)
                         .append(" (").append(versionCode).append(")\n");
                logContent.append("上传时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(new Date())).append("\n");
                logContent.append("日志类型: ").append(logType).append("\n");
                logContent.append("日志条数: ").append(snapshot.size()).append("\n");
                logContent.append("========================\n\n");
                
                // 添加问题描述
                logContent.append("【问题描述】\n");
                logContent.append(problemDescription != null ? problemDescription : "（无）").append("\n\n");
                logContent.append("========================\n\n");
                
                for (String line : snapshot) {
                    logContent.append(line).append("\n");
                }
                
                // 构建请求 URL
                URL url = new URL(GOTIFY_SERVER_URL + "/message?token=" + GOTIFY_APP_TOKEN);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                connection.setDoOutput(true);
                connection.setConnectTimeout(15000);
                connection.setReadTimeout(15000);
                
                // 构建 JSON 请求体 - 标题包含用户标识和日志类型便于识别
                JSONObject jsonBody = new JSONObject();
                String logTypeShort = uploadPreviousSession ? "[Previous]" : "[Current]";
                String title = deviceNickname != null ? 
                        "EVCam " + logTypeShort + " - " + deviceNickname : 
                        "EVCam " + logTypeShort + " - " + Build.MODEL;
                jsonBody.put("title", title);
                jsonBody.put("message", logContent.toString());
                jsonBody.put("priority", 5);
                
                // 发送请求
                try (OutputStream os = connection.getOutputStream()) {
                    byte[] input = jsonBody.toString().getBytes(StandardCharsets.UTF_8);
                    os.write(input, 0, input.length);
                }
                
                // 获取响应
                int responseCode = connection.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                    callback.onSuccess();
                } else {
                    // 读取错误信息
                    StringBuilder errorResponse = new StringBuilder();
                    try (BufferedReader br = new BufferedReader(
                            new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            errorResponse.append(line);
                        }
                    } catch (Exception e) {
                        // 忽略读取错误流的异常
                    }
                    callback.onError("HTTP " + responseCode + ": " + errorResponse);
                }
                
            } catch (Exception e) {
                Log.e("AppLog", "Upload failed", e);
                callback.onError(e.getMessage());
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
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
