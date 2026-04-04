package com.kooo.evcam.config;

import android.content.SharedPreferences;

import com.kooo.evcam.AppLog;

/**
 * 补盲配置类
 * 管理补盲功能相关的配置参数
 */
public class BlindSpotConfig {
    private static final String TAG = "BlindSpotConfig";

    public static final String SIDE_LEFT = "left";
    public static final String SIDE_RIGHT = "right";

    // 默认值
    private static final float DEFAULT_LEFT_CROP_X = 0.0f;
    private static final float DEFAULT_LEFT_CROP_Y = 0.5f;
    private static final float DEFAULT_LEFT_CROP_W = 0.5f;
    private static final float DEFAULT_LEFT_CROP_H = 0.5f;

    private static final float DEFAULT_RIGHT_CROP_X = 0.5f;
    private static final float DEFAULT_RIGHT_CROP_Y = 0.5f;
    private static final float DEFAULT_RIGHT_CROP_W = 0.5f;
    private static final float DEFAULT_RIGHT_CROP_H = 0.5f;

    private static final float DEFAULT_FISHEYE_K1 = -0.35f;
    private static final float DEFAULT_FISHEYE_K2 = 0.12f;
    private static final float DEFAULT_FISHEYE_K3 = -0.03f;
    private static final float DEFAULT_FISHEYE_K4 = 0.005f;
    private static final float DEFAULT_FISHEYE_CENTER_X = 0.5f;
    private static final float DEFAULT_FISHEYE_CENTER_Y = 0.5f;
    private static final float DEFAULT_FISHEYE_STRENGTH = 1.0f;

    private static final int DEFAULT_WINDOW_WIDTH_DP = 950;
    private static final int DEFAULT_WINDOW_HEIGHT_DP = 550;
    private static final int DEFAULT_WINDOW_POS_X = 0;
    private static final int DEFAULT_WINDOW_POS_Y = 900;
    private static final int DEFAULT_OVERLAY_ROTATION_DEG = 0;
    private static final boolean DEFAULT_OVERLAY_ROUNDED = false;

    // SharedPreferences 键名
    private static final String KEY_ENABLED = "blind_spot_enabled";
    private static final String KEY_FISHEYE_ENABLED = "blind_spot_fisheye_enabled";

    private static final String KEY_LEFT_CROP_X = "blind_spot_left_crop_x";
    private static final String KEY_LEFT_CROP_Y = "blind_spot_left_crop_y";
    private static final String KEY_LEFT_CROP_W = "blind_spot_left_crop_w";
    private static final String KEY_LEFT_CROP_H = "blind_spot_left_crop_h";

    private static final String KEY_RIGHT_CROP_X = "blind_spot_right_crop_x";
    private static final String KEY_RIGHT_CROP_Y = "blind_spot_right_crop_y";
    private static final String KEY_RIGHT_CROP_W = "blind_spot_right_crop_w";
    private static final String KEY_RIGHT_CROP_H = "blind_spot_right_crop_h";

    private static final String KEY_LEFT_FISHEYE_K1 = "blind_spot_left_fisheye_k1";
    private static final String KEY_LEFT_FISHEYE_K2 = "blind_spot_left_fisheye_k2";
    private static final String KEY_LEFT_FISHEYE_K3 = "blind_spot_left_fisheye_k3";
    private static final String KEY_LEFT_FISHEYE_K4 = "blind_spot_left_fisheye_k4";
    private static final String KEY_LEFT_FISHEYE_CENTER_X = "blind_spot_left_fisheye_center_x";
    private static final String KEY_LEFT_FISHEYE_CENTER_Y = "blind_spot_left_fisheye_center_y";
    private static final String KEY_LEFT_FISHEYE_STRENGTH = "blind_spot_left_fisheye_strength";

    private static final String KEY_RIGHT_FISHEYE_K1 = "blind_spot_right_fisheye_k1";
    private static final String KEY_RIGHT_FISHEYE_K2 = "blind_spot_right_fisheye_k2";
    private static final String KEY_RIGHT_FISHEYE_K3 = "blind_spot_right_fisheye_k3";
    private static final String KEY_RIGHT_FISHEYE_K4 = "blind_spot_right_fisheye_k4";
    private static final String KEY_RIGHT_FISHEYE_CENTER_X = "blind_spot_right_fisheye_center_x";
    private static final String KEY_RIGHT_FISHEYE_CENTER_Y = "blind_spot_right_fisheye_center_y";
    private static final String KEY_RIGHT_FISHEYE_STRENGTH = "blind_spot_right_fisheye_strength";

    private static final String KEY_WINDOW_WIDTH = "blind_spot_window_width";
    private static final String KEY_WINDOW_HEIGHT = "blind_spot_window_height";
    private static final String KEY_WINDOW_X = "blind_spot_window_x";
    private static final String KEY_WINDOW_Y = "blind_spot_window_y";
    private static final String KEY_LEFT_OVERLAY_ROTATION = "blind_spot_left_overlay_rotation_deg";
    private static final String KEY_RIGHT_OVERLAY_ROTATION = "blind_spot_right_overlay_rotation_deg";
    private static final String KEY_OVERLAY_ROUNDED = "blind_spot_overlay_rounded";

    private final SharedPreferences prefs;

    public BlindSpotConfig(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    // ========== 功能开关 ==========

    public boolean isEnabled() {
        return prefs.getBoolean(KEY_ENABLED, true);
    }

    public void setEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply();
        AppLog.d(TAG, "补盲功能: " + (enabled ? "开启" : "关闭"));
    }

    public boolean isFisheyeEnabled() {
        return prefs.getBoolean(KEY_FISHEYE_ENABLED, false);
    }

    public void setFisheyeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_FISHEYE_ENABLED, enabled).apply();
        AppLog.d(TAG, "鱼眼畸变校准: " + (enabled ? "开启" : "关闭"));
    }

    // ========== 裁剪区域 ==========

    public float getLeftCropX() {
        return prefs.getFloat(KEY_LEFT_CROP_X, DEFAULT_LEFT_CROP_X);
    }

    public void setLeftCropX(float value) {
        prefs.edit().putFloat(KEY_LEFT_CROP_X, clamp(value, 0f, 1f)).apply();
    }

    public float getLeftCropY() {
        return prefs.getFloat(KEY_LEFT_CROP_Y, DEFAULT_LEFT_CROP_Y);
    }

    public void setLeftCropY(float value) {
        prefs.edit().putFloat(KEY_LEFT_CROP_Y, clamp(value, 0f, 1f)).apply();
    }

    public float getLeftCropW() {
        return prefs.getFloat(KEY_LEFT_CROP_W, DEFAULT_LEFT_CROP_W);
    }

    public void setLeftCropW(float value) {
        prefs.edit().putFloat(KEY_LEFT_CROP_W, clamp(value, 0.05f, 1f)).apply();
    }

    public float getLeftCropH() {
        return prefs.getFloat(KEY_LEFT_CROP_H, DEFAULT_LEFT_CROP_H);
    }

    public void setLeftCropH(float value) {
        prefs.edit().putFloat(KEY_LEFT_CROP_H, clamp(value, 0.05f, 1f)).apply();
    }

    public float getRightCropX() {
        return prefs.getFloat(KEY_RIGHT_CROP_X, DEFAULT_RIGHT_CROP_X);
    }

    public void setRightCropX(float value) {
        prefs.edit().putFloat(KEY_RIGHT_CROP_X, clamp(value, 0f, 1f)).apply();
    }

    public float getRightCropY() {
        return prefs.getFloat(KEY_RIGHT_CROP_Y, DEFAULT_RIGHT_CROP_Y);
    }

    public void setRightCropY(float value) {
        prefs.edit().putFloat(KEY_RIGHT_CROP_Y, clamp(value, 0f, 1f)).apply();
    }

    public float getRightCropW() {
        return prefs.getFloat(KEY_RIGHT_CROP_W, DEFAULT_RIGHT_CROP_W);
    }

    public void setRightCropW(float value) {
        prefs.edit().putFloat(KEY_RIGHT_CROP_W, clamp(value, 0.05f, 1f)).apply();
    }

    public float getRightCropH() {
        return prefs.getFloat(KEY_RIGHT_CROP_H, DEFAULT_RIGHT_CROP_H);
    }

    public void setRightCropH(float value) {
        prefs.edit().putFloat(KEY_RIGHT_CROP_H, clamp(value, 0.05f, 1f)).apply();
    }

    public float[] getCropRegion(String side) {
        if (SIDE_LEFT.equals(side)) {
            return new float[]{
                    getLeftCropX(),
                    getLeftCropY(),
                    getLeftCropW(),
                    getLeftCropH()
            };
        } else {
            return new float[]{
                    getRightCropX(),
                    getRightCropY(),
                    getRightCropW(),
                    getRightCropH()
            };
        }
    }

    public void setCropRegion(String side, float x, float y, float w, float h) {
        String prefix = SIDE_LEFT.equals(side) ? "blind_spot_left_crop_" : "blind_spot_right_crop_";
        prefs.edit()
                .putFloat(prefix + "x", clamp(x, 0f, 1f))
                .putFloat(prefix + "y", clamp(y, 0f, 1f))
                .putFloat(prefix + "w", clamp(w, 0.05f, 1f))
                .putFloat(prefix + "h", clamp(h, 0.05f, 1f))
                .apply();
        AppLog.d(TAG, String.format("%s 裁剪区域: x=%.2f, y=%.2f, w=%.2f, h=%.2f", side, x, y, w, h));
    }

    // ========== 鱼眼矫正参数 ==========

    public float getFisheyeK1(String side) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_K1 : KEY_RIGHT_FISHEYE_K1;
        return prefs.getFloat(key, DEFAULT_FISHEYE_K1);
    }

    public void setFisheyeK1(String side, float value) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_K1 : KEY_RIGHT_FISHEYE_K1;
        prefs.edit().putFloat(key, value).apply();
    }

    public float getFisheyeK2(String side) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_K2 : KEY_RIGHT_FISHEYE_K2;
        return prefs.getFloat(key, DEFAULT_FISHEYE_K2);
    }

    public void setFisheyeK2(String side, float value) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_K2 : KEY_RIGHT_FISHEYE_K2;
        prefs.edit().putFloat(key, value).apply();
    }

    public float getFisheyeK3(String side) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_K3 : KEY_RIGHT_FISHEYE_K3;
        return prefs.getFloat(key, DEFAULT_FISHEYE_K3);
    }

    public void setFisheyeK3(String side, float value) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_K3 : KEY_RIGHT_FISHEYE_K3;
        prefs.edit().putFloat(key, value).apply();
    }

    public float getFisheyeK4(String side) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_K4 : KEY_RIGHT_FISHEYE_K4;
        return prefs.getFloat(key, DEFAULT_FISHEYE_K4);
    }

    public void setFisheyeK4(String side, float value) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_K4 : KEY_RIGHT_FISHEYE_K4;
        prefs.edit().putFloat(key, value).apply();
    }

    public float getFisheyeCenterX(String side) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_CENTER_X : KEY_RIGHT_FISHEYE_CENTER_X;
        return prefs.getFloat(key, DEFAULT_FISHEYE_CENTER_X);
    }

    public void setFisheyeCenterX(String side, float value) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_CENTER_X : KEY_RIGHT_FISHEYE_CENTER_X;
        prefs.edit().putFloat(key, clamp(value, 0f, 1f)).apply();
    }

    public float getFisheyeCenterY(String side) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_CENTER_Y : KEY_RIGHT_FISHEYE_CENTER_Y;
        return prefs.getFloat(key, DEFAULT_FISHEYE_CENTER_Y);
    }

    public void setFisheyeCenterY(String side, float value) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_CENTER_Y : KEY_RIGHT_FISHEYE_CENTER_Y;
        prefs.edit().putFloat(key, clamp(value, 0f, 1f)).apply();
    }

    public float getFisheyeStrength(String side) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_STRENGTH : KEY_RIGHT_FISHEYE_STRENGTH;
        return prefs.getFloat(key, DEFAULT_FISHEYE_STRENGTH);
    }

    public void setFisheyeStrength(String side, float value) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_FISHEYE_STRENGTH : KEY_RIGHT_FISHEYE_STRENGTH;
        prefs.edit().putFloat(key, clamp(value, 0f, 1f)).apply();
    }

    public void setFisheyeParams(String side, float k1, float k2, float k3, float k4,
                                  float centerX, float centerY, float strength) {
        String prefix = SIDE_LEFT.equals(side) ? "blind_spot_left_fisheye_" : "blind_spot_right_fisheye_";
        prefs.edit()
                .putFloat(prefix + "k1", k1)
                .putFloat(prefix + "k2", k2)
                .putFloat(prefix + "k3", k3)
                .putFloat(prefix + "k4", k4)
                .putFloat(prefix + "center_x", clamp(centerX, 0f, 1f))
                .putFloat(prefix + "center_y", clamp(centerY, 0f, 1f))
                .putFloat(prefix + "strength", clamp(strength, 0f, 1f))
                .apply();
        AppLog.d(TAG, String.format("%s 鱼眼校准参数: k1=%.4f, k2=%.4f, k3=%.4f, k4=%.4f, center=(%.2f,%.2f), strength=%.2f",
                side, k1, k2, k3, k4, centerX, centerY, strength));
    }

    // ========== 悬浮窗配置 ==========

    public int getWindowWidthDp() {
        return prefs.getInt(KEY_WINDOW_WIDTH, DEFAULT_WINDOW_WIDTH_DP);
    }

    public void setWindowWidthDp(int width) {
        prefs.edit().putInt(KEY_WINDOW_WIDTH, Math.max(100, width)).apply();
    }

    public int getWindowHeightDp() {
        return prefs.getInt(KEY_WINDOW_HEIGHT, DEFAULT_WINDOW_HEIGHT_DP);
    }

    public void setWindowHeightDp(int height) {
        prefs.edit().putInt(KEY_WINDOW_HEIGHT, Math.max(60, height)).apply();
    }

    public int getWindowX() {
        return prefs.getInt(KEY_WINDOW_X, DEFAULT_WINDOW_POS_X);
    }

    public void setWindowX(int x) {
        prefs.edit().putInt(KEY_WINDOW_X, x).apply();
    }

    public int getWindowY() {
        return prefs.getInt(KEY_WINDOW_Y, DEFAULT_WINDOW_POS_Y);
    }

    public void setWindowY(int y) {
        prefs.edit().putInt(KEY_WINDOW_Y, y).apply();
    }

    public void setWindowPosition(int x, int y) {
        prefs.edit()
                .putInt(KEY_WINDOW_X, x)
                .putInt(KEY_WINDOW_Y, y)
                .apply();
    }

    public void setWindowSize(int width, int height) {
        prefs.edit()
                .putInt(KEY_WINDOW_WIDTH, Math.max(100, width))
                .putInt(KEY_WINDOW_HEIGHT, Math.max(60, height))
                .apply();
        AppLog.d(TAG, "悬浮窗大小: " + width + "x" + height + "dp");
    }

    // ========== 叠加层样式 ==========

    public int getOverlayRotationDegrees(String side) {
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_OVERLAY_ROTATION : KEY_RIGHT_OVERLAY_ROTATION;
        int rotation = prefs.getInt(key, DEFAULT_OVERLAY_ROTATION_DEG);
        return normalizeRotationDegrees(rotation);
    }

    public void setOverlayRotationDegrees(String side, int degrees) {
        degrees = normalizeRotationDegrees(degrees);
        String key = SIDE_LEFT.equals(side) ? KEY_LEFT_OVERLAY_ROTATION : KEY_RIGHT_OVERLAY_ROTATION;
        prefs.edit().putInt(key, degrees).apply();
    }

    public boolean isOverlayRoundedCornersEnabled() {
        return prefs.getBoolean(KEY_OVERLAY_ROUNDED, DEFAULT_OVERLAY_ROUNDED);
    }

    public void setOverlayRoundedCornersEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_OVERLAY_ROUNDED, enabled).apply();
    }

    // ========== 工具方法 ==========

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int normalizeRotationDegrees(int degrees) {
        if (degrees == 90 || degrees == 180 || degrees == 270) {
            return degrees;
        }
        return 0;
    }
}
