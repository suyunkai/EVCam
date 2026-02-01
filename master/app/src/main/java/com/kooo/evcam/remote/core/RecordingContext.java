package com.kooo.evcam.remote.core;

import java.util.ArrayList;
import java.util.List;

/**
 * 录制上下文
 * 封装一次远程录制任务的所有状态信息
 */
public class RecordingContext {
    
    private final ChatIdentifier chatId;
    private final int durationSeconds;
    private String timestamp;  // 当前时间戳（可能因 Watchdog 重建而更新）
    private final List<String> allTimestamps = new ArrayList<>();  // 所有使用过的时间戳（用于上传时查找所有文件）
    
    // 状态标志
    private boolean wasManualRecordingBefore = false;
    private boolean isCompleted = false;
    private boolean isCancelled = false;
    
    // 错误信息
    private String errorMessage = null;
    
    public RecordingContext(ChatIdentifier chatId, int durationSeconds, String timestamp) {
        this.chatId = chatId;
        this.durationSeconds = durationSeconds;
        this.timestamp = timestamp;
        this.allTimestamps.add(timestamp);  // 初始时间戳也加入列表
    }
    
    // ==================== Getters ====================
    
    public ChatIdentifier getChatId() {
        return chatId;
    }
    
    public int getDurationSeconds() {
        return durationSeconds;
    }
    
    public String getTimestamp() {
        return timestamp;
    }
    
    public boolean wasManualRecordingBefore() {
        return wasManualRecordingBefore;
    }
    
    public boolean isCompleted() {
        return isCompleted;
    }
    
    public boolean isCancelled() {
        return isCancelled;
    }
    
    public String getErrorMessage() {
        return errorMessage;
    }
    
    public RemotePlatform getPlatform() {
        return chatId.getPlatform();
    }
    
    // ==================== Setters ====================
    
    public void setWasManualRecordingBefore(boolean wasManualRecordingBefore) {
        this.wasManualRecordingBefore = wasManualRecordingBefore;
    }
    
    public void setCompleted(boolean completed) {
        this.isCompleted = completed;
    }
    
    public void setCancelled(boolean cancelled) {
        this.isCancelled = cancelled;
    }
    
    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
    
    /**
     * 更新时间戳（Watchdog 重建录制后调用）
     * 同时将新时间戳加入历史列表，以便上传时能找到所有录制文件
     */
    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
        if (!allTimestamps.contains(timestamp)) {
            allTimestamps.add(timestamp);
        }
    }
    
    /**
     * 获取所有使用过的时间戳（包括 Watchdog 重建后的新时间戳）
     * 用于上传时查找所有录制的文件
     */
    public List<String> getAllTimestamps() {
        return new ArrayList<>(allTimestamps);
    }
    
    @Override
    public String toString() {
        return "RecordingContext{" +
                "platform=" + getPlatform().getDisplayName() +
                ", chatId=" + chatId.getId() +
                ", duration=" + durationSeconds + "s" +
                ", timestamp=" + timestamp +
                ", wasManualBefore=" + wasManualRecordingBefore +
                '}';
    }
}
