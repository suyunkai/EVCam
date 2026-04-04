package com.kooo.evcam.recording;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.config.RecordingConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 录制控制器
 * 管理录制状态和事件回调
 * 按照D:\yuan参考实现重构
 */
public class RecordingController {
    private static final String TAG = "RecordingController";

    // 录制状态
    public enum RecordingState {
        IDLE,           // 空闲
        INITIALIZING,   // 初始化中
        RECORDING,      // 录制中
        PAUSED,         // 暂停
        STOPPING,       // 停止中
        ERROR           // 错误
    }

    private RecordingState currentState = RecordingState.IDLE;
    private final Context context;
    private final RecordingConfig recordingConfig;
    private final Handler mainHandler;

    // 回调监听器列表
    private final List<RecordingStateListener> stateListeners = new ArrayList<>();
    private final List<RecordingErrorListener> errorListeners = new ArrayList<>();
    private final List<RecordingProgressListener> progressListeners = new ArrayList<>();

    // 录制统计信息
    private long recordingStartTime = 0;
    private long totalRecordedBytes = 0;
    private int currentSegmentIndex = 0;

    public interface RecordingStateListener {
        void onStateChanged(RecordingState newState, RecordingState oldState);
    }

    public interface RecordingErrorListener {
        void onError(int errorCode, String errorMessage);
    }

    public interface RecordingProgressListener {
        void onProgress(long durationMs, long bytesRecorded);
    }

    public RecordingController(Context context, RecordingConfig recordingConfig) {
        this.context = context.getApplicationContext();
        this.recordingConfig = recordingConfig;
        this.mainHandler = new Handler(Looper.getMainLooper());
    }

    // ========== 状态管理 ==========

    public synchronized void setState(RecordingState newState) {
        if (currentState != newState) {
            RecordingState oldState = currentState;
            currentState = newState;
            AppLog.d(TAG, "录制状态变化: " + oldState + " -> " + newState);
            notifyStateChanged(newState, oldState);
        }
    }

    public synchronized RecordingState getState() {
        return currentState;
    }

    public boolean isRecording() {
        return currentState == RecordingState.RECORDING;
    }

    public boolean isIdle() {
        return currentState == RecordingState.IDLE;
    }

    // ========== 录制控制 ==========

    public void startRecording() {
        if (currentState == RecordingState.RECORDING) {
            AppLog.w(TAG, "已经在录制中，忽略开始请求");
            return;
        }
        setState(RecordingState.INITIALIZING);
        recordingStartTime = System.currentTimeMillis();
        currentSegmentIndex = 0;
    }

    public void stopRecording() {
        if (currentState == RecordingState.IDLE || currentState == RecordingState.STOPPING) {
            return;
        }
        setState(RecordingState.STOPPING);
    }

    public void pauseRecording() {
        if (currentState == RecordingState.RECORDING) {
            setState(RecordingState.PAUSED);
        }
    }

    public void resumeRecording() {
        if (currentState == RecordingState.PAUSED) {
            setState(RecordingState.RECORDING);
        }
    }

    // ========== 统计信息更新 ==========

    public void updateProgress(long bytesRecorded) {
        this.totalRecordedBytes = bytesRecorded;
        long durationMs = System.currentTimeMillis() - recordingStartTime;
        notifyProgress(durationMs, bytesRecorded);
    }

    public void onSegmentCompleted(int segmentIndex) {
        this.currentSegmentIndex = segmentIndex;
        AppLog.d(TAG, "分段录制完成: " + segmentIndex);
    }

    public void onError(int errorCode, String errorMessage) {
        AppLog.e(TAG, "录制错误 [" + errorCode + "]: " + errorMessage);
        setState(RecordingState.ERROR);
        notifyError(errorCode, errorMessage);
    }

    // ========== 监听器管理 ==========

    public void addStateListener(RecordingStateListener listener) {
        if (!stateListeners.contains(listener)) {
            stateListeners.add(listener);
        }
    }

    public void removeStateListener(RecordingStateListener listener) {
        stateListeners.remove(listener);
    }

    public void addErrorListener(RecordingErrorListener listener) {
        if (!errorListeners.contains(listener)) {
            errorListeners.add(listener);
        }
    }

    public void removeErrorListener(RecordingErrorListener listener) {
        errorListeners.remove(listener);
    }

    public void addProgressListener(RecordingProgressListener listener) {
        if (!progressListeners.contains(listener)) {
            progressListeners.add(listener);
        }
    }

    public void removeProgressListener(RecordingProgressListener listener) {
        progressListeners.remove(listener);
    }

    // ========== 通知方法 ==========

    private void notifyStateChanged(RecordingState newState, RecordingState oldState) {
        mainHandler.post(() -> {
            for (RecordingStateListener listener : stateListeners) {
                try {
                    listener.onStateChanged(newState, oldState);
                } catch (Exception e) {
                    AppLog.e(TAG, "状态监听器回调异常", e);
                }
            }
        });
    }

    private void notifyError(int errorCode, String errorMessage) {
        mainHandler.post(() -> {
            for (RecordingErrorListener listener : errorListeners) {
                try {
                    listener.onError(errorCode, errorMessage);
                } catch (Exception e) {
                    AppLog.e(TAG, "错误监听器回调异常", e);
                }
            }
        });
    }

    private void notifyProgress(long durationMs, long bytesRecorded) {
        mainHandler.post(() -> {
            for (RecordingProgressListener listener : progressListeners) {
                try {
                    listener.onProgress(durationMs, bytesRecorded);
                } catch (Exception e) {
                    AppLog.e(TAG, "进度监听器回调异常", e);
                }
            }
        });
    }

    // ========== 获取统计信息 ==========

    public long getRecordingDurationMs() {
        if (recordingStartTime == 0) {
            return 0;
        }
        return System.currentTimeMillis() - recordingStartTime;
    }

    public long getTotalRecordedBytes() {
        return totalRecordedBytes;
    }

    public int getCurrentSegmentIndex() {
        return currentSegmentIndex;
    }

    public void reset() {
        currentState = RecordingState.IDLE;
        recordingStartTime = 0;
        totalRecordedBytes = 0;
        currentSegmentIndex = 0;
    }
}
