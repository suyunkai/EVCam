package com.kooo.evcam;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
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
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.io.File;

/**
 * 软件设置界面 Fragment
 */
public class SettingsFragment extends Fragment {

    private SwitchMaterial debugSwitch;
    private Button saveLogsButton;
    private Button overlayPermissionButton;
    private TextView overlayStatusText;
    private Button accessibilityPermissionButton;
    private TextView accessibilityStatusText;
    private SwitchMaterial autoStartSwitch;
    private SwitchMaterial keepAliveSwitch;
    private AppConfig appConfig;
    
    // 车型配置相关
    private Spinner carModelSpinner;
    private Button customCameraConfigButton;
    private static final String[] CAR_MODEL_OPTIONS = {"银河E5", "银河L6/L7", "自定义车型"};
    private boolean isInitializingCarModel = false;  // 标志位：是否正在初始化车型配置
    private String lastAppliedCarModel = null;  // 记录上次已应用的车型，避免初始化触发

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

        // 初始化悬浮窗权限控件
        overlayPermissionButton = view.findViewById(R.id.btn_overlay_permission);
        overlayStatusText = view.findViewById(R.id.tv_overlay_status);

        // 更新悬浮窗权限状态
        updateOverlayPermissionStatus();

        // 设置悬浮窗权限按钮监听器
        overlayPermissionButton.setOnClickListener(v -> {
            if (getContext() != null) {
                if (WakeUpHelper.hasOverlayPermission(getContext())) {
                    Toast.makeText(getContext(), "已授权悬浮窗权限", Toast.LENGTH_SHORT).show();
                } else {
                    WakeUpHelper.requestOverlayPermission(getContext());
                    Toast.makeText(getContext(), "请在设置中授权悬浮窗权限", Toast.LENGTH_LONG).show();
                }
            }
        });

        // 初始化无障碍服务控件
        accessibilityPermissionButton = view.findViewById(R.id.btn_accessibility_permission);
        accessibilityStatusText = view.findViewById(R.id.tv_accessibility_status);

        // 更新无障碍服务状态
        updateAccessibilityServiceStatus();

        // 设置无障碍服务按钮监听器
        accessibilityPermissionButton.setOnClickListener(v -> {
            if (getContext() != null) {
                if (isAccessibilityServiceEnabled(getContext())) {
                    Toast.makeText(getContext(), "保活服务已启用", Toast.LENGTH_SHORT).show();
                } else {
                    openAccessibilitySettings();
                    Toast.makeText(getContext(), "请在无障碍设置中启用「电车记录仪 - 保活服务」", Toast.LENGTH_LONG).show();
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
                
                // 根据开关状态启动或停止保活任务
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

        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次返回时更新权限状态
        updateOverlayPermissionStatus();
        updateAccessibilityServiceStatus();
    }

    /**
     * 更新悬浮窗权限状态显示
     */
    private void updateOverlayPermissionStatus() {
        if (getContext() == null || overlayStatusText == null || overlayPermissionButton == null) {
            return;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            // Android 6.0 以下不需要此权限
            overlayStatusText.setText("系统版本低于 Android 6.0，无需授权");
            overlayPermissionButton.setVisibility(View.GONE);
        } else if (WakeUpHelper.hasOverlayPermission(getContext())) {
            overlayStatusText.setText("已授权 ✓");
            overlayStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            overlayPermissionButton.setText("已授权");
            overlayPermissionButton.setEnabled(false);
        } else {
            overlayStatusText.setText("未授权 - 后台钉钉命令需要此权限");
            overlayStatusText.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            overlayPermissionButton.setText("去授权");
            overlayPermissionButton.setEnabled(true);
        }
    }

    /**
     * 更新无障碍服务状态显示
     */
    private void updateAccessibilityServiceStatus() {
        if (getContext() == null || accessibilityStatusText == null || accessibilityPermissionButton == null) {
            return;
        }

        if (isAccessibilityServiceEnabled(getContext())) {
            accessibilityStatusText.setText("已启用 ✓ - 应用进程保护中");
            accessibilityStatusText.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            accessibilityPermissionButton.setText("已启用");
            accessibilityPermissionButton.setEnabled(false);
        } else {
            accessibilityStatusText.setText("未启用 - 防止应用被系统清理");
            accessibilityStatusText.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            accessibilityPermissionButton.setText("去启用");
            accessibilityPermissionButton.setEnabled(true);
        }
    }

    /**
     * 检查无障碍服务是否已启用
     */
    private boolean isAccessibilityServiceEnabled(Context context) {
        try {
            int accessibilityEnabled = Settings.Secure.getInt(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            
            if (accessibilityEnabled == 1) {
                String services = Settings.Secure.getString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                
                if (services != null) {
                    String serviceName = context.getPackageName() + "/" + KeepAliveAccessibilityService.class.getName();
                    return services.contains(serviceName);
                }
            }
        } catch (Exception e) {
            AppLog.e("SettingsFragment", "检查无障碍服务状态失败", e);
        }
        return false;
    }

    /**
     * 打开无障碍设置页面
     */
    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            AppLog.e("SettingsFragment", "打开无障碍设置失败", e);
            Toast.makeText(getContext(), "无法打开设置页面", Toast.LENGTH_SHORT).show();
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

        // 进入初始化阶段：Spinner 绑定 adapter/监听器 也可能触发一次 onItemSelected
        isInitializingCarModel = true;
        lastAppliedCarModel = (appConfig != null) ? appConfig.getCarModel() : null;
        
        // 设置下拉选择框适配器
        ArrayAdapter<String> adapter = new ArrayAdapter<>(
                getContext(),
                R.layout.spinner_item,
                CAR_MODEL_OPTIONS
        );
        adapter.setDropDownViewResource(R.layout.spinner_dropdown_item);
        carModelSpinner.setAdapter(adapter);
        
        // 设置下拉选择监听器（必须在 setSelection 之前设置）
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
                } else {
                    newModel = AppConfig.CAR_MODEL_CUSTOM;
                    modelName = "自定义车型";
                }

                // 始终更新 UI（按钮显隐）
                updateCustomConfigButtonVisibility(position == 2);

                // 初始化阶段不做“保存/提示”
                if (isInitializingCarModel) {
                    return;
                }

                // 与当前已保存车型相同：不重复写入、不提示（避免进入页面触发、避免重复选择触发）
                if (newModel.equals(lastAppliedCarModel)) {
                    return;
                }

                lastAppliedCarModel = newModel;
                appConfig.setCarModel(newModel);
                
                // 提示需要重启应用
                if (getContext() != null) {
                    Toast.makeText(getContext(), "已切换为「" + modelName + "」，重启应用后生效", Toast.LENGTH_SHORT).show();
                }
            }
            
            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // 不处理
            }
        });
        
        // 根据当前配置设置选中项（仍处于初始化状态）
        String currentModel = appConfig.getCarModel();
        int selectedIndex = 0;  // 默认银河E5
        if (AppConfig.CAR_MODEL_L7.equals(currentModel)) {
            selectedIndex = 1;
        } else if (AppConfig.CAR_MODEL_CUSTOM.equals(currentModel)) {
            selectedIndex = 2;
        }
        carModelSpinner.setSelection(selectedIndex);
        
        // 延迟重置标志位，确保 setSelection 的回调已执行完毕
        carModelSpinner.post(() -> {
            isInitializingCarModel = false;
        });
        
        // 设置自定义配置按钮点击事件
        customCameraConfigButton.setOnClickListener(v -> {
            // 打开自定义摄像头配置界面
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
        
        // 切换到 CustomCameraConfigFragment
        FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
        transaction.replace(R.id.fragment_container, new CustomCameraConfigFragment());
        transaction.addToBackStack(null);  // 允许返回
        transaction.commit();
    }
}
