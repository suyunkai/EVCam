package com.kooo.evcam.camera;

import android.content.Context;
import android.util.Range;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

import java.util.ArrayList;
import java.util.List;

/**
 * 亮度/降噪调节管理器
 * 管理所有摄像头的亮度/降噪参数，协调多个摄像头的参数同步更新
 */
public class ImageAdjustManager {
    private static final String TAG = "ImageAdjustManager";
    
    private final Context context;
    private final AppConfig appConfig;
    private final List<SingleCamera> cameras = new ArrayList<>();
    
    // 当前参数值（内存缓存，用于实时调节时同步到所有摄像头）
    private int exposureCompensation = 0;
    private int awbMode = AppConfig.AWB_MODE_DEFAULT;
    private int tonemapMode = AppConfig.TONEMAP_MODE_DEFAULT;
    private int edgeMode = AppConfig.EDGE_MODE_DEFAULT;
    private int noiseReductionMode = AppConfig.NOISE_REDUCTION_DEFAULT;
    private int effectMode = AppConfig.EFFECT_MODE_DEFAULT;
    
    // 参数范围（从第一个摄像头获取，假设所有摄像头范围相同）
    private Range<Integer> exposureRange = null;
    private int[] supportedAwbModes = null;
    private int[] supportedTonemapModes = null;
    private int[] supportedEdgeModes = null;
    private int[] supportedNoiseReductionModes = null;
    private int[] supportedEffectModes = null;
    
    // 回调接口
    public interface OnParamsChangedListener {
        void onParamsChanged();
    }
    
    private OnParamsChangedListener listener;
    
    public ImageAdjustManager(Context context) {
        this.context = context;
        this.appConfig = new AppConfig(context);
        
        // 从配置中加载保存的参数
        loadParamsFromConfig();
    }
    
    /**
     * 设置参数变化监听器
     */
    public void setOnParamsChangedListener(OnParamsChangedListener listener) {
        this.listener = listener;
    }
    
    /**
     * 从配置中加载参数
     */
    private void loadParamsFromConfig() {
        exposureCompensation = appConfig.getExposureCompensation();
        awbMode = appConfig.getAwbMode();
        tonemapMode = appConfig.getTonemapMode();
        edgeMode = appConfig.getEdgeMode();
        noiseReductionMode = appConfig.getNoiseReductionMode();
        effectMode = appConfig.getEffectMode();
        
        AppLog.d(TAG, "Loaded params from config: exposure=" + exposureCompensation + 
                ", awb=" + awbMode + ", tonemap=" + tonemapMode);
    }
    
    /**
     * 保存参数到配置
     */
    public void saveParamsToConfig() {
        appConfig.setExposureCompensation(exposureCompensation);
        appConfig.setAwbMode(awbMode);
        appConfig.setTonemapMode(tonemapMode);
        appConfig.setEdgeMode(edgeMode);
        appConfig.setNoiseReductionMode(noiseReductionMode);
        appConfig.setEffectMode(effectMode);
        
        AppLog.d(TAG, "Saved params to config");
    }
    
    /**
     * 注册摄像头
     * @param camera 要注册的摄像头
     */
    public void registerCamera(SingleCamera camera) {
        if (camera != null && !cameras.contains(camera)) {
            cameras.add(camera);
            
            // 如果启用了亮度/降噪调节，设置摄像头的启用状态
            if (appConfig.isImageAdjustEnabled()) {
                camera.setImageAdjustEnabled(true);
            }
            
            // 从第一个摄像头获取参数范围
            if (cameras.size() == 1) {
                detectSupportedParams(camera);
            }
            
            AppLog.d(TAG, "Registered camera: " + camera.getCameraId() + ", total: " + cameras.size());
        }
    }
    
    /**
     * 注销摄像头
     * @param camera 要注销的摄像头
     */
    public void unregisterCamera(SingleCamera camera) {
        if (camera != null) {
            cameras.remove(camera);
            AppLog.d(TAG, "Unregistered camera: " + camera.getCameraId() + ", remaining: " + cameras.size());
        }
    }
    
    /**
     * 清空所有已注册的摄像头
     */
    public void clearCameras() {
        cameras.clear();
        AppLog.d(TAG, "Cleared all cameras");
    }
    
    /**
     * 检测设备支持的参数范围
     */
    private void detectSupportedParams(SingleCamera camera) {
        exposureRange = camera.getExposureCompensationRange();
        supportedAwbModes = camera.getSupportedAwbModes();
        supportedTonemapModes = camera.getSupportedTonemapModes();
        supportedEdgeModes = camera.getSupportedEdgeModes();
        supportedNoiseReductionModes = camera.getSupportedNoiseReductionModes();
        supportedEffectModes = camera.getSupportedEffectModes();
        
        AppLog.d(TAG, "Detected supported params:");
        AppLog.d(TAG, "  Exposure range: " + (exposureRange != null ? exposureRange.toString() : "null"));
        AppLog.d(TAG, "  AWB modes: " + (supportedAwbModes != null ? supportedAwbModes.length : 0));
        AppLog.d(TAG, "  Tonemap modes: " + (supportedTonemapModes != null ? supportedTonemapModes.length : 0));
        AppLog.d(TAG, "  Edge modes: " + (supportedEdgeModes != null ? supportedEdgeModes.length : 0));
        AppLog.d(TAG, "  Noise reduction modes: " + (supportedNoiseReductionModes != null ? supportedNoiseReductionModes.length : 0));
        AppLog.d(TAG, "  Effect modes: " + (supportedEffectModes != null ? supportedEffectModes.length : 0));
    }
    
    /**
     * 更新所有摄像头的参数（实时生效）
     * @return 成功更新的摄像头数量
     */
    public int updateAllCameras() {
        if (!appConfig.isImageAdjustEnabled()) {
            AppLog.d(TAG, "Image adjust not enabled, skip update");
            return 0;
        }
        
        int successCount = 0;
        for (SingleCamera camera : cameras) {
            boolean success = camera.updateImageAdjustParams(
                exposureCompensation,
                awbMode,
                tonemapMode,
                edgeMode,
                noiseReductionMode,
                effectMode
            );
            if (success) {
                successCount++;
            }
        }
        
        AppLog.d(TAG, "Updated " + successCount + "/" + cameras.size() + " cameras");
        
        // 通知监听器
        if (listener != null) {
            listener.onParamsChanged();
        }
        
        return successCount;
    }
    
    /**
     * 重置所有参数为默认值
     */
    public void resetToDefault() {
        exposureCompensation = 0;
        awbMode = AppConfig.AWB_MODE_DEFAULT;
        tonemapMode = AppConfig.TONEMAP_MODE_DEFAULT;
        edgeMode = AppConfig.EDGE_MODE_DEFAULT;
        noiseReductionMode = AppConfig.NOISE_REDUCTION_DEFAULT;
        effectMode = AppConfig.EFFECT_MODE_DEFAULT;
        
        // 保存到配置
        appConfig.resetImageAdjustParams();
        
        // 更新所有摄像头
        updateAllCameras();
        
        AppLog.d(TAG, "Reset all params to default");
    }
    
    // ==================== Getter/Setter 方法 ====================
    
    public int getExposureCompensation() {
        return exposureCompensation;
    }
    
    public void setExposureCompensation(int value) {
        if (exposureRange != null) {
            this.exposureCompensation = Math.max(exposureRange.getLower(), 
                    Math.min(value, exposureRange.getUpper()));
        } else {
            this.exposureCompensation = value;
        }
    }
    
    public int getAwbMode() {
        return awbMode;
    }
    
    public void setAwbMode(int mode) {
        this.awbMode = mode;
    }
    
    public int getTonemapMode() {
        return tonemapMode;
    }
    
    public void setTonemapMode(int mode) {
        this.tonemapMode = mode;
    }
    
    public int getEdgeMode() {
        return edgeMode;
    }
    
    public void setEdgeMode(int mode) {
        this.edgeMode = mode;
    }
    
    public int getNoiseReductionMode() {
        return noiseReductionMode;
    }
    
    public void setNoiseReductionMode(int mode) {
        this.noiseReductionMode = mode;
    }
    
    public int getEffectMode() {
        return effectMode;
    }
    
    public void setEffectMode(int mode) {
        this.effectMode = mode;
    }
    
    // ==================== 参数范围 Getter ====================
    
    public Range<Integer> getExposureRange() {
        return exposureRange;
    }
    
    public int[] getSupportedAwbModes() {
        return supportedAwbModes;
    }
    
    public int[] getSupportedTonemapModes() {
        return supportedTonemapModes;
    }
    
    public int[] getSupportedEdgeModes() {
        return supportedEdgeModes;
    }
    
    public int[] getSupportedNoiseReductionModes() {
        return supportedNoiseReductionModes;
    }
    
    public int[] getSupportedEffectModes() {
        return supportedEffectModes;
    }
    
    /**
     * 检查是否支持曝光补偿调节
     */
    public boolean isExposureCompensationSupported() {
        return exposureRange != null && !exposureRange.getLower().equals(exposureRange.getUpper());
    }
    
    /**
     * 检查是否支持白平衡模式调节
     */
    public boolean isAwbModeSupported() {
        return supportedAwbModes != null && supportedAwbModes.length > 1;
    }
    
    /**
     * 检查是否支持色调映射模式调节
     */
    public boolean isTonemapModeSupported() {
        return supportedTonemapModes != null && supportedTonemapModes.length > 1;
    }
    
    /**
     * 检查是否支持边缘增强模式调节
     */
    public boolean isEdgeModeSupported() {
        return supportedEdgeModes != null && supportedEdgeModes.length > 1;
    }
    
    /**
     * 检查是否支持降噪模式调节
     */
    public boolean isNoiseReductionModeSupported() {
        return supportedNoiseReductionModes != null && supportedNoiseReductionModes.length > 1;
    }
    
    /**
     * 检查是否支持特效模式调节
     */
    public boolean isEffectModeSupported() {
        return supportedEffectModes != null && supportedEffectModes.length > 1;
    }
    
    /**
     * 获取当前参数的摘要字符串（用于显示）
     */
    public String getParamsSummary() {
        StringBuilder sb = new StringBuilder();
        
        if (exposureCompensation != 0) {
            sb.append("曝光: ").append(exposureCompensation > 0 ? "+" : "").append(exposureCompensation);
        }
        
        if (awbMode != AppConfig.AWB_MODE_DEFAULT) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("白平衡: ").append(AppConfig.getAwbModeDisplayName(awbMode));
        }
        
        if (tonemapMode != AppConfig.TONEMAP_MODE_DEFAULT) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("色调: ").append(AppConfig.getTonemapModeDisplayName(tonemapMode));
        }
        
        if (edgeMode != AppConfig.EDGE_MODE_DEFAULT) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("锐化: ").append(AppConfig.getEdgeModeDisplayName(edgeMode));
        }
        
        if (noiseReductionMode != AppConfig.NOISE_REDUCTION_DEFAULT) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("降噪: ").append(AppConfig.getNoiseReductionModeDisplayName(noiseReductionMode));
        }
        
        if (effectMode != AppConfig.EFFECT_MODE_DEFAULT && effectMode != AppConfig.EFFECT_MODE_OFF) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append("特效: ").append(AppConfig.getEffectModeDisplayName(effectMode));
        }
        
        if (sb.length() == 0) {
            return "默认参数";
        }
        
        return sb.toString();
    }
    
    // ==================== 获取相机实际使用的参数 ====================
    
    /**
     * 获取相机实际使用的曝光补偿值（从第一个已注册的相机获取）
     */
    public int getActualExposureCompensation() {
        if (!cameras.isEmpty()) {
            return cameras.get(0).getActualExposureCompensation();
        }
        return 0;
    }
    
    /**
     * 获取相机实际使用的白平衡模式
     */
    public int getActualAwbMode() {
        if (!cameras.isEmpty()) {
            return cameras.get(0).getActualAwbMode();
        }
        return 1; // AUTO
    }
    
    /**
     * 获取相机实际使用的色调映射模式
     */
    public int getActualTonemapMode() {
        if (!cameras.isEmpty()) {
            return cameras.get(0).getActualTonemapMode();
        }
        return 1; // FAST
    }
    
    /**
     * 获取相机实际使用的边缘增强模式
     */
    public int getActualEdgeMode() {
        if (!cameras.isEmpty()) {
            return cameras.get(0).getActualEdgeMode();
        }
        return 0; // OFF
    }
    
    /**
     * 获取相机实际使用的降噪模式
     */
    public int getActualNoiseReductionMode() {
        if (!cameras.isEmpty()) {
            return cameras.get(0).getActualNoiseReductionMode();
        }
        return 0; // OFF
    }
    
    /**
     * 获取相机实际使用的特效模式
     */
    public int getActualEffectMode() {
        if (!cameras.isEmpty()) {
            return cameras.get(0).getActualEffectMode();
        }
        return 0; // OFF
    }
    
    /**
     * 是否已获取到相机实际参数
     */
    public boolean hasActualParams() {
        if (!cameras.isEmpty()) {
            return cameras.get(0).hasActualParams();
        }
        return false;
    }
}
