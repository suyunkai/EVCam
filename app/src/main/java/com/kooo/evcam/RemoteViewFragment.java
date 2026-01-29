package com.kooo.evcam;

import android.os.Bundle;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.kooo.evcam.dingtalk.DingTalkApiClient;
import com.kooo.evcam.dingtalk.DingTalkConfig;

public class RemoteViewFragment extends Fragment {
    private static final String TAG = "RemoteViewFragment";

    private EditText etClientId, etClientSecret;
    private Button btnSaveConfig, btnStartService, btnStopService, btnMenu;
    private Button btnTestConnection, btnToggleSecretVisibility;
    private TextView tvConnectionStatus;
    private SwitchCompat switchAutoStart;
    private boolean isSecretVisible = false;

    private DingTalkConfig config;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_remote_view, container, false);
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
        Button btnHome = view.findViewById(R.id.btn_home);
        etClientId = view.findViewById(R.id.et_client_id);
        etClientSecret = view.findViewById(R.id.et_client_secret);
        btnSaveConfig = view.findViewById(R.id.btn_save_config);
        btnTestConnection = view.findViewById(R.id.btn_test_connection);
        btnToggleSecretVisibility = view.findViewById(R.id.btn_toggle_secret_visibility);
        btnStartService = view.findViewById(R.id.btn_start_service);
        btnStopService = view.findViewById(R.id.btn_stop_service);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
        switchAutoStart = view.findViewById(R.id.switch_auto_start);
        config = new DingTalkConfig(requireContext());

        // 主页按钮 - 返回预览界面
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
        if (config.isConfigured()) {
            etClientId.setText(config.getClientId());
            etClientSecret.setText(config.getClientSecret());
        }
        // 加载自动启动设置
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
        btnSaveConfig.setOnClickListener(v -> saveConfig());
        btnTestConnection.setOnClickListener(v -> testConnection());
        btnStartService.setOnClickListener(v -> startService());
        btnStopService.setOnClickListener(v -> stopService());

        // 密码可见性切换
        btnToggleSecretVisibility.setOnClickListener(v -> toggleSecretVisibility());

        // 自动启动开关监听
        switchAutoStart.setOnCheckedChangeListener((buttonView, isChecked) -> {
            config.setAutoStart(isChecked);
            Toast.makeText(requireContext(),
                isChecked ? "已启用自动启动" : "已禁用自动启动",
                Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * 切换密码可见性
     */
    private void toggleSecretVisibility() {
        isSecretVisible = !isSecretVisible;
        if (isSecretVisible) {
            // 显示密码
            etClientSecret.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
            btnToggleSecretVisibility.setText("🔒");
        } else {
            // 隐藏密码
            etClientSecret.setTransformationMethod(PasswordTransformationMethod.getInstance());
            btnToggleSecretVisibility.setText("👁");
        }
        // 将光标移到末尾
        etClientSecret.setSelection(etClientSecret.getText().length());
    }

    /**
     * 测试连接 - 通过获取 AccessToken 验证凭证是否正确
     */
    private void testConnection() {
        String clientId = etClientId.getText().toString().trim();
        String clientSecret = etClientSecret.getText().toString().trim();

        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            Toast.makeText(requireContext(), "请先填写 ClientId 和 ClientSecret", Toast.LENGTH_SHORT).show();
            return;
        }

        // 禁用按钮，防止重复点击
        btnTestConnection.setEnabled(false);
        btnTestConnection.setText("测试中...");

        // 在后台线程执行测试
        new Thread(() -> {
            try {
                // 创建临时配置
                DingTalkConfig tempConfig = new DingTalkConfig(requireContext());
                tempConfig.saveConfig(clientId, clientSecret);
                
                // 清除缓存的 token，强制重新获取
                tempConfig.clearAccessToken();
                
                // 尝试获取 AccessToken
                DingTalkApiClient apiClient = new DingTalkApiClient(tempConfig);
                apiClient.getAccessToken();
                
                // 成功
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        btnTestConnection.setEnabled(true);
                        btnTestConnection.setText("测试连接");
                        Toast.makeText(requireContext(), "✅ 连接测试成功！凭证有效", Toast.LENGTH_LONG).show();
                        tvConnectionStatus.setText("凭证有效");
                        tvConnectionStatus.setTextColor(0xFF66FF66);
                    });
                }
            } catch (Exception e) {
                // 失败
                String errorMsg = e.getMessage();
                if (errorMsg != null && errorMsg.contains("errcode")) {
                    // 解析钉钉错误信息
                    if (errorMsg.contains("40089") || errorMsg.contains("invalid appkey")) {
                        errorMsg = "ClientId/AppKey 无效";
                    } else if (errorMsg.contains("43003") || errorMsg.contains("secret")) {
                        errorMsg = "ClientSecret/AppSecret 无效";
                    }
                }
                final String finalErrorMsg = errorMsg;
                if (getActivity() != null) {
                    getActivity().runOnUiThread(() -> {
                        btnTestConnection.setEnabled(true);
                        btnTestConnection.setText("测试连接");
                        Toast.makeText(requireContext(), "❌ 连接测试失败：" + finalErrorMsg, Toast.LENGTH_LONG).show();
                        tvConnectionStatus.setText("凭证无效");
                        tvConnectionStatus.setTextColor(0xFFFF6666);
                    });
                }
                AppLog.e(TAG, "测试连接失败", e);
            }
        }).start();
    }

    private void saveConfig() {
        String clientId = etClientId.getText().toString().trim();
        String clientSecret = etClientSecret.getText().toString().trim();

        if (clientId.isEmpty() || clientSecret.isEmpty()) {
            Toast.makeText(requireContext(), "请填写完整的配置信息", Toast.LENGTH_SHORT).show();
            return;
        }

        config.saveConfig(clientId, clientSecret);
        Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void startService() {
        if (!config.isConfigured()) {
            Toast.makeText(requireContext(), "请先保存配置", Toast.LENGTH_SHORT).show();
            return;
        }

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).startDingTalkService();
        }
    }

    private void stopService() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).stopDingTalkService();
        }
    }

    /**
     * 更新服务状态显示（由 MainActivity 调用）
     */
    public void updateServiceStatus() {
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            boolean isRunning = activity.isDingTalkServiceRunning();

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
        // 每次显示时更新状态
        updateServiceStatus();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // 不再在这里停止服务，服务由 MainActivity 管理
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 不再在这里停止服务，服务由 MainActivity 管理
    }
}
