package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 应用配置管理类
 * 管理应用级别的配置项
 */
public class AppConfig {
    private static final String TAG = "AppConfig";
    private static final String PREF_NAME = "app_config";
    
    // 配置项键名
    private static final String KEY_FIRST_LAUNCH = "first_launch";  // 首次启动标记
    private static final String KEY_DEVICE_NICKNAME = "device_nickname";  // 设备识别名称（用于日志上传）
    private static final String KEY_AUTO_START_ON_BOOT = "auto_start_on_boot";  // 开机自启动
    private static final String KEY_AUTO_START_RECORDING = "auto_start_recording";  // 启动自动录制
    private static final String KEY_SCREEN_OFF_RECORDING = "screen_off_recording";  // 息屏录制（锁车录制）
    private static final String KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled";  // 保活服务
    private static final String KEY_PREVENT_SLEEP_ENABLED = "prevent_sleep_enabled";  // 防止休眠（持续WakeLock）
    private static final String KEY_RECORDING_MODE = "recording_mode";  // 录制模式
    
    // 存储位置配置
    private static final String KEY_STORAGE_LOCATION = "storage_location";  // 存储位置
    private static final String KEY_CUSTOM_SD_CARD_PATH = "custom_sd_card_path";  // 手动设置的U盘路径
    private static final String KEY_LAST_DETECTED_SD_PATH = "last_detected_sd_path";  // 上次自动检测到的U盘路径（缓存）
    
    // 存储位置常量
    public static final String STORAGE_INTERNAL = "internal";  // 内部存储（默认）
    public static final String STORAGE_EXTERNAL_SD = "external_sd";  // U盘
    
    // U盘回退提示标志（每次冷启动后重置）
    private static boolean sdFallbackShownThisSession = false;
    
    // 悬浮窗配置
    private static final String KEY_FLOATING_WINDOW_ENABLED = "floating_window_enabled";  // 悬浮窗开关
    private static final String KEY_FLOATING_WINDOW_SIZE = "floating_window_size";  // 悬浮窗大小
    private static final String KEY_FLOATING_WINDOW_ALPHA = "floating_window_alpha";  // 悬浮窗透明度
    private static final String KEY_FLOATING_WINDOW_X = "floating_window_x";  // 悬浮窗X位置
    private static final String KEY_FLOATING_WINDOW_Y = "floating_window_y";  // 悬浮窗Y位置
    
    // 存储清理配置
    private static final String KEY_VIDEO_STORAGE_LIMIT_GB = "video_storage_limit_gb";  // 视频存储限制（GB）
    private static final String KEY_PHOTO_STORAGE_LIMIT_GB = "photo_storage_limit_gb";  // 图片存储限制（GB）
    
    // 分段录制配置
    private static final String KEY_SEGMENT_DURATION_MINUTES = "segment_duration_minutes";  // 分段时长（分钟）
    
    // 录制状态显示配置
    private static final String KEY_RECORDING_STATS_ENABLED = "recording_stats_enabled";  // 录制状态显示开关
    
    // 补盲选项配置 (原副屏显示)
    private static final String KEY_SECONDARY_DISPLAY_ENABLED = "secondary_display_enabled";  // 副屏显示开关
    private static final String KEY_SECONDARY_DISPLAY_CAMERA = "secondary_display_camera";    // 副屏显示的摄像头位置
    private static final String KEY_SECONDARY_DISPLAY_ID = "secondary_display_id";            // 副屏 Display ID
    private static final String KEY_SECONDARY_DISPLAY_X = "secondary_display_x";              // 副屏位置X
    private static final String KEY_SECONDARY_DISPLAY_Y = "secondary_display_y";              // 副屏位置Y
    private static final String KEY_SECONDARY_DISPLAY_WIDTH = "secondary_display_width";      // 副屏宽度
    private static final String KEY_SECONDARY_DISPLAY_HEIGHT = "secondary_display_height";    // 副屏高度
    private static final String KEY_SECONDARY_DISPLAY_ROTATION = "secondary_display_rotation"; // 副屏旋转角度
    private static final String KEY_SECONDARY_DISPLAY_BORDER = "secondary_display_border";    // 是否显示白边框
    private static final String KEY_SECONDARY_DISPLAY_ORIENTATION = "secondary_display_orientation"; // 屏幕方向（0/90/180/270）

    // 主屏悬浮窗配置 (补盲选项新增)
    private static final String KEY_MAIN_FLOATING_ENABLED = "main_floating_enabled";          // 主屏悬浮窗开关
    private static final String KEY_MAIN_FLOATING_CAMERA = "main_floating_camera";            // 主屏悬浮窗摄像头
    private static final String KEY_MAIN_FLOATING_X = "main_floating_x";                      // 主屏悬浮窗X位置
    private static final String KEY_MAIN_FLOATING_Y = "main_floating_y";                      // 主屏悬浮窗Y位置
    private static final String KEY_MAIN_FLOATING_WIDTH = "main_floating_width";              // 主屏悬浮窗宽度
    private static final String KEY_MAIN_FLOATING_HEIGHT = "main_floating_height";            // 主屏悬浮窗高度

    // 转向灯联动配置 (补盲选项新增)
    private static final String KEY_TURN_SIGNAL_LINKAGE_ENABLED = "turn_signal_linkage_enabled"; // 转向灯联动开关
    private static final String KEY_TURN_SIGNAL_TIMEOUT = "turn_signal_timeout";               // 转向灯熄灭后延迟消失时间 (秒)
    private static final String KEY_TURN_SIGNAL_REUSE_MAIN_FLOATING = "turn_signal_reuse_main_floating"; // 是否复用主屏悬浮窗
    private static final String KEY_TURN_SIGNAL_FLOATING_X = "turn_signal_floating_x";          // 独立补盲悬浮窗X
    private static final String KEY_TURN_SIGNAL_FLOATING_Y = "turn_signal_floating_y";          // 独立补盲悬浮窗Y
    private static final String KEY_TURN_SIGNAL_FLOATING_WIDTH = "turn_signal_floating_width";  // 独立补盲悬浮窗宽度
    private static final String KEY_TURN_SIGNAL_FLOATING_HEIGHT = "turn_signal_floating_height"; // 独立补盲悬浮窗高度
    private static final String KEY_TURN_SIGNAL_FLOATING_ROTATION = "turn_signal_floating_rotation"; // 独立补盲悬浮窗旋转
    private static final String KEY_TURN_SIGNAL_CUSTOM_LEFT_TRIGGER_LOG = "turn_signal_custom_left_trigger_log"; // 左转向灯触发log关键字
    private static final String KEY_TURN_SIGNAL_CUSTOM_RIGHT_TRIGGER_LOG = "turn_signal_custom_right_trigger_log"; // 右转向灯触发log关键字

    // 桌面悬浮模拟按钮 (补盲选项新增)
    private static final String KEY_MOCK_TURN_SIGNAL_FLOATING_ENABLED = "mock_turn_signal_floating_enabled"; // 悬浮模拟按钮开关
    private static final String KEY_MOCK_TURN_SIGNAL_FLOATING_X = "mock_turn_signal_floating_x";             // 悬浮模拟按钮X
    private static final String KEY_MOCK_TURN_SIGNAL_FLOATING_Y = "mock_turn_signal_floating_y";             // 悬浮模拟按钮Y

    // 补盲画面矫正 (Matrix)
    private static final String KEY_BLIND_SPOT_CORRECTION_ENABLED = "blind_spot_correction_enabled";
    private static final String KEY_BLIND_SPOT_CORRECTION_PREFIX = "blind_spot_correction_";
    private static final String KEY_BLIND_SPOT_DISCLAIMER_ACCEPTED = "blind_spot_disclaimer_accepted";
    
    // 时间角标配置
    private static final String KEY_TIMESTAMP_WATERMARK_ENABLED = "timestamp_watermark_enabled";  // 时间角标开关
    
    // 录制摄像头选择配置
    private static final String KEY_RECORDING_CAMERA_FRONT_ENABLED = "recording_camera_front_enabled";  // 前摄像头参与录制
    private static final String KEY_RECORDING_CAMERA_BACK_ENABLED = "recording_camera_back_enabled";    // 后摄像头参与录制
    private static final String KEY_RECORDING_CAMERA_LEFT_ENABLED = "recording_camera_left_enabled";    // 左摄像头参与录制
    private static final String KEY_RECORDING_CAMERA_RIGHT_ENABLED = "recording_camera_right_enabled";  // 右摄像头参与录制
    
    // 亮度/降噪调节配置
    private static final String KEY_IMAGE_ADJUST_ENABLED = "image_adjust_enabled";  // 是否启用亮度/降噪调节
    private static final String KEY_EXPOSURE_COMPENSATION = "exposure_compensation";  // 曝光补偿值
    private static final String KEY_AWB_MODE = "awb_mode";  // 白平衡模式
    private static final String KEY_TONEMAP_MODE = "tonemap_mode";  // 色调映射模式
    private static final String KEY_EDGE_MODE = "edge_mode";  // 边缘增强模式
    private static final String KEY_NOISE_REDUCTION_MODE = "noise_reduction_mode";  // 降噪模式
    private static final String KEY_EFFECT_MODE = "effect_mode";  // 特效模式
    private static final String KEY_SCENE_MODE = "scene_mode";  // 场景模式
    
    // 白平衡模式常量（对应 CameraMetadata.CONTROL_AWB_MODE_*）
    public static final int AWB_MODE_DEFAULT = -1;  // 默认（不设置）
    public static final int AWB_MODE_AUTO = 1;  // 自动
    public static final int AWB_MODE_INCANDESCENT = 2;  // 白炽灯
    public static final int AWB_MODE_FLUORESCENT = 3;  // 荧光灯
    public static final int AWB_MODE_WARM_FLUORESCENT = 4;  // 暖荧光灯
    public static final int AWB_MODE_DAYLIGHT = 5;  // 日光
    public static final int AWB_MODE_CLOUDY_DAYLIGHT = 6;  // 阴天
    public static final int AWB_MODE_TWILIGHT = 7;  // 黄昏
    public static final int AWB_MODE_SHADE = 8;  // 阴影
    
    // 色调映射模式常量（对应 CameraMetadata.TONEMAP_MODE_*）
    public static final int TONEMAP_MODE_DEFAULT = -1;  // 默认（不设置）
    public static final int TONEMAP_MODE_CONTRAST_CURVE = 0;  // 对比度曲线
    public static final int TONEMAP_MODE_FAST = 1;  // 快速
    public static final int TONEMAP_MODE_HIGH_QUALITY = 2;  // 高质量
    
    // 边缘增强模式常量（对应 CameraMetadata.EDGE_MODE_*）
    public static final int EDGE_MODE_DEFAULT = -1;  // 默认（不设置）
    public static final int EDGE_MODE_OFF = 0;  // 关闭
    public static final int EDGE_MODE_FAST = 1;  // 快速
    public static final int EDGE_MODE_HIGH_QUALITY = 2;  // 高质量
    
    // 降噪模式常量（对应 CameraMetadata.NOISE_REDUCTION_MODE_*）
    public static final int NOISE_REDUCTION_DEFAULT = -1;  // 默认（不设置）
    public static final int NOISE_REDUCTION_OFF = 0;  // 关闭
    public static final int NOISE_REDUCTION_FAST = 1;  // 快速
    public static final int NOISE_REDUCTION_HIGH_QUALITY = 2;  // 高质量
    
    // 特效模式常量（对应 CameraMetadata.CONTROL_EFFECT_MODE_*）
    public static final int EFFECT_MODE_DEFAULT = -1;  // 默认（不设置）
    public static final int EFFECT_MODE_OFF = 0;  // 关闭
    public static final int EFFECT_MODE_MONO = 1;  // 黑白
    public static final int EFFECT_MODE_NEGATIVE = 2;  // 负片
    public static final int EFFECT_MODE_SOLARIZE = 3;  // 曝光过度
    public static final int EFFECT_MODE_SEPIA = 4;  // 怀旧
    public static final int EFFECT_MODE_AQUA = 6;  // 水蓝
    
    // 分段时长常量（分钟）
    public static final int SEGMENT_DURATION_1_MIN = 1;
    public static final int SEGMENT_DURATION_3_MIN = 3;
    public static final int SEGMENT_DURATION_5_MIN = 5;
    
    // 悬浮窗大小常量
    public static final int FLOATING_SIZE_TINY = 32;        // 超小
    public static final int FLOATING_SIZE_EXTRA_SMALL = 40; // 特小
    public static final int FLOATING_SIZE_SMALL = 48;       // 小
    public static final int FLOATING_SIZE_MEDIUM = 64;      // 中
    public static final int FLOATING_SIZE_LARGE = 80;       // 大
    public static final int FLOATING_SIZE_EXTRA_LARGE = 96; // 超大
    public static final int FLOATING_SIZE_HUGE = 112;       // 特大
    public static final int FLOATING_SIZE_GIANT = 128;      // 特特大
    public static final int FLOATING_SIZE_PLUS = 144;       // PLUS大
    public static final int FLOATING_SIZE_MAX = 160;        // MAX大
    
    // 录制模式常量
    public static final String RECORDING_MODE_AUTO = "auto";  // 自动（根据车型决定）
    public static final String RECORDING_MODE_MEDIA_RECORDER = "media_recorder";  // MediaRecorder（硬件编码）
    public static final String RECORDING_MODE_CODEC = "codec";  // OpenGL + MediaCodec（软编码）
    
    // 分辨率配置相关键名
    private static final String KEY_TARGET_RESOLUTION = "target_resolution";  // 目标分辨率
    
    // 分辨率常量
    public static final String RESOLUTION_DEFAULT = "default";  // 默认（优先1280x800）
    
    // 码率配置相关键名
    private static final String KEY_BITRATE_LEVEL = "bitrate_level";  // 码率等级
    
    // 码率等级常量
    public static final String BITRATE_LOW = "low";        // 低码率（计算值的50%）
    public static final String BITRATE_MEDIUM = "medium";  // 中码率（计算值，默认）
    public static final String BITRATE_HIGH = "high";      // 高码率（计算值的150%）
    
    // 帧率配置相关键名
    private static final String KEY_FRAMERATE_LEVEL = "framerate_level";  // 帧率等级
    
    // 帧率等级常量
    public static final String FRAMERATE_STANDARD = "standard";  // 标准帧率（默认）
    public static final String FRAMERATE_LOW = "low";            // 低帧率（标准值的一半）
    
    // 车型配置相关键名
    private static final String KEY_CAR_MODEL = "car_model";  // 车型（galaxy_e5 / custom）
    private static final String KEY_CAMERA_COUNT = "camera_count";  // 摄像头数量（4/2/1）
    private static final String KEY_SCREEN_ORIENTATION = "screen_orientation";  // 屏幕方向（landscape/portrait，仅4摄像头时有效）
    private static final String KEY_CAMERA_FRONT_ID = "camera_front_id";  // 前摄像头编号
    private static final String KEY_CAMERA_BACK_ID = "camera_back_id";  // 后摄像头编号
    private static final String KEY_CAMERA_LEFT_ID = "camera_left_id";  // 左摄像头编号
    private static final String KEY_CAMERA_RIGHT_ID = "camera_right_id";  // 右摄像头编号
    private static final String KEY_CAMERA_FRONT_NAME = "camera_front_name";  // 前摄像头名称
    private static final String KEY_CAMERA_BACK_NAME = "camera_back_name";  // 后摄像头名称
    private static final String KEY_CAMERA_LEFT_NAME = "camera_left_name";  // 左摄像头名称
    private static final String KEY_CAMERA_RIGHT_NAME = "camera_right_name";  // 右摄像头名称
    private static final String KEY_CAMERA_FRONT_ROTATION = "camera_front_rotation";  // 前摄像头旋转角度
    private static final String KEY_CAMERA_BACK_ROTATION = "camera_back_rotation";  // 后摄像头旋转角度
    private static final String KEY_CAMERA_LEFT_ROTATION = "camera_left_rotation";  // 左摄像头旋转角度
    private static final String KEY_CAMERA_RIGHT_ROTATION = "camera_right_rotation";  // 右摄像头旋转角度
    private static final String KEY_CAMERA_FRONT_MIRROR = "camera_front_mirror";  // 前摄像头镜像
    private static final String KEY_CAMERA_BACK_MIRROR = "camera_back_mirror";  // 后摄像头镜像
    private static final String KEY_CAMERA_LEFT_MIRROR = "camera_left_mirror";  // 左摄像头镜像
    private static final String KEY_CAMERA_RIGHT_MIRROR = "camera_right_mirror";  // 右摄像头镜像
    
    // 摄像头裁剪配置（每个方向的裁剪像素值）
    private static final String KEY_CAMERA_CROP_PREFIX = "camera_crop_";  // 裁剪配置前缀
    
    // 自定义车型自由操控配置
    private static final String KEY_CUSTOM_FREE_CONTROL_ENABLED = "custom_free_control_enabled";  // 自由操控开关
    private static final String KEY_CUSTOM_BUTTON_STYLE = "custom_button_style";  // 按钮样式（standard/multi）
    private static final String KEY_CUSTOM_BUTTON_ORIENTATION = "custom_button_orientation";  // 按钮布局方向（horizontal/vertical）
    private static final String KEY_CUSTOM_LAYOUT_DATA = "custom_layout_data";  // 布局位置数据（JSON格式）
    
    // 按钮样式常量
    public static final String BUTTON_STYLE_STANDARD = "standard";  // 标准按钮（E5风格）
    public static final String BUTTON_STYLE_MULTI = "multi";        // 多按钮（L7-多按钮风格）
    
    // 按钮方向常量
    public static final String BUTTON_ORIENTATION_HORIZONTAL = "horizontal";  // 横版
    public static final String BUTTON_ORIENTATION_VERTICAL = "vertical";      // 竖版
    
    // 版本更新配置
    private static final String KEY_UPDATE_SERVER_URL = "update_server_url";  // 更新服务器地址
    private static final String DEFAULT_UPDATE_SERVER_URL = "https://evcam.suyunkai.top:9568/update/";  // 默认更新服务器
    
    // 车型常量
    public static final String CAR_MODEL_GALAXY_E5 = "galaxy_e5";  // 银河E5
    public static final String CAR_MODEL_E5_MULTI = "galaxy_e5_multi";  // 银河E5-多按钮
    public static final String CAR_MODEL_L7 = "galaxy_l7";  // 银河L6/L7
    public static final String CAR_MODEL_L7_MULTI = "galaxy_l7_multi";  // 银河L7-多按钮
    public static final String CAR_MODEL_PHONE = "phone";  // 手机
    public static final String CAR_MODEL_CUSTOM = "custom";  // 自定义车型
    public static final String CAR_MODEL_XINGHAN_7 = "xinghan_7";  // 26款星舰7
    
    private final SharedPreferences prefs;
    
    public AppConfig(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
    // ==================== 首次启动相关方法 ====================
    
    /**
     * 检查是否为首次启动
     * @return true 表示首次启动（新安装后第一次打开）
     */
    public boolean isFirstLaunch() {
        return prefs.getBoolean(KEY_FIRST_LAUNCH, true);
    }
    
    /**
     * 标记首次启动已完成
     */
    public void setFirstLaunchCompleted() {
        prefs.edit().putBoolean(KEY_FIRST_LAUNCH, false).apply();
        AppLog.d(TAG, "首次启动标记已设置为完成");
    }
    
    // ==================== 设备识别名称相关方法 ====================
    
    /**
     * 获取设备识别名称（用于日志上传）
     * @return 设备名称，如果未设置返回 null
     */
    public String getDeviceNickname() {
        return prefs.getString(KEY_DEVICE_NICKNAME, null);
    }
    
    /**
     * 设置设备识别名称
     * @param nickname 设备名称
     */
    public void setDeviceNickname(String nickname) {
        prefs.edit().putString(KEY_DEVICE_NICKNAME, nickname).apply();
        AppLog.d(TAG, "设备识别名称已设置: " + nickname);
    }
    
    /**
     * 检查是否已设置设备识别名称
     * @return true 表示已设置
     */
    public boolean hasDeviceNickname() {
        String nickname = getDeviceNickname();
        return nickname != null && !nickname.trim().isEmpty();
    }
    
    // ==================== 开机自启动相关方法 ====================
    
    /**
     * 设置开机自启动
     * @param enabled true 表示启用开机自启动
     */
    public void setAutoStartOnBoot(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_START_ON_BOOT, enabled).apply();
        AppLog.d(TAG, "开机自启动设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取开机自启动设置
     * @return true 表示启用开机自启动
     */
    public boolean isAutoStartOnBoot() {
        // 默认启用开机自启动（车机系统场景）
        return prefs.getBoolean(KEY_AUTO_START_ON_BOOT, true);
    }
    
    /**
     * 设置启动自动录制
     * @param enabled true 表示启用启动自动录制
     */
    public void setAutoStartRecording(boolean enabled) {
        prefs.edit().putBoolean(KEY_AUTO_START_RECORDING, enabled).apply();
        AppLog.d(TAG, "启动自动录制设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取启动自动录制设置
     * @return true 表示启用启动自动录制
     */
    public boolean isAutoStartRecording() {
        // 默认禁用启动自动录制（需要用户主动开启）
        return prefs.getBoolean(KEY_AUTO_START_RECORDING, false);
    }
    
    /**
     * 设置息屏录制（锁车录制）
     * @param enabled true 表示息屏时继续录制
     */
    public void setScreenOffRecordingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SCREEN_OFF_RECORDING, enabled).apply();
        AppLog.d(TAG, "息屏录制设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取息屏录制设置
     * @return true 表示息屏时继续录制
     */
    public boolean isScreenOffRecordingEnabled() {
        // 默认禁用息屏录制
        return prefs.getBoolean(KEY_SCREEN_OFF_RECORDING, false);
    }
    
    /**
     * 设置保活服务
     * @param enabled true 表示启用保活服务
     */
    public void setKeepAliveEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_KEEP_ALIVE_ENABLED, enabled).apply();
        AppLog.d(TAG, "保活服务设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取保活服务设置
     * @return true 表示启用保活服务
     */
    public boolean isKeepAliveEnabled() {
        // 默认启用保活服务
        return prefs.getBoolean(KEY_KEEP_ALIVE_ENABLED, true);
    }
    
    /**
     * 设置防止休眠（持续WakeLock）
     * @param enabled true 表示启用防止休眠
     */
    public void setPreventSleepEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_PREVENT_SLEEP_ENABLED, enabled).apply();
        AppLog.d(TAG, "防止休眠设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取防止休眠设置
     * @return true 表示启用防止休眠
     */
    public boolean isPreventSleepEnabled() {
        // 车机应用默认启用防止休眠
        // 原因：1. 车机使用车辆供电，不影响电池
        //       2. 摄像头应用需要在息屏时继续录制
        //       3. 远程控制需要后台运行
        return prefs.getBoolean(KEY_PREVENT_SLEEP_ENABLED, true);
    }
    
    /**
     * 设置录制模式
     * @param mode 录制模式（auto/media_recorder/codec）
     */
    public void setRecordingMode(String mode) {
        prefs.edit().putString(KEY_RECORDING_MODE, mode).apply();
        AppLog.d(TAG, "录制模式设置: " + mode);
    }
    
    /**
     * 获取录制模式
     * @return 录制模式，默认为自动
     */
    public String getRecordingMode() {
        return prefs.getString(KEY_RECORDING_MODE, RECORDING_MODE_AUTO);
    }
    
    /**
     * 判断是否应该使用 Codec 录制模式
     * @return true 表示应该使用 CodecVideoRecorder
     */
    public boolean shouldUseCodecRecording() {
        String mode = getRecordingMode();
        if (RECORDING_MODE_CODEC.equals(mode)) {
            // 强制使用 Codec 模式
            return true;
        } else if (RECORDING_MODE_MEDIA_RECORDER.equals(mode)) {
            // 强制使用 MediaRecorder 模式
            return false;
        } else {
            // 自动模式：L6/L7、L7-多按钮、26款星舰7 车型使用 Codec 模式
            String carModel = getCarModel();
            return CAR_MODEL_L7.equals(carModel) || CAR_MODEL_L7_MULTI.equals(carModel) || CAR_MODEL_XINGHAN_7.equals(carModel);
        }
    }
    
    /**
     * 重置所有配置为默认值
     */
    public void resetToDefault() {
        prefs.edit().clear().apply();
        AppLog.d(TAG, "配置已重置为默认值");
    }
    
    // ==================== 分辨率配置相关方法 ====================
    
    /**
     * 设置目标分辨率
     * @param resolution 分辨率字符串（如 "1280x720"）或 "default"
     */
    public void setTargetResolution(String resolution) {
        prefs.edit().putString(KEY_TARGET_RESOLUTION, resolution).apply();
        AppLog.d(TAG, "目标分辨率设置: " + resolution);
    }
    
    /**
     * 获取目标分辨率
     * @return 分辨率字符串，默认为 "default"
     */
    public String getTargetResolution() {
        return prefs.getString(KEY_TARGET_RESOLUTION, RESOLUTION_DEFAULT);
    }
    
    /**
     * 是否使用默认分辨率
     */
    public boolean isDefaultResolution() {
        return RESOLUTION_DEFAULT.equals(getTargetResolution());
    }
    
    /**
     * 解析分辨率字符串为宽高数组
     * @param resolution 分辨率字符串（如 "1280x720"）
     * @return [width, height]，解析失败返回 null
     */
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
    
    // ==================== 码率配置相关方法 ====================
    
    /**
     * 设置码率等级
     * @param level 码率等级（low/medium/high）
     */
    public void setBitrateLevel(String level) {
        prefs.edit().putString(KEY_BITRATE_LEVEL, level).apply();
        AppLog.d(TAG, "码率等级设置: " + level);
    }
    
    /**
     * 获取码率等级
     * @return 码率等级，默认为 medium
     */
    public String getBitrateLevel() {
        return prefs.getString(KEY_BITRATE_LEVEL, BITRATE_MEDIUM);
    }
    
    /**
     * 根据分辨率和帧率计算码率（bps）
     * 公式：像素数 × 帧率 × 0.1
     * @param width 宽度
     * @param height 高度
     * @param frameRate 帧率
     * @return 计算出的码率（bps）
     */
    public static int calculateBitrate(int width, int height, int frameRate) {
        // 像素数 × 帧率 × 0.1
        long bitrate = (long) width * height * frameRate / 10;
        return (int) bitrate;
    }
    
    /**
     * 根据当前配置获取实际应用的码率（bps）
     * @param width 宽度
     * @param height 高度
     * @param frameRate 帧率
     * @return 实际码率（bps）
     */
    public int getActualBitrate(int width, int height, int frameRate) {
        int baseBitrate = calculateBitrate(width, height, frameRate);
        String level = getBitrateLevel();
        
        switch (level) {
            case BITRATE_LOW:
                // 50%，取整到 0.5Mbps
                return roundToHalfMbps(baseBitrate / 2);
            case BITRATE_HIGH:
                // 150%，取整到 0.5Mbps
                return roundToHalfMbps(baseBitrate * 3 / 2);
            case BITRATE_MEDIUM:
            default:
                // 100%，取整到 0.5Mbps
                return roundToHalfMbps(baseBitrate);
        }
    }
    
    /**
     * 将码率四舍五入到最接近的 0.5Mbps
     * @param bitrate 原始码率（bps）
     * @return 四舍五入后的码率（bps）
     */
    private static int roundToHalfMbps(int bitrate) {
        // 转换为 0.5Mbps 的倍数
        int halfMbps = 500000;
        int rounded = ((bitrate + halfMbps / 2) / halfMbps) * halfMbps;
        // 最小 0.5Mbps，最大 20Mbps
        return Math.max(halfMbps, Math.min(rounded, 20000000));
    }
    
    /**
     * 获取码率等级的显示名称
     */
    public static String getBitrateLevelDisplayName(String level) {
        switch (level) {
            case BITRATE_LOW:
                return "低";
            case BITRATE_HIGH:
                return "高";
            case BITRATE_MEDIUM:
            default:
                return "标准";
        }
    }
    
    /**
     * 格式化码率为可读字符串
     * @param bitrate 码率（bps）
     * @return 格式化字符串，如 "3.0 Mbps"
     */
    public static String formatBitrate(int bitrate) {
        float mbps = bitrate / 1000000f;
        if (mbps >= 1) {
            return String.format(java.util.Locale.getDefault(), "%.1f Mbps", mbps);
        } else {
            return String.format(java.util.Locale.getDefault(), "%d Kbps", bitrate / 1000);
        }
    }
    
    /**
     * 根据硬件最大帧率计算标准帧率（接近30fps的成倍降低值）
     * @param hardwareMaxFps 硬件支持的最大帧率
     * @return 标准帧率
     */
    public static int getStandardFrameRate(int hardwareMaxFps) {
        if (hardwareMaxFps <= 0) {
            return 30;  // 默认30fps
        }
        
        // 如果硬件帧率本身就是30或接近30，直接使用
        if (hardwareMaxFps >= 25 && hardwareMaxFps <= 35) {
            return hardwareMaxFps;
        }
        
        // 如果超过30，降到30或以下的整数倍
        if (hardwareMaxFps > 35) {
            // 60fps -> 30fps, 120fps -> 30fps
            int divisor = (hardwareMaxFps + 29) / 30;  // 向上取整
            int result = hardwareMaxFps / divisor;
            // 确保结果在合理范围内
            return Math.max(15, Math.min(result, 30));
        }
        
        // 如果低于25，直接使用硬件帧率
        return hardwareMaxFps;
    }
    
    // ==================== 帧率配置相关方法 ====================
    
    /**
     * 设置帧率等级
     * @param level 帧率等级（standard/low）
     */
    public void setFramerateLevel(String level) {
        prefs.edit().putString(KEY_FRAMERATE_LEVEL, level).apply();
        AppLog.d(TAG, "帧率等级设置: " + level);
    }
    
    /**
     * 获取帧率等级
     * @return 帧率等级，默认为 standard
     */
    public String getFramerateLevel() {
        return prefs.getString(KEY_FRAMERATE_LEVEL, FRAMERATE_STANDARD);
    }
    
    /**
     * 根据配置的帧率等级获取实际帧率
     * @param hardwareMaxFps 硬件支持的最大帧率
     * @return 实际使用的帧率
     */
    public int getActualFrameRate(int hardwareMaxFps) {
        int standardFps = getStandardFrameRate(hardwareMaxFps);
        String level = getFramerateLevel();
        
        if (FRAMERATE_LOW.equals(level)) {
            // 低帧率：标准值除以2，最低10fps
            return Math.max(10, standardFps / 2);
        }
        
        // 标准帧率
        return standardFps;
    }
    
    /**
     * 获取帧率等级的显示名称
     */
    public static String getFramerateLevelDisplayName(String level) {
        if (FRAMERATE_LOW.equals(level)) {
            return "低";
        }
        return "标准";
    }
    
    // ==================== 车型配置相关方法 ====================
    
    /**
     * 设置车型
     * @param carModel 车型标识（galaxy_e5 或 custom）
     */
    public void setCarModel(String carModel) {
        prefs.edit().putString(KEY_CAR_MODEL, carModel).apply();
        AppLog.d(TAG, "车型设置: " + carModel);
    }
    
    /**
     * 获取车型
     * @return 车型标识，默认为银河E5
     */
    public String getCarModel() {
        return prefs.getString(KEY_CAR_MODEL, CAR_MODEL_GALAXY_E5);
    }
    
    /**
     * 是否为自定义车型
     */
    public boolean isCustomCarModel() {
        return CAR_MODEL_CUSTOM.equals(getCarModel());
    }
    
    /**
     * 设置摄像头数量
     * @param count 摄像头数量（4/2/1）
     */
    public void setCameraCount(int count) {
        prefs.edit().putInt(KEY_CAMERA_COUNT, count).apply();
        AppLog.d(TAG, "摄像头数量设置: " + count);
    }
    
    /**
     * 获取摄像头数量
     * 对于预设车型返回固定数量，对于自定义车型返回用户设置的数量
     * @return 摄像头数量
     */
    public int getCameraCount() {
        String carModel = getCarModel();
        // 预设车型返回固定的摄像头数量
        switch (carModel) {
            case CAR_MODEL_PHONE:
                return 2;  // 手机：2摄
            case CAR_MODEL_GALAXY_E5:
            case CAR_MODEL_E5_MULTI:
            case CAR_MODEL_L7:
            case CAR_MODEL_L7_MULTI:
            case CAR_MODEL_XINGHAN_7:
                return 4;  // 银河E5/L7/26款星舰7：4摄
            case CAR_MODEL_CUSTOM:
            default:
                // 自定义车型使用用户设置的数量
                return prefs.getInt(KEY_CAMERA_COUNT, 4);
        }
    }
    
    /**
     * 获取用户设置的摄像头数量（仅用于自定义车型）
     * @return 用户设置的摄像头数量，默认为4
     */
    public int getCustomCameraCount() {
        return prefs.getInt(KEY_CAMERA_COUNT, 4);
    }
    
    /**
     * 设置屏幕方向（仅4摄像头时有效）
     * @param orientation 屏幕方向（landscape/portrait）
     */
    public void setScreenOrientation(String orientation) {
        prefs.edit().putString(KEY_SCREEN_ORIENTATION, orientation).apply();
        AppLog.d(TAG, "屏幕方向设置: " + orientation);
    }
    
    /**
     * 获取屏幕方向（仅4摄像头时有效）
     * @return 屏幕方向，默认为横屏
     */
    public String getScreenOrientation() {
        return prefs.getString(KEY_SCREEN_ORIENTATION, "landscape");
    }
    
    /**
     * 设置摄像头编号
     * @param position 位置（front/back/left/right）
     * @param cameraId 摄像头编号
     */
    public void setCameraId(String position, String cameraId) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_ID;
                break;
            case "back":
                key = KEY_CAMERA_BACK_ID;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_ID;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_ID;
                break;
            default:
                AppLog.w(TAG, "未知的摄像头位置: " + position);
                return;
        }
        prefs.edit().putString(key, cameraId).apply();
        AppLog.d(TAG, "摄像头编号设置: " + position + " = " + cameraId);
    }
    
    /**
     * 获取摄像头编号
     * @param position 位置（front/back/left/right）
     * @return 摄像头编号，默认为 -1 表示自动检测
     */
    public String getCameraId(String position) {
        String key;
        String defaultValue;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_ID;
                defaultValue = "2";  // 银河E5默认：前=2
                break;
            case "back":
                key = KEY_CAMERA_BACK_ID;
                defaultValue = "1";  // 银河E5默认：后=1
                break;
            case "left":
                key = KEY_CAMERA_LEFT_ID;
                defaultValue = "3";  // 银河E5默认：左=3
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_ID;
                defaultValue = "0";  // 银河E5默认：右=0
                break;
            default:
                return "-1";
        }
        return prefs.getString(key, defaultValue);
    }
    
    /**
     * 设置摄像头名称
     * @param position 位置（front/back/left/right）
     * @param name 摄像头名称
     */
    public void setCameraName(String position, String name) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_NAME;
                break;
            case "back":
                key = KEY_CAMERA_BACK_NAME;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_NAME;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_NAME;
                break;
            default:
                AppLog.w(TAG, "未知的摄像头位置: " + position);
                return;
        }
        prefs.edit().putString(key, name).apply();
        AppLog.d(TAG, "摄像头名称设置: " + position + " = " + name);
    }
    
    /**
     * 获取摄像头名称
     * 对于预设车型返回默认名称，对于自定义车型返回用户设置的名称
     * @param position 位置（front/back/left/right）
     * @return 摄像头名称
     */
    public String getCameraName(String position) {
        // 预设车型返回默认名称
        if (!isCustomCarModel()) {
            return getDefaultCameraName(position);
        }
        
        // 自定义车型返回用户设置的名称
        String key;
        String defaultValue = getDefaultCameraName(position);
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_NAME;
                break;
            case "back":
                key = KEY_CAMERA_BACK_NAME;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_NAME;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_NAME;
                break;
            default:
                return "未知";
        }
        return prefs.getString(key, defaultValue);
    }
    
    /**
     * 获取预设车型的默认摄像头名称
     * 新增预设车型时，如果名称不同于默认值，在此添加
     * @param position 位置（front/back/left/right）
     * @return 默认名称
     */
    public String getDefaultCameraName(String position) {
        // 默认名称（适用于大多数预设车型）
        switch (position) {
            case "front":
                return "前";
            case "back":
                return "后";
            case "left":
                return "左";
            case "right":
                return "右";
            default:
                return "未知";
        }
    }

    /**
     * 设置摄像头旋转角度（仅用于自定义车型）
     * @param position 位置（front/back/left/right）
     * @param rotation 旋转角度（0/90/180/270）
     */
    public void setCameraRotation(String position, int rotation) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_ROTATION;
                break;
            case "back":
                key = KEY_CAMERA_BACK_ROTATION;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_ROTATION;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_ROTATION;
                break;
            default:
                AppLog.w(TAG, "未知的摄像头位置: " + position);
                return;
        }
        prefs.edit().putInt(key, rotation).apply();
        AppLog.d(TAG, "摄像头旋转角度设置: " + position + " = " + rotation + "°");
    }

    /**
     * 获取摄像头旋转角度（仅用于自定义车型）
     * @param position 位置（front/back/left/right）
     * @return 旋转角度，默认为0（不旋转）
     */
    public int getCameraRotation(String position) {
        // 如果不是自定义车型，返回0（E5使用代码中的固定旋转）
        if (!isCustomCarModel()) {
            return 0;
        }

        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_ROTATION;
                break;
            case "back":
                key = KEY_CAMERA_BACK_ROTATION;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_ROTATION;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_ROTATION;
                break;
            default:
                return 0;
        }
        return prefs.getInt(key, 0);
    }
    
    /**
     * 设置摄像头镜像
     * @param position 摄像头位置（front/back/left/right）
     * @param mirror 是否镜像
     */
    public void setCameraMirror(String position, boolean mirror) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_MIRROR;
                break;
            case "back":
                key = KEY_CAMERA_BACK_MIRROR;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_MIRROR;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_MIRROR;
                break;
            default:
                return;
        }
        prefs.edit().putBoolean(key, mirror).apply();
        AppLog.d(TAG, position + " 摄像头镜像设置: " + mirror);
    }

    /**
     * 获取摄像头镜像设置
     * @param position 摄像头位置（front/back/left/right）
     * @return 是否镜像，默认为false（不镜像）
     */
    public boolean getCameraMirror(String position) {
        String key;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_MIRROR;
                break;
            case "back":
                key = KEY_CAMERA_BACK_MIRROR;
                break;
            case "left":
                key = KEY_CAMERA_LEFT_MIRROR;
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_MIRROR;
                break;
            default:
                return false;
        }
        return prefs.getBoolean(key, false);
    }

    /**
     * 设置摄像头裁剪值
     * @param position 位置（front/back/left/right）
     * @param direction 方向（top/bottom/left/right）
     * @param pixels 裁剪像素值
     */
    public void setCameraCrop(String position, String direction, int pixels) {
        String key = KEY_CAMERA_CROP_PREFIX + position + "_" + direction;
        prefs.edit().putInt(key, Math.max(0, pixels)).apply();
    }

    /**
     * 获取摄像头裁剪值
     * @param position 位置（front/back/left/right）
     * @param direction 方向（top/bottom/left/right）
     * @return 裁剪像素值，默认为0
     */
    public int getCameraCrop(String position, String direction) {
        String key = KEY_CAMERA_CROP_PREFIX + position + "_" + direction;
        return prefs.getInt(key, 0);
    }

    /**
     * 重置摄像头的所有裁剪值
     * @param position 位置（front/back/left/right）
     */
    public void resetCameraCrop(String position) {
        setCameraCrop(position, "top", 0);
        setCameraCrop(position, "bottom", 0);
        setCameraCrop(position, "left", 0);
        setCameraCrop(position, "right", 0);
    }

    /**
     * 获取所有摄像头配置（用于自定义车型）
     * 返回格式：position -> [cameraId, cameraName]
     */
    public String[][] getAllCameraConfig() {
        int count = getCameraCount();
        String[][] config;
        
        if (count == 4) {
            config = new String[][] {
                {"front", getCameraId("front"), getCameraName("front")},
                {"back", getCameraId("back"), getCameraName("back")},
                {"left", getCameraId("left"), getCameraName("left")},
                {"right", getCameraId("right"), getCameraName("right")}
            };
        } else if (count == 2) {
            config = new String[][] {
                {"front", getCameraId("front"), getCameraName("front")},
                {"back", getCameraId("back"), getCameraName("back")}
            };
        } else {
            config = new String[][] {
                {"front", getCameraId("front"), getCameraName("front")}
            };
        }
        
        return config;
    }
    
    // ==================== 存储位置配置相关方法 ====================
    
    /**
     * 设置存储位置
     * @param location 存储位置（internal 或 external_sd）
     */
    public void setStorageLocation(String location) {
        prefs.edit().putString(KEY_STORAGE_LOCATION, location).apply();
        AppLog.d(TAG, "存储位置设置: " + location);
    }
    
    /**
     * 获取存储位置
     * @return 存储位置，默认为内部存储
     */
    public String getStorageLocation() {
        return prefs.getString(KEY_STORAGE_LOCATION, STORAGE_INTERNAL);
    }
    
    /**
     * 是否使用U盘存储
     * @return true 表示使用U盘
     */
    public boolean isUsingExternalSdCard() {
        return STORAGE_EXTERNAL_SD.equals(getStorageLocation());
    }
    
    /**
     * 设置自定义U盘路径
     * @param path U盘路径，设为null或空字符串表示使用自动检测
     */
    public void setCustomSdCardPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            prefs.edit().remove(KEY_CUSTOM_SD_CARD_PATH).apply();
            AppLog.d(TAG, "清除自定义U盘路径，使用自动检测");
        } else {
            prefs.edit().putString(KEY_CUSTOM_SD_CARD_PATH, path.trim()).apply();
            AppLog.d(TAG, "设置自定义U盘路径: " + path.trim());
        }
    }
    
    /**
     * 获取自定义U盘路径
     * @return 自定义路径，如果未设置返回null
     */
    public String getCustomSdCardPath() {
        String path = prefs.getString(KEY_CUSTOM_SD_CARD_PATH, null);
        if (path != null && path.trim().isEmpty()) {
            return null;
        }
        return path;
    }
    
    /**
     * 是否使用自定义U盘路径
     */
    public boolean hasCustomSdCardPath() {
        return getCustomSdCardPath() != null;
    }
    
    /**
     * 设置上次自动检测到的U盘路径（缓存）
     * @param path U盘路径
     */
    public void setLastDetectedSdPath(String path) {
        if (path == null || path.trim().isEmpty()) {
            prefs.edit().remove(KEY_LAST_DETECTED_SD_PATH).apply();
        } else {
            prefs.edit().putString(KEY_LAST_DETECTED_SD_PATH, path.trim()).apply();
            AppLog.d(TAG, "缓存U盘路径: " + path.trim());
        }
    }
    
    /**
     * 获取上次自动检测到的U盘路径（缓存）
     * @return 缓存的路径，如果未设置返回null
     */
    public String getLastDetectedSdPath() {
        return prefs.getString(KEY_LAST_DETECTED_SD_PATH, null);
    }
    
    /**
     * 检查本次启动是否已显示过U盘回退提示
     */
    public static boolean isSdFallbackShownThisSession() {
        return sdFallbackShownThisSession;
    }
    
    /**
     * 标记本次启动已显示过U盘回退提示
     */
    public static void setSdFallbackShownThisSession(boolean shown) {
        sdFallbackShownThisSession = shown;
    }
    
    /**
     * 重置U盘回退提示标志（应用启动时调用）
     */
    public static void resetSdFallbackFlag() {
        sdFallbackShownThisSession = false;
    }
    
    /**
     * 检查当前是否应该使用中转写入
     * 当选择U盘存储时，始终使用中转写入以避免U盘慢速写入导致录制卡顿
     * @return true 表示应该使用中转写入
     */
    public boolean shouldUseRelayWrite() {
        return isUsingExternalSdCard();
    }
    
    // ==================== 悬浮窗配置相关方法 ====================
    
    /**
     * 设置悬浮窗开关
     * @param enabled true 表示启用悬浮窗
     */
    public void setFloatingWindowEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FLOATING_WINDOW_ENABLED, enabled).apply();
        AppLog.d(TAG, "悬浮窗设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取悬浮窗开关状态
     * @return true 表示启用悬浮窗
     */
    public boolean isFloatingWindowEnabled() {
        return prefs.getBoolean(KEY_FLOATING_WINDOW_ENABLED, false);
    }
    
    /**
     * 设置悬浮窗大小（dp）
     * @param sizeDp 悬浮窗大小，单位dp
     */
    public void setFloatingWindowSize(int sizeDp) {
        prefs.edit().putInt(KEY_FLOATING_WINDOW_SIZE, sizeDp).apply();
        AppLog.d(TAG, "悬浮窗大小设置: " + sizeDp + "dp");
    }
    
    /**
     * 获取悬浮窗大小（dp）
     * @return 悬浮窗大小，默认为中等大小
     */
    public int getFloatingWindowSize() {
        return prefs.getInt(KEY_FLOATING_WINDOW_SIZE, FLOATING_SIZE_MEDIUM);
    }
    
    /**
     * 设置悬浮窗透明度（0-100）
     * @param alpha 透明度百分比，0为完全透明，100为完全不透明
     */
    public void setFloatingWindowAlpha(int alpha) {
        prefs.edit().putInt(KEY_FLOATING_WINDOW_ALPHA, alpha).apply();
        AppLog.d(TAG, "悬浮窗透明度设置: " + alpha + "%");
    }
    
    /**
     * 获取悬浮窗透明度（0-100）
     * @return 透明度百分比，默认为100（完全不透明）
     */
    public int getFloatingWindowAlpha() {
        return prefs.getInt(KEY_FLOATING_WINDOW_ALPHA, 100);
    }
    
    /**
     * 保存悬浮窗位置
     * @param x X坐标
     * @param y Y坐标
     */
    public void setFloatingWindowPosition(int x, int y) {
        prefs.edit()
            .putInt(KEY_FLOATING_WINDOW_X, x)
            .putInt(KEY_FLOATING_WINDOW_Y, y)
            .apply();
    }
    
    /**
     * 获取悬浮窗X位置
     * @return X坐标，默认-1表示未设置
     */
    public int getFloatingWindowX() {
        return prefs.getInt(KEY_FLOATING_WINDOW_X, -1);
    }
    
    /**
     * 获取悬浮窗Y位置
     * @return Y坐标，默认-1表示未设置
     */
    public int getFloatingWindowY() {
        return prefs.getInt(KEY_FLOATING_WINDOW_Y, -1);
    }
    
    // ==================== 存储清理配置相关方法 ====================
    
    /**
     * 设置视频存储限制（GB）
     * @param limitGb 存储限制，单位GB，0表示不限制
     */
    public void setVideoStorageLimitGb(int limitGb) {
        prefs.edit().putInt(KEY_VIDEO_STORAGE_LIMIT_GB, limitGb).apply();
        AppLog.d(TAG, "视频存储限制设置: " + limitGb + " GB");
    }
    
    /**
     * 获取视频存储限制（GB）
     * @return 存储限制，单位GB，0表示不限制，默认10GB
     */
    public int getVideoStorageLimitGb() {
        return prefs.getInt(KEY_VIDEO_STORAGE_LIMIT_GB, 10);
    }
    
    /**
     * 设置图片存储限制（GB）
     * @param limitGb 存储限制，单位GB，0表示不限制
     */
    public void setPhotoStorageLimitGb(int limitGb) {
        prefs.edit().putInt(KEY_PHOTO_STORAGE_LIMIT_GB, limitGb).apply();
        AppLog.d(TAG, "图片存储限制设置: " + limitGb + " GB");
    }
    
    /**
     * 获取图片存储限制（GB）
     * @return 存储限制，单位GB，0表示不限制，默认10GB
     */
    public int getPhotoStorageLimitGb() {
        return prefs.getInt(KEY_PHOTO_STORAGE_LIMIT_GB, 10);
    }
    
    /**
     * 检查是否启用了存储清理功能
     * @return true 如果至少有一项存储限制设置大于0
     */
    public boolean isStorageCleanupEnabled() {
        return getVideoStorageLimitGb() > 0 || getPhotoStorageLimitGb() > 0;
    }
    
    // ==================== 分段录制配置相关方法 ====================
    
    /**
     * 设置分段时长（分钟）
     * @param minutes 分段时长，单位分钟（1/3/5）
     */
    public void setSegmentDurationMinutes(int minutes) {
        prefs.edit().putInt(KEY_SEGMENT_DURATION_MINUTES, minutes).apply();
        AppLog.d(TAG, "分段时长设置: " + minutes + " 分钟");
    }
    
    /**
     * 获取分段时长（分钟）
     * @return 分段时长，单位分钟，默认为1分钟
     */
    public int getSegmentDurationMinutes() {
        return prefs.getInt(KEY_SEGMENT_DURATION_MINUTES, SEGMENT_DURATION_1_MIN);
    }
    
    /**
     * 获取分段时长（毫秒）
     * @return 分段时长，单位毫秒
     */
    public long getSegmentDurationMs() {
        return getSegmentDurationMinutes() * 60 * 1000L;
    }
    
    // ==================== 录制状态显示配置相关方法 ====================
    
    /**
     * 设置录制状态显示开关
     * @param enabled true 表示显示录制时间和分段数
     */
    public void setRecordingStatsEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_RECORDING_STATS_ENABLED, enabled).apply();
        AppLog.d(TAG, "录制状态显示设置: " + (enabled ? "显示" : "隐藏"));
    }
    
    /**
     * 获取录制状态显示开关状态
     * @return true 表示显示录制时间和分段数
     */
    public boolean isRecordingStatsEnabled() {
        // 默认开启录制状态显示
        return prefs.getBoolean(KEY_RECORDING_STATS_ENABLED, true);
    }
    
    // ==================== 补盲选项配置相关方法 (原副屏显示) ====================
    
    /**
     * 设置副屏显示开关
     */
    public void setSecondaryDisplayEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SECONDARY_DISPLAY_ENABLED, enabled).apply();
        AppLog.d(TAG, "副屏显示设置: " + (enabled ? "启用" : "禁用"));
    }
    
    public boolean isSecondaryDisplayEnabled() {
        return prefs.getBoolean(KEY_SECONDARY_DISPLAY_ENABLED, false);
    }
    
    /**
     * 设置副屏显示的摄像头位置
     */
    public void setSecondaryDisplayCamera(String position) {
        prefs.edit().putString(KEY_SECONDARY_DISPLAY_CAMERA, position).apply();
    }
    
    public String getSecondaryDisplayCamera() {
        return prefs.getString(KEY_SECONDARY_DISPLAY_CAMERA, "front");
    }
    
    /**
     * 设置副屏 Display ID
     */
    public void setSecondaryDisplayId(int displayId) {
        prefs.edit().putInt(KEY_SECONDARY_DISPLAY_ID, displayId).apply();
    }
    
    public int getSecondaryDisplayId() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_ID, 0); // 0 为默认主屏，通常副屏从1开始
    }
    
    /**
     * 设置副屏位置和大小
     */
    public void setSecondaryDisplayBounds(int x, int y, int width, int height) {
        prefs.edit()
            .putInt(KEY_SECONDARY_DISPLAY_X, x)
            .putInt(KEY_SECONDARY_DISPLAY_Y, y)
            .putInt(KEY_SECONDARY_DISPLAY_WIDTH, width)
            .putInt(KEY_SECONDARY_DISPLAY_HEIGHT, height)
            .apply();
    }
    
    public int getSecondaryDisplayX() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_X, 0);
    }
    
    public int getSecondaryDisplayY() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_Y, 139);
    }
    
    public int getSecondaryDisplayWidth() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_WIDTH, 318);
    }
    
    public int getSecondaryDisplayHeight() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_HEIGHT, 236);
    }
    
    /**
     * 设置副屏旋转角度
     */
    public void setSecondaryDisplayRotation(int rotation) {
        prefs.edit().putInt(KEY_SECONDARY_DISPLAY_ROTATION, rotation).apply();
    }
    
    public int getSecondaryDisplayRotation() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_ROTATION, 0);
    }
    
    /**
     * 设置是否显示白边框
     */
    public void setSecondaryDisplayBorderEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_SECONDARY_DISPLAY_BORDER, enabled).apply();
    }
    
    public boolean isSecondaryDisplayBorderEnabled() {
        return prefs.getBoolean(KEY_SECONDARY_DISPLAY_BORDER, false);
    }
    
    /**
     * 设置屏幕方向
     */
    public void setSecondaryDisplayOrientation(int orientation) {
        prefs.edit().putInt(KEY_SECONDARY_DISPLAY_ORIENTATION, orientation).apply();
    }
    
    public int getSecondaryDisplayOrientation() {
        return prefs.getInt(KEY_SECONDARY_DISPLAY_ORIENTATION, 180);
    }

    public void setBlindSpotCorrectionEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_BLIND_SPOT_CORRECTION_ENABLED, enabled).apply();
    }

    public boolean isBlindSpotCorrectionEnabled() {
        return prefs.getBoolean(KEY_BLIND_SPOT_CORRECTION_ENABLED, false);
    }

    public void setBlindSpotDisclaimerAccepted(boolean accepted) {
        prefs.edit().putBoolean(KEY_BLIND_SPOT_DISCLAIMER_ACCEPTED, accepted).apply();
    }

    public boolean isBlindSpotDisclaimerAccepted() {
        return prefs.getBoolean(KEY_BLIND_SPOT_DISCLAIMER_ACCEPTED, false);
    }

    private String getBlindSpotCorrectionKey(String cameraPos, String suffix) {
        return KEY_BLIND_SPOT_CORRECTION_PREFIX + cameraPos + "_" + suffix;
    }

    public void setBlindSpotCorrectionScaleX(String cameraPos, float scaleX) {
        prefs.edit().putFloat(getBlindSpotCorrectionKey(cameraPos, "scale_x"), scaleX).apply();
    }

    public void setBlindSpotCorrectionScaleY(String cameraPos, float scaleY) {
        prefs.edit().putFloat(getBlindSpotCorrectionKey(cameraPos, "scale_y"), scaleY).apply();
    }

    public void setBlindSpotCorrectionTranslateX(String cameraPos, float translateX) {
        prefs.edit().putFloat(getBlindSpotCorrectionKey(cameraPos, "translate_x"), translateX).apply();
    }

    public void setBlindSpotCorrectionTranslateY(String cameraPos, float translateY) {
        prefs.edit().putFloat(getBlindSpotCorrectionKey(cameraPos, "translate_y"), translateY).apply();
    }

    public float getBlindSpotCorrectionScaleX(String cameraPos) {
        return prefs.getFloat(getBlindSpotCorrectionKey(cameraPos, "scale_x"), 1.0f);
    }

    public float getBlindSpotCorrectionScaleY(String cameraPos) {
        return prefs.getFloat(getBlindSpotCorrectionKey(cameraPos, "scale_y"), 1.0f);
    }

    public float getBlindSpotCorrectionTranslateX(String cameraPos) {
        return prefs.getFloat(getBlindSpotCorrectionKey(cameraPos, "translate_x"), 0.0f);
    }

    public float getBlindSpotCorrectionTranslateY(String cameraPos) {
        return prefs.getFloat(getBlindSpotCorrectionKey(cameraPos, "translate_y"), 0.0f);
    }

    public void resetBlindSpotCorrection(String cameraPos) {
        prefs.edit()
                .putFloat(getBlindSpotCorrectionKey(cameraPos, "scale_x"), 1.0f)
                .putFloat(getBlindSpotCorrectionKey(cameraPos, "scale_y"), 1.0f)
                .putFloat(getBlindSpotCorrectionKey(cameraPos, "translate_x"), 0.0f)
                .putFloat(getBlindSpotCorrectionKey(cameraPos, "translate_y"), 0.0f)
                .apply();
    }

    // ==================== 主屏悬浮窗配置相关方法 ====================

    /**
     * 设置主屏悬浮窗开关
     */
    public void setMainFloatingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MAIN_FLOATING_ENABLED, enabled).apply();
        AppLog.d(TAG, "主屏悬浮窗设置: " + (enabled ? "启用" : "禁用"));
    }

    public boolean isMainFloatingEnabled() {
        return prefs.getBoolean(KEY_MAIN_FLOATING_ENABLED, false);
    }

    /**
     * 设置主屏悬浮窗显示的摄像头位置
     */
    public void setMainFloatingCamera(String position) {
        prefs.edit().putString(KEY_MAIN_FLOATING_CAMERA, position).apply();
    }

    public String getMainFloatingCamera() {
        return prefs.getString(KEY_MAIN_FLOATING_CAMERA, "front");
    }

    /**
     * 设置主屏悬浮窗位置和大小
     */
    public void setMainFloatingBounds(int x, int y, int width, int height) {
        prefs.edit()
            .putInt(KEY_MAIN_FLOATING_X, x)
            .putInt(KEY_MAIN_FLOATING_Y, y)
            .putInt(KEY_MAIN_FLOATING_WIDTH, width)
            .putInt(KEY_MAIN_FLOATING_HEIGHT, height)
            .apply();
    }

    public int getMainFloatingX() {
        return prefs.getInt(KEY_MAIN_FLOATING_X, 100);
    }

    public int getMainFloatingY() {
        return prefs.getInt(KEY_MAIN_FLOATING_Y, 100);
    }

    public int getMainFloatingWidth() {
        return prefs.getInt(KEY_MAIN_FLOATING_WIDTH, 480);
    }

    public int getMainFloatingHeight() {
        return prefs.getInt(KEY_MAIN_FLOATING_HEIGHT, 320);
    }

    // ==================== 转向灯联动配置相关方法 ====================

    /**
     * 设置转向灯联动开关
     */
    public void setTurnSignalLinkageEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TURN_SIGNAL_LINKAGE_ENABLED, enabled).apply();
        AppLog.d(TAG, "转向灯联动设置: " + (enabled ? "启用" : "禁用"));
    }

    public boolean isTurnSignalLinkageEnabled() {
        return prefs.getBoolean(KEY_TURN_SIGNAL_LINKAGE_ENABLED, false);
    }

    /**
     * 设置转向灯熄灭后的延迟消失时间（秒）
     */
    public void setTurnSignalTimeout(int seconds) {
        prefs.edit().putInt(KEY_TURN_SIGNAL_TIMEOUT, seconds).apply();
    }

   public int getTurnSignalTimeout() {
        return prefs.getInt(KEY_TURN_SIGNAL_TIMEOUT, 10);
    }

    /**
     * 设置是否复用主屏悬浮窗
     */
    public void setTurnSignalReuseMainFloating(boolean reuse) {
        prefs.edit().putBoolean(KEY_TURN_SIGNAL_REUSE_MAIN_FLOATING, reuse).apply();
    }

    public boolean isTurnSignalReuseMainFloating() {
        return prefs.getBoolean(KEY_TURN_SIGNAL_REUSE_MAIN_FLOATING, true);
    }

    public void setTurnSignalCustomLeftTriggerLog(String keyword) {
        prefs.edit().putString(KEY_TURN_SIGNAL_CUSTOM_LEFT_TRIGGER_LOG, keyword).apply();
    }

    public String getTurnSignalCustomLeftTriggerLog() {
        return prefs.getString(
                KEY_TURN_SIGNAL_CUSTOM_LEFT_TRIGGER_LOG,
                "left front turn signal:1"
        );
    }

    public void setTurnSignalCustomRightTriggerLog(String keyword) {
        prefs.edit().putString(KEY_TURN_SIGNAL_CUSTOM_RIGHT_TRIGGER_LOG, keyword).apply();
    }

    public String getTurnSignalCustomRightTriggerLog() {
        return prefs.getString(
                KEY_TURN_SIGNAL_CUSTOM_RIGHT_TRIGGER_LOG,
                "right front turn signal:1"
        );
    }

    public String getTurnSignalLeftTriggerLog() {
        return getTurnSignalCustomLeftTriggerLog();
    }

    public String getTurnSignalRightTriggerLog() {
        return getTurnSignalCustomRightTriggerLog();
    }

    /**
     * 设置独立补盲悬浮窗位置和大小
     */
    public void setTurnSignalFloatingBounds(int x, int y, int width, int height) {
        prefs.edit()
            .putInt(KEY_TURN_SIGNAL_FLOATING_X, x)
            .putInt(KEY_TURN_SIGNAL_FLOATING_Y, y)
            .putInt(KEY_TURN_SIGNAL_FLOATING_WIDTH, width)
            .putInt(KEY_TURN_SIGNAL_FLOATING_HEIGHT, height)
            .apply();
    }

    public int getTurnSignalFloatingX() {
        return prefs.getInt(KEY_TURN_SIGNAL_FLOATING_X, 200);
    }

    public int getTurnSignalFloatingY() {
        return prefs.getInt(KEY_TURN_SIGNAL_FLOATING_Y, 200);
    }

    public int getTurnSignalFloatingWidth() {
        return prefs.getInt(KEY_TURN_SIGNAL_FLOATING_WIDTH, 640);
    }

    public int getTurnSignalFloatingHeight() {
        return prefs.getInt(KEY_TURN_SIGNAL_FLOATING_HEIGHT, 360);
    }

    /**
     * 设置独立补盲悬浮窗旋转
     */
    public void setTurnSignalFloatingRotation(int rotation) {
        prefs.edit().putInt(KEY_TURN_SIGNAL_FLOATING_ROTATION, rotation).apply();
    }

    public int getTurnSignalFloatingRotation() {
        return prefs.getInt(KEY_TURN_SIGNAL_FLOATING_ROTATION, 0);
    }

    // ==================== 桌面悬浮模拟按钮配置相关方法 ====================

    public void setMockTurnSignalFloatingEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_MOCK_TURN_SIGNAL_FLOATING_ENABLED, enabled).apply();
    }

    public boolean isMockTurnSignalFloatingEnabled() {
        return prefs.getBoolean(KEY_MOCK_TURN_SIGNAL_FLOATING_ENABLED, false);
    }

    public void setMockTurnSignalFloatingPosition(int x, int y) {
        prefs.edit()
                .putInt(KEY_MOCK_TURN_SIGNAL_FLOATING_X, x)
                .putInt(KEY_MOCK_TURN_SIGNAL_FLOATING_Y, y)
                .apply();
    }

    public int getMockTurnSignalFloatingX() {
        return prefs.getInt(KEY_MOCK_TURN_SIGNAL_FLOATING_X, 200);
    }

    public int getMockTurnSignalFloatingY() {
        return prefs.getInt(KEY_MOCK_TURN_SIGNAL_FLOATING_Y, 200);
    }
    
    // ==================== 时间角标配置相关方法 ====================
    
    /**
     * 设置时间角标开关
     * @param enabled true 表示在保存的视频和图片上添加时间角标
     */
    public void setTimestampWatermarkEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_TIMESTAMP_WATERMARK_ENABLED, enabled).apply();
        AppLog.d(TAG, "时间角标设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取时间角标开关状态
     * @return true 表示启用时间角标
     */
    public boolean isTimestampWatermarkEnabled() {
        // 默认关闭时间角标
        return prefs.getBoolean(KEY_TIMESTAMP_WATERMARK_ENABLED, false);
    }
    
    // ==================== 录制摄像头选择配置相关方法 ====================
    
    /**
     * 设置某个摄像头是否参与主界面录制
     * @param position 位置（front/back/left/right）
     * @param enabled true 表示参与录制
     */
    public void setRecordingCameraEnabled(String position, boolean enabled) {
        String key;
        switch (position) {
            case "front":
                key = KEY_RECORDING_CAMERA_FRONT_ENABLED;
                break;
            case "back":
                key = KEY_RECORDING_CAMERA_BACK_ENABLED;
                break;
            case "left":
                key = KEY_RECORDING_CAMERA_LEFT_ENABLED;
                break;
            case "right":
                key = KEY_RECORDING_CAMERA_RIGHT_ENABLED;
                break;
            default:
                AppLog.w(TAG, "未知的摄像头位置: " + position);
                return;
        }
        prefs.edit().putBoolean(key, enabled).apply();
        AppLog.d(TAG, "录制摄像头设置: " + position + " = " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取某个摄像头是否参与主界面录制
     * @param position 位置（front/back/left/right）
     * @return true 表示参与录制，默认为 true
     */
    public boolean isRecordingCameraEnabled(String position) {
        String key;
        switch (position) {
            case "front":
                key = KEY_RECORDING_CAMERA_FRONT_ENABLED;
                break;
            case "back":
                key = KEY_RECORDING_CAMERA_BACK_ENABLED;
                break;
            case "left":
                key = KEY_RECORDING_CAMERA_LEFT_ENABLED;
                break;
            case "right":
                key = KEY_RECORDING_CAMERA_RIGHT_ENABLED;
                break;
            default:
                return true;  // 未知位置默认启用
        }
        // 默认启用（全选）
        return prefs.getBoolean(key, true);
    }
    
    /**
     * 获取所有启用录制的摄像头位置集合
     * 仅返回当前车型配置中存在的摄像头
     * @return 启用的摄像头位置集合（如 ["front", "back"]）
     */
    public java.util.Set<String> getEnabledRecordingCameras() {
        java.util.Set<String> enabled = new java.util.HashSet<>();
        int cameraCount = getCameraCount();
        
        // 根据摄像头数量判断哪些位置存在
        if (cameraCount >= 1 && isRecordingCameraEnabled("front")) {
            enabled.add("front");
        }
        if (cameraCount >= 2 && isRecordingCameraEnabled("back")) {
            enabled.add("back");
        }
        if (cameraCount >= 4) {
            if (isRecordingCameraEnabled("left")) {
                enabled.add("left");
            }
            if (isRecordingCameraEnabled("right")) {
                enabled.add("right");
            }
        }
        
        // 安全检查：如果结果为空，返回所有可用摄像头（防止无法录制）
        if (enabled.isEmpty()) {
            AppLog.w(TAG, "没有启用的录制摄像头，自动启用所有可用摄像头");
            if (cameraCount >= 1) enabled.add("front");
            if (cameraCount >= 2) enabled.add("back");
            if (cameraCount >= 4) {
                enabled.add("left");
                enabled.add("right");
            }
            // 同时重置配置
            resetRecordingCameraSelection();
        }
        
        return enabled;
    }
    
    /**
     * 重置录制摄像头选择为全选
     */
    public void resetRecordingCameraSelection() {
        prefs.edit()
            .putBoolean(KEY_RECORDING_CAMERA_FRONT_ENABLED, true)
            .putBoolean(KEY_RECORDING_CAMERA_BACK_ENABLED, true)
            .putBoolean(KEY_RECORDING_CAMERA_LEFT_ENABLED, true)
            .putBoolean(KEY_RECORDING_CAMERA_RIGHT_ENABLED, true)
            .apply();
        AppLog.d(TAG, "录制摄像头选择已重置为全选");
    }
    
    /**
     * 获取用于显示的摄像头名称（用于录制摄像头选择等设置界面）
     * 使用配置中的名称，如果为空则返回"位置N"
     * @param position 位置（front/back/left/right）
     * @param index 位置索引（1-4）
     * @return 显示名称
     */
    public String getRecordingCameraDisplayName(String position, int index) {
        String name = getCameraName(position);
        // 如果名称为空或仅为空白，使用位置名称
        if (name == null || name.trim().isEmpty()) {
            return "位置" + index;
        }
        return name;
    }
    
    // ==================== 亮度/降噪调节配置相关方法 ====================
    
    /**
     * 设置是否启用亮度/降噪调节
     * @param enabled true 表示启用
     */
    public void setImageAdjustEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_IMAGE_ADJUST_ENABLED, enabled).apply();
        AppLog.d(TAG, "亮度/降噪调节设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取是否启用亮度/降噪调节
     * @return true 表示启用
     */
    public boolean isImageAdjustEnabled() {
        return prefs.getBoolean(KEY_IMAGE_ADJUST_ENABLED, false);
    }
    
    /**
     * 设置曝光补偿值
     * @param value 曝光补偿值（范围取决于设备，通常 -12 到 +12）
     */
    public void setExposureCompensation(int value) {
        prefs.edit().putInt(KEY_EXPOSURE_COMPENSATION, value).apply();
        AppLog.d(TAG, "曝光补偿设置: " + value);
    }
    
    /**
     * 获取曝光补偿值
     * @return 曝光补偿值，默认为 0
     */
    public int getExposureCompensation() {
        return prefs.getInt(KEY_EXPOSURE_COMPENSATION, 0);
    }
    
    /**
     * 设置白平衡模式
     * @param mode 白平衡模式（AWB_MODE_* 常量）
     */
    public void setAwbMode(int mode) {
        prefs.edit().putInt(KEY_AWB_MODE, mode).apply();
        AppLog.d(TAG, "白平衡模式设置: " + mode);
    }
    
    /**
     * 获取白平衡模式
     * @return 白平衡模式，默认为 AWB_MODE_DEFAULT（不设置）
     */
    public int getAwbMode() {
        return prefs.getInt(KEY_AWB_MODE, AWB_MODE_DEFAULT);
    }
    
    /**
     * 设置色调映射模式
     * @param mode 色调映射模式（TONEMAP_MODE_* 常量）
     */
    public void setTonemapMode(int mode) {
        prefs.edit().putInt(KEY_TONEMAP_MODE, mode).apply();
        AppLog.d(TAG, "色调映射模式设置: " + mode);
    }
    
    /**
     * 获取色调映射模式
     * @return 色调映射模式，默认为 TONEMAP_MODE_DEFAULT（不设置）
     */
    public int getTonemapMode() {
        return prefs.getInt(KEY_TONEMAP_MODE, TONEMAP_MODE_DEFAULT);
    }
    
    /**
     * 设置边缘增强模式
     * @param mode 边缘增强模式（EDGE_MODE_* 常量）
     */
    public void setEdgeMode(int mode) {
        prefs.edit().putInt(KEY_EDGE_MODE, mode).apply();
        AppLog.d(TAG, "边缘增强模式设置: " + mode);
    }
    
    /**
     * 获取边缘增强模式
     * @return 边缘增强模式，默认为 EDGE_MODE_DEFAULT（不设置）
     */
    public int getEdgeMode() {
        return prefs.getInt(KEY_EDGE_MODE, EDGE_MODE_DEFAULT);
    }
    
    /**
     * 设置降噪模式
     * @param mode 降噪模式（NOISE_REDUCTION_* 常量）
     */
    public void setNoiseReductionMode(int mode) {
        prefs.edit().putInt(KEY_NOISE_REDUCTION_MODE, mode).apply();
        AppLog.d(TAG, "降噪模式设置: " + mode);
    }
    
    /**
     * 获取降噪模式
     * @return 降噪模式，默认为 NOISE_REDUCTION_DEFAULT（不设置）
     */
    public int getNoiseReductionMode() {
        return prefs.getInt(KEY_NOISE_REDUCTION_MODE, NOISE_REDUCTION_DEFAULT);
    }
    
    /**
     * 设置特效模式
     * @param mode 特效模式（EFFECT_MODE_* 常量）
     */
    public void setEffectMode(int mode) {
        prefs.edit().putInt(KEY_EFFECT_MODE, mode).apply();
        AppLog.d(TAG, "特效模式设置: " + mode);
    }
    
    /**
     * 获取特效模式
     * @return 特效模式，默认为 EFFECT_MODE_DEFAULT（不设置）
     */
    public int getEffectMode() {
        return prefs.getInt(KEY_EFFECT_MODE, EFFECT_MODE_DEFAULT);
    }
    
    /**
     * 设置场景模式
     * @param mode 场景模式
     */
    public void setSceneMode(int mode) {
        prefs.edit().putInt(KEY_SCENE_MODE, mode).apply();
        AppLog.d(TAG, "场景模式设置: " + mode);
    }
    
    /**
     * 获取场景模式
     * @return 场景模式，默认为 -1（不设置）
     */
    public int getSceneMode() {
        return prefs.getInt(KEY_SCENE_MODE, -1);
    }
    
    /**
     * 重置所有亮度/降噪调节参数为默认值
     */
    public void resetImageAdjustParams() {
        prefs.edit()
            .putInt(KEY_EXPOSURE_COMPENSATION, 0)
            .putInt(KEY_AWB_MODE, AWB_MODE_DEFAULT)
            .putInt(KEY_TONEMAP_MODE, TONEMAP_MODE_DEFAULT)
            .putInt(KEY_EDGE_MODE, EDGE_MODE_DEFAULT)
            .putInt(KEY_NOISE_REDUCTION_MODE, NOISE_REDUCTION_DEFAULT)
            .putInt(KEY_EFFECT_MODE, EFFECT_MODE_DEFAULT)
            .putInt(KEY_SCENE_MODE, -1)
            .apply();
        AppLog.d(TAG, "亮度/降噪调节参数已重置为默认值");
    }
    
    /**
     * 获取白平衡模式的显示名称
     */
    public static String getAwbModeDisplayName(int mode) {
        switch (mode) {
            case AWB_MODE_DEFAULT: return "默认";
            case AWB_MODE_AUTO: return "自动";
            case AWB_MODE_INCANDESCENT: return "白炽灯";
            case AWB_MODE_FLUORESCENT: return "荧光灯";
            case AWB_MODE_WARM_FLUORESCENT: return "暖荧光灯";
            case AWB_MODE_DAYLIGHT: return "日光";
            case AWB_MODE_CLOUDY_DAYLIGHT: return "阴天";
            case AWB_MODE_TWILIGHT: return "黄昏";
            case AWB_MODE_SHADE: return "阴影";
            default: return "未知";
        }
    }
    
    /**
     * 获取色调映射模式的显示名称
     */
    public static String getTonemapModeDisplayName(int mode) {
        switch (mode) {
            case TONEMAP_MODE_DEFAULT: return "默认";
            case TONEMAP_MODE_CONTRAST_CURVE: return "对比度曲线";
            case TONEMAP_MODE_FAST: return "快速";
            case TONEMAP_MODE_HIGH_QUALITY: return "高质量";
            default: return "未知";
        }
    }
    
    /**
     * 获取边缘增强模式的显示名称
     */
    public static String getEdgeModeDisplayName(int mode) {
        switch (mode) {
            case EDGE_MODE_DEFAULT: return "默认";
            case EDGE_MODE_OFF: return "关闭";
            case EDGE_MODE_FAST: return "快速";
            case EDGE_MODE_HIGH_QUALITY: return "高质量";
            default: return "未知";
        }
    }
    
    /**
     * 获取降噪模式的显示名称
     */
    public static String getNoiseReductionModeDisplayName(int mode) {
        switch (mode) {
            case NOISE_REDUCTION_DEFAULT: return "默认";
            case NOISE_REDUCTION_OFF: return "关闭";
            case NOISE_REDUCTION_FAST: return "快速";
            case NOISE_REDUCTION_HIGH_QUALITY: return "高质量";
            default: return "未知";
        }
    }
    
    /**
     * 获取特效模式的显示名称
     */
    public static String getEffectModeDisplayName(int mode) {
        switch (mode) {
            case EFFECT_MODE_DEFAULT: return "默认";
            case EFFECT_MODE_OFF: return "关闭";
            case EFFECT_MODE_MONO: return "黑白";
            case EFFECT_MODE_NEGATIVE: return "负片";
            case EFFECT_MODE_SOLARIZE: return "曝光过度";
            case EFFECT_MODE_SEPIA: return "怀旧";
            case EFFECT_MODE_AQUA: return "水蓝";
            default: return "未知";
        }
    }
    
    // ==================== 自定义车型自由操控配置相关方法 ====================
    
    /**
     * 设置自由操控开关
     * @param enabled true 表示启用自由操控
     */
    public void setCustomFreeControlEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_CUSTOM_FREE_CONTROL_ENABLED, enabled).apply();
        AppLog.d(TAG, "自由操控设置: " + (enabled ? "启用" : "禁用"));
    }
    
    /**
     * 获取自由操控开关状态
     * @return true 表示启用自由操控
     */
    public boolean isCustomFreeControlEnabled() {
        return prefs.getBoolean(KEY_CUSTOM_FREE_CONTROL_ENABLED, false);
    }
    
    /**
     * 设置按钮样式
     * @param style 按钮样式（BUTTON_STYLE_STANDARD / BUTTON_STYLE_MULTI）
     */
    public void setCustomButtonStyle(String style) {
        prefs.edit().putString(KEY_CUSTOM_BUTTON_STYLE, style).apply();
        AppLog.d(TAG, "按钮样式设置: " + style);
    }
    
    /**
     * 获取按钮样式
     * @return 按钮样式，默认为标准按钮
     */
    public String getCustomButtonStyle() {
        return prefs.getString(KEY_CUSTOM_BUTTON_STYLE, BUTTON_STYLE_STANDARD);
    }
    
    /**
     * 设置按钮布局方向
     * @param orientation 方向（BUTTON_ORIENTATION_HORIZONTAL / BUTTON_ORIENTATION_VERTICAL）
     */
    public void setCustomButtonOrientation(String orientation) {
        prefs.edit().putString(KEY_CUSTOM_BUTTON_ORIENTATION, orientation).apply();
        AppLog.d(TAG, "按钮布局方向设置: " + orientation);
    }
    
    /**
     * 获取按钮布局方向
     * @return 布局方向，默认为横版
     */
    public String getCustomButtonOrientation() {
        return prefs.getString(KEY_CUSTOM_BUTTON_ORIENTATION, BUTTON_ORIENTATION_HORIZONTAL);
    }
    
    /**
     * 保存自定义布局数据（JSON格式）
     * @param layoutDataJson 布局数据JSON字符串
     */
    public void setCustomLayoutData(String layoutDataJson) {
        prefs.edit().putString(KEY_CUSTOM_LAYOUT_DATA, layoutDataJson).apply();
        AppLog.d(TAG, "自定义布局数据已保存");
    }
    
    /**
     * 获取自定义布局数据
     * @return 布局数据JSON字符串，如果未设置返回null
     */
    public String getCustomLayoutData() {
        return prefs.getString(KEY_CUSTOM_LAYOUT_DATA, null);
    }
    
    /**
     * 清除自定义布局数据
     */
    public void clearCustomLayoutData() {
        prefs.edit().remove(KEY_CUSTOM_LAYOUT_DATA).apply();
        AppLog.d(TAG, "自定义布局数据已清除");
    }
    
    /**
     * 根据旋转角度计算实际显示比例
     * @param width 原始宽度
     * @param height 原始高度
     * @param rotation 旋转角度 (0/90/180/270)
     * @return [displayWidth, displayHeight]
     */
    public static int[] calculateDisplayRatio(int width, int height, int rotation) {
        if (rotation == 90 || rotation == 270) {
            // 旋转90°或270°时，宽高互换
            return new int[]{height, width};
        }
        return new int[]{width, height};
    }
    
    /**
     * 获取按钮样式的显示名称
     */
    public static String getButtonStyleDisplayName(String style) {
        if (BUTTON_STYLE_MULTI.equals(style)) {
            return "多按钮";
        }
        return "标准";
    }
    
    /**
     * 获取按钮方向的显示名称
     */
    public static String getButtonOrientationDisplayName(String orientation) {
        if (BUTTON_ORIENTATION_VERTICAL.equals(orientation)) {
            return "竖版";
        }
        return "横版";
    }
    
    // ==================== 版本更新配置相关方法 ====================
    
    /**
     * 设置更新服务器地址
     * @param url 服务器地址（如 https://example.com/update/）
     */
    public void setUpdateServerUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            prefs.edit().remove(KEY_UPDATE_SERVER_URL).apply();
            AppLog.d(TAG, "清除更新服务器地址");
        } else {
            prefs.edit().putString(KEY_UPDATE_SERVER_URL, url.trim()).apply();
            AppLog.d(TAG, "更新服务器地址设置: " + url.trim());
        }
    }
    
    /**
     * 获取更新服务器地址
     * @return 服务器地址，默认为官方更新服务器
     */
    public String getUpdateServerUrl() {
        String url = prefs.getString(KEY_UPDATE_SERVER_URL, DEFAULT_UPDATE_SERVER_URL);
        if (url == null || url.trim().isEmpty()) {
            return DEFAULT_UPDATE_SERVER_URL;
        }
        return url;
    }
    
    /**
     * 检查是否已配置更新服务器
     * @return true 表示已配置
     */
    public boolean hasUpdateServerUrl() {
        return getUpdateServerUrl() != null;
    }
}
