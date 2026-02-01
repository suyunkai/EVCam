package com.kooo.evcam;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.wechat.QrCodeGenerator;
import com.kooo.evcam.wechat.WechatMiniConfig;

/**
 * 微信小程序绑定界面（简化版）
 * 只显示二维码和连接状态
 */
public class WechatMiniFragment extends Fragment {
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

    private WechatMiniConfig config;

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
        
        // 自动启动微信云服务
        autoStartWechatService();
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

        // 更新绑定状态显示
        updateBindStatusUI();
    }

    private void setupListeners() {
        // 刷新二维码
        btnRefreshQr.setOnClickListener(v -> generateQrCode());

        // 解除绑定
        btnUnbind.setOnClickListener(v -> showUnbindConfirmDialog());
    }

    /**
     * 自动启动微信云服务
     */
    private void autoStartWechatService() {
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            // 自动启动微信云管理器
            activity.startWechatCloudManager();
            AppLog.d(TAG, "自动启动微信云服务");
        }
    }

    /**
     * 生成设备绑定二维码
     * 使用微信小程序URL Scheme格式，支持微信直接扫码跳转
     */
    private void generateQrCode() {
        try {
            // 获取二维码尺寸（dp 转 px）
            float density = getResources().getDisplayMetrics().density;
            int size = (int) (240 * density);

            // 生成带小程序跳转链接的二维码
            Bitmap qrBitmap = QrCodeGenerator.generateMiniProgramQrCode(config, size);

            if (qrBitmap != null) {
                ivQrCode.setImageBitmap(qrBitmap);
                AppLog.d(TAG, "二维码生成成功，数据: " + config.getQrCodeData());
                
                // 更新提示文字
                tvQrHint.setText("打开小程序 → 扫码绑定");
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
            tvBoundUser.setText("绑定用户：" + config.getBoundUserNickname());
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
                .setMessage("确定要解除与「" + config.getBoundUserNickname() + "」的绑定吗？\n\n解绑后需要重新扫码绑定才能使用远程控制功能。")
                .setPositiveButton("确认解绑", (dialog, which) -> {
                    config.unbind();
                    updateBindStatusUI();
                    generateQrCode();
                    Toast.makeText(requireContext(), "已解除绑定", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 更新服务状态显示（由 MainActivity 调用）
     */
    public void updateServiceStatus() {
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            boolean isRunning = activity.isWechatCloudManagerRunning();

            if (statusIndicator != null && tvConnectionStatus != null) {
                if (isRunning) {
                    statusIndicator.setBackgroundResource(R.drawable.status_indicator_online);
                    tvConnectionStatus.setText("已连接");
                    tvConnectionStatus.setTextColor(0xFF66FF66);
                } else {
                    statusIndicator.setBackgroundResource(R.drawable.status_indicator_offline);
                    tvConnectionStatus.setText("未连接");
                    tvConnectionStatus.setTextColor(0xFFFF6666);
                }
            }
        }
    }

    /**
     * 更新连接中状态
     */
    public void updateConnectingStatus() {
        if (statusIndicator != null && tvConnectionStatus != null) {
            statusIndicator.setBackgroundResource(R.drawable.status_indicator_connecting);
            tvConnectionStatus.setText("连接中...");
            tvConnectionStatus.setTextColor(0xFFFFCC00);
        }
    }

    /**
     * 更新绑定状态（由 MainActivity 调用）
     */
    public void updateBindStatus(boolean bound, String userNickname) {
        if (bound) {
            config.setBound(true, "", userNickname);
        }
        updateBindStatusUI();
    }

    @Override
    public void onResume() {
        super.onResume();
        updateServiceStatus();
        updateBindStatusUI();
    }
}
