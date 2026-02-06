package com.kooo.evcam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logcat 信号观察者
 * 专门解析转向灯系统日志信号
 */
public class LogcatSignalObserver {
    private static final String TAG = "LogcatSignalObserver";
    
    private static final String LOGCAT_CMD = "logcat -v brief *:I";
    private static final Pattern SIGNAL_PATTERN = Pattern.compile("data1 = (\\d+)");

    public interface SignalListener {
        /**
         * 原始日志回调
         * @param line 完整logcat行
         * @param data1 解析到的 data1（未解析到则为 -1）
         */
        void onLogLine(String line, int data1);
    }

    private final SignalListener listener;
    private Thread logcatThread;
    private volatile boolean isRunning = false;

    public LogcatSignalObserver(SignalListener listener) {
        this.listener = listener;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        
        logcatThread = new Thread(() -> {
            Process process = null;
            BufferedReader reader = null;
            try {
                process = Runtime.getRuntime().exec(LOGCAT_CMD);
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                
                String line;
                while (isRunning && (line = reader.readLine()) != null) {
                    int data1 = -1;
                    if (line.contains("data1 =")) {
                        Matcher matcher = SIGNAL_PATTERN.matcher(line);
                        if (matcher.find()) {
                            try {
                                data1 = Integer.parseInt(matcher.group(1));
                            } catch (NumberFormatException e) {
                                data1 = -1;
                            }
                        }
                    }
                    if (listener != null) {
                        listener.onLogLine(line, data1);
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Logcat reading error: " + e.getMessage());
            } finally {
                isRunning = false;
                try {
                    if (reader != null) reader.close();
                    if (process != null) process.destroy();
                } catch (Exception e) {
                    // Ignore
                }
            }
        });
        logcatThread.setPriority(Thread.MAX_PRIORITY);
        logcatThread.start();
    }

    public void stop() {
        isRunning = false;
        if (logcatThread != null) {
            logcatThread.interrupt();
            logcatThread = null;
        }
    }
}
