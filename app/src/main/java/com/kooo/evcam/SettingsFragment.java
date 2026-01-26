package com.kooo.evcam;

import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;
import java.util.List;

/**
 * 软件设置界面 Fragment
 */
public class SettingsFragment extends Fragment {

    private SwitchMaterial debugSwitch;
    private Button saveLogsButton;
    private SwitchMaterial autoStartSwitch;
    private SwitchMaterial keepAliveSwitch;
    private AppConfig appConfig;
    
    // 悬浮窗相关
    private SwitchMaterial floatingWindowSwitch;
    private LinearLayout floatingWindowSettingsLayout;
    private Spinner floatingWindowSizeSpinner;
    private SeekBar floatingWindowAlphaSeekBar;
    private TextView floatingWindowAlphaText;
    private static final String[] FLOATING_SIZE_OPTIONS = {"小", "中", "大"};
    private boolean isInitializingFloatingSize = false;
    
    // 车型配置相关
    private Spinner carModelSpinner;
    private Button customCameraConfigButton;
    private static final String[] CAR_MODEL_OPTIONS = {"银河E5", "银河L6/L7", "手机", "自定义车型"};
    private boolean isInitializingCarModel = false;
    private String lastAppliedCarModel = null;
    
    // 录制模式配置相关
    private Spinner recordingModeSpinner;
    private TextView recordingModeDescText;
    private static final String[] RECORDING_MODE_OPTIONS = {"自动（推荐）", "MediaRecorder", "OpenGL+MediaCodec"};
    private boolean isInitializingRecordingMode = false;
    private String lastAppliedRecordingMode = null;
    
    // 存储位置配置相关
    private Spinner storageLocationSpinner;
    private TextView storageLocationDescText;
    private Button storageDebugButton;
    private String[] storageLocationOptions;
    private boolean isInitializingStorageLocation = false;
    private String lastAppliedStorageLocation = null;
    private boolean hasExternalSdCard = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // 初始化控件
        debugSwitch = view.findViewById(R.id.switch_debug_to_info);
        saveLogsButton = view.findViewById(R.id.btn_save_logs);
        Button menuButton = view.findViewById(R.id.btn_menu);

        // 设置菜单按钮点击事件
        menuButton.setOnClickListener(v -> {
            if (getActivity() != null) {
                DrawerLayout drawerLayout = getActivity().findViewById(R.id.drawer_layout);
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            }
        });

        // 初始化应用配置
        if (getContext() != null) {
            appConfig = new AppConfig(getContext());
            
            // 初始化Debug开关状态
            debugSwitch.setChecked(AppLog.isDebugToInfoEnabled(getContext()));
            
            // 根据 Debug 状态显示或隐藏保存日志按钮
            updateSaveLogsButtonVisibility(debugSwitch.isChecked());
            
            // 初始化车型配置
            initCarModelConfig(view);
            
            // 初始化录制模式配置
            initRecordingModeConfig(view);
            
            // 初始化存储位置配置
            initStorageLocationConfig(view);
        }

        // 设置Debug开关监听器
        debugSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null) {
                AppLog.setDebugToInfoEnabled(getContext(), isChecked);
                updateSaveLogsButtonVisibility(isChecked);
                String message = isChecked ? "Debug logs will show as info" : "Debug logs will show as debug";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        });

        // 设置保存日志按钮监听器
        saveLogsButton.setOnClickListener(v -> {
            if (getContext() != null) {
                File logFile = AppLog.saveLogsToFile(getContext());
                if (logFile != null) {
                    Toast.makeText(getContext(), "Logs saved to: " + logFile.getAbsolutePath(), Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(getContext(), "Failed to save logs", Toast.LENGTH_SHORT).show();
                }
            }
        });

        // 初始化权限设置入口
        Button btnPermissionSettings = view.findViewById(R.id.btn_permission_settings);
        btnPermissionSettings.setOnClickListener(v -> openPermissionSettings());

        // 初始化开机自启动开关
        autoStartSwitch = view.findViewById(R.id.switch_auto_start);
        if (getContext() != null && appConfig != null) {
            autoStartSwitch.setChecked(appConfig.isAutoStartOnBoot());
        }

        // 设置开机自启动开关监听器
        autoStartSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setAutoStartOnBoot(isChecked);
                String message = isChecked ? "开机自启动已启用" : "开机自启动已禁用";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
            }
        });

        // 初始化保活服务开关
        keepAliveSwitch = view.findViewById(R.id.switch_keep_alive);
        if (getContext() != null && appConfig != null) {
            keepAliveSwitch.setChecked(appConfig.isKeepAliveEnabled());
        }

        // 设置保活服务开关监听器
        keepAliveSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setKeepAliveEnabled(isChecked);
                
                if (isChecked) {
                    KeepAliveManager.startKeepAliveWork(getContext());
                    Toast.makeText(getContext(), "定时保活任务已启动", Toast.LENGTH_SHORT).show();
                    AppLog.d("SettingsFragment", "定时保活任务已启动");
                } else {
                    KeepAliveManager.stopKeepAliveWork(getContext());
                    Toast.makeText(getContext(), "定时保活任务已停止", Toast.LENGTH_SHORT).show();
                    AppLog.d("SettingsFragment", "定时保活任务已停止");
                }
            }
        });

        // 初始化悬浮窗设置
        initFloatingWindowSettings(view);

        return view;
    }
    
    /**
     * 打开权限设置页面
     */
    private void openPermissionSettings() {
        if (getActivity() == null) return;
        
        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, new PermissionSettingsFragment());
        transaction.addToBackStack(null);
        transaction.commit();
    }
    
    /**
     * 初始化悬浮窗设置
     */
    private void initFloatingWindowSettings(View view) {
        floatingWindowSwitch = view.findViewById(R.id.switch_floating_window);
        floatingWindowSettingsLayout = view.findViewById(R.id.layout_floating_window_settings);
        floatingWindowSizeSpinner = view.findViewById(R.id.spinner_floating_window_size);
        floatingWindowAlphaSeekBar = view.findViewById(R.id.seekbar_floating_window_alpha);
        floatingWindowAlphaText = view.findViewById(R.id.tv_floating_window_alpha_value);
        
        if (floatingWindowSwitch == null || getContext() == null || appConfig == null) {
            return;
        }
        
        // 初始化悬浮窗开关状态
        boolean floatingEnabled = appConfig.isFloatingWindowEnabled();
        floatingWindowSwitch.setChecked(floatingEnabled);
        updateFloatingWindowSettingsVisibility(floatingEnabled);
        
        // 设置悬浮窗开关监听器
        floatingWindowSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() == null || appConfig == null) {
                return;
            }
            
            // 检查悬浮窗权限
            if (isChecked && !WakeUpHelper.hasOverlayPermission(getContext())) {
                Toast.makeText(getContext(), "请先在权限设置中授权悬浮窗权限", Toast.LENGTH_SHORT).show();
                buttonView.setChecked(false);
                WakeUpHelper.requestOverlayPermission(getContext());
                return;
            }
            
            appConfig.setFloatingWindowEnabled(isChecked);
            updateFloatingWindowSettingsVisibility(isChecked);
            
            if (isChecked) {
                FloatingWindowService.start(getContext());
                Toast.makeText(getContext(), "悬浮窗已开启", Toast.LENGTH_SHORT).show();
                
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).broadcastCurrentRecordingState();
                }
            } else {
                FloatingWindowService.stop(getContext());
                Toast.makeText(getContext(), "悬浮窗已关闭", Toast.LENGTH_SHORT).show();
            }
        });
        
        // 初始化悬浮窗大小选择器
        initFloatingWindowSizeSpinner();
        
        // 初始化悬浮窗透明度滑块
        initFloatingWindowAlphaSeekBar();
    }
    
    /**
     * 初始化悬浮窗大小选择器
     */
    private void initFloatingWindowSizeSpinner() {
        if (floatingWindowSizeSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingFloatingSize = true;
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                FLOATING_SIZE_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        floatingWindowSizeSpinner.setAdapter(adapter);
        
        floatingWindowSizeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (isInitializingFloatingSize) {
                    return;
                }
                
                int sizeDp;
                String sizeName;
                switch (position) {
                    case 0:
                        sizeDp = AppConfig.FLOATING_SIZE_SMALL;
                        sizeName = "小";
                        break;
                    case 1:
                        sizeDp = AppConfig.FLOATING_SIZE_MEDIUM;
                        sizeName = "中";
                        break;
                    default:
                        sizeDp = AppConfig.FLOATING_SIZE_LARGE;
                        sizeName = "大";
                        break;
                }
                
                appConfig.setFloatingWindowSize(sizeDp);
                
                if (getContext() != null && appConfig.isFloatingWindowEnabled()) {
                    FloatingWindowService.sendUpdateFloatingWindow(getContext());
                    Toast.makeText(getContext(), "悬浮窗大小已设置为「" + sizeName + "」", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        int currentSize = appConfig.getFloatingWindowSize();
        int selectedIndex;
        if (currentSize <= AppConfig.FLOATING_SIZE_SMALL) {
            selectedIndex = 0;
        } else if (currentSize <= AppConfig.FLOATING_SIZE_MEDIUM) {
            selectedIndex = 1;
        } else {
            selectedIndex = 2;
        }
        floatingWindowSizeSpinner.setSelection(selectedIndex);
        
        floatingWindowSizeSpinner.post(() -> {
            isInitializingFloatingSize = false;
        });
    }
    
    /**
     * 初始化悬浮窗透明度滑块
     */
    private void initFloatingWindowAlphaSeekBar() {
        if (floatingWindowAlphaSeekBar == null || floatingWindowAlphaText == null || getContext() == null) {
            return;
        }
        
        floatingWindowAlphaSeekBar.setMax(80);
        
        int currentAlpha = appConfig.getFloatingWindowAlpha();
        floatingWindowAlphaSeekBar.setProgress(currentAlpha - 20);
        floatingWindowAlphaText.setText(currentAlpha + "%");
        
        floatingWindowAlphaSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int alpha = progress + 20;
                floatingWindowAlphaText.setText(alpha + "%");
            }
            
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }
            
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int alpha = seekBar.getProgress() + 20;
                appConfig.setFloatingWindowAlpha(alpha);
                
                if (getContext() != null && appConfig.isFloatingWindowEnabled()) {
                    FloatingWindowService.sendUpdateFloatingWindow(getContext());
                }
            }
        });
    }
    
    /**
     * 更新悬浮窗设置区域的可见性
     */
    private void updateFloatingWindowSettingsVisibility(boolean visible) {
        if (floatingWindowSettingsLayout != null) {
            floatingWindowSettingsLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        
        // 重新检测 SD 卡（可能在授权后返回）
        if (getContext() != null) {
            boolean newHasSdCard = StorageHelper.hasExternalSdCard(getContext());
            if (newHasSdCard != hasExternalSdCard) {
                hasExternalSdCard = newHasSdCard;
                if (storageDebugButton != null) {
                    storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
                }
                if (hasExternalSdCard && storageLocationSpinner != null) {
                    storageLocationOptions = new String[] {"内部存储", "外置SD卡"};
                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                            getContext(),
                            R.layout.spinner_item,
                            storageLocationOptions
                    );
                    adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                    storageLocationSpinner.setAdapter(adapter);
                    Toast.makeText(getContext(), "检测到外置SD卡", Toast.LENGTH_SHORT).show();
                    // 更新描述文字
                    String currentLocation = appConfig != null ? appConfig.getStorageLocation() : AppConfig.STORAGE_INTERNAL;
                    updateStorageLocationDescription(currentLocation);
                }
            }
        }
        
        // 更新悬浮窗开关状态
        if (floatingWindowSwitch != null && getContext() != null && appConfig != null) {
            boolean hasPermission = WakeUpHelper.hasOverlayPermission(getContext());
            boolean isEnabled = appConfig.isFloatingWindowEnabled();
            
            if (isEnabled && hasPermission) {
                FloatingWindowService.start(getContext());
            }
        }
    }
    
    /**
     * 初始化车型配置
     */
    private void initCarModelConfig(View view) {
        carModelSpinner = view.findViewById(R.id.spinner_car_model);
        customCameraConfigButton = view.findViewById(R.id.btn_custom_camera_config);
        
        if (carModelSpinner == null || customCameraConfigButton == null || getContext() == null) {
            return;
        }

        isInitializingCarModel = true;
        lastAppliedCarModel = (appConfig != null) ? appConfig.getCarModel() : null;
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                CAR_MODEL_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        carModelSpinner.setAdapter(adapter);
        
        carModelSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newModel;
                String modelName;
                
                if (position == 0) {
                    newModel = AppConfig.CAR_MODEL_GALAXY_E5;
                    modelName = "银河E5";
                } else if (position == 1) {
                    newModel = AppConfig.CAR_MODEL_L7;
                    modelName = "银河L6/L7";
                } else if (position == 2) {
                    newModel = AppConfig.CAR_MODEL_PHONE;
                    modelName = "手机";
                } else {
                    newModel = AppConfig.CAR_MODEL_CUSTOM;
                    modelName = "自定义车型";
                }

                updateCustomConfigButtonVisibility(position == 3);

                if (isInitializingCarModel) {
                    return;
                }

                if (newModel.equals(lastAppliedCarModel)) {
                    return;
                }

                lastAppliedCarModel = newModel;
                appConfig.setCarModel(newModel);
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "已切换为「" + modelName + "」，重启应用后生效", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        String currentModel = appConfig.getCarModel();
        int selectedIndex = 0;
        if (AppConfig.CAR_MODEL_L7.equals(currentModel)) {
            selectedIndex = 1;
        } else if (AppConfig.CAR_MODEL_PHONE.equals(currentModel)) {
            selectedIndex = 2;
        } else if (AppConfig.CAR_MODEL_CUSTOM.equals(currentModel)) {
            selectedIndex = 3;
        }
        carModelSpinner.setSelection(selectedIndex);
        
        carModelSpinner.post(() -> {
            isInitializingCarModel = false;
        });
        
        customCameraConfigButton.setOnClickListener(v -> {
            openCustomCameraConfig();
        });
    }
    
    /**
     * 更新自定义配置按钮的可见性
     */
    private void updateCustomConfigButtonVisibility(boolean visible) {
        if (customCameraConfigButton != null) {
            customCameraConfigButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    
    /**
     * 初始化录制模式配置
     */
    private void initRecordingModeConfig(View view) {
        recordingModeSpinner = view.findViewById(R.id.spinner_recording_mode);
        recordingModeDescText = view.findViewById(R.id.tv_recording_mode_desc);
        
        if (recordingModeSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingRecordingMode = true;
        lastAppliedRecordingMode = (appConfig != null) ? appConfig.getRecordingMode() : null;
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                RECORDING_MODE_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        recordingModeSpinner.setAdapter(adapter);
        
        recordingModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newMode;
                String modeName;
                String modeDesc;
                
                if (position == 0) {
                    newMode = AppConfig.RECORDING_MODE_AUTO;
                    modeName = "自动";
                    modeDesc = "MediaRecorder编码更稳定，MediaCodec兼容性更好，如果无法存储视频，尝试修改";
                } else if (position == 1) {
                    newMode = AppConfig.RECORDING_MODE_MEDIA_RECORDER;
                    modeName = "MediaRecorder";
                    modeDesc = "使用系统硬件编码器，兼容性好";
                } else {
                    newMode = AppConfig.RECORDING_MODE_CODEC;
                    modeName = "OpenGL+MediaCodec";
                    modeDesc = "软编码方案，解决部分设备兼容问题";
                }
                
                updateRecordingModeDescription(modeDesc);
                
                if (isInitializingRecordingMode) {
                    return;
                }
                
                if (newMode.equals(lastAppliedRecordingMode)) {
                    return;
                }
                
                lastAppliedRecordingMode = newMode;
                appConfig.setRecordingMode(newMode);
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "已切换为「" + modeName + "」模式，下次录制生效", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        String currentMode = appConfig.getRecordingMode();
        int selectedIndex = 0;
        if (AppConfig.RECORDING_MODE_MEDIA_RECORDER.equals(currentMode)) {
            selectedIndex = 1;
        } else if (AppConfig.RECORDING_MODE_CODEC.equals(currentMode)) {
            selectedIndex = 2;
        }
        recordingModeSpinner.setSelection(selectedIndex);
        
        recordingModeSpinner.post(() -> {
            isInitializingRecordingMode = false;
        });
    }
    
    /**
     * 更新录制模式描述文字
     */
    private void updateRecordingModeDescription(String desc) {
        if (recordingModeDescText != null) {
            recordingModeDescText.setText(desc);
        }
    }
    
    /**
     * 初始化存储位置配置
     */
    private void initStorageLocationConfig(View view) {
        storageLocationSpinner = view.findViewById(R.id.spinner_storage_location);
        storageLocationDescText = view.findViewById(R.id.tv_storage_location_desc);
        storageDebugButton = view.findViewById(R.id.btn_storage_debug);
        
        if (storageLocationSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingStorageLocation = true;
        lastAppliedStorageLocation = (appConfig != null) ? appConfig.getStorageLocation() : null;
        
        // 检测是否有外置SD卡
        hasExternalSdCard = StorageHelper.hasExternalSdCard(getContext());
        
        // 如果未检测到SD卡，显示调试按钮
        if (storageDebugButton != null) {
            storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
            storageDebugButton.setOnClickListener(v -> showStorageDebugInfo());
        }
        
        // 动态生成选项（简短文字，详细信息在描述中显示）
        if (hasExternalSdCard) {
            storageLocationOptions = new String[] {"内部存储", "外置SD卡"};
        } else {
            storageLocationOptions = new String[] {"内部存储", "外置SD卡（未检测到）"};
        }
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                storageLocationOptions
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        storageLocationSpinner.setAdapter(adapter);
        
        storageLocationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newLocation;
                String locationName;
                
                if (position == 0) {
                    newLocation = AppConfig.STORAGE_INTERNAL;
                    locationName = "内部存储";
                } else {
                    if (!hasExternalSdCard) {
                        if (!isInitializingStorageLocation && getContext() != null) {
                            Toast.makeText(getContext(), "未检测到外置SD卡，请先在权限设置中授予「所有文件访问权限」", Toast.LENGTH_LONG).show();
                            storageLocationSpinner.setSelection(0);
                        }
                        return;
                    }
                    newLocation = AppConfig.STORAGE_EXTERNAL_SD;
                    locationName = "外置SD卡";
                }
                
                updateStorageLocationDescription(newLocation);
                
                if (isInitializingStorageLocation) {
                    return;
                }
                
                if (newLocation.equals(lastAppliedStorageLocation)) {
                    return;
                }
                
                lastAppliedStorageLocation = newLocation;
                appConfig.setStorageLocation(newLocation);
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "存储位置已切换为「" + locationName + "」", Toast.LENGTH_SHORT).show();
                    AppLog.d("SettingsFragment", "存储位置已切换为: " + newLocation + 
                            "，路径: " + StorageHelper.getCurrentStoragePathDesc(getContext()));
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        String currentLocation = appConfig.getStorageLocation();
        int selectedIndex = 0;
        if (AppConfig.STORAGE_EXTERNAL_SD.equals(currentLocation) && hasExternalSdCard) {
            selectedIndex = 1;
        }
        storageLocationSpinner.setSelection(selectedIndex);
        
        updateStorageLocationDescription(currentLocation);
        
        storageLocationSpinner.post(() -> {
            isInitializingStorageLocation = false;
        });
    }
    
    /**
     * 更新存储位置描述文字
     */
    private void updateStorageLocationDescription(String location) {
        if (storageLocationDescText == null || getContext() == null) {
            return;
        }
        
        boolean useExternal = AppConfig.STORAGE_EXTERNAL_SD.equals(location);
        java.io.File videoDir = useExternal ? 
                StorageHelper.getVideoDir(getContext(), true) :
                StorageHelper.getVideoDir(getContext(), false);
        String path = videoDir.getAbsolutePath();
        
        // 简化路径显示
        String displayPath;
        if (path.startsWith("/storage/emulated/0/")) {
            displayPath = path.replace("/storage/emulated/0/", "内部存储/");
        } else if (path.startsWith("/storage/")) {
            int dcimIndex = path.indexOf("/DCIM/");
            if (dcimIndex > 0) {
                displayPath = "SD卡" + path.substring(dcimIndex);
            } else {
                displayPath = path;
            }
        } else {
            displayPath = path;
        }
        
        // 获取容量信息
        long availableSpace = StorageHelper.getAvailableSpace(videoDir);
        long totalSpace = StorageHelper.getTotalSpace(videoDir);
        String availableStr = StorageHelper.formatSize(availableSpace);
        String totalStr = StorageHelper.formatSize(totalSpace);
        
        storageLocationDescText.setText(displayPath + "\n可用: " + availableStr + " / 共: " + totalStr);
    }
    
    /**
     * 显示存储设备调试信息
     */
    private void showStorageDebugInfo() {
        if (getContext() == null) {
            return;
        }
        
        StringBuilder sb = new StringBuilder();
        
        // 首先检测存储权限状态
        sb.append("=== 存储权限状态 ===\n");
        
        // 检查所有文件访问权限（Android 11+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            boolean hasAllFilesAccess = android.os.Environment.isExternalStorageManager();
            sb.append("所有文件访问权限 (Android 11+): ");
            if (hasAllFilesAccess) {
                sb.append("已授权 ✓\n");
            } else {
                sb.append("未授权 ✗\n");
                sb.append("⚠️ 提示: 访问外置SD卡需要此权限！\n");
                sb.append("   请前往「权限设置」授予「所有文件访问权限」\n");
            }
        } else {
            sb.append("Android 版本低于 11，无需「所有文件访问权限」\n");
        }
        
        // 检查基础存储权限
        boolean hasStoragePermission;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            hasStoragePermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    getContext(), android.Manifest.permission.READ_MEDIA_VIDEO) 
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            sb.append("媒体文件权限 (Android 13+): ");
        } else {
            hasStoragePermission = androidx.core.content.ContextCompat.checkSelfPermission(
                    getContext(), android.Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            sb.append("存储读写权限: ");
        }
        sb.append(hasStoragePermission ? "已授权 ✓\n" : "未授权 ✗\n");
        
        sb.append("\n");
        
        // 然后显示存储设备检测信息
        List<String> debugInfo = StorageHelper.getStorageDebugInfo(getContext());
        for (String line : debugInfo) {
            sb.append(line).append("\n");
        }
        
        new android.app.AlertDialog.Builder(getContext())
                .setTitle("存储设备检测信息")
                .setMessage(sb.toString())
                .setPositiveButton("确定", null)
                .setNeutralButton("复制", (dialog, which) -> {
                    android.content.ClipboardManager clipboard = 
                            (android.content.ClipboardManager) getContext().getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("存储调试信息", sb.toString());
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(getContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show();
                })
                .show();
    }
    
    /**
     * 更新保存日志按钮的可见性（仅 Debug 开启时显示）
     */
    private void updateSaveLogsButtonVisibility(boolean visible) {
        if (saveLogsButton != null) {
            saveLogsButton.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }
    
    /**
     * 打开自定义摄像头配置界面
     */
    private void openCustomCameraConfig() {
        if (getActivity() == null) {
            return;
        }
        
        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, new CustomCameraConfigFragment());
        transaction.addToBackStack(null);
        transaction.commit();
    }
}
