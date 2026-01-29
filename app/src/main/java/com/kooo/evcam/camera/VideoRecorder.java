package com.kooo.evcam.camera;


import com.kooo.evcam.AppLog;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 视频录制管理类
 */
public class VideoRecorder {
    private static final String TAG = "VideoRecorder";

    /**
     * 录制状态枚举 - 用于解决分段切换和停止录制的竞态条件
     */
    public enum RecordingState {
        IDLE,                    // 空闲状态
        PREPARING,               // 准备中
        RECORDING,               // 录制中
        SWITCHING_SEGMENT,       // 分段切换中（此状态下禁止停止）
        STOPPING                 // 停止中
    }

    private final String cameraId;
    private MediaRecorder mediaRecorder;
    private Surface cachedSurface;  // 缓存的录制 Surface，确保整个录制周期使用同一个对象
    private RecordCallback callback;
    private final AtomicBoolean isRecording = new AtomicBoolean(false);  // 使用 AtomicBoolean 确保线程安全
    private volatile RecordingState state = RecordingState.IDLE;  // 录制状态
    private final Object stateLock = new Object();  // 状态锁
    private boolean waitingForSessionReconfiguration = false;  // 等待会话重新配置
    private String currentFilePath;
    
    // 录制参数（可配置）
    private int videoBitrate = 3000000;  // 默认 3Mbps
    private int videoFrameRate = 30;     // 默认 30fps

    // 分段录制相关
    private long segmentDurationMs = 60000;  // 分段时长，默认1分钟，可通过 setSegmentDuration 配置
    private static final long SEGMENT_DURATION_COMPENSATION_MS = 0;  // 分段时长补偿（H3修复后定时器更精确，不再需要补偿）
    private static final long FILE_SIZE_CHECK_INTERVAL_MS = 3000;  // 每3秒检查一次文件大小（加快检测）
    private static final long FIRST_CHECK_DELAY_MS = 500;  // 首次检查延迟（更快检测首次写入）
    private static final long MIN_VALID_FILE_SIZE = 10 * 1024;  // 最小有效文件大小 10KB
    
    // 使用独立的后台线程处理分段和文件 I/O 操作，避免阻塞主线程导致 ANR
    private HandlerThread segmentThread;
    private Handler segmentHandler;
    
    private Runnable segmentRunnable;
    private Runnable fileSizeCheckRunnable;  // 文件大小检查任务
    private Runnable pendingSegmentSwitchRunnable;  // 待执行的分段切换任务（用于取消）
    private int segmentIndex = 0;
    private String saveDirectory;  // 保存目录
    private String cameraPosition;  // 摄像头位置（front/back/left/right）
    private int recordWidth;
    private int recordHeight;
    private long lastFileSize = 0;  // 上次检查的文件大小
    private List<String> recordedFilePaths = new ArrayList<>();  // 本次录制的所有文件路径

    // Watchdog 相关：检测无写入并请求重建
    private static final int WATCHDOG_NO_WRITE_THRESHOLD = 3;  // 连续 N 次无写入则触发重建
    private static final long FIRST_WRITE_TIMEOUT_MS = 10000;  // 首次写入超时（10秒）
    private int noWriteCount = 0;  // 连续无写入计数
    private boolean hasFirstWrite = false;  // 是否已有首次写入
    private long recordingStartTime = 0;  // 录制开始时间
    private Runnable firstWriteTimeoutRunnable;  // 首次写入超时检查任务

    public VideoRecorder(String cameraId) {
        this.cameraId = cameraId;
        // 创建独立的后台线程用于分段处理和文件 I/O 操作
        segmentThread = new HandlerThread("VideoRecorder-Segment-" + cameraId);
        segmentThread.start();
        this.segmentHandler = new Handler(segmentThread.getLooper());
    }

    public void setCallback(RecordCallback callback) {
        this.callback = callback;
    }

    /**
     * 设置分段时长
     * @param durationMs 分段时长（毫秒）
     */
    public void setSegmentDuration(long durationMs) {
        this.segmentDurationMs = durationMs;
        AppLog.d(TAG, "Camera " + cameraId + " segment duration set to " + (durationMs / 1000) + " seconds");
    }

    /**
     * 设置录制码率
     * @param bitrate 码率（bps）
     */
    public void setVideoBitrate(int bitrate) {
        this.videoBitrate = bitrate;
        AppLog.d(TAG, "Camera " + cameraId + " bitrate set to " + (bitrate / 1000) + " Kbps");
    }

    /**
     * 设置录制帧率
     * @param frameRate 帧率（fps）
     */
    public void setVideoFrameRate(int frameRate) {
        this.videoFrameRate = frameRate;
        AppLog.d(TAG, "Camera " + cameraId + " frame rate set to " + frameRate + " fps");
    }

    /**
     * 获取当前配置的码率
     */
    public int getVideoBitrate() {
        return videoBitrate;
    }

    /**
     * 获取当前配置的帧率
     */
    public int getVideoFrameRate() {
        return videoFrameRate;
    }

    /**
     * 获取分段时长（毫秒）
     */
    public long getSegmentDuration() {
        return segmentDurationMs;
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    /**
     * 检查录制器是否已准备好（但未开始录制）
     * 用于判断是否可以启动初始录制
     */
    public boolean isPrepared() {
        return mediaRecorder != null && cachedSurface != null && !isRecording.get() && !waitingForSessionReconfiguration;
    }

    public Surface getSurface() {
        // 优先返回缓存的 Surface，确保传给 CameraCaptureSession 的是同一个对象
        if (cachedSurface != null) {
            AppLog.d(TAG, "Camera " + cameraId + " getSurface (cached): " + cachedSurface + ", isValid=" + cachedSurface.isValid());
            return cachedSurface;
        }
        // 如果没有缓存，尝试从 MediaRecorder 获取并缓存
        if (mediaRecorder != null) {
            Surface surface = mediaRecorder.getSurface();
            if (surface != null) {
                cachedSurface = surface;  // 缓存起来
                AppLog.d(TAG, "Camera " + cameraId + " getSurface (new, now cached): " + surface + ", isValid=" + surface.isValid());
            } else {
                AppLog.w(TAG, "Camera " + cameraId + " getSurface returned NULL");
            }
            return surface;
        }
        AppLog.w(TAG, "Camera " + cameraId + " getSurface: mediaRecorder is NULL");
        return null;
    }

    /**
     * 获取当前段索引
     */
    public int getCurrentSegmentIndex() {
        return segmentIndex;
    }

    /**
     * 获取当前文件路径
     */
    public String getCurrentFilePath() {
        return currentFilePath;
    }

    /**
     * 检查是否正在等待会话重新配置
     */
    public boolean isWaitingForSessionReconfiguration() {
        return waitingForSessionReconfiguration;
    }

    /**
     * 清除等待会话重新配置的标志
     */
    public void clearWaitingForSessionReconfiguration() {
        waitingForSessionReconfiguration = false;
    }

    /**
     * 准备录制器
     */
    private void prepareMediaRecorder(String filePath, int width, int height) throws IOException {
        mediaRecorder = new MediaRecorder();
        
        // 添加监听器以监控 MediaRecorder 状态（调试用）
        mediaRecorder.setOnInfoListener((mr, what, extra) -> {
            String info = "UNKNOWN";
            switch (what) {
                case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                    info = "MAX_DURATION_REACHED";
                    break;
                case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                    info = "MAX_FILESIZE_REACHED";
                    break;
                case MediaRecorder.MEDIA_RECORDER_INFO_UNKNOWN:
                    info = "INFO_UNKNOWN";
                    break;
            }
            AppLog.d(TAG, "Camera " + cameraId + " MediaRecorder INFO: " + info + " (what=" + what + ", extra=" + extra + ")");
        });
        
        mediaRecorder.setOnErrorListener((mr, what, extra) -> {
            String error = "UNKNOWN";
            switch (what) {
                case MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN:
                    error = "ERROR_UNKNOWN";
                    break;
                case MediaRecorder.MEDIA_ERROR_SERVER_DIED:
                    error = "SERVER_DIED";
                    break;
            }
            AppLog.e(TAG, "Camera " + cameraId + " MediaRecorder ERROR: " + error + " (what=" + what + ", extra=" + extra + ")");
        });
        
        mediaRecorder.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        mediaRecorder.setOutputFile(filePath);
        mediaRecorder.setVideoEncodingBitRate(videoBitrate);
        mediaRecorder.setVideoFrameRate(videoFrameRate);
        mediaRecorder.setVideoSize(width, height);
        mediaRecorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
        mediaRecorder.prepare();
        
        AppLog.d(TAG, "Camera " + cameraId + " MediaRecorder configured: " + width + "x" + height + 
                " @ " + videoFrameRate + "fps, " + (videoBitrate / 1000) + " Kbps");
        
        // 准备后立即缓存 Surface，确保整个录制周期使用同一个对象
        // 这对于某些车机平台很重要，因为 Camera2 API 可能无法识别不同的 Surface 包装对象
        cachedSurface = mediaRecorder.getSurface();
        if (cachedSurface != null) {
            AppLog.d(TAG, "Camera " + cameraId + " MediaRecorder Surface created and cached: " + cachedSurface + 
                    ", isValid=" + cachedSurface.isValid());
        } else {
            AppLog.e(TAG, "Camera " + cameraId + " MediaRecorder Surface is NULL after prepare!");
        }
    }

    /**
     * 准备录制器（不启动）
     */
    public boolean prepareRecording(String filePath, int width, int height) {
        if (isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " is already recording");
            return false;
        }

        // 先释放旧的 MediaRecorder（如果存在）
        releaseMediaRecorder();

        try {
            // 保存录制参数用于分段
            this.recordWidth = width;
            this.recordHeight = height;
            this.segmentIndex = 0;

            // 从文件路径中提取保存目录和摄像头位置
            File file = new File(filePath);
            this.saveDirectory = file.getParent();
            String fileName = file.getName();
            // 文件名格式：日期_时间_摄像头位置.mp4
            // 提取摄像头位置（最后一个下划线后的部分，去掉.mp4）
            int lastUnderscoreIndex = fileName.lastIndexOf('_');
            if (lastUnderscoreIndex > 0 && fileName.endsWith(".mp4")) {
                this.cameraPosition = fileName.substring(lastUnderscoreIndex + 1, fileName.length() - 4);
            } else {
                this.cameraPosition = "unknown";
            }

            // 清空并初始化本次录制的文件列表
            recordedFilePaths.clear();
            recordedFilePaths.add(filePath);

            // 使用传入的文件路径作为第一段
            prepareMediaRecorder(filePath, width, height);
            currentFilePath = filePath;
            AppLog.d(TAG, "Camera " + cameraId + " prepared recording to: " + filePath);
            return true;
        } catch (IOException e) {
            AppLog.e(TAG, "Failed to prepare recording for camera " + cameraId, e);
            releaseMediaRecorder();
            // 确保状态被重置
            isRecording.set(false);
            waitingForSessionReconfiguration = false;
            currentFilePath = null;
            segmentIndex = 0;
            if (callback != null) {
                callback.onRecordError(cameraId, e.getMessage());
            }
            return false;
        }
    }

    /**
     * 生成新的分段文件路径（使用当前时间戳）
     */
    private String generateSegmentPath() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String fileName = timestamp + "_" + cameraPosition + ".mp4";
        return new File(saveDirectory, fileName).getAbsolutePath();
    }

    /**
     * 启动录制（必须先调用 prepareRecording）
     */
    public boolean startRecording() {
        if (mediaRecorder == null) {
            AppLog.e(TAG, "Camera " + cameraId + " MediaRecorder not prepared");
            return false;
        }

        if (isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " is already recording");
            return false;
        }

        // 诊断：检查缓存的 Surface 状态
        if (cachedSurface == null) {
            AppLog.e(TAG, "Camera " + cameraId + " CRITICAL: Cached Surface is NULL before start!");
        } else if (!cachedSurface.isValid()) {
            AppLog.e(TAG, "Camera " + cameraId + " CRITICAL: Cached Surface is INVALID before start! Surface=" + cachedSurface);
        } else {
            AppLog.d(TAG, "Camera " + cameraId + " Cached Surface OK before start: " + cachedSurface + ", isValid=true");
        }

        try {
            AppLog.d(TAG, "Camera " + cameraId + " calling mediaRecorder.start()...");
            mediaRecorder.start();
            isRecording.set(true);
            lastFileSize = 0;  // 重置文件大小计数
            recordingStartTime = System.currentTimeMillis();  // 记录开始时间
            
            // 重置 Watchdog 状态
            noWriteCount = 0;
            hasFirstWrite = false;
            
            // 更新状态为 RECORDING
            synchronized (stateLock) {
                state = RecordingState.RECORDING;
            }
            
            AppLog.d(TAG, "Camera " + cameraId + " started recording segment " + segmentIndex);
            
            // 诊断：start() 后再次检查缓存的 Surface 状态（应该是同一个对象）
            if (cachedSurface != null) {
                AppLog.d(TAG, "Camera " + cameraId + " Cached Surface after start: " + cachedSurface + 
                        ", isValid=" + cachedSurface.isValid());
            }
            
            if (callback != null && segmentIndex == 0) {
                // 只在第一段时通知开始录制
                callback.onRecordStart(cameraId);
            }

            // 【重要】分段定时器延迟到首次写入后启动
            // 这样可以确保：
            // 1. 摄像头启动慢或需要修复时，用户只会感觉"启动慢"而不是录制空视频
            // 2. 钉钉指定时长录制时，实际录制时长是有效的
            // scheduleNextSegment() 将在 scheduleFileSizeCheck() 检测到首次写入时调用
            
            // 启动首次写入超时检查（每段都需要，用于检测录制是否正常）
            scheduleFirstWriteTimeout();
            
            // 启动文件大小检查（用于诊断 MediaRecorder 是否在接收帧）
            scheduleFileSizeCheck();

            return true;
        } catch (RuntimeException e) {
            AppLog.e(TAG, "Failed to start recording for camera " + cameraId, e);
            releaseMediaRecorder();
            // 失败时恢复到 IDLE 状态
            synchronized (stateLock) {
                state = RecordingState.IDLE;
            }
            if (callback != null) {
                callback.onRecordError(cameraId, e.getMessage());
            }
            return false;
        }
    }

    /**
     * 调度下一段录制
     * 
     * 注意：分段时长需要加上补偿时间，因为：
     * 1. MediaRecorder.start() 后需要时间初始化编码器
     * 2. MediaRecorder.stop() 时可能丢失正在编码的帧
     * 3. 这样可以确保实际录制的视频时长达到设定的分段时长
     */
    private void scheduleNextSegment() {
        // 取消之前的定时器
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
        }

        // 创建新的分段任务
        segmentRunnable = () -> {
            if (isRecording.get()) {
                AppLog.d(TAG, "Camera " + cameraId + " switching to next segment");
                switchToNextSegment();
            }
        };

        // 延迟执行（使用配置的分段时长 + 补偿时间）
        // 补偿编码器初始化延迟和停止时的帧丢失
        long actualDelayMs = segmentDurationMs + SEGMENT_DURATION_COMPENSATION_MS;
        segmentHandler.postDelayed(segmentRunnable, actualDelayMs);
        AppLog.d(TAG, "Camera " + cameraId + " scheduled next segment in " + (segmentDurationMs / 1000) + " seconds (actual delay: " + actualDelayMs + "ms)");
    }

    /**
     * 调度文件大小检查（含 Watchdog 逻辑）
     * 
     * Watchdog 机制：
     * - 每 3 秒检查一次文件大小
     * - 如果连续 3 次（9秒）无写入，触发重建请求
     * - 首次写入超时保护：录制开始后 10 秒内无写入也触发重建
     */
    private void scheduleFileSizeCheck() {
        // 取消之前的检查
        if (fileSizeCheckRunnable != null) {
            segmentHandler.removeCallbacks(fileSizeCheckRunnable);
        }

        fileSizeCheckRunnable = () -> {
            if (isRecording.get() && currentFilePath != null) {
                File file = new File(currentFilePath);
                long currentSize = file.exists() ? file.length() : 0;
                long sizeIncrease = currentSize - lastFileSize;
                
                // 检查是否有写入
                boolean hasWrite = (sizeIncrease > 0) || (currentSize > MIN_VALID_FILE_SIZE);
                
                if (hasWrite) {
                    // 有写入：重置计数器，标记首次写入
                    noWriteCount = 0;
                    if (!hasFirstWrite) {
                        hasFirstWrite = true;
                        AppLog.d(TAG, "Camera " + cameraId + " first write detected! Size: " + currentSize + " bytes");
                        // 取消首次写入超时检查
                        cancelFirstWriteTimeout();
                        
                        // 【核心改动】首次写入后才启动分段定时器
                        // 这确保了分段时长是"有效录制时长"而非"尝试录制时长"
                        scheduleNextSegment();
                        AppLog.d(TAG, "Camera " + cameraId + " segment timer started after first write");
                        
                        // 通知外部：首次写入成功，录制已真正开始
                        // 外部可以据此开始钉钉录制计时等
                        if (callback != null) {
                            callback.onFirstDataWritten(cameraId);
                        }
                    }
                    AppLog.d(TAG, "Camera " + cameraId + " file size check: " + currentSize + " bytes (" + (currentSize / 1024) + " KB), increase: " + sizeIncrease + " bytes");
                } else if (currentSize == 0 || sizeIncrease == 0) {
                    // 无写入：增加计数器
                    noWriteCount++;
                    
                    if (currentSize == 0) {
                        AppLog.e(TAG, "Camera " + cameraId + " ERROR: File size is 0! No frames received! (count: " + noWriteCount + "/" + WATCHDOG_NO_WRITE_THRESHOLD + ")");
                    } else {
                        AppLog.w(TAG, "Camera " + cameraId + " WARNING: File size not growing! Current: " + currentSize + " bytes (count: " + noWriteCount + "/" + WATCHDOG_NO_WRITE_THRESHOLD + ")");
                    }
                    
                    // Watchdog：连续 N 次无写入，触发重建
                    if (noWriteCount >= WATCHDOG_NO_WRITE_THRESHOLD) {
                        AppLog.e(TAG, "Camera " + cameraId + " WATCHDOG TRIGGERED: No write for " + (noWriteCount * FILE_SIZE_CHECK_INTERVAL_MS / 1000) + " seconds, requesting rebuild");
                        requestRecordingRebuild("no_write");
                        return;  // 停止检查，等待重建
                    }
                }
                
                lastFileSize = currentSize;
                
                // 继续下一次检查（首次写入前用快速间隔，之后用正常间隔）
                long nextDelay = hasFirstWrite ? FILE_SIZE_CHECK_INTERVAL_MS : FIRST_CHECK_DELAY_MS;
                segmentHandler.postDelayed(fileSizeCheckRunnable, nextDelay);
            }
        };

        // 首次检查使用更短的延迟，快速检测首次写入
        long initialDelay = hasFirstWrite ? FILE_SIZE_CHECK_INTERVAL_MS : FIRST_CHECK_DELAY_MS;
        segmentHandler.postDelayed(fileSizeCheckRunnable, initialDelay);
    }

    /**
     * 调度首次写入超时检查
     */
    private void scheduleFirstWriteTimeout() {
        // 取消之前的超时检查
        cancelFirstWriteTimeout();
        
        firstWriteTimeoutRunnable = () -> {
            if (isRecording.get() && !hasFirstWrite) {
                AppLog.e(TAG, "Camera " + cameraId + " FIRST WRITE TIMEOUT: No data written in " + (FIRST_WRITE_TIMEOUT_MS / 1000) + " seconds, requesting rebuild");
                requestRecordingRebuild("first_write_timeout");
            }
        };
        
        segmentHandler.postDelayed(firstWriteTimeoutRunnable, FIRST_WRITE_TIMEOUT_MS);
        AppLog.d(TAG, "Camera " + cameraId + " first write timeout scheduled: " + (FIRST_WRITE_TIMEOUT_MS / 1000) + " seconds");
    }

    /**
     * 取消首次写入超时检查
     */
    private void cancelFirstWriteTimeout() {
        if (firstWriteTimeoutRunnable != null) {
            segmentHandler.removeCallbacks(firstWriteTimeoutRunnable);
            firstWriteTimeoutRunnable = null;
        }
    }

    /**
     * 请求重建录制（通知外部）
     */
    private void requestRecordingRebuild(String reason) {
        // 停止当前录制（不触发正常的 stop 回调）
        isRecording.set(false);
        cancelFileSizeCheck();
        cancelFirstWriteTimeout();
        
        // 重置状态为 IDLE（等待外部重建）
        synchronized (stateLock) {
            state = RecordingState.IDLE;
        }
        
        // 通知外部需要重建
        if (callback != null) {
            callback.onRecordingRebuildRequested(cameraId, reason);
        }
    }

    /**
     * 取消文件大小检查
     */
    private void cancelFileSizeCheck() {
        if (fileSizeCheckRunnable != null) {
            segmentHandler.removeCallbacks(fileSizeCheckRunnable);
            fileSizeCheckRunnable = null;
        }
    }

    /**
     * 切换到下一段
     * 注意：这个方法需要通过回调通知外部重新配置相机会话
     * 
     * 【重要】为避免 Surface 竞态条件导致 CAPTURE FAILED，分段切换流程如下：
     * 1. 先通知外部暂停 CaptureSession 的录制输出（onPrepareSegmentSwitch）
     * 2. 等待 300ms 让 CaptureSession 完全停止向旧 Surface 发送帧
     * 3. 停止当前 MediaRecorder
     * 4. 准备新的 MediaRecorder
     * 5. 通知外部重新配置会话（onSegmentSwitch）
     */
    private void switchToNextSegment() {
        // 【状态检查】确保当前处于录制状态才能切换分段
        synchronized (stateLock) {
            if (state != RecordingState.RECORDING) {
                AppLog.w(TAG, "Camera " + cameraId + " cannot switch segment: current state=" + state);
                return;
            }
            // 进入分段切换状态（此状态下 stopRecording 会等待或取消切换）
            state = RecordingState.SWITCHING_SEGMENT;
        }
        
        AppLog.d(TAG, "Camera " + cameraId + " initiating segment switch from segment " + segmentIndex);
        
        // 【第一步】通知外部暂停 CaptureSession 的录制输出
        // 这会让 CaptureSession 停止向当前的 recordSurface 发送帧
        if (callback != null) {
            AppLog.d(TAG, "Camera " + cameraId + " calling onPrepareSegmentSwitch to pause capture session");
            callback.onPrepareSegmentSwitch(cameraId, segmentIndex);
        }
        
        // 【第二步】延迟执行实际的分段切换，等待 CaptureSession 完全停止
        // 300ms 足够让 Camera2 框架完成帧缓冲区的清空
        // 使用可取消的 Runnable，以便在 stopRecording() 时取消
        if (pendingSegmentSwitchRunnable != null) {
            segmentHandler.removeCallbacks(pendingSegmentSwitchRunnable);
        }
        pendingSegmentSwitchRunnable = () -> {
            pendingSegmentSwitchRunnable = null;  // 执行后清除引用
            performActualSegmentSwitch();
        };
        segmentHandler.postDelayed(pendingSegmentSwitchRunnable, 300);
    }
    
    /**
     * 执行实际的分段切换操作（在 CaptureSession 暂停后调用）
     */
    private void performActualSegmentSwitch() {
        // 【状态检查】确保当前处于分段切换状态
        synchronized (stateLock) {
            if (state != RecordingState.SWITCHING_SEGMENT) {
                AppLog.w(TAG, "Camera " + cameraId + " performActualSegmentSwitch cancelled: current state=" + state);
                return;
            }
        }
        
        // 安全检查：如果 MediaRecorder 已释放，不执行切换
        if (mediaRecorder == null) {
            AppLog.w(TAG, "Camera " + cameraId + " performActualSegmentSwitch cancelled: MediaRecorder released");
            synchronized (stateLock) {
                state = RecordingState.IDLE;
            }
            return;
        }
        
        // 保存当前分段的文件路径（已完成的文件）
        String completedFilePath = currentFilePath;
        boolean completedFileValid = false;
        
        try {
            // 【第三步】停止当前 MediaRecorder
            if (mediaRecorder != null) {
                // 诊断：在 stop() 之前检查文件大小
                long fileSizeBeforeStop = 0;
                if (currentFilePath != null) {
                    File file = new File(currentFilePath);
                    fileSizeBeforeStop = file.exists() ? file.length() : 0;
                    AppLog.d(TAG, "Camera " + cameraId + " file size before stop: " + fileSizeBeforeStop + " bytes (" + (fileSizeBeforeStop / 1024) + " KB)");
                }
                
                try {
                    // 如果文件太小（<10KB），说明 MediaRecorder 没有接收到帧，跳过 stop()
                    if (fileSizeBeforeStop < MIN_VALID_FILE_SIZE) {
                        AppLog.e(TAG, "Camera " + cameraId + " file size too small (" + fileSizeBeforeStop + " bytes < " + MIN_VALID_FILE_SIZE + "), MediaRecorder may not be receiving frames. Skipping stop().");
                        isRecording.set(false);
                    } else {
                        mediaRecorder.stop();
                        isRecording.set(false);  // 立即更新状态
                        AppLog.d(TAG, "Camera " + cameraId + " stopped segment " + segmentIndex + ": " + currentFilePath);

                        // 验证并清理损坏的文件
                        validateAndCleanupFile(currentFilePath);
                        completedFileValid = true;  // 标记文件有效
                    }
                } catch (RuntimeException e) {
                    AppLog.e(TAG, "Error stopping segment for camera " + cameraId + " (file size was: " + fileSizeBeforeStop + " bytes)", e);
                    isRecording.set(false);  // 即使失败也更新状态

                    // 停止失败，删除损坏的文件
                    if (currentFilePath != null) {
                        File file = new File(currentFilePath);
                        if (file.exists()) {
                            file.delete();
                            AppLog.w(TAG, "Deleted corrupted segment file: " + currentFilePath);
                        }
                    }
                    completedFilePath = null;  // 文件已删除，标记为无效
                }
                releaseMediaRecorder();
            }

            // 【第四步】准备下一段（使用新的时间戳）
            segmentIndex++;
            String nextSegmentPath = generateSegmentPath();
            prepareMediaRecorder(nextSegmentPath, recordWidth, recordHeight);
            currentFilePath = nextSegmentPath;
            recordedFilePaths.add(nextSegmentPath);  // 记录新分段文件

            // 设置等待会话重新配置的标志
            waitingForSessionReconfiguration = true;

            // 【第五步】通知外部需要重新配置相机会话（因为 MediaRecorder 的 Surface 已经改变）
            // 外部需要调用 startRecording() 来启动新段的录制
            if (callback != null) {
                // 只传递有效的已完成文件路径
                callback.onSegmentSwitch(cameraId, segmentIndex, completedFileValid ? completedFilePath : null);
            }

            // 注意：不在这里调用 start()，而是等待外部重新配置相机会话后调用 startRecording()
            // 这样可以确保新的 Surface 已经添加到 CaptureSession 中
            AppLog.d(TAG, "Camera " + cameraId + " prepared segment " + segmentIndex + ": " + nextSegmentPath + ", waiting for session reconfiguration");

        } catch (Exception e) {
            AppLog.e(TAG, "Failed to switch segment for camera " + cameraId, e);
            isRecording.set(false);
            waitingForSessionReconfiguration = false;
            // 切换失败，恢复到 IDLE 状态
            synchronized (stateLock) {
                state = RecordingState.IDLE;
            }
            if (callback != null) {
                callback.onRecordError(cameraId, "Failed to switch segment: " + e.getMessage());
            }
        }
    }

    /**
     * 开始录制（旧方法，保持兼容性）
     */
    public boolean startRecording(String filePath, int width, int height) {
        if (prepareRecording(filePath, width, height)) {
            return startRecording();
        }
        return false;
    }

    /**
     * 停止录制
     */
    public void stopRecording() {
        // 【状态机检查】处理不同状态下的停止请求
        synchronized (stateLock) {
            if (state == RecordingState.IDLE) {
                AppLog.w(TAG, "Camera " + cameraId + " is not recording (state=IDLE)");
                return;
            }
            
            if (state == RecordingState.STOPPING) {
                AppLog.w(TAG, "Camera " + cameraId + " is already stopping");
                return;
            }
            
            // 如果正在分段切换，取消切换任务并继续停止
            if (state == RecordingState.SWITCHING_SEGMENT) {
                AppLog.w(TAG, "Camera " + cameraId + " stop requested during segment switch, cancelling switch");
                // 取消待执行的分段切换任务
                if (pendingSegmentSwitchRunnable != null) {
                    segmentHandler.removeCallbacks(pendingSegmentSwitchRunnable);
                    pendingSegmentSwitchRunnable = null;
                }
            }
            
            // 进入停止状态
            state = RecordingState.STOPPING;
        }
        
        // 取消分段定时器
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }
        
        // 取消待执行的分段切换任务（再次确保取消）
        if (pendingSegmentSwitchRunnable != null) {
            segmentHandler.removeCallbacks(pendingSegmentSwitchRunnable);
            pendingSegmentSwitchRunnable = null;
            AppLog.d(TAG, "Camera " + cameraId + " cancelled pending segment switch");
        }
        
        // 取消文件大小检查和首次写入超时检查
        cancelFileSizeCheck();
        cancelFirstWriteTimeout();

        // 如果正在等待会话重新配置，说明MediaRecorder已经stop过了，只需要清理状态
        if (waitingForSessionReconfiguration) {
            AppLog.d(TAG, "Camera " + cameraId + " is waiting for session reconfiguration, skipping stop");
            isRecording.set(false);
            waitingForSessionReconfiguration = false;
            releaseMediaRecorder();

            // 验证并清理所有录制的文件
            List<String> deletedFiles = validateAndCleanupAllFiles();
            notifyCorruptedFilesDeleted(deletedFiles);

            currentFilePath = null;
            segmentIndex = 0;
            recordedFilePaths.clear();
            // 恢复到 IDLE 状态
            synchronized (stateLock) {
                state = RecordingState.IDLE;
            }
            if (callback != null) {
                callback.onRecordStop(cameraId);
            }
            return;
        }

        if (!isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " is not recording");
            synchronized (stateLock) {
                state = RecordingState.IDLE;
            }
            return;
        }

        // 诊断：在 stop() 之前检查文件大小
        long fileSizeBeforeStop = 0;
        if (currentFilePath != null) {
            File file = new File(currentFilePath);
            fileSizeBeforeStop = file.exists() ? file.length() : 0;
            AppLog.d(TAG, "Camera " + cameraId + " file size before stop: " + fileSizeBeforeStop + " bytes (" + (fileSizeBeforeStop / 1024) + " KB)");
        }

        List<String> deletedFiles = new ArrayList<>();
        try {
            if (mediaRecorder != null) {
                // 如果文件太小（<10KB），说明 MediaRecorder 没有接收到帧，跳过 stop()
                if (fileSizeBeforeStop < MIN_VALID_FILE_SIZE) {
                    AppLog.e(TAG, "Camera " + cameraId + " file size too small (" + fileSizeBeforeStop + " bytes < " + MIN_VALID_FILE_SIZE + "), MediaRecorder may not be receiving frames. Skipping stop().");
                } else {
                    mediaRecorder.stop();
                    AppLog.d(TAG, "Camera " + cameraId + " stopped recording: " + currentFilePath + " (total segments: " + (segmentIndex + 1) + ")");
                }
            }
            isRecording.set(false);

            // 验证并清理所有录制的文件
            deletedFiles = validateAndCleanupAllFiles();

            if (callback != null) {
                callback.onRecordStop(cameraId);
            }
        } catch (RuntimeException e) {
            AppLog.e(TAG, "Failed to stop recording for camera " + cameraId + " (file size was: " + fileSizeBeforeStop + " bytes)", e);
            isRecording.set(false);

            // 录制失败，删除损坏的文件
            if (currentFilePath != null) {
                File file = new File(currentFilePath);
                if (file.exists()) {
                    file.delete();
                    deletedFiles.add(file.getName());
                    AppLog.w(TAG, "Deleted corrupted video file: " + currentFilePath);
                }
            }
        } finally {
            releaseMediaRecorder();
            currentFilePath = null;
            segmentIndex = 0;
            
            // 通知损坏文件被删除
            notifyCorruptedFilesDeleted(deletedFiles);
            recordedFilePaths.clear();
            
            // 恢复到 IDLE 状态
            synchronized (stateLock) {
                state = RecordingState.IDLE;
            }
        }
    }

    /**
     * 验证并清理所有录制的文件
     * @return 被删除的文件名列表
     */
    private List<String> validateAndCleanupAllFiles() {
        List<String> deletedFiles = new ArrayList<>();
        
        AppLog.d(TAG, "Camera " + cameraId + " validating " + recordedFilePaths.size() + " recorded files");
        
        for (String filePath : recordedFilePaths) {
            String deletedFileName = validateAndCleanupFile(filePath);
            if (deletedFileName != null) {
                deletedFiles.add(deletedFileName);
            }
        }
        
        if (!deletedFiles.isEmpty()) {
            AppLog.w(TAG, "Camera " + cameraId + " deleted " + deletedFiles.size() + " corrupted files: " + deletedFiles);
        }
        
        return deletedFiles;
    }

    /**
     * 通知损坏文件被删除
     */
    private void notifyCorruptedFilesDeleted(List<String> deletedFiles) {
        if (!deletedFiles.isEmpty() && callback != null) {
            callback.onCorruptedFilesDeleted(cameraId, deletedFiles);
        }
    }

    /**
     * 验证并清理损坏的视频文件
     * @return 如果文件被删除，返回文件名；否则返回 null
     */
    private String validateAndCleanupFile(String filePath) {
        if (filePath == null) {
            return null;
        }

        File file = new File(filePath);
        if (!file.exists()) {
            return null;
        }

        long fileSize = file.length();

        if (fileSize < MIN_VALID_FILE_SIZE) {
            AppLog.w(TAG, "Video file too small: " + filePath + " (size: " + fileSize + " bytes, minimum: " + MIN_VALID_FILE_SIZE + " bytes). Deleting...");
            file.delete();
            return file.getName();
        } else {
            AppLog.d(TAG, "Video file validated: " + filePath + " (size: " + (fileSize / 1024) + " KB)");
            return null;
        }
    }

    /**
     * 释放录制器
     */
    private void releaseMediaRecorder() {
        // 先清空缓存的 Surface
        cachedSurface = null;
        
        if (mediaRecorder != null) {
            mediaRecorder.reset();
            mediaRecorder.release();
            mediaRecorder = null;
        }
    }

    /**
     * 释放资源
     */
    public void release() {
        // 取消分段定时器
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }
        
        // 取消待执行的分段切换任务
        if (pendingSegmentSwitchRunnable != null) {
            segmentHandler.removeCallbacks(pendingSegmentSwitchRunnable);
            pendingSegmentSwitchRunnable = null;
        }
        
        // 取消文件大小检查和首次写入超时检查
        cancelFileSizeCheck();
        cancelFirstWriteTimeout();

        // 只有在真正录制中且mediaRecorder不为null时才调用stopRecording
        if (isRecording.get() && mediaRecorder != null) {
            stopRecording();
        } else {
            // 直接清理状态
            isRecording.set(false);
            waitingForSessionReconfiguration = false;
            releaseMediaRecorder();
            currentFilePath = null;
            segmentIndex = 0;
            // 确保状态重置为 IDLE
            synchronized (stateLock) {
                state = RecordingState.IDLE;
            }
        }
        
        // 清理分段处理线程
        if (segmentHandler != null) {
            segmentHandler.removeCallbacksAndMessages(null);
        }
        if (segmentThread != null) {
            segmentThread.quitSafely();
            try {
                segmentThread.join(1000);  // 1秒超时
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                AppLog.w(TAG, "Camera " + cameraId + " segment thread join interrupted");
            }
            segmentThread = null;
        }
        segmentHandler = null;
    }
    
    /**
     * 获取当前录制状态
     * @return 当前的 RecordingState
     */
    public RecordingState getState() {
        synchronized (stateLock) {
            return state;
        }
    }
}
