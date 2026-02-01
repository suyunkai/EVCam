package com.kooo.evcam.dingtalk;


import com.kooo.evcam.AppLog;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * 视频缩略图提取工具
 * 用于从视频文件中提取第一帧作为封面图
 */
public class VideoThumbnailExtractor {
    private static final String TAG = "VideoThumbnailExtractor";

    /**
     * 从视频文件提取封面图
     * @param videoFile 视频文件
     * @param outputFile 输出的封面图文件
     * @return 是否成功
     */
    public static boolean extractThumbnail(File videoFile, File outputFile) {
        MediaMetadataRetriever retriever = null;
        FileOutputStream fos = null;

        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());

            // 获取第一帧（时间为 0 微秒）
            Bitmap bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);

            if (bitmap == null) {
                AppLog.e(TAG, "无法从视频中提取帧: " + videoFile.getName());
                return false;
            }

            // 保存为 JPEG 文件
            fos = new FileOutputStream(outputFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            fos.flush();

            AppLog.d(TAG, "封面图提取成功: " + outputFile.getAbsolutePath());
            return true;

        } catch (Exception e) {
            AppLog.e(TAG, "提取封面图失败: " + videoFile.getName(), e);
            return false;
        } finally {
            try {
                if (retriever != null) {
                    retriever.release();
                }
                if (fos != null) {
                    fos.close();
                }
            } catch (IOException e) {
                AppLog.e(TAG, "关闭资源失败", e);
            }
        }
    }

    /**
     * 获取视频时长（秒）
     * @param videoFile 视频文件
     * @return 视频时长，失败返回 0
     */
    public static int getVideoDuration(File videoFile) {
        MediaMetadataRetriever retriever = null;
        try {
            retriever = new MediaMetadataRetriever();
            retriever.setDataSource(videoFile.getAbsolutePath());

            String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            if (durationStr != null) {
                long durationMs = Long.parseLong(durationStr);
                int durationSec = (int) (durationMs / 1000);
                AppLog.d(TAG, "视频时长: " + durationSec + " 秒");
                return durationSec;
            }
        } catch (Exception e) {
            AppLog.e(TAG, "获取视频时长失败: " + videoFile.getName(), e);
        } finally {
            if (retriever != null) {
                try {
                    retriever.release();
                } catch (Exception e) {
                    AppLog.e(TAG, "释放 MediaMetadataRetriever 失败", e);
                }
            }
        }
        return 0;
    }
}
