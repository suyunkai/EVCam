package com.kooo.evcam.heartbeat;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.provider.Settings;

import com.kooo.evcam.AppLog;

import java.security.MessageDigest;

/**
 * 心跳推图配置管理类
 * 管理实时监控推送的配置项
 */
public class HeartbeatConfig {
    private static final String TAG = "HeartbeatConfig";
    private static final String PREF_NAME = "heartbeat_config";
    
    // 配置项键名
    private static final String KEY_ENABLED = "enabled";                      // 功能开关
    private static final String KEY_INTERVAL_SECONDS = "interval_seconds";    // 推送间隔（秒）
    private static final String KEY_SERVER_URL = "server_url";                // 服务器地址
    private static final String KEY_VEHICLE_ID = "vehicle_id";                // 车辆ID
    private static final String KEY_SECRET_KEY = "secret_key";                // 通信密钥
    private static final String KEY_TARGET_SIZE_KB = "target_size_kb";        // 目标压缩大小
    private static final String KEY_SCREEN_ON_PUSH = "screen_on_push";        // 亮屏推图开关
    private static final String KEY_SCREEN_OFF_PUSH = "screen_off_push";      // 息屏推图开关
    private static final String KEY_AUTO_START = "auto_start";                // 自动启动服务
    
    // 统计信息
    private static final String KEY_LAST_UPLOAD_TIME = "last_upload_time";    // 上次上传时间
    private static final String KEY_SUCCESS_COUNT = "success_count";          // 成功次数
    private static final String KEY_FAIL_COUNT = "fail_count";                // 失败次数
    private static final String KEY_LAST_ERROR = "last_error";                // 最后一次错误信息
    
    // 推送间隔常量（秒）
    public static final int INTERVAL_30_SECONDS = 30;
    public static final int INTERVAL_60_SECONDS = 60;
    public static final int INTERVAL_120_SECONDS = 120;
    public static final int INTERVAL_300_SECONDS = 300;
    
    // 默认值
    private static final int DEFAULT_INTERVAL = INTERVAL_60_SECONDS;
    private static final boolean DEFAULT_SCREEN_ON_PUSH = true;   // 亮屏推图默认开
    private static final boolean DEFAULT_SCREEN_OFF_PUSH = false; // 息屏推图默认关
    
    // 压缩目标大小选项
    public static final int TARGET_SIZE_100KB = 100;
    public static final int TARGET_SIZE_500KB = 500;
    public static final int TARGET_SIZE_1MB = 1024;
    public static final int TARGET_SIZE_NO_COMPRESS = 0;  // 0 表示不压缩
    private static final int DEFAULT_TARGET_SIZE_KB = TARGET_SIZE_100KB;
    
    private final SharedPreferences prefs;
    private final Context context;
    
    public HeartbeatConfig(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        
        // 确保车辆ID已生成
        ensureVehicleId();
    }
    
    // ==================== 基本配置 ====================
    
    /**
     * 获取功能开关状态
     */
    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, false);
    }
    
    /**
     * 设置功能开关
     */
    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        AppLog.d(TAG, "心跳推图功能: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取推送间隔（秒）
     */
    public int getIntervalSeconds() {
        return prefs.getInt(KEY_INTERVAL_SECONDS, DEFAULT_INTERVAL);
    }
    
    /**
     * 设置推送间隔（秒）
     */
    public void setIntervalSeconds(int seconds) {
        prefs.edit().putInt(KEY_INTERVAL_SECONDS, seconds).apply();
        AppLog.d(TAG, "推送间隔设置: " + seconds + "s");
    }
    
    /**
     * 获取服务器地址
     */
    public String getServerUrl() {
        return prefs.getString(KEY_SERVER_URL, "");
    }
    
    /**
     * 设置服务器地址
     */
    public void setServerUrl(String url) {
        prefs.edit().putString(KEY_SERVER_URL, url).apply();
        AppLog.d(TAG, "服务器地址设置: " + url);
    }
    
    /**
     * 检查服务器地址是否已配置
     */
    public boolean hasServerUrl() {
        String url = getServerUrl();
        return url != null && !url.trim().isEmpty();
    }
    
    // ==================== 认证配置 ====================
    
    /**
     * 确保车辆ID已生成
     * 使用设备唯一标识生成固定的车辆ID
     */
    private void ensureVehicleId() {
        String savedId = prefs.getString(KEY_VEHICLE_ID, "");
        String expectedId = generateVehicleId();
        
        // 如果保存的ID与基于设备生成的ID不一致，则更新
        // 这确保即使用户清除数据，也能恢复到相同的ID
        if (!expectedId.equals(savedId)) {
            prefs.edit().putString(KEY_VEHICLE_ID, expectedId).apply();
            AppLog.d(TAG, "车辆ID已初始化: " + expectedId);
        }
    }
    
    /**
     * 基于设备唯一标识生成固定的车辆ID
     * 算法：SHA256(ANDROID_ID + Build.FINGERPRINT + Build.BOARD) 取前8位
     * 
     * 特点：
     * - 同一设备始终生成相同的ID
     * - 不同设备生成不同的ID
     * - 用户无法修改
     * 
     * @return 格式: EV-{8位十六进制}
     */
    private String generateVehicleId() {
        try {
            // 获取 Android ID（每个设备+用户+签名 唯一）
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), 
                    Settings.Secure.ANDROID_ID
            );
            
            // 组合多个设备特征，增加唯一性
            String deviceInfo = (androidId != null ? androidId : "") 
                    + Build.FINGERPRINT  // 设备指纹
                    + Build.BOARD        // 主板名
                    + Build.DEVICE       // 设备名
                    + Build.HARDWARE;    // 硬件名
            
            // 使用 SHA-256 哈希
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(deviceInfo.getBytes("UTF-8"));
            
            // 取前4字节（8位十六进制）
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                sb.append(String.format("%02X", hash[i]));
            }
            
            return "EV-" + sb.toString();
            
        } catch (Exception e) {
            AppLog.e(TAG, "生成车辆ID失败: " + e.getMessage());
            // 降级方案：使用 ANDROID_ID 直接截取
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(), 
                    Settings.Secure.ANDROID_ID
            );
            if (androidId != null && androidId.length() >= 8) {
                return "EV-" + androidId.substring(0, 8).toUpperCase();
            }
            // 最后的降级：基于时间戳（不推荐，但保证不崩溃）
            return "EV-" + String.format("%08X", System.currentTimeMillis() & 0xFFFFFFFFL);
        }
    }
    
    /**
     * 获取车辆ID
     * 基于设备唯一标识生成，固定不变
     */
    public String getVehicleId() {
        return prefs.getString(KEY_VEHICLE_ID, "");
    }
    
    /**
     * 获取通信密钥
     */
    public String getSecretKey() {
        return prefs.getString(KEY_SECRET_KEY, "");
    }
    
    /**
     * 设置通信密钥
     */
    public void setSecretKey(String key) {
        prefs.edit().putString(KEY_SECRET_KEY, key).apply();
        AppLog.d(TAG, "通信密钥已设置");
    }
    
    /**
     * 检查通信密钥是否已配置
     */
    public boolean hasSecretKey() {
        String key = getSecretKey();
        return key != null && !key.trim().isEmpty();
    }
    
    // ==================== 图片配置 ====================
    
    /**
     * 获取目标压缩大小（KB）
     */
    public int getTargetSizeKB() {
        return prefs.getInt(KEY_TARGET_SIZE_KB, DEFAULT_TARGET_SIZE_KB);
    }
    
    /**
     * 设置目标压缩大小（KB）
     */
    public void setTargetSizeKB(int sizeKB) {
        prefs.edit().putInt(KEY_TARGET_SIZE_KB, sizeKB).apply();
        AppLog.d(TAG, "目标压缩大小设置: " + (sizeKB == 0 ? "不压缩" : sizeKB + "KB"));
    }
    
    /**
     * 获取目标大小的显示名称
     */
    public static String getTargetSizeDisplayName(int sizeKB) {
        switch (sizeKB) {
            case TARGET_SIZE_100KB:
                return "100KB (Data Saving)";
            case TARGET_SIZE_500KB:
                return "500KB";
            case TARGET_SIZE_1MB:
                return "1MB";
            case TARGET_SIZE_NO_COMPRESS:
                return "Uncompressed (Original Quality)";
            default:
                return sizeKB + "KB";
        }
    }
    
    // ==================== 推图模式配置 ====================
    
    /**
     * 获取亮屏推图开关
     * 亮屏状态下，如果在前台就推图
     */
    public boolean isScreenOnPushEnabled() {
        return prefs.getBoolean(KEY_SCREEN_ON_PUSH, DEFAULT_SCREEN_ON_PUSH);
    }
    
    /**
     * 设置亮屏推图开关
     */
    public void setScreenOnPushEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SCREEN_ON_PUSH, enabled).apply();
        AppLog.d(TAG, "亮屏推图设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取息屏推图开关
     * 息屏状态下，定时唤醒到前台推图
     */
    public boolean isScreenOffPushEnabled() {
        return prefs.getBoolean(KEY_SCREEN_OFF_PUSH, DEFAULT_SCREEN_OFF_PUSH);
    }
    
    /**
     * 设置息屏推图开关
     */
    public void setScreenOffPushEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SCREEN_OFF_PUSH, enabled).apply();
        AppLog.d(TAG, "息屏推图设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取自动启动开关
     */
    public boolean isAutoStartEnabled() {
        return prefs.getBoolean(KEY_AUTO_START, false);
    }
    
    /**
     * 设置自动启动开关
     */
    public void setAutoStartEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply();
        AppLog.d(TAG, "自动启动设置: " + (enabled ? "启用" : "禁用"));
    }
    
    // ==================== 统计信息 ====================
    
    /**
     * 获取上次上传时间
     */
    public long getLastUploadTime() {
        return prefs.getLong(KEY_LAST_UPLOAD_TIME, 0);
    }
    
    /**
     * 设置上次上传时间
     */
    public void setLastUploadTime(long time) {
        prefs.edit().putLong(KEY_LAST_UPLOAD_TIME, time).apply();
    }
    
    /**
     * 获取成功次数
     */
    public int getSuccessCount() {
        return prefs.getInt(KEY_SUCCESS_COUNT, 0);
    }
    
    /**
     * 增加成功次数
     */
    public void incrementSuccessCount() {
        int count = getSuccessCount() + 1;
        prefs.edit().putInt(KEY_SUCCESS_COUNT, count).apply();
    }
    
    /**
     * 获取失败次数
     */
    public int getFailCount() {
        return prefs.getInt(KEY_FAIL_COUNT, 0);
    }
    
    /**
     * 增加失败次数
     */
    public void incrementFailCount() {
        int count = getFailCount() + 1;
        prefs.edit().putInt(KEY_FAIL_COUNT, count).apply();
    }
    
    /**
     * 获取最后一次错误信息
     */
    public String getLastError() {
        return prefs.getString(KEY_LAST_ERROR, "");
    }
    
    /**
     * 设置最后一次错误信息
     */
    public void setLastError(String error) {
        prefs.edit().putString(KEY_LAST_ERROR, error).apply();
    }
    
    /**
     * 重置统计信息
     */
    public void resetStatistics() {
        prefs.edit()
            .putLong(KEY_LAST_UPLOAD_TIME, 0)
            .putInt(KEY_SUCCESS_COUNT, 0)
            .putInt(KEY_FAIL_COUNT, 0)
            .remove(KEY_LAST_ERROR)
            .apply();
        AppLog.d(TAG, "统计信息已重置");
    }
    
    // ==================== 配置检查 ====================
    
    /**
     * 检查配置是否完整（可以启动服务）
     */
    public boolean isConfigured() {
        return hasServerUrl() && hasSecretKey();
    }
    
    /**
     * 获取配置状态描述
     */
    public String getConfigStatus() {
        if (!hasServerUrl()) {
            return "Please configure server address";
        }
        if (!hasSecretKey()) {
            return "Please configure secret key";
        }
        return "Configuration complete";
    }
    
    // ==================== 间隔显示名称 ====================
    
    /**
     * 获取间隔的显示名称
     */
    public static String getIntervalDisplayName(int seconds) {
        switch (seconds) {
            case INTERVAL_30_SECONDS:
                return "30s";
            case INTERVAL_60_SECONDS:
                return "1m (Recommended)";
            case INTERVAL_120_SECONDS:
                return "2m";
            case INTERVAL_300_SECONDS:
                return "5m";
            default:
                return seconds + "s";
        }
    }
}
