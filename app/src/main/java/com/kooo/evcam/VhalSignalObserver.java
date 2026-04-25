package com.kooo.evcam;

import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

/**
 * 通过车辆API 监听车辆信号（转向灯 + 车门状态）。
 * 协议解码由 native 层完成。
 */
public class VhalSignalObserver {
    private static final String TAG = "VhalSignalObserver";

    /**
     * 转向灯信号回调接口
     */
    public interface TurnSignalListener {
        /** 转向灯状态变化 */
        void onTurnSignal(String direction, boolean on);
        /** 连接状态变化 */
        void onConnectionStateChanged(boolean connected);
    }

    /**
     * 车门信号回调接口（与 DoorSignalObserver.DoorSignalListener 方法签名一致）
     */
    public interface DoorSignalListener {
        void onDoorOpen(String side);
        void onDoorClose(String side);
        void onConnectionStateChanged(boolean connected);
    }

    /**
     * 定制键唤醒回调接口
     */
    public interface CustomKeyListener {
        /** 速度阈值或长按按键触发 */
        void onCustomKeyTriggered(int triggerMode);
    }

    private final TurnSignalListener listener;
    private volatile DoorSignalListener doorListener;
    private volatile CustomKeyListener customKeyListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // 定制键唤醒状态跟踪
    private static final int CUSTOM_KEY_LONG_PRESS_VALUE = 4;
    private volatile float currentSpeed = 0f;
    private volatile float customKeySpeedThreshold = 8.34f;
    private volatile int customKeyTriggerMode = AppConfig.CUSTOM_KEY_TRIGGER_MODE_SPEED_THRESHOLD;
    private volatile boolean wasSpeedAtOrAboveThreshold = false;
    private volatile int lastButtonState = -1;

    private ManagedChannel grpcChannel;
    private Thread connectThread;
    private volatile boolean running = false;
    private volatile boolean connected = false;

    // 上一次的转向灯状态，避免重复回调
    private int lastSignalState = -1;

    // 车门状态跟踪（用于多门关闭逻辑）
    private volatile boolean isPassDoorOpen = false;     // 副驾门
    private volatile boolean isLeftRearDoorOpen = false;  // 左后门
    private volatile boolean isRightRearDoorOpen = false; // 右后门

    // 重连参数
    private static final long RECONNECT_DELAY_MS = 3000;
    private static final long STREAM_TIMEOUT_MS = 120_000; // gRPC stream 最大沉默时间

    public VhalSignalObserver(TurnSignalListener listener) {
        this.listener = listener;
    }

    /**
     * 设置车门信号监听器（可在 start() 前后调用）
     */
    public void setDoorSignalListener(DoorSignalListener listener) {
        this.doorListener = listener;
    }

    /**
     * 设置定制键唤醒监听器
     */
    public void setCustomKeyListener(CustomKeyListener listener) {
        this.customKeyListener = listener;
    }

    /**
     * 获取当前速度值（用于定制键唤醒速度条件判断）
     */
    public float getCurrentSpeed() {
        return currentSpeed;
    }

    /**
     * 配置定制键唤醒参数
     */
    public void configureCustomKey(int speedPropId, int buttonPropId, float speedThreshold, int triggerMode) {
        customKeySpeedThreshold = speedThreshold;
        customKeyTriggerMode = triggerMode == AppConfig.CUSTOM_KEY_TRIGGER_MODE_LONG_PRESS
                ? AppConfig.CUSTOM_KEY_TRIGGER_MODE_LONG_PRESS
                : AppConfig.CUSTOM_KEY_TRIGGER_MODE_SPEED_THRESHOLD;
        wasSpeedAtOrAboveThreshold = currentSpeed >= customKeySpeedThreshold;
        lastButtonState = -1;

        if (!VhalNative.isLibraryLoaded()) {
            AppLog.w(TAG, "Native library not loaded, skipping custom key configuration");
            return;
        }

        float nativeSpeedThreshold = customKeyTriggerMode == AppConfig.CUSTOM_KEY_TRIGGER_MODE_LONG_PRESS
                ? 0f
                : speedThreshold;
        VhalNative.configureCustomKey(speedPropId, buttonPropId, nativeSpeedThreshold);
    }

    /**
     * 启动连接和监听
     */
    public void start() {
        if (running) return;
        running = true;
        lastSignalState = -1;
        isPassDoorOpen = false;
        isLeftRearDoorOpen = false;
        isRightRearDoorOpen = false;
        connectThread = new Thread(this::connectLoop, "VehicleApiConnect");
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
     * 当前是否已连接
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 观察者是否存活（线程仍在运行）
     */
    public boolean isAlive() {
        return running && connectThread != null && connectThread.isAlive();
    }

    /**
     * 一次性连接测试（阻塞调用，用于 UI 状态检查）
     */
    public static boolean testConnection() {
        // 检查 native 库是否已加载
        if (!VhalNative.isLibraryLoaded()) {
            AppLog.w(TAG, "Native library not loaded, skipping gRPC connection test");
            return false;
        }
        
        try {
            java.net.Socket s = new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(VhalNative.getGrpcHost(), VhalNative.getGrpcPort()), 2000);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== Internal ====================

    private void connectLoop() {
        while (running) {
            try {
                AppLog.d(TAG, "Connecting to vehicle API service...");
                boolean ok = connect();
                if (ok) {
                    AppLog.d(TAG, "Connected, starting property stream");
                    notifyConnectionState(true);
                    streamProperties(); // blocks until disconnected
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Connection error: " + e.getMessage());
            }

            connected = false;
            notifyConnectionState(false);
            disconnect();

            if (!running) break;

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
            // 检查 native 库是否已加载
            if (!VhalNative.isLibraryLoaded()) {
                AppLog.w(TAG, "Native library not loaded, skipping VHAL connection");
                return false;
            }
            
            // 构建连接，附带 session_id 和 client_id metadata
            String sessionId = UUID.randomUUID().toString();
            Metadata headers = new Metadata();
            headers.put(
                    Metadata.Key.of("session_id", Metadata.ASCII_STRING_MARSHALLER),
                    sessionId
            );
            headers.put(
                    Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER),
                    "evcam_signal"
            );

            grpcChannel = OkHttpChannelBuilder.forAddress(VhalNative.getGrpcHost(), VhalNative.getGrpcPort())
                    .usePlaintext()
                    .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                    .build();

            connected = true;
            AppLog.d(TAG, "Channel created, session_id=" + sessionId);
            return true;
        } catch (UnsatisfiedLinkError e) {
            AppLog.e(TAG, "Native method not found: " + e.getMessage());
            return false;
        } catch (Exception e) {
            AppLog.e(TAG, "Connect failed: " + e.getMessage());
            disconnect();
            return false;
        }
    }

    private void disconnect() {
        connected = false;
        if (grpcChannel != null) {
            try {
                grpcChannel.shutdown();
                if (!grpcChannel.awaitTermination(2, TimeUnit.SECONDS)) {
                    grpcChannel.shutdownNow();
                }
            } catch (Exception ignored) {
                try { grpcChannel.shutdownNow(); } catch (Exception ignored2) {}
            }
            grpcChannel = null;
        }
    }

    /**
     * 开始属性流监听（阻塞直到断开或出错）
     */
    private void streamProperties() {
        if (grpcChannel == null) return;

        // 使用 CountDownLatch 等待流结束
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final boolean[] streamError = {false};

        try {
            MethodDescriptor<byte[], byte[]> streamMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                    .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                    .setFullMethodName(VhalNative.getStreamMethod())
                    .setRequestMarshaller(ByteMarshaller.INSTANCE)
                    .setResponseMarshaller(ByteMarshaller.INSTANCE)
                    .build();

            var call = grpcChannel.newCall(streamMethod, CallOptions.DEFAULT);

            ClientCalls.asyncServerStreamingCall(call, new byte[0], new StreamObserver<byte[]>() {
                @Override
                public void onNext(byte[] value) {
                    try {
                        processPropertyBatch(value);
                    } catch (Exception e) {
                        AppLog.e(TAG, "Failed to process property batch: " + e.getMessage());
                    }
                }

                @Override
                public void onError(Throwable t) {
                    AppLog.e(TAG, "Property stream error: " + t.getMessage());
                    streamError[0] = true;
                    latch.countDown();
                }

                @Override
                public void onCompleted() {
                    AppLog.d(TAG, "Property stream completed");
                    latch.countDown();
                }
            });

            // 请求服务器推送所有当前属性值
            // 服务器通过 channel metadata 中的 session_id 关联此请求和 stream
            // 延迟发送，等待 stream 在服务端注册完成；失败后重试
            new Thread(() -> {
                final int MAX_RETRIES = 3;
                final long INITIAL_DELAY_MS = 500;
                
                for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                    try {
                        Thread.sleep(attempt == 1 ? INITIAL_DELAY_MS : INITIAL_DELAY_MS * attempt);
                    } catch (InterruptedException e) {
                        return;
                    }
                    
                    if (grpcChannel == null || !running) return;
                    
                    try {
                        MethodDescriptor<byte[], byte[]> sendAllMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                                .setType(MethodDescriptor.MethodType.UNARY)
                                .setFullMethodName(VhalNative.getSendAllMethod())
                                .setRequestMarshaller(ByteMarshaller.INSTANCE)
                                .setResponseMarshaller(ByteMarshaller.INSTANCE)
                                .build();
                        var sendCall = grpcChannel.newCall(sendAllMethod, CallOptions.DEFAULT);
                        ClientCalls.blockingUnaryCall(sendCall, new byte[0]);
                        AppLog.d(TAG, "Requested all property values to stream (attempt " + attempt + ")");
                        return;
                    } catch (UnsatisfiedLinkError e) {
                        AppLog.e(TAG, "Native method not found: " + e.getMessage());
                        return;
                    } catch (Exception e) {
                        AppLog.w(TAG, "SendAll attempt " + attempt + "/" + MAX_RETRIES + " failed: " + e.getMessage());
                    }
                }
                AppLog.e(TAG, "SendAll exhausted all retries");
            }, "VehicleApiSendAll").start();

            // 等待流结束（带超时，防止半开连接卡死 reconnect 循环）
            if (!latch.await(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                AppLog.w(TAG, "Stream idle timeout (" + STREAM_TIMEOUT_MS + "ms), forcing reconnect");
            }

        } catch (UnsatisfiedLinkError e) {
            AppLog.e(TAG, "Native method not found: " + e.getMessage());
        } catch (Exception e) {
            AppLog.e(TAG, "Stream setup failed: " + e.getMessage());
        }
    }

    /**
     * 处理一批属性值更新（由 native 层解码）
     */
    private void processPropertyBatch(byte[] data) {
        if (!VhalNative.isLibraryLoaded()) {
            AppLog.w(TAG, "Native library not loaded, skipping property batch processing");
            return;
        }
        
        int[] events;
        try {
            events = VhalNative.decode(data);
        } catch (UnsatisfiedLinkError e) {
            AppLog.e(TAG, "Native method not found: " + e.getMessage());
            return;
        }
        
        if (events == null || events.length < 1) return;

        int numEvents = events[0];
        for (int i = 0; i < numEvents; i++) {
            int offset = 1 + i * 3;
            if (offset + 2 >= events.length) break;
            int type = events[offset];
            int p1 = events[offset + 1];
            int p2 = events[offset + 2];

            switch (type) {
                case VhalNative.EVT_TURN_SIGNAL:
                    handleTurnSignalEvent(p1);
                    break;
                case VhalNative.EVT_DOOR_OPEN:
                    handleDoorPositionEvent(p1, true);
                    break;
                case VhalNative.EVT_DOOR_CLOSE:
                    handleDoorPositionEvent(p1, false);
                    break;
                case VhalNative.EVT_SPEED:
                    handleSpeedEvent(Float.intBitsToFloat(p1));
                    break;
                case VhalNative.EVT_CUSTOM_KEY:
                    handleCustomKeyEvent(p1);
                    break;
            }
        }
    }

    /**
     * 处理车速事件。速度阈值模式下，仅在从低于阈值跨到达到阈值时触发一次。
     */
    private void handleSpeedEvent(float speed) {
        currentSpeed = speed;
        if (customKeyTriggerMode != AppConfig.CUSTOM_KEY_TRIGGER_MODE_SPEED_THRESHOLD) return;

        boolean atOrAboveThreshold = speed >= customKeySpeedThreshold;
        if (atOrAboveThreshold && !wasSpeedAtOrAboveThreshold) {
            AppLog.d(TAG, "Custom key speed threshold reached, speed=" + speed
                    + ", threshold=" + customKeySpeedThreshold);
            notifyCustomKeyTriggered(AppConfig.CUSTOM_KEY_TRIGGER_MODE_SPEED_THRESHOLD);
        }
        wasSpeedAtOrAboveThreshold = atOrAboveThreshold;
    }

    /**
     * 处理转向灯事件
     */
    private void handleTurnSignalEvent(int direction) {
        if (direction == lastSignalState) return;

        AppLog.d(TAG, "Turn signal changed: " + lastSignalState + " -> " + direction);

        int previousDirection = lastSignalState;
        lastSignalState = direction;

        mainHandler.post(() -> {
            if (listener == null) return;

            switch (direction) {
                case VhalNative.DIR_LEFT:
                    listener.onTurnSignal("left", true);
                    break;
                case VhalNative.DIR_RIGHT:
                    listener.onTurnSignal("right", true);
                    break;
                case VhalNative.DIR_NONE:
                    if (previousDirection == VhalNative.DIR_LEFT) {
                        listener.onTurnSignal("left", false);
                    } else if (previousDirection == VhalNative.DIR_RIGHT) {
                        listener.onTurnSignal("right", false);
                    }
                    break;
            }
        });
    }

    /**
     * 处理车门位置事件
     * 多门逻辑: 右侧摄像头仅在副驾 AND 右后门都关闭时才触发 onDoorClose
     */
    private void handleDoorPositionEvent(int doorPos, boolean isOpen) {
        AppLog.d(TAG, "Door event: pos=" + doorPos + ", open=" + isOpen);

        if (doorListener == null) return;

        switch (doorPos) {
            case VhalNative.DOOR_FL:
                AppLog.d(TAG, "Driver door state change, ignoring");
                break;
            case VhalNative.DOOR_FR:
                handleDoorSideEvent(isOpen, "right", true);
                break;
            case VhalNative.DOOR_RL:
                handleDoorSideEvent(isOpen, "left", false);
                break;
            case VhalNative.DOOR_RR:
                handleDoorSideEvent(isOpen, "right", false);
                break;
        }
    }

    /**
     * 处理单个车门事件的辅助方法
     */
    private void handleDoorSideEvent(boolean isOpen, String side, boolean isPassenger) {
        mainHandler.post(() -> {
            if (doorListener == null) return;
            if (isOpen) {
                if (isPassenger) isPassDoorOpen = true;
                else if ("left".equals(side)) isLeftRearDoorOpen = true;
                else isRightRearDoorOpen = true;
                doorListener.onDoorOpen(side);
            } else {
                if (isPassenger) {
                    isPassDoorOpen = false;
                    if (!isRightRearDoorOpen) doorListener.onDoorClose("right");
                } else if ("left".equals(side)) {
                    isLeftRearDoorOpen = false;
                    doorListener.onDoorClose("left");
                } else {
                    isRightRearDoorOpen = false;
                    if (!isPassDoorOpen) doorListener.onDoorClose("right");
                }
            }
        });
    }

    /**
     * 处理定制键按钮事件
     */
    private void handleCustomKeyEvent(int buttonState) {
        if (customKeyTriggerMode == AppConfig.CUSTOM_KEY_TRIGGER_MODE_LONG_PRESS
                && buttonState == CUSTOM_KEY_LONG_PRESS_VALUE
                && lastButtonState != CUSTOM_KEY_LONG_PRESS_VALUE) {
            AppLog.d(TAG, "Custom key long press triggered, value=" + buttonState);
            notifyCustomKeyTriggered(AppConfig.CUSTOM_KEY_TRIGGER_MODE_LONG_PRESS);
        }
        lastButtonState = buttonState;
    }

    private void notifyCustomKeyTriggered(int triggerMode) {
        if (customKeyListener == null) return;
        mainHandler.post(() -> {
            if (customKeyListener != null) {
                customKeyListener.onCustomKeyTriggered(triggerMode);
            }
        });
    }

    private void notifyConnectionState(boolean isConnected) {
        mainHandler.post(() -> {
            if (listener != null) {
                listener.onConnectionStateChanged(isConnected);
            }
            if (doorListener != null) {
                doorListener.onConnectionStateChanged(isConnected);
            }
        });
    }

    // ==================== ByteMarshaller ====================

    /** gRPC marshaller that passes raw bytes (same as EVCC's approach) */
    private enum ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        INSTANCE;

        @Override
        public InputStream stream(byte[] value) {
            return new ByteArrayInputStream(value);
        }

        @Override
        public byte[] parse(InputStream stream) {
            try {
                // 不使用 readAllBytes()，Android 11 (API 30) 不支持该方法
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = stream.read(buf)) != -1) {
                    baos.write(buf, 0, len);
                }
                return baos.toByteArray();
            } catch (Exception e) {
                return new byte[0];
            }
        }
    }

}
