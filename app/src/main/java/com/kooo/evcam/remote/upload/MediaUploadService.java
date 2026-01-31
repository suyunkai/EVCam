package com.kooo.evcam.remote.upload;

import com.kooo.evcam.remote.core.ChatIdentifier;
import com.kooo.evcam.remote.core.RemoteUploadCallback;

import java.io.File;
import java.util.List;

/**
 * 媒体上传服务接口
 * 定义统一的媒体文件上传接口
 */
public interface MediaUploadService {
    
    /**
     * 上传视频文件
     * @param videoFiles 视频文件列表
     * @param chatId 聊天标识
     * @param callback 上传回调
     */
    void uploadVideos(List<File> videoFiles, ChatIdentifier chatId, RemoteUploadCallback callback);
    
    /**
     * 上传照片文件
     * @param photoFiles 照片文件列表
     * @param chatId 聊天标识
     * @param callback 上传回调
     */
    void uploadPhotos(List<File> photoFiles, ChatIdentifier chatId, RemoteUploadCallback callback);
}
