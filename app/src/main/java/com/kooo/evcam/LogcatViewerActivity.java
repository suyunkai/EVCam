package com.kooo.evcam;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Logcat 实时日志查看工具
 */
public class LogcatViewerActivity extends Activity {
    private TextView logTextView;
    private ScrollView scrollView;
    private Thread logThread;
    private volatile boolean isRunning = false;
    private String filterKeyword = "";
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // 设置全屏和常亮
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        
        setContentView(R.layout.activity_logcat_viewer);
        
        logTextView = findViewById(R.id.tv_log_content);
        scrollView = findViewById(R.id.scroll_log);
        Button btnClear = findViewById(R.id.btn_clear_log);
        Button btnSave = findViewById(R.id.btn_save_log);
        Button btnClose = findViewById(R.id.btn_close_log);
        
        filterKeyword = getIntent().getStringExtra("filter_keyword");
        if (filterKeyword == null) filterKeyword = "";
        
        setTitle("Logcat Debug: " + (filterKeyword.isEmpty() ? "All" : filterKeyword));
        
        btnClear.setOnClickListener(v -> logTextView.setText(""));
        btnSave.setOnClickListener(v -> saveLogToFile());
        btnClose.setOnClickListener(v -> finish());
        
        startReadingLogs();
    }

    private void saveLogToFile() {
        String logContent = logTextView.getText().toString();
        if (logContent.isEmpty()) {
            Toast.makeText(this, "Log is empty, nothing to save", Toast.LENGTH_SHORT).show();
            return;
        }
        
        try {
            String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
            String fileName = "logcat_" + timestamp + ".txt";
            
            // 保存到 Download/EVCam_Log/ 目录（与设置界面debug模式日志保存位置一致）
            File logDir = new File(Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS), "EVCam_Log");
            if (!logDir.exists()) {
                logDir.mkdirs();
            }
            
            File logFile = new File(logDir, fileName);
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(logFile), StandardCharsets.UTF_8)) {
                writer.write(logContent);
            }
            
            Toast.makeText(this, "Log saved: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "保存失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void startReadingLogs() {
        isRunning = true;
        logThread = new Thread(() -> {
            Process process = null;
            BufferedReader reader = null;
            try {
                // 读取最近的100条日志并持续监听
                String cmd = "logcat -v time";
                process = Runtime.getRuntime().exec(cmd);
                reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
                
                String line;
                while (isRunning && (line = reader.readLine()) != null) {
                    if (filterKeyword.isEmpty() || line.contains(filterKeyword)) {
                        final String finalLine = line;
                        mainHandler.post(() -> {
                            logTextView.append(finalLine + "\n");
                            // 自动滚动到底部
                            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
                            
                            // 防止内容过多导致内存问题
                            if (logTextView.length() > 20000) {
                                logTextView.setText(logTextView.getText().subSequence(logTextView.length() - 10000, logTextView.length()));
                            }
                        });
                    }
                }
            } catch (Exception e) {
                mainHandler.post(() -> logTextView.append("Error reading logs: " + e.getMessage() + "\n"));
            } finally {
                try {
                    if (reader != null) reader.close();
                    if (process != null) process.destroy();
                } catch (Exception e) {}
            }
        });
        logThread.start();
    }

    @Override
    protected void onDestroy() {
        isRunning = false;
        if (logThread != null) {
            logThread.interrupt();
        }
        super.onDestroy();
    }
}
