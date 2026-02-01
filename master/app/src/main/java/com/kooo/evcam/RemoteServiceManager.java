package com.kooo.evcam;

import android.content.Context;

import java.lang.ref.WeakReference;

import com.kooo.evcam.dingtalk.DingTalkApiClient;
import com.kooo.evcam.dingtalk.DingTalkConfig;
import com.kooo.evcam.dingtalk.DingTalkStreamManager;
import com.kooo.evcam.telegram.TelegramApiClient;
import com.kooo.evcam.telegram.TelegramBotManager;
import com.kooo.evcam.telegram.TelegramConfig;
import com.kooo.evcam.feishu.FeishuApiClient;
import com.kooo.evcam.feishu.FeishuBotManager;
import com.kooo.evcam.feishu.FeishuConfig;

/**
 * 远程服务管理器（单例）
 * 管理钉钉和 Telegram 服务的生命周期，确保在 Activity 重建时服务不会中断
 * 这个类持有服务实例的强引用，避免被垃圾回收
 *
 * 【重要】服务持久化策略：
 * 1. 单例模式确保服务实例在应用进程存活期间始终可用
 * 2. 即使 MainActivity 被系统杀死，只要进程还在，服务就继续运行
 * 3. 配合 CameraForegroundService（前台服务）提升进程优先级，降低被杀概率
 * 4. 服务只在以下情况停止：
 *    - 用户明确调用 stopDingTalkService() / stopTelegramService()
 *    - 用户退出应用（exitApp()）
 *    - 应用进程被系统完全杀死（此时所有资源都被回收）
 *
 * 【车机系统适配】
 * - 不依赖 Activity.isFinishing() 判断服务是否停止
 * - 某些深度定制的 Android 系统（如车机系统）在后台强杀 Activity 时
 *   isFinishing() 可能错误返回 true，导致误判为用户主动退出
 * - 新策略：服务生命周期与 Activity 生命周期完全解耦
 */
public class RemoteServiceManager {
    private static final String TAG = "RemoteServiceManager";
    private static RemoteServiceManager instance;

    // 钉钉服务（强引用，避免被 GC）
    private DingTalkStreamManager dingTalkStreamManager;
    private DingTalkApiClient dingTalkApiClient;

    // Telegram 服务（强引用，避免被 GC）
    private TelegramBotManager telegramBotManager;
    private TelegramApiClient telegramApiClient;

    // 飞书服务（强引用，避免被 GC）
    private FeishuBotManager feishuBotManager;
    private FeishuApiClient feishuApiClient;
    
    // 启动锁，防止竞态条件
    private volatile boolean isDingTalkStarting = false;
    private volatile boolean isTelegramStarting = false;
    private volatile boolean isFeishuStarting = false;
    private final Object dingTalkLock = new Object();
    private final Object telegramLock = new Object();
    private final Object feishuLock = new Object();
    
    // 状态信息提供者（当 MainActivity 启动后会注册，使用弱引用避免内存泄漏）
    private WeakReference<StatusInfoProvider> statusInfoProviderRef;

    /**
     * 状态信息提供者接口
     * 由 MainActivity 实现，提供完整的状态信息
     */
    public interface StatusInfoProvider {
        String getFullStatusInfo();
    }
    
    private RemoteServiceManager() {
        // 私有构造函数，确保单例
        AppLog.d(TAG, "RemoteServiceManager instance created");
    }
    
    /**
     * 注册状态信息提供者（MainActivity 启动时调用）
     * 使用弱引用避免 Activity 内存泄漏
     */
    public void setStatusInfoProvider(StatusInfoProvider provider) {
        this.statusInfoProviderRef = new WeakReference<>(provider);
        AppLog.d(TAG, "StatusInfoProvider registered (WeakReference)");
    }
    
    /**
     * 清除状态信息提供者（MainActivity 销毁时调用）
     */
    public void clearStatusInfoProvider() {
        this.statusInfoProviderRef = null;
        AppLog.d(TAG, "StatusInfoProvider cleared");
    }
    
    /**
     * 获取状态信息
     * 如果有 MainActivity 提供者且有效，使用完整信息；否则使用基本信息
     */
    public String getStatusInfo(Context context) {
        if (statusInfoProviderRef != null) {
            StatusInfoProvider provider = statusInfoProviderRef.get();
            if (provider != null) {
                try {
                    String fullInfo = provider.getFullStatusInfo();
                    if (fullInfo != null) {
                        return fullInfo;
                    }
                    // 返回 null 表示 Activity 已销毁，使用基本信息
                    AppLog.d(TAG, "StatusInfoProvider 返回 null，Activity 可能已销毁");
                } catch (Exception e) {
                    AppLog.e(TAG, "获取完整状态信息失败，使用基本信息", e);
                }
            } else {
                // 弱引用已被回收，清理引用
                statusInfoProviderRef = null;
                AppLog.d(TAG, "StatusInfoProvider 已被回收，使用基本信息");
            }
        }
        return buildBasicStatusInfo(context);
    }

    public static synchronized RemoteServiceManager getInstance() {
        if (instance == null) {
            instance = new RemoteServiceManager();
        }
        return instance;
    }

    // ==================== DingTalk 服务管理 ====================

    public void setDingTalkService(DingTalkStreamManager manager, DingTalkApiClient apiClient) {
        this.dingTalkStreamManager = manager;
        this.dingTalkApiClient = apiClient;
        AppLog.d(TAG, "DingTalk service registered");
    }

    public DingTalkStreamManager getDingTalkStreamManager() {
        return dingTalkStreamManager;
    }

    public DingTalkApiClient getDingTalkApiClient() {
        return dingTalkApiClient;
    }

    public boolean isDingTalkRunning() {
        return dingTalkStreamManager != null && dingTalkStreamManager.isRunning();
    }
    
    /**
     * 检查钉钉服务是否正在启动或已在运行
     * 用于防止竞态条件下创建重复实例
     */
    public boolean isDingTalkStartingOrRunning() {
        synchronized (dingTalkLock) {
            return isDingTalkRunning() || isDingTalkStarting;
        }
    }

    public void clearDingTalkService() {
        if (dingTalkStreamManager != null) {
            dingTalkStreamManager.stop();
        }
        this.dingTalkStreamManager = null;
        this.dingTalkApiClient = null;
        AppLog.d(TAG, "DingTalk service cleared");
    }

    // ==================== Telegram 服务管理 ====================

    public void setTelegramService(TelegramBotManager manager, TelegramApiClient apiClient) {
        this.telegramBotManager = manager;
        this.telegramApiClient = apiClient;
        AppLog.d(TAG, "Telegram service registered");
    }

    public TelegramBotManager getTelegramBotManager() {
        return telegramBotManager;
    }

    public TelegramApiClient getTelegramApiClient() {
        return telegramApiClient;
    }

    public boolean isTelegramRunning() {
        return telegramBotManager != null && telegramBotManager.isRunning();
    }
    
    /**
     * 检查 Telegram 服务是否正在启动或已在运行
     * 用于防止竞态条件下创建重复实例
     */
    public boolean isTelegramStartingOrRunning() {
        synchronized (telegramLock) {
            return isTelegramRunning() || isTelegramStarting;
        }
    }

    public void clearTelegramService() {
        if (telegramBotManager != null) {
            telegramBotManager.stop();
        }
        this.telegramBotManager = null;
        this.telegramApiClient = null;
        AppLog.d(TAG, "Telegram service cleared");
    }

    // ==================== 飞书服务管理 ====================

    public void setFeishuService(FeishuBotManager manager, FeishuApiClient apiClient) {
        this.feishuBotManager = manager;
        this.feishuApiClient = apiClient;
        AppLog.d(TAG, "Feishu service registered");
    }

    public FeishuBotManager getFeishuBotManager() {
        return feishuBotManager;
    }

    public FeishuApiClient getFeishuApiClient() {
        return feishuApiClient;
    }

    public boolean isFeishuRunning() {
        return feishuBotManager != null && feishuBotManager.isRunning();
    }

    /**
     * 检查飞书服务是否正在启动或已在运行
     */
    public boolean isFeishuStartingOrRunning() {
        synchronized (feishuLock) {
            return isFeishuRunning() || isFeishuStarting;
        }
    }

    public void clearFeishuService() {
        if (feishuBotManager != null) {
            feishuBotManager.stop();
        }
        this.feishuBotManager = null;
        this.feishuApiClient = null;
        AppLog.d(TAG, "Feishu service cleared");
    }

    // ==================== 通用方法 ====================

    /**
     * 检查是否有任何远程服务在运行
     */
    public boolean hasAnyServiceRunning() {
        return isDingTalkRunning() || isTelegramRunning() || isFeishuRunning();
    }

    /**
     * 停止所有服务
     */
    public void stopAllServices() {
        AppLog.d(TAG, "Stopping all remote services");
        clearDingTalkService();
        clearTelegramService();
        clearFeishuService();
    }

    /**
     * 获取服务状态描述（用于前台服务通知）
     */
    public String getServiceStatusDescription() {
        StringBuilder sb = new StringBuilder();
        if (isDingTalkRunning()) {
            sb.append("钉钉远程服务运行中");
        }
        if (isTelegramRunning()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append("Telegram 远程服务运行中");
        }
        if (isFeishuRunning()) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append("飞书远程服务运行中");
        }
        if (sb.length() == 0) {
            sb.append("远程服务运行中");
        }
        return sb.toString();
    }

    // ==================== 从 Service 启动远程服务 ====================

    /**
     * 从 CameraForegroundService 启动配置好的远程服务
     * 这样远程服务不依赖 MainActivity 的生命周期
     * 收到命令后通过 WakeUpHelper 唤醒 MainActivity 执行
     */
    public void startRemoteServicesFromService(Context context) {
        AppLog.d(TAG, "从 Service 启动远程服务...");
        
        // 使用 ApplicationContext 避免 Service 生命周期问题
        Context appContext = context.getApplicationContext();

        // 启动钉钉服务
        DingTalkConfig dingTalkConfig = new DingTalkConfig(appContext);
        if (dingTalkConfig.isConfigured() && dingTalkConfig.isAutoStart() && !isDingTalkRunning()) {
            startDingTalkFromService(appContext, dingTalkConfig);
        }

        // 启动 Telegram 服务
        TelegramConfig telegramConfig = new TelegramConfig(appContext);
        if (telegramConfig.isConfigured() && telegramConfig.isAutoStart() && !isTelegramRunning()) {
            startTelegramFromService(appContext, telegramConfig);
        }

        // 启动飞书服务
        FeishuConfig feishuConfig = new FeishuConfig(appContext);
        if (feishuConfig.isConfigured() && feishuConfig.isAutoStart() && !isFeishuRunning()) {
            startFeishuFromService(appContext, feishuConfig);
        }
    }

    /**
     * 从 Service 启动钉钉服务
     */
    private void startDingTalkFromService(Context context, DingTalkConfig config) {
        // 防止竞态条件：加锁检查
        synchronized (dingTalkLock) {
            if (isDingTalkRunning() || isDingTalkStarting) {
                AppLog.d(TAG, "钉钉服务已在运行或正在启动，跳过");
                return;
            }
            isDingTalkStarting = true;
        }
        
        AppLog.d(TAG, "从 Service 启动钉钉服务...");

        try {
            DingTalkApiClient apiClient = new DingTalkApiClient(config);

            DingTalkStreamManager.ConnectionCallback connectionCallback = new DingTalkStreamManager.ConnectionCallback() {
                @Override
                public void onConnected() {
                    AppLog.d(TAG, "钉钉服务已连接（从 Service 启动）");
                }

                @Override
                public void onDisconnected() {
                    AppLog.d(TAG, "钉钉服务已断开（从 Service 启动）");
                }

                @Override
                public void onError(String error) {
                    AppLog.e(TAG, "钉钉服务错误（从 Service 启动）: " + error);
                }
            };

            // 简化的命令回调 - 收到命令后通过 WakeUpHelper 唤醒 MainActivity 执行
            DingTalkStreamManager.CommandCallback commandCallback = new DingTalkStreamManager.CommandCallback() {
                @Override
                public void onRecordCommand(String conversationId, String conversationType, String userId, int durationSeconds) {
                    // 通过 WakeUpHelper 唤醒 MainActivity 执行
                    WakeUpHelper.launchForRecording(context, conversationId, conversationType, userId, durationSeconds);
                }

                @Override
                public void onPhotoCommand(String conversationId, String conversationType, String userId) {
                    WakeUpHelper.launchForPhoto(context, conversationId, conversationType, userId);
                }

                @Override
                public String getStatusInfo() {
                    // 优先使用 MainActivity 提供的完整状态信息
                    return RemoteServiceManager.this.getStatusInfo(context);
                }

                @Override
                public String onStartRecordingCommand() {
                    WakeUpHelper.launchForStartRecording(context);
                    return "✅ 正在启动录制...";
                }

                @Override
                public String onStopRecordingCommand() {
                    WakeUpHelper.launchForStopRecording(context);
                    return "✅ 正在停止录制...";
                }

                @Override
                public String onExitCommand(boolean confirmed) {
                    if (confirmed) {
                        // 停止所有服务
                        stopAllServices();
                        return "✅ EVCam 已退出";
                    }
                    return "⚠️ 发送「确认退出」执行退出操作";
                }
            };

            DingTalkStreamManager streamManager = new DingTalkStreamManager(context, config, apiClient, connectionCallback);
            streamManager.start(commandCallback, true);

            // 注册到管理器
            setDingTalkService(streamManager, apiClient);
            AppLog.d(TAG, "钉钉服务启动成功（从 Service）");

        } catch (Exception e) {
            AppLog.e(TAG, "从 Service 启动钉钉服务失败", e);
        } finally {
            synchronized (dingTalkLock) {
                isDingTalkStarting = false;
            }
        }
    }

    /**
     * 从 Service 启动 Telegram 服务
     */
    private void startTelegramFromService(Context context, TelegramConfig config) {
        // 防止竞态条件：加锁检查
        synchronized (telegramLock) {
            if (isTelegramRunning() || isTelegramStarting) {
                AppLog.d(TAG, "Telegram 服务已在运行或正在启动，跳过");
                return;
            }
            isTelegramStarting = true;
        }
        
        AppLog.d(TAG, "从 Service 启动 Telegram 服务...");

        try {
            TelegramApiClient apiClient = new TelegramApiClient(config);

            TelegramBotManager.ConnectionCallback connectionCallback = new TelegramBotManager.ConnectionCallback() {
                @Override
                public void onConnected() {
                    AppLog.d(TAG, "Telegram 服务已连接（从 Service 启动）");
                }

                @Override
                public void onDisconnected() {
                    AppLog.d(TAG, "Telegram 服务已断开（从 Service 启动）");
                }

                @Override
                public void onError(String error) {
                    AppLog.e(TAG, "Telegram 服务错误（从 Service 启动）: " + error);
                }
            };

            // 简化的命令回调
            TelegramBotManager.CommandCallback commandCallback = new TelegramBotManager.CommandCallback() {
                @Override
                public void onRecordCommand(long chatId, int durationSeconds) {
                    WakeUpHelper.launchForRecordingTelegram(context, chatId, durationSeconds);
                }

                @Override
                public void onPhotoCommand(long chatId) {
                    WakeUpHelper.launchForPhotoTelegram(context, chatId);
                }

                @Override
                public String getStatusInfo() {
                    // 优先使用 MainActivity 提供的完整状态信息
                    return RemoteServiceManager.this.getStatusInfo(context);
                }

                @Override
                public String onStartRecordingCommand() {
                    WakeUpHelper.launchForStartRecording(context);
                    return "✅ 正在启动录制...";
                }

                @Override
                public String onStopRecordingCommand() {
                    WakeUpHelper.launchForStopRecording(context);
                    return "✅ 正在停止录制...";
                }

                @Override
                public String onExitCommand(boolean confirmed) {
                    if (confirmed) {
                        stopAllServices();
                        return "✅ EVCam 已退出";
                    }
                    return "⚠️ 发送「确认退出」执行退出操作";
                }
            };

            TelegramBotManager botManager = new TelegramBotManager(context, config, apiClient, connectionCallback);
            botManager.start(commandCallback);

            // 注册到管理器
            setTelegramService(botManager, apiClient);
            AppLog.d(TAG, "Telegram 服务启动成功（从 Service）");

        } catch (Exception e) {
            AppLog.e(TAG, "从 Service 启动 Telegram 服务失败", e);
        } finally {
            synchronized (telegramLock) {
                isTelegramStarting = false;
            }
        }
    }

    /**
     * 从 Service 启动飞书服务
     */
    private void startFeishuFromService(Context context, FeishuConfig config) {
        // 防止竞态条件：加锁检查
        synchronized (feishuLock) {
            if (isFeishuRunning() || isFeishuStarting) {
                AppLog.d(TAG, "飞书服务已在运行或正在启动，跳过");
                return;
            }
            isFeishuStarting = true;
        }

        AppLog.d(TAG, "从 Service 启动飞书服务...");

        try {
            FeishuApiClient apiClient = new FeishuApiClient(config);

            FeishuBotManager.ConnectionCallback connectionCallback = new FeishuBotManager.ConnectionCallback() {
                @Override
                public void onConnected() {
                    AppLog.d(TAG, "飞书服务已连接（从 Service 启动）");
                }

                @Override
                public void onDisconnected() {
                    AppLog.d(TAG, "飞书服务已断开（从 Service 启动）");
                }

                @Override
                public void onError(String error) {
                    AppLog.e(TAG, "飞书服务错误（从 Service 启动）: " + error);
                }
            };

            // 简化的命令回调
            FeishuBotManager.CommandCallback commandCallback = new FeishuBotManager.CommandCallback() {
                @Override
                public void onRecordCommand(String chatId, String messageId, int durationSeconds) {
                    WakeUpHelper.launchForRecordingFeishu(context, chatId, messageId, durationSeconds);
                }

                @Override
                public void onPhotoCommand(String chatId, String messageId) {
                    WakeUpHelper.launchForPhotoFeishu(context, chatId, messageId);
                }

                @Override
                public String getStatusInfo() {
                    return RemoteServiceManager.this.getStatusInfo(context);
                }

                @Override
                public String onStartRecordingCommand() {
                    WakeUpHelper.launchForStartRecording(context);
                    return "✅ 正在启动录制...";
                }

                @Override
                public String onStopRecordingCommand() {
                    WakeUpHelper.launchForStopRecording(context);
                    return "✅ 正在停止录制...";
                }

                @Override
                public String onExitCommand(boolean confirmed) {
                    if (confirmed) {
                        stopAllServices();
                        return "✅ EVCam 已退出";
                    }
                    return "⚠️ 发送「确认退出」执行退出操作";
                }
            };

            FeishuBotManager botManager = new FeishuBotManager(context, config, apiClient, connectionCallback);
            botManager.start(commandCallback);

            // 注册到管理器
            setFeishuService(botManager, apiClient);
            AppLog.d(TAG, "飞书服务启动成功（从 Service）");

        } catch (Exception e) {
            AppLog.e(TAG, "从 Service 启动飞书服务失败", e);
        } finally {
            synchronized (feishuLock) {
                isFeishuStarting = false;
            }
        }
    }

    /**
     * 构建基本状态信息（不依赖 MainActivity）
     */
    private String buildBasicStatusInfo(Context context) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 EVCam 状态\n");
        sb.append("━━━━━━━━━━━━━━\n");

        try {
            AppConfig appConfig = new AppConfig(context);

            // 远程服务状态
            sb.append("🌐 远程服务:\n");
            sb.append("• 钉钉: ").append(isDingTalkRunning() ? "已连接" : "未连接").append("\n");
            sb.append("• Telegram: ").append(isTelegramRunning() ? "已连接" : "未连接").append("\n");
            sb.append("• 飞书: ").append(isFeishuRunning() ? "已连接" : "未连接").append("\n");

            // 存储信息
            try {
                boolean useExternal = appConfig.isUsingExternalSdCard();
                java.io.File storageDir = useExternal ?
                        StorageHelper.getExternalSdCardRoot(context) :
                        android.os.Environment.getExternalStorageDirectory();
                if (storageDir != null && storageDir.exists()) {
                    long available = StorageHelper.getAvailableSpace(storageDir);
                    String availableStr = StorageHelper.formatSize(available);
                    sb.append("💾 存储: ").append(useExternal ? "U盘" : "内部");
                    sb.append("（剩余 ").append(availableStr).append("）\n");
                }
            } catch (Exception e) {
                // 忽略
            }

            sb.append("━━━━━━━━━━━━━━\n");
            sb.append("💡 发送指令可远程控制录制/拍照");

        } catch (Exception e) {
            sb.append("获取状态失败: ").append(e.getMessage());
        }

        return sb.toString();
    }
}
