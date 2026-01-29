package com.kooo.evcam.dingtalk;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 钉钉配置存储工具类
 */
public class DingTalkConfig {
    private static final String PREF_NAME = "dingtalk_config";
    private static final String KEY_CLIENT_ID = "client_id";
    private static final String KEY_CLIENT_SECRET = "client_secret";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_TOKEN_EXPIRE_TIME = "token_expire_time";
    private static final String KEY_WEBHOOK_URL = "webhook_url";
    private static final String KEY_AUTO_START = "auto_start";

    private final SharedPreferences prefs;

    public DingTalkConfig(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    public void saveConfig(String clientId, String clientSecret) {
        prefs.edit()
                .putString(KEY_CLIENT_ID, clientId)
                .putString(KEY_CLIENT_SECRET, clientSecret)
                .apply();
    }

    public String getClientId() {
        return prefs.getString(KEY_CLIENT_ID, "");
    }

    public String getClientSecret() {
        return prefs.getString(KEY_CLIENT_SECRET, "");
    }

    public boolean isConfigured() {
        return !getClientId().isEmpty() && !getClientSecret().isEmpty();
    }

    public void saveAccessToken(String token, long expireTime) {
        prefs.edit()
                .putString(KEY_ACCESS_TOKEN, token)
                .putLong(KEY_TOKEN_EXPIRE_TIME, expireTime)
                .apply();
    }

    public String getAccessToken() {
        return prefs.getString(KEY_ACCESS_TOKEN, "");
    }

    public boolean isTokenValid() {
        long expireTime = prefs.getLong(KEY_TOKEN_EXPIRE_TIME, 0);
        return System.currentTimeMillis() < expireTime;
    }

    /**
     * 清除缓存的 AccessToken（用于测试连接时强制重新获取）
     */
    public void clearAccessToken() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_TOKEN_EXPIRE_TIME)
                .apply();
    }

    public void clearConfig() {
        prefs.edit().clear().apply();
    }

    public void saveWebhookUrl(String webhookUrl) {
        prefs.edit()
                .putString(KEY_WEBHOOK_URL, webhookUrl)
                .apply();
    }

    public String getWebhookUrl() {
        return prefs.getString(KEY_WEBHOOK_URL, "");
    }

    public void setAutoStart(boolean autoStart) {
        prefs.edit()
                .putBoolean(KEY_AUTO_START, autoStart)
                .apply();
    }

    public boolean isAutoStart() {
        return prefs.getBoolean(KEY_AUTO_START, false);
    }
}
