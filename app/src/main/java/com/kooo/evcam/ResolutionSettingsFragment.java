package com.kooo.evcam;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分辨率设置界面 Fragment
 */
public class ResolutionSettingsFragment extends Fragment {

    private static final String TAG = "ResolutionSettings";

    private AppConfig appConfig;
    private Spinner resolutionSpinner;
    private TextView resolutionDescText;
    private TextView currentResolutionsText;
    private TextView supportedResolutionsText;

    // 分辨率选项列表
    private List<String> resolutionOptions = new ArrayList<>();
    
    // 每个摄像头支持的分辨率
    private Map<String, List<Size>> cameraSupportedResolutions = new LinkedHashMap<>();

    // 当前选中的分辨率
    private String selectedResolution;
    
    // 是否正在初始化
    private boolean isInitializing = false;

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

        // 检测摄像头支持的分辨率
        detectSupportedResolutions();

        // 初始化分辨率选择器
        initResolutionSpinner();

        // 显示调试信息
        displayDebugInfo();

        // 设置返回按钮
        Button btnBack = view.findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().getSupportFragmentManager().popBackStack();
            }
        });

        // 设置保存按钮
        Button btnSave = view.findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> saveConfig());

        return view;
    }

    private void initViews(View view) {
        resolutionSpinner = view.findViewById(R.id.spinner_resolution);
        resolutionDescText = view.findViewById(R.id.tv_resolution_desc);
        currentResolutionsText = view.findViewById(R.id.tv_current_resolutions);
        supportedResolutionsText = view.findViewById(R.id.tv_supported_resolutions);
    }

    /**
     * 检测所有摄像头支持的分辨率
     */
    private void detectSupportedResolutions() {
        if (getContext() == null) {
            return;
        }

        cameraSupportedResolutions.clear();

        try {
            CameraManager cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = cameraManager.getCameraIdList();

            for (String cameraId : cameraIds) {
                try {
                    CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);
                    StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                    if (map != null) {
                        // 获取 PRIVATE 格式的分辨率（用于预览和录制）
                        Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE);
                        if (sizes == null || sizes.length == 0) {
                            sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                        }

                        if (sizes != null && sizes.length > 0) {
                            List<Size> sizeList = new ArrayList<>();
                            for (Size size : sizes) {
                                sizeList.add(size);
                            }
                            // 按分辨率从大到小排序
                            Collections.sort(sizeList, (s1, s2) -> {
                                int pixels1 = s1.getWidth() * s1.getHeight();
                                int pixels2 = s2.getWidth() * s2.getHeight();
                                return pixels2 - pixels1;
                            });
                            cameraSupportedResolutions.put(cameraId, sizeList);
                        }
                    }
                } catch (CameraAccessException e) {
                    AppLog.e(TAG, "获取摄像头 " + cameraId + " 特性失败", e);
                }
            }

            AppLog.d(TAG, "检测到 " + cameraSupportedResolutions.size() + " 个摄像头的分辨率信息");

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
        resolutionOptions.add("默认 (1280×800)");  // 默认选项

        // 收集所有摄像头支持的分辨率（去重）
        Set<String> allResolutions = new LinkedHashSet<>();
        for (List<Size> sizes : cameraSupportedResolutions.values()) {
            for (Size size : sizes) {
                allResolutions.add(size.getWidth() + "x" + size.getHeight());
            }
        }

        // 将分辨率按像素数从大到小排序
        List<String> sortedResolutions = new ArrayList<>(allResolutions);
        Collections.sort(sortedResolutions, (r1, r2) -> {
            int[] p1 = AppConfig.parseResolution(r1);
            int[] p2 = AppConfig.parseResolution(r2);
            if (p1 == null || p2 == null) return 0;
            int pixels1 = p1[0] * p1[1];
            int pixels2 = p2[0] * p2[1];
            return pixels2 - pixels1;
        });

        // 添加到选项列表
        for (String res : sortedResolutions) {
            resolutionOptions.add(res);
        }

        // 设置适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                resolutionOptions
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        resolutionSpinner.setAdapter(adapter);

        // 设置当前选中项
        String currentResolution = appConfig.getTargetResolution();
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

                if (position == 0) {
                    selectedResolution = AppConfig.RESOLUTION_DEFAULT;
                    updateResolutionDescription("默认：优先匹配 1280×800，否则选择最接近的分辨率");
                } else {
                    selectedResolution = resolutionOptions.get(position);
                    updateResolutionDescription("将优先匹配 " + selectedResolution + "，如果摄像头不支持则选择最接近的分辨率");
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        // 延迟结束初始化
        resolutionSpinner.post(() -> isInitializing = false);
    }

    /**
     * 更新分辨率描述文字
     */
    private void updateResolutionDescription(String desc) {
        if (resolutionDescText != null) {
            resolutionDescText.setText(desc);
        }
    }

    /**
     * 显示调试信息
     */
    private void displayDebugInfo() {
        // 显示当前使用的分辨率
        displayCurrentResolutions();

        // 显示支持的分辨率
        displaySupportedResolutions();
    }

    /**
     * 显示当前使用的分辨率
     */
    private void displayCurrentResolutions() {
        if (currentResolutionsText == null || getActivity() == null) {
            return;
        }

        // 从 MainActivity 获取当前分辨率信息
        if (getActivity() instanceof MainActivity) {
            MainActivity mainActivity = (MainActivity) getActivity();
            String info = mainActivity.getCurrentCameraResolutionsInfo();
            if (info != null && !info.isEmpty()) {
                currentResolutionsText.setText(info);
            } else {
                currentResolutionsText.setText("暂无数据（摄像头可能未初始化）");
            }
        } else {
            currentResolutionsText.setText("暂无数据");
        }
    }

    /**
     * 显示支持的分辨率
     */
    private void displaySupportedResolutions() {
        if (supportedResolutionsText == null) {
            return;
        }

        if (cameraSupportedResolutions.isEmpty()) {
            supportedResolutionsText.setText("未检测到摄像头");
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, List<Size>> entry : cameraSupportedResolutions.entrySet()) {
            String cameraId = entry.getKey();
            List<Size> sizes = entry.getValue();

            sb.append("摄像头 ").append(cameraId).append(":\n");
            for (int i = 0; i < sizes.size(); i++) {
                Size size = sizes.get(i);
                sb.append("  ").append(size.getWidth()).append("×").append(size.getHeight());
                if (i < sizes.size() - 1) {
                    sb.append("\n");
                }
            }
            sb.append("\n\n");
        }

        supportedResolutionsText.setText(sb.toString().trim());
    }

    /**
     * 保存配置
     */
    private void saveConfig() {
        if (appConfig == null || getContext() == null) {
            return;
        }

        String oldResolution = appConfig.getTargetResolution();
        appConfig.setTargetResolution(selectedResolution);

        String displayName = selectedResolution.equals(AppConfig.RESOLUTION_DEFAULT) 
                ? "默认 (1280×800)" 
                : selectedResolution;
        
        Toast.makeText(getContext(), "分辨率已设置为「" + displayName + "」\n重启应用后生效", Toast.LENGTH_LONG).show();
        AppLog.d(TAG, "分辨率配置已保存: " + oldResolution + " -> " + selectedResolution);

        // 返回上一级
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }
}
