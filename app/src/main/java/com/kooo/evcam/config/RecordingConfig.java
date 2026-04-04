package com.kooo.evcam.config;

import android.content.SharedPreferences;

import com.kooo.evcam.AppLog;

import java.util.Locale;

/**
 * 录制配置类
 * 管理录制相关的配置参数
 */
public class RecordingConfig {
    private static final String TAG = "RecordingConfig";

    // 码率等级
    public static final String BITRATE_HIGH = "high";
    public static final String BITRATE_MEDIUM = "medium";
    public static final String BITRATE_LOW = "low";

    // 帧率等级
    public static final String FRAMERATE_STANDARD = "standard";
    public static final String FRAMERATE_LOW = "low";

    // 录制模式
    public static final String RECORDING_MODE_AUTO = "auto";
    public static final String RECORDING_MODE_CODEC = "codec";
    public static final String RECORDING_MODE_MEDIA_RECORDER = "media_recorder";

    // 分辨率
    public static final String RESOLUTION_DEFAULT = "default";

    // 编码缩放因子
    public static final float ENCODE_SCALE_100 = 1.0f;
    public static final float ENCODE_SCALE_75 = 0.75f;
    public static final float ENCODE_SCALE_50 = 0.5f;
    public static final float ENCODE_SCALE_25 = 0.25f;

    // 分段时长
    public static final int SEGMENT_DURATION_1_MIN = 1;
    public static final int SEGMENT_DURATION_3_MIN = 3;
    public static final int SEGMENT_DURATION_5_MIN = 5;

    // 水印颜色
    public static final int WATERMARK_COLOR_WHITE = 0;
    public static final int WATERMARK_COLOR_RED = 1;
    public static final int WATERMARK_COLOR_GREEN = 2;
    public static final int WATERMARK_COLOR_BLUE = 3;
    public static final int WATERMARK_COLOR_YELLOW = 4;
    public static final int WATERMARK_COLOR_CYAN = 5;

    // 水印样式
    public static final int WATERMARK_STYLE_CLASSIC = 0;
    public static final int WATERMARK_STYLE_DASHCAM = 1;
    public static final int WATERMARK_STYLE_CINEMA = 2;
    public static final int WATERMARK_STYLE_HUD = 3;
    public static final int WATERMARK_STYLE_MINIMAL = 4;

    // 车型常量
    private static final String CAR_MODEL_LYNK_08_07 = "lynk_08_07";
    private static final String CAR_MODEL_LYNK_08_07_PLUS = "lynk_08_07_plus";
    private static final String CAR_MODEL_L7 = "galaxy_l7";
    private static final String CAR_MODEL_L7_MULTI = "galaxy_l7_multi";

    // SharedPreferences 键名
    private static final String KEY_RECORDING_MODE = "recording_mode";
    private static final String KEY_BITRATE_LEVEL = "bitrate_level";
    private static final String KEY_FRAMERATE_LEVEL = "framerate_level";
    private static final String KEY_ENCODE_SCALE_FACTOR = "encode_scale_factor";
    private static final String KEY_TARGET_RESOLUTION = "target_resolution";
    private static final String KEY_SEGMENT_DURATION_MINUTES = "segment_duration_minutes";
    private static final String KEY_TIMESTAMP_WATERMARK_ENABLED = "timestamp_watermark_enabled";
    private static final String KEY_GPS_WATERMARK_ENABLED = "gps_watermark_enabled";
    private static final String KEY_SPEED_WATERMARK_ENABLED = "speed_watermark_enabled";
    private static final String KEY_LOCATION_WATERMARK_ENABLED = "location_watermark_enabled";
    private static final String KEY_WATERMARK_COLOR = "watermark_color";
    private static final String KEY_WATERMARK_STYLE = "watermark_style";
    private static final String KEY_WATERMARK_BRAND_ENABLED = "watermark_brand_enabled";
    private static final String KEY_GPS_SOURCE_LOGCAT = "gps_source_logcat";
    private static final String KEY_TURN_SIGNAL_SOURCE = "turn_signal_source";
    private static final String KEY_ADAPTIVE_BLIND_SPOT_DROP = "adaptive_blind_spot_drop";
    private static final String KEY_ADAPTIVE_PREVIEW_DROP = "adaptive_preview_drop";

    private final SharedPreferences prefs;
    private final CarModelProvider carModelProvider;

    public interface CarModelProvider {
        String getCarModel();
        boolean isPanoramicMode();
    }

    public RecordingConfig(SharedPreferences prefs, CarModelProvider carModelProvider) {
        this.prefs = prefs;
        this.carModelProvider = carModelProvider;
    }

    // ========== 录制模式 ==========

    public String getRecordingMode() {
        return prefs.getString(KEY_RECORDING_MODE, RECORDING_MODE_AUTO);
    }

    public void setRecordingMode(String mode) {
        prefs.edit().putString(KEY_RECORDING_MODE, mode).apply();
        AppLog.d(TAG, "录制模式设置: " + mode);
    }

    /**
     * 判断是否使用 Codec 录制模式
     */
    public boolean shouldUseCodecRecording() {
        String mode = getRecordingMode();

        if (RECORDING_MODE_CODEC.equals(mode)) {
            return true;
        }

        if (RECORDING_MODE_MEDIA_RECORDER.equals(mode)) {
            // MediaRecorder 模式下，如果启用了时间水印，自动切换到 Codec 模式
            if (isTimestampWatermarkEnabled()) {
                AppLog.d(TAG, "时间水印已启用，自动切换到 Codec 模式");
                return true;
            }
            return false;
        }

        // AUTO 模式
        if (isTimestampWatermarkEnabled()) {
            AppLog.d(TAG, "时间水印已启用，自动切换到 Codec 模式");
            return true;
        }

        if (carModelProvider.isPanoramicMode()) {
            AppLog.d(TAG, "全景模式已启用，自动切换到 Codec 模式");
            return true;
        }

        String carModel = carModelProvider.getCarModel();
        if (CAR_MODEL_L7.equals(carModel) || CAR_MODEL_L7_MULTI.equals(carModel)) {
            return true;
        }

        return false;
    }

    // ========== 码率配置 ==========

    public String getBitrateLevel() {
        return prefs.getString(KEY_BITRATE_LEVEL, BITRATE_MEDIUM);
    }

    public void setBitrateLevel(String level) {
        prefs.edit().putString(KEY_BITRATE_LEVEL, level).apply();
        AppLog.d(TAG, "码率等级设置: " + level);
    }

    public int getActualBitrate(int width, int height, int frameRate) {
        int baseBitrate = calculateBitrate(width, height, frameRate);
        String level = getBitrateLevel();
        float scaleFactor = getEncodeScaleFactor();

        switch (level) {
            case BITRATE_HIGH:
                // 高码率模式：根据分辨率缩放调整码率
                // 原画使用 1.5x 码率，缩放后使用 1.2x 码率
                if (scaleFactor >= 0.99f) {
                    baseBitrate = baseBitrate * 3 / 2; // 1.5x
                } else if (scaleFactor >= 0.74f) {
                    baseBitrate = baseBitrate * 6 / 5; // 1.2x
                } else {
                    baseBitrate = baseBitrate * 5 / 4; // 1.25x
                }
                break;
            case BITRATE_LOW:
                baseBitrate = baseBitrate / 2;
                break;
            default: // medium
                // 标准码率：根据分辨率缩放微调
                if (scaleFactor >= 0.99f) {
                    baseBitrate = baseBitrate * 11 / 10; // 1.1x
                } else if (scaleFactor < 0.5f) {
                    baseBitrate = baseBitrate * 9 / 10; // 0.9x
                }
                break;
        }

        return roundToHalfMbps(baseBitrate);
    }

    /**
     * 计算基础码率
     * 使用更保守的算法，避免编码器压力过大
     */
    public static int calculateBitrate(int width, int height, int frameRate) {
        // 使用更保守的码率计算：每像素 0.08 bps
        long bitrate = (long) width * height * frameRate * 8 / 100;
        // 限制最大基础码率为 8Mbps
        return (int) Math.min(bitrate, 8000000);
    }

    public static int roundToHalfMbps(int bitrate) {
        int halfMbps = 500000;
        int rounded = ((bitrate + halfMbps / 2) / halfMbps) * halfMbps;
        rounded = Math.min(rounded, 20000000); // 最大 20Mbps
        rounded = Math.max(rounded, halfMbps); // 最小 0.5Mbps
        return rounded;
    }

    public static String formatBitrate(int bitrate) {
        float mbps = bitrate / 1000000.0f;
        if (mbps >= 1.0f) {
            return String.format(Locale.getDefault(), "%.1f Mbps", mbps);
        } else {
            return String.format(Locale.getDefault(), "%d Kbps", bitrate / 1000);
        }
    }

    public static String getBitrateLevelDisplayName(String level) {
        switch (level) {
            case BITRATE_HIGH:
                return "高";
            case BITRATE_LOW:
                return "低";
            default:
                return "标准";
        }
    }

    // ========== 帧率配置 ==========

    public String getFramerateLevel() {
        return prefs.getString(KEY_FRAMERATE_LEVEL, FRAMERATE_STANDARD);
    }

    public void setFramerateLevel(String level) {
        prefs.edit().putString(KEY_FRAMERATE_LEVEL, level).apply();
        AppLog.d(TAG, "帧率等级设置: " + level);
    }

    public int getActualFrameRate(int originalFrameRate) {
        int frameRate = getStandardFrameRate(originalFrameRate);
        if (FRAMERATE_LOW.equals(getFramerateLevel())) {
            frameRate = frameRate / 2;
            frameRate = Math.max(10, frameRate);
        }
        return frameRate;
    }

    public static int getStandardFrameRate(int frameRate) {
        if (frameRate <= 0) return 25;
        if (frameRate >= 25) return 25;
        return frameRate;
    }

    public static String getFramerateLevelDisplayName(String level) {
        return FRAMERATE_LOW.equals(level) ? "低" : "标准";
    }

    // ========== 编码缩放 ==========

    public float getEncodeScaleFactor() {
        return prefs.getFloat(KEY_ENCODE_SCALE_FACTOR, ENCODE_SCALE_75);
    }

    public void setEncodeScaleFactor(float scale) {
        scale = Math.max(ENCODE_SCALE_25, Math.min(ENCODE_SCALE_100, scale));
        prefs.edit().putFloat(KEY_ENCODE_SCALE_FACTOR, scale).apply();
        AppLog.d(TAG, "编码缩放因子设置: " + scale);
    }

    public static String getEncodeScaleDisplayName(float scale) {
        if (scale >= 0.99f) return "原画（高码率）";
        if (scale >= 0.74f && scale <= 0.76f) return "高画质（推荐）";
        if (scale >= 0.49f && scale <= 0.51f) return "中画质";
        if (scale >= 0.24f && scale <= 0.26f) return "低画质";
        return String.format(Locale.getDefault(), "%.0f%%", scale * 100);
    }

    /**
     * 获取推荐的编码缩放因子，根据码率等级动态调整
     * 高码率时降低分辨率以保证流畅性
     */
    public float getRecommendedEncodeScaleFactor() {
        String bitrateLevel = getBitrateLevel();
        if (BITRATE_HIGH.equals(bitrateLevel)) {
            // 高码率时使用 75% 分辨率，平衡画质和性能
            return ENCODE_SCALE_75;
        } else if (BITRATE_LOW.equals(bitrateLevel)) {
            // 低码率时使用 50% 分辨率
            return ENCODE_SCALE_50;
        }
        // 标准码率默认使用 75%
        return ENCODE_SCALE_75;
    }

    /**
     * 检查是否需要启用性能优化模式
     * 高画质 + 高分辨率时需要优化
     */
    public boolean shouldEnablePerformanceMode() {
        String bitrateLevel = getBitrateLevel();
        float scaleFactor = getEncodeScaleFactor();
        return BITRATE_HIGH.equals(bitrateLevel) && scaleFactor >= 0.99f;
    }

    // ========== 分辨率配置 ==========

    public String getTargetResolution() {
        String resolution = prefs.getString(KEY_TARGET_RESOLUTION, RESOLUTION_DEFAULT);
        if (RESOLUTION_DEFAULT.equals(resolution)) {
            String carModel = carModelProvider.getCarModel();
            if (CAR_MODEL_LYNK_08_07.equals(carModel) || CAR_MODEL_LYNK_08_07_PLUS.equals(carModel)) {
                return "2560x1600";
            }
        }
        return resolution;
    }

    public void setTargetResolution(String resolution) {
        prefs.edit().putString(KEY_TARGET_RESOLUTION, resolution).apply();
        AppLog.d(TAG, "目标分辨率设置: " + resolution);
    }

    public boolean isDefaultResolution() {
        return RESOLUTION_DEFAULT.equals(getTargetResolution());
    }

    public static int[] parseResolution(String resolution) {
        if (resolution == null || RESOLUTION_DEFAULT.equals(resolution)) {
            return null;
        }
        try {
            String[] parts = resolution.split("x");
            if (parts.length == 2) {
                int width = Integer.parseInt(parts[0].trim());
                int height = Integer.parseInt(parts[1].trim());
                return new int[]{width, height};
            }
        } catch (NumberFormatException e) {
            AppLog.w(TAG, "无法解析分辨率: " + resolution);
        }
        return null;
    }

    // ========== 分段时长 ==========

    public int getSegmentDurationMinutes() {
        return prefs.getInt(KEY_SEGMENT_DURATION_MINUTES, SEGMENT_DURATION_1_MIN);
    }

    public void setSegmentDurationMinutes(int minutes) {
        prefs.edit().putInt(KEY_SEGMENT_DURATION_MINUTES, minutes).apply();
        AppLog.d(TAG, "分段时长设置: " + minutes + " 分钟");
    }

    public long getSegmentDurationMs() {
        return getSegmentDurationMinutes() * 60L * 1000L;
    }

    // ========== 水印配置 ==========

    public boolean isTimestampWatermarkEnabled() {
        return prefs.getBoolean(KEY_TIMESTAMP_WATERMARK_ENABLED, true);
    }

    public void setTimestampWatermarkEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TIMESTAMP_WATERMARK_ENABLED, enabled).apply();
        AppLog.d(TAG, "时间角标设置: " + (enabled ? "启用" : "禁用"));
    }

    public boolean isGpsWatermarkEnabled() {
        return prefs.getBoolean(KEY_GPS_WATERMARK_ENABLED, false);
    }

    public void setGpsWatermarkEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_GPS_WATERMARK_ENABLED, enabled).apply();
        AppLog.d(TAG, "GPS经纬度水印设置: " + (enabled ? "启用" : "禁用"));
    }

    public boolean isSpeedWatermarkEnabled() {
        return prefs.getBoolean(KEY_SPEED_WATERMARK_ENABLED, false);
    }

    public void setSpeedWatermarkEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SPEED_WATERMARK_ENABLED, enabled).apply();
        AppLog.d(TAG, "速度水印设置: " + (enabled ? "启用" : "禁用"));
    }

    public boolean isLocationWatermarkEnabled() {
        return prefs.getBoolean(KEY_LOCATION_WATERMARK_ENABLED, false);
    }

    public void setLocationWatermarkEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_LOCATION_WATERMARK_ENABLED, enabled).apply();
        AppLog.d(TAG, "位置地址水印设置: " + (enabled ? "启用" : "禁用"));
    }

    public boolean isGpsRequired() {
        return isGpsWatermarkEnabled() || isSpeedWatermarkEnabled() || isLocationWatermarkEnabled();
    }

    public int getWatermarkColor() {
        return prefs.getInt(KEY_WATERMARK_COLOR, WATERMARK_COLOR_WHITE);
    }

    public void setWatermarkColor(int color) {
        prefs.edit().putInt(KEY_WATERMARK_COLOR, color).apply();
        AppLog.d(TAG, "水印颜色设置: " + getWatermarkColorName(color));
    }

    public static int getWatermarkColorArgb(int color) {
        switch (color) {
            case WATERMARK_COLOR_RED:
                return 0xFFFF0000;
            case WATERMARK_COLOR_GREEN:
                return 0xFF00FF00;
            case WATERMARK_COLOR_BLUE:
                return 0xFF0000FF;
            case WATERMARK_COLOR_YELLOW:
                return 0xFFFFFF00;
            case WATERMARK_COLOR_CYAN:
                return 0xFF00FFFF;
            default:
                return 0xFFFFFFFF;
        }
    }

    public static String getWatermarkColorName(int color) {
        switch (color) {
            case WATERMARK_COLOR_RED:
                return "红色";
            case WATERMARK_COLOR_GREEN:
                return "绿色";
            case WATERMARK_COLOR_BLUE:
                return "蓝色";
            case WATERMARK_COLOR_YELLOW:
                return "黄色";
            case WATERMARK_COLOR_CYAN:
                return "青色";
            default:
                return "白色";
        }
    }

    public int getWatermarkStyle() {
        return prefs.getInt(KEY_WATERMARK_STYLE, WATERMARK_STYLE_DASHCAM);
    }

    public void setWatermarkStyle(int style) {
        prefs.edit().putInt(KEY_WATERMARK_STYLE, style).apply();
        AppLog.d(TAG, "水印风格设置: " + getWatermarkStyleName(style));
    }

    public static String getWatermarkStyleName(int style) {
        switch (style) {
            case WATERMARK_STYLE_CLASSIC:
                return "经典";
            case WATERMARK_STYLE_DASHCAM:
                return "行车记录仪";
            case WATERMARK_STYLE_CINEMA:
                return "电影字幕";
            case WATERMARK_STYLE_HUD:
                return "HUD 抬头";
            case WATERMARK_STYLE_MINIMAL:
                return "极简";
            default:
                return "未知";
        }
    }

    public boolean isWatermarkBrandEnabled() {
        return prefs.getBoolean(KEY_WATERMARK_BRAND_ENABLED, true);
    }

    public void setWatermarkBrandEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_WATERMARK_BRAND_ENABLED, enabled).apply();
        AppLog.d(TAG, "品牌标识设置: " + (enabled ? "启用" : "禁用"));
    }

    // ========== GPS 配置 ==========

    public boolean isGpsSourceLogcat() {
        return prefs.getBoolean(KEY_GPS_SOURCE_LOGCAT, false);
    }

    public void setGpsSourceLogcat(boolean enabled) {
        prefs.edit().putBoolean(KEY_GPS_SOURCE_LOGCAT, enabled).apply();
        AppLog.d(TAG, "GPS 数据源设置: " + (enabled ? "导航Logcat" : "系统GPS"));
    }

    public String getGpsDataSource() {
        return isGpsSourceLogcat() ? "logcat" : "system";
    }

    // ========== 转向灯信号源 ==========

    public String getTurnSignalSource() {
        return prefs.getString(KEY_TURN_SIGNAL_SOURCE, "logcat");
    }

    public void setTurnSignalSource(String source) {
        prefs.edit().putString(KEY_TURN_SIGNAL_SOURCE, source).apply();
    }

    public boolean isTurnSignalFromVhal() {
        return "vhal".equals(getTurnSignalSource());
    }

    public boolean isTurnSignalFromCarSignal() {
        return "car_signal".equals(getTurnSignalSource());
    }

    public boolean isTurnSignalFromLogcat() {
        String source = getTurnSignalSource();
        return "logcat".equals(source) || source == null || source.isEmpty();
    }

    // ========== 自适应降帧 ==========

    public boolean isAdaptiveBlindSpotDropEnabled() {
        return prefs.getBoolean(KEY_ADAPTIVE_BLIND_SPOT_DROP, false);
    }

    public void setAdaptiveBlindSpotDropEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ADAPTIVE_BLIND_SPOT_DROP, enabled).apply();
        AppLog.d(TAG, "自适应补盲降帧: " + (enabled ? "启用" : "禁用"));
    }

    public boolean isAdaptivePreviewDropEnabled() {
        return prefs.getBoolean(KEY_ADAPTIVE_PREVIEW_DROP, false);
    }

    public void setAdaptivePreviewDropEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ADAPTIVE_PREVIEW_DROP, enabled).apply();
        AppLog.d(TAG, "自适应预览降帧: " + (enabled ? "启用" : "禁用"));
    }
}
