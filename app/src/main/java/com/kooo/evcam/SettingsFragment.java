package com.kooo.evcam;

import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
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
    private SwitchMaterial recordingStatsSwitch;
    private AppConfig appConfig;
    
    // 悬浮窗相关
    private SwitchMaterial floatingWindowSwitch;
    private LinearLayout floatingWindowSettingsLayout;
    private Spinner floatingWindowSizeSpinner;
    private SeekBar floatingWindowAlphaSeekBar;
    private TextView floatingWindowAlphaText;
    private static final String[] FLOATING_SIZE_OPTIONS = {"超小", "特小", "小", "中", "大", "超大", "特大", "特特大", "PLUS大", "MAX大"};
    private boolean isInitializingFloatingSize = false;
    private int lastAppliedFloatingSize = -1;  // 记录上次应用的大小，避免重复触发
    
    // 车型配置相关
    private Spinner carModelSpinner;
    private Button customCameraConfigButton;
    private static final String[] CAR_MODEL_OPTIONS = {"银河E5", "银河L6/L7", "银河L7-多按钮", "手机", "自定义车型"};
    private boolean isInitializingCarModel = false;
    private String lastAppliedCarModel = null;
    
    // 录制模式配置相关
    private Spinner recordingModeSpinner;
    private TextView recordingModeDescText;
    private static final String[] RECORDING_MODE_OPTIONS = {"自动（推荐）", "MediaRecorder", "OpenGL+MediaCodec"};
    private boolean isInitializingRecordingMode = false;
    private String lastAppliedRecordingMode = null;
    
    // 分段时长配置相关
    private Spinner segmentDurationSpinner;
    private static final String[] SEGMENT_DURATION_OPTIONS = {"1分钟", "3分钟", "5分钟"};
    private boolean isInitializingSegmentDuration = false;
    private int lastAppliedSegmentDuration = -1;
    
    // 存储位置配置相关
    private Spinner storageLocationSpinner;
    private TextView storageLocationDescText;
    private Button storageDebugButton;
    private String[] storageLocationOptions;
    private boolean isInitializingStorageLocation = false;
    private String lastAppliedStorageLocation = null;
    private boolean hasExternalSdCard = false;
    
    // 存储清理配置相关
    private EditText videoStorageLimitEdit;
    private EditText photoStorageLimitEdit;
    private TextView videoUsedSizeText;
    private TextView photoUsedSizeText;
    private boolean isInitializingStorageCleanup = false;

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
            
            // 初始化分段时长配置
            initSegmentDurationConfig(view);
            
            // 初始化存储位置配置
            initStorageLocationConfig(view);
            
            // 初始化存储清理配置
            initStorageCleanupConfig(view);
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

        // 初始化使用提示入口
        Button btnUsageGuide = view.findViewById(R.id.btn_usage_guide);
        btnUsageGuide.setOnClickListener(v -> showUsageGuideDialog());

        // 初始化权限设置入口
        Button btnPermissionSettings = view.findViewById(R.id.btn_permission_settings);
        btnPermissionSettings.setOnClickListener(v -> openPermissionSettings());

        // 初始化录制状态显示开关
        recordingStatsSwitch = view.findViewById(R.id.switch_recording_stats);
        if (getContext() != null && appConfig != null) {
            recordingStatsSwitch.setChecked(appConfig.isRecordingStatsEnabled());
        }

        // 设置录制状态显示开关监听器
        recordingStatsSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setRecordingStatsEnabled(isChecked);
                String message = isChecked ? "录制状态显示已开启" : "录制状态显示已关闭";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
                
                // 通知 MainActivity 刷新设置
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).refreshRecordingStatsSettings();
                }
            }
        });

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
     * 显示使用提示对话框
     */
    private void showUsageGuideDialog() {
        if (getContext() == null) return;

        // 创建自定义对话框
        android.app.Dialog dialog = new android.app.Dialog(getContext());
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_first_launch_guide);
        dialog.setCancelable(true);

        // 设置对话框窗口属性
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            // 设置背景透明（让圆角生效）
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            // 设置对话框宽度
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
            window.setAttributes(params);
        }

        // 加载二维码图片
        android.widget.ImageView ivQrcode = dialog.findViewById(R.id.iv_qrcode);
        loadQrcodeImage(ivQrcode);

        // 设置确认按钮点击事件
        dialog.findViewById(R.id.btn_confirm).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /**
     * 加载打赏二维码图片（URL经过混淆处理）
     */
    private void loadQrcodeImage(android.widget.ImageView imageView) {
        if (getActivity() == null) return;
        
        // URL混淆存储，防止被轻易修改
        // 原始URL经过Base64编码后分段存储
        final String[] p = {
            "aHR0cHM6Ly9ldmNhbS5jaGF0d2Vi", // 第一段
            "LmNsb3VkLzE3Njk0NzcxOTc4NTUu", // 第二段  
            "anBn"                           // 第三段
        };
        
        new Thread(() -> {
            try {
                // 组合并解码URL
                String encoded = p[0] + p[1] + p[2];
                String url = new String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT));
                
                // 下载图片
                java.net.URL imageUrl = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) imageUrl.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoInput(true);
                conn.connect();
                
                java.io.InputStream is = conn.getInputStream();
                final android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
                conn.disconnect();
                
                // 在主线程更新UI
                if (bitmap != null && getActivity() != null) {
                    getActivity().runOnUiThread(() -> imageView.setImageBitmap(bitmap));
                }
            } catch (Exception e) {
                AppLog.e("SettingsFragment", "加载二维码图片失败: " + e.getMessage());
            }
        }).start();
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
        
        // 记录当前保存的大小值
        lastAppliedFloatingSize = appConfig.getFloatingWindowSize();
        
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
                int sizeDp;
                String sizeName;
                switch (position) {
                    case 0:
                        sizeDp = AppConfig.FLOATING_SIZE_TINY;
                        sizeName = "超小";
                        break;
                    case 1:
                        sizeDp = AppConfig.FLOATING_SIZE_EXTRA_SMALL;
                        sizeName = "特小";
                        break;
                    case 2:
                        sizeDp = AppConfig.FLOATING_SIZE_SMALL;
                        sizeName = "小";
                        break;
                    case 3:
                        sizeDp = AppConfig.FLOATING_SIZE_MEDIUM;
                        sizeName = "中";
                        break;
                    case 4:
                        sizeDp = AppConfig.FLOATING_SIZE_LARGE;
                        sizeName = "大";
                        break;
                    case 5:
                        sizeDp = AppConfig.FLOATING_SIZE_EXTRA_LARGE;
                        sizeName = "超大";
                        break;
                    case 6:
                        sizeDp = AppConfig.FLOATING_SIZE_HUGE;
                        sizeName = "特大";
                        break;
                    case 7:
                        sizeDp = AppConfig.FLOATING_SIZE_GIANT;
                        sizeName = "特特大";
                        break;
                    case 8:
                        sizeDp = AppConfig.FLOATING_SIZE_PLUS;
                        sizeName = "PLUS大";
                        break;
                    default:
                        sizeDp = AppConfig.FLOATING_SIZE_MAX;
                        sizeName = "MAX大";
                        break;
                }
                
                // 初始化阶段不处理
                if (isInitializingFloatingSize) {
                    return;
                }
                
                // 与上次应用的值相同，不重复处理
                if (sizeDp == lastAppliedFloatingSize) {
                    return;
                }
                
                lastAppliedFloatingSize = sizeDp;
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
        
        // 根据当前保存的尺寸确定选中项
        int currentSize = appConfig.getFloatingWindowSize();
        int selectedIndex;
        if (currentSize <= AppConfig.FLOATING_SIZE_TINY) {
            selectedIndex = 0;  // 超小
        } else if (currentSize <= AppConfig.FLOATING_SIZE_EXTRA_SMALL) {
            selectedIndex = 1;  // 特小
        } else if (currentSize <= AppConfig.FLOATING_SIZE_SMALL) {
            selectedIndex = 2;  // 小
        } else if (currentSize <= AppConfig.FLOATING_SIZE_MEDIUM) {
            selectedIndex = 3;  // 中
        } else if (currentSize <= AppConfig.FLOATING_SIZE_LARGE) {
            selectedIndex = 4;  // 大
        } else if (currentSize <= AppConfig.FLOATING_SIZE_EXTRA_LARGE) {
            selectedIndex = 5;  // 超大
        } else if (currentSize <= AppConfig.FLOATING_SIZE_HUGE) {
            selectedIndex = 6;  // 特大
        } else if (currentSize <= AppConfig.FLOATING_SIZE_GIANT) {
            selectedIndex = 7;  // 特特大
        } else if (currentSize <= AppConfig.FLOATING_SIZE_PLUS) {
            selectedIndex = 8;  // PLUS大
        } else {
            selectedIndex = 9;  // MAX大
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
            
            // 更新存储占用大小显示
            updateStorageUsedSizeDisplay();
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
                    newModel = AppConfig.CAR_MODEL_L7_MULTI;
                    modelName = "银河L7-多按钮";
                } else if (position == 3) {
                    newModel = AppConfig.CAR_MODEL_PHONE;
                    modelName = "手机";
                } else {
                    newModel = AppConfig.CAR_MODEL_CUSTOM;
                    modelName = "自定义车型";
                }

                // 仅自定义车型显示配置按钮
                updateCustomConfigButtonVisibility(position == 4);

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
        } else if (AppConfig.CAR_MODEL_L7_MULTI.equals(currentModel)) {
            selectedIndex = 2;
        } else if (AppConfig.CAR_MODEL_PHONE.equals(currentModel)) {
            selectedIndex = 3;
        } else if (AppConfig.CAR_MODEL_CUSTOM.equals(currentModel)) {
            selectedIndex = 4;
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
     * 初始化分段时长配置
     */
    private void initSegmentDurationConfig(View view) {
        segmentDurationSpinner = view.findViewById(R.id.spinner_segment_duration);
        
        if (segmentDurationSpinner == null || getContext() == null) {
            return;
        }
        
        isInitializingSegmentDuration = true;
        lastAppliedSegmentDuration = (appConfig != null) ? appConfig.getSegmentDurationMinutes() : AppConfig.SEGMENT_DURATION_1_MIN;
        
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                SEGMENT_DURATION_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        segmentDurationSpinner.setAdapter(adapter);
        
        segmentDurationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int newDuration;
                String durationName;
                
                if (position == 0) {
                    newDuration = AppConfig.SEGMENT_DURATION_1_MIN;
                    durationName = "1分钟";
                } else if (position == 1) {
                    newDuration = AppConfig.SEGMENT_DURATION_3_MIN;
                    durationName = "3分钟";
                } else {
                    newDuration = AppConfig.SEGMENT_DURATION_5_MIN;
                    durationName = "5分钟";
                }
                
                if (isInitializingSegmentDuration) {
                    return;
                }
                
                if (newDuration == lastAppliedSegmentDuration) {
                    return;
                }
                
                lastAppliedSegmentDuration = newDuration;
                appConfig.setSegmentDurationMinutes(newDuration);
                
                if (getContext() != null) {
                    Toast.makeText(getContext(), "分段时长已设置为「" + durationName + "」，下次录制生效", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        // 根据当前配置设置选中项
        int currentDuration = appConfig.getSegmentDurationMinutes();
        int selectedIndex = 0;  // 默认1分钟
        if (currentDuration == AppConfig.SEGMENT_DURATION_3_MIN) {
            selectedIndex = 1;
        } else if (currentDuration == AppConfig.SEGMENT_DURATION_5_MIN) {
            selectedIndex = 2;
        }
        segmentDurationSpinner.setSelection(selectedIndex);
        
        segmentDurationSpinner.post(() -> {
            isInitializingSegmentDuration = false;
        });
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
     * 初始化存储清理配置
     */
    private void initStorageCleanupConfig(View view) {
        videoStorageLimitEdit = view.findViewById(R.id.et_video_storage_limit);
        photoStorageLimitEdit = view.findViewById(R.id.et_photo_storage_limit);
        videoUsedSizeText = view.findViewById(R.id.tv_video_used_size);
        photoUsedSizeText = view.findViewById(R.id.tv_photo_used_size);
        
        if (videoStorageLimitEdit == null || photoStorageLimitEdit == null || getContext() == null) {
            return;
        }
        
        isInitializingStorageCleanup = true;
        
        // 加载当前设置
        int videoLimit = appConfig.getVideoStorageLimitGb();
        int photoLimit = appConfig.getPhotoStorageLimitGb();
        
        // 设置初始值（0显示为空）
        if (videoLimit > 0) {
            videoStorageLimitEdit.setText(String.valueOf(videoLimit));
        } else {
            videoStorageLimitEdit.setText("");
        }
        
        if (photoLimit > 0) {
            photoStorageLimitEdit.setText(String.valueOf(photoLimit));
        } else {
            photoStorageLimitEdit.setText("");
        }
        
        // 更新当前占用大小显示
        updateStorageUsedSizeDisplay();
        
        // 添加文本变化监听器 - 视频
        videoStorageLimitEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (isInitializingStorageCleanup) {
                    return;
                }
                
                int limit = 0;
                String text = s.toString().trim();
                if (!text.isEmpty()) {
                    try {
                        limit = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        // 忽略无效输入
                    }
                }
                
                appConfig.setVideoStorageLimitGb(limit);
                AppLog.d("SettingsFragment", "视频存储限制已设置为: " + limit + " GB");
                
                // 通知 MainActivity 重启清理任务
                notifyStorageCleanupConfigChanged();
            }
        });
        
        // 添加文本变化监听器 - 图片
        photoStorageLimitEdit.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            
            @Override
            public void afterTextChanged(Editable s) {
                if (isInitializingStorageCleanup) {
                    return;
                }
                
                int limit = 0;
                String text = s.toString().trim();
                if (!text.isEmpty()) {
                    try {
                        limit = Integer.parseInt(text);
                    } catch (NumberFormatException e) {
                        // 忽略无效输入
                    }
                }
                
                appConfig.setPhotoStorageLimitGb(limit);
                AppLog.d("SettingsFragment", "图片存储限制已设置为: " + limit + " GB");
                
                // 通知 MainActivity 重启清理任务
                notifyStorageCleanupConfigChanged();
            }
        });
        
        // 延迟结束初始化标记
        videoStorageLimitEdit.post(() -> {
            isInitializingStorageCleanup = false;
        });
    }
    
    /**
     * 更新存储占用大小显示
     */
    private void updateStorageUsedSizeDisplay() {
        if (getContext() == null) {
            return;
        }
        
        // 在后台线程计算大小，避免阻塞UI
        new Thread(() -> {
            StorageCleanupManager cleanupManager = new StorageCleanupManager(getContext());
            long videoSize = cleanupManager.getVideoUsedSize();
            long photoSize = cleanupManager.getPhotoUsedSize();
            
            String videoSizeStr = "已用: " + StorageHelper.formatSize(videoSize);
            String photoSizeStr = "已用: " + StorageHelper.formatSize(photoSize);
            
            // 回到主线程更新UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (videoUsedSizeText != null) {
                        videoUsedSizeText.setText(videoSizeStr);
                    }
                    if (photoUsedSizeText != null) {
                        photoUsedSizeText.setText(photoSizeStr);
                    }
                });
            }
        }).start();
    }
    
    /**
     * 通知 MainActivity 存储清理配置已更改
     */
    private void notifyStorageCleanupConfigChanged() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).restartStorageCleanupTask();
        }
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
