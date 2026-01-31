package com.kooo.evcam.feishu;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 飞书配置存储工具类
 */
public class FeishuConfig {
    private static final String PREF_NAME = "feishu_config";
    private static final String KEY_APP_ID = "app_id";
    private static final String KEY_APP_SECRET = "app_secret";
    private static final String KEY_ACCESS_TOKEN = "access_token";
    private static final String KEY_TOKEN_EXPIRE_TIME = "token_expire_time";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_ALLOWED_USER_IDS = "allowed_user_ids";

    private final SharedPreferences prefs;

    public FeishuConfig(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 保存配置
     * @param appId 应用 App ID
     * @param appSecret 应用 App Secret
     */
    public void saveConfig(String appId, String appSecret) {
        prefs.edit()
                .putString(KEY_APP_ID, appId)
                .putString(KEY_APP_SECRET, appSecret)
                .apply();
    }

    /**
     * 保存配置（包含允许的用户ID）
     */
    public void saveConfig(String appId, String appSecret, String allowedUserIds) {
        prefs.edit()
                .putString(KEY_APP_ID, appId)
                .putString(KEY_APP_SECRET, appSecret)
                .putString(KEY_ALLOWED_USER_IDS, allowedUserIds)
                .apply();
    }

    public String getAppId() {
        return prefs.getString(KEY_APP_ID, "");
    }

    public String getAppSecret() {
        return prefs.getString(KEY_APP_SECRET, "");
    }

    /**
     * 获取允许的用户ID列表
     * @return 逗号分隔的用户ID字符串
     */
    public String getAllowedUserIds() {
        return prefs.getString(KEY_ALLOWED_USER_IDS, "");
    }

    /**
     * 检查用户ID是否被允许
     * 如果未配置任何用户ID，则允许所有
     */
    public boolean isUserIdAllowed(String userId) {
        String allowedIds = getAllowedUserIds();
        if (allowedIds.isEmpty()) {
            return true; // 未配置时允许所有
        }

        String[] ids = allowedIds.split(",");
        for (String id : ids) {
            if (id.trim().equals(userId)) {
                return true;
            }
        }
        return false;
    }

    public boolean isConfigured() {
        return !getAppId().isEmpty() && !getAppSecret().isEmpty();
    }

    /**
     * 保存 Access Token
     */
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
     * 清除缓存的 AccessToken
     */
    public void clearAccessToken() {
        prefs.edit()
                .remove(KEY_ACCESS_TOKEN)
                .remove(KEY_TOKEN_EXPIRE_TIME)
                .apply();
    }

    public void setAutoStart(boolean autoStart) {
        prefs.edit()
                .putBoolean(KEY_AUTO_START, autoStart)
                .apply();
    }

    public boolean isAutoStart() {
        return prefs.getBoolean(KEY_AUTO_START, false);
    }

    public void clearConfig() {
        prefs.edit().clear().apply();
    }
}
