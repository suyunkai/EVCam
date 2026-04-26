package com.kooo.evcam.v2.service;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.kooo.evcam.VhalNative;

import io.grpc.ManagedChannel;

public final class V2VhalTurnSignalObserver {
    public interface Listener { void onTurnSignal(String side, boolean on); }

    private static final String TAG = "V2VhalTurnSignal";
    private static final int EVT_TURN_SIGNAL = 1;
    private static final long RECONNECT_DELAY_MS = 3000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final int propId;
    private final int leftValue;
    private final int rightValue;
    private final int offValue;
    private final Listener listener;
    private final V2VhalStreamClient streamClient = new V2VhalStreamClient(
            TAG,
            "connected session_id=",
            "Stream idle timeout, reconnecting",
            "Requested all property values (attempt ",
            ""
    );
    private ManagedChannel channel;
    private Thread thread;
    private volatile boolean running;
    private volatile int lastState = Integer.MIN_VALUE;
    private volatile String lastSide;

    public V2VhalTurnSignalObserver(int propId, int leftValue, int rightValue, int offValue, Listener listener) {
        this.propId = propId;
        this.leftValue = leftValue;
        this.rightValue = rightValue;
        this.offValue = offValue;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        if (!VhalNative.isLibraryLoaded()) {
            Log.w(TAG, "Native library not loaded, turn signal observer disabled");
            return;
        }
        running = true;
        lastState = Integer.MIN_VALUE;
        lastSide = null;
        Log.d(TAG, "start propId=" + propId + " left=" + leftValue + " right=" + rightValue + " off=" + offValue);
        thread = new Thread(this::connectLoop, "V2VhalTurnSignal");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        disconnect();
        if (thread != null) {
            thread.interrupt();
            thread = null;
        }
    }

    private void connectLoop() {
        while (running) {
            try {
                if (connect()) streamProperties();
            } catch (Throwable error) {
                Log.e(TAG, "Connection error: " + error.getMessage(), error);
            }
            disconnect();
            if (!running) break;
            try { Thread.sleep(RECONNECT_DELAY_MS); } catch (InterruptedException ignored) { break; }
        }
    }

    private boolean connect() {
        try {
            V2VhalStreamClient.Connection connection = streamClient.connect();
            channel = connection.channel;
            Log.d(TAG, "connected session_id=" + connection.sessionId + " propId=" + propId);
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "connect failed: " + error.getMessage(), error);
            disconnect();
            return false;
        }
    }

    private void disconnect() {
        ManagedChannel old = channel;
        channel = null;
        streamClient.disconnect(old);
    }

    private void streamProperties() throws InterruptedException {
        streamClient.streamProperties(VhalNative.getStreamMethod(), channel, new V2VhalStreamClient.Callback() {
            @Override public void onBatch(byte[] data) { processPropertyBatch(data); }
            @Override public void onStreamCompleted() { Log.d(TAG, "Property stream completed"); }
            @Override public void onStreamError(Throwable t) { Log.e(TAG, "Property stream error: " + t.getMessage(), t); }
        });
    }

    private void processPropertyBatch(byte[] data) {
        int[] events;
        try { events = VhalNative.decode(data); } catch (Throwable error) { Log.e(TAG, "decode failed: " + error.getMessage(), error); return; }
        if (events == null || events.length < 1) return;
        int count = events[0];
        for (int i = 0; i < count; i++) {
            int offset = 1 + i * 3;
            if (offset + 2 >= events.length) break;
            int type = events[offset];
            int value = events[offset + 1];
            int eventPropId = events[offset + 2];
            if (type == EVT_TURN_SIGNAL) {
                Log.d(TAG, "turn signal event value=" + value + " p2=" + eventPropId + " configuredPropId=" + propId);
                if (eventPropId > 0 && eventPropId != propId) {
                    Log.d(TAG, "ignore turn signal event for propId=" + eventPropId + ", expected=" + propId);
                    continue;
                }
                handleTurnSignalValue(value);
            }
        }
    }

    private void handleTurnSignalValue(int value) {
        if (value == lastState) return;
        String previousSide = lastSide;
        lastState = value;
        String side = value == leftValue ? "left" : value == rightValue ? "right" : null;
        Log.d(TAG, "turn signal value=" + value + " side=" + side + " propId=" + propId);
        if (side != null) {
            lastSide = side;
            mainHandler.post(() -> listener.onTurnSignal(side, true));
        } else if (value == offValue && previousSide != null) {
            lastSide = null;
            mainHandler.post(() -> listener.onTurnSignal(previousSide, false));
        }
    }

}
