package com.kooo.evcam;

import android.content.Context;
import android.os.Build;
import android.os.Environment;
import android.os.StatFs;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 存储帮助类
 * 提供U盘检测和存储路径管理功能
 * 
 * 性能优化：使用内存缓存减少重复的文件系统 I/O 操作
 */
public class StorageHelper {
    private static final String TAG = "StorageHelper";
    
    // 存储目录名称
    public static final String VIDEO_DIR_NAME = "EVCam_Video";
    public static final String PHOTO_DIR_NAME = "EVCam_Photo";
    public static final String LOG_DIR_NAME = "EVCam_Log";
    
    // ==================== 内存缓存（性能优化）====================
    // U盘检测结果缓存（避免重复的文件系统 I/O）
    private static volatile Boolean cachedHasSdCard = null;
    private static volatile File cachedSdCardRoot = null;
    private static volatile long cacheTimestamp = 0;
    private static final long CACHE_VALIDITY_MS = 5000;  // 缓存有效期：5秒
    
    // 用于同步的锁对象
    private static final Object cacheLock = new Object();
    
    /**
     * 清除内存缓存（U盘插拔时调用）
     */
    public static void clearCache() {
        synchronized (cacheLock) {
            cachedHasSdCard = null;
            cachedSdCardRoot = null;
            cacheTimestamp = 0;
            AppLog.d(TAG, "U盘检测缓存已清除");
        }
    }
    
    /**
     * 检查缓存是否有效
     */
    private static boolean isCacheValid() {
        return cacheTimestamp > 0 && (System.currentTimeMillis() - cacheTimestamp) < CACHE_VALIDITY_MS;
    }
    
    /**
     * 检测是否有U盘（并且可以写入公共目录）
     * 使用内存缓存，5秒内不重复检测
     * @param context 上下文
     * @return true 如果检测到U盘且可写入
     */
    public static boolean hasExternalSdCard(Context context) {
        // 先检查缓存
        synchronized (cacheLock) {
            if (isCacheValid() && cachedHasSdCard != null) {
                return cachedHasSdCard;
            }
        }
        
        // 缓存无效，执行检测
        File sdCardRoot = getExternalSdCardRoot(context);
        boolean result = false;
        
        if (sdCardRoot != null && sdCardRoot.exists()) {
            // 检查 DCIM 目录是否可写
            File dcimDir = new File(sdCardRoot, Environment.DIRECTORY_DCIM);
            if (!dcimDir.exists()) {
                // 尝试创建 DCIM 目录
                boolean created = dcimDir.mkdirs();
                if (!created) {
                    AppLog.w(TAG, "无法在U盘上创建 DCIM 目录");
                }
            }
            result = dcimDir.exists() && dcimDir.canWrite();
        }
        
        // 更新缓存
        synchronized (cacheLock) {
            cachedHasSdCard = result;
            cacheTimestamp = System.currentTimeMillis();
        }
        
        return result;
    }
    
    /**
     * 检测是否发生了U盘回退
     * 即：用户选择了U盘存储，但U盘不可用，实际使用内部存储
     * @param context 上下文
     * @return true 如果发生了回退
     */
    public static boolean isSdCardFallback(Context context) {
        if (context == null) return false;
        
        AppConfig config = new AppConfig(context);
        // 只有当用户选择了U盘时才需要检测回退
        if (!config.isUsingExternalSdCard()) {
            return false;
        }
        
        // 检测U盘是否可用
        return !hasExternalSdCard(context);
    }
    
    /**
     * 获取U盘路径
     * @param context 上下文
     * @return U盘根目录，如果没有则返回 null
     */
    public static File getExternalSdCardPath(Context context) {
        if (context == null) {
            return null;
        }
        
        try {
            // 获取所有外部存储设备
            File[] externalDirs = context.getExternalFilesDirs(null);
            
            if (externalDirs == null || externalDirs.length < 2) {
                AppLog.d(TAG, "未检测到U盘（仅有内部存储）");
                return null;
            }
            
            // 第一个是内部存储，第二个及以后是U盘
            for (int i = 1; i < externalDirs.length; i++) {
                File dir = externalDirs[i];
                if (dir != null && dir.exists()) {
                    // 尝试获取U盘根目录（去掉 /Android/data/包名/files 部分）
                    String path = dir.getAbsolutePath();
                    int index = path.indexOf("/Android/data/");
                    if (index > 0) {
                        File sdRoot = new File(path.substring(0, index));
                        if (sdRoot.exists() && sdRoot.canRead()) {
                            AppLog.d(TAG, "检测到U盘: " + sdRoot.getAbsolutePath());
                            return sdRoot;
                        }
                    }
                    
                    // 如果无法获取根目录，返回应用专属目录的上级目录
                    AppLog.d(TAG, "检测到U盘（应用目录）: " + dir.getAbsolutePath());
                    return dir;
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "检测U盘失败", e);
        }
        
        return null;
    }
    
    /**
     * 获取U盘的应用专属目录
     * @param context 上下文
     * @return U盘上的应用专属目录，如果没有则返回 null
     */
    public static File getExternalSdCardAppDir(Context context) {
        if (context == null) {
            return null;
        }
        
        try {
            File[] externalDirs = context.getExternalFilesDirs(null);
            
            if (externalDirs != null && externalDirs.length >= 2) {
                File dir = externalDirs[1];
                if (dir != null) {
                    // 确保目录存在
                    if (!dir.exists()) {
                        dir.mkdirs();
                    }
                    return dir;
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "获取U盘应用目录失败", e);
        }
        
        return null;
    }
    
    /**
     * 获取视频存储目录
     * @param context 上下文
     * @param useExternalSd 是否使用U盘
     * @return 视频存储目录
     */
    public static File getVideoDir(Context context, boolean useExternalSd) {
        return getStorageDir(context, useExternalSd, VIDEO_DIR_NAME, Environment.DIRECTORY_DCIM);
    }
    
    /**
     * 获取图片存储目录
     * @param context 上下文
     * @param useExternalSd 是否使用U盘
     * @return 图片存储目录
     */
    public static File getPhotoDir(Context context, boolean useExternalSd) {
        return getStorageDir(context, useExternalSd, PHOTO_DIR_NAME, Environment.DIRECTORY_DCIM);
    }
    
    /**
     * 获取日志存储目录
     * @param context 上下文
     * @param useExternalSd 是否使用U盘
     * @return 日志存储目录
     */
    public static File getLogDir(Context context, boolean useExternalSd) {
        return getStorageDir(context, useExternalSd, LOG_DIR_NAME, Environment.DIRECTORY_DOWNLOADS);
    }
    
    /**
     * 根据 AppConfig 配置获取视频存储目录
     * @param context 上下文
     * @return 视频存储目录
     */
    public static File getVideoDir(Context context) {
        AppConfig config = new AppConfig(context);
        return getVideoDir(context, config.isUsingExternalSdCard());
    }
    
    /**
     * 获取录制时实际写入的目录
     * 如果启用了中转写入，返回临时目录；否则返回最终存储目录
     * @param context 上下文
     * @return 录制写入目录
     */
    public static File getRecordingDir(Context context) {
        AppConfig config = new AppConfig(context);
        
        // 检查是否应该使用中转写入
        if (config.shouldUseRelayWrite()) {
            // 使用临时目录（内部存储的缓存目录）
            File tempDir = new File(context.getCacheDir(), FileTransferManager.TEMP_VIDEO_DIR);
            if (!tempDir.exists()) {
                if (tempDir.mkdirs()) {
                    AppLog.d(TAG, "创建临时视频目录: " + tempDir.getAbsolutePath());
                } else {
                    AppLog.e(TAG, "创建临时视频目录失败，回退到普通目录");
                    return getVideoDir(context);
                }
            }
            return tempDir;
        }
        
        // 不使用中转写入，直接返回最终存储目录
        return getVideoDir(context);
    }
    
    /**
     * 获取视频的最终存储目录
     * 即使启用了中转写入，这个方法也返回最终的目标目录
     * @param context 上下文
     * @return 最终存储目录
     */
    public static File getFinalVideoDir(Context context) {
        AppConfig config = new AppConfig(context);
        return getVideoDir(context, config.isUsingExternalSdCard());
    }
    
    /**
     * 检查临时目录是否有足够空间
     * @param context 上下文
     * @param requiredBytes 需要的字节数
     * @return true 如果有足够空间
     */
    public static boolean hasSufficientTempSpace(Context context, long requiredBytes) {
        File cacheDir = context.getCacheDir();
        long available = getAvailableSpace(cacheDir);
        return available > requiredBytes;
    }
    
    /**
     * 获取临时目录的可用空间
     * @param context 上下文
     * @return 可用空间（字节）
     */
    public static long getTempAvailableSpace(Context context) {
        return getAvailableSpace(context.getCacheDir());
    }
    
    /**
     * 根据 AppConfig 配置获取图片存储目录
     * @param context 上下文
     * @return 图片存储目录
     */
    public static File getPhotoDir(Context context) {
        AppConfig config = new AppConfig(context);
        return getPhotoDir(context, config.isUsingExternalSdCard());
    }
    
    /**
     * 获取存储目录
     * @param context 上下文
     * @param useExternalSd 是否使用U盘
     * @param dirName 目录名称
     * @param parentDirType 父目录类型（如 DCIM, Downloads）
     * @return 存储目录
     */
    private static File getStorageDir(Context context, boolean useExternalSd, String dirName, String parentDirType) {
        File dir;
        
        if (useExternalSd) {
            // 使用U盘的公共目录（U盘/DCIM/EVCam_Video 或 U盘/DCIM/EVCam_Photo）
            File sdCardRoot = getExternalSdCardRoot(context);
            if (sdCardRoot != null) {
                // 在U盘的公共目录下创建子目录（如 /storage/xxxx-xxxx/DCIM/EVCam_Video）
                File parentDir = new File(sdCardRoot, parentDirType);
                dir = new File(parentDir, dirName);
            } else {
                // 如果没有U盘，回退到内部存储
                AppLog.w(TAG, "U盘不可用，回退到内部存储");
                dir = new File(Environment.getExternalStoragePublicDirectory(parentDirType), dirName);
            }
        } else {
            // 使用内部存储的公共目录
            dir = new File(Environment.getExternalStoragePublicDirectory(parentDirType), dirName);
        }
        
        // 确保目录存在
        if (!dir.exists()) {
            boolean created = dir.mkdirs();
            if (created) {
                AppLog.d(TAG, "创建存储目录: " + dir.getAbsolutePath());
            } else {
                AppLog.e(TAG, "创建存储目录失败: " + dir.getAbsolutePath());
            }
        }
        
        return dir;
    }
    
    /**
     * 获取U盘根目录（用于写入公共目录）
     * 优化检测逻辑：内存缓存优先 + SharedPreferences缓存 + 无感切换不同U盘
     * @param context 上下文
     * @return U盘根目录，如果没有则返回 null
     */
    public static File getExternalSdCardRoot(Context context) {
        if (context == null) {
            return null;
        }
        
        // 优先检查内存缓存（最快，避免任何 I/O）
        synchronized (cacheLock) {
            if (isCacheValid() && cachedSdCardRoot != null) {
                // 快速验证缓存的路径仍然有效
                if (cachedSdCardRoot.exists() && cachedSdCardRoot.canRead()) {
                    return cachedSdCardRoot;
                }
                // 缓存的路径失效了，清除缓存继续检测
                cachedSdCardRoot = null;
                cachedHasSdCard = null;
            }
        }
        
        // 内存缓存未命中，执行检测
        File result = getExternalSdCardRootInternal(context);
        
        // 更新内存缓存
        synchronized (cacheLock) {
            cachedSdCardRoot = result;
            cacheTimestamp = System.currentTimeMillis();
        }
        
        return result;
    }
    
    /**
     * 实际执行U盘检测（内部方法，不使用缓存）
     */
    private static File getExternalSdCardRootInternal(Context context) {
        AppConfig config = new AppConfig(context);
        
        // 方法0：优先使用用户手动设置的路径
        String customPath = config.getCustomSdCardPath();
        if (customPath != null && !customPath.isEmpty()) {
            File customDir = new File(customPath);
            if (customDir.exists() && customDir.isDirectory() && customDir.canRead()) {
                return customDir;
            }
        }
        
        // 方法1：检测上次 SharedPreferences 缓存的路径（比重新检测快）
        String spCachedPath = config.getLastDetectedSdPath();
        if (spCachedPath != null && !spCachedPath.isEmpty()) {
            File cachedDir = new File(spCachedPath);
            if (cachedDir.exists() && cachedDir.isDirectory() && cachedDir.canRead()) {
                return cachedDir;
            }
            // 缓存的路径不可用了（U盘拔出或更换），继续检测
        }
        
        // 方法2：读取 /proc/mounts（快速可靠，能看到所有挂载的存储设备）
        // 会检测任何 XXXX-XXXX 格式的 SD 卡，实现无感切换
        File sdRoot = getSdCardFromMounts();
        if (sdRoot != null) {
            // 检测到U盘，更新 SharedPreferences 缓存
            config.setLastDetectedSdPath(sdRoot.getAbsolutePath());
            return sdRoot;
        }
        
        // 方法3：通过 getExternalFilesDirs 获取（标准 API）
        sdRoot = getSdCardFromExternalFilesDirs(context);
        if (sdRoot != null) {
            // 检测到U盘，更新 SharedPreferences 缓存
            config.setLastDetectedSdPath(sdRoot.getAbsolutePath());
            return sdRoot;
        }
        
        AppLog.d(TAG, "未检测到U盘");
        return null;
    }
    
    /**
     * 方法1：读取 /proc/mounts 查找 SD 卡
     * 这是最可靠的方法，能看到系统实际挂载的所有存储设备
     * 只接受 /storage/XXXX-XXXX 格式
     */
    private static File getSdCardFromMounts() {
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/mounts"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length < 2) continue;
                
                String mountPoint = parts[1];
                // 只接受 /storage/XXXX-XXXX 格式
                if (mountPoint.matches("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) {
                    File sdCard = new File(mountPoint);
                    if (sdCard.exists() && sdCard.isDirectory() && sdCard.canRead()) {
                        AppLog.d(TAG, "通过 /proc/mounts 找到U盘: " + mountPoint);
                        reader.close();
                        return sdCard;
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            // 忽略错误
        }
        return null;
    }
    
    /**
     * 方法2：通过标准 API getExternalFilesDirs 获取 SD 卡
     * 只接受 /storage/XXXX-XXXX 格式的路径
     */
    private static File getSdCardFromExternalFilesDirs(Context context) {
        try {
            File[] externalDirs = context.getExternalFilesDirs(null);
            
            if (externalDirs == null || externalDirs.length < 2) {
                return null;
            }
            
            // 第一个是内部存储，第二个及以后可能是U盘
            for (int i = 1; i < externalDirs.length; i++) {
                File dir = externalDirs[i];
                if (dir != null && dir.exists()) {
                    String path = dir.getAbsolutePath();
                    int index = path.indexOf("/Android/data/");
                    if (index > 0) {
                        String sdRootPath = path.substring(0, index);
                        // 只接受 /storage/XXXX-XXXX 格式
                        if (sdRootPath.matches("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) {
                            File sdRoot = new File(sdRootPath);
                            if (sdRoot.exists() && sdRoot.canRead()) {
                                AppLog.d(TAG, "通过 getExternalFilesDirs 找到U盘: " + sdRoot.getAbsolutePath());
                                return sdRoot;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            // 忽略错误
        }
        return null;
    }
    
    
    
    /**
     * 获取所有检测到的存储设备信息（用于调试）
     * @param context 上下文
     * @return 存储设备信息列表
     */
    public static List<String> getStorageDebugInfo(Context context) {
        List<String> info = new ArrayList<>();
        
        // 0. 显示内部存储路径（用于对比）
        info.add("=== 内部存储 ===");
        String internalPath = Environment.getExternalStorageDirectory().getAbsolutePath();
        info.add("路径: " + internalPath);
        info.add("");
        
        // 1. /proc/mounts 内容（最可靠的挂载信息）
        info.add("=== /proc/mounts ===");
        try {
            java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.FileReader("/proc/mounts"));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\s+");
                if (parts.length >= 2) {
                    String mountPoint = parts[1];
                    // 只显示 /storage/ 相关的挂载点
                    if (mountPoint.startsWith("/storage/")) {
                        String marker = "";
                        if (mountPoint.contains("emulated")) {
                            marker = " [内部]";
                        } else if (mountPoint.matches("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) {
                            marker = " [U盘]";
                        }
                        info.add(mountPoint + marker);
                    }
                }
            }
            reader.close();
        } catch (Exception e) {
            info.add("读取失败: " + e.getMessage());
        }
        
        // 2. getExternalFilesDirs 信息
        info.add("");
        info.add("=== getExternalFilesDirs ===");
        try {
            File[] externalDirs = context.getExternalFilesDirs(null);
            if (externalDirs != null) {
                for (int i = 0; i < externalDirs.length; i++) {
                    File dir = externalDirs[i];
                    if (dir != null) {
                        String label = (i == 0) ? "[0] 内部" : "[" + i + "] 外部";
                        info.add(label + ": " + dir.getAbsolutePath());
                    } else {
                        info.add("[" + i + "] null");
                    }
                }
            } else {
                info.add("返回 null");
            }
        } catch (Exception e) {
            info.add("错误: " + e.getMessage());
        }
        
        // 3. 自定义路径
        info.add("");
        info.add("=== 自定义路径 ===");
        AppConfig config = new AppConfig(context);
        String customPath = config.getCustomSdCardPath();
        if (customPath != null && !customPath.isEmpty()) {
            File customDir = new File(customPath);
            info.add("路径: " + customPath);
            info.add("存在: " + customDir.exists() + ", 可读: " + customDir.canRead() + ", 可写: " + customDir.canWrite());
        } else {
            info.add("未设置");
        }
        
        // 4. 检测结果
        info.add("");
        info.add("=== 检测结果 ===");
        File sdCard = getExternalSdCardRoot(context);
        if (sdCard != null) {
            info.add("检测到U盘: " + sdCard.getAbsolutePath());
            info.add("可写入: " + sdCard.canWrite());
        } else {
            info.add("未检测到U盘");
        }
        
        return info;
    }
    
    /**
     * 获取存储空间信息
     * @param path 存储路径
     * @return 可用空间（字节），如果获取失败返回 -1
     */
    public static long getAvailableSpace(File path) {
        if (path == null || !path.exists()) {
            return -1;
        }
        
        try {
            StatFs stat = new StatFs(path.getAbsolutePath());
            return stat.getAvailableBlocksLong() * stat.getBlockSizeLong();
        } catch (Exception e) {
            AppLog.e(TAG, "获取存储空间信息失败", e);
            return -1;
        }
    }
    
    /**
     * 获取总存储空间
     * @param path 存储路径
     * @return 总空间（字节），如果获取失败返回 -1
     */
    public static long getTotalSpace(File path) {
        if (path == null || !path.exists()) {
            return -1;
        }
        
        try {
            StatFs stat = new StatFs(path.getAbsolutePath());
            return stat.getBlockCountLong() * stat.getBlockSizeLong();
        } catch (Exception e) {
            AppLog.e(TAG, "获取总存储空间失败", e);
            return -1;
        }
    }
    
    /**
     * 格式化存储大小显示
     * @param bytes 字节数
     * @return 格式化后的字符串（如 "1.5 GB"）
     */
    public static String formatSize(long bytes) {
        if (bytes < 0) {
            return "未知";
        }
        
        final long KB = 1024;
        final long MB = KB * 1024;
        final long GB = MB * 1024;
        
        if (bytes >= GB) {
            return String.format("%.1f GB", (double) bytes / GB);
        } else if (bytes >= MB) {
            return String.format("%.1f MB", (double) bytes / MB);
        } else if (bytes >= KB) {
            return String.format("%.1f KB", (double) bytes / KB);
        } else {
            return bytes + " B";
        }
    }
    
    /**
     * 获取存储信息描述
     * @param context 上下文
     * @param useExternalSd 是否使用U盘
     * @return 存储信息描述字符串
     */
    public static String getStorageInfoDesc(Context context, boolean useExternalSd) {
        File storageDir;
        String storageName;
        
        if (useExternalSd) {
            storageDir = getExternalSdCardRoot(context);
            storageName = "U盘";
            if (storageDir == null) {
                return "U盘不可用";
            }
        } else {
            storageDir = Environment.getExternalStorageDirectory();
            storageName = "内部存储";
        }
        
        long available = getAvailableSpace(storageDir);
        long total = getTotalSpace(storageDir);
        
        if (available < 0 || total < 0) {
            return storageName;
        }
        
        return String.format("%s（可用 %s / 共 %s）", 
                storageName, 
                formatSize(available), 
                formatSize(total));
    }
    
    /**
     * 获取当前存储路径描述
     * @param context 上下文
     * @return 当前存储路径描述
     */
    public static String getCurrentStoragePathDesc(Context context) {
        AppConfig config = new AppConfig(context);
        boolean useExternalSd = config.isUsingExternalSdCard();
        
        File videoDir = getVideoDir(context, useExternalSd);
        return videoDir.getAbsolutePath();
    }
}
