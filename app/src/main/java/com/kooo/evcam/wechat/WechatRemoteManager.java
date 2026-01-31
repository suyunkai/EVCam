package com.kooo.evcam.wechat;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import com.kooo.evcam.remote.upload.MediaFileFinder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.List;

/**
 * 微信远程服务管理器
 * 封装所有微信小程序相关逻辑，使 MainActivity 保持简洁
 */
public class WechatRemoteManager {
    private static final String TAG = "WechatRemoteManager";
    
    // 预览流间隔
    private static final long PREVIEW_INTERVAL = 2000; // 2秒
    
    private final Context context;
    private final WechatMiniConfig config;
    private WechatCloudManager cloudManager;
    private MediaFileFinder mediaFileFinder;
    
    // 预览流相关
    private boolean isPreviewStreaming = false;
    private Handler previewHandler;
    private Runnable previewRunnable;
    
    // 当前命令ID
    private String currentCommandId;
    
    // 命令超时相关
    private static final long COMMAND_TIMEOUT_MS = 60000; // 60秒超时
    private Handler commandTimeoutHandler;
    private Runnable commandTimeoutRunnable;
    private long commandStartTime;
    
    // 状态回调（弱引用避免内存泄漏）
    private WeakReference<StatusCallback> statusCallbackRef;
    
    // 命令执行回调（弱引用避免内存泄漏）
    private WeakReference<CommandExecutor> commandExecutorRef;
    
    // UI 回调
    private WeakReference<UICallback> uiCallbackRef;

    /**
     * 状态回调接口（由 MainActivity 实现）
     */
    public interface StatusCallback {
        String getStatusInfo();
    }

    /**
     * 命令执行接口（由 MainActivity 实现）
     * 只提供基础能力，具体逻辑由 WechatRemoteManager 处理
     */
    public interface CommandExecutor {
        /** 检查相机是否就绪 */
        boolean isCameraReady();
        
        /** 打开所有相机 */
        void openCameras();
        
        /** 拍照 */
        void takePicture(String timestamp);
        
        /** 开始录制 */
        void doStartRecording();
        
        /** 停止录制（普通停止） */
        void doStopRecording();
        
        /** 停止录制（跳过自动传输，用于远程录制后上传） */
        void stopRecordingForRemote();
        
        /** 是否正在录制 */
        boolean checkIsRecording();
        
        /** 捕获预览帧 */
        byte[] capturePreviewFrame();
        
        /** 获取照片存储目录 */
        java.io.File getPhotoDir();
        
        /** 返回后台 */
        void scheduleReturnToBackground(String source);
        
        /** 设置远程唤醒标记 */
        void setRemoteWakeUp(boolean wakeUp);
    }
    
    /**
     * UI 回调接口（由 WechatMiniFragment 实现）
     */
    public interface UICallback {
        void onServiceStatusChanged(boolean connected);
        void onConnecting();
        void onBindStatusChanged(boolean bound, String userNickname);
    }

    public WechatRemoteManager(Context context, WechatMiniConfig config) {
        this.context = context;
        this.config = config;
        this.previewHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * 设置状态回调
     */
    public void setStatusCallback(StatusCallback callback) {
        this.statusCallbackRef = new WeakReference<>(callback);
    }

    /**
     * 设置命令执行器
     */
    public void setCommandExecutor(CommandExecutor executor) {
        this.commandExecutorRef = new WeakReference<>(executor);
    }
    
    /**
     * 设置 UI 回调
     */
    public void setUICallback(UICallback callback) {
        this.uiCallbackRef = new WeakReference<>(callback);
    }

    /**
     * 启动微信云服务
     */
    public void startService() {
        if (cloudManager != null && cloudManager.isRunning()) {
            AppLog.d(TAG, "微信云服务已在运行");
            return;
        }

        AppLog.d(TAG, "启动微信云服务...");
        
        // 通知 UI 正在连接
        notifyConnecting();

        // 创建连接回调
        WechatCloudManager.ConnectionCallback connectionCallback = new WechatCloudManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                AppLog.d(TAG, "微信云服务已连接");
                showToast("微信云服务已连接");
                notifyServiceStatus(true);
            }

            @Override
            public void onDisconnected() {
                AppLog.d(TAG, "微信云服务已断开");
                notifyServiceStatus(false);
            }

            @Override
            public void onError(String error) {
                AppLog.e(TAG, "微信云服务错误: " + error);
                showToast("连接失败: " + error);
                notifyServiceStatus(false);
            }

            @Override
            public void onBindStatusChanged(boolean bound, String userNickname) {
                AppLog.d(TAG, "绑定状态变化: " + bound + ", " + userNickname);
                notifyBindStatus(bound, userNickname);
            }
        };

        // 创建命令回调
        WechatCloudManager.CommandCallback commandCallback = new WechatCloudManager.CommandCallback() {
            @Override
            public void onRecordCommand(String commandId, int durationSeconds) {
                currentCommandId = commandId;
                handleRecordCommand(commandId, durationSeconds);
            }
            
            @Override
            public void onStopRecordingCommand() {
                handleStopRecordingCommand();
            }

            @Override
            public void onPhotoCommand(String commandId) {
                currentCommandId = commandId;
                handlePhotoCommand(commandId);
            }

            @Override
            public String getStatusInfo() {
                StatusCallback callback = statusCallbackRef != null ? statusCallbackRef.get() : null;
                return callback != null ? callback.getStatusInfo() : "状态未知";
            }

            @Override
            public void onStartPreviewCommand() {
                // 与其他命令保持一致：使用 WakeUpHelper 唤醒应用
                AppLog.d(TAG, "执行开始预览命令");
                WakeUpHelper.launchForWechatStartPreview(context);
            }

            @Override
            public void onStopPreviewCommand() {
                // 停止预览并退回后台
                AppLog.d(TAG, "执行停止预览命令");
                WakeUpHelper.launchForWechatStopPreview(context);
            }
        };

        // 创建并启动云管理器
        cloudManager = new WechatCloudManager(context, config, connectionCallback);
        cloudManager.start(commandCallback);
    }

    /**
     * 停止微信云服务
     */
    public void stopService() {
        stopPreviewStream();
        
        if (cloudManager != null) {
            cloudManager.stop();
            cloudManager = null;
        }
        
        AppLog.d(TAG, "微信云服务已停止");
    }

    /**
     * 检查服务是否运行中
     */
    public boolean isRunning() {
        return cloudManager != null && cloudManager.isRunning();
    }

    /**
     * 获取配置
     */
    public WechatMiniConfig getConfig() {
        return config;
    }

    /**
     * 获取云管理器
     */
    public WechatCloudManager getCloudManager() {
        return cloudManager;
    }
    
    /**
     * 获取当前命令ID
     */
    public String getCurrentCommandId() {
        return currentCommandId;
    }

    // ===== 命令处理 =====
    
    /**
     * 处理录制命令
     * 与钉钉/Telegram/飞书保持一致：始终使用 WakeUpHelper 唤醒应用
     */
    private void handleRecordCommand(String commandId, int durationSeconds) {
        AppLog.d(TAG, "执行录制命令: " + commandId + ", 时长: " + durationSeconds);
        
        // 启动命令超时计时
        startCommandTimeout(commandId);
        
        // 与其他平台保持一致：始终使用 WakeUpHelper 唤醒应用并录制
        // 这样可以确保在后台时也能正常打开摄像头并录制
        WakeUpHelper.launchForWechatRecording(context, commandId, durationSeconds);
    }
    
    /**
     * 处理停止录制命令
     */
    private void handleStopRecordingCommand() {
        AppLog.d(TAG, "执行停止录制命令");
        
        CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
        if (executor != null) {
            executor.doStopRecording();
        } else {
            AppLog.w(TAG, "命令执行器未设置，无法停止录制");
        }
    }

    /**
     * 处理拍照命令
     * 与钉钉/Telegram/飞书保持一致：始终使用 WakeUpHelper 唤醒应用
     */
    private void handlePhotoCommand(String commandId) {
        AppLog.d(TAG, "执行拍照命令: " + commandId);
        
        // 启动命令超时计时
        startCommandTimeout(commandId);
        
        // 与其他平台保持一致：始终使用 WakeUpHelper 唤醒应用并拍照
        // 这样可以确保在后台时也能正常打开摄像头并拍照
        WakeUpHelper.launchForWechatPhoto(context, commandId);
    }

    // ===== Intent 唤醒后的命令执行（从 MainActivity 移入） =====
    
    /**
     * 从 Intent 唤醒后执行命令
     * 由 MainActivity 在收到微信 Intent 后调用
     */
    public void executeCommandFromIntent(String action, String commandId, int durationSeconds) {
        AppLog.d(TAG, "执行微信命令 (from Intent): " + action + ", commandId=" + commandId);
        
        CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
        if (executor == null) {
            AppLog.e(TAG, "命令执行器未设置");
            reportCommandResult(commandId, false, "命令执行器未设置");
            return;
        }
        
        // 延迟执行，等待相机初始化
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            switch (action) {
                case "photo":
                    executePhotoFromIntent(commandId);
                    break;
                case "record":
                    executeRecordFromIntent(commandId, durationSeconds);
                    break;
                case "start_preview":
                    executeStartPreview();
                    break;
                case "stop_preview":
                    executeStopPreview();
                    break;
                default:
                    AppLog.w(TAG, "未知命令: " + action);
                    reportCommandResult(commandId, false, "未知命令");
            }
        }, 1000);
    }
    
    /**
     * 执行拍照（从 Intent 唤醒）
     */
    private void executePhotoFromIntent(String commandId) {
        CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
        if (executor == null) return;
        
        if (!executor.isCameraReady()) {
            executor.openCameras();
            retryPhotoFromIntent(commandId, 6);
        } else {
            doPhotoFromIntent(commandId);
        }
    }
    
    private void retryPhotoFromIntent(String commandId, int retriesLeft) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
            if (executor == null) return;
            
            if (executor.isCameraReady()) {
                doPhotoFromIntent(commandId);
            } else if (retriesLeft > 0) {
                retryPhotoFromIntent(commandId, retriesLeft - 1);
            } else {
                AppLog.w(TAG, "相机启动超时");
                reportCommandResult(commandId, false, "相机启动超时");
                executor.scheduleReturnToBackground("WeChat photo");
            }
        }, 500);
    }
    
    private void doPhotoFromIntent(String commandId) {
        CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
        if (executor == null) return;
        
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", 
                java.util.Locale.getDefault()).format(new java.util.Date());
        executor.takePicture(timestamp);
        
        // 延迟后上传
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            java.io.File photoDir = executor.getPhotoDir();
            if (photoDir == null || !photoDir.exists()) {
                AppLog.e(TAG, "照片目录不存在");
                reportCommandResult(commandId, false, "照片目录不存在");
                executor.scheduleReturnToBackground("WeChat photo");
                return;
            }
            
            java.io.File[] files = photoDir.listFiles((dir, name) -> 
                name.startsWith(timestamp) && name.endsWith(".jpg"));
            
            if (files == null || files.length == 0) {
                AppLog.e(TAG, "未找到拍摄的照片，时间戳: " + timestamp);
                reportCommandResult(commandId, false, "未找到照片文件");
                executor.scheduleReturnToBackground("WeChat photo");
                return;
            }
            
            AppLog.d(TAG, "找到 " + files.length + " 张照片，开始上传");
            uploadPhotos(java.util.Arrays.asList(files), commandId, 
                (success, fail, ids) -> {
                    reportCommandResult(commandId, success > 0, 
                            success > 0 ? "已上传" + success + "张" : "上传失败");
                    executor.scheduleReturnToBackground("WeChat photo");
                });
        }, 5000);
    }
    
    /**
     * 执行录制（从 Intent 唤醒）
     */
    private void executeRecordFromIntent(String commandId, int durationSeconds) {
        CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
        if (executor == null) return;
        
        if (!executor.isCameraReady()) {
            executor.openCameras();
            retryRecordFromIntent(commandId, durationSeconds, 6);
        } else {
            doRecordFromIntent(commandId, durationSeconds);
        }
    }
    
    private void retryRecordFromIntent(String commandId, int durationSeconds, int retriesLeft) {
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
            if (executor == null) return;
            
            if (executor.isCameraReady()) {
                doRecordFromIntent(commandId, durationSeconds);
            } else if (retriesLeft > 0) {
                retryRecordFromIntent(commandId, durationSeconds, retriesLeft - 1);
            } else {
                AppLog.w(TAG, "相机启动超时");
                reportCommandResult(commandId, false, "相机启动超时");
                executor.scheduleReturnToBackground("WeChat record");
            }
        }, 500);
    }
    
    private void doRecordFromIntent(String commandId, int durationSeconds) {
        CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
        if (executor == null) return;
        
        // 记录录制开始时间戳
        String timestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", 
                java.util.Locale.getDefault()).format(new java.util.Date());
        
        executor.doStartRecording();
        
        if (durationSeconds > 0) {
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                CommandExecutor exec = commandExecutorRef != null ? commandExecutorRef.get() : null;
                if (exec == null) return;
                
                // 停止录制（跳过自动传输）
                exec.stopRecordingForRemote();
                
                // 延迟1秒后处理录制完成
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    handleRecordingComplete(commandId, timestamp, 
                            () -> exec.scheduleReturnToBackground("WeChat recording"));
                }, 1000);
            }, durationSeconds * 1000L);
        }
    }
    
    /**
     * 执行开始预览
     */
    private void executeStartPreview() {
        CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
        if (executor != null) {
            executor.setRemoteWakeUp(false); // 预览期间不自动退后台
        }
        
        AppLog.d(TAG, "WeChat: Starting preview stream");
        startPreviewStream();
    }
    
    /**
     * 执行停止预览
     */
    private void executeStopPreview() {
        AppLog.d(TAG, "WeChat: Stopping preview stream");
        stopPreviewStream();
        WakeUpHelper.releaseWakeLock();
        
        CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
        if (executor != null) {
            executor.scheduleReturnToBackground("WeChat stop preview");
        }
    }

    /**
     * 启动预览流
     */
    public void startPreviewStream() {
        if (isPreviewStreaming) {
            AppLog.d(TAG, "预览流已在运行");
            return;
        }
        
        AppLog.d(TAG, "启动预览流");
        isPreviewStreaming = true;
        
        previewRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPreviewStreaming) return;
                
                captureAndUploadPreviewFrame();
                previewHandler.postDelayed(this, PREVIEW_INTERVAL);
            }
        };
        
        previewHandler.post(previewRunnable);
    }

    /**
     * 停止预览流
     */
    public void stopPreviewStream() {
        if (!isPreviewStreaming) return;
        
        AppLog.d(TAG, "停止预览流");
        isPreviewStreaming = false;
        
        if (previewRunnable != null) {
            previewHandler.removeCallbacks(previewRunnable);
            previewRunnable = null;
        }
    }

    /**
     * 捕获并上传预览帧
     */
    private void captureAndUploadPreviewFrame() {
        if (cloudManager == null || !cloudManager.isRunning()) {
            return;
        }
        
        CommandExecutor executor = commandExecutorRef != null ? commandExecutorRef.get() : null;
        if (executor == null) {
            AppLog.w(TAG, "命令执行器未设置，无法捕获预览帧");
            return;
        }
        
        // 在后台线程执行
        new Thread(() -> {
            try {
                byte[] jpegData = executor.capturePreviewFrame();
                if (jpegData != null && jpegData.length > 0) {
                    cloudManager.uploadPreviewFrame(jpegData, fileId -> {
                        AppLog.d(TAG, "预览帧上传成功");
                    });
                }
            } catch (Exception e) {
                AppLog.e(TAG, "捕获预览帧失败", e);
            }
        }).start();
    }

    /**
     * 上传照片到云存储
     */
    public void uploadPhotos(List<File> photos, String commandId, WechatCloudManager.BatchUploadCallback callback) {
        if (cloudManager != null) {
            cloudManager.uploadPhotosToCloudAsync(photos, commandId, callback);
        } else if (callback != null) {
            callback.onComplete(0, photos.size(), null);
        }
    }

    /**
     * 上传视频到云存储
     */
    public void uploadVideos(List<File> videos, String commandId, WechatCloudManager.BatchUploadCallback callback) {
        if (cloudManager == null) {
            if (callback != null) {
                callback.onComplete(0, videos.size(), null);
            }
            return;
        }
        
        new Thread(() -> {
            int successCount = 0;
            int failCount = 0;
            java.util.List<String> fileIds = new java.util.ArrayList<>();
            
            for (File file : videos) {
                try {
                    String deviceId = config.getDeviceId();
                    String fileName = file.getName();
                    String cloudPath = "videos/" + deviceId + "/" + fileName;
                    
                    String fileId = cloudManager.uploadFileToCloud(file, cloudPath);
                    
                    if (fileId != null) {
                        fileIds.add(fileId);
                        successCount++;
                        
                        // 记录到数据库
                        cloudManager.addFileRecord(fileId, deviceId, fileName, "video", 
                                file.length(), cloudPath, commandId);
                        
                        AppLog.d(TAG, "视频 " + successCount + "/" + videos.size() + " 上传成功");
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    AppLog.e(TAG, "上传视频异常: " + file.getName(), e);
                }
            }
            
            final int finalSuccess = successCount;
            final int finalFail = failCount;
            if (callback != null) {
                new Handler(Looper.getMainLooper()).post(() -> 
                        callback.onComplete(finalSuccess, finalFail, fileIds));
            }
        }).start();
    }
    
    /**
     * 处理录制完成后的逻辑（参考钉钉 RemoteCommandHandler.handleRecordingComplete）
     * 包括：查找视频文件、上传、传输到最终目录、返回后台
     * 
     * @param commandId 命令ID
     * @param timestamp 录制时间戳
     * @param returnToBackgroundCallback 返回后台的回调
     */
    public void handleRecordingComplete(String commandId, String timestamp, Runnable returnToBackgroundCallback) {
        // 初始化 MediaFileFinder
        if (mediaFileFinder == null) {
            mediaFileFinder = new MediaFileFinder(context);
        }
        
        // 查找视频文件（先找临时目录，再找最终目录）
        List<File> videoFiles = mediaFileFinder.findVideoFiles(timestamp);
        
        if (videoFiles.isEmpty()) {
            AppLog.e(TAG, "未找到录制的视频，时间戳: " + timestamp);
            reportCommandResult(commandId, false, "未找到视频文件");
            if (returnToBackgroundCallback != null) {
                returnToBackgroundCallback.run();
            }
            return;
        }
        
        AppLog.d(TAG, "找到 " + videoFiles.size() + " 个视频，开始上传到微信云");
        
        // 上传视频
        final List<File> filesToTransfer = videoFiles;
        uploadVideos(videoFiles, commandId, (successCount, failCount, fileIds) -> {
            if (successCount > 0) {
                AppLog.d(TAG, "微信视频上传完成: 成功" + successCount + "个，失败" + failCount + "个");
                reportCommandResult(commandId, true, "录制完成，已上传" + successCount + "个视频");
            } else {
                AppLog.e(TAG, "微信视频上传失败");
                reportCommandResult(commandId, false, "视频上传失败");
            }
            
            // 上传完成后，将临时文件传输到最终目录（参考钉钉）
            if (mediaFileFinder != null) {
                mediaFileFinder.transferToFinalDir(filesToTransfer);
            }
            
            // 返回后台
            if (returnToBackgroundCallback != null) {
                returnToBackgroundCallback.run();
            }
        });
    }

    // ===== 命令超时管理 =====
    
    /**
     * 开始命令超时计时
     */
    private void startCommandTimeout(String commandId) {
        cancelCommandTimeout(); // 取消之前的超时
        
        currentCommandId = commandId;
        commandStartTime = System.currentTimeMillis();
        
        if (commandTimeoutHandler == null) {
            commandTimeoutHandler = new Handler(Looper.getMainLooper());
        }
        
        commandTimeoutRunnable = () -> {
            if (currentCommandId != null && currentCommandId.equals(commandId)) {
                AppLog.w(TAG, "命令超时: " + commandId + "，执行时间超过 " + (COMMAND_TIMEOUT_MS / 1000) + " 秒");
                reportCommandResult(commandId, false, "命令执行超时");
                currentCommandId = null;
            }
        };
        
        commandTimeoutHandler.postDelayed(commandTimeoutRunnable, COMMAND_TIMEOUT_MS);
        AppLog.d(TAG, "命令超时计时开始: " + commandId + "，超时时间: " + (COMMAND_TIMEOUT_MS / 1000) + "秒");
    }
    
    /**
     * 取消命令超时计时
     */
    private void cancelCommandTimeout() {
        if (commandTimeoutHandler != null && commandTimeoutRunnable != null) {
            commandTimeoutHandler.removeCallbacks(commandTimeoutRunnable);
            commandTimeoutRunnable = null;
        }
        if (currentCommandId != null) {
            long elapsed = System.currentTimeMillis() - commandStartTime;
            AppLog.d(TAG, "命令超时计时取消: " + currentCommandId + "，已执行 " + elapsed + "ms");
        }
        currentCommandId = null;
    }
    
    /**
     * 上报命令结果（带超时清理）
     */
    public void reportCommandResult(String commandId, boolean success, String message) {
        // 取消超时计时
        if (commandId != null && commandId.equals(currentCommandId)) {
            cancelCommandTimeout();
        }
        
        // 上报结果
        if (cloudManager != null) {
            cloudManager.reportCommandResult(commandId, success, message);
        }
    }

    // ===== 私有辅助方法 =====
    
    private void notifyServiceStatus(boolean connected) {
        UICallback callback = uiCallbackRef != null ? uiCallbackRef.get() : null;
        if (callback != null) {
            new Handler(Looper.getMainLooper()).post(() -> 
                    callback.onServiceStatusChanged(connected));
        }
    }
    
    private void notifyConnecting() {
        UICallback callback = uiCallbackRef != null ? uiCallbackRef.get() : null;
        if (callback != null) {
            new Handler(Looper.getMainLooper()).post(() -> callback.onConnecting());
        }
    }
    
    private void notifyBindStatus(boolean bound, String userNickname) {
        UICallback callback = uiCallbackRef != null ? uiCallbackRef.get() : null;
        if (callback != null) {
            new Handler(Looper.getMainLooper()).post(() -> 
                    callback.onBindStatusChanged(bound, userNickname));
        }
    }
    
    private void showToast(String message) {
        new Handler(Looper.getMainLooper()).post(() -> 
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }
}
