package com.kooo.evcam;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * 权限设置界面 Fragment
 */
public class PermissionSettingsFragment extends Fragment {

    // 基础权限
    private TextView tvCameraStatus;
    private Button btnCameraPermission;
    private TextView tvMicrophoneStatus;
    private Button btnMicrophonePermission;
    private TextView tvStorageStatus;
    private Button btnStoragePermission;
    
    // 通知权限（Android 13+）
    private LinearLayout layoutNotificationPermission;
    private TextView tvNotificationStatus;
    private Button btnNotificationPermission;
    
    // 高级权限
    private LinearLayout layoutAllFilesPermission;
    private TextView tvAllFilesStatus;
    private Button btnAllFilesPermission;
    private TextView tvOverlayStatus;
    private Button btnOverlayPermission;
    private TextView tvAccessibilityStatus;
    private Button btnAccessibilityPermission;
    private TextView tvBatteryStatus;
    private Button btnBatteryPermission;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_permission_settings, container, false);

        // 返回按钮
        Button btnBack = view.findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> {
            if (getActivity() != null) {
                getActivity().getSupportFragmentManager().popBackStack();
            }
        });

        // 初始化控件
        initViews(view);
        
        // 设置点击事件
        setupClickListeners();
        
        // 更新权限状态
        updateAllPermissionStatus();

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
        // 基础权限
        tvCameraStatus = view.findViewById(R.id.tv_camera_status);
        btnCameraPermission = view.findViewById(R.id.btn_camera_permission);
        tvMicrophoneStatus = view.findViewById(R.id.tv_microphone_status);
        btnMicrophonePermission = view.findViewById(R.id.btn_microphone_permission);
        tvStorageStatus = view.findViewById(R.id.tv_storage_status);
        btnStoragePermission = view.findViewById(R.id.btn_storage_permission);
        
        // 通知权限
        layoutNotificationPermission = view.findViewById(R.id.layout_notification_permission);
        tvNotificationStatus = view.findViewById(R.id.tv_notification_status);
        btnNotificationPermission = view.findViewById(R.id.btn_notification_permission);
        
        // 高级权限
        layoutAllFilesPermission = view.findViewById(R.id.layout_all_files_permission);
        tvAllFilesStatus = view.findViewById(R.id.tv_all_files_status);
        btnAllFilesPermission = view.findViewById(R.id.btn_all_files_permission);
        tvOverlayStatus = view.findViewById(R.id.tv_overlay_status);
        btnOverlayPermission = view.findViewById(R.id.btn_overlay_permission);
        tvAccessibilityStatus = view.findViewById(R.id.tv_accessibility_status);
        btnAccessibilityPermission = view.findViewById(R.id.btn_accessibility_permission);
        tvBatteryStatus = view.findViewById(R.id.tv_battery_status);
        btnBatteryPermission = view.findViewById(R.id.btn_battery_permission);
        
        // 根据系统版本显示/隐藏某些选项
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            layoutNotificationPermission.setVisibility(View.VISIBLE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutAllFilesPermission.setVisibility(View.VISIBLE);
        }
    }

    private void setupClickListeners() {
        // 相机权限
        btnCameraPermission.setOnClickListener(v -> openAppSettings());
        
        // 麦克风权限
        btnMicrophonePermission.setOnClickListener(v -> openAppSettings());
        
        // 存储权限
        btnStoragePermission.setOnClickListener(v -> openAppSettings());
        
        // 通知权限
        btnNotificationPermission.setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                openNotificationSettings();
            } else {
                openAppSettings();
            }
        });
        
        // 所有文件访问权限
        btnAllFilesPermission.setOnClickListener(v -> requestAllFilesAccessPermission());
        
        // 悬浮窗权限
        btnOverlayPermission.setOnClickListener(v -> {
            if (getContext() != null) {
                WakeUpHelper.requestOverlayPermission(getContext());
                Toast.makeText(getContext(), "请开启悬浮窗权限", Toast.LENGTH_LONG).show();
            }
        });
        
        // 无障碍服务
        btnAccessibilityPermission.setOnClickListener(v -> openAccessibilitySettings());
        
        // 电池优化
        btnBatteryPermission.setOnClickListener(v -> {
            if (getContext() != null) {
                WakeUpHelper.requestIgnoreBatteryOptimizations(getContext());
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // 每次返回时更新权限状态
        updateAllPermissionStatus();
    }

    /**
     * 更新所有权限状态
     */
    private void updateAllPermissionStatus() {
        if (getContext() == null) return;
        
        updateCameraPermissionStatus();
        updateMicrophonePermissionStatus();
        updateStoragePermissionStatus();
        updateNotificationPermissionStatus();
        updateAllFilesPermissionStatus();
        updateOverlayPermissionStatus();
        updateAccessibilityServiceStatus();
        updateBatteryOptimizationStatus();
    }

    /**
     * 更新相机权限状态
     */
    private void updateCameraPermissionStatus() {
        if (getContext() == null) return;
        
        boolean granted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.CAMERA) 
                == PackageManager.PERMISSION_GRANTED;
        
        if (granted) {
            tvCameraStatus.setText("已授权 ✓");
            tvCameraStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            btnCameraPermission.setText("已授权");
            btnCameraPermission.setEnabled(false);
        } else {
            tvCameraStatus.setText("未授权 - 核心功能必需");
            tvCameraStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
            btnCameraPermission.setText("去授权");
            btnCameraPermission.setEnabled(true);
        }
    }

    /**
     * 更新麦克风权限状态
     */
    private void updateMicrophonePermissionStatus() {
        if (getContext() == null) return;
        
        boolean granted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.RECORD_AUDIO) 
                == PackageManager.PERMISSION_GRANTED;
        
        if (granted) {
            tvMicrophoneStatus.setText("已授权 ✓");
            tvMicrophoneStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            btnMicrophonePermission.setText("已授权");
            btnMicrophonePermission.setEnabled(false);
        } else {
            tvMicrophoneStatus.setText("未授权 - 录制视频时无声音");
            tvMicrophoneStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            btnMicrophonePermission.setText("去授权");
            btnMicrophonePermission.setEnabled(true);
        }
    }

    /**
     * 更新存储权限状态
     */
    private void updateStoragePermissionStatus() {
        if (getContext() == null) return;
        
        boolean granted;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 使用媒体权限
            granted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_MEDIA_VIDEO) 
                    == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10-12
            granted = true; // 分区存储，不需要特殊权限
        } else {
            // Android 9 及以下
            granted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE) 
                    == PackageManager.PERMISSION_GRANTED;
        }
        
        if (granted) {
            tvStorageStatus.setText("已授权 ✓");
            tvStorageStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            btnStoragePermission.setText("已授权");
            btnStoragePermission.setEnabled(false);
        } else {
            tvStorageStatus.setText("未授权 - 无法保存视频和照片");
            tvStorageStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
            btnStoragePermission.setText("去授权");
            btnStoragePermission.setEnabled(true);
        }
    }

    /**
     * 更新通知权限状态（Android 13+）
     */
    private void updateNotificationPermissionStatus() {
        if (getContext() == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        
        boolean granted = ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) 
                == PackageManager.PERMISSION_GRANTED;
        
        if (granted) {
            tvNotificationStatus.setText("已授权 ✓");
            tvNotificationStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            btnNotificationPermission.setText("已授权");
            btnNotificationPermission.setEnabled(false);
        } else {
            tvNotificationStatus.setText("未授权 - 无法显示录制状态通知");
            tvNotificationStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            btnNotificationPermission.setText("去授权");
            btnNotificationPermission.setEnabled(true);
        }
    }

    /**
     * 更新所有文件访问权限状态（Android 11+）
     */
    private void updateAllFilesPermissionStatus() {
        if (getContext() == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return;
        
        boolean granted = android.os.Environment.isExternalStorageManager();
        
        if (granted) {
            tvAllFilesStatus.setText("已授权 ✓");
            tvAllFilesStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            btnAllFilesPermission.setText("已授权");
            btnAllFilesPermission.setEnabled(false);
        } else {
            tvAllFilesStatus.setText("未授权 - 无法存储到U盘");
            tvAllFilesStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            btnAllFilesPermission.setText("去授权");
            btnAllFilesPermission.setEnabled(true);
        }
    }

    /**
     * 更新悬浮窗权限状态
     */
    private void updateOverlayPermissionStatus() {
        if (getContext() == null) return;
        
        boolean granted = WakeUpHelper.hasOverlayPermission(getContext());
        
        if (granted) {
            tvOverlayStatus.setText("已授权 ✓");
            tvOverlayStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            btnOverlayPermission.setText("已授权");
            btnOverlayPermission.setEnabled(false);
        } else {
            tvOverlayStatus.setText("未授权 - 悬浮窗和后台唤醒不可用");
            tvOverlayStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            btnOverlayPermission.setText("去授权");
            btnOverlayPermission.setEnabled(true);
        }
    }

    /**
     * 更新无障碍服务状态
     */
    private void updateAccessibilityServiceStatus() {
        if (getContext() == null) return;
        
        boolean enabled = isAccessibilityServiceEnabled(getContext());
        
        if (enabled) {
            tvAccessibilityStatus.setText("已启用 ✓");
            tvAccessibilityStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            btnAccessibilityPermission.setText("已启用");
            btnAccessibilityPermission.setEnabled(false);
        } else {
            tvAccessibilityStatus.setText("未启用 - 应用可能被系统清理");
            tvAccessibilityStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            btnAccessibilityPermission.setText("去启用");
            btnAccessibilityPermission.setEnabled(true);
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
            AppLog.e("PermissionSettings", "检查无障碍服务状态失败", e);
        }
        return false;
    }

    /**
     * 更新电池优化状态
     */
    private void updateBatteryOptimizationStatus() {
        if (getContext() == null) return;
        
        boolean ignored = WakeUpHelper.isIgnoringBatteryOptimizations(getContext());
        
        if (ignored) {
            tvBatteryStatus.setText("已关闭优化 ✓");
            tvBatteryStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            btnBatteryPermission.setText("已设置");
            btnBatteryPermission.setEnabled(false);
        } else {
            tvBatteryStatus.setText("优化中 - 应用可能被系统休眠");
            tvBatteryStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            btnBatteryPermission.setText("去设置");
            btnBatteryPermission.setEnabled(true);
        }
    }

    /**
     * 打开应用设置页面
     */
    private void openAppSettings() {
        if (getContext() == null) return;
        
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getContext().getPackageName()));
            startActivity(intent);
            Toast.makeText(getContext(), "请在权限列表中授予所需权限", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLog.e("PermissionSettings", "打开应用设置失败", e);
            Toast.makeText(getContext(), "无法打开设置页面，请使用第三方权限管理工具设置", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 打开通知设置页面
     */
    private void openNotificationSettings() {
        if (getContext() == null) return;
        
        try {
            Intent intent = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
            intent.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            startActivity(intent);
            Toast.makeText(getContext(), "请开启通知权限", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            openAppSettings();
        }
    }

    /**
     * 请求所有文件访问权限
     */
    private void requestAllFilesAccessPermission() {
        if (getContext() == null) return;
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                intent.setData(Uri.parse("package:" + getContext().getPackageName()));
                startActivity(intent);
                Toast.makeText(getContext(), "请开启「允许访问所有文件」", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    startActivity(intent);
                    Toast.makeText(getContext(), "请找到本应用并开启权限", Toast.LENGTH_LONG).show();
                } catch (Exception e2) {
                    AppLog.e("PermissionSettings", "无法打开权限设置页面", e2);
                    Toast.makeText(getContext(), "无法打开设置页面，请使用第三方权限管理工具设置", Toast.LENGTH_SHORT).show();
                }
            }
        }
    }

    /**
     * 打开无障碍设置页面
     */
    private void openAccessibilitySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            Toast.makeText(getContext(), "请找到「电车记录仪 - 保活服务」并启用", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLog.e("PermissionSettings", "打开无障碍设置失败", e);
            Toast.makeText(getContext(), "无法打开设置页面，请使用第三方权限管理工具设置", Toast.LENGTH_SHORT).show();
        }
    }
}
