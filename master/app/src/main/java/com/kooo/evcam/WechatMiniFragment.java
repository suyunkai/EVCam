package com.kooo.evcam;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.wechat.QrCodeGenerator;
import com.kooo.evcam.wechat.WechatMiniConfig;
import com.kooo.evcam.wechat.WechatRemoteManager;

/**
 * 微信小程序绑定界面
 * 显示二维码和连接状态
 */
public class WechatMiniFragment extends Fragment implements WechatRemoteManager.UICallback {
    private static final String TAG = "WechatMiniFragment";

    // 连接状态
    private View statusIndicator;
    private TextView tvConnectionStatus;

    // 绑定状态相关
    private LinearLayout layoutQrCode;
    private LinearLayout layoutBoundInfo;
    private ImageView ivQrCode;
    private TextView tvQrHint;
    private Button btnRefreshQr;
    private TextView tvBoundUser;
    private Button btnUnbind;

    // 设备信息
    private TextView tvDeviceId;
    private TextView tvDeviceName;
    
    // 凭证配置输入框
    private EditText etAppId;
    private EditText etAppSecret;
    private EditText etCloudEnv;
    private Button btnSaveConfig;
    private TextView tvConfigStatus;
    
    // 服务控制
    private Switch switchAutoStart;
    private TextView tvServiceStatus;
    private Button btnStartService;
    private Button btnStopService;

    private WechatMiniConfig config;
    private WechatRemoteManager remoteManager;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_wechat_mini, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        loadConfig();
        setupListeners();
        generateQrCode();
        
        // 注册 UI 回调并启动服务
        registerAndStartService();
    }

    private void initViews(View view) {
        // Toolbar
        Button btnMenu = view.findViewById(R.id.btn_menu);
        Button btnHome = view.findViewById(R.id.btn_home);

        // 连接状态
        statusIndicator = view.findViewById(R.id.status_indicator);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);

        // 绑定状态
        layoutQrCode = view.findViewById(R.id.layout_qr_code);
        layoutBoundInfo = view.findViewById(R.id.layout_bound_info);
        ivQrCode = view.findViewById(R.id.iv_qr_code);
        tvQrHint = view.findViewById(R.id.tv_qr_hint);
        btnRefreshQr = view.findViewById(R.id.btn_refresh_qr);
        tvBoundUser = view.findViewById(R.id.tv_bound_user);
        btnUnbind = view.findViewById(R.id.btn_unbind);

        // 设备信息
        tvDeviceId = view.findViewById(R.id.tv_device_id);
        tvDeviceName = view.findViewById(R.id.tv_device_name);
        
        // 凭证配置
        etAppId = view.findViewById(R.id.et_app_id);
        etAppSecret = view.findViewById(R.id.et_app_secret);
        etCloudEnv = view.findViewById(R.id.et_cloud_env);
        btnSaveConfig = view.findViewById(R.id.btn_save_config);
        tvConfigStatus = view.findViewById(R.id.tv_config_status);
        
        // 服务控制
        switchAutoStart = view.findViewById(R.id.switch_auto_start);
        tvServiceStatus = view.findViewById(R.id.tv_service_status);
        btnStartService = view.findViewById(R.id.btn_start_service);
        btnStopService = view.findViewById(R.id.btn_stop_service);

        // 初始化配置
        config = new WechatMiniConfig(requireContext());

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
    }

    private void loadConfig() {
        // 加载设备信息
        tvDeviceId.setText("设备ID：" + config.getDeviceId());
        
        String deviceName = config.getDeviceName();
        if (deviceName != null && !deviceName.isEmpty()) {
            tvDeviceName.setText(deviceName);
            tvDeviceName.setVisibility(View.VISIBLE);
        }
        
        // 加载已保存的凭证到输入框
        if (etAppId != null) {
            etAppId.setText(config.getAppId());
        }
        if (etAppSecret != null) {
            etAppSecret.setText(config.getAppSecret());
        }
        if (etCloudEnv != null) {
            etCloudEnv.setText(config.getCloudEnv());
        }

        // 更新绑定状态显示
        updateBindStatusUI();
        
        // 更新配置状态显示
        updateConfigStatus();
    }

    private void setupListeners() {
        // 刷新二维码
        btnRefreshQr.setOnClickListener(v -> generateQrCode());

        // 解除绑定
        btnUnbind.setOnClickListener(v -> showUnbindConfirmDialog());
        
        // 保存配置
        if (btnSaveConfig != null) {
            btnSaveConfig.setOnClickListener(v -> saveConfig());
        }
        
        // 自动启动开关
        if (switchAutoStart != null) {
            switchAutoStart.setChecked(config.isAutoStart());
            switchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
                config.setAutoStart(isChecked);
                Toast.makeText(requireContext(), 
                        isChecked ? "已开启自动启动" : "已关闭自动启动", 
                        Toast.LENGTH_SHORT).show();
            });
        }
        
        // 启动服务按钮
        if (btnStartService != null) {
            btnStartService.setOnClickListener(v -> {
                if (!config.isCloudConfigured()) {
                    Toast.makeText(requireContext(), "请先配置云开发凭证", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (remoteManager != null && !remoteManager.isRunning()) {
                    remoteManager.startService();
                    Toast.makeText(requireContext(), "正在启动服务...", Toast.LENGTH_SHORT).show();
                } else if (remoteManager != null && remoteManager.isRunning()) {
                    Toast.makeText(requireContext(), "服务已在运行", Toast.LENGTH_SHORT).show();
                }
            });
        }
        
        // 停止服务按钮
        if (btnStopService != null) {
            btnStopService.setOnClickListener(v -> {
                if (remoteManager != null && remoteManager.isRunning()) {
                    remoteManager.stopService();
                    updateServiceStatus(false);
                    Toast.makeText(requireContext(), "服务已停止", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(requireContext(), "服务未运行", Toast.LENGTH_SHORT).show();
                }
            });
        }
    }
    
    /**
     * 保存凭证配置
     */
    private void saveConfig() {
        String appId = etAppId.getText().toString().trim();
        String appSecret = etAppSecret.getText().toString().trim();
        String cloudEnv = etCloudEnv.getText().toString().trim();
        
        if (appId.isEmpty() && appSecret.isEmpty() && cloudEnv.isEmpty()) {
            // 全部为空，清除配置
            config.clearCloudCredentials();
            Toast.makeText(requireContext(), "配置已清除", Toast.LENGTH_SHORT).show();
            updateConfigStatus();
            
            // 停止服务
            if (remoteManager != null) {
                remoteManager.stopService();
                updateServiceStatus(false);
            }
            return;
        }
        
        if (appId.isEmpty() || appSecret.isEmpty() || cloudEnv.isEmpty()) {
            Toast.makeText(requireContext(), "请填写完整的凭证信息", Toast.LENGTH_SHORT).show();
            return;
        }
        
        // 保存配置
        config.saveCloudCredentials(appId, appSecret, cloudEnv);
        Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
        
        // 更新配置状态显示
        updateConfigStatus();
        
        // 重启服务
        restartService();
    }

    /**
     * 注册 UI 回调并启动服务
     */
    private void registerAndStartService() {
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            remoteManager = activity.getWechatRemoteManager();
            
            if (remoteManager != null) {
                // 注册 UI 回调
                remoteManager.setUICallback(this);
                
                // 如果服务未运行且已配置凭证，启动服务
                if (!remoteManager.isRunning() && config.isCloudConfigured()) {
                    remoteManager.startService();
                } else if (remoteManager.isRunning()) {
                    // 服务已运行，更新状态显示
                    updateServiceStatus(true);
                }
            }
        }
    }

    /**
     * 生成设备绑定二维码
     */
    private void generateQrCode() {
        try {
            // 获取二维码尺寸（dp 转 px）
            float density = getResources().getDisplayMetrics().density;
            int size = (int) (200 * density);

            // 生成二维码
            Bitmap qrBitmap = QrCodeGenerator.generateMiniProgramQrCode(config, size);

            if (qrBitmap != null) {
                ivQrCode.setImageBitmap(qrBitmap);
                AppLog.d(TAG, "二维码生成成功，设备ID: " + config.getDeviceId());
            } else {
                Toast.makeText(requireContext(), "生成二维码失败", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            AppLog.e(TAG, "生成二维码失败", e);
            Toast.makeText(requireContext(), "生成二维码失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 更新绑定状态 UI
     */
    private void updateBindStatusUI() {
        if (config.isBound()) {
            // 已绑定状态
            layoutQrCode.setVisibility(View.GONE);
            layoutBoundInfo.setVisibility(View.VISIBLE);
            tvBoundUser.setText("用户：" + config.getBoundUserNickname());
        } else {
            // 未绑定状态
            layoutQrCode.setVisibility(View.VISIBLE);
            layoutBoundInfo.setVisibility(View.GONE);
        }
    }

    /**
     * 显示解绑确认对话框
     */
    private void showUnbindConfirmDialog() {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("解除绑定")
                .setMessage("确定要解除绑定吗？解绑后需要重新扫码。")
                .setPositiveButton("确认", (dialog, which) -> {
                    config.unbind();
                    updateBindStatusUI();
                    generateQrCode();
                    Toast.makeText(requireContext(), "已解除绑定", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }
    
    /**
     * 更新配置状态显示
     */
    private void updateConfigStatus() {
        if (tvConfigStatus != null) {
            if (config.isCloudConfigured()) {
                tvConfigStatus.setText("已配置");
                tvConfigStatus.setTextColor(getResources().getColor(R.color.text_secondary, null));
            } else {
                tvConfigStatus.setText("未配置");
                tvConfigStatus.setTextColor(getResources().getColor(R.color.text_secondary, null));
            }
        }
    }
    
    /**
     * 重启服务（配置更新后）
     */
    private void restartService() {
        if (remoteManager != null) {
            // 先停止
            remoteManager.stopService();
        }
        
        // 重新创建管理器（因为凭证在构造时读取）
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            // 重新初始化
            activity.reinitWechatRemoteManager();
            remoteManager = activity.getWechatRemoteManager();
            
            if (remoteManager != null) {
                remoteManager.setUICallback(this);
                remoteManager.startService();
            }
        }
    }

    /**
     * 更新服务状态显示
     */
    private void updateServiceStatus(boolean connected) {
        // 更新顶部状态指示器
        if (statusIndicator != null && tvConnectionStatus != null) {
            if (connected) {
                statusIndicator.setBackgroundResource(R.drawable.status_indicator_online);
                tvConnectionStatus.setText("已连接");
            } else {
                statusIndicator.setBackgroundResource(R.drawable.status_indicator_offline);
                tvConnectionStatus.setText("未连接");
            }
            tvConnectionStatus.setTextColor(getResources().getColor(R.color.text_primary, null));
        }
        
        // 更新服务控制区域的状态
        if (tvServiceStatus != null) {
            if (connected) {
                tvServiceStatus.setText("已连接");
                tvServiceStatus.setTextColor(0xFF4CAF50); // 绿色
            } else {
                tvServiceStatus.setText("未连接");
                tvServiceStatus.setTextColor(0xFFFF5252); // 红色
            }
        }
    }

    /**
     * 更新连接中状态
     */
    private void updateConnectingStatus() {
        // 更新顶部状态指示器
        if (statusIndicator != null && tvConnectionStatus != null) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_connecting);
            tvConnectionStatus.setText("连接中...");
            tvConnectionStatus.setTextColor(getResources().getColor(R.color.text_primary, null));
        }
        
        // 更新服务控制区域的状态
        if (tvServiceStatus != null) {
            tvServiceStatus.setText("连接中...");
            tvServiceStatus.setTextColor(0xFFFF9800); // 橙色
        }
    }

    // ===== UICallback 接口实现 =====
    
    @Override
    public void onServiceStatusChanged(boolean connected) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(() -> updateServiceStatus(connected));
        }
    }

    @Override
    public void onConnecting() {
        if (getActivity() != null) {
            getActivity().runOnUiThread(this::updateConnectingStatus);
        }
    }

    @Override
    public void onBindStatusChanged(boolean bound, String userNickname) {
        if (bound) {
            config.setBound(true, "", userNickname);
        }
        if (getActivity() != null) {
            getActivity().runOnUiThread(this::updateBindStatusUI);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // 刷新状态
        if (remoteManager != null) {
            updateServiceStatus(remoteManager.isRunning());
        }
        updateBindStatusUI();
    }
    
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 清除 UI 回调（避免内存泄漏）
        if (remoteManager != null) {
            remoteManager.setUICallback(null);
        }
    }
}
