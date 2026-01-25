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
    private static final String KEY_AUTO_START_ON_BOOT = "auto_start_on_boot";  // 开机自启动
    private static final String KEY_KEEP_ALIVE_ENABLED = "keep_alive_enabled";  // 保活服务
    
    // 车型配置相关键名
    private static final String KEY_CAR_MODEL = "car_model";  // 车型（galaxy_e5 / custom）
    private static final String KEY_CAMERA_COUNT = "camera_count";  // 摄像头数量（4/2/1）
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
    
    // 车型常量
    public static final String CAR_MODEL_GALAXY_E5 = "galaxy_e5";  // 银河E5
    public static final String CAR_MODEL_CUSTOM = "custom";  // 自定义车型
    
    private final SharedPreferences prefs;
    
    public AppConfig(Context context) {
        prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
    }
    
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
     * 重置所有配置为默认值
     */
    public void resetToDefault() {
        prefs.edit().clear().apply();
        AppLog.d(TAG, "配置已重置为默认值");
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
     * @return 摄像头数量，默认为4
     */
    public int getCameraCount() {
        return prefs.getInt(KEY_CAMERA_COUNT, 4);
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
     * @param position 位置（front/back/left/right）
     * @return 摄像头名称
     */
    public String getCameraName(String position) {
        String key;
        String defaultValue;
        switch (position) {
            case "front":
                key = KEY_CAMERA_FRONT_NAME;
                defaultValue = "前";
                break;
            case "back":
                key = KEY_CAMERA_BACK_NAME;
                defaultValue = "后";
                break;
            case "left":
                key = KEY_CAMERA_LEFT_NAME;
                defaultValue = "左";
                break;
            case "right":
                key = KEY_CAMERA_RIGHT_NAME;
                defaultValue = "右";
                break;
            default:
                return "未知";
        }
        return prefs.getString(key, defaultValue);
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
}
