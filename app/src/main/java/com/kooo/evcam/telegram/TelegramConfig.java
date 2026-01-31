package com.kooo.evcam.telegram;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Telegram 机器人配置存储工具类
 */
public class TelegramConfig {
    private static final String PREF_NAME = "telegram_config";
    private static final String KEY_BOT_TOKEN = "bot_token";
    private static final String KEY_ALLOWED_CHAT_IDS = "allowed_chat_ids";
    private static final String KEY_AUTO_START = "auto_start";
    private static final String KEY_LAST_UPDATE_ID = "last_update_id";
    private static final String KEY_BOT_API_HOST = "bot_api_host";
    private static final String DEFAULT_API_HOST = "https://api.telegram.org";

    private final SharedPreferences prefs;

    public TelegramConfig(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 保存配置
     * @param botToken Bot Token (从 @BotFather 获取)
     */
    public void saveConfig(String botToken) {
        prefs.edit()
                .putString(KEY_BOT_TOKEN, botToken)
                .apply();
    }

    /**
     * 保存配置（包含允许的 Chat ID 列表）
     * @param botToken Bot Token
     * @param allowedChatIds 允许的 Chat ID 列表（逗号分隔）
     */
    public void saveConfig(String botToken, String allowedChatIds) {
        prefs.edit()
                .putString(KEY_BOT_TOKEN, botToken)
                .putString(KEY_ALLOWED_CHAT_IDS, allowedChatIds)
                .apply();
    }

    public String getBotToken() {
        return prefs.getString(KEY_BOT_TOKEN, "");
    }

    /**
     * 获取允许的 Chat ID 列表
     * @return 逗号分隔的 Chat ID 字符串
     */
    public String getAllowedChatIds() {
        return prefs.getString(KEY_ALLOWED_CHAT_IDS, "");
    }

    /**
     * 保存自定义 Bot API 地址（反向代理地址）
     * @param apiHost 自定义 API 地址，如 https://a.tgpush.com，为空则使用官方地址
     */
    public void saveBotApiHost(String apiHost) {
        prefs.edit()
                .putString(KEY_BOT_API_HOST, apiHost)
                .apply();
    }

    /**
     * 获取 Bot API 地址
     * @return 自定义反向代理地址，若未配置则返回官方地址 https://api.telegram.org
     */
    public String getBotApiHost() {
        String customHost = prefs.getString(KEY_BOT_API_HOST, "");
        if (customHost == null || customHost.trim().isEmpty()) {
            return DEFAULT_API_HOST;
        }
        // 去除末尾的斜杠，保持一致性
        return customHost.endsWith("/") ? customHost.substring(0, customHost.length() - 1) : customHost;
    }

    /**
     * 获取原始配置的 API Host（不包含默认值）
     * @return 用户配置的自定义 API 地址，未配置返回空字符串
     */
    public String getRawBotApiHost() {
        return prefs.getString(KEY_BOT_API_HOST, "");
    }

    /**
     * 验证 API Host 格式是否正确
     * 必须以 http:// 或 https:// 开头
     * @param apiHost 待验证的地址
     * @return 是否有效
     */
    public static boolean isValidApiHost(String apiHost) {
        if (apiHost == null || apiHost.trim().isEmpty()) {
            return true; // 空值是允许的，会使用默认地址
        }
        String trimmed = apiHost.trim().toLowerCase();
        return trimmed.startsWith("http://") || trimmed.startsWith("https://");
    }

    /**
     * 检查 Chat ID 是否被允许
     * 如果未配置任何 Chat ID，则允许所有
     */
    public boolean isChatIdAllowed(long chatId) {
        String allowedIds = getAllowedChatIds();
        if (allowedIds.isEmpty()) {
            return true; // 未配置时允许所有
        }

        String[] ids = allowedIds.split(",");
        for (String id : ids) {
            try {
                if (Long.parseLong(id.trim()) == chatId) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return false;
    }

    public boolean isConfigured() {
        return !getBotToken().isEmpty();
    }

    public void setAutoStart(boolean autoStart) {
        prefs.edit()
                .putBoolean(KEY_AUTO_START, autoStart)
                .apply();
    }

    public boolean isAutoStart() {
        return prefs.getBoolean(KEY_AUTO_START, false);
    }

    /**
     * 保存最后处理的 update_id
     * 用于 Long Polling 时跳过已处理的消息
     */
    public void saveLastUpdateId(long updateId) {
        prefs.edit()
                .putLong(KEY_LAST_UPDATE_ID, updateId)
                .apply();
    }

    public long getLastUpdateId() {
        return prefs.getLong(KEY_LAST_UPDATE_ID, 0);
    }

    public void clearConfig() {
        prefs.edit().clear().apply();
    }
}
