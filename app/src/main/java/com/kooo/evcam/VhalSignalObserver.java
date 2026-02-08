package com.kooo.evcam;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 通过 EVCC daemon 的 Vehicle HAL API 监听转向灯信号。
 *
 * 连接 EVCC 的 root daemon（abstract Unix domain socket），
 * 订阅车辆属性变化事件，过滤转向灯 property 回调给调用方。
 *
 * 相比 LogcatSignalObserver 的优势：
 * - 不受系统日志拥堵影响
 * - 延迟 ~10-50ms（logcat 方案 500ms+）
 * - 直接从 Vehicle HAL 获取数据，更可靠
 *
 * 前提条件：设备上已安装并启动 EVCC daemon。
 */
public class VhalSignalObserver {
    private static final String TAG = "VhalSignalObserver";

    // E5 转向灯 Vehicle HAL Property IDs
    // 来源: vendor/etc/transfers/libapvp/apvp_signal_config.json
    public static final int PROP_TURN_INDICATOR_LEFT = 306184813;   // ExtrLtgStsTurnIndrLe
    public static final int PROP_TURN_INDICATOR_RIGHT = 306184814;  // ExtrLtgStsTurnIndrRi

    // 转向灯状态值
    private static final int VALUE_OFF = 0;
    private static final int VALUE_ON = 1;

    /**
     * 转向灯信号回调接口
     */
    public interface TurnSignalListener {
        /** 转向灯状态变化 */
        void onTurnSignal(String direction, boolean on);
        /** 连接状态变化 */
        void onConnectionStateChanged(boolean connected);
    }

    private final TurnSignalListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger requestId = new AtomicInteger(0);
    private final Object writeLock = new Object();

    private LocalSocket socket;
    private BufferedWriter writer;
    private BufferedReader reader;
    private Thread readerThread;
    private Thread connectThread;
    private volatile boolean running = false;
    private volatile boolean connected = false;

    // 重连参数
    private static final long RECONNECT_DELAY_MS = 3000;
    private static final long INITIAL_CONNECT_TIMEOUT_MS = 2000;

    public VhalSignalObserver(TurnSignalListener listener) {
        this.listener = listener;
    }

    /**
     * 启动连接和监听
     */
    public void start() {
        if (running) return;
        running = true;
        connectThread = new Thread(this::connectLoop, "VhalSignalConnect");
        connectThread.setDaemon(true);
        connectThread.start();
    }

    /**
     * 停止连接和监听
     */
    public void stop() {
        running = false;
        disconnect();
        if (connectThread != null) {
            connectThread.interrupt();
            connectThread = null;
        }
    }

    /**
     * 当前是否已连接到 daemon
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 尝试一次性连接测试（阻塞调用，用于 UI 状态检查）
     * @return true 如果能成功连接到 daemon
     */
    public static boolean testConnection() {
        LocalSocket testSocket = null;
        try {
            testSocket = new LocalSocket();
            testSocket.connect(new LocalSocketAddress(
                    DaemonProtocol.SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT
            ));

            BufferedWriter w = new BufferedWriter(new OutputStreamWriter(testSocket.getOutputStream()));
            BufferedReader r = new BufferedReader(new InputStreamReader(testSocket.getInputStream()));

            // 发送 isConnected 命令
            JSONObject req = new JSONObject();
            req.put(DaemonProtocol.KEY_ID, 1);
            req.put(DaemonProtocol.KEY_CMD, DaemonProtocol.CMD_IS_CONNECTED);

            w.write(req.toString());
            w.newLine();
            w.flush();

            // 读取响应
            String line = r.readLine();
            if (line != null) {
                JSONObject resp = new JSONObject(line);
                return resp.optString(DaemonProtocol.KEY_STATUS).equals(DaemonProtocol.STATUS_OK);
            }
            return false;
        } catch (Exception e) {
            return false;
        } finally {
            try {
                if (testSocket != null) testSocket.close();
            } catch (Exception ignored) {}
        }
    }

    // ==================== Internal ====================

    private void connectLoop() {
        while (running) {
            try {
                if (!connected) {
                    AppLog.d(TAG, "Attempting to connect to EVCC daemon...");
                    boolean ok = connect();
                    if (ok) {
                        AppLog.d(TAG, "Connected to EVCC daemon, starting monitor");
                        notifyConnectionState(true);
                        startMonitoring();
                        readLoop(); // blocks until disconnected
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Connection error: " + e.getMessage());
            }

            // Disconnected or failed
            connected = false;
            notifyConnectionState(false);
            disconnect();

            if (!running) break;

            // Wait before reconnecting
            try {
                AppLog.d(TAG, "Reconnecting in " + RECONNECT_DELAY_MS + "ms...");
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    private boolean connect() {
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(
                    DaemonProtocol.SOCKET_NAME,
                    LocalSocketAddress.Namespace.ABSTRACT
            ));
            writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()));
            reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            connected = true;
            return true;
        } catch (Exception e) {
            AppLog.d(TAG, "Connect failed: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    private void disconnect() {
        connected = false;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        writer = null;
        reader = null;
    }

    private void startMonitoring() {
        try {
            JSONObject req = new JSONObject();
            req.put(DaemonProtocol.KEY_ID, requestId.incrementAndGet());
            req.put(DaemonProtocol.KEY_CMD, DaemonProtocol.CMD_START_MONITOR);

            synchronized (writeLock) {
                if (writer != null) {
                    writer.write(req.toString());
                    writer.newLine();
                    writer.flush();
                }
            }
            AppLog.d(TAG, "startMonitor command sent");
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to send startMonitor: " + e.getMessage());
        }
    }

    private void readLoop() {
        try {
            while (running && connected) {
                String line = reader.readLine();
                if (line == null) break; // Connection closed

                try {
                    JSONObject json = new JSONObject(line);
                    String type = json.optString(DaemonProtocol.KEY_TYPE, DaemonProtocol.TYPE_RESPONSE);

                    if (DaemonProtocol.TYPE_EVENT.equals(type)) {
                        handleEvent(json);
                    }
                    // Response messages are ignored (we don't wait for them in monitoring mode)
                } catch (Exception e) {
                    AppLog.e(TAG, "Parse error: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            if (running) {
                AppLog.e(TAG, "Read loop ended: " + e.getMessage());
            }
        }
    }

    private void handleEvent(JSONObject json) {
        String event = json.optString(DaemonProtocol.KEY_EVENT, "");
        if (!DaemonProtocol.EVENT_PROPERTY_CHANGED.equals(event)) return;

        int propId = json.optInt(DaemonProtocol.KEY_PROP_ID, 0);

        // 只关心转向灯属性
        if (propId != PROP_TURN_INDICATOR_LEFT && propId != PROP_TURN_INDICATOR_RIGHT) return;

        // 解析值：daemon 发送格式为 "1 (0x1)" 或 "0 (0x0)"
        String valueStr = json.optString(DaemonProtocol.KEY_VALUE, "");
        int value = parseIntFromValueStr(valueStr);

        String direction = (propId == PROP_TURN_INDICATOR_LEFT) ? "left" : "right";
        boolean on = (value == VALUE_ON);

        AppLog.d(TAG, "Turn signal event: " + direction + " = " + (on ? "ON" : "OFF")
                + " (raw: " + valueStr + ")");

        mainHandler.post(() -> {
            if (listener != null) {
                listener.onTurnSignal(direction, on);
            }
        });
    }

    /**
     * 解析 daemon 返回的值字符串。
     * 格式为 "1 (0x1)" 或 "0 (0x0)"，提取第一个数字。
     */
    private static int parseIntFromValueStr(String valueStr) {
        if (valueStr == null || valueStr.isEmpty()) return -1;
        try {
            // 尝试直接解析整数
            return Integer.parseInt(valueStr.trim());
        } catch (NumberFormatException e) {
            // 尝试解析 "1 (0x1)" 格式
            String trimmed = valueStr.trim();
            int spaceIdx = trimmed.indexOf(' ');
            if (spaceIdx > 0) {
                try {
                    return Integer.parseInt(trimmed.substring(0, spaceIdx));
                } catch (NumberFormatException e2) {
                    return -1;
                }
            }
            return -1;
        }
    }

    private void notifyConnectionState(boolean isConnected) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnectionStateChanged(isConnected);
            }
        });
    }
}
