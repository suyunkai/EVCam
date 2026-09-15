package com.kooo.evcam.heartbeat;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.MainActivity;
import com.kooo.evcam.R;

/**
 * 心跳推图配置界面
 */
public class HeartbeatFragment extends Fragment implements HeartbeatManager.HeartbeatListener {
    private static final String TAG = "HeartbeatFragment";
    
    // UI 组件
    private Button btnMenu, btnHome;
    private EditText etServerUrl, etSecretKey;
    private ImageButton btnToggleKeyVisibility;
    private RadioGroup rgInterval;
    private RadioButton rbInterval30, rbInterval60, rbInterval120, rbInterval300;
    private RadioGroup rgImageQuality;
    private RadioButton rbQuality100k, rbQuality500k, rbQuality1m, rbQualityNoCompress;
    private SwitchCompat switchScreenOnPush, switchScreenOffPush;
    private SwitchCompat switchAutoStart;
    private TextView tvVehicleId;
    private Button btnCopyId;
    private Button btnSaveConfig, btnStartService, btnStopService, btnTest, btnResetStats;
    private TextView tvStatus, tvCameraCount, tvLastUpload, tvStatistics;
    
    private HeartbeatConfig config;
    private boolean isKeyVisible = false;
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_heartbeat, container, false);
    }
    
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        loadConfig();
        setupListeners();
        
        // 注册监听器
        if (getActivity() instanceof MainActivity) {
            HeartbeatManager manager = ((MainActivity) getActivity()).getHeartbeatManager();
            if (manager != null) {
                manager.setListener(this);
            }
        }
    }
    
    @Override
    public void onResume() {
        super.onResume();
        // 立即更新一次
        updateStatusDisplay();
        // 延迟再更新一次，确保相机状态已就绪
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            if (isAdded()) {
                updateStatusDisplay();
            }
        }, 1000);
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 移除监听器
        if (getActivity() instanceof MainActivity) {
            HeartbeatManager manager = ((MainActivity) getActivity()).getHeartbeatManager();
            if (manager != null) {
                manager.setListener(null);
            }
        }
    }
    
    private void initViews(View view) {
        btnMenu = view.findViewById(R.id.btn_menu);
        btnHome = view.findViewById(R.id.btn_home);
        etServerUrl = view.findViewById(R.id.et_server_url);
        etSecretKey = view.findViewById(R.id.et_secret_key);
        btnToggleKeyVisibility = view.findViewById(R.id.btn_toggle_key_visibility);
        rgInterval = view.findViewById(R.id.rg_interval);
        rbInterval30 = view.findViewById(R.id.rb_interval_30);
        rbInterval60 = view.findViewById(R.id.rb_interval_60);
        rbInterval120 = view.findViewById(R.id.rb_interval_120);
        rbInterval300 = view.findViewById(R.id.rb_interval_300);
        rgImageQuality = view.findViewById(R.id.rg_image_quality);
        rbQuality100k = view.findViewById(R.id.rb_quality_100k);
        rbQuality500k = view.findViewById(R.id.rb_quality_500k);
        rbQuality1m = view.findViewById(R.id.rb_quality_1m);
        rbQualityNoCompress = view.findViewById(R.id.rb_quality_no_compress);
        switchScreenOnPush = view.findViewById(R.id.switch_screen_on_push);
        switchScreenOffPush = view.findViewById(R.id.switch_screen_off_push);
        switchAutoStart = view.findViewById(R.id.switch_auto_start);
        tvVehicleId = view.findViewById(R.id.tv_vehicle_id);
        btnCopyId = view.findViewById(R.id.btn_copy_id);
        btnSaveConfig = view.findViewById(R.id.btn_save_config);
        btnStartService = view.findViewById(R.id.btn_start_service);
        btnStopService = view.findViewById(R.id.btn_stop_service);
        btnTest = view.findViewById(R.id.btn_test);
        btnResetStats = view.findViewById(R.id.btn_reset_stats);
        tvStatus = view.findViewById(R.id.tv_status);
        tvCameraCount = view.findViewById(R.id.tv_camera_count);
        tvLastUpload = view.findViewById(R.id.tv_last_upload);
        tvStatistics = view.findViewById(R.id.tv_statistics);
        
        config = new HeartbeatConfig(requireContext());
    }
    
    private void loadConfig() {
        // 服务器地址
        etServerUrl.setText(config.getServerUrl());
        
        // 通信密钥
        etSecretKey.setText(config.getSecretKey());
        
        // 推送间隔
        int interval = config.getIntervalSeconds();
        switch (interval) {
            case HeartbeatConfig.INTERVAL_30_SECONDS:
                rbInterval30.setChecked(true);
                break;
            case HeartbeatConfig.INTERVAL_120_SECONDS:
                rbInterval120.setChecked(true);
                break;
            case HeartbeatConfig.INTERVAL_300_SECONDS:
                rbInterval300.setChecked(true);
                break;
            default:
                rbInterval60.setChecked(true);
                break;
        }
        
        // 图片质量
        int targetSize = config.getTargetSizeKB();
        switch (targetSize) {
            case HeartbeatConfig.TARGET_SIZE_500KB:
                rbQuality500k.setChecked(true);
                break;
            case HeartbeatConfig.TARGET_SIZE_1MB:
                rbQuality1m.setChecked(true);
                break;
            case HeartbeatConfig.TARGET_SIZE_NO_COMPRESS:
                rbQualityNoCompress.setChecked(true);
                break;
            default:
                rbQuality100k.setChecked(true);
                break;
        }
        
        // 推图模式
        switchScreenOnPush.setChecked(config.isScreenOnPushEnabled());
        switchScreenOffPush.setChecked(config.isScreenOffPushEnabled());
        
        // 自动启动
        switchAutoStart.setChecked(config.isAutoStartEnabled());
        
        // 车辆ID
        tvVehicleId.setText(config.getVehicleId());
        
        // 统计信息
        updateStatisticsDisplay();
    }
    
    private void setupListeners() {
        // 菜单按钮
        btnMenu.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                MainActivity activity = (MainActivity) getActivity();
                DrawerLayout drawerLayout = activity.findViewById(R.id.drawer_layout);
                if (drawerLayout != null) {
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                        drawerLayout.closeDrawer(GravityCompat.START);
                    } else {
                        drawerLayout.openDrawer(GravityCompat.START);
                    }
                }
            }
        });
        
        // 主页按钮
        btnHome.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });
        
        // 密钥可见性切换
        btnToggleKeyVisibility.setOnClickListener(v -> {
            isKeyVisible = !isKeyVisible;
            if (isKeyVisible) {
                etSecretKey.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                btnToggleKeyVisibility.setImageResource(R.drawable.ic_visibility_off);
            } else {
                etSecretKey.setTransformationMethod(PasswordTransformationMethod.getInstance());
                btnToggleKeyVisibility.setImageResource(R.drawable.ic_visibility);
            }
            etSecretKey.setSelection(etSecretKey.getText().length());
        });
        
        // 复制车辆ID
        btnCopyId.setOnClickListener(v -> {
            String vehicleId = config.getVehicleId();
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                ClipData clip = ClipData.newPlainText("vehicle_id", vehicleId);
                clipboard.setPrimaryClip(clip);
                Toast.makeText(requireContext(), "Copied: " + vehicleId, Toast.LENGTH_SHORT).show();
            }
        });
        
        // 保存配置
        btnSaveConfig.setOnClickListener(v -> saveConfig());
        
        // 自动启动开关
        switchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setAutoStartEnabled(isChecked);
        });
        
        // 启动服务
        btnStartService.setOnClickListener(v -> {
            if (!config.isConfigured()) {
                Toast.makeText(requireContext(), "Please save configuration first", Toast.LENGTH_SHORT).show();
                return;
            }
            
            config.setEnabled(true);
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).onHeartbeatConfigChanged();
            }
            updateButtonStates();
            updateStatusDisplay();
            Toast.makeText(requireContext(), "Service started", Toast.LENGTH_SHORT).show();
        });
        
        // 停止服务
        btnStopService.setOnClickListener(v -> {
            config.setEnabled(false);
            if (getActivity() instanceof MainActivity) {
                HeartbeatManager manager = ((MainActivity) getActivity()).getHeartbeatManager();
                if (manager != null) {
                    manager.stop();
                }
                ((MainActivity) getActivity()).onHeartbeatConfigChanged();
            }
            updateButtonStates();
            updateStatusDisplay();
            Toast.makeText(requireContext(), "Service stopped", Toast.LENGTH_SHORT).show();
        });
        
        // 立即测试
        btnTest.setOnClickListener(v -> {
            if (!config.isConfigured()) {
                Toast.makeText(requireContext(), "Please save configuration first", Toast.LENGTH_SHORT).show();
                return;
            }
            
            if (getActivity() instanceof MainActivity) {
                HeartbeatManager manager = ((MainActivity) getActivity()).getHeartbeatManager();
                if (manager != null) {
                    Toast.makeText(requireContext(), "Testing...", Toast.LENGTH_SHORT).show();
                    manager.executeOnce();
                }
            }
        });
        
        // 重置统计
        btnResetStats.setOnClickListener(v -> {
            config.resetStatistics();
            updateStatisticsDisplay();
            Toast.makeText(requireContext(), "Statistics reset", Toast.LENGTH_SHORT).show();
        });
    }
    
    /**
     * 更新启动/停止按钮状态
     */
    private void updateButtonStates() {
        boolean isEnabled = config.isEnabled();
        boolean isConfigured = config.isConfigured();
        
        btnStartService.setEnabled(!isEnabled && isConfigured);
        btnStopService.setEnabled(isEnabled);
    }
    
    private void saveConfig() {
        String serverUrl = etServerUrl.getText().toString().trim();
        String secretKey = etSecretKey.getText().toString().trim();
        
        // 验证
        if (serverUrl.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter server URL", Toast.LENGTH_SHORT).show();
            return;
        }
        if (!serverUrl.startsWith("http://") && !serverUrl.startsWith("https://")) {
            Toast.makeText(requireContext(), "Server URL must start with http:// or https://", Toast.LENGTH_SHORT).show();
            return;
        }
        if (secretKey.isEmpty()) {
            Toast.makeText(requireContext(), "Please enter communication secret key", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 保存配置
        config.setServerUrl(serverUrl);
        config.setSecretKey(secretKey);
        config.setIntervalSeconds(getSelectedInterval());
        config.setTargetSizeKB(getSelectedTargetSize());
        config.setScreenOnPushEnabled(switchScreenOnPush.isChecked());
        config.setScreenOffPushEnabled(switchScreenOffPush.isChecked());
        config.setAutoStartEnabled(switchAutoStart.isChecked());
        
        Toast.makeText(requireContext(), "Configuration saved", Toast.LENGTH_SHORT).show();
        
        // 更新按钮状态
        updateButtonStates();
        updateStatusDisplay();
    }
    
    private int getSelectedInterval() {
        int checkedId = rgInterval.getCheckedRadioButtonId();
        if (checkedId == R.id.rb_interval_30) {
            return HeartbeatConfig.INTERVAL_30_SECONDS;
        } else if (checkedId == R.id.rb_interval_120) {
            return HeartbeatConfig.INTERVAL_120_SECONDS;
        } else if (checkedId == R.id.rb_interval_300) {
            return HeartbeatConfig.INTERVAL_300_SECONDS;
        }
        return HeartbeatConfig.INTERVAL_60_SECONDS;
    }
    
    private int getSelectedTargetSize() {
        int checkedId = rgImageQuality.getCheckedRadioButtonId();
        if (checkedId == R.id.rb_quality_500k) {
            return HeartbeatConfig.TARGET_SIZE_500KB;
        } else if (checkedId == R.id.rb_quality_1m) {
            return HeartbeatConfig.TARGET_SIZE_1MB;
        } else if (checkedId == R.id.rb_quality_no_compress) {
            return HeartbeatConfig.TARGET_SIZE_NO_COMPRESS;
        }
        return HeartbeatConfig.TARGET_SIZE_100KB;
    }
    
    /**
     * 更新状态显示
     */
    public void updateStatusDisplay() {
        if (getActivity() instanceof MainActivity) {
            HeartbeatManager manager = ((MainActivity) getActivity()).getHeartbeatManager();
            boolean isRunning = manager != null && manager.isRunning();
            boolean isEnabled = config.isEnabled();
            boolean isConfigured = config.isConfigured();
            boolean screenOnPush = config.isScreenOnPushEnabled();
            boolean screenOffPush = config.isScreenOffPushEnabled();
            
            if (isRunning) {
                // 服务正在运行
                tvStatus.setText("Running");
                tvStatus.setTextColor(0xFF00CC00);  // 深绿色
            } else if (!isConfigured) {
                // 配置不完整
                tvStatus.setText("Not Configured");
                tvStatus.setTextColor(0xFFFF4444);  // 红色
            } else if (!isEnabled) {
                // 用户未启用服务
                tvStatus.setText("Not Started");
                tvStatus.setTextColor(0xFF888888);  // 灰色
            } else {
                // isEnabled=true 但 isRunning=false：配置已启用但服务未运行
                // 这种情况通常是：用户之前启用了服务，但 app 重启后没有自动启动
                if (!screenOnPush && !screenOffPush) {
                    tvStatus.setText("Service not running (all push modes disabled)");
                    tvStatus.setTextColor(0xFFFF9900);  // 橙色
                } else if (screenOffPush && !screenOnPush) {
                    tvStatus.setText("Service not running (screen-off push only)");
                    tvStatus.setTextColor(0xFFFF9900);  // 橙色
                } else {
                    tvStatus.setText("Service not running");
                    tvStatus.setTextColor(0xFFFF9900);  // 橙色
                }
            }
            
            // 摄像头数量（从 MainActivity 获取）
            int cameraCount = ((MainActivity) getActivity()).getConnectedCameraCount();
            int totalCameras = ((MainActivity) getActivity()).getTotalCameraCount();
            tvCameraCount.setText("Camera: " + cameraCount + "/" + totalCameras + " Connected");
        }
        
        updateButtonStates();
        updateStatisticsDisplay();
    }
    
    private void updateStatisticsDisplay() {
        // 上次上传时间
        long lastUpload = config.getLastUploadTime();
        tvLastUpload.setText("Last push: " + HeartbeatManager.formatTimestamp(lastUpload));
        
        // 成功/失败统计
        int success = config.getSuccessCount();
        int fail = config.getFailCount();
        tvStatistics.setText("Success: " + success + " | Failed: " + fail);
    }
    
    // ==================== HeartbeatListener 回调 ====================
    
    @Override
    public void onHeartbeatStarted() {
        if (isAdded()) {
            tvStatus.setText("Running");
            tvStatus.setTextColor(0xFF00CC00);  // 深绿色
        }
    }
    
    @Override
    public void onHeartbeatStopped() {
        if (isAdded()) {
            updateStatusDisplay();
        }
    }
    
    @Override
    public void onHeartbeatSuccess(long timestamp) {
        if (isAdded()) {
            updateStatisticsDisplay();
            tvLastUpload.setText(HeartbeatManager.formatTimestamp(timestamp));
        }
    }
    
    @Override
    public void onHeartbeatFailed(String error) {
        if (isAdded()) {
            updateStatisticsDisplay();
            // 可选：显示错误提示
            // Toast.makeText(requireContext(), "Heartbeat failed: " + error, Toast.LENGTH_SHORT).show();
        }
    }
}
