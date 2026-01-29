package com.kooo.evcam;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.util.Range;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.camera.ImageAdjustManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 画质设置界面 Fragment（分辨率、码率）
 */
public class ResolutionSettingsFragment extends Fragment {

    private static final String TAG = "ResolutionSettings";

    private AppConfig appConfig;
    
    // 分辨率相关
    private Spinner resolutionSpinner;
    private TextView resolutionDescText;
    private List<String> resolutionOptions = new ArrayList<>();
    private String selectedResolution;
    
    // 码率相关
    private Spinner bitrateSpinner;
    private TextView bitrateDescText;
    private List<String> bitrateOptions = new ArrayList<>();
    private String selectedBitrateLevel;
    
    // 帧率相关
    private Spinner framerateSpinner;
    private TextView framerateDescText;
    private List<String> framerateOptions = new ArrayList<>();
    private String selectedFramerateLevel;
    
    // 信息显示
    private TextView currentParamsText;
    private TextView hardwareInfoText;
    
    // 摄像头信息
    private Map<String, CameraInfo> cameraInfoMap = new LinkedHashMap<>();
    
    // 是否正在初始化
    private boolean isInitializing = false;
    
    // 亮度/降噪调节相关
    private Button openAdjustWindowButton;
    private Button resetAdjustButton;
    private TextView imageAdjustCurrentText;
    private TextView imageAdjustSupportedText;
    private ImageAdjustManager imageAdjustManager;

    /**
     * 摄像头信息类
     */
    private static class CameraInfo {
        String cameraId;
        List<Size> supportedResolutions = new ArrayList<>();
        int maxFps = 30;  // 最大帧率
        int minFps = 15;  // 最小帧率
        String facing = "未知";  // 朝向
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_resolution_settings, container, false);

        // 初始化应用配置
        if (getContext() != null) {
            appConfig = new AppConfig(getContext());
        }

        // 初始化控件
        initViews(view);

        // 检测摄像头信息
        detectCameraInfo();

        // 初始化分辨率选择器
        initResolutionSpinner();
        
        // 初始化帧率选择器（必须在码率之前，因为码率计算依赖帧率）
        initFramerateSpinner();
        
        // 初始化码率选择器
        initBitrateSpinner();
        
        // 初始化亮度/降噪调节
        initImageAdjust();

        // 显示调试信息
        displayDebugInfo();

        // 设置返回按钮
        Button btnBack = view.findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().getSupportFragmentManager().popBackStack();
            }
        });

        // 沉浸式状态栏兼容
        View toolbar = view.findViewById(R.id.toolbar);
        if (toolbar != null) {
            final int originalPaddingTop = toolbar.getPaddingTop();
            androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(toolbar, (v, insets) -> {
                int statusBarHeight = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.statusBars()).top;
                v.setPadding(v.getPaddingLeft(), statusBarHeight + originalPaddingTop, v.getPaddingRight(), v.getPaddingBottom());
                return insets;
            });
            androidx.core.view.ViewCompat.requestApplyInsets(toolbar);
        }

        return view;
    }

    private void initViews(View view) {
        resolutionSpinner = view.findViewById(R.id.spinner_resolution);
        resolutionDescText = view.findViewById(R.id.tv_resolution_desc);
        bitrateSpinner = view.findViewById(R.id.spinner_bitrate);
        bitrateDescText = view.findViewById(R.id.tv_bitrate_desc);
        framerateSpinner = view.findViewById(R.id.spinner_framerate);
        framerateDescText = view.findViewById(R.id.tv_framerate_desc);
        currentParamsText = view.findViewById(R.id.tv_current_params);
        hardwareInfoText = view.findViewById(R.id.tv_hardware_info);
        
        // 亮度/降噪调节控件
        openAdjustWindowButton = view.findViewById(R.id.btn_open_adjust_window);
        resetAdjustButton = view.findViewById(R.id.btn_reset_adjust);
        imageAdjustCurrentText = view.findViewById(R.id.tv_image_adjust_current);
        imageAdjustSupportedText = view.findViewById(R.id.tv_image_adjust_supported);
    }

    /**
     * 检测所有摄像头信息（分辨率和帧率）
     */
    private void detectCameraInfo() {
        if (getContext() == null) {
            return;
        }

        cameraInfoMap.clear();

        try {
            CameraManager cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = cameraManager.getCameraIdList();

            for (String cameraId : cameraIds) {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    CameraInfo info = new CameraInfo();
                    info.cameraId = cameraId;
                    
                    // 获取摄像头朝向
                    Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                    if (facing != null) {
                        switch (facing) {
                            case CameraCharacteristics.LENS_FACING_FRONT:
                                info.facing = "前置";
                                break;
                            case CameraCharacteristics.LENS_FACING_BACK:
                                info.facing = "后置";
                                break;
                            case CameraCharacteristics.LENS_FACING_EXTERNAL:
                                info.facing = "外置";
                                break;
                        }
                    }
                    
                    // 获取支持的帧率范围
                    Range<Integer>[] fpsRanges = characteristics.get(
                            CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                    if (fpsRanges != null && fpsRanges.length > 0) {
                        int maxFps = 0;
                        int minFps = Integer.MAX_VALUE;
                        for (Range<Integer> range : fpsRanges) {
                            if (range.getUpper() > maxFps) {
                                maxFps = range.getUpper();
                            }
                            if (range.getLower() < minFps) {
                                minFps = range.getLower();
                            }
                        }
                        info.maxFps = maxFps;
                        info.minFps = minFps;
                    }

                    // 获取支持的分辨率
                    StreamConfigurationMap map = characteristics.get(
                            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                    if (map != null) {
                        Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE);
                        if (sizes == null || sizes.length == 0) {
                            sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                        }

                        if (sizes != null && sizes.length > 0) {
                            for (Size size : sizes) {
                                info.supportedResolutions.add(size);
                            }
                            // 按分辨率从大到小排序
                            Collections.sort(info.supportedResolutions, (s1, s2) -> {
                                int pixels1 = s1.getWidth() * s1.getHeight();
                                int pixels2 = s2.getWidth() * s2.getHeight();
                                return pixels2 - pixels1;
                            });
                        }
                    }
                    
                    cameraInfoMap.put(cameraId, info);
                    
                } catch (CameraAccessException e) {
                    AppLog.e(TAG, "获取摄像头 " + cameraId + " 特性失败", e);
                }
            }

            AppLog.d(TAG, "检测到 " + cameraInfoMap.size() + " 个摄像头");

        } catch (CameraAccessException e) {
            AppLog.e(TAG, "获取摄像头列表失败", e);
        }
    }

    /**
     * 初始化分辨率选择器
     */
    private void initResolutionSpinner() {
        if (resolutionSpinner == null || getContext() == null) {
            return;
        }

        isInitializing = true;

        // 构建分辨率选项列表
        resolutionOptions.clear();
        resolutionOptions.add("默认 (1280×800)");

        // 收集所有摄像头支持的分辨率（去重）
        Set<String> allResolutions = new LinkedHashSet<>();
        for (CameraInfo info : cameraInfoMap.values()) {
            for (Size size : info.supportedResolutions) {
                allResolutions.add(size.getWidth() + "x" + size.getHeight());
            }
        }

        // 按像素数从大到小排序
        List<String> sortedResolutions = new ArrayList<>(allResolutions);
        Collections.sort(sortedResolutions, (r1, r2) -> {
            int[] p1 = AppConfig.parseResolution(r1);
            int[] p2 = AppConfig.parseResolution(r2);
            if (p1 == null || p2 == null) return 0;
            return (p2[0] * p2[1]) - (p1[0] * p1[1]);
        });

        resolutionOptions.addAll(sortedResolutions);

        // 设置适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                resolutionOptions
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);

        // 设置当前选中项
        String currentResolution = (appConfig != null) ? appConfig.getTargetResolution() : AppConfig.RESOLUTION_DEFAULT;
        selectedResolution = currentResolution;
        int selectedIndex = 0;
        if (!AppConfig.RESOLUTION_DEFAULT.equals(currentResolution)) {
            for (int i = 1; i < resolutionOptions.size(); i++) {
                if (resolutionOptions.get(i).equals(currentResolution)) {
                    selectedIndex = i;
                    break;
                }
            }
        }
        resolutionSpinner.setSelection(selectedIndex);

        // 设置选择监听器
        resolutionSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializing) {
                    return;
                }

                String newResolution;
                if (position == 0) {
                    newResolution = AppConfig.RESOLUTION_DEFAULT;
                    resolutionDescText.setText("默认：优先匹配 1280×800，否则选择最接近的分辨率");
                } else {
                    newResolution = resolutionOptions.get(position);
                    resolutionDescText.setText("将优先匹配 " + newResolution + "，如果摄像头不支持则选择最接近的");
                }
                
                // 只在值变化时保存
                if (!newResolution.equals(selectedResolution)) {
                    selectedResolution = newResolution;
                    saveResolution();
                }
                
                // 更新码率描述（因为分辨率变化会影响推荐码率）
                updateBitrateDescription();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        resolutionSpinner.post(() -> isInitializing = false);
    }

    /**
     * 初始化码率选择器
     */
    private void initBitrateSpinner() {
        if (bitrateSpinner == null || getContext() == null) {
            return;
        }

        // 构建码率选项
        bitrateOptions.clear();
        bitrateOptions.add("低（省空间）");
        bitrateOptions.add("标准（推荐）");
        bitrateOptions.add("高（高画质）");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                bitrateOptions
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        bitrateSpinner.setAdapter(adapter);

        // 设置当前选中项
        String currentLevel = appConfig.getBitrateLevel();
        selectedBitrateLevel = currentLevel;
        int selectedIndex = 1;  // 默认标准
        if (AppConfig.BITRATE_LOW.equals(currentLevel)) {
            selectedIndex = 0;
        } else if (AppConfig.BITRATE_HIGH.equals(currentLevel)) {
            selectedIndex = 2;
        }
        bitrateSpinner.setSelection(selectedIndex);

        // 设置选择监听器
        bitrateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean isFirstSelection = true;
            
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newLevel;
                switch (position) {
                    case 0:
                        newLevel = AppConfig.BITRATE_LOW;
                        break;
                    case 2:
                        newLevel = AppConfig.BITRATE_HIGH;
                        break;
                    default:
                        newLevel = AppConfig.BITRATE_MEDIUM;
                        break;
                }
                
                // 只在值变化且非首次选择时保存
                if (!isFirstSelection && !newLevel.equals(selectedBitrateLevel)) {
                    selectedBitrateLevel = newLevel;
                    saveBitrate();
                } else {
                    selectedBitrateLevel = newLevel;
                }
                isFirstSelection = false;
                
                updateBitrateDescription();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // 初始化描述
        updateBitrateDescription();
    }

    /**
     * 初始化帧率选择器
     */
    private void initFramerateSpinner() {
        if (framerateSpinner == null || getContext() == null) {
            return;
        }

        // 构建帧率选项
        framerateOptions.clear();
        framerateOptions.add("标准（推荐）");
        framerateOptions.add("低（省空间）");

        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                framerateOptions
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        framerateSpinner.setAdapter(adapter);

        // 设置当前选中项
        String currentLevel = appConfig.getFramerateLevel();
        selectedFramerateLevel = currentLevel;
        int selectedIndex = 0;  // 默认标准
        if (AppConfig.FRAMERATE_LOW.equals(currentLevel)) {
            selectedIndex = 1;
        }
        framerateSpinner.setSelection(selectedIndex);

        // 设置选择监听器
        framerateSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean isFirstSelection = true;
            
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newLevel = (position == 1) ? AppConfig.FRAMERATE_LOW : AppConfig.FRAMERATE_STANDARD;
                
                // 只在值变化且非首次选择时保存
                if (!isFirstSelection && !newLevel.equals(selectedFramerateLevel)) {
                    selectedFramerateLevel = newLevel;
                    saveFramerate();
                } else {
                    selectedFramerateLevel = newLevel;
                }
                isFirstSelection = false;
                
                updateFramerateDescription();
                updateBitrateDescription();  // 帧率变化会影响码率计算
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // 初始化描述
        updateFramerateDescription();
    }

    /**
     * 更新帧率描述
     */
    private void updateFramerateDescription() {
        if (framerateDescText == null) {
            return;
        }

        int standardFps = getStandardFrameRate();
        int lowFps = Math.max(10, standardFps / 2);

        String desc = String.format("标准: %dfps | 低: %dfps", standardFps, lowFps);
        framerateDescText.setText(desc);
    }

    /**
     * 获取标准帧率（接近30fps的硬件支持帧率）
     */
    private int getStandardFrameRate() {
        int maxFps = 30;
        for (CameraInfo info : cameraInfoMap.values()) {
            if (info.maxFps > 0) {
                maxFps = info.maxFps;
                break;  // 假设所有摄像头帧率相同
            }
        }
        return AppConfig.getStandardFrameRate(maxFps);
    }

    /**
     * 根据当前选择获取实际帧率
     */
    private int getSelectedFrameRate() {
        int standardFps = getStandardFrameRate();
        // 防止初始化顺序导致的 null 问题
        if (selectedFramerateLevel != null && AppConfig.FRAMERATE_LOW.equals(selectedFramerateLevel)) {
            return Math.max(10, standardFps / 2);
        }
        return standardFps;
    }

    /**
     * 更新码率描述（显示计算出的码率值）
     */
    private void updateBitrateDescription() {
        if (bitrateDescText == null) {
            return;
        }

        // 获取目标分辨率
        int width = 1280;
        int height = 800;
        if (!AppConfig.RESOLUTION_DEFAULT.equals(selectedResolution)) {
            int[] parsed = AppConfig.parseResolution(selectedResolution);
            if (parsed != null) {
                width = parsed[0];
                height = parsed[1];
            }
        }

        // 获取帧率（根据用户选择的帧率等级）
        int frameRate = getSelectedFrameRate();

        // 计算各等级码率
        int baseBitrate = AppConfig.calculateBitrate(width, height, frameRate);
        
        int lowBitrate = roundToHalfMbps(baseBitrate / 2);
        int mediumBitrate = roundToHalfMbps(baseBitrate);
        int highBitrate = roundToHalfMbps(baseBitrate * 3 / 2);

        String desc = String.format(
                "分辨率 %dx%d @ %dfps\n低: %s | 标准: %s | 高: %s",
                width, height, frameRate,
                AppConfig.formatBitrate(lowBitrate),
                AppConfig.formatBitrate(mediumBitrate),
                AppConfig.formatBitrate(highBitrate)
        );
        bitrateDescText.setText(desc);
    }
    
    /**
     * 四舍五入到 0.5Mbps
     */
    private int roundToHalfMbps(int bitrate) {
        int halfMbps = 500000;
        int rounded = ((bitrate + halfMbps / 2) / halfMbps) * halfMbps;
        return Math.max(halfMbps, Math.min(rounded, 20000000));
    }

    /**
     * 显示调试信息
     */
    private void displayDebugInfo() {
        displayCurrentParams();
        displayHardwareInfo();
    }

    /**
     * 显示当前录制参数
     */
    private void displayCurrentParams() {
        if (currentParamsText == null || getActivity() == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        
        // 从 MainActivity 获取当前分辨率信息
        if (getActivity() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getActivity();
            String resInfo = mainActivity.getCurrentCameraResolutionsInfo();
            if (resInfo != null && !resInfo.isEmpty()) {
                sb.append("【实际分辨率】\n").append(resInfo).append("\n\n");
            }
        }
        
        // 当前配置
        String targetRes = appConfig.getTargetResolution();
        String bitrateLevel = AppConfig.getBitrateLevelDisplayName(appConfig.getBitrateLevel());
        String framerateLevel = AppConfig.getFramerateLevelDisplayName(appConfig.getFramerateLevel());
        int standardFps = getStandardFrameRate();
        int actualFps = appConfig.getActualFrameRate(standardFps);
        
        sb.append("【当前配置】\n");
        sb.append("目标分辨率: ").append(AppConfig.RESOLUTION_DEFAULT.equals(targetRes) ? "默认 (1280×800)" : targetRes).append("\n");
        sb.append("码率等级: ").append(bitrateLevel).append("\n");
        sb.append("帧率等级: ").append(framerateLevel).append(" (").append(actualFps).append("fps)");

        currentParamsText.setText(sb.toString());
    }

    /**
     * 显示硬件信息
     */
    private void displayHardwareInfo() {
        if (hardwareInfoText == null) {
            return;
        }

        if (cameraInfoMap.isEmpty()) {
            hardwareInfoText.setText("未检测到摄像头");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, CameraInfo> entry : cameraInfoMap.entrySet()) {
            String cameraId = entry.getKey();
            CameraInfo info = entry.getValue();

            sb.append("摄像头 ").append(cameraId).append(" (").append(info.facing).append(")\n");
            sb.append("  帧率: ").append(info.minFps).append("-").append(info.maxFps).append(" fps\n");
            sb.append("  分辨率:\n");
            
            int count = 0;
            for (Size size : info.supportedResolutions) {
                sb.append("    ").append(size.getWidth()).append("×").append(size.getHeight());
                count++;
                if (count >= 5) {
                    sb.append("\n    ... 共 ").append(info.supportedResolutions.size()).append(" 种");
                    break;
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        hardwareInfoText.setText(sb.toString().trim());
    }

    /**
     * 保存分辨率设置
     */
    private void saveResolution() {
        if (appConfig == null || getContext() == null) {
            return;
        }
        
        String oldResolution = appConfig.getTargetResolution();
        appConfig.setTargetResolution(selectedResolution);
        
        String resolutionName = AppConfig.RESOLUTION_DEFAULT.equals(selectedResolution) 
                ? "默认 (1280×800)" 
                : selectedResolution;
        
        Toast.makeText(getContext(), "分辨率已设置为: " + resolutionName + "\n重启应用后生效", Toast.LENGTH_SHORT).show();
        AppLog.d(TAG, "分辨率已保存: " + oldResolution + " -> " + selectedResolution);
        
        // 更新当前参数显示
        displayCurrentParams();
    }
    
    /**
     * 保存码率设置
     */
    private void saveBitrate() {
        if (appConfig == null || getContext() == null) {
            return;
        }
        
        String oldBitrate = appConfig.getBitrateLevel();
        appConfig.setBitrateLevel(selectedBitrateLevel);
        
        String bitrateName = AppConfig.getBitrateLevelDisplayName(selectedBitrateLevel);
        
        Toast.makeText(getContext(), "码率已设置为: " + bitrateName + "\n重启应用后生效", Toast.LENGTH_SHORT).show();
        AppLog.d(TAG, "码率已保存: " + oldBitrate + " -> " + selectedBitrateLevel);
        
        // 更新当前参数显示
        displayCurrentParams();
    }
    
    /**
     * 保存帧率设置
     */
    private void saveFramerate() {
        if (appConfig == null || getContext() == null) {
            return;
        }
        
        String oldFramerate = appConfig.getFramerateLevel();
        appConfig.setFramerateLevel(selectedFramerateLevel);
        
        String framerateName = AppConfig.getFramerateLevelDisplayName(selectedFramerateLevel);
        
        Toast.makeText(getContext(), "帧率已设置为: " + framerateName + "\n重启应用后生效", Toast.LENGTH_SHORT).show();
        AppLog.d(TAG, "帧率已保存: " + oldFramerate + " -> " + selectedFramerateLevel);
        
        // 更新当前参数显示
        displayCurrentParams();
    }
    
    // ==================== 亮度/降噪调节 ====================
    
    /**
     * 初始化亮度/降噪调节功能
     */
    private void initImageAdjust() {
        if (getContext() == null) {
            return;
        }
        
        // 初始化 ImageAdjustManager
        imageAdjustManager = getImageAdjustManager();
        
        // 确保亮度/降噪调节始终启用
        if (!appConfig.isImageAdjustEnabled()) {
            appConfig.setImageAdjustEnabled(true);
            // 通知 MainActivity 启用亮度/降噪调节
            if (getActivity() instanceof MainActivity) {
                MainActivity mainActivity = (MainActivity) getActivity();
                mainActivity.setImageAdjustEnabled(true);
            }
        }
        
        // 打开调节悬浮窗按钮
        if (openAdjustWindowButton != null) {
            openAdjustWindowButton.setOnClickListener(v -> openAdjustFloatingWindow());
        }
        
        // 恢复默认按钮
        if (resetAdjustButton != null) {
            resetAdjustButton.setOnClickListener(v -> {
                if (imageAdjustManager != null) {
                    imageAdjustManager.resetToDefault();
                    updateImageAdjustParamsDisplay();
                    Toast.makeText(getContext(), "亮度/降噪参数已恢复默认", Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        // 更新参数显示
        updateImageAdjustParamsDisplay();
    }
    
    /**
     * 获取 ImageAdjustManager 实例（从 MainActivity 获取）
     */
    private ImageAdjustManager getImageAdjustManager() {
        if (getActivity() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getActivity();
            return mainActivity.getImageAdjustManager();
        }
        return null;
    }
    
    /**
     * 更新亮度/降噪参数显示
     */
    private void updateImageAdjustParamsDisplay() {
        // 更新当前设置显示
        if (imageAdjustCurrentText != null) {
            imageAdjustCurrentText.setText(buildCurrentParamsText());
        }
        
        // 更新设备支持显示
        if (imageAdjustSupportedText != null) {
            imageAdjustSupportedText.setText(buildSupportedParamsText());
        }
    }
    
    /**
     * 构建当前参数文本
     */
    private String buildCurrentParamsText() {
        StringBuilder sb = new StringBuilder();
        
        if (imageAdjustManager != null) {
            boolean hasActual = imageAdjustManager.hasActualParams();
            
            // 曝光补偿
            int exposure = imageAdjustManager.getExposureCompensation();
            sb.append("曝光补偿：").append(exposure > 0 ? "+" : "").append(exposure);
            
            // 白平衡
            int awbMode = imageAdjustManager.getAwbMode();
            sb.append("\n白平衡：").append(AppConfig.getAwbModeDisplayName(awbMode));
            if (awbMode == AppConfig.AWB_MODE_DEFAULT && hasActual) {
                sb.append("（实际: ").append(AppConfig.getAwbModeDisplayName(imageAdjustManager.getActualAwbMode())).append("）");
            }
            
            // 色调映射
            int tonemapMode = imageAdjustManager.getTonemapMode();
            sb.append("\n色调映射：").append(AppConfig.getTonemapModeDisplayName(tonemapMode));
            if (tonemapMode == AppConfig.TONEMAP_MODE_DEFAULT && hasActual) {
                sb.append("（实际: ").append(AppConfig.getTonemapModeDisplayName(imageAdjustManager.getActualTonemapMode())).append("）");
            }
            
            // 边缘增强（锐度）
            int edgeMode = imageAdjustManager.getEdgeMode();
            sb.append("\n边缘增强：").append(AppConfig.getEdgeModeDisplayName(edgeMode));
            if (edgeMode == AppConfig.EDGE_MODE_DEFAULT && hasActual) {
                sb.append("（实际: ").append(AppConfig.getEdgeModeDisplayName(imageAdjustManager.getActualEdgeMode())).append("）");
            }
            
            // 降噪
            int noiseMode = imageAdjustManager.getNoiseReductionMode();
            sb.append("\n降噪模式：").append(AppConfig.getNoiseReductionModeDisplayName(noiseMode));
            if (noiseMode == AppConfig.NOISE_REDUCTION_DEFAULT && hasActual) {
                sb.append("（实际: ").append(AppConfig.getNoiseReductionModeDisplayName(imageAdjustManager.getActualNoiseReductionMode())).append("）");
            }
            
            // 特效
            int effectMode = imageAdjustManager.getEffectMode();
            sb.append("\n特效模式：").append(AppConfig.getEffectModeDisplayName(effectMode));
            if (effectMode == AppConfig.EFFECT_MODE_DEFAULT && hasActual) {
                sb.append("（实际: ").append(AppConfig.getEffectModeDisplayName(imageAdjustManager.getActualEffectMode())).append("）");
            }
        } else {
            sb.append("曝光补偿：0");
            sb.append("\n白平衡：默认");
            sb.append("\n色调映射：默认");
            sb.append("\n边缘增强：默认");
            sb.append("\n降噪模式：默认");
            sb.append("\n特效模式：默认");
        }
        
        return sb.toString();
    }
    
    /**
     * 构建设备支持的参数文本
     */
    private String buildSupportedParamsText() {
        StringBuilder sb = new StringBuilder();
        
        if (imageAdjustManager != null) {
            // 曝光补偿范围
            if (imageAdjustManager.isExposureCompensationSupported()) {
                android.util.Range<Integer> range = imageAdjustManager.getExposureRange();
                if (range != null) {
                    int lower = range.getLower();
                    int upper = range.getUpper();
                    sb.append("曝光补偿范围：").append(lower).append(" ~ ");
                    if (upper > 0) sb.append("+");
                    sb.append(upper);
                } else {
                    sb.append("曝光补偿范围：不支持");
                }
            } else {
                sb.append("曝光补偿范围：不支持");
            }
            
            // 支持的白平衡模式
            sb.append("\n支持的白平衡：");
            int[] awbModes = imageAdjustManager.getSupportedAwbModes();
            if (awbModes != null && awbModes.length > 0) {
                sb.append(formatSupportedModes(awbModes, "awb"));
            } else {
                sb.append("不支持");
            }
            
            // 支持的色调映射模式
            sb.append("\n支持的色调映射：");
            int[] tonemapModes = imageAdjustManager.getSupportedTonemapModes();
            if (tonemapModes != null && tonemapModes.length > 0) {
                sb.append(formatSupportedModes(tonemapModes, "tonemap"));
            } else {
                sb.append("不支持");
            }
            
            // 支持的边缘增强模式
            sb.append("\n支持的边缘增强：");
            int[] edgeModes = imageAdjustManager.getSupportedEdgeModes();
            if (edgeModes != null && edgeModes.length > 0) {
                sb.append(formatSupportedModes(edgeModes, "edge"));
            } else {
                sb.append("不支持");
            }
            
            // 支持的降噪模式
            sb.append("\n支持的降噪模式：");
            int[] noiseModes = imageAdjustManager.getSupportedNoiseReductionModes();
            if (noiseModes != null && noiseModes.length > 0) {
                sb.append(formatSupportedModes(noiseModes, "noise"));
            } else {
                sb.append("不支持");
            }
            
            // 支持的特效模式
            sb.append("\n支持的特效模式：");
            int[] effectModes = imageAdjustManager.getSupportedEffectModes();
            if (effectModes != null && effectModes.length > 0) {
                sb.append(formatSupportedModes(effectModes, "effect"));
            } else {
                sb.append("不支持");
            }
        } else {
            sb.append("摄像头未就绪，无法获取支持的参数");
        }
        
        return sb.toString();
    }
    
    /**
     * 格式化支持的模式列表
     */
    private String formatSupportedModes(int[] modes, String type) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < modes.length; i++) {
            if (i > 0) sb.append("、");
            switch (type) {
                case "awb":
                    sb.append(AppConfig.getAwbModeDisplayName(modes[i]));
                    break;
                case "tonemap":
                    sb.append(AppConfig.getTonemapModeDisplayName(modes[i]));
                    break;
                case "edge":
                    sb.append(AppConfig.getEdgeModeDisplayName(modes[i]));
                    break;
                case "noise":
                    sb.append(AppConfig.getNoiseReductionModeDisplayName(modes[i]));
                    break;
                case "effect":
                    sb.append(AppConfig.getEffectModeDisplayName(modes[i]));
                    break;
            }
        }
        return sb.toString();
    }
    
    /**
     * 打开调节悬浮窗
     * 悬浮窗由 MainActivity 管理，这样即使退出设置页面也能保持显示
     */
    private void openAdjustFloatingWindow() {
        if (getActivity() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getActivity();
            mainActivity.showImageAdjustFloatingWindow();
            
            // 返回主界面以便查看预览效果
            Toast.makeText(getContext(), "悬浮窗已打开，返回预览界面可实时查看效果", Toast.LENGTH_SHORT).show();
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // 每次返回此页面时更新参数显示
        updateImageAdjustParamsDisplay();
    }
}
