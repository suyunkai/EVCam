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
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

/**
 * 权限设置界面 Fragment
 */
public class PermissionSettingsFragment extends Fragment {

    // ADB 一键获取
    private Button btnAdbGrantAll;
    private ScrollView scrollAdbLog;
    private TextView tvAdbLog;
    private AdbPermissionHelper adbHelper;
    private boolean isAdbRunning = false;
    private boolean autoScrollAdbLog = true;

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
    private TextView tvUsageStatsStatus;
    private Button btnUsageStatsPermission;
    private TextView tvAllFilesStatus;
    private Button btnAllFilesPermission;
    private TextView tvOverlayStatus;
    private Button btnOverlayPermission;
    private TextView tvAccessibilityStatus;
    private Button btnAccessibilityPermission;
    private TextView tvBatteryStatus;
    private Button btnBatteryPermission;

    // 系统白名单（E245）
    private Button btnSystemWhitelist;
    private TextView tvWhitelistStatus;
    private ScrollView scrollWhitelistLog;
    private TextView tvWhitelistLog;
    private SystemWhitelistHelper whitelistHelper;
    private boolean isWhitelistRunning = false;
    private boolean autoScrollWhitelistLog = true;

    // 恢复系统白名单
    private Button btnRestoreWhitelist;
    private ScrollView scrollRestoreLog;
    private TextView tvRestoreLog;
    private boolean isRestoreRunning = false;
    private boolean autoScrollRestoreLog = true;

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
        // ADB 一键获取
        btnAdbGrantAll = view.findViewById(R.id.btn_adb_grant_all);
        scrollAdbLog = view.findViewById(R.id.scroll_adb_log);
        tvAdbLog = view.findViewById(R.id.tv_adb_log);
        // 触摸日志区域时：1.阻止外层ScrollView拦截，让日志可滑动 2.停止自动滚动
        scrollAdbLog.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                autoScrollAdbLog = false;
            }
            return false;
        });

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
        tvUsageStatsStatus = view.findViewById(R.id.tv_usage_stats_status);
        btnUsageStatsPermission = view.findViewById(R.id.btn_usage_stats_permission);
        
        // 系统白名单（E245）
        btnSystemWhitelist = view.findViewById(R.id.btn_system_whitelist);
        tvWhitelistStatus = view.findViewById(R.id.tv_whitelist_status);
        scrollWhitelistLog = view.findViewById(R.id.scroll_whitelist_log);
        tvWhitelistLog = view.findViewById(R.id.tv_whitelist_log);
        // 触摸日志区域时：1.阻止外层ScrollView拦截，让日志可滑动 2.停止自动滚动
        scrollWhitelistLog.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                autoScrollWhitelistLog = false;
            }
            return false;
        });

        // 恢复系统白名单
        btnRestoreWhitelist = view.findViewById(R.id.btn_restore_whitelist);
        scrollRestoreLog = view.findViewById(R.id.scroll_restore_log);
        tvRestoreLog = view.findViewById(R.id.tv_restore_log);
        scrollRestoreLog.setOnTouchListener((v, event) -> {
            v.getParent().requestDisallowInterceptTouchEvent(true);
            if (event.getAction() == MotionEvent.ACTION_DOWN || event.getAction() == MotionEvent.ACTION_MOVE) {
                autoScrollRestoreLog = false;
            }
            return false;
        });

        // 根据系统版本显示/隐藏某些选项
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            layoutNotificationPermission.setVisibility(View.VISIBLE);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            layoutAllFilesPermission.setVisibility(View.VISIBLE);
        }
    }

    private void setupClickListeners() {
        // ADB 一键获取权限
        btnAdbGrantAll.setOnClickListener(v -> startAdbGrant());

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
        
        // 使用情况访问权限
        btnUsageStatsPermission.setOnClickListener(v -> openUsageStatsSettings());

        // 电池优化
        btnBatteryPermission.setOnClickListener(v -> {
            if (getContext() != null) {
                WakeUpHelper.requestIgnoreBatteryOptimizations(getContext());
            }
        });
        
        // 系统白名单（E245）
        btnSystemWhitelist.setOnClickListener(v -> showWhitelistRiskDialog());

        // 恢复系统白名单
        btnRestoreWhitelist.setOnClickListener(v -> showRestoreConfirmDialog());
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
        updateUsageStatsPermissionStatus();
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
     * 更新使用情况访问权限状态
     */
    private void updateUsageStatsPermissionStatus() {
        if (getContext() == null) return;

        boolean granted = hasUsageStatsPermission(getContext());

        if (granted) {
            tvUsageStatsStatus.setText("已授权 ✓");
            tvUsageStatsStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
            btnUsageStatsPermission.setText("已授权");
            btnUsageStatsPermission.setEnabled(false);
        } else {
            tvUsageStatsStatus.setText("未授权 - 全景影像避让不可用");
            tvUsageStatsStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark, null));
            btnUsageStatsPermission.setText("去授权");
            btnUsageStatsPermission.setEnabled(true);
        }
    }

    /**
     * 检查使用情况访问权限
     */
    private boolean hasUsageStatsPermission(Context context) {
        android.app.AppOpsManager appOps = (android.app.AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
        int mode = appOps.unsafeCheckOpNoThrow("android:get_usage_stats",
                android.os.Process.myUid(), context.getPackageName());
        return mode == android.app.AppOpsManager.MODE_ALLOWED;
    }

    /**
     * 打开使用情况访问权限设置页面
     */
    private void openUsageStatsSettings() {
        if (getContext() == null) return;

        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            startActivity(intent);
            Toast.makeText(getContext(), "请找到本应用并开启使用情况访问权限", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            AppLog.e("PermissionSettings", "打开使用情况访问设置失败", e);
            Toast.makeText(getContext(), "无法打开设置页面", Toast.LENGTH_SHORT).show();
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

    // ==================== ADB 一键获取权限 ====================

    /**
     * 启动 ADB 一键获取权限
     */
    private void startAdbGrant() {
        if (isAdbRunning) return;
        if (getContext() == null) return;

        isAdbRunning = true;
        autoScrollAdbLog = true;
        btnAdbGrantAll.setEnabled(false);
        btnAdbGrantAll.setText("正在执行...");
        scrollAdbLog.setVisibility(View.VISIBLE);
        tvAdbLog.setText("");

        if (adbHelper == null) {
            adbHelper = new AdbPermissionHelper(getContext());
        }

        adbHelper.grantAllPermissions(new AdbPermissionHelper.Callback() {
            @Override
            public void onLog(String message) {
                if (getContext() == null) return;
                tvAdbLog.append(message + "\n");
                if (autoScrollAdbLog) {
                    scrollAdbLog.post(() -> scrollAdbLog.fullScroll(View.FOCUS_DOWN));
                }
            }

            @Override
            public void onComplete(boolean allSuccess) {
                isAdbRunning = false;
                btnAdbGrantAll.setEnabled(true);
                btnAdbGrantAll.setText("一键获取权限");
                // 刷新所有权限状态显示
                updateAllPermissionStatus();
            }
        });
    }

    // ==================== E245 系统白名单配置 ====================

    /**
     * 显示风险提醒对话框
     */
    private void showWhitelistRiskDialog() {
        if (getContext() == null) return;

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("风险提醒")
                .setMessage("此操作将修改车机系统分区的配置文件，请仔细阅读：\n\n"
                        + "1. 仅适用于银河E5（E245）车机\n"
                        + "2. 需要设备已打开USB调试\n"
                        + "3. 将修改 system 和 vendor 分区的 3 个 XML 文件\n"
                        + "4. 修改前会自动备份原文件到 /sdcard/evcam_backup/\n"
                        + "5. 修改完成后需要重启车机才能生效\n"
                        + "6. 本脚本理论上不会对车机造成危害，但出现任何问题均请自行承担后果\n"
                        + "7. 如果设备不是 E245，脚本会自动检测并中止。\n\n"
                        + "确认要继续执行吗？")
                .setPositiveButton("确认执行", (dialog, which) -> startWhitelistSetup())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 启动系统白名单配置
     */
    private void startWhitelistSetup() {
        if (isWhitelistRunning) return;
        if (getContext() == null) return;

        isWhitelistRunning = true;
        autoScrollWhitelistLog = true;
        btnSystemWhitelist.setEnabled(false);
        btnSystemWhitelist.setText("正在执行...");
        scrollWhitelistLog.setVisibility(View.VISIBLE);
        tvWhitelistLog.setText("");

        if (whitelistHelper == null) {
            whitelistHelper = new SystemWhitelistHelper(getContext());
        }

        whitelistHelper.executeWhitelistSetup(new SystemWhitelistHelper.Callback() {
            @Override
            public void onLog(String message) {
                if (getContext() == null) return;
                tvWhitelistLog.append(message + "\n");
                if (autoScrollWhitelistLog) {
                    scrollWhitelistLog.post(() -> scrollWhitelistLog.fullScroll(View.FOCUS_DOWN));
                }
            }

            @Override
            public void onComplete(boolean success) {
                isWhitelistRunning = false;
                btnSystemWhitelist.setEnabled(true);
                btnSystemWhitelist.setText("一键配置");

                if (getContext() == null) return;

                if (success) {
                    tvWhitelistStatus.setText("配置成功 - 请重启车机使配置生效");
                    tvWhitelistStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
                } else {
                    tvWhitelistStatus.setText("配置失败 - 请查看日志了解详情");
                    tvWhitelistStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
                }
            }
        });
    }

    // ==================== E245 恢复系统白名单 ====================

    /**
     * 显示恢复确认对话框
     */
    private void showRestoreConfirmDialog() {
        if (getContext() == null) return;

        new MaterialAlertDialogBuilder(getContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("恢复确认")
                .setMessage("此操作将从备份恢复车机系统白名单配置：\n\n"
                        + "1. 恢复后 EVCam 的白名单配置将被移除\n"
                        + "2. 系统配置文件将还原为修改前的状态\n"
                        + "3. 需要重启车机才能生效\n"
                        + "4. 如果之前「一键配置」导致全景影像等功能异常，恢复后应恢复正常\n\n"
                        + "确认要恢复吗？")
                .setPositiveButton("确认恢复", (dialog, which) -> startWhitelistRestore())
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 启动系统白名单恢复
     */
    private void startWhitelistRestore() {
        if (isRestoreRunning) return;
        if (getContext() == null) return;

        isRestoreRunning = true;
        autoScrollRestoreLog = true;
        btnRestoreWhitelist.setEnabled(false);
        btnRestoreWhitelist.setText("正在恢复...");
        scrollRestoreLog.setVisibility(View.VISIBLE);
        tvRestoreLog.setText("");

        if (whitelistHelper == null) {
            whitelistHelper = new SystemWhitelistHelper(getContext());
        }

        whitelistHelper.executeWhitelistRestore(new SystemWhitelistHelper.Callback() {
            @Override
            public void onLog(String message) {
                if (getContext() == null) return;
                tvRestoreLog.append(message + "\n");
                if (autoScrollRestoreLog) {
                    scrollRestoreLog.post(() -> scrollRestoreLog.fullScroll(View.FOCUS_DOWN));
                }
            }

            @Override
            public void onComplete(boolean success) {
                isRestoreRunning = false;
                btnRestoreWhitelist.setEnabled(true);
                btnRestoreWhitelist.setText("恢复系统白名单");

                if (getContext() == null) return;

                if (success) {
                    tvWhitelistStatus.setText("已恢复 - 请重启车机使配置生效");
                    tvWhitelistStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark, null));
                } else {
                    tvWhitelistStatus.setText("恢复失败 - 请查看日志了解详情");
                    tvWhitelistStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark, null));
                }
            }
        });
    }
}
