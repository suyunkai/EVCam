package com.kooo.evcam;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 存储清理管理器
 * 自动删除超过限制的旧视频和图片文件
 * 
 * 功能：
 * - 冷启动30秒后执行首次检测
 * - 每隔1小时执行定期检测
 * - 支持分别设置视频和图片的存储限制（GB）
 * - 删除时额外删除20%，避免频繁删除
 */
public class StorageCleanupManager {
    private static final String TAG = "StorageCleanupManager";
    
    // 定时任务延迟
    private static final long INITIAL_DELAY_MS = 30 * 1000;  // 冷启动后30秒
    private static final long PERIODIC_INTERVAL_MS = 60 * 60 * 1000;  // 每1小时
    
    // 额外删除比例（20%）
    private static final double EXTRA_DELETE_RATIO = 0.20;
    
    // GB 转 字节
    private static final long GB_TO_BYTES = 1024L * 1024L * 1024L;
    
    // 内部存储低空间阈值（3GB）
    private static final long LOW_SPACE_THRESHOLD_BYTES = 3L * GB_TO_BYTES;
    
    // 低空间强制清理比例（删除20%的已用空间，保留80%）
    private static final double LOW_SPACE_CLEANUP_RATIO = 0.20;
    
    private final Context context;
    private final AppConfig appConfig;
    private ScheduledExecutorService scheduler;
    private Handler mainHandler;
    private boolean isRunning = false;
    
    public StorageCleanupManager(Context context) {
        this.context = context.getApplicationContext();
        this.appConfig = new AppConfig(context);
        this.mainHandler = new Handler(Looper.getMainLooper());
    }
    
    /**
     * 启动存储清理任务
     * 冷启动30秒后执行首次检测，之后每隔1小时执行一次
     * 注意：即使清理功能未启用，也会启动以检测内部存储低空间情况
     */
    public void start() {
        if (isRunning) {
            AppLog.d(TAG, "存储清理任务已在运行");
            return;
        }
        
        isRunning = true;
        scheduler = Executors.newSingleThreadScheduledExecutor();
        
        // 30秒后执行首次检测
        scheduler.schedule(this::performCleanup, INITIAL_DELAY_MS, TimeUnit.MILLISECONDS);
        
        // 每1小时执行一次定期检测
        scheduler.scheduleAtFixedRate(
            this::performCleanup,
            INITIAL_DELAY_MS + PERIODIC_INTERVAL_MS,  // 首次定期检测在首次检测后1小时
            PERIODIC_INTERVAL_MS,
            TimeUnit.MILLISECONDS
        );
        
        AppLog.d(TAG, "存储清理任务已启动：30秒后首次检测，之后每1小时检测一次");
        AppLog.d(TAG, "视频限制: " + appConfig.getVideoStorageLimitGb() + " GB, 图片限制: " + appConfig.getPhotoStorageLimitGb() + " GB");
    }
    
    /**
     * 停止存储清理任务
     */
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            scheduler = null;
        }
        isRunning = false;
        AppLog.d(TAG, "存储清理任务已停止");
    }
    
    /**
     * 执行清理任务
     */
    private void performCleanup() {
        AppLog.d(TAG, "开始执行存储清理检测...");
        
        // 首先检测内部存储低空间情况（强制清理）
        performLowSpaceCleanupIfNeeded();
        
        int videoLimitGb = appConfig.getVideoStorageLimitGb();
        int photoLimitGb = appConfig.getPhotoStorageLimitGb();
        
        // 检测并清理视频
        if (videoLimitGb > 0) {
            CleanupResult videoResult = cleanupDirectory(
                StorageHelper.getVideoDir(context),
                videoLimitGb * GB_TO_BYTES,
                "视频"
            );
            if (videoResult.deletedCount > 0) {
                showCleanupNotification(videoResult, "视频");
            }
        }
        
        // 检测并清理图片
        if (photoLimitGb > 0) {
            CleanupResult photoResult = cleanupDirectory(
                StorageHelper.getPhotoDir(context),
                photoLimitGb * GB_TO_BYTES,
                "图片"
            );
            if (photoResult.deletedCount > 0) {
                showCleanupNotification(photoResult, "图片");
            }
        }
        
        AppLog.d(TAG, "存储清理检测完成");
    }
    
    /**
     * 内部存储低空间时强制清理
     * 当使用内部存储且可用空间低于3GB时，强制清理20%的已用空间
     */
    private void performLowSpaceCleanupIfNeeded() {
        // 检测当前是否使用内部存储
        boolean usingInternal = !appConfig.isUsingExternalSdCard() || StorageHelper.isSdCardFallback(context);
        
        if (!usingInternal) {
            // 使用U盘，不需要强制清理
            return;
        }
        
        // 获取内部存储可用空间
        File internalDir = android.os.Environment.getExternalStorageDirectory();
        long availableSpace = StorageHelper.getAvailableSpace(internalDir);
        
        AppLog.d(TAG, "内部存储可用空间: " + StorageHelper.formatSize(availableSpace));
        
        if (availableSpace < 0 || availableSpace >= LOW_SPACE_THRESHOLD_BYTES) {
            // 空间充足，不需要清理
            return;
        }
        
        AppLog.w(TAG, "内部存储空间不足（<3GB），开始强制清理...");
        
        // 强制清理视频（删除20%的已用空间）
        File videoDir = StorageHelper.getVideoDir(context, false);
        CleanupResult videoResult = cleanupByPercentage(videoDir, LOW_SPACE_CLEANUP_RATIO, "视频");
        if (videoResult.deletedCount > 0) {
            showLowSpaceCleanupNotification(videoResult, "视频");
        }
        
        // 强制清理图片（删除20%的已用空间）
        File photoDir = StorageHelper.getPhotoDir(context, false);
        CleanupResult photoResult = cleanupByPercentage(photoDir, LOW_SPACE_CLEANUP_RATIO, "图片");
        if (photoResult.deletedCount > 0) {
            showLowSpaceCleanupNotification(photoResult, "图片");
        }
    }
    
    /**
     * 按比例清理目录（删除指定比例的已用空间）
     * @param directory 目标目录
     * @param deleteRatio 删除比例（0.0-1.0）
     * @param typeName 类型名称
     * @return 清理结果
     */
    private CleanupResult cleanupByPercentage(File directory, double deleteRatio, String typeName) {
        CleanupResult result = new CleanupResult();
        
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return result;
        }
        
        File[] files = directory.listFiles(File::isFile);
        if (files == null || files.length == 0) {
            return result;
        }
        
        // 计算当前总大小
        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }
        
        result.originalSize = totalSize;
        
        if (totalSize == 0) {
            return result;
        }
        
        // 计算需要删除的大小（总大小的指定比例）
        long needToDelete = (long) (totalSize * deleteRatio);
        long targetSize = totalSize - needToDelete;
        
        AppLog.d(TAG, typeName + "强制清理：当前占用 " + StorageHelper.formatSize(totalSize) + 
                "，将删除 " + StorageHelper.formatSize(needToDelete) + " (20%)");
        
        // 按修改时间排序（最旧的在前）
        List<File> sortedFiles = new ArrayList<>(Arrays.asList(files));
        sortedFiles.sort(Comparator.comparingLong(File::lastModified));
        
        // 删除最旧的文件直到达到目标大小
        long deletedSize = 0;
        int deletedCount = 0;
        
        for (File file : sortedFiles) {
            if (totalSize - deletedSize <= targetSize) {
                break;
            }
            
            long fileSize = file.length();
            if (file.delete()) {
                deletedSize += fileSize;
                deletedCount++;
                AppLog.d(TAG, "强制删除旧文件: " + file.getName() + " (" + StorageHelper.formatSize(fileSize) + ")");
            }
        }
        
        result.deletedSize = deletedSize;
        result.deletedCount = deletedCount;
        result.finalSize = totalSize - deletedSize;
        
        AppLog.d(TAG, typeName + "强制清理完成：删除 " + deletedCount + " 个文件，释放 " + StorageHelper.formatSize(deletedSize));
        
        return result;
    }
    
    /**
     * 显示低空间强制清理通知
     */
    private void showLowSpaceCleanupNotification(CleanupResult result, String typeName) {
        mainHandler.post(() -> {
            String message = "内部存储空间不足，已清理" + typeName + " " + 
                    result.deletedCount + "个文件（" + StorageHelper.formatSize(result.deletedSize) + "）";
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
        });
    }
    
    /**
     * 清理指定目录
     * @param directory 目标目录
     * @param limitBytes 限制大小（字节）
     * @param typeName 类型名称（用于日志）
     * @return 清理结果
     */
    private CleanupResult cleanupDirectory(File directory, long limitBytes, String typeName) {
        CleanupResult result = new CleanupResult();
        
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            AppLog.w(TAG, typeName + "目录不存在: " + (directory != null ? directory.getAbsolutePath() : "null"));
            return result;
        }
        
        // 获取目录中所有文件（不筛选格式）
        File[] files = directory.listFiles(File::isFile);
        
        if (files == null || files.length == 0) {
            AppLog.d(TAG, typeName + "目录为空");
            return result;
        }
        
        // 计算当前总大小
        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }
        
        result.originalSize = totalSize;
        
        AppLog.d(TAG, typeName + "当前占用: " + StorageHelper.formatSize(totalSize) + 
                " / 限制: " + StorageHelper.formatSize(limitBytes));
        
        // 如果未超过限制，无需清理
        if (totalSize <= limitBytes) {
            AppLog.d(TAG, typeName + "未超过限制，无需清理");
            return result;
        }
        
        // 计算目标大小（限制的80%，即额外删除20%）
        long targetSize = (long) (limitBytes * (1 - EXTRA_DELETE_RATIO));
        long needToDelete = totalSize - targetSize;
        
        AppLog.d(TAG, typeName + "超过限制，需要删除: " + StorageHelper.formatSize(needToDelete) + 
                "，目标大小: " + StorageHelper.formatSize(targetSize));
        
        // 按修改时间排序（最旧的在前）
        List<File> sortedFiles = new ArrayList<>(Arrays.asList(files));
        sortedFiles.sort(Comparator.comparingLong(File::lastModified));
        
        // 删除最旧的文件直到达到目标大小
        long deletedSize = 0;
        int deletedCount = 0;
        
        for (File file : sortedFiles) {
            if (totalSize - deletedSize <= targetSize) {
                break;
            }
            
            long fileSize = file.length();
            String fileName = file.getName();
            
            if (file.delete()) {
                deletedSize += fileSize;
                deletedCount++;
                AppLog.d(TAG, "已删除" + typeName + ": " + fileName + " (" + StorageHelper.formatSize(fileSize) + ")");
            } else {
                AppLog.w(TAG, "删除" + typeName + "失败: " + fileName);
            }
        }
        
        result.deletedCount = deletedCount;
        result.deletedSize = deletedSize;
        result.finalSize = totalSize - deletedSize;
        
        AppLog.d(TAG, typeName + "清理完成：删除 " + deletedCount + " 个文件，释放 " + 
                StorageHelper.formatSize(deletedSize) + "，剩余 " + StorageHelper.formatSize(result.finalSize));
        
        return result;
    }
    
    /**
     * 显示清理通知
     */
    private void showCleanupNotification(CleanupResult result, String typeName) {
        mainHandler.post(() -> {
            String message = "已清理" + typeName + "：删除 " + result.deletedCount + " 个文件，释放 " + 
                    StorageHelper.formatSize(result.deletedSize);
            Toast.makeText(context, message, Toast.LENGTH_LONG).show();
            AppLog.d(TAG, "清理通知: " + message);
        });
    }
    
    /**
     * 手动触发清理（用于测试或用户手动清理）
     */
    public void manualCleanup() {
        new Thread(this::performCleanup).start();
    }
    
    /**
     * 获取当前视频占用大小
     * @return 占用大小（字节）
     */
    public long getVideoUsedSize() {
        return getDirectorySize(StorageHelper.getVideoDir(context));
    }
    
    /**
     * 获取当前图片占用大小
     * @return 占用大小（字节）
     */
    public long getPhotoUsedSize() {
        return getDirectorySize(StorageHelper.getPhotoDir(context));
    }
    
    /**
     * 获取目录中所有文件的总大小
     */
    private long getDirectorySize(File directory) {
        if (directory == null || !directory.exists() || !directory.isDirectory()) {
            return 0;
        }
        
        File[] files = directory.listFiles(File::isFile);
        
        if (files == null) {
            return 0;
        }
        
        long totalSize = 0;
        for (File file : files) {
            totalSize += file.length();
        }
        return totalSize;
    }
    
    /**
     * 清理结果
     */
    private static class CleanupResult {
        long originalSize = 0;  // 清理前大小
        long deletedSize = 0;   // 删除的大小
        long finalSize = 0;     // 清理后大小
        int deletedCount = 0;   // 删除的文件数
    }
}
