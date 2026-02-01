package com.kooo.evcam;

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
    private static final String[] CAMERA_COUNT_OPTIONS = {"4-横屏", "4-竖屏", "2 个", "1 个"};

    // 旋转角度选项
    private static final String[] ROTATION_OPTIONS = {"0°", "90°", "180°", "270°"};
    private static final int[] ROTATION_VALUES = {0, 90, 180, 270};

    // 摄像头配置区域
    private LinearLayout configFront, configBack, configLeft, configRight;
    
    // 摄像头编号选择器
    private Spinner spinnerFrontId, spinnerBackId, spinnerLeftId, spinnerRightId;

    // 摄像头旋转角度选择器
    private Spinner spinnerFrontRotation, spinnerBackRotation, spinnerLeftRotation, spinnerRightRotation;

    // 摄像头名称输入框
    private EditText editFrontName, editBackName, editLeftName, editRightName;
    
    // 可用的摄像头ID列表
    private List<String> availableCameraIds = new ArrayList<>();
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_custom_camera_config, container, false);
        
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
        
        // 设置保存按钮
        Button btnSave = view.findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> saveConfig());
        
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

        spinnerFrontRotation = view.findViewById(R.id.spinner_front_rotation);
        spinnerBackRotation = view.findViewById(R.id.spinner_back_rotation);
        spinnerLeftRotation = view.findViewById(R.id.spinner_left_rotation);
        spinnerRightRotation = view.findViewById(R.id.spinner_right_rotation);

        editFrontName = view.findViewById(R.id.edit_front_name);
        editBackName = view.findViewById(R.id.edit_back_name);
        editLeftName = view.findViewById(R.id.edit_left_name);
        editRightName = view.findViewById(R.id.edit_right_name);
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
        
        // 摄像头数量选择监听
        cameraCountSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // 0: 4-横屏, 1: 4-竖屏, 2: 2个, 3: 1个
                int count;
                if (position == 0 || position == 1) {
                    count = 4;  // 4-横屏 或 4-竖屏
                } else if (position == 2) {
                    count = 2;  // 2个
                } else {
                    count = 1;  // 1个
                }
                updateConfigVisibility(count);
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不处理
            }
        });
        
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

        // 旋转角度选择器
        ArrayAdapter<String> rotationAdapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                ROTATION_OPTIONS
        );
        rotationAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);

        spinnerFrontRotation.setAdapter(rotationAdapter);
        spinnerBackRotation.setAdapter(rotationAdapter);
        spinnerLeftRotation.setAdapter(rotationAdapter);
        spinnerRightRotation.setAdapter(rotationAdapter);
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
        
        // 加载摄像头数量和屏幕方向
        int count = appConfig.getCameraCount();
        String orientation = appConfig.getScreenOrientation();
        int countIndex;
        
        if (count == 4) {
            // 4摄像头：根据屏幕方向选择"4-横屏"或"4-竖屏"
            countIndex = "portrait".equals(orientation) ? 1 : 0;
        } else if (count == 2) {
            countIndex = 2;
        } else {
            countIndex = 3;
        }
        
        cameraCountSpinner.setSelection(countIndex);
        updateConfigVisibility(count);
        
        // 加载摄像头ID
        setSpinnerSelection(spinnerFrontId, appConfig.getCameraId("front"));
        setSpinnerSelection(spinnerBackId, appConfig.getCameraId("back"));
        setSpinnerSelection(spinnerLeftId, appConfig.getCameraId("left"));
        setSpinnerSelection(spinnerRightId, appConfig.getCameraId("right"));

        // 加载摄像头旋转角度
        setRotationSpinnerSelection(spinnerFrontRotation, appConfig.getCameraRotation("front"));
        setRotationSpinnerSelection(spinnerBackRotation, appConfig.getCameraRotation("back"));
        setRotationSpinnerSelection(spinnerLeftRotation, appConfig.getCameraRotation("left"));
        setRotationSpinnerSelection(spinnerRightRotation, appConfig.getCameraRotation("right"));

        // 加载摄像头名称
        editFrontName.setText(appConfig.getCameraName("front"));
        editBackName.setText(appConfig.getCameraName("back"));
        editLeftName.setText(appConfig.getCameraName("left"));
        editRightName.setText(appConfig.getCameraName("right"));
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
     * 设置旋转角度 Spinner 的选中项
     */
    private void setRotationSpinnerSelection(Spinner spinner, int rotation) {
        int index = 0;
        for (int i = 0; i < ROTATION_VALUES.length; i++) {
            if (ROTATION_VALUES[i] == rotation) {
                index = i;
                break;
            }
        }
        spinner.setSelection(index);
    }

    /**
     * 获取旋转角度 Spinner 的值
     */
    private int getRotationSpinnerValue(Spinner spinner) {
        int position = spinner.getSelectedItemPosition();
        if (position >= 0 && position < ROTATION_VALUES.length) {
            return ROTATION_VALUES[position];
        }
        return 0;  // 默认不旋转
    }

    /**
     * 保存配置
     */
    private void saveConfig() {
        if (appConfig == null || getContext() == null) {
            return;
        }
        
        // 保存摄像头数量和屏幕方向
        int countIndex = cameraCountSpinner.getSelectedItemPosition();
        int count;
        String orientation;
        
        if (countIndex == 0) {
            // 4-横屏
            count = 4;
            orientation = "landscape";
        } else if (countIndex == 1) {
            // 4-竖屏
            count = 4;
            orientation = "portrait";
        } else if (countIndex == 2) {
            // 2个
            count = 2;
            orientation = "landscape";  // 默认横屏
        } else {
            // 1个
            count = 1;
            orientation = "landscape";  // 默认横屏
        }
        
        appConfig.setCameraCount(count);
        appConfig.setScreenOrientation(orientation);
        
        // 保存摄像头ID
        appConfig.setCameraId("front", getSpinnerValue(spinnerFrontId));
        if (count >= 2) {
            appConfig.setCameraId("back", getSpinnerValue(spinnerBackId));
        }
        if (count >= 4) {
            appConfig.setCameraId("left", getSpinnerValue(spinnerLeftId));
            appConfig.setCameraId("right", getSpinnerValue(spinnerRightId));
        }

        // 保存摄像头旋转角度
        appConfig.setCameraRotation("front", getRotationSpinnerValue(spinnerFrontRotation));
        if (count >= 2) {
            appConfig.setCameraRotation("back", getRotationSpinnerValue(spinnerBackRotation));
        }
        if (count >= 4) {
            appConfig.setCameraRotation("left", getRotationSpinnerValue(spinnerLeftRotation));
            appConfig.setCameraRotation("right", getRotationSpinnerValue(spinnerRightRotation));
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
        
        Toast.makeText(getContext(), "配置已保存，重启应用后生效", Toast.LENGTH_LONG).show();
        AppLog.d(TAG, "配置已保存: 摄像头数量=" + count);
        
        // 返回上一页
        if (getActivity() != null) {
            getActivity().getSupportFragmentManager().popBackStack();
        }
    }
    
    /**
     * 获取 Spinner 当前选中的值
     */
    private String getSpinnerValue(Spinner spinner) {
        Object selectedItem = spinner.getSelectedItem();
        return selectedItem != null ? selectedItem.toString() : "0";
    }
}
