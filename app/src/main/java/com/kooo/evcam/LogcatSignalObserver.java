package com.kooo.evcam;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Logcat 信号观察者
 * 专门解析转向灯系统日志信号
 *
 * 使用 logcat -e 在原生层面过滤，只输出匹配的日志行。
 * 这样即使行驶中系统日志量暴增，也不会影响转向灯信号的响应速度。
 */
public class LogcatSignalObserver {
    private static final String TAG = "LogcatSignalObserver";
    
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
    private Process logcatProcess;
    private volatile boolean isRunning = false;
    private String[] filterKeywords;

    public LogcatSignalObserver(SignalListener listener) {
        this.listener = listener;
    }

    /**
     * 设置过滤关键字列表（用于构建 logcat -e 正则）。
     * 必须在 start() 之前调用。
     * @param keywords 转向灯相关关键字（如左转触发词、右转触发词）
     */
    public void setFilterKeywords(String... keywords) {
        this.filterKeywords = keywords;
    }

    public void start() {
        if (isRunning) return;
        isRunning = true;
        
        logcatThread = new Thread(() -> {
            Process process = null;
            BufferedReader reader = null;
            try {
                // 使用 -T 参数从当前时间开始读取，完全跳过历史缓冲区。
                // 避免冷启动时读到旧的转向灯信号导致误触发补盲画面。
                SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
                String now = sdf.format(new Date());

                // 构建 logcat -e 正则过滤表达式，在原生层面只输出匹配的行。
                // 行驶中车机日志量可能每秒数千行，不做原生过滤会导致转向灯信号延迟。
                String regexFilter = buildLogcatRegex();

                List<String> cmd = new ArrayList<>();
                cmd.add("logcat");
                cmd.add("-v");
                cmd.add("brief");
                cmd.add("-T");
                cmd.add(now);
                if (regexFilter != null) {
                    cmd.add("-e");
                    cmd.add(regexFilter);
                }
                cmd.add("*:V");

                AppLog.d(TAG, "Logcat command: " + cmd);

                process = Runtime.getRuntime().exec(cmd.toArray(new String[0]));
                logcatProcess = process;
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
                logcatProcess = null;
            }
        });
        logcatThread.setPriority(Thread.MAX_PRIORITY);
        logcatThread.start();
    }

    /**
     * 构建 logcat -e 使用的正则表达式。
     * 将用户配置的触发关键字与内置的 "data1 =" 模式合并为一个 OR 正则。
     * logcat -e 在 C 层过滤，效率远高于 Java 层逐行检查。
     *
     * @return 正则字符串，若无有效关键字则返回 null（不过滤）
     */
    private String buildLogcatRegex() {
        // 使用 Set 去重
        Set<String> parts = new HashSet<>();

        // 内置 data1 模式（转向灯通用信号）
        parts.add("data1 =");

        // 内置转向灯状态关键字（用于检测转向灯关闭）
        parts.add("front turn signal:");

        // 用户自定义触发关键字
        if (filterKeywords != null) {
            for (String keyword : filterKeywords) {
                if (keyword != null && !keyword.trim().isEmpty()) {
                    // 提取关键字中最具区分度的固定部分作为过滤条件
                    // 例如 "left front turn signal:1" -> 已被 "front turn signal:" 覆盖
                    // 但如果用户设置了完全不同的关键字，需要单独加入
                    String trimmed = keyword.trim();
                    boolean coveredByBuiltin = false;
                    for (String builtin : new String[]{"data1 =", "front turn signal:"}) {
                        if (trimmed.contains(builtin)) {
                            coveredByBuiltin = true;
                            break;
                        }
                    }
                    if (!coveredByBuiltin) {
                        // 对正则特殊字符进行转义
                        parts.add(escapeRegex(trimmed));
                    }
                }
            }
        }

        if (parts.isEmpty()) return null;

        // 用 "|" 连接为 OR 表达式
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (sb.length() > 0) sb.append("|");
            sb.append(part);
        }
        return sb.toString();
    }

    /**
     * 转义正则特殊字符（logcat -e 使用 POSIX ERE）
     */
    private static String escapeRegex(String input) {
        // 只转义在 logcat 正则中有特殊含义的字符
        StringBuilder sb = new StringBuilder();
        for (char c : input.toCharArray()) {
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    public void stop() {
        isRunning = false;
        // 先销毁进程，使 readLine() 立即返回 null 从而退出循环
        if (logcatProcess != null) {
            logcatProcess.destroy();
            logcatProcess = null;
        }
        if (logcatThread != null) {
            logcatThread.interrupt();
            logcatThread = null;
        }
    }
}
