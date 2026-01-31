package com.kooo.evcam.remote.core;

/**
 * 远程上传回调接口
 * 统一所有平台的上传回调
 */
public interface RemoteUploadCallback {
    
    /**
     * 上传进度更新
     * @param message 进度消息
     */
    void onProgress(String message);
    
    /**
     * 上传成功
     * @param message 成功消息
     */
    void onSuccess(String message);
    
    /**
     * 上传失败
     * @param error 错误信息
     */
    void onError(String error);
}
