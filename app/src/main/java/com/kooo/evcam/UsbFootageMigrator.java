package com.kooo.evcam;

import android.content.Context;
import android.media.MediaScannerConnection;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Helper to safely migrate footage and photos saved on internal display storage to a connected USB drive.
 */
public class UsbFootageMigrator {
    private static final String TAG = "UsbFootageMigrator";
    private static final long ACTIVE_FILE_THRESHOLD_MS = 15000; // Skip files modified in last 15s (actively writing)
    private static final long MIN_USB_FREE_BUFFER_BYTES = 50 * 1024 * 1024; // Keep at least 50MB free on USB

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static final AtomicBoolean isMigrating = new AtomicBoolean(false);
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    public static class FootageStats {
        public int videoCount = 0;
        public long videoBytes = 0;
        public int photoCount = 0;
        public long photoBytes = 0;

        public int totalCount() {
            return videoCount + photoCount;
        }

        public long totalBytes() {
            return videoBytes + photoBytes;
        }
    }

    public interface MigrationCallback {
        void onProgress(int current, int total, String fileName);
        void onComplete(int movedCount, long movedBytes, int failedCount);
        void onError(String message);
    }

    /**
     * Check how much footage is currently stored on internal storage.
     */
    public static FootageStats getLocalFootageStats(Context context) {
        FootageStats stats = new FootageStats();
        if (context == null) return stats;

        try {
            File localVideoDir = StorageHelper.getVideoDir(context, false);
            if (localVideoDir != null && localVideoDir.exists() && localVideoDir.isDirectory()) {
                File[] videos = localVideoDir.listFiles(File::isFile);
                if (videos != null) {
                    for (File f : videos) {
                        stats.videoCount++;
                        stats.videoBytes += f.length();
                    }
                }
            }

            File localPhotoDir = StorageHelper.getPhotoDir(context, false);
            if (localPhotoDir != null && localPhotoDir.exists() && localPhotoDir.isDirectory()) {
                File[] photos = localPhotoDir.listFiles(File::isFile);
                if (photos != null) {
                    for (File f : photos) {
                        stats.photoCount++;
                        stats.photoBytes += f.length();
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Error calculating local footage stats", e);
        }

        return stats;
    }

    /**
     * Asynchronously migrate all eligible local footage to the USB drive.
     */
    public static void migrateLocalFootageToUsb(Context context, MigrationCallback callback) {
        if (context == null) {
            if (callback != null) callback.onError("Invalid context");
            return;
        }

        if (!StorageHelper.hasExternalSdCard(context)) {
            AppLog.w(TAG, "Cannot migrate footage: USB drive is not detected or writable");
            if (callback != null) {
                mainHandler.post(() -> callback.onError("USB drive is not detected or writable"));
            }
            return;
        }

        if (!isMigrating.compareAndSet(false, true)) {
            AppLog.d(TAG, "Migration is already in progress");
            if (callback != null) {
                mainHandler.post(() -> callback.onError("Migration is already in progress"));
            }
            return;
        }

        executor.execute(() -> {
            int movedCount = 0;
            long movedBytes = 0;
            int failedCount = 0;

            try {
                Context appContext = context.getApplicationContext();
                File usbRoot = StorageHelper.getExternalSdCardRoot(appContext);
                if (usbRoot == null) {
                    throw new IOException("USB root is unavailable");
                }

                File localVideoDir = StorageHelper.getVideoDir(appContext, false);
                File usbVideoDir = StorageHelper.getVideoDir(appContext, true);
                File localPhotoDir = StorageHelper.getPhotoDir(appContext, false);
                File usbPhotoDir = StorageHelper.getPhotoDir(appContext, true);

                List<MigrationItem> items = new ArrayList<>();
                collectItems(localVideoDir, usbVideoDir, items);
                collectItems(localPhotoDir, usbPhotoDir, items);

                int totalItems = items.size();
                AppLog.d(TAG, "Found " + totalItems + " local files eligible for migration to USB");

                if (totalItems == 0) {
                    final int fMoved = movedCount;
                    final long fBytes = movedBytes;
                    final int fFailed = failedCount;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onComplete(fMoved, fBytes, fFailed);
                    });
                    return;
                }

                long now = System.currentTimeMillis();
                int currentIndex = 0;

                for (MigrationItem item : items) {
                    currentIndex++;
                    File src = item.source;
                    File dest = item.target;

                    // Skip active files modified within 15 seconds
                    if (now - src.lastModified() < ACTIVE_FILE_THRESHOLD_MS) {
                        AppLog.d(TAG, "Skipping actively recording file: " + src.getName());
                        continue;
                    }

                    // Check USB available space
                    long available = StorageHelper.getAvailableSpace(usbRoot);
                    if (available > 0 && available < (src.length() + MIN_USB_FREE_BUFFER_BYTES)) {
                        AppLog.w(TAG, "USB storage space is full, aborting migration");
                        mainHandler.post(() -> {
                            Toast.makeText(appContext, "USB drive is full. Local footage migration paused.", Toast.LENGTH_SHORT).show();
                            if (callback != null) callback.onError("USB storage space is full");
                        });
                        break;
                    }

                    final int cIdx = currentIndex;
                    mainHandler.post(() -> {
                        if (callback != null) callback.onProgress(cIdx, totalItems, src.getName());
                    });

                    boolean success = copyAndVerify(src, dest);
                    if (success) {
                        long len = dest.length();
                        if (src.delete()) {
                            movedCount++;
                            movedBytes += len;
                            AppLog.d(TAG, "Migrated: " + src.getName() + " (" + StorageHelper.formatSize(len) + ")");
                            // Scan paths in media store
                            MediaScannerConnection.scanFile(appContext,
                                    new String[]{dest.getAbsolutePath(), src.getAbsolutePath()}, null, null);
                        } else {
                            AppLog.w(TAG, "Copied file but failed to delete local source: " + src.getName());
                            failedCount++;
                        }
                    } else {
                        failedCount++;
                        AppLog.e(TAG, "Failed to migrate file: " + src.getName());
                    }
                }

                final int finalMoved = movedCount;
                final long finalBytes = movedBytes;
                final int finalFailed = failedCount;

                mainHandler.post(() -> {
                    if (finalMoved > 0) {
                        Toast.makeText(appContext, "Moved " + finalMoved + " local files (" + 
                                StorageHelper.formatSize(finalBytes) + ") to USB drive", Toast.LENGTH_LONG).show();
                    }
                    if (callback != null) {
                        callback.onComplete(finalMoved, finalBytes, finalFailed);
                    }
                });

            } catch (Exception e) {
                AppLog.e(TAG, "Error migrating local footage to USB", e);
                mainHandler.post(() -> {
                    if (callback != null) callback.onError("Migration failed: " + e.getMessage());
                });
            } finally {
                isMigrating.set(false);
            }
        });
    }

    private static void collectItems(File sourceDir, File targetDir, List<MigrationItem> outList) {
        if (sourceDir == null || !sourceDir.exists() || !sourceDir.isDirectory()) return;
        if (targetDir == null) return;
        if (!targetDir.exists()) {
            targetDir.mkdirs();
        }

        File[] files = sourceDir.listFiles(File::isFile);
        if (files == null) return;

        for (File f : files) {
            if (f.length() > 0) {
                outList.add(new MigrationItem(f, new File(targetDir, f.getName())));
            }
        }
    }

    private static boolean copyAndVerify(File source, File target) {
        if (target.exists() && target.length() == source.length()) {
            // Already copied completely previously
            return true;
        }

        FileInputStream fis = null;
        FileOutputStream fos = null;
        try {
            fis = new FileInputStream(source);
            fos = new FileOutputStream(target);

            byte[] buffer = new byte[64 * 1024];
            long transferred = 0;
            int read;

            while ((read = fis.read(buffer)) != -1) {
                fos.write(buffer, 0, read);
                transferred += read;
                if (transferred % (1024 * 1024) == 0) {
                    fos.flush();
                }
            }

            fos.flush();
            fos.getFD().sync();

            if (target.length() == source.length()) {
                return true;
            } else {
                AppLog.w(TAG, "Size mismatch after copying " + source.getName() + 
                        ": expected=" + source.length() + ", actual=" + target.length());
                if (target.exists()) target.delete();
                return false;
            }
        } catch (IOException e) {
            AppLog.e(TAG, "Exception copying " + source.getName(), e);
            if (target.exists()) target.delete();
            return false;
        } finally {
            try {
                if (fis != null) fis.close();
                if (fos != null) fos.close();
            } catch (IOException ignored) {}
        }
    }

    public static boolean isMigrationRunning() {
        return isMigrating.get();
    }

    private static class MigrationItem {
        final File source;
        final File target;

        MigrationItem(File source, File target) {
            this.source = source;
            this.target = target;
        }
    }
}
