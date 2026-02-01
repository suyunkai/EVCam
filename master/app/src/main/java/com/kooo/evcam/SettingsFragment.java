package com.kooo.evcam;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
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
    private Button uploadLogsButton;
    private LinearLayout logButtonsLayout;
    private SwitchMaterial autoStartSwitch;
    private SwitchMaterial autoStartRecordingSwitch;
    private SwitchMaterial screenOffRecordingSwitch;
    private LinearLayout screenOffRecordingLayout;
    // 定时保活和防止休眠已改为始终开启，无需用户设置（车机必需）
    // private SwitchMaterial keepAliveSwitch;
    // private SwitchMaterial preventSleepSwitch;
    private SwitchMaterial recordingStatsSwitch;
    private SwitchMaterial timestampWatermarkSwitch;
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
    private static final String[] CAR_MODEL_OPTIONS = {"银河E5", "银河E5-多按钮", "银河L6/L7", "银河L7-多按钮", "手机", "自定义车型"};
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
    
    // 录制摄像头选择配置相关
    private android.widget.CheckBox cbRecordCameraFront;
    private android.widget.CheckBox cbRecordCameraBack;
    private android.widget.CheckBox cbRecordCameraLeft;
    private android.widget.CheckBox cbRecordCameraRight;
    private boolean isInitializingRecordingCameraSelection = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        // 初始化控件
        debugSwitch = view.findViewById(R.id.switch_debug_to_info);
        saveLogsButton = view.findViewById(R.id.btn_save_logs);
        uploadLogsButton = view.findViewById(R.id.btn_upload_logs);
        logButtonsLayout = view.findViewById(R.id.layout_log_buttons);
        Button menuButton = view.findViewById(R.id.btn_menu);
        Button homeButton = view.findViewById(R.id.btn_home);

        // 设置菜单按钮点击事件
        menuButton.setOnClickListener(v -> {
            if (getActivity() != null) {
                DrawerLayout drawerLayout = getActivity().findViewById(R.id.drawer_layout);
                if (drawerLayout != null) {
                    drawerLayout.openDrawer(GravityCompat.START);
                }
            }
        });

        // 主页按钮 - 返回预览界面
        homeButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
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
            
            // 初始化录制摄像头选择配置
            initRecordingCameraSelectionConfig(view);
            
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

        // 设置一键上传日志按钮监听器
        uploadLogsButton.setOnClickListener(v -> {
            if (getContext() != null && appConfig != null) {
                // 检查是否已设置设备名称
                if (!appConfig.hasDeviceNickname()) {
                    // 首次上传，显示输入框
                    showDeviceNicknameInputDialog();
                } else {
                    // 已有设备名称，显示确认对话框
                    showUploadConfirmDialog(appConfig.getDeviceNickname());
                }
            }
        });

        // 初始化使用提示入口
        Button btnUsageGuide = view.findViewById(R.id.btn_usage_guide);
        btnUsageGuide.setOnClickListener(v -> showUsageGuideDialog());

        // 初始化权限设置入口
        Button btnPermissionSettings = view.findViewById(R.id.btn_permission_settings);
        btnPermissionSettings.setOnClickListener(v -> openPermissionSettings());

        // 初始化分辨率设置入口
        Button btnResolutionSettings = view.findViewById(R.id.btn_resolution_settings);
        btnResolutionSettings.setOnClickListener(v -> openResolutionSettings());

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

        // 初始化时间角标开关
        timestampWatermarkSwitch = view.findViewById(R.id.switch_timestamp_watermark);
        if (getContext() != null && appConfig != null) {
            timestampWatermarkSwitch.setChecked(appConfig.isTimestampWatermarkEnabled());
        }

        // 设置时间角标开关监听器
        timestampWatermarkSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setTimestampWatermarkEnabled(isChecked);
                String message = isChecked ? "时间角标已开启" : "时间角标已关闭";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
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

        // 初始化启动自动录制开关
        autoStartRecordingSwitch = view.findViewById(R.id.switch_auto_start_recording);
        if (getContext() != null && appConfig != null) {
            autoStartRecordingSwitch.setChecked(appConfig.isAutoStartRecording());
        }

        // 初始化息屏录制开关
        screenOffRecordingSwitch = view.findViewById(R.id.switch_screen_off_recording);
        screenOffRecordingLayout = view.findViewById(R.id.layout_screen_off_recording);
        if (getContext() != null && appConfig != null) {
            screenOffRecordingSwitch.setChecked(appConfig.isScreenOffRecordingEnabled());
            // 根据启动自动录制的状态决定是否显示息屏录制开关
            updateScreenOffRecordingVisibility(appConfig.isAutoStartRecording());
        }

        // 设置启动自动录制开关监听器
        autoStartRecordingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setAutoStartRecording(isChecked);
                String message = isChecked ? "启动自动录制已启用，下次启动生效" : "启动自动录制已禁用";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
                
                // 更新息屏录制开关的可见性
                updateScreenOffRecordingVisibility(isChecked);
            }
        });

        // 设置息屏录制开关监听器
        screenOffRecordingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (getContext() != null && appConfig != null) {
                appConfig.setScreenOffRecordingEnabled(isChecked);
                String message = isChecked ? "息屏录制已启用，息屏时将继续录制" : "息屏录制已禁用，息屏10秒后将自动停止录制";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                AppLog.d("SettingsFragment", message);
            }
        });

        // 定时保活已改为始终开启（车机必需），无需设置开关
        // 隐藏定时保活开关
        View keepAliveSwitch = view.findViewById(R.id.switch_keep_alive);
        if (keepAliveSwitch != null) {
            View parent = (View) keepAliveSwitch.getParent();
            if (parent != null) {
                parent.setVisibility(View.GONE);
            }
        }
        // 确保定时保活任务已启动
        if (getContext() != null) {
            KeepAliveManager.startKeepAliveWork(getContext());
        }

        // 防止休眠已改为始终开启（车机必需），无需设置开关
        // WakeLock 在 CameraForegroundService 中自动获取
        // 隐藏防止休眠开关
        View preventSleepLayout = view.findViewById(R.id.switch_prevent_sleep);
        if (preventSleepLayout != null) {
            // 隐藏整个布局（包括开关和说明文字）
            View parent = (View) preventSleepLayout.getParent();
            if (parent != null) {
                parent.setVisibility(View.GONE);
            }
        }

        // 初始化悬浮窗设置
        initFloatingWindowSettings(view);

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
        if (getActivity() == null || getContext() == null) return;
        
        // 根据屏幕密度动态设置二维码尺寸
        // 低DPI大屏设备使用更大尺寸，高DPI设备使用适中尺寸
        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        float density = dm.density;
        int screenWidthPx = dm.widthPixels;
        
        // 计算二维码尺寸（像素）
        // density: mdpi=1.0, hdpi=1.5, xhdpi=2.0, xxhdpi=3.0, xxxhdpi=4.0
        int qrcodeSizePx;
        if (density <= 1.0f) {
            // mdpi 或更低密度（大屏低DPI设备）：使用屏幕宽度的25%
            qrcodeSizePx = (int) (screenWidthPx * 0.25f);
        } else if (density <= 1.5f) {
            // hdpi：使用屏幕宽度的22%
            qrcodeSizePx = (int) (screenWidthPx * 0.22f);
        } else if (density <= 2.0f) {
            // xhdpi：使用屏幕宽度的20%
            qrcodeSizePx = (int) (screenWidthPx * 0.20f);
        } else {
            // xxhdpi 及以上（高密度设备）：使用屏幕宽度的18%
            qrcodeSizePx = (int) (screenWidthPx * 0.18f);
        }
        
        // 设置ImageView尺寸
        android.view.ViewGroup.LayoutParams params = imageView.getLayoutParams();
        params.width = qrcodeSizePx;
        params.height = qrcodeSizePx;
        imageView.setLayoutParams(params);
        
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
    
    /**
     * 更新息屏录制开关的可见性
     * 仅当启动自动录制开启时才显示
     */
    private void updateScreenOffRecordingVisibility(boolean autoStartRecordingEnabled) {
        if (screenOffRecordingLayout != null) {
            screenOffRecordingLayout.setVisibility(autoStartRecordingEnabled ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        
        // 重新检测 U盘（可能在授权后返回或U盘插拔）- 异步执行避免卡顿
        if (getContext() != null) {
            final Context context = getContext();
            final String currentLocation = appConfig != null ? appConfig.getStorageLocation() : AppConfig.STORAGE_INTERNAL;
            
            // 异步检测 U盘
            new Thread(() -> {
                boolean newHasSdCard = StorageHelper.hasExternalSdCard(context);
                
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        if (getContext() == null) return;
                        
                        if (newHasSdCard != hasExternalSdCard) {
                            hasExternalSdCard = newHasSdCard;
                            if (storageDebugButton != null) {
                                storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
                            }
                            
                            // 更新 Spinner 选项文字
                            if (storageLocationSpinner != null) {
                                if (hasExternalSdCard) {
                                    storageLocationOptions = new String[] {"内部存储", "U盘"};
                                } else {
                                    storageLocationOptions = new String[] {"内部存储", "U盘（未检测到）"};
                                }
                                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                        getContext(),
                                        R.layout.spinner_item,
                                        storageLocationOptions
                                );
                                adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                                
                                isInitializingStorageLocation = true;
                                storageLocationSpinner.setAdapter(adapter);
                                
                                // 恢复用户之前的选择
                                int selectedIndex = AppConfig.STORAGE_EXTERNAL_SD.equals(currentLocation) ? 1 : 0;
                                storageLocationSpinner.setSelection(selectedIndex);
                                storageLocationSpinner.post(() -> isInitializingStorageLocation = false);
                                
                                if (hasExternalSdCard) {
                                    Toast.makeText(getContext(), "检测到U盘", Toast.LENGTH_SHORT).show();
                                }
                            }
                        }
                        
                        // 始终更新描述文字（可能U盘状态变化或空间变化）
                        updateStorageLocationDescriptionAsync(currentLocation);
                    });
                }
            }).start();
            
            // 更新存储占用大小显示（已经是异步的）
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
                    newModel = AppConfig.CAR_MODEL_E5_MULTI;
                    modelName = "银河E5-多按钮";
                } else if (position == 2) {
                    newModel = AppConfig.CAR_MODEL_L7;
                    modelName = "银河L6/L7";
                } else if (position == 3) {
                    newModel = AppConfig.CAR_MODEL_L7_MULTI;
                    modelName = "银河L7-多按钮";
                } else if (position == 4) {
                    newModel = AppConfig.CAR_MODEL_PHONE;
                    modelName = "手机";
                } else {
                    newModel = AppConfig.CAR_MODEL_CUSTOM;
                    modelName = "自定义车型";
                }

                // 仅自定义车型显示配置按钮
                updateCustomConfigButtonVisibility(position == 5);

                if (isInitializingCarModel) {
                    return;
                }

                if (newModel.equals(lastAppliedCarModel)) {
                    return;
                }

                lastAppliedCarModel = newModel;
                appConfig.setCarModel(newModel);
                
                // 切换车型时重置录制摄像头选择为全选（避免之前的设置导致无法录制）
                appConfig.resetRecordingCameraSelection();
                
                // 更新录制摄像头选择的 UI（摄像头数量由 AppConfig.getCameraCount() 自动根据车型返回）
                updateRecordingCameraSelectionUI();
                
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
        if (AppConfig.CAR_MODEL_E5_MULTI.equals(currentModel)) {
            selectedIndex = 1;
        } else if (AppConfig.CAR_MODEL_L7.equals(currentModel)) {
            selectedIndex = 2;
        } else if (AppConfig.CAR_MODEL_L7_MULTI.equals(currentModel)) {
            selectedIndex = 3;
        } else if (AppConfig.CAR_MODEL_PHONE.equals(currentModel)) {
            selectedIndex = 4;
        } else if (AppConfig.CAR_MODEL_CUSTOM.equals(currentModel)) {
            selectedIndex = 5;
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
                    // 显示当前实际使用的模式
                    String actualMode = appConfig.shouldUseCodecRecording() ? "MediaCodec" : "MediaRecorder";
                    modeDesc = "MediaRecorder编码更稳定，MediaCodec兼容性更好，如果无法存储视频，尝试修改\n当前自动选择：" + actualMode;
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
     * 初始化录制摄像头选择配置
     */
    private void initRecordingCameraSelectionConfig(View view) {
        cbRecordCameraFront = view.findViewById(R.id.cb_record_camera_front);
        cbRecordCameraBack = view.findViewById(R.id.cb_record_camera_back);
        cbRecordCameraLeft = view.findViewById(R.id.cb_record_camera_left);
        cbRecordCameraRight = view.findViewById(R.id.cb_record_camera_right);
        
        if (cbRecordCameraFront == null || getContext() == null || appConfig == null) {
            return;
        }
        
        isInitializingRecordingCameraSelection = true;
        
        // 根据摄像头数量显示/隐藏对应的 CheckBox
        int cameraCount = appConfig.getCameraCount();
        
        // 前摄像头（1摄及以上都有）
        cbRecordCameraFront.setVisibility(cameraCount >= 1 ? View.VISIBLE : View.GONE);
        cbRecordCameraFront.setText(appConfig.getRecordingCameraDisplayName("front", 1));
        cbRecordCameraFront.setChecked(appConfig.isRecordingCameraEnabled("front"));
        
        // 后摄像头（2摄及以上才有）
        cbRecordCameraBack.setVisibility(cameraCount >= 2 ? View.VISIBLE : View.GONE);
        cbRecordCameraBack.setText(appConfig.getRecordingCameraDisplayName("back", 2));
        cbRecordCameraBack.setChecked(appConfig.isRecordingCameraEnabled("back"));
        
        // 左摄像头（4摄才有）
        cbRecordCameraLeft.setVisibility(cameraCount >= 4 ? View.VISIBLE : View.GONE);
        cbRecordCameraLeft.setText(appConfig.getRecordingCameraDisplayName("left", 3));
        cbRecordCameraLeft.setChecked(appConfig.isRecordingCameraEnabled("left"));
        
        // 右摄像头（4摄才有）
        cbRecordCameraRight.setVisibility(cameraCount >= 4 ? View.VISIBLE : View.GONE);
        cbRecordCameraRight.setText(appConfig.getRecordingCameraDisplayName("right", 4));
        cbRecordCameraRight.setChecked(appConfig.isRecordingCameraEnabled("right"));
        
        // 设置监听器
        android.widget.CompoundButton.OnCheckedChangeListener listener = (buttonView, isChecked) -> {
            if (isInitializingRecordingCameraSelection) {
                return;
            }
            
            // 检查是否至少有一个勾选
            if (!isChecked && !hasAtLeastOneRecordingCameraEnabled(buttonView)) {
                // 恢复勾选状态
                buttonView.setChecked(true);
                Toast.makeText(getContext(), "至少需要选择一个摄像头", Toast.LENGTH_SHORT).show();
                return;
            }
            
            // 保存设置
            String position = getPositionFromCheckBox(buttonView);
            if (position != null) {
                appConfig.setRecordingCameraEnabled(position, isChecked);
                String cameraName = ((android.widget.CheckBox) buttonView).getText().toString();
                String message = isChecked ? "已启用「" + cameraName + "」录制" : "已禁用「" + cameraName + "」录制";
                Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
            }
        };
        
        cbRecordCameraFront.setOnCheckedChangeListener(listener);
        cbRecordCameraBack.setOnCheckedChangeListener(listener);
        cbRecordCameraLeft.setOnCheckedChangeListener(listener);
        cbRecordCameraRight.setOnCheckedChangeListener(listener);
        
        // 延迟结束初始化标记
        cbRecordCameraFront.post(() -> {
            isInitializingRecordingCameraSelection = false;
        });
    }
    
    /**
     * 更新录制摄像头选择的 UI（车型切换时调用）
     */
    private void updateRecordingCameraSelectionUI() {
        if (cbRecordCameraFront == null || getContext() == null || appConfig == null) {
            return;
        }
        
        isInitializingRecordingCameraSelection = true;
        
        // 根据摄像头数量显示/隐藏对应的 CheckBox
        int cameraCount = appConfig.getCameraCount();
        
        // 前摄像头（1摄及以上都有）
        cbRecordCameraFront.setVisibility(cameraCount >= 1 ? View.VISIBLE : View.GONE);
        cbRecordCameraFront.setText(appConfig.getRecordingCameraDisplayName("front", 1));
        cbRecordCameraFront.setChecked(appConfig.isRecordingCameraEnabled("front"));
        
        // 后摄像头（2摄及以上才有）
        cbRecordCameraBack.setVisibility(cameraCount >= 2 ? View.VISIBLE : View.GONE);
        cbRecordCameraBack.setText(appConfig.getRecordingCameraDisplayName("back", 2));
        cbRecordCameraBack.setChecked(appConfig.isRecordingCameraEnabled("back"));
        
        // 左摄像头（4摄才有）
        cbRecordCameraLeft.setVisibility(cameraCount >= 4 ? View.VISIBLE : View.GONE);
        cbRecordCameraLeft.setText(appConfig.getRecordingCameraDisplayName("left", 3));
        cbRecordCameraLeft.setChecked(appConfig.isRecordingCameraEnabled("left"));
        
        // 右摄像头（4摄才有）
        cbRecordCameraRight.setVisibility(cameraCount >= 4 ? View.VISIBLE : View.GONE);
        cbRecordCameraRight.setText(appConfig.getRecordingCameraDisplayName("right", 4));
        cbRecordCameraRight.setChecked(appConfig.isRecordingCameraEnabled("right"));
        
        // 延迟结束初始化标记
        cbRecordCameraFront.post(() -> {
            isInitializingRecordingCameraSelection = false;
        });
    }
    
    /**
     * 检查除了当前按钮外，是否还有至少一个摄像头被勾选
     */
    private boolean hasAtLeastOneRecordingCameraEnabled(View excludeButton) {
        if (cbRecordCameraFront != excludeButton && cbRecordCameraFront.getVisibility() == View.VISIBLE && cbRecordCameraFront.isChecked()) {
            return true;
        }
        if (cbRecordCameraBack != excludeButton && cbRecordCameraBack.getVisibility() == View.VISIBLE && cbRecordCameraBack.isChecked()) {
            return true;
        }
        if (cbRecordCameraLeft != excludeButton && cbRecordCameraLeft.getVisibility() == View.VISIBLE && cbRecordCameraLeft.isChecked()) {
            return true;
        }
        if (cbRecordCameraRight != excludeButton && cbRecordCameraRight.getVisibility() == View.VISIBLE && cbRecordCameraRight.isChecked()) {
            return true;
        }
        return false;
    }
    
    /**
     * 根据 CheckBox 获取对应的摄像头位置
     */
    private String getPositionFromCheckBox(View checkBox) {
        if (checkBox == cbRecordCameraFront) {
            return "front";
        } else if (checkBox == cbRecordCameraBack) {
            return "back";
        } else if (checkBox == cbRecordCameraLeft) {
            return "left";
        } else if (checkBox == cbRecordCameraRight) {
            return "right";
        }
        return null;
    }
    
    /**
     * 初始化存储位置配置
     * 注意：U盘检测涉及文件系统操作，需要异步执行避免卡顿
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
        
        // 先使用默认状态初始化 UI（假设没有U盘，避免主线程阻塞）
        hasExternalSdCard = false;
        
        // 设置调试按钮点击事件（先显示，检测完后可能隐藏）
        if (storageDebugButton != null) {
            storageDebugButton.setVisibility(View.VISIBLE);
            storageDebugButton.setOnClickListener(v -> showStorageDebugInfo());
        }
        
        // 初始化 Spinner（使用默认选项，后续异步更新）
        storageLocationOptions = new String[] {"内部存储", "U盘（检测中...）"};
        
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
                    newLocation = AppConfig.STORAGE_EXTERNAL_SD;
                    locationName = "U盘";
                    // 如果U盘不可用，显示警告但仍然允许用户选择
                    if (!hasExternalSdCard && !isInitializingStorageLocation && getContext() != null) {
                        Toast.makeText(getContext(), "当前未检测到U盘，录制将临时使用内部存储", Toast.LENGTH_LONG).show();
                    }
                }
                
                updateStorageLocationDescriptionAsync(newLocation);
                
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
                    // 异步获取路径描述
                    new Thread(() -> {
                        String pathDesc = StorageHelper.getCurrentStoragePathDesc(getContext());
                        AppLog.d("SettingsFragment", "存储位置已切换为: " + newLocation + "，路径: " + pathDesc);
                    }).start();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
        
        String currentLocation = appConfig.getStorageLocation();
        int selectedIndex = 0;
        // 保持用户选择的存储位置，即使U盘不可用也显示选中状态
        if (AppConfig.STORAGE_EXTERNAL_SD.equals(currentLocation)) {
            selectedIndex = 1;
        }
        storageLocationSpinner.setSelection(selectedIndex);
        
        // 显示加载中状态
        if (storageLocationDescText != null) {
            storageLocationDescText.setText("正在检测存储设备...");
        }
        
        // 异步检测 U盘并更新 UI
        final String finalCurrentLocation = currentLocation;
        final int finalSelectedIndex = selectedIndex;
        new Thread(() -> {
            // 在后台线程执行耗时的 I/O 操作
            boolean detected = StorageHelper.hasExternalSdCard(getContext());
            
            // 回到主线程更新 UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (getContext() == null || storageLocationSpinner == null) {
                        return;
                    }
                    
                    hasExternalSdCard = detected;
                    
                    // 更新调试按钮可见性
                    if (storageDebugButton != null) {
                        storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
                    }
                    
                    // 更新 Spinner 选项文字
                    if (hasExternalSdCard) {
                        storageLocationOptions = new String[] {"内部存储", "U盘"};
                    } else {
                        storageLocationOptions = new String[] {"内部存储", "U盘（未检测到）"};
                    }
                    
                    ArrayAdapter<String> newAdapter = new ArrayAdapter<>(
                            getContext(),
                            R.layout.spinner_item,
                            storageLocationOptions
                    );
                    newAdapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                    storageLocationSpinner.setAdapter(newAdapter);
                    
                    // 恢复用户选择
                    storageLocationSpinner.setSelection(finalSelectedIndex);
                    
                    // 异步更新描述文字
                    updateStorageLocationDescriptionAsync(finalCurrentLocation);
                    
                    storageLocationSpinner.post(() -> {
                        isInitializingStorageLocation = false;
                    });
                });
            }
        }).start();
    }
    
    /**
     * 更新存储位置描述文字（同步版本，仅在已有数据时使用）
     * @deprecated 请使用 {@link #updateStorageLocationDescriptionAsync(String)} 避免主线程阻塞
     */
    @Deprecated
    private void updateStorageLocationDescription(String location) {
        // 直接调用异步版本
        updateStorageLocationDescriptionAsync(location);
    }
    
    /**
     * 异步更新存储位置描述文字
     * 避免在主线程执行文件系统 I/O 操作导致卡顿
     */
    private void updateStorageLocationDescriptionAsync(String location) {
        if (storageLocationDescText == null || getContext() == null) {
            return;
        }
        
        // 先显示加载状态
        storageLocationDescText.setText("正在获取存储信息...");
        
        final Context context = getContext();
        final boolean useExternal = AppConfig.STORAGE_EXTERNAL_SD.equals(location);
        final boolean isFallback = useExternal && !hasExternalSdCard;
        
        new Thread(() -> {
            // 在后台线程执行耗时的 I/O 操作
            java.io.File videoDir = useExternal ? 
                    StorageHelper.getVideoDir(context, true) :
                    StorageHelper.getVideoDir(context, false);
            String path = videoDir.getAbsolutePath();
            
            // 获取内部存储根路径用于判断
            String internalRoot = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
            
            // 简化路径显示
            String displayPath;
            if (path.startsWith(internalRoot + "/")) {
                // 是内部存储
                displayPath = path.replace(internalRoot + "/", "内部存储/");
            } else if (path.startsWith("/storage/emulated/")) {
                // 其他 emulated 路径也是内部存储
                displayPath = "内部存储" + path.substring(path.indexOf("/", "/storage/emulated/".length()));
            } else if (path.matches("/storage/[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}/.*")) {
                // XXXX-XXXX 格式是 SD 卡
                int dcimIndex = path.indexOf("/DCIM/");
                if (dcimIndex > 0) {
                    displayPath = "U盘" + path.substring(dcimIndex);
                } else {
                    displayPath = "U盘/" + path.substring(path.lastIndexOf("/") + 1);
                }
            } else {
                // 其他路径原样显示
                displayPath = path;
            }
            
            // 获取容量信息
            long availableSpace = StorageHelper.getAvailableSpace(videoDir);
            long totalSpace = StorageHelper.getTotalSpace(videoDir);
            String availableStr = StorageHelper.formatSize(availableSpace);
            String totalStr = StorageHelper.formatSize(totalSpace);
            
            // 构建最终显示文字
            final String finalText;
            if (isFallback) {
                finalText = "⚠ U盘不可用，临时使用内部存储\n" + displayPath + "\n可用: " + availableStr + " / 共: " + totalStr;
            } else {
                finalText = displayPath + "\n可用: " + availableStr + " / 共: " + totalStr;
            }
            
            // 回到主线程更新 UI
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    if (storageLocationDescText != null) {
                        storageLocationDescText.setText(finalText);
                    }
                });
            }
        }).start();
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
                sb.append("⚠️ 提示: 访问U盘需要此权限！\n");
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
        
        // 显示当前自定义路径
        String customPath = appConfig.getCustomSdCardPath();
        sb.append("\n=== 自定义U盘路径 ===\n");
        if (customPath != null) {
            sb.append("当前设置: " + customPath + "\n");
            java.io.File customDir = new java.io.File(customPath);
            sb.append("路径状态: " + (customDir.exists() ? "存在" : "不存在") + 
                    ", " + (customDir.canWrite() ? "可写" : "不可写") + "\n");
        } else {
            sb.append("未设置（使用自动检测）\n");
        }
        
        sb.append("\n");
        
        // 然后显示存储设备检测信息
        List<String> debugInfo = StorageHelper.getStorageDebugInfo(getContext());
        for (String line : debugInfo) {
            sb.append(line).append("\n");
        }
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
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
                .setNegativeButton("手动设置路径", (dialog, which) -> {
                    showManualSdCardPathDialog();
                })
                .show();
    }
    
    /**
     * 显示手动设置U盘路径对话框
     */
    private void showManualSdCardPathDialog() {
        if (getContext() == null) return;
        
        android.widget.EditText input = new android.widget.EditText(getContext());
        input.setHint("例如: /storage/ABCD-1234");
        input.setSingleLine(true);
        // 适配夜间模式
        input.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        input.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        input.setBackgroundResource(R.drawable.edit_text_background);
        
        // 显示当前设置的路径
        String currentPath = appConfig.getCustomSdCardPath();
        if (currentPath != null) {
            input.setText(currentPath);
        }
        
        // 设置边距
        android.widget.FrameLayout container = new android.widget.FrameLayout(getContext());
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 48;
        params.rightMargin = 48;
        input.setLayoutParams(params);
        container.addView(input);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("手动设置U盘路径")
                .setMessage("如果自动检测失败，你可以手动输入U盘的挂载路径。\n\n" +
                        "常见格式：/storage/XXXX-XXXX（十六进制ID）\n\n" +
                        "留空表示使用自动检测。")
                .setView(container)
                .setPositiveButton("保存", (dialog, which) -> {
                    String path = input.getText().toString().trim();
                    if (path.isEmpty()) {
                        appConfig.setCustomSdCardPath(null);
                        Toast.makeText(getContext(), "已清除自定义路径，使用自动检测", Toast.LENGTH_SHORT).show();
                    } else {
                        java.io.File testDir = new java.io.File(path);
                        if (!testDir.exists()) {
                            Toast.makeText(getContext(), "警告：路径不存在，但已保存", Toast.LENGTH_LONG).show();
                        } else if (!testDir.isDirectory()) {
                            Toast.makeText(getContext(), "警告：路径不是目录，但已保存", Toast.LENGTH_LONG).show();
                        } else if (!testDir.canWrite()) {
                            Toast.makeText(getContext(), "警告：路径不可写，但已保存", Toast.LENGTH_LONG).show();
                        } else {
                            Toast.makeText(getContext(), "U盘路径已设置", Toast.LENGTH_SHORT).show();
                        }
                        appConfig.setCustomSdCardPath(path);
                    }
                    
                    // 重新检测并更新UI
                    hasExternalSdCard = StorageHelper.hasExternalSdCard(getContext());
                    if (storageDebugButton != null) {
                        storageDebugButton.setVisibility(hasExternalSdCard ? View.GONE : View.VISIBLE);
                    }
                    if (hasExternalSdCard && storageLocationSpinner != null) {
                        storageLocationOptions = new String[] {"内部存储", "U盘"};
                        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                                getContext(),
                                R.layout.spinner_item,
                                storageLocationOptions
                        );
                        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
                        storageLocationSpinner.setAdapter(adapter);
                    }
                    String currentLocation = appConfig.getStorageLocation();
                    updateStorageLocationDescriptionAsync(currentLocation);
                })
                .setNegativeButton("取消", null)
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
     * 更新日志按钮区域的可见性（仅 Debug 开启时显示）
     */
    private void updateSaveLogsButtonVisibility(boolean visible) {
        if (logButtonsLayout != null) {
            logButtonsLayout.setVisibility(visible ? View.VISIBLE : View.GONE);
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
    
    /**
     * 打开分辨率设置界面
     */
    private void openResolutionSettings() {
        if (getActivity() == null) {
            return;
        }
        
        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, new ResolutionSettingsFragment());
        transaction.addToBackStack(null);
        transaction.commit();
    }
    
    // ==================== 日志上传相关方法 ====================
    
    /**
     * 显示设备名称输入对话框（首次上传时）
     */
    private void showDeviceNicknameInputDialog() {
        if (getContext() == null) return;
        
        EditText inputEditText = new EditText(getContext());
        inputEditText.setInputType(InputType.TYPE_CLASS_TEXT);
        inputEditText.setHint("例如：张三的银河E5");
        inputEditText.setPadding(48, 32, 48, 32);
        // 适配夜间模式
        inputEditText.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        inputEditText.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        inputEditText.setBackgroundResource(R.drawable.edit_text_background);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("设置设备识别名称")
                .setMessage("请输入一个便于识别的名称，用于区分不同用户的日志：")
                .setView(inputEditText)
                .setPositiveButton("确认", (dialog, which) -> {
                    String nickname = inputEditText.getText().toString().trim();
                    if (nickname.isEmpty()) {
                        Toast.makeText(getContext(), "名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // 显示二次确认
                    showNicknameConfirmDialog(nickname);
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 显示设备名称二次确认对话框（首次设置名称后）
     */
    private void showNicknameConfirmDialog(String nickname) {
        if (getContext() == null) return;
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("确认设备名称")
                .setMessage("您输入的设备名称是：\n\n「" + nickname + "」\n\n确认使用此名称吗？")
                .setPositiveButton("确认", (dialog, which) -> {
                    // 保存名称，然后显示上传确认框
                    if (appConfig != null) {
                        appConfig.setDeviceNickname(nickname);
                    }
                    showUploadConfirmDialog(nickname);
                })
                .setNegativeButton("重新输入", (dialog, which) -> {
                    // 重新显示输入框
                    showDeviceNicknameInputDialog();
                })
                .show();
    }
    
    /**
     * 显示上传确认对话框（包含名称确认和问题描述输入）
     */
    private void showUploadConfirmDialog(String nickname) {
        if (getContext() == null) return;
        
        // 创建包含名称显示和问题描述输入的布局
        LinearLayout layout = new LinearLayout(getContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(48, 24, 48, 8);
        
        // 名称显示 - 适配夜间模式
        TextView nicknameLabel = new TextView(getContext());
        nicknameLabel.setText("上传身份：「" + nickname + "」");
        nicknameLabel.setTextSize(16);
        nicknameLabel.setPadding(0, 0, 0, 24);
        nicknameLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        layout.addView(nicknameLabel);
        
        // 日志选择标签
        TextView logTypeLabel = new TextView(getContext());
        logTypeLabel.setText("选择日志：");
        logTypeLabel.setTextSize(14);
        logTypeLabel.setPadding(0, 0, 0, 8);
        logTypeLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        layout.addView(logTypeLabel);
        
        // 日志选择 RadioGroup
        RadioGroup logTypeGroup = new RadioGroup(getContext());
        logTypeGroup.setOrientation(RadioGroup.VERTICAL);
        logTypeGroup.setPadding(0, 0, 0, 16);
        
        // 本次运行日志选项
        RadioButton currentLogRadio = new RadioButton(getContext());
        currentLogRadio.setId(View.generateViewId());
        currentLogRadio.setText("本次运行日志");
        currentLogRadio.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        currentLogRadio.setChecked(true);
        logTypeGroup.addView(currentLogRadio);
        
        // 上次运行日志选项
        RadioButton previousLogRadio = new RadioButton(getContext());
        previousLogRadio.setId(View.generateViewId());
        boolean hasPrevious = AppLog.hasPreviousSessionLogs(getContext());
        if (hasPrevious) {
            String prevInfo = AppLog.getPreviousSessionLogInfo(getContext());
            previousLogRadio.setText("上次运行日志" + (prevInfo != null ? "\n  " + prevInfo : ""));
            previousLogRadio.setEnabled(true);
        } else {
            previousLogRadio.setText("上次运行日志（无可用日志）");
            previousLogRadio.setEnabled(false);
        }
        previousLogRadio.setTextColor(ContextCompat.getColor(getContext(), 
                hasPrevious ? R.color.text_primary : R.color.text_secondary));
        logTypeGroup.addView(previousLogRadio);
        
        layout.addView(logTypeGroup);
        
        // 问题描述标签 - 适配夜间模式
        TextView descLabel = new TextView(getContext());
        descLabel.setText("问题描述：");
        descLabel.setTextSize(14);
        descLabel.setPadding(0, 0, 0, 8);
        descLabel.setTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        layout.addView(descLabel);
        
        // 问题描述输入框 - 适配夜间模式
        EditText inputEditText = new EditText(getContext());
        inputEditText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        inputEditText.setMinLines(3);
        inputEditText.setMaxLines(6);
        inputEditText.setHint("请描述遇到的问题...");
        inputEditText.setTextColor(ContextCompat.getColor(getContext(), R.color.text_primary));
        inputEditText.setHintTextColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        inputEditText.setBackgroundResource(R.drawable.edit_text_background);
        layout.addView(inputEditText);
        
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("上传日志")
                .setView(layout)
                .setPositiveButton("上传", (dialog, which) -> {
                    String problemDesc = inputEditText.getText().toString().trim();
                    if (problemDesc.isEmpty()) {
                        problemDesc = "（用户未填写问题描述）";
                    }
                    // 判断选择了哪个日志
                    boolean uploadPreviousSession = previousLogRadio.isChecked();
                    performLogUpload(nickname, problemDesc, uploadPreviousSession);
                })
                .setNeutralButton("修改名称", (dialog, which) -> {
                    showDeviceNicknameInputDialog();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 执行日志上传（默认上传本次运行日志）
     */
    private void performLogUpload(String deviceNickname, String problemDescription) {
        performLogUpload(deviceNickname, problemDescription, false);
    }
    
    /**
     * 执行日志上传
     * @param uploadPreviousSession 是否上传上次运行的日志
     */
    private void performLogUpload(String deviceNickname, String problemDescription, boolean uploadPreviousSession) {
        if (getContext() == null) return;
        
        // 禁用按钮防止重复点击
        uploadLogsButton.setEnabled(false);
        uploadLogsButton.setText("上传中...");
        
        String logType = uploadPreviousSession ? "上次运行" : "本次运行";
        
        AppLog.uploadLogsToServer(getContext(), deviceNickname, problemDescription, uploadPreviousSession, new AppLog.UploadCallback() {
            @Override
            public void onSuccess() {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        uploadLogsButton.setEnabled(true);
                        uploadLogsButton.setText("一键上传");
                        Toast.makeText(getContext(), "作者已收到" + logType + "日志", Toast.LENGTH_LONG).show();
                    });
                }
            }

            @Override
            public void onError(String error) {
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        uploadLogsButton.setEnabled(true);
                        uploadLogsButton.setText("一键上传");
                        Toast.makeText(getContext(), "上传失败: " + error, Toast.LENGTH_SHORT).show();
                    });
                }
            }
        });
    }
}
