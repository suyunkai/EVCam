package com.kooo.evcam.wechat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * 微信小程序配置存储工具类
 * 管理设备ID、绑定状态等
 */
public class WechatMiniConfig {
    private static final String PREF_NAME = "wechat_mini_config";
    
    // 设备标识
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";
    
    // 绑定状态
    private static final String KEY_IS_BOUND = "is_bound";
    private static final String KEY_BOUND_USER_ID = "bound_user_id";
    private static final String KEY_BOUND_USER_NICKNAME = "bound_user_nickname";
    private static final String KEY_BOUND_TIME = "bound_time";
    
    // 服务器配置（用于二维码数据）
    private static final String KEY_SERVER_URL = "server_url";
    private static final String DEFAULT_SERVER_URL = "https://evcam.cloud/api";
    
    // 微信云开发凭证
    private static final String KEY_APP_ID = "wechat_app_id";
    private static final String KEY_APP_SECRET = "wechat_app_secret";
    private static final String KEY_CLOUD_ENV = "wechat_cloud_env";
    
    // 自动启动配置
    private static final String KEY_AUTO_START = "wechat_auto_start";

    private final SharedPreferences prefs;
    private final Context context;

    public WechatMiniConfig(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        // 确保设备ID已生成
        ensureDeviceId();
    }

    /**
     * 确保设备ID已生成
     */
    private void ensureDeviceId() {
        if (getDeviceId().isEmpty()) {
            String deviceId = generateDeviceId();
            prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
        }
    }

    /**
     * 生成唯一设备ID
     * 格式: EV-{UUID前8位}-{时间戳后4位}
     */
    private String generateDeviceId() {
        String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
        String timestamp = String.valueOf(System.currentTimeMillis() % 10000);
        return "EV-" + uuid + "-" + timestamp;
    }

    /**
     * 获取设备ID
     */
    public String getDeviceId() {
        return prefs.getString(KEY_DEVICE_ID, "");
    }

    /**
     * 重新生成设备ID（解绑后可用）
     */
    public void regenerateDeviceId() {
        String deviceId = generateDeviceId();
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
    }

    /**
     * 获取设备名称
     */
    public String getDeviceName() {
        return prefs.getString(KEY_DEVICE_NAME, android.os.Build.MODEL);
    }

    /**
     * 设置设备名称
     */
    public void setDeviceName(String name) {
        prefs.edit().putString(KEY_DEVICE_NAME, name).apply();
    }

    /**
     * 获取服务器URL
     */
    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL);
    }

    /**
     * 检查是否已绑定用户
     */
    public boolean isBound() {
        return prefs.getBoolean(KEY_IS_BOUND, false);
    }

    /**
     * 设置绑定状态
     */
    public void setBound(boolean bound, String userId, String userNickname) {
        SharedPreferences.Editor editor = prefs.edit();
        editor.putBoolean(KEY_IS_BOUND, bound);
        if (bound) {
            editor.putString(KEY_BOUND_USER_ID, userId);
            editor.putString(KEY_BOUND_USER_NICKNAME, userNickname);
            editor.putLong(KEY_BOUND_TIME, System.currentTimeMillis());
        } else {
            editor.remove(KEY_BOUND_USER_ID);
            editor.remove(KEY_BOUND_USER_NICKNAME);
            editor.remove(KEY_BOUND_TIME);
        }
        editor.apply();
    }

    /**
     * 获取绑定的用户昵称
     */
    public String getBoundUserNickname() {
        return prefs.getString(KEY_BOUND_USER_NICKNAME, "");
    }

    /**
     * 解除绑定
     */
    public void unbind() {
        setBound(false, null, null);
    }

    /**
     * 获取二维码数据
     * 返回JSON格式的字符串，供小程序扫描解析
     */
    public String getQrCodeData() {
        return "{" +
                "\"type\":\"evcam_bind\"," +
                "\"deviceId\":\"" + getDeviceId() + "\"," +
                "\"deviceName\":\"" + getDeviceName() + "\"," +
                "\"serverUrl\":\"" + getServerUrl() + "\"," +
                "\"timestamp\":" + System.currentTimeMillis() +
                "}";
    }
    
    // ==================== 微信云开发凭证配置 ====================
    
    /**
     * 获取小程序 App ID
     */
    public String getAppId() {
        return prefs.getString(KEY_APP_ID, "");
    }
    
    /**
     * 设置小程序 App ID
     */
    public void setAppId(String appId) {
        prefs.edit().putString(KEY_APP_ID, appId).apply();
    }
    
    /**
     * 获取小程序 App Secret
     */
    public String getAppSecret() {
        return prefs.getString(KEY_APP_SECRET, "");
    }
    
    /**
     * 设置小程序 App Secret
     */
    public void setAppSecret(String appSecret) {
        prefs.edit().putString(KEY_APP_SECRET, appSecret).apply();
    }
    
    /**
     * 获取云开发环境 ID
     */
    public String getCloudEnv() {
        return prefs.getString(KEY_CLOUD_ENV, "");
    }
    
    /**
     * 设置云开发环境 ID
     */
    public void setCloudEnv(String cloudEnv) {
        prefs.edit().putString(KEY_CLOUD_ENV, cloudEnv).apply();
    }
    
    /**
     * 保存所有云开发凭证
     */
    public void saveCloudCredentials(String appId, String appSecret, String cloudEnv) {
        prefs.edit()
                .putString(KEY_APP_ID, appId)
                .putString(KEY_APP_SECRET, appSecret)
                .putString(KEY_CLOUD_ENV, cloudEnv)
                .apply();
    }
    
    /**
     * 检查云开发凭证是否已配置
     */
    public boolean isCloudConfigured() {
        String appId = getAppId();
        String appSecret = getAppSecret();
        String cloudEnv = getCloudEnv();
        return !appId.isEmpty() && !appSecret.isEmpty() && !cloudEnv.isEmpty();
    }
    
    /**
     * 清除云开发凭证
     */
    public void clearCloudCredentials() {
        prefs.edit()
                .remove(KEY_APP_ID)
                .remove(KEY_APP_SECRET)
                .remove(KEY_CLOUD_ENV)
                .apply();
    }
    
    // ==================== 自动启动配置 ====================
    
    /**
     * 获取是否自动启动服务
     */
    public boolean isAutoStart() {
        return prefs.getBoolean(KEY_AUTO_START, true); // 默认开启
    }
    
    /**
     * 设置是否自动启动服务
     */
    public void setAutoStart(boolean autoStart) {
        prefs.edit().putBoolean(KEY_AUTO_START, autoStart).apply();
    }
}
