package com.kooo.evcam.wechat;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.UUID;

/**
 * 微信小程序配置存储工具类
 * 管理设备ID、绑定状态、服务器配置等
 */
public class WechatMiniConfig {
    private static final String PREF_NAME = "wechat_mini_config";
    
    // 服务器配置
    private static final String KEY_SERVER_URL = "server_url";
    private static final String KEY_WS_URL = "ws_url";
    
    // 设备标识
    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_DEVICE_NAME = "device_name";
    
    // 绑定状态
    private static final String KEY_IS_BOUND = "is_bound";
    private static final String KEY_BOUND_USER_ID = "bound_user_id";
    private static final String KEY_BOUND_USER_NICKNAME = "bound_user_nickname";
    private static final String KEY_BOUND_TIME = "bound_time";
    
    // 自动启动
    private static final String KEY_AUTO_START = "auto_start";
    
    // 小程序配置
    private static final String KEY_MINI_PROGRAM_APP_ID = "mini_program_app_id";
    
    // 默认服务器地址（需要用户自行搭建或使用云开发）
    private static final String DEFAULT_SERVER_URL = "https://your-server.com/api";
    private static final String DEFAULT_WS_URL = "wss://your-server.com/ws";
    
    // 默认小程序 AppId（用户需要替换为自己的小程序AppId）
    private static final String DEFAULT_MINI_PROGRAM_APP_ID = "wx1df526b63078a9e5";

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
            // 生成唯一设备ID
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
     * 设置服务器URL
     */
    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
    }

    /**
     * 获取WebSocket URL
     */
    public String getWsUrl() {
        return prefs.getString(KEY_WS_URL, DEFAULT_WS_URL);
    }

    /**
     * 设置WebSocket URL
     */
    public void setWsUrl(String url) {
        prefs.edit().putString(KEY_WS_URL, url).apply();
    }

    /**
     * 检查是否已配置服务器地址
     */
    public boolean isServerConfigured() {
        String serverUrl = getServerUrl();
        return !serverUrl.isEmpty() && !serverUrl.equals(DEFAULT_SERVER_URL);
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
     * 获取绑定的用户ID
     */
    public String getBoundUserId() {
        return prefs.getString(KEY_BOUND_USER_ID, "");
    }

    /**
     * 获取绑定的用户昵称
     */
    public String getBoundUserNickname() {
        return prefs.getString(KEY_BOUND_USER_NICKNAME, "");
    }

    /**
     * 获取绑定时间
     */
    public long getBoundTime() {
        return prefs.getLong(KEY_BOUND_TIME, 0);
    }

    /**
     * 解除绑定
     */
    public void unbind() {
        setBound(false, null, null);
    }

    /**
     * 设置自动启动
     */
    public void setAutoStart(boolean autoStart) {
        prefs.edit().putBoolean(KEY_AUTO_START, autoStart).apply();
    }

    /**
     * 是否自动启动
     */
    public boolean isAutoStart() {
        return prefs.getBoolean(KEY_AUTO_START, false);
    }

    /**
     * 保存服务器配置
     */
    public void saveServerConfig(String serverUrl, String wsUrl) {
        prefs.edit()
                .putString(KEY_SERVER_URL, serverUrl)
                .putString(KEY_WS_URL, wsUrl)
                .apply();
    }

    /**
     * 清除所有配置
     */
    public void clearConfig() {
        String deviceId = getDeviceId(); // 保留设备ID
        prefs.edit().clear().apply();
        prefs.edit().putString(KEY_DEVICE_ID, deviceId).apply();
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
    
    /**
     * 获取小程序 AppId
     */
    public String getMiniProgramAppId() {
        return prefs.getString(KEY_MINI_PROGRAM_APP_ID, DEFAULT_MINI_PROGRAM_APP_ID);
    }
    
    /**
     * 设置小程序 AppId
     */
    public void setMiniProgramAppId(String appId) {
        prefs.edit().putString(KEY_MINI_PROGRAM_APP_ID, appId).apply();
    }
}
