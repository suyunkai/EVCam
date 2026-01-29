package com.kooo.evcam.camera;

import java.util.List;

/**
 * 录制回调接口
 */
public interface RecordCallback {
    /**
     * 录制开始
     */
    void onRecordStart(String cameraId);

    /**
     * 录制停止
     */
    void onRecordStop(String cameraId);

    /**
     * 录制错误
     */
    void onRecordError(String cameraId, String error);

    /**
     * 预分段切换（在停止当前 MediaRecorder 之前调用）
     * 用于通知外部暂停 CaptureSession 的录制输出，避免向即将释放的 Surface 发送帧
     * 
     * @param cameraId 相机ID
     * @param currentSegmentIndex 当前分段索引（即将结束的分段）
     */
    void onPrepareSegmentSwitch(String cameraId, int currentSegmentIndex);

    /**
     * 分段切换（需要重新配置相机会话）
     * @param cameraId 相机ID
     * @param newSegmentIndex 新的分段索引
     * @param completedFilePath 已完成的文件路径（可用于传输到最终目录）
     */
    void onSegmentSwitch(String cameraId, int newSegmentIndex, String completedFilePath);

    /**
     * 损坏文件被删除
     * @param cameraId 相机ID
     * @param deletedFiles 被删除的文件名列表
     */
    void onCorruptedFilesDeleted(String cameraId, List<String> deletedFiles);

    /**
     * 请求重建录制（Watchdog 触发）
     * 当检测到录制异常（连续无写入或首次写入超时）时调用
     * 外部应该停止当前录制并重新开始，可选切换到 Codec 模式
     * 
     * @param cameraId 相机ID
     * @param reason 重建原因（"no_write" 或 "first_write_timeout"）
     */
    void onRecordingRebuildRequested(String cameraId, String reason);

    /**
     * 首次数据写入成功
     * 当检测到录制器首次成功写入数据时调用
     * 用于通知外部录制已真正开始，可以开始计时（分段计时、钉钉录制计时等）
     * 
     * @param cameraId 相机ID
     */
    void onFirstDataWritten(String cameraId);
}
