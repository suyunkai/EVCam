package com.kooo.evcam;

import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.feishu.FeishuConfig;

/**
 * 飞书 Bot 配置界面
 */
public class FeishuFragment extends Fragment {
    private static final String TAG = "FeishuFragment";

    private EditText etAppId, etAppSecret, etAllowedUserIds;
    private Button btnSaveConfig, btnStartService, btnStopService, btnMenu, btnHome;
    private ImageButton btnToggleSecretVisibility;
    private TextView tvConnectionStatus, tvHelpText;
    private SwitchCompat switchAutoStart;
    private boolean isSecretVisible = false;

    private FeishuConfig config;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_feishu, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        initViews(view);
        loadConfig();
        setupListeners();
    }

    private void initViews(View view) {
        btnMenu = view.findViewById(R.id.btn_menu);
        btnHome = view.findViewById(R.id.btn_home);
        btnToggleSecretVisibility = view.findViewById(R.id.btn_toggle_secret_visibility);
        etAppId = view.findViewById(R.id.et_app_id);
        etAppSecret = view.findViewById(R.id.et_app_secret);
        etAllowedUserIds = view.findViewById(R.id.et_allowed_user_ids);
        btnSaveConfig = view.findViewById(R.id.btn_save_config);
        btnStartService = view.findViewById(R.id.btn_start_service);
        btnStopService = view.findViewById(R.id.btn_stop_service);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
        tvHelpText = view.findViewById(R.id.tv_help_text);
        switchAutoStart = view.findViewById(R.id.switch_auto_start);
        config = new FeishuConfig(requireContext());
    }

    private void loadConfig() {
        if (config.isConfigured()) {
            etAppId.setText(config.getAppId());
            etAppSecret.setText(config.getAppSecret());
            etAllowedUserIds.setText(config.getAllowedUserIds());
        }
        switchAutoStart.setChecked(config.isAutoStart());
    }

    private void setupListeners() {
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

        // 密码可见性切换
        btnToggleSecretVisibility.setOnClickListener(v -> {
            isSecretVisible = !isSecretVisible;
            if (isSecretVisible) {
                etAppSecret.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                btnToggleSecretVisibility.setImageResource(R.drawable.ic_visibility_off);
            } else {
                etAppSecret.setTransformationMethod(PasswordTransformationMethod.getInstance());
                btnToggleSecretVisibility.setImageResource(R.drawable.ic_visibility);
            }
            // 保持光标在末尾
            etAppSecret.setSelection(etAppSecret.getText().length());
        });

        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnStartService.setOnClickListener(v -> startService());
        btnStopService.setOnClickListener(v -> stopService());

        switchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setAutoStart(isChecked);
            Toast.makeText(requireContext(),
                isChecked ? "已启用自动启动" : "已禁用自动启动",
                Toast.LENGTH_SHORT).show();
        });
    }

    private void saveConfig() {
        String appId = etAppId.getText().toString().trim();
        String appSecret = etAppSecret.getText().toString().trim();
        String allowedUserIds = etAllowedUserIds.getText().toString().trim();

        if (appId.isEmpty()) {
            Toast.makeText(requireContext(), "请填写 App ID", Toast.LENGTH_SHORT).show();
            return;
        }

        if (appSecret.isEmpty()) {
            Toast.makeText(requireContext(), "请填写 App Secret", Toast.LENGTH_SHORT).show();
            return;
        }

        config.saveConfig(appId, appSecret, allowedUserIds);
        Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void startService() {
        if (!config.isConfigured()) {
            Toast.makeText(requireContext(), "请先保存配置", Toast.LENGTH_SHORT).show();
            return;
        }

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).startFeishuService();
        }
    }

    private void stopService() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).stopFeishuService();
        }
    }

    /**
     * 更新服务状态显示（由 MainActivity 调用）
     */
    public void updateServiceStatus() {
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            boolean isRunning = activity.isFeishuServiceRunning();

            if (isRunning) {
                tvConnectionStatus.setText("已连接");
                tvConnectionStatus.setTextColor(0xFF66FF66);
                btnStartService.setEnabled(false);
                btnStopService.setEnabled(true);
            } else {
                tvConnectionStatus.setText("未连接");
                tvConnectionStatus.setTextColor(0xFFFF6666);
                btnStartService.setEnabled(true);
                btnStopService.setEnabled(false);
            }
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        updateServiceStatus();
    }
}
