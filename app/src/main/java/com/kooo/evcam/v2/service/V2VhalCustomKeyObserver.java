package com.kooo.evcam.v2.service;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.kooo.evcam.VhalNative;

import io.grpc.ManagedChannel;

public final class V2VhalCustomKeyObserver {
    public interface Listener { void onCustomKeyLongPress(); }

    private static final String TAG = "V2VhalCustomKey";
    private static final int DEFAULT_SPEED_PROP_ID = 291504647;
    private static final int LONG_PRESS_VALUE = 4;
    private static final long RECONNECT_DELAY_MS = 3000;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private final int buttonPropId;
    private final V2VhalStreamClient streamClient = new V2VhalStreamClient(
            TAG,
            "Channel created, session_id=",
            "Stream idle timeout (120000ms), forcing reconnect",
            "Requested all property values to stream (attempt ",
            "SendAll exhausted all retries"
    );
    private ManagedChannel channel;
    private Thread thread;
    private volatile boolean running;
    private volatile int lastButtonState = -1;

    public V2VhalCustomKeyObserver(int buttonPropId, Listener listener) {
        this.buttonPropId = buttonPropId;
        this.listener = listener;
    }

    public synchronized void start() {
        if (running) return;
        if (!VhalNative.isLibraryLoaded()) {
            Log.w(TAG, "Native library not loaded, custom key observer disabled");
            return;
        }
        running = true;
        lastButtonState = -1;
        Log.d(TAG, "configure custom key buttonPropId=" + buttonPropId);
        VhalNative.configureCustomKey(DEFAULT_SPEED_PROP_ID, buttonPropId, 0f);
        thread = new Thread(this::connectLoop, "V2VhalCustomKey");
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
                Log.d(TAG, "Connecting to vehicle API service...");
                if (connect()) {
                    Log.d(TAG, "Connected, starting property stream");
                    streamProperties();
                }
            } catch (Throwable error) {
                Log.e(TAG, "Connection error: " + error.getMessage(), error);
            }
            disconnect();
            if (!running) break;
            try {
                Log.d(TAG, "Reconnecting in " + RECONNECT_DELAY_MS + "ms...");
                Thread.sleep(RECONNECT_DELAY_MS);
            } catch (InterruptedException ignored) {
                break;
            }
        }
    }

    private boolean connect() {
        try {
            channel = streamClient.connect().channel;
            return true;
        } catch (Throwable error) {
            Log.e(TAG, "Connect failed: " + error.getMessage(), error);
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
        if (!VhalNative.isLibraryLoaded()) {
            Log.w(TAG, "Native library not loaded, skipping property batch processing");
            return;
        }
        int[] events;
        try { events = VhalNative.decode(data); } catch (Throwable error) { Log.e(TAG, "Failed to decode property batch: " + error.getMessage(), error); return; }
        if (events == null || events.length < 1) return;
        int count = events[0];
        for (int i = 0; i < count; i++) {
            int offset = 1 + i * 3;
            if (offset + 2 >= events.length) break;
            int type = events[offset];
            int p1 = events[offset + 1];
            int p2 = events[offset + 2];
            if (type == VhalNative.EVT_CUSTOM_KEY) {
                Log.d(TAG, "Decoded custom key event p1=" + p1 + " p2=" + p2);
                handleButtonState(p1);
            }
        }
    }

    private void handleButtonState(int state) {
        if (state == LONG_PRESS_VALUE && lastButtonState != LONG_PRESS_VALUE) {
            Log.d(TAG, "Custom key long press triggered, value=" + state);
            mainHandler.post(listener::onCustomKeyLongPress);
        }
        lastButtonState = state;
    }

}
