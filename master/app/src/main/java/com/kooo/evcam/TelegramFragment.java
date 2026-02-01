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

import com.kooo.evcam.telegram.TelegramConfig;

/**
 * Telegram Bot 配置界面
 */
public class TelegramFragment extends Fragment {
    private static final String TAG = "TelegramFragment";

    private EditText etBotToken, etAllowedChatIds, etApiHost;
    private Button btnSaveConfig, btnStartService, btnStopService, btnMenu, btnHome;
    private ImageButton btnToggleTokenVisibility;
    private TextView tvConnectionStatus, tvHelpText;
    private SwitchCompat switchAutoStart;
    private boolean isTokenVisible = false;

    private TelegramConfig config;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_telegram, container, false);
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
        btnToggleTokenVisibility = view.findViewById(R.id.btn_toggle_token_visibility);
        etBotToken = view.findViewById(R.id.et_bot_token);
        etAllowedChatIds = view.findViewById(R.id.et_allowed_chat_ids);
        etApiHost = view.findViewById(R.id.et_api_host);
        btnSaveConfig = view.findViewById(R.id.btn_save_config);
        btnStartService = view.findViewById(R.id.btn_start_service);
        btnStopService = view.findViewById(R.id.btn_stop_service);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
        tvHelpText = view.findViewById(R.id.tv_help_text);
        switchAutoStart = view.findViewById(R.id.switch_auto_start);
        config = new TelegramConfig(requireContext());
    }

    private void loadConfig() {
        if (config.isConfigured()) {
            etBotToken.setText(config.getBotToken());
            etAllowedChatIds.setText(config.getAllowedChatIds());
        }
        // 加载 API Host（使用原始配置值，未配置时显示空）
        etApiHost.setText(config.getRawBotApiHost());
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
        btnToggleTokenVisibility.setOnClickListener(v -> {
            isTokenVisible = !isTokenVisible;
            if (isTokenVisible) {
                etBotToken.setTransformationMethod(HideReturnsTransformationMethod.getInstance());
                btnToggleTokenVisibility.setImageResource(R.drawable.ic_visibility_off);
            } else {
                etBotToken.setTransformationMethod(PasswordTransformationMethod.getInstance());
                btnToggleTokenVisibility.setImageResource(R.drawable.ic_visibility);
            }
            // 保持光标在末尾
            etBotToken.setSelection(etBotToken.getText().length());
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
        String botToken = etBotToken.getText().toString().trim();
        String allowedChatIds = etAllowedChatIds.getText().toString().trim();
        String apiHost = etApiHost.getText().toString().trim();

        if (botToken.isEmpty()) {
            Toast.makeText(requireContext(), "请填写 Bot Token", Toast.LENGTH_SHORT).show();
            return;
        }

        // 验证 API Host 格式（必须带协议头）
        if (!TelegramConfig.isValidApiHost(apiHost)) {
            Toast.makeText(requireContext(), "API 地址格式错误，必须以 http:// 或 https:// 开头", Toast.LENGTH_LONG).show();
            return;
        }

        config.saveConfig(botToken, allowedChatIds);
        config.saveBotApiHost(apiHost);
        Toast.makeText(requireContext(), "配置已保存", Toast.LENGTH_SHORT).show();
    }

    private void startService() {
        if (!config.isConfigured()) {
            Toast.makeText(requireContext(), "请先保存配置", Toast.LENGTH_SHORT).show();
            return;
        }

        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).startTelegramService();
        }
    }

    private void stopService() {
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).stopTelegramService();
        }
    }

    /**
     * 更新服务状态显示（由 MainActivity 调用）
     */
    public void updateServiceStatus() {
        if (getActivity() instanceof MainActivity) {
            MainActivity activity = (MainActivity) getActivity();
            boolean isRunning = activity.isTelegramServiceRunning();

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
