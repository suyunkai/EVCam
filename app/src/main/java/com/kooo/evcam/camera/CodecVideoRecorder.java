package com.kooo.evcam.camera;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.view.Surface;

import com.kooo.evcam.AppLog;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 使用 MediaCodec + MediaMuxer 进行视频编码和录制
 * 用于 L6/L7 等不支持 MediaRecorder 直接录制的车机平台
 * 
 * 工作流程：
 * 1. 创建 MediaCodec 编码器，获取其输入 Surface
 * 2. 使用 EglSurfaceEncoder 将 Camera 的帧渲染到编码器输入 Surface
 * 3. 从 MediaCodec 获取编码后的数据
 * 4. 通过 MediaMuxer 写入 MP4 文件
 */
public class CodecVideoRecorder {
    private static final String TAG = "CodecVideoRecorder";

    // 编码参数（常量）
    private static final String MIME_TYPE_H264 = MediaFormat.MIMETYPE_VIDEO_AVC;      // H.264
    private static final String MIME_TYPE_HEVC = MediaFormat.MIMETYPE_VIDEO_HEVC;     // H.265/HEVC
    private String mimeType = MIME_TYPE_H264;  // 默认使用 H.264，支持时自动切换到 HEVC
    
    private static final int I_FRAME_INTERVAL = 3;  // I帧间隔（秒）- 改为3秒减少CPU占用和文件大小
    
    // 编码参数（可配置）
    private int frameRate = 20;       // 默认 20fps - 降低帧率减少CPU占用，同时保持流畅
    private int bitRate = 0;          // 默认自动计算码率
    
    // 性能优化：码率上限（防止过高码率导致卡顿）
    private static final int MAX_BITRATE = 12000000;  // 最大码率 12Mbps
    private static final int MAX_HEVC_BITRATE = 8000000;  // HEVC 最大码率 8Mbps
    
    // 录制时补盲优化模式
    private boolean blindSpotOptimizeMode = false;  // 是否启用补盲优化模式（录制时降低负载）
    private static final int BLIND_SPOT_OPTIMIZED_FPS = 15;  // 补盲优化模式帧率
    
    // 画质等级：0=低, 1=中, 2=高, 3=最高
    private int qualityLevel = 2;
    
    // 自适应 drain 间隔控制（优化版 - 减少CPU占用）
    private volatile long currentDrainIntervalMs = 20;  // 当前 drain 间隔（毫秒）- 提高到20ms减少CPU占用
    private static final long DRAIN_INTERVAL_MIN_MS = 10;   // 最小 10ms（保证流畅性）
    private static final long DRAIN_INTERVAL_MAX_MS = 50;  // 最大 50ms（减少CPU占用）
    private static final int DRAIN_BATCH_SIZE = 8;  // 每次 drain 批量处理更多帧，减少系统调用开销
    private long lastDrainTimeMs = 0;  // 上次 drain 时间
    private int framesSinceLastDrain = 0;  // 上次 drain 以来的帧数

    private final String cameraId;
    private final int width;
    private final int height;

    // MediaCodec 相关
    private MediaCodec encoder;
    private Surface encoderInputSurface;
    private MediaCodec.BufferInfo bufferInfo;

    // MediaMuxer 相关
    private MediaMuxer muxer;
    private int videoTrackIndex = -1;
    private boolean muxerStarted = false;

    // EGL 渲染器
    private EglSurfaceEncoder eglEncoder;
    private SurfaceTexture inputSurfaceTexture;
    private int textureId;

    // 编码线程
    private HandlerThread encoderThread;
    private Handler encoderHandler;

    // 状态
    private final AtomicBoolean isRecording = new AtomicBoolean(false);  // 使用 AtomicBoolean 确保线程安全
    private volatile boolean isReleased = false;
    private String currentFilePath;
    
    // 缓存的录制 Surface，避免重复创建导致内存泄漏
    private Surface cachedRecordSurface = null;
    
    // 时间戳基准（用于计算相对时间戳，供输入端使用）
    private long firstFrameTimestampNs = -1;
    
    // 分段开始时间（用于计算 PTS，基于系统时间而非帧数）
    // 这样可以准确反映实际录制时长，不受帧率波动影响
    private long segmentStartTimeNs = 0;
    
    // 编码器输出帧计数（仅用于日志和统计，不再用于 PTS 计算）
    private long encodedOutputFrameCount = 0;

    // 分段录制相关
    private long segmentDurationMs = 60000;  // 分段时长，默认1分钟，可通过 setSegmentDuration 配置
    private static final long SEGMENT_DURATION_COMPENSATION_MS = 0;  // 分段时长补偿（H3修复后定时器更精确，不再需要补偿）
    private static final long MIN_VALID_FILE_SIZE = 1 * 1024;   // 最小有效文件大小 1KB（降低阈值，短录制也能保存）
    
    // 使用独立的后台线程处理分段和文件 I/O 操作，避免阻塞主线程导致 ANR
    private HandlerThread segmentThread;
    private Handler segmentHandler;
    
    private Runnable segmentRunnable;
    private int segmentIndex = 0;
    private String saveDirectory;
    private String cameraPosition;
    private VideoRecorder.SegmentTimestampProvider timestampProvider;  // 分段时间戳提供者（用于多路同步）
    private long lastFileSize = 0;
    private static final long FILE_SIZE_CHECK_INTERVAL_MS = 5000;
    private static final long FIRST_CHECK_DELAY_MS = 500;  // 首次检查延迟（更快检测首次写入）
    private Runnable fileSizeCheckRunnable;
    private long recordedFrameCount = 0;
    private List<String> recordedFilePaths = new ArrayList<>();  // 本次录制的所有文件路径
    
    // 首次写入检测（与 VideoRecorder 保持一致）
    private static final long FIRST_WRITE_TIMEOUT_MS = 10000;  // 首次写入超时（10秒）
    private boolean hasFirstWrite = false;  // 是否已有首次写入
    private Runnable firstWriteTimeoutRunnable;  // 首次写入超时检查任务
    
    // 快速恢复机制
    private static final long RECOVERY_RETRY_INTERVAL_MS = 5000;  // 恢复重试间隔：5秒
    private static final int MAX_RECOVERY_ATTEMPTS = 60;  // 最大重试次数（5秒 × 60 = 5分钟内重试）
    private int recoveryAttempts = 0;  // 当前重试次数
    private Runnable recoveryRunnable;  // 恢复重试任务

    // 编码器健康检查
    private static final long ENCODER_HEALTH_CHECK_INTERVAL_MS = 3000;  // 健康检查间隔：3秒
    private static final int MAX_FRAMES_WITHOUT_OUTPUT = 30;  // 无输出的最大帧数阈值
    private long lastEncoderOutputTime = 0;  // 最后一次编码器输出时间
    private int framesWithoutEncoderOutput = 0;  // 无编码器输出的连续帧数
    private volatile boolean encoderHealthy = true;  // 编码器是否健康
    private Runnable healthCheckRunnable;  // 健康检查任务

    // 回调
    private RecordCallback callback;

    // 时间水印设置
    private boolean watermarkEnabled = false;

    // 注意：帧同步变量已移除，帧处理现在直接在 onFrameAvailable 回调中完成

    public CodecVideoRecorder(String cameraId, int width, int height) {
        this.cameraId = cameraId;
        this.width = width;
        this.height = height;
        // 创建独立的后台线程用于分段处理和文件 I/O 操作
        segmentThread = new HandlerThread("CodecRecorder-Segment-" + cameraId) {
            @Override
            protected void onLooperPrepared() {
                // 降低分段线程优先级，减少对主线程的影响
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            }
        };
        segmentThread.start();
        this.segmentHandler = new Handler(segmentThread.getLooper());
    }

    /**
     * 设置是否启用时间水印
     * @param enabled true 表示启用水印
     */
    public void setWatermarkEnabled(boolean enabled) {
        this.watermarkEnabled = enabled;
        // 如果 EGL 编码器已初始化，同步设置
        if (eglEncoder != null) {
            eglEncoder.setWatermarkEnabled(enabled);
        }
        AppLog.d(TAG, "Camera " + cameraId + " Watermark " + (enabled ? "enabled" : "disabled"));
    }

    /**
     * 检查是否启用了时间水印
     */
    public boolean isWatermarkEnabled() {
        return watermarkEnabled;
    }

    public void setCallback(RecordCallback callback) {
        this.callback = callback;
    }

    /**
     * 设置分段时间戳提供者
     * 用于多路摄像头分段切换时使用统一的时间戳，避免时间戳差1秒导致分组错误
     * @param provider 时间戳提供者
     */
    public void setTimestampProvider(VideoRecorder.SegmentTimestampProvider provider) {
        this.timestampProvider = provider;
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
     * 获取分段时长（毫秒）
     */
    public long getSegmentDuration() {
        return segmentDurationMs;
    }

    /**
     * 设置录制码率
     * @param bitrate 码率（bps）
     */
    public void setBitRate(int bitrate) {
        this.bitRate = bitrate;
        AppLog.d(TAG, "Camera " + cameraId + " bitrate set to " + (bitrate / 1000) + " Kbps");
    }

    /**
     * 设置录制帧率
     * @param fps 帧率（fps）
     */
    public void setFrameRate(int fps) {
        this.frameRate = fps;
        AppLog.d(TAG, "Camera " + cameraId + " frame rate set to " + fps + " fps");
    }

    /**
     * 设置画质等级
     * @param level 画质等级：0=低, 1=中, 2=高, 3=最高
     */
    public void setQualityLevel(int level) {
        this.qualityLevel = Math.max(0, Math.min(3, level));
        AppLog.d(TAG, "Camera " + cameraId + " quality level set to " + this.qualityLevel);
    }

    /**
     * 获取当前配置的码率
     */
    public int getBitRate() {
        return bitRate;
    }

    /**
     * 获取当前配置的帧率
     */
    public int getFrameRate() {
        return frameRate;
    }
    
    /**
     * 设置补盲优化模式
     * 启用后降低帧率以减少CPU/GPU负载，改善补盲画面延迟
     * @param enabled true 表示启用补盲优化模式
     */
    public void setBlindSpotOptimizeMode(boolean enabled) {
        this.blindSpotOptimizeMode = enabled;
        if (enabled) {
            AppLog.i(TAG, "Camera " + cameraId + " 启用补盲优化模式，帧率降至 " + BLIND_SPOT_OPTIMIZED_FPS + "fps");
        } else {
            AppLog.i(TAG, "Camera " + cameraId + " 关闭补盲优化模式，恢复 " + frameRate + "fps");
        }
    }
    
    /**
     * 获取补盲优化模式状态
     */
    public boolean isBlindSpotOptimizeMode() {
        return blindSpotOptimizeMode;
    }

    /**
     * 准备录制
     * 
     * 警告：此方法包含阻塞操作（CountDownLatch.await），不建议在主线程调用
     * 如果必须在主线程调用，可能导致 ANR。建议在后台线程调用或使用 prepareRecordingAsync()
     * 
     * @param filePath 输出文件路径
     * @return 用于 Camera 输出的 SurfaceTexture
     */
    public SurfaceTexture prepareRecording(String filePath) {
        // 检查是否在主线程调用（可能导致 ANR）
        if (Looper.myLooper() == Looper.getMainLooper()) {
            AppLog.w(TAG, "Camera " + cameraId + " WARNING: prepareRecording() called on MAIN THREAD! " +
                    "This may cause ANR due to blocking operations. Consider using prepareRecordingAsync().");
        }
        
        if (isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " is already recording");
            return inputSurfaceTexture;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Preparing codec recording: " + width + "x" + height);

        // 保存录制参数
        this.currentFilePath = filePath;
        this.segmentIndex = 0;
        this.recordedFrameCount = 0;
        this.firstFrameTimestampNs = -1;  // 重置时间戳基准
        this.encodedOutputFrameCount = 0;  // 重置编码输出帧计数

        // 重置健康检查状态
        this.encoderHealthy = true;
        this.framesWithoutEncoderOutput = 0;
        this.lastEncoderOutputTime = System.currentTimeMillis();

        // 清空并初始化本次录制的文件列表
        recordedFilePaths.clear();
        recordedFilePaths.add(filePath);

        // 从文件路径中提取保存目录和摄像头位置
        File file = new File(filePath);
        this.saveDirectory = file.getParent();
        String fileName = file.getName();
        int lastUnderscoreIndex = fileName.lastIndexOf('_');
        if (lastUnderscoreIndex > 0 && fileName.endsWith(".mp4")) {
            this.cameraPosition = fileName.substring(lastUnderscoreIndex + 1, fileName.length() - 4);
        } else {
            this.cameraPosition = "unknown";
        }

        try {
            // 创建编码线程
            encoderThread = new HandlerThread("Encoder-" + cameraId) {
                @Override
                protected void onLooperPrepared() {
                    // 降低编码线程优先级，避免与补盲画面渲染竞争资源
                    // THREAD_PRIORITY_BACKGROUND 比 FOREGROUND 更低，给补盲画面留出更多 CPU 时间
                    // 同时保持比 THREAD_PRIORITY_LOWEST 高，确保录制不会掉帧
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    AppLog.d(TAG, "Camera " + cameraId + " 编码线程优先级设置为 BACKGROUND");
                }
            };
            encoderThread.start();
            encoderHandler = new Handler(encoderThread.getLooper());

            // 创建 MediaCodec 编码器
            createEncoder();

            // 创建 MediaMuxer
            createMuxer(filePath);

            // 在编码线程上初始化 EGL 和 SurfaceTexture（重要：必须在同一线程上）
            // 使用 CountDownLatch 等待初始化完成
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            final int[] resultTextureId = {0};
            final Exception[] initException = {null};

            encoderHandler.post(() -> {
                try {
                    // 创建 EGL 渲染器（在编码线程上）
                    eglEncoder = new EglSurfaceEncoder(cameraId, width, height);
                    resultTextureId[0] = eglEncoder.initialize(encoderInputSurface);
                    textureId = resultTextureId[0];

                    // 创建 SurfaceTexture 供 Camera 输出（在编码线程上，绑定到 EGL context）
                    inputSurfaceTexture = new SurfaceTexture(textureId);
                    inputSurfaceTexture.setDefaultBufferSize(width, height);

                    // 设置帧可用回调（在编码线程上）
                    // 直接在回调中处理帧，避免 Handler 死锁
                    inputSurfaceTexture.setOnFrameAvailableListener(surfaceTexture -> {
                        if (isReleased) {
                            return;
                        }

                        try {
                            // 关键修复：即使不在录制状态，也必须调用 updateTexImage() 消费帧
                            // 否则 SurfaceTexture 会保持 pending 状态，不再触发后续回调
                            // updateTexImage 在 drawFrame 内部调用，这里单独处理非录制状态
                            if (!isRecording.get()) {
                                // 不在录制状态时，仍需消费帧以保持 SurfaceTexture 正常工作
                                if (eglEncoder != null && eglEncoder.isInitialized()) {
                                    eglEncoder.consumeFrame();  // 只消费帧，不编码
                                }
                                return;
                            }

                            // 检查编码器健康状态，不健康时只消费帧不编码
                            if (!encoderHealthy) {
                                if (eglEncoder != null && eglEncoder.isInitialized()) {
                                    eglEncoder.consumeFrame();  // 只消费帧，等待重建
                                }
                                return;
                            }

                            // 获取绝对时间戳（系统启动以来的纳秒）
                            long absoluteTimestampNs = surfaceTexture.getTimestamp();
                            
                            // 计算相对时间戳（以第一帧为基准）
                            // 注意：firstFrameTimestampNs 在整个录制期间不重置
                            // 因为 eglPresentationTimeANDROID 需要单调递增的时间戳
                            // 否则 GraphicBufferSource 会拒绝帧
                            if (firstFrameTimestampNs < 0) {
                                firstFrameTimestampNs = absoluteTimestampNs;
                                AppLog.d(TAG, "Camera " + cameraId + " First frame timestamp: " + absoluteTimestampNs + " ns");
                            }
                            long relativeTimestampNs = absoluteTimestampNs - firstFrameTimestampNs;

                            // 直接渲染帧到编码器（使用相对时间戳）
                            if (eglEncoder != null && eglEncoder.isInitialized()) {
                                eglEncoder.drawFrame(relativeTimestampNs);
                                recordedFrameCount++;
                                framesSinceLastDrain++;

                                // 定期输出帧计数
                                if (recordedFrameCount % 100 == 0) {
                                    AppLog.d(TAG, "Camera " + cameraId + " Encoded frames: " + recordedFrameCount);
                                }
                            }

                            // 自适应 drain 控制：根据时间间隔决定是否 drain
                            // 优化：使用更激进的批量策略，减少系统调用开销
                            long currentTimeMs = System.currentTimeMillis();
                            // 优化：增加帧数阈值到 10 帧，进一步减少 drain 次数
                            if (currentTimeMs - lastDrainTimeMs >= currentDrainIntervalMs || framesSinceLastDrain >= 10) {
                                // 从编码器获取输出数据并写入 muxer
                                boolean hadOutput = drainEncoderWithResult(false);
                                lastDrainTimeMs = currentTimeMs;
                                framesSinceLastDrain = 0;
                                
                                // 调整 drain 间隔：有输出时缩短间隔，无输出时延长间隔
                                // 优化：使用更平滑的调整策略
                                if (hadOutput) {
                                    currentDrainIntervalMs = Math.max(DRAIN_INTERVAL_MIN_MS, currentDrainIntervalMs - 1);
                                } else {
                                    currentDrainIntervalMs = Math.min(DRAIN_INTERVAL_MAX_MS, currentDrainIntervalMs + 2);
                                }
                            }

                        } catch (Exception e) {
                            AppLog.e(TAG, "Camera " + cameraId + " Error processing frame", e);
                            // 发生异常时标记编码器不健康
                            encoderHealthy = false;
                        }
                    }, encoderHandler);

                    // 设置 EGL 渲染器的输入
                    eglEncoder.setInputSurfaceTexture(inputSurfaceTexture);

                    // 设置时间水印（如果启用）
                    if (watermarkEnabled) {
                        eglEncoder.setWatermarkEnabled(true);
                    }

                    AppLog.d(TAG, "Camera " + cameraId + " EGL/SurfaceTexture initialized on encoder thread, textureId=" + textureId + ", watermark=" + watermarkEnabled);

                } catch (Exception e) {
                    AppLog.e(TAG, "Camera " + cameraId + " Failed to initialize EGL on encoder thread", e);
                    initException[0] = e;
                } finally {
                    latch.countDown();
                }
            });

            // 等待初始化完成（最多 5 秒）
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new RuntimeException("Timeout waiting for EGL initialization");
            }

            // 检查是否有初始化错误
            if (initException[0] != null) {
                throw initException[0];
            }

            AppLog.d(TAG, "Camera " + cameraId + " Codec recording prepared, textureId=" + textureId);

            return inputSurfaceTexture;

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to prepare codec recording", e);
            release();
            if (callback != null) {
                callback.onRecordError(cameraId, e.getMessage());
            }
            return null;
        }
    }
    
    /**
     * 准备录制回调接口
     */
    public interface PrepareCallback {
        /**
         * 准备完成回调
         * @param success 是否成功
         * @param surfaceTexture 成功时返回的 SurfaceTexture，失败时为 null
         * @param errorMessage 失败时的错误信息，成功时为 null
         */
        void onPrepareComplete(boolean success, SurfaceTexture surfaceTexture, String errorMessage);
    }
    
    /**
     * 异步准备录制（推荐使用）
     * 
     * 此方法在后台线程执行准备操作，完成后在主线程回调
     * 避免在主线程执行阻塞操作导致 ANR
     * 
     * @param filePath 输出文件路径
     * @param callback 准备完成回调
     */
    public void prepareRecordingAsync(String filePath, PrepareCallback callback) {
        new Thread(() -> {
            try {
                SurfaceTexture result = prepareRecording(filePath);
                if (callback != null) {
                    // 在主线程回调
                    new Handler(Looper.getMainLooper()).post(() -> {
                        if (result != null) {
                            callback.onPrepareComplete(true, result, null);
                        } else {
                            callback.onPrepareComplete(false, null, "Preparation failed");
                        }
                    });
                }
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " prepareRecordingAsync failed", e);
                if (callback != null) {
                    new Handler(Looper.getMainLooper()).post(() -> 
                        callback.onPrepareComplete(false, null, e.getMessage()));
                }
            }
        }, "CodecRecorderPrepare-" + cameraId).start();
    }

    /**
     * 开始录制
     */
    public boolean startRecording() {
        if (encoder == null || eglEncoder == null) {
            AppLog.e(TAG, "Camera " + cameraId + " Encoder not prepared");
            return false;
        }

        if (isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " Already recording");
            return false;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Starting codec recording");

        // 记录分段开始时间（用于 PTS 计算）
        segmentStartTimeNs = System.nanoTime();
        encodedOutputFrameCount = 0;
        
        // 重置首次写入状态
        hasFirstWrite = false;
        lastFileSize = 0;
        
        isRecording.set(true);

        // 注意：不再使用单独的编码循环
        // 帧的处理直接在 onFrameAvailable 回调中完成（该回调在 encoderHandler 上执行）
        // 这样避免了 Handler 死锁问题

        // 【重要】分段定时器延迟到首次写入后启动
        // 这样可以确保：
        // 1. 摄像头启动慢或需要修复时，用户只会感觉"启动慢"而不是录制空视频
        // 2. 钉钉指定时长录制时，实际录制时长是有效的
        // scheduleNextSegment() 将在 scheduleFileSizeCheck() 检测到首次写入时调用

        // 启动首次写入超时检查
        scheduleFirstWriteTimeout();

        // 启动文件大小检查
        scheduleFileSizeCheck();

        // 启动编码器健康检查
        scheduleEncoderHealthCheck();

        if (callback != null && segmentIndex == 0) {
            callback.onRecordStart(cameraId);
        }

        AppLog.d(TAG, "Camera " + cameraId + " Codec recording started");
        return true;
    }

    /**
     * 停止录制
     */
    public void stopRecording() {
        if (!isRecording.get()) {
            AppLog.w(TAG, "Camera " + cameraId + " Not recording");
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Stopping codec recording");

        // 立即标记停止状态，防止新帧处理
        isRecording.set(false);

        // 取消所有定时器和任务
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
            segmentRunnable = null;
        }
        if (fileSizeCheckRunnable != null) {
            segmentHandler.removeCallbacks(fileSizeCheckRunnable);
            fileSizeCheckRunnable = null;
        }
        // 取消首次写入超时检查
        cancelFirstWriteTimeout();
        
        // 取消恢复重试任务
        if (recoveryRunnable != null) {
            segmentHandler.removeCallbacks(recoveryRunnable);
            recoveryRunnable = null;
        }
        recoveryAttempts = 0;

        // 取消健康检查任务
        if (healthCheckRunnable != null) {
            segmentHandler.removeCallbacks(healthCheckRunnable);
            healthCheckRunnable = null;
        }

        // 在编码线程上执行停止操作
        if (encoderHandler != null) {
            final Object stopLock = new Object();
            encoderHandler.post(() -> {
                try {
                    // 稍等一下让正在处理的帧完成
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }

                    // 发送结束信号给编码器
                    if (encoder != null) {
                        try {
                            encoder.signalEndOfInputStream();
                            // 排空编码器
                            drainEncoder(true);
                        } catch (Exception e) {
                            AppLog.e(TAG, "Camera " + cameraId + " Error signaling end of stream", e);
                        }
                    }

                    // 停止 muxer
                    if (muxerStarted && muxer != null) {
                        try {
                            muxer.stop();
                        } catch (Exception e) {
                            AppLog.e(TAG, "Camera " + cameraId + " Error stopping muxer", e);
                        }
                        muxerStarted = false;
                    }

                    AppLog.d(TAG, "Camera " + cameraId + " Codec recording stopped on encoder thread, frames recorded: " + recordedFrameCount);
                } catch (Exception e) {
                    AppLog.e(TAG, "Camera " + cameraId + " Error in stopRecording on encoder thread", e);
                } finally {
                    synchronized (stopLock) {
                        stopLock.notifyAll();
                    }
                }
            });

            // 等待停止完成（最多3秒）
            synchronized (stopLock) {
                try {
                    stopLock.wait(3000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        // 验证并清理所有录制的文件
        List<String> deletedFiles = validateAndCleanupAllFiles();

        AppLog.d(TAG, "Camera " + cameraId + " Codec recording stopped, frames recorded: " + recordedFrameCount);

        if (callback != null) {
            callback.onRecordStop(cameraId);
            // 通知损坏文件被删除
            if (!deletedFiles.isEmpty()) {
                callback.onCorruptedFilesDeleted(cameraId, deletedFiles);
            }
        }
        
        recordedFilePaths.clear();
    }

    /**
     * 释放资源
     */
    public void release() {
        if (isReleased) {
            return;
        }

        AppLog.d(TAG, "Camera " + cameraId + " Releasing CodecVideoRecorder");

        isReleased = true;

        if (isRecording.get()) {
            stopRecording();
        }

        // 释放 EGL 渲染器
        if (eglEncoder != null) {
            eglEncoder.release();
            eglEncoder = null;
        }

        // 释放缓存的录制 Surface（必须在 SurfaceTexture 之前释放）
        if (cachedRecordSurface != null) {
            cachedRecordSurface.release();
            cachedRecordSurface = null;
        }

        // 释放 SurfaceTexture
        if (inputSurfaceTexture != null) {
            inputSurfaceTexture.release();
            inputSurfaceTexture = null;
        }

        // 释放编码器
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception e) {
                // Ignore
            }
            encoder.release();
            encoder = null;
        }

        // 释放编码器输入 Surface
        if (encoderInputSurface != null) {
            encoderInputSurface.release();
            encoderInputSurface = null;
        }

        // 释放 muxer
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
            } catch (Exception e) {
                // Ignore
            }
            muxer.release();
            muxer = null;
        }

        // 停止编码线程
        if (encoderThread != null) {
            encoderThread.quitSafely();
            try {
                encoderThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            encoderThread = null;
            encoderHandler = null;
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

        AppLog.d(TAG, "Camera " + cameraId + " CodecVideoRecorder released");
    }

    /**
     * 获取录制用的 Surface（供 Camera 使用）
     * 使用缓存模式避免重复创建 Surface 导致内存泄漏
     */
    public Surface getRecordSurface() {
        if (inputSurfaceTexture == null) {
            return null;
        }
        
        // 检查缓存的 Surface 是否有效
        if (cachedRecordSurface != null && cachedRecordSurface.isValid()) {
            return cachedRecordSurface;
        }
        
        // 释放旧的无效 Surface
        if (cachedRecordSurface != null) {
            AppLog.d(TAG, "Camera " + cameraId + " releasing invalid cached record surface");
            cachedRecordSurface.release();
            cachedRecordSurface = null;
        }
        
        // 创建新的 Surface 并缓存
        cachedRecordSurface = new Surface(inputSurfaceTexture);
        AppLog.d(TAG, "Camera " + cameraId + " created new record surface");
        return cachedRecordSurface;
    }

    /**
     * 获取当前文件路径
     */
    public String getCurrentFilePath() {
        return currentFilePath;
    }

    /**
     * 检查是否正在录制
     */
    public boolean isRecording() {
        return isRecording.get();
    }

    // ===== 私有方法 =====

    /**
     * 创建 MediaCodec 编码器
     * 优先尝试 HEVC (H.265)，如果不支持则回退到 H.264
     */
    private void createEncoder() throws IOException {
        // 检测并选择最优编码格式
        mimeType = selectBestEncoder();
        
        // 计算最优码率
        int optimalBitrate = calculateOptimalBitrate();
        
        // 如果启用了补盲优化模式，使用降低的帧率
        int effectiveFrameRate = blindSpotOptimizeMode ? BLIND_SPOT_OPTIMIZED_FPS : frameRate;
        
        MediaFormat format = MediaFormat.createVideoFormat(mimeType, width, height);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, optimalBitrate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, effectiveFrameRate);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL);
        
        // HEVC 特定优化参数
        if (mimeType.equals(MIME_TYPE_HEVC)) {
            // 设置 HEVC 的 Profile 和 Level 以获得更好效率
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.HEVCProfileMain);
            format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.HEVCHighTierLevel4);
        } else {
            // H.264 优化参数
            format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileHigh);
        }

        // 优化：使用硬件编码器创建方法
        encoder = createHardwareEncoder(mimeType);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

        encoderInputSurface = encoder.createInputSurface();
        encoder.start();

        bufferInfo = new MediaCodec.BufferInfo();

        AppLog.d(TAG, "Camera " + cameraId + " Encoder created: " + width + "x" + height + 
                " @ " + effectiveFrameRate + "fps" + (blindSpotOptimizeMode ? "(补盲优化)" : "") + 
                ", " + (optimalBitrate / 1000) + " Kbps, " + 
                (mimeType.equals(MIME_TYPE_HEVC) ? "HEVC" : "H.264"));
    }

    /**
     * 选择最优编码器类型
     * 优先使用 HEVC (H.265)，如果不支持则回退到 H.264
     * 优化：优先选择硬件编码器，性能更好
     */
    private String selectBestEncoder() {
        try {
            // 检查 HEVC 编码器是否可用
            MediaCodec hevcEncoder = MediaCodec.createEncoderByType(MIME_TYPE_HEVC);
            hevcEncoder.release();
            AppLog.d(TAG, "HEVC encoder available, using H.265 for better efficiency");
            return MIME_TYPE_HEVC;
        } catch (Exception e) {
            AppLog.w(TAG, "HEVC encoder not available, falling back to H.264");
            return MIME_TYPE_H264;
        }
    }
    
    /**
     * 创建编码器，优先使用硬件编码器
     * 优化：通过编码器名称选择硬件编码器，避免软件编码器性能问题
     */
    private MediaCodec createHardwareEncoder(String mimeType) throws IOException {
        // 获取所有支持该类型的编码器
        MediaCodecList codecList = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaCodecInfo[] codecInfos = codecList.getCodecInfos();
        
        MediaCodecInfo bestEncoder = null;
        String bestEncoderName = null;
        
        for (MediaCodecInfo codecInfo : codecInfos) {
            if (!codecInfo.isEncoder()) {
                continue;
            }
            
            String[] supportedTypes = codecInfo.getSupportedTypes();
            boolean supportsMimeType = false;
            for (String type : supportedTypes) {
                if (type.equalsIgnoreCase(mimeType)) {
                    supportsMimeType = true;
                    break;
                }
            }
            
            if (!supportsMimeType) {
                continue;
            }
            
            String name = codecInfo.getName();
            
            // 优先选择硬件编码器（通常名称包含特定关键字）
            // 避免软件编码器（如 c2.android.* 或 OMX.google.*）
            if (name.contains("c2.android") || name.contains("OMX.google")) {
                // 软件编码器，作为备选
                if (bestEncoder == null) {
                    bestEncoder = codecInfo;
                    bestEncoderName = name;
                }
                continue;
            }
            
            // 硬件编码器优先
            bestEncoder = codecInfo;
            bestEncoderName = name;
            AppLog.i(TAG, "Camera " + cameraId + " Selected hardware encoder: " + name);
            break;
        }
        
        if (bestEncoder != null) {
            return MediaCodec.createByCodecName(bestEncoderName);
        }
        
        // 回退到默认方式
        return MediaCodec.createEncoderByType(mimeType);
    }

    /**
     * 计算最优码率
     * 根据分辨率、帧率和画质等级计算
     * HEVC 使用更低的码率（相同画质下约为 H.264 的 60%）
     * 
     * 优化策略：
     * 1. 降低各画质等级的 bpp 值，减少编码压力
     * 2. 根据分辨率动态调整，高分辨率适当降低 bpp
     * 3. 严格限制最大码率，防止编码器过载
     */
    private int calculateOptimalBitrate() {
        // 基础码率计算（每像素每帧的比特数）- 优化后的值，降低码率减少CPU占用
        double bitsPerPixelPerFrame;

        switch (qualityLevel) {
            case 0: // 低画质 - 适合长时间录制
                bitsPerPixelPerFrame = 0.03;
                break;
            case 1: // 中画质 - 平衡画质和性能
                bitsPerPixelPerFrame = 0.05;
                break;
            case 2: // 高画质（推荐）- 优化后的值
                bitsPerPixelPerFrame = 0.07;
                break;
            case 3: // 最高画质 - 适当降低 bpp 减少CPU
                bitsPerPixelPerFrame = 0.10;
                break;
            default:
                bitsPerPixelPerFrame = 0.07;
        }

        // 根据分辨率调整 bpp：分辨率越高，bpp 适当降低（编码效率提升）
        long totalPixels = (long) width * height;
        if (totalPixels > 2073600) { // 超过 1080p (1920x1080)
            bitsPerPixelPerFrame *= 0.85; // 降低 15%
        } else if (totalPixels > 921600) { // 超过 720p (1280x720)
            bitsPerPixelPerFrame *= 0.90; // 降低 10%
        }

        // HEVC 效率更高，相同画质下使用 55% 的码率
        boolean isHevc = mimeType.equals(MIME_TYPE_HEVC);
        if (isHevc) {
            bitsPerPixelPerFrame *= 0.55;
        }

        // 计算总码率
        long bitrate = (long) (width * height * frameRate * bitsPerPixelPerFrame);

        // 严格设置码率上限（防止过高导致编码器卡顿）
        int maxAllowedBitrate = isHevc ? MAX_HEVC_BITRATE : MAX_BITRATE;
        bitrate = Math.min(bitrate, maxAllowedBitrate);

        // 设置码率下限（保证基本画质）
        int minAllowedBitrate = isHevc ? 1000000 : 1500000; // HEVC 1Mbps, H.264 1.5Mbps
        bitrate = Math.max(bitrate, minAllowedBitrate);

        // 四舍五入到 100Kbps，便于日志阅读
        bitrate = ((bitrate + 50000) / 100000) * 100000;

        return (int) bitrate;
    }

    /**
     * 创建 MediaMuxer
     */
    private void createMuxer(String filePath) throws IOException {
        muxer = new MediaMuxer(filePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
        videoTrackIndex = -1;
        muxerStarted = false;

        AppLog.d(TAG, "Camera " + cameraId + " Muxer created: " + filePath);
    }

    // 注意：encodingLoop() 方法已被移除
    // 帧处理现在直接在 onFrameAvailable 回调中完成
    // 这样可以避免 Handler 死锁问题

    /**
     * 排空编码器输出（优化版 - 不降低画质）
     * 
     * 优化策略：
     * - 使用批量处理，一次 drain 最多处理 DRAIN_BATCH_SIZE 帧
     * - 使用零超时非阻塞模式，快速返回避免阻塞渲染线程
     * - 捕获 IllegalStateException 并标记编码器不健康
     * - 跟踪无输出的帧数，用于健康检查
     */
    private void drainEncoder(boolean endOfStream) {
        if (encoder == null) {
            return;
        }

        // 优化：使用零超时非阻塞模式，快速检查是否有输出
        final int TIMEOUT_USEC = endOfStream ? 10000 : 0;  // 结束状态等待，正常状态非阻塞
        boolean gotOutput = false;
        int processedFrames = 0;  // 本次 drain 已处理帧数

        try {
            while (processedFrames < DRAIN_BATCH_SIZE) {  // 批量处理限制
                int outputBufferIndex;
                try {
                    outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                } catch (IllegalStateException e) {
                    // 编码器处于无效状态，标记为不健康
                    AppLog.e(TAG, "Camera " + cameraId + " Encoder in invalid state during dequeueOutputBuffer", e);
                    encoderHealthy = false;
                    return;
                }

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) {
                        break;  // 没有数据了，快速返回
                    }
                    // 结束状态且超时，继续等待
                    if (processedFrames > 0) {
                        break;  // 已经处理了一些帧，可以返回了
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    // 输出格式变化，添加视频轨道
                    if (muxerStarted) {
                        AppLog.w(TAG, "Camera " + cameraId + " Format changed twice");
                    } else {
                        MediaFormat newFormat = encoder.getOutputFormat();
                        videoTrackIndex = muxer.addTrack(newFormat);
                        muxer.start();
                        muxerStarted = true;
                        encoderHealthy = true;  // 收到格式变化说明编码器正常
                        lastEncoderOutputTime = System.currentTimeMillis();
                        AppLog.d(TAG, "Camera " + cameraId + " Muxer started, track=" + videoTrackIndex);
                    }
                    gotOutput = true;
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);

                    if (encodedData == null) {
                        AppLog.e(TAG, "Camera " + cameraId + " Encoder output buffer " + outputBufferIndex + " was null");
                    } else if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                        // 配置数据，忽略（已在 FORMAT_CHANGED 中处理）
                        bufferInfo.size = 0;
                    }

                    if (bufferInfo.size != 0) {
                        if (!muxerStarted) {
                            AppLog.e(TAG, "Camera " + cameraId + " Muxer not started but got data");
                        } else {
                            // 性能优化：使用基于帧数的 PTS 计算，减少 System.nanoTime() 调用
                            // 假设目标帧率为 25fps，每帧间隔 40000 微秒
                            // 优点：
                            //   1. 大幅减少系统调用开销
                            //   2. 时间戳单调递增，不会出现乱序
                            //   3. 掉帧时时间轴仍然正确
                            long calculatedPtsUs = encodedOutputFrameCount * 40000L;
                            
                            // 调试日志（仅第一帧）
                            if (encodedOutputFrameCount == 0) {
                                AppLog.d(TAG, "Camera " + cameraId + " First frame PTS: " + calculatedPtsUs + " us");
                            }
                            
                            // 使用计算的时间戳
                            bufferInfo.presentationTimeUs = calculatedPtsUs;
                            
                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);
                            
                            encodedOutputFrameCount++;
                            lastEncoderOutputTime = System.currentTimeMillis();
                            gotOutput = true;
                            processedFrames++;  // 增加已处理帧计数
                        }
                    }

                    try {
                        encoder.releaseOutputBuffer(outputBufferIndex, false);
                    } catch (IllegalStateException e) {
                        AppLog.e(TAG, "Camera " + cameraId + " Encoder in invalid state during releaseOutputBuffer", e);
                        encoderHealthy = false;
                        return;
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;  // 流结束
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Unexpected error in drainEncoder", e);
            encoderHealthy = false;
        }

        // 更新无输出帧计数器
        if (gotOutput) {
            framesWithoutEncoderOutput = 0;
        } else {
            framesWithoutEncoderOutput++;
        }
    }

    /**
     * 排空编码器输出（带返回值）
     * @param endOfStream 是否结束流
     * @return 是否有输出数据
     */
    private boolean drainEncoderWithResult(boolean endOfStream) {
        if (encoder == null) {
            return false;
        }

        final int TIMEOUT_USEC = 10000;
        boolean gotOutput = false;

        try {
            while (true) {
                int outputBufferIndex;
                try {
                    outputBufferIndex = encoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_USEC);
                } catch (IllegalStateException e) {
                    encoderHealthy = false;
                    return false;
                }

                if (outputBufferIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) {
                        break;
                    }
                } else if (outputBufferIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (!muxerStarted) {
                        MediaFormat newFormat = encoder.getOutputFormat();
                        videoTrackIndex = muxer.addTrack(newFormat);
                        muxer.start();
                        muxerStarted = true;
                        encoderHealthy = true;
                        lastEncoderOutputTime = System.currentTimeMillis();
                    }
                    gotOutput = true;
                } else if (outputBufferIndex >= 0) {
                    ByteBuffer encodedData = encoder.getOutputBuffer(outputBufferIndex);

                    if (encodedData != null && bufferInfo.size != 0) {
                        if (muxerStarted) {
                            // 性能优化：使用基于帧数的 PTS 计算，减少 System.nanoTime() 调用开销
                            // 与 drainEncoder 中的计算方式保持一致
                            long calculatedPtsUs = encodedOutputFrameCount * 40000L;
                            bufferInfo.presentationTimeUs = calculatedPtsUs;

                            encodedData.position(bufferInfo.offset);
                            encodedData.limit(bufferInfo.offset + bufferInfo.size);
                            muxer.writeSampleData(videoTrackIndex, encodedData, bufferInfo);

                            encodedOutputFrameCount++;
                            lastEncoderOutputTime = System.currentTimeMillis();
                            gotOutput = true;
                        }
                    }

                    try {
                        encoder.releaseOutputBuffer(outputBufferIndex, false);
                    } catch (IllegalStateException e) {
                        encoderHealthy = false;
                        return gotOutput;
                    }

                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Error in drainEncoderWithResult", e);
            encoderHealthy = false;
        }

        return gotOutput;
    }

    /**
     * 调度下一段录制
     * 
     * 注意：分段时长需要加上补偿时间，因为：
     * 1. 编码器初始化需要时间
     * 2. 停止时需要排空编码器缓冲区
     * 3. 这样可以确保实际录制的视频时长达到设定的分段时长
     */
    private void scheduleNextSegment() {
        if (segmentRunnable != null) {
            segmentHandler.removeCallbacks(segmentRunnable);
        }

        segmentRunnable = () -> {
            if (isRecording.get() && encoderHandler != null) {
                AppLog.d(TAG, "Camera " + cameraId + " Scheduling segment switch on encoder thread");
                // 在编码线程上执行切换，避免线程冲突
                encoderHandler.post(() -> switchToNextSegment());
            }
        };

        // 延迟执行（使用配置的分段时长 + 补偿时间）
        // 补偿编码器初始化延迟和停止时的帧丢失
        long actualDelayMs = segmentDurationMs + SEGMENT_DURATION_COMPENSATION_MS;
        segmentHandler.postDelayed(segmentRunnable, actualDelayMs);
        AppLog.d(TAG, "Camera " + cameraId + " Scheduled next segment in " + (segmentDurationMs / 1000) + " seconds (actual delay: " + actualDelayMs + "ms)");
    }

    /**
     * 切换到下一段（在编码线程上执行）
     * 
     * 采用简单方案：完整停止当前录制，然后重新开始
     * 类似 MediaRecorder 的方式，虽然会丢失几帧，但更简单可靠
     * 
     * 快速恢复机制：
     * - 成功时：重置恢复计数器，调度正常的1分钟定时器
     * - 失败时：使用5秒快速重试，最多重试6次（30秒内），之后回到正常1分钟间隔
     */
    private void switchToNextSegment() {
        // 检查是否仍在录制状态（防止与 stopRecording 竞态）
        if (!isRecording.get() || isReleased) {
            AppLog.w(TAG, "Camera " + cameraId + " Skipping segment switch (not recording or released)");
            return;
        }
        
        AppLog.d(TAG, "Camera " + cameraId + " Starting segment switch on encoder thread");
        
        boolean switchSuccess = false;
        
        try {
            // 1. 停止当前录制（会排空编码器、停止 Muxer）
            stopRecordingForSegmentSwitch();
            
            // 2. 验证当前文件（在主线程上执行，因为是 IO 操作）
            final String previousFilePath = currentFilePath;
            segmentHandler.post(() -> validateAndCleanupFile(previousFilePath));

            // 3. 准备下一段
            segmentIndex++;
            String nextSegmentPath = generateSegmentPath();
            currentFilePath = nextSegmentPath;
            recordedFilePaths.add(nextSegmentPath);  // 记录新分段文件
            
            // 重置分段开始时间和帧计数
            segmentStartTimeNs = System.nanoTime();
            encodedOutputFrameCount = 0;
            // 不重置 firstFrameTimestampNs，保持 EGL 时间戳单调递增

            // 4. 创建新的 Muxer
            createMuxer(nextSegmentPath);
            
            // 5. 重新开始录制
            isRecording.set(true);
            switchSuccess = true;
            
            // 成功：重置恢复计数器
            recoveryAttempts = 0;
            
            AppLog.d(TAG, "Camera " + cameraId + " Switched to segment " + segmentIndex + ": " + nextSegmentPath);

            if (callback != null) {
                final int newIndex = segmentIndex;
                final String completedPath = previousFilePath;  // 已完成的文件路径
                segmentHandler.post(() -> callback.onSegmentSwitch(cameraId, newIndex, completedPath));
            }

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to switch segment (attempt " + (recoveryAttempts + 1) + ")", e);
            
            // 标记录制状态（允许帧回调继续消费帧）
            isRecording.set(false);
            
            if (callback != null) {
                final String errorMsg = e.getMessage();
                segmentHandler.post(() -> callback.onRecordError(cameraId, "Failed to switch segment: " + errorMsg));
            }
        }
        
        // 6. 根据结果调度下一次操作
        if (switchSuccess) {
            // 成功：调度正常的1分钟定时器
            segmentHandler.post(() -> scheduleNextSegment());
        } else {
            // 失败：启动快速恢复机制
            recoveryAttempts++;
            if (recoveryAttempts <= MAX_RECOVERY_ATTEMPTS) {
                // 快速重试（5秒后）
                AppLog.w(TAG, "Camera " + cameraId + " Segment switch failed, quick retry in " 
                    + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
                scheduleRecoveryRetry();
            } else {
                // 超过最大重试次数，回到正常分段间隔
                AppLog.w(TAG, "Camera " + cameraId + " Max recovery attempts reached, will retry in " 
                    + (segmentDurationMs / 1000) + " seconds");
                recoveryAttempts = 0;  // 重置计数器
                segmentHandler.post(() -> scheduleNextSegment());
            }
        }
    }
    
    /**
     * 调度快速恢复重试
     */
    private void scheduleRecoveryRetry() {
        // 取消之前的恢复任务
        if (recoveryRunnable != null) {
            segmentHandler.removeCallbacks(recoveryRunnable);
        }
        
        recoveryRunnable = () -> {
            if (!isReleased && encoderHandler != null) {
                AppLog.d(TAG, "Camera " + cameraId + " Recovery retry triggered");
                // 在编码线程上执行恢复
                encoderHandler.post(() -> attemptRecovery());
            }
        };
        
        segmentHandler.postDelayed(recoveryRunnable, RECOVERY_RETRY_INTERVAL_MS);
    }
    
    /**
     * 尝试恢复录制
     */
    private void attemptRecovery() {
        AppLog.d(TAG, "Camera " + cameraId + " Attempting recovery (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
        
        boolean recoverySuccess = false;
        
        try {
            // 确保编码器和 EGL 已准备好
            if (encoder == null) {
                createEncoder();
                if (eglEncoder != null && encoderInputSurface != null) {
                    eglEncoder.updateOutputSurface(encoderInputSurface);
                }
            }
            
            // 创建新的 Muxer
            if (muxer == null) {
                String nextSegmentPath = generateSegmentPath();
                currentFilePath = nextSegmentPath;
                createMuxer(nextSegmentPath);
            }
            
            // 重置分段开始时间和帧计数
            segmentStartTimeNs = System.nanoTime();
            encodedOutputFrameCount = 0;
            
            // 恢复录制
            isRecording.set(true);
            recoverySuccess = true;
            
            // 成功：重置恢复计数器
            recoveryAttempts = 0;
            
            AppLog.d(TAG, "Camera " + cameraId + " Recovery successful, recording resumed: " + currentFilePath);
            
            // 调度正常的1分钟定时器
            segmentHandler.post(() -> scheduleNextSegment());
            
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Recovery attempt failed", e);
            isRecording.set(false);
            
            // 继续快速重试或回到正常间隔
            recoveryAttempts++;
            if (recoveryAttempts <= MAX_RECOVERY_ATTEMPTS) {
                AppLog.w(TAG, "Camera " + cameraId + " Recovery failed, quick retry in " 
                    + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
                scheduleRecoveryRetry();
            } else {
                AppLog.w(TAG, "Camera " + cameraId + " Max recovery attempts reached, will retry in " 
                    + (segmentDurationMs / 1000) + " seconds");
                recoveryAttempts = 0;
                segmentHandler.post(() -> scheduleNextSegment());
            }
        }
    }
    
    /**
     * 为分段切换停止录制（在编码线程上执行）
     * 完整停止并重新创建编码器
     * 
     * 注意：此方法有完善的异常处理，即使部分操作失败也会继续执行
     */
    private void stopRecordingForSegmentSwitch() {
        AppLog.d(TAG, "Camera " + cameraId + " Stopping recording for segment switch");
        
        // 1. 停止录制（阻止新帧写入）
        isRecording.set(false);
        
        // 2. 排空编码器（drainEncoder 现在在同一线程执行，不会有竞争）
        if (encoder != null) {
            try {
                drainEncoder(false);  // 先排空已有数据
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " Error draining encoder during segment switch", e);
            }
        }
        
        // 3. 停止 Muxer（即使失败也继续）
        if (muxer != null) {
            try {
                if (muxerStarted) {
                    muxer.stop();
                }
                muxer.release();
            } catch (Exception e) {
                AppLog.e(TAG, "Camera " + cameraId + " Error stopping muxer during segment switch", e);
            }
            muxer = null;
            muxerStarted = false;
            videoTrackIndex = -1;
        }
        
        // 4. 释放旧编码器（即使失败也继续）
        if (encoder != null) {
            try {
                encoder.stop();
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " Error stopping encoder: " + e.getMessage());
            }
            try {
                encoder.release();
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " Error releasing encoder: " + e.getMessage());
            }
            encoder = null;
        }
        
        if (encoderInputSurface != null) {
            try {
                encoderInputSurface.release();
            } catch (Exception e) {
                AppLog.w(TAG, "Camera " + cameraId + " Error releasing encoder surface: " + e.getMessage());
            }
            encoderInputSurface = null;
        }
        
        // 5. 重新创建编码器
        try {
            createEncoder();
            
            // 重新设置 EGL 的输出 Surface
            if (eglEncoder != null && encoderInputSurface != null) {
                eglEncoder.updateOutputSurface(encoderInputSurface);
            }
            
            AppLog.d(TAG, "Camera " + cameraId + " Encoder recreated for new segment");
            
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to recreate encoder", e);
            // 抛出异常，让调用者处理
            throw new RuntimeException("Failed to recreate encoder for segment switch", e);
        }
    }

    /**
     * 生成新的分段文件路径
     * 优先使用 TimestampProvider 获取统一时间戳（多路摄像头同步）
     * 如果没有设置 provider，则使用当前时间
     */
    private String generateSegmentPath() {
        String timestamp;
        if (timestampProvider != null) {
            // 使用统一的时间戳提供者（确保多路摄像头使用相同时间戳）
            timestamp = timestampProvider.getSegmentTimestamp();
            AppLog.d(TAG, "Camera " + cameraId + " using provider timestamp: " + timestamp);
        } else {
            // 回退到独立生成时间戳（兼容旧逻辑）
            timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
            AppLog.d(TAG, "Camera " + cameraId + " using local timestamp: " + timestamp);
        }
        String fileName = timestamp + "_" + cameraPosition + ".mp4";
        return new File(saveDirectory, fileName).getAbsolutePath();
    }

    /**
     * 调度编码器健康检查
     * 检测编码器是否正常工作，如果长时间无输出则尝试重建
     */
    private void scheduleEncoderHealthCheck() {
        if (healthCheckRunnable != null) {
            segmentHandler.removeCallbacks(healthCheckRunnable);
        }

        healthCheckRunnable = () -> {
            if (!isRecording.get() || isReleased) {
                return;
            }

            // 检查编码器健康状态
            boolean needsRecovery = false;
            String reason = "";

            if (!encoderHealthy) {
                needsRecovery = true;
                reason = "encoder marked unhealthy";
            } else if (!muxerStarted && recordedFrameCount > MAX_FRAMES_WITHOUT_OUTPUT) {
                // Muxer 从未启动，但已经处理了很多帧
                needsRecovery = true;
                reason = "muxer never started after " + recordedFrameCount + " frames";
            } else if (framesWithoutEncoderOutput > MAX_FRAMES_WITHOUT_OUTPUT) {
                needsRecovery = true;
                reason = "no encoder output for " + framesWithoutEncoderOutput + " frames";
            }

            if (needsRecovery) {
                AppLog.w(TAG, "Camera " + cameraId + " Encoder health check FAILED: " + reason);
                AppLog.w(TAG, "Camera " + cameraId + " Attempting to rebuild encoder...");

                // 在编码线程上执行重建
                if (encoderHandler != null) {
                    encoderHandler.post(() -> rebuildEncoder());
                }
            } else {
                // 编码器健康，继续调度下一次检查
                scheduleEncoderHealthCheck();
            }
        };

        segmentHandler.postDelayed(healthCheckRunnable, ENCODER_HEALTH_CHECK_INTERVAL_MS);
    }

    /**
     * 重建编码器（在编码线程上执行）
     * 当检测到编码器不健康时调用
     */
    private void rebuildEncoder() {
        AppLog.d(TAG, "Camera " + cameraId + " Rebuilding encoder due to health check failure");

        // 暂停录制
        isRecording.set(false);

        try {
            // 1. 清理旧的 Muxer（可能已损坏）
            if (muxer != null) {
                try {
                    if (muxerStarted) {
                        muxer.stop();
                    }
                    muxer.release();
                } catch (Exception e) {
                    AppLog.w(TAG, "Camera " + cameraId + " Error releasing old muxer: " + e.getMessage());
                }
                muxer = null;
                muxerStarted = false;
                videoTrackIndex = -1;
            }

            // 2. 清理旧的编码器
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Exception e) {
                    // Ignore
                }
                try {
                    encoder.release();
                } catch (Exception e) {
                    // Ignore
                }
                encoder = null;
            }

            if (encoderInputSurface != null) {
                try {
                    encoderInputSurface.release();
                } catch (Exception e) {
                    // Ignore
                }
                encoderInputSurface = null;
            }

            // 3. 小延迟让系统释放资源
            Thread.sleep(100);

            // 4. 重新创建编码器
            createEncoder();

            // 5. 更新 EGL 输出 Surface
            if (eglEncoder != null && encoderInputSurface != null) {
                eglEncoder.updateOutputSurface(encoderInputSurface);
            }

            // 6. 创建新的 Muxer（生成新的文件名）
            segmentIndex++;
            String newFilePath = generateSegmentPath();
            currentFilePath = newFilePath;
            recordedFilePaths.add(newFilePath);
            createMuxer(newFilePath);

            // 7. 重置状态
            segmentStartTimeNs = System.nanoTime();
            encodedOutputFrameCount = 0;
            framesWithoutEncoderOutput = 0;
            encoderHealthy = true;
            lastEncoderOutputTime = System.currentTimeMillis();

            // 8. 恢复录制
            isRecording.set(true);

            AppLog.d(TAG, "Camera " + cameraId + " Encoder rebuilt successfully, new file: " + newFilePath);

            // 9. 继续健康检查
            segmentHandler.post(() -> scheduleEncoderHealthCheck());

            // 10. 重新调度分段定时器
            segmentHandler.post(() -> scheduleNextSegment());

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to rebuild encoder", e);

            // 重建失败，启动恢复重试机制
            recoveryAttempts++;
            if (recoveryAttempts <= MAX_RECOVERY_ATTEMPTS) {
                AppLog.w(TAG, "Camera " + cameraId + " Will retry encoder rebuild in " 
                    + (RECOVERY_RETRY_INTERVAL_MS / 1000) + "s (attempt " + recoveryAttempts + "/" + MAX_RECOVERY_ATTEMPTS + ")");
                scheduleRecoveryRetry();
            } else {
                AppLog.e(TAG, "Camera " + cameraId + " Max recovery attempts reached, giving up");
                if (callback != null) {
                    final String errorMsg = e.getMessage();
                    segmentHandler.post(() -> callback.onRecordError(cameraId, "Encoder rebuild failed: " + errorMsg));
                }
            }
        }
    }

    /**
     * 调度文件大小检查
     */
    private void scheduleFileSizeCheck() {
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
                    // 首次写入检测
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
                    AppLog.d(TAG, "Camera " + cameraId + " file size: " + currentSize + " bytes (" + (currentSize / 1024) + " KB), frames: " + recordedFrameCount);
                } else if (sizeIncrease == 0 && lastFileSize > 0) {
                    AppLog.w(TAG, "Camera " + cameraId + " WARNING: File size not growing! Current: " + currentSize + " bytes");
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
                AppLog.e(TAG, "Camera " + cameraId + " FIRST WRITE TIMEOUT: No data written in " + (FIRST_WRITE_TIMEOUT_MS / 1000) + " seconds");
                // 触发编码器重建（通过健康检查机制处理）
                encoderHealthy = false;
                // 也可以通过回调通知外部
                if (callback != null) {
                    segmentHandler.post(() -> callback.onRecordingRebuildRequested(cameraId, "first_write_timeout"));
                }
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
     * 验证并清理损坏的文件
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
            AppLog.w(TAG, "Camera " + cameraId + " Video file too small: " + filePath + " (" + fileSize + " bytes). Deleting...");
            file.delete();
            return file.getName();
        } else {
            AppLog.d(TAG, "Camera " + cameraId + " Video file validated: " + filePath + " (" + (fileSize / 1024) + " KB)");
            return null;
        }
    }
}
