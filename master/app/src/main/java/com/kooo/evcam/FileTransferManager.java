package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 文件传输管理器
 * 负责将临时目录中的视频文件异步传输到目标存储（如U盘）
 * 
 * 工作原理：
 * 1. 录制时先写入内部存储的临时目录（高速）
 * 2. 分段完成后，将文件加入传输队列
 * 3. 后台线程负责将文件移动/复制到目标目录
 * 4. 传输完成后删除临时文件
 * 
 * 这样可以避免U盘慢速写入影响录制性能
 */
public class FileTransferManager {
    private static final String TAG = "FileTransferManager";
    
    // 临时目录名称（在内部存储的应用缓存目录下）
    public static final String TEMP_VIDEO_DIR = "temp_video";
    
    // 传输任务
    private static class TransferTask {
        final File sourceFile;      // 源文件（临时目录中）
        final File targetFile;      // 目标文件（最终存储位置）
        final TransferCallback callback;
        int retryCount;             // 重试次数
        
        TransferTask(File source, File target, TransferCallback callback) {
            this.sourceFile = source;
            this.targetFile = target;
            this.callback = callback;
            this.retryCount = 0;
        }
    }
    
    // 传输回调
    public interface TransferCallback {
        void onTransferComplete(File sourceFile, File targetFile);
        void onTransferFailed(File sourceFile, File targetFile, String error);
    }
    
    // 单例
    private static FileTransferManager instance;
    
    private final Context context;
    private final ConcurrentLinkedQueue<TransferTask> transferQueue;
    private HandlerThread transferThread;
    private Handler transferHandler;
    private final AtomicBoolean isRunning;
    private final AtomicBoolean isProcessing;
    
    // 配置
    private static final int MAX_RETRY_COUNT = 3;           // 最大重试次数
    private static final long RETRY_DELAY_MS = 5000;        // 重试延迟（毫秒）
    private static final long TRANSFER_CHECK_INTERVAL_MS = 1000;  // 检查队列间隔
    private static final long STARTUP_CLEANUP_DELAY_MS = 60 * 1000;  // 启动后清理延迟：1分钟
    private static final long TEMP_FILE_EXPIRE_MS = 60 * 60 * 1000;  // 临时文件过期时间：1小时
    
    // 统计
    private long totalTransferred = 0;      // 已传输文件数
    private long totalFailed = 0;           // 失败文件数
    private long totalBytesTransferred = 0; // 已传输字节数
    
    private FileTransferManager(Context context) {
        this.context = context.getApplicationContext();
        this.transferQueue = new ConcurrentLinkedQueue<>();
        this.isRunning = new AtomicBoolean(false);
        this.isProcessing = new AtomicBoolean(false);
    }
    
    /**
     * 获取单例实例
     */
    public static synchronized FileTransferManager getInstance(Context context) {
        if (instance == null) {
            instance = new FileTransferManager(context);
        }
        return instance;
    }
    
    /**
     * 启动传输服务
     */
    public void start() {
        if (isRunning.getAndSet(true)) {
            AppLog.d(TAG, "Transfer service already running");
            return;
        }
        
        transferThread = new HandlerThread("FileTransfer");
        transferThread.start();
        transferHandler = new Handler(transferThread.getLooper());
        
        // 启动定期检查队列
        scheduleNextCheck();
        
        // 启动后1分钟检查并清理过期的临时文件
        transferHandler.postDelayed(this::cleanupExpiredTempFiles, STARTUP_CLEANUP_DELAY_MS);
        
        AppLog.d(TAG, "File transfer service started");
    }
    
    /**
     * 停止传输服务
     */
    public void stop() {
        if (!isRunning.getAndSet(false)) {
            return;
        }
        
        if (transferHandler != null) {
            transferHandler.removeCallbacksAndMessages(null);
            transferHandler = null;
        }
        
        if (transferThread != null) {
            transferThread.quitSafely();
            try {
                transferThread.join(1000);
            } catch (InterruptedException e) {
                // Ignore
            }
            transferThread = null;
        }
        
        AppLog.d(TAG, "File transfer service stopped. Stats: transferred=" + totalTransferred + 
                ", failed=" + totalFailed + ", bytes=" + formatSize(totalBytesTransferred));
    }
    
    /**
     * 添加传输任务
     * @param sourceFile 源文件（临时目录中）
     * @param targetFile 目标文件（最终位置）
     * @param callback 回调（可为null）
     */
    public void addTransferTask(File sourceFile, File targetFile, TransferCallback callback) {
        if (sourceFile == null || !sourceFile.exists()) {
            AppLog.w(TAG, "Source file does not exist: " + sourceFile);
            if (callback != null) {
                callback.onTransferFailed(sourceFile, targetFile, "Source file not found");
            }
            return;
        }
        
        TransferTask task = new TransferTask(sourceFile, targetFile, callback);
        transferQueue.offer(task);
        
        AppLog.d(TAG, "Added transfer task: " + sourceFile.getName() + " -> " + targetFile.getAbsolutePath());
        
        // 如果服务在运行，立即触发处理
        if (isRunning.get() && transferHandler != null) {
            transferHandler.post(this::processQueue);
        }
    }
    
    /**
     * 获取临时视频目录
     * @return 临时目录，如果创建失败返回null
     */
    public File getTempVideoDir() {
        File tempDir = new File(context.getCacheDir(), TEMP_VIDEO_DIR);
        if (!tempDir.exists()) {
            if (!tempDir.mkdirs()) {
                AppLog.e(TAG, "Failed to create temp video directory: " + tempDir.getAbsolutePath());
                return null;
            }
        }
        return tempDir;
    }
    
    /**
     * 获取临时目录中的文件数量
     */
    public int getPendingFileCount() {
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return 0;
        }
        File[] files = tempDir.listFiles();
        return files != null ? files.length : 0;
    }
    
    /**
     * 获取临时目录占用的空间（字节）
     */
    public long getTempDirSize() {
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return 0;
        }
        
        long size = 0;
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                size += file.length();
            }
        }
        return size;
    }
    
    /**
     * 清理临时目录（删除所有文件）
     * 注意：仅在确认不需要这些文件时调用
     */
    public void clearTempDir() {
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return;
        }
        
        File[] files = tempDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.delete()) {
                    AppLog.d(TAG, "Deleted temp file: " + file.getName());
                }
            }
        }
        
        AppLog.d(TAG, "Temp directory cleared");
    }
    
    /**
     * 获取队列中等待传输的任务数
     */
    public int getQueueSize() {
        return transferQueue.size();
    }
    
    /**
     * 获取传输统计信息
     */
    public String getStats() {
        return String.format("已传输: %d 个文件 (%s), 失败: %d, 队列: %d, 临时文件: %d",
                totalTransferred, formatSize(totalBytesTransferred), 
                totalFailed, getQueueSize(), getPendingFileCount());
    }
    
    // ===== 私有方法 =====
    
    /**
     * 调度下一次队列检查
     */
    private void scheduleNextCheck() {
        if (!isRunning.get() || transferHandler == null) {
            return;
        }
        
        transferHandler.postDelayed(() -> {
            if (isRunning.get()) {
                processQueue();
                scheduleNextCheck();
            }
        }, TRANSFER_CHECK_INTERVAL_MS);
    }
    
    /**
     * 清理过期的临时文件
     * 在服务运行期间定期调用
     */
    private void cleanupExpiredTempFiles() {
        File tempDir = getTempVideoDir();
        if (tempDir == null || !tempDir.exists()) {
            return;
        }
        
        File[] files = tempDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }
        
        long now = System.currentTimeMillis();
        int deletedCount = 0;
        long deletedSize = 0;
        
        for (File file : files) {
            long fileAge = now - file.lastModified();
            if (fileAge > TEMP_FILE_EXPIRE_MS) {
                long fileSize = file.length();
                if (file.delete()) {
                    deletedCount++;
                    deletedSize += fileSize;
                    AppLog.d(TAG, "Cleanup: deleted expired temp file: " + file.getName());
                }
            }
        }
        
        if (deletedCount > 0) {
            AppLog.d(TAG, "Periodic cleanup: deleted " + deletedCount + " expired files, freed " + formatSize(deletedSize));
        }
    }
    
    /**
     * 处理传输队列
     */
    private void processQueue() {
        if (!isRunning.get() || isProcessing.getAndSet(true)) {
            return;
        }
        
        try {
            TransferTask task;
            while ((task = transferQueue.poll()) != null) {
                if (!isRunning.get()) {
                    // 服务停止，将任务放回队列
                    transferQueue.offer(task);
                    break;
                }
                
                processTask(task);
            }
        } finally {
            isProcessing.set(false);
        }
    }
    
    /**
     * 处理单个传输任务
     */
    private void processTask(TransferTask task) {
        if (!task.sourceFile.exists()) {
            AppLog.w(TAG, "Source file no longer exists: " + task.sourceFile.getName());
            if (task.callback != null) {
                task.callback.onTransferFailed(task.sourceFile, task.targetFile, "Source file not found");
            }
            totalFailed++;
            return;
        }
        
        // 确保目标目录存在
        File targetDir = task.targetFile.getParentFile();
        if (targetDir != null && !targetDir.exists()) {
            if (!targetDir.mkdirs()) {
                AppLog.e(TAG, "Failed to create target directory: " + targetDir.getAbsolutePath());
                handleTransferFailure(task, "Cannot create target directory");
                return;
            }
        }
        
        // 尝试移动文件（如果在同一文件系统，这是最快的）
        boolean moved = task.sourceFile.renameTo(task.targetFile);
        
        if (moved) {
            // 移动成功
            long fileSize = task.targetFile.length();
            AppLog.d(TAG, "File moved successfully: " + task.sourceFile.getName() + 
                    " -> " + task.targetFile.getAbsolutePath() + " (" + formatSize(fileSize) + ")");
            
            totalTransferred++;
            totalBytesTransferred += fileSize;
            
            if (task.callback != null) {
                task.callback.onTransferComplete(task.sourceFile, task.targetFile);
            }
        } else {
            // 移动失败（可能跨文件系统），尝试复制
            AppLog.d(TAG, "Move failed, trying copy: " + task.sourceFile.getName());
            
            boolean copied = copyFile(task.sourceFile, task.targetFile);
            
            if (copied) {
                // 复制成功，删除源文件
                long fileSize = task.targetFile.length();
                
                if (task.sourceFile.delete()) {
                    AppLog.d(TAG, "File copied and source deleted: " + task.sourceFile.getName() + 
                            " -> " + task.targetFile.getAbsolutePath() + " (" + formatSize(fileSize) + ")");
                } else {
                    AppLog.w(TAG, "File copied but failed to delete source: " + task.sourceFile.getName());
                }
                
                totalTransferred++;
                totalBytesTransferred += fileSize;
                
                if (task.callback != null) {
                    task.callback.onTransferComplete(task.sourceFile, task.targetFile);
                }
            } else {
                // 复制也失败
                handleTransferFailure(task, "Copy failed");
            }
        }
    }
    
    /**
     * 处理传输失败
     */
    private void handleTransferFailure(TransferTask task, String error) {
        task.retryCount++;
        
        if (task.retryCount < MAX_RETRY_COUNT) {
            // 重试
            AppLog.w(TAG, "Transfer failed, will retry (" + task.retryCount + "/" + MAX_RETRY_COUNT + "): " + 
                    task.sourceFile.getName() + " - " + error);
            
            // 延迟后重新加入队列
            if (transferHandler != null) {
                transferHandler.postDelayed(() -> {
                    transferQueue.offer(task);
                }, RETRY_DELAY_MS);
            }
        } else {
            // 超过重试次数，放弃
            AppLog.e(TAG, "Transfer failed after " + MAX_RETRY_COUNT + " retries: " + 
                    task.sourceFile.getName() + " - " + error);
            
            totalFailed++;
            
            if (task.callback != null) {
                task.callback.onTransferFailed(task.sourceFile, task.targetFile, error);
            }
        }
    }
    
    /**
     * 复制文件（使用 NIO Channel，效率较高）
     */
    private boolean copyFile(File source, File target) {
        FileChannel sourceChannel = null;
        FileChannel targetChannel = null;
        
        try {
            sourceChannel = new FileInputStream(source).getChannel();
            targetChannel = new FileOutputStream(target).getChannel();
            
            long size = sourceChannel.size();
            long transferred = 0;
            
            // 分块传输，避免内存问题
            final long CHUNK_SIZE = 8 * 1024 * 1024;  // 8MB chunks
            
            while (transferred < size) {
                long remaining = size - transferred;
                long toTransfer = Math.min(remaining, CHUNK_SIZE);
                long actualTransferred = sourceChannel.transferTo(transferred, toTransfer, targetChannel);
                
                if (actualTransferred == 0) {
                    // 传输停滞，可能有问题
                    AppLog.w(TAG, "Transfer stalled at " + transferred + "/" + size);
                    break;
                }
                
                transferred += actualTransferred;
            }
            
            return transferred == size;
            
        } catch (IOException e) {
            AppLog.e(TAG, "Error copying file: " + source.getName(), e);
            
            // 删除可能不完整的目标文件
            if (target.exists()) {
                target.delete();
            }
            
            return false;
        } finally {
            try {
                if (sourceChannel != null) sourceChannel.close();
                if (targetChannel != null) targetChannel.close();
            } catch (IOException e) {
                // Ignore
            }
        }
    }
    
    /**
     * 格式化文件大小
     */
    private String formatSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        } else {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        }
    }
}
