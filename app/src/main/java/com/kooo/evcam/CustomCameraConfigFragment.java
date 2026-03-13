package com.kooo.evcam;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

/**
 * 自定义摄像头配置界面 Fragment
 */
public class CustomCameraConfigFragment extends Fragment {

    private static final String TAG = "CustomCameraConfig";
    
    private AppConfig appConfig;
    
    // 摄像头数量选择
    private Spinner cameraCountSpinner;
    private static final String[] CAMERA_COUNT_OPTIONS = {"4 个摄像头", "2 个摄像头", "1 个摄像头"};

    // 按钮样式选项
    private static final String[] BUTTON_STYLE_OPTIONS = {"标准按钮", "多按钮"};
    private static final String[] BUTTON_STYLE_VALUES = {AppConfig.BUTTON_STYLE_STANDARD, AppConfig.BUTTON_STYLE_MULTI};

    // 摄像头配置区域
    private LinearLayout configFront, configBack, configLeft, configRight;
    
    // 摄像头编号选择器
    private Spinner spinnerFrontId, spinnerBackId, spinnerLeftId, spinnerRightId;

    // 摄像头名称输入框
    private EditText editFrontName, editBackName, editLeftName, editRightName;

    // 自由操控配置
    private SwitchMaterial switchFreeControl;
    private Spinner spinnerButtonStyle;
    
    // 布局数据编辑
    private EditText editLayoutData;
    private Button btnCopyLayout;
    private Button btnSaveLayout;
    
    // 可用的摄像头ID列表
    private List<String> availableCameraIds = new ArrayList<>();
    
    // 是否已完成初始加载（避免加载时触发保存）
    private boolean configInitialized = false;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_custom_camera_config, container, false);
        
        // 重置初始化标记
        configInitialized = false;
        
        // 初始化应用配置
        if (getContext() != null) {
            appConfig = new AppConfig(getContext());
        }
        
        // 初始化控件
        initViews(view);
        
        // 检测可用的摄像头
        detectAvailableCameras();
        
        // 初始化下拉选择器
        initSpinners();
        
        // 加载已保存的配置
        loadSavedConfig();
        
        // 设置返回按钮
        Button btnBack = view.findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().getSupportFragmentManager().popBackStack();
            }
        });
        
        // 设置重置所有配置按钮
        Button btnResetAllConfig = view.findViewById(R.id.btn_reset_all_config);
        if (btnResetAllConfig != null) {
            btnResetAllConfig.setOnClickListener(v -> resetAllCustomConfig());
        }
        
        // 设置重启按钮
        Button btnRestartApp = view.findViewById(R.id.btn_restart_app);
        if (btnRestartApp != null) {
            btnRestartApp.setOnClickListener(v -> restartApp());
        }
        
        // 设置自动保存监听器
        setupAutoSaveListeners();
        
        // 加载布局数据到编辑框
        loadLayoutData();
        
        // 设置布局数据按钮事件
        setupLayoutDataButtons();
        
        // 延迟设置初始化完成标记，确保 loadSavedConfig 触发的 Spinner 选择不会触发保存
        view.postDelayed(() -> {
            configInitialized = true;
            AppLog.d(TAG, "配置界面初始化完成，自动保存已启用");
        }, 300);
        
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
        cameraCountSpinner = view.findViewById(R.id.spinner_camera_count);
        
        configFront = view.findViewById(R.id.config_front);
        configBack = view.findViewById(R.id.config_back);
        configLeft = view.findViewById(R.id.config_left);
        configRight = view.findViewById(R.id.config_right);
        
        spinnerFrontId = view.findViewById(R.id.spinner_front_id);
        spinnerBackId = view.findViewById(R.id.spinner_back_id);
        spinnerLeftId = view.findViewById(R.id.spinner_left_id);
        spinnerRightId = view.findViewById(R.id.spinner_right_id);

        editFrontName = view.findViewById(R.id.edit_front_name);
        editBackName = view.findViewById(R.id.edit_back_name);
        editLeftName = view.findViewById(R.id.edit_left_name);
        editRightName = view.findViewById(R.id.edit_right_name);

        // 自由操控配置
        switchFreeControl = view.findViewById(R.id.switch_free_control);
        spinnerButtonStyle = view.findViewById(R.id.spinner_button_style);
        
        // 布局数据编辑
        editLayoutData = view.findViewById(R.id.edit_layout_data);
        btnCopyLayout = view.findViewById(R.id.btn_copy_layout);
        btnSaveLayout = view.findViewById(R.id.btn_save_layout);
    }
    
    /**
     * 检测可用的摄像头
     * 会验证每个摄像头是否真正可用（有有效的输出格式）
     */
    private void detectAvailableCameras() {
        if (getContext() == null) {
            return;
        }
        
        try {
            CameraManager cameraManager = (CameraManager) getContext().getSystemService(Context.CAMERA_SERVICE);
            String[] cameraIds = cameraManager.getCameraIdList();
            
            availableCameraIds.clear();
            int invalidCount = 0;
            
            for (String id : cameraIds) {
                // 验证摄像头是否真正可用
                if (isCameraValid(cameraManager, id)) {
                    availableCameraIds.add(id);
                } else {
                    invalidCount++;
                    if (invalidCount <= 3) {  // 只记录前几个无效的，避免日志过多
                        AppLog.d(TAG, "摄像头 " + id + " 无效（虚拟摄像头？），已跳过");
                    }
                }
            }
            
            if (invalidCount > 3) {
                AppLog.d(TAG, "还有 " + (invalidCount - 3) + " 个无效摄像头已跳过");
            }
            
            AppLog.d(TAG, "检测到 " + cameraIds.length + " 个摄像头ID，其中 " + 
                    availableCameraIds.size() + " 个有效: " + availableCameraIds);
            
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "检测摄像头失败", e);
            // 如果检测失败，提供默认选项
            availableCameraIds.clear();
            for (int i = 0; i < 4; i++) {
                availableCameraIds.add(String.valueOf(i));
            }
        }
    }
    
    /**
     * 验证摄像头是否真正可用
     * 检查摄像头是否有有效的输出格式和分辨率
     * @param cameraManager CameraManager实例
     * @param cameraId 要验证的摄像头ID
     * @return true如果摄像头可用，false如果是虚拟/无效摄像头
     */
    private boolean isCameraValid(CameraManager cameraManager, String cameraId) {
        try {
            android.hardware.camera2.CameraCharacteristics characteristics = 
                    cameraManager.getCameraCharacteristics(cameraId);
            
            // 检查是否有有效的输出配置
            android.hardware.camera2.params.StreamConfigurationMap map = 
                    characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            
            if (map == null) {
                return false;
            }
            
            // 检查是否有 PRIVATE 或 SurfaceTexture 的输出尺寸
            android.util.Size[] privateSizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE);
            android.util.Size[] textureSizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
            
            boolean hasValidOutput = (privateSizes != null && privateSizes.length > 0) ||
                                    (textureSizes != null && textureSizes.length > 0);
            
            return hasValidOutput;
            
        } catch (CameraAccessException e) {
            AppLog.d(TAG, "摄像头 " + cameraId + " 访问失败: " + e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            AppLog.d(TAG, "摄像头 " + cameraId + " 参数无效: " + e.getMessage());
            return false;
        } catch (Exception e) {
            AppLog.d(TAG, "摄像头 " + cameraId + " 验证异常: " + e.getMessage());
            return false;
        }
    }
    
    /**
     * 初始化下拉选择器
     */
    private void initSpinners() {
        if (getContext() == null) {
            return;
        }
        
        // 摄像头数量选择器
        ArrayAdapter<String> countAdapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                CAMERA_COUNT_OPTIONS
        );
        countAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        cameraCountSpinner.setAdapter(countAdapter);
        
        // 摄像头ID选择器
        ArrayAdapter<String> idAdapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                availableCameraIds
        );
        idAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        
        spinnerFrontId.setAdapter(idAdapter);
        spinnerBackId.setAdapter(idAdapter);
        spinnerLeftId.setAdapter(idAdapter);
        spinnerRightId.setAdapter(idAdapter);

        // 按钮样式选择器
        ArrayAdapter<String> buttonStyleAdapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                BUTTON_STYLE_OPTIONS
        );
        buttonStyleAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spinnerButtonStyle.setAdapter(buttonStyleAdapter);
    }
    
    /**
     * 设置自动保存监听器
     * 当任何配置项变更时自动保存
     */
    /**
     * 加载布局数据到编辑框
     */
    private void loadLayoutData() {
        if (editLayoutData != null && appConfig != null) {
            String layoutData = appConfig.getCustomLayoutData();
            if (layoutData != null && !layoutData.isEmpty()) {
                editLayoutData.setText(layoutData);
            } else {
                editLayoutData.setText("");
                editLayoutData.setHint("无布局数据");
            }
        }
    }
    
    /**
     * 设置布局数据按钮事件
     */
    private void setupLayoutDataButtons() {
        // 复制按钮
        if (btnCopyLayout != null) {
            btnCopyLayout.setOnClickListener(v -> {
                if (editLayoutData != null && getContext() != null) {
                    String text = editLayoutData.getText().toString();
                    if (!text.isEmpty()) {
                        ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
                        ClipData clip = ClipData.newPlainText("layout_data", text);
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(getContext(), "布局数据已复制", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(getContext(), "无数据可复制", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
        
        // 保存按钮
        if (btnSaveLayout != null) {
            btnSaveLayout.setOnClickListener(v -> {
                if (editLayoutData != null && appConfig != null && getContext() != null) {
                    String text = editLayoutData.getText().toString().trim();
                    if (!text.isEmpty()) {
                        // 简单验证 JSON 格式
                        if (text.startsWith("{") && text.endsWith("}")) {
                            appConfig.setCustomLayoutData(text);
                            Toast.makeText(getContext(), "布局数据已保存，重载后生效", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(getContext(), "无效的 JSON 格式", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        // 清空布局数据
                        appConfig.clearCustomLayoutData();
                        Toast.makeText(getContext(), "布局数据已清空", Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }
    
    private void setupAutoSaveListeners() {
        // 通用的 Spinner 变更监听器
        AdapterView.OnItemSelectedListener autoSaveSpinnerListener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (configInitialized) {
                    saveConfig();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        };
        
        // 为摄像头数量 Spinner 添加监听
        cameraCountSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int count;
                if (position == 0) {
                    count = 4;
                } else if (position == 1) {
                    count = 2;
                } else {
                    count = 1;
                }
                updateConfigVisibility(count);
                if (configInitialized) {
                    saveConfig();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        
        spinnerFrontId.setOnItemSelectedListener(autoSaveSpinnerListener);
        spinnerBackId.setOnItemSelectedListener(autoSaveSpinnerListener);
        spinnerLeftId.setOnItemSelectedListener(autoSaveSpinnerListener);
        spinnerRightId.setOnItemSelectedListener(autoSaveSpinnerListener);
        spinnerButtonStyle.setOnItemSelectedListener(autoSaveSpinnerListener);
        
        // Switch 变更监听
        switchFreeControl.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (configInitialized) {
                saveConfig();
            }
        });
        
        // EditText 变更监听（失去焦点时保存）
        View.OnFocusChangeListener autoSaveFocusListener = (v, hasFocus) -> {
            if (!hasFocus && configInitialized) {
                saveConfig();
            }
        };
        
        editFrontName.setOnFocusChangeListener(autoSaveFocusListener);
        editBackName.setOnFocusChangeListener(autoSaveFocusListener);
        editLeftName.setOnFocusChangeListener(autoSaveFocusListener);
        editRightName.setOnFocusChangeListener(autoSaveFocusListener);
    }
    
    /**
     * 根据摄像头数量更新配置区域的可见性
     */
    private void updateConfigVisibility(int count) {
        // 位置1（前）始终显示
        configFront.setVisibility(View.VISIBLE);
        
        // 位置2（后）在2个或4个摄像头时显示
        configBack.setVisibility(count >= 2 ? View.VISIBLE : View.GONE);
        
        // 位置3和4（左右）仅在4个摄像头时显示
        configLeft.setVisibility(count >= 4 ? View.VISIBLE : View.GONE);
        configRight.setVisibility(count >= 4 ? View.VISIBLE : View.GONE);
    }
    
    /**
     * 加载已保存的配置
     */
    private void loadSavedConfig() {
        if (appConfig == null) {
            return;
        }
        
        // 加载摄像头数量
        int count = appConfig.getCameraCount();
        int countIndex;
        
        if (count == 4) {
            countIndex = 0;  // 4个摄像头
        } else if (count == 2) {
            countIndex = 1;  // 2个摄像头
        } else {
            countIndex = 2;  // 1个摄像头
        }
        
        cameraCountSpinner.setSelection(countIndex);
        updateConfigVisibility(count);
        
        // 加载摄像头ID
        setSpinnerSelection(spinnerFrontId, appConfig.getCameraId("front"));
        setSpinnerSelection(spinnerBackId, appConfig.getCameraId("back"));
        setSpinnerSelection(spinnerLeftId, appConfig.getCameraId("left"));
        setSpinnerSelection(spinnerRightId, appConfig.getCameraId("right"));

        // 加载摄像头名称
        editFrontName.setText(appConfig.getCameraName("front"));
        editBackName.setText(appConfig.getCameraName("back"));
        editLeftName.setText(appConfig.getCameraName("left"));
        editRightName.setText(appConfig.getCameraName("right"));

        // 加载自由操控配置
        switchFreeControl.setChecked(appConfig.isCustomFreeControlEnabled());
        setButtonStyleSpinnerSelection(spinnerButtonStyle, appConfig.getCustomButtonStyle());
    }

    /**
     * 设置按钮样式 Spinner 的选中项
     */
    private void setButtonStyleSpinnerSelection(Spinner spinner, String style) {
        int index = 0;
        for (int i = 0; i < BUTTON_STYLE_VALUES.length; i++) {
            if (BUTTON_STYLE_VALUES[i].equals(style)) {
                index = i;
                break;
            }
        }
        spinner.setSelection(index);
    }

    /**
     * 设置 Spinner 的选中项
     */
    private void setSpinnerSelection(Spinner spinner, String value) {
        int index = availableCameraIds.indexOf(value);
        if (index >= 0) {
            spinner.setSelection(index);
        } else if (!availableCameraIds.isEmpty()) {
            spinner.setSelection(0);
        }
    }

    /**
     * 保存配置
     */
    private void saveConfig() {
        if (appConfig == null || getContext() == null) {
            return;
        }
        
        // 保存摄像头数量
        int countIndex = cameraCountSpinner.getSelectedItemPosition();
        int count;
        
        if (countIndex == 0) {
            count = 4;  // 4个摄像头
        } else if (countIndex == 1) {
            count = 2;  // 2个摄像头
        } else {
            count = 1;  // 1个摄像头
        }
        
        appConfig.setCameraCount(count);
        appConfig.setScreenOrientation("landscape");  // 统一使用横屏模式
        
        // 保存摄像头ID
        appConfig.setCameraId("front", getSpinnerValue(spinnerFrontId));
        if (count >= 2) {
            appConfig.setCameraId("back", getSpinnerValue(spinnerBackId));
        }
        if (count >= 4) {
            appConfig.setCameraId("left", getSpinnerValue(spinnerLeftId));
            appConfig.setCameraId("right", getSpinnerValue(spinnerRightId));
        }

        // 保存摄像头名称
        appConfig.setCameraName("front", editFrontName.getText().toString().trim());
        if (count >= 2) {
            appConfig.setCameraName("back", editBackName.getText().toString().trim());
        }
        if (count >= 4) {
            appConfig.setCameraName("left", editLeftName.getText().toString().trim());
            appConfig.setCameraName("right", editRightName.getText().toString().trim());
        }

        // 保存自由操控配置
        appConfig.setCustomFreeControlEnabled(switchFreeControl.isChecked());
        
        // 保存按钮样式
        String buttonStyleValue = getButtonStyleSpinnerValue();
        appConfig.setCustomButtonStyle(buttonStyleValue);
        
        AppLog.d(TAG, "配置已自动保存: 摄像头数量=" + count + ", 自由操控=" + switchFreeControl.isChecked() + ", 按钮样式=" + buttonStyleValue);
    }
    
    /**
     * 获取按钮样式 Spinner 的值
     */
    private String getButtonStyleSpinnerValue() {
        int position = spinnerButtonStyle.getSelectedItemPosition();
        if (position >= 0 && position < BUTTON_STYLE_VALUES.length) {
            return BUTTON_STYLE_VALUES[position];
        }
        return AppConfig.BUTTON_STYLE_STANDARD;
    }
    
    /**
     * 获取 Spinner 当前选中的值
     */
    private String getSpinnerValue(Spinner spinner) {
        Object selectedItem = spinner.getSelectedItem();
        return selectedItem != null ? selectedItem.toString() : "0";
    }
    
    /**
     * 重置所有自定义配置
     */
    private void resetAllCustomConfig() {
        if (appConfig == null) return;
        
        // 禁用自动保存，防止重置过程中触发保存
        configInitialized = false;
        
        // 重置摄像头映射
        appConfig.setCameraCount(4);
        appConfig.setCameraId("front", "0");
        appConfig.setCameraId("back", "1");
        appConfig.setCameraId("left", "2");
        appConfig.setCameraId("right", "3");
        appConfig.setCameraName("front", "前");
        appConfig.setCameraName("back", "后");
        appConfig.setCameraName("left", "左");
        appConfig.setCameraName("right", "右");
        
        // 重置摄像头旋转和镜像
        appConfig.setCameraRotation("front", 0);
        appConfig.setCameraRotation("back", 0);
        appConfig.setCameraRotation("left", 0);
        appConfig.setCameraRotation("right", 0);
        appConfig.setCameraMirror("front", false);
        appConfig.setCameraMirror("back", false);
        appConfig.setCameraMirror("left", false);
        appConfig.setCameraMirror("right", false);
        
        // 重置裁剪配置
        appConfig.resetCameraCrop("front");
        appConfig.resetCameraCrop("back");
        appConfig.resetCameraCrop("left");
        appConfig.resetCameraCrop("right");
        
        // 重置布局数据
        appConfig.clearCustomLayoutData();
        
        // 重置自由操控开关
        appConfig.setCustomFreeControlEnabled(false);
        
        // 重置按钮样式和方向
        appConfig.setCustomButtonStyle(AppConfig.BUTTON_STYLE_STANDARD);
        appConfig.setCustomButtonOrientation(AppConfig.BUTTON_ORIENTATION_HORIZONTAL);
        
        AppLog.d(TAG, "所有自定义配置已重置");
        
        // 重新加载配置到界面
        loadSavedConfig();
        
        // 重新启用自动保存
        if (getView() != null) {
            getView().postDelayed(() -> {
                configInitialized = true;
            }, 300);
        }
        
        // 提示用户
        if (getContext() != null) {
            android.widget.Toast.makeText(getContext(), "配置已重置，请重启应用生效", android.widget.Toast.LENGTH_LONG).show();
        }
    }
    
    /**
     * 重载界面（重新创建 Activity）
     */
    private void restartApp() {
        if (getActivity() == null) return;

        android.widget.Toast.makeText(getContext(), "正在重载界面...", android.widget.Toast.LENGTH_SHORT).show();
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().setCameraManager(null);
        getActivity().recreate();
    }
}
