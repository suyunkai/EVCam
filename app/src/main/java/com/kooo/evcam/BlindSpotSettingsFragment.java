package com.kooo.evcam;

import android.content.res.AssetManager;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

/**
 * 补盲选项设置界面
 */
public class BlindSpotSettingsFragment extends Fragment {
    private static final String TAG = "BlindSpotSettingsFragment";

    private Button openLabButton;

    private SwitchMaterial turnSignalLinkageSwitch;
    private SeekBar turnSignalTimeoutSeekBar;
    private TextView tvTurnSignalTimeout;
    private RadioGroup turnSignalPresetGroup;
    private LinearLayout customKeywordsLayout;
    private EditText turnSignalLeftLogEditText;
    private EditText turnSignalRightLogEditText;
    private boolean isUpdatingFromPreset = false; // 防止 TextWatcher 在预设填充时触发

    // 转向灯触发log预设方案
    private static final String[][] TURN_SIGNAL_PRESETS = {
        // { presetId, leftKeyword, rightKeyword }
        { "xinghan7", "left front turn signal:1", "right front turn signal:1" },
        { "e5", "PA_GpioTurnLeftLamp, value:1", "PA_GpioTurnRightLamp, value:1" },
    };

    private TextView carApiStatusText;

    private SwitchMaterial blindSpotGlobalSwitch;
    private android.widget.LinearLayout subFeaturesContainer;
    private SwitchMaterial secondaryBlindSpotSwitch;
    private Button adjustSecondaryBlindSpotWindowButton;
    private SwitchMaterial mockFloatingSwitch;
    private SwitchMaterial floatingWindowAnimationSwitch;
    private SwitchMaterial blindSpotCorrectionSwitch;
    private Button adjustBlindSpotCorrectionButton;
    private SwitchMaterial mainFloatingAspectRatioLockSwitch;
    private Button resetMainFloatingButton;
    private Button logcatDebugButton;
    private android.widget.EditText logFilterEditText;
    private Button menuButton;
    private Button homeButton;

    private AppConfig appConfig;
    private boolean disclaimerDialogShown = false;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_secondary_display_settings, container, false);
        appConfig = new AppConfig(requireContext());
        initViews(view);
        loadSettings();
        setupListeners();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        maybeShowDisclaimerDialog();
    }

    private void initViews(View view) {
        // 全局开关
        blindSpotGlobalSwitch = view.findViewById(R.id.switch_blind_spot_global);
        subFeaturesContainer = view.findViewById(R.id.blind_spot_sub_features_container);

        openLabButton = view.findViewById(R.id.btn_open_lab);

        // 转向灯联动
        turnSignalLinkageSwitch = view.findViewById(R.id.switch_turn_signal_linkage);
        turnSignalTimeoutSeekBar = view.findViewById(R.id.seekbar_turn_signal_timeout);
        tvTurnSignalTimeout = view.findViewById(R.id.tv_turn_signal_timeout_value);
        turnSignalPresetGroup = view.findViewById(R.id.rg_turn_signal_preset);
        customKeywordsLayout = view.findViewById(R.id.layout_turn_signal_custom_keywords);
        turnSignalLeftLogEditText = view.findViewById(R.id.et_turn_signal_left_log);
        turnSignalRightLogEditText = view.findViewById(R.id.et_turn_signal_right_log);

        secondaryBlindSpotSwitch = view.findViewById(R.id.switch_secondary_blind_spot_display);
        adjustSecondaryBlindSpotWindowButton = view.findViewById(R.id.btn_adjust_secondary_blind_spot_window);
        
        mockFloatingSwitch = view.findViewById(R.id.switch_mock_floating);
        floatingWindowAnimationSwitch = view.findViewById(R.id.switch_floating_window_animation);

        blindSpotCorrectionSwitch = view.findViewById(R.id.switch_blind_spot_correction);
        adjustBlindSpotCorrectionButton = view.findViewById(R.id.btn_adjust_blind_spot_correction);

        mainFloatingAspectRatioLockSwitch = view.findViewById(R.id.switch_main_floating_aspect_ratio_lock);
        resetMainFloatingButton = view.findViewById(R.id.btn_reset_main_floating);

        carApiStatusText = view.findViewById(R.id.tv_car_api_status);

        logcatDebugButton = view.findViewById(R.id.btn_logcat_debug);
        logFilterEditText = view.findViewById(R.id.et_log_filter);
        menuButton = view.findViewById(R.id.btn_menu);
        homeButton = view.findViewById(R.id.btn_home);

        // 加载抖音二维码
        ImageView douyinQrCode = view.findViewById(R.id.img_douyin_qrcode);
        loadAssetImage(douyinQrCode, "douyin.jpg");

        // 加载第二个抖音二维码（阿卜IT老师）
        ImageView douyinQrCode2 = view.findViewById(R.id.img_douyin_qrcode2);
        loadAssetImage(douyinQrCode2, "douyin2.png");
    }

    private void loadAssetImage(ImageView imageView, String assetName) {
        try {
            AssetManager am = requireContext().getAssets();
            try (InputStream is = am.open(assetName)) {
                imageView.setImageBitmap(BitmapFactory.decodeStream(is));
            }
        } catch (Exception e) {
            imageView.setVisibility(View.GONE);
        }
    }

    private void loadSettings() {
        // 全局开关
        boolean globalEnabled = appConfig.isBlindSpotGlobalEnabled();
        blindSpotGlobalSwitch.setChecked(globalEnabled);
        updateSubFeaturesVisibility(globalEnabled);

        // 转向灯联动
        turnSignalLinkageSwitch.setChecked(appConfig.isTurnSignalLinkageEnabled());
        int timeout = appConfig.getTurnSignalTimeout();
        turnSignalTimeoutSeekBar.setProgress(timeout);
        tvTurnSignalTimeout.setText(timeout + "s");
        String currentLeft = appConfig.getTurnSignalLeftTriggerLog();
        String currentRight = appConfig.getTurnSignalRightTriggerLog();
        turnSignalLeftLogEditText.setText(currentLeft);
        turnSignalRightLogEditText.setText(currentRight);

        // 根据触发模式和当前关键词匹配预设
        if (appConfig.isCarSignalManagerTriggerMode()) {
            // CarSignalManager 模式：根据保存的预设选择恢复 RadioButton
            String presetSelection = appConfig.getTurnSignalPresetSelection();
            if ("boyue_l".equals(presetSelection)) {
                turnSignalPresetGroup.check(R.id.rb_preset_boyue_l);
            } else {
                // 默认选中 L6/L7
                turnSignalPresetGroup.check(R.id.rb_preset_l6l7);
            }
            customKeywordsLayout.setVisibility(View.GONE);
            carApiStatusText.setVisibility(View.VISIBLE);
            carApiStatusText.setText("CarSignalManager 服务状态: 检测中...");
            checkCarSignalManagerConnection();
        } else if (appConfig.isVhalGrpcTriggerMode()) {
            turnSignalPresetGroup.check(R.id.rb_preset_car_api);
            customKeywordsLayout.setVisibility(View.GONE);
            carApiStatusText.setVisibility(View.VISIBLE);
            carApiStatusText.setText("VHAL gRPC 服务状态: 检测中...");
            checkVhalGrpcConnection();
        } else {
            int matchedPreset = findMatchingPreset(currentLeft, currentRight);
            if (matchedPreset == 0) {
                turnSignalPresetGroup.check(R.id.rb_preset_xinghan7);
                customKeywordsLayout.setVisibility(View.GONE);
            } else if (matchedPreset == 1) {
                turnSignalPresetGroup.check(R.id.rb_preset_e5);
                customKeywordsLayout.setVisibility(View.GONE);
            } else {
                turnSignalPresetGroup.check(R.id.rb_preset_custom);
                customKeywordsLayout.setVisibility(View.VISIBLE);
            }
            carApiStatusText.setVisibility(View.GONE);
        }

        secondaryBlindSpotSwitch.setChecked(appConfig.isSecondaryDisplayEnabled());

        mockFloatingSwitch.setChecked(appConfig.isMockTurnSignalFloatingEnabled());
        floatingWindowAnimationSwitch.setChecked(appConfig.isFloatingWindowAnimationEnabled());

        blindSpotCorrectionSwitch.setChecked(appConfig.isBlindSpotCorrectionEnabled());

        mainFloatingAspectRatioLockSwitch.setChecked(appConfig.isMainFloatingAspectRatioLocked());
    }

    private void updateSubFeaturesVisibility(boolean globalEnabled) {
        // 全局开关关闭时，隐藏所有子功能区域
        subFeaturesContainer.setVisibility(globalEnabled ? View.VISIBLE : View.GONE);
    }

    private void setupListeners() {
        // 全局开关
        blindSpotGlobalSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setBlindSpotGlobalEnabled(isChecked);
            updateSubFeaturesVisibility(isChecked);
            if (!isChecked) {
                // 关闭时，停止补盲服务
                requireContext().stopService(new android.content.Intent(requireContext(), BlindSpotService.class));
            } else {
                // 开启时，如果有子功能已配置，启动服务
                BlindSpotService.update(requireContext());
            }
        });

        openLabButton.setOnClickListener(v -> {
            if (getActivity() == null) return;
            androidx.fragment.app.FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, new BlindSpotLabFragment());
            transaction.addToBackStack(null);
            transaction.commit();
        });

        turnSignalLinkageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                turnSignalLinkageSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setTurnSignalLinkageEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        turnSignalTimeoutSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvTurnSignalTimeout.setText(progress + "s");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                appConfig.setTurnSignalTimeout(seekBar.getProgress());
                BlindSpotService.update(requireContext());
            }
        });

        // 预设方案选择
        turnSignalPresetGroup.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_preset_l6l7 || checkedId == R.id.rb_preset_boyue_l) {
                // CarSignalManager API 模式
                customKeywordsLayout.setVisibility(View.GONE);
                carApiStatusText.setVisibility(View.VISIBLE);
                carApiStatusText.setText("CarSignalManager 服务状态: 检测中...");
                appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_CAR_SIGNAL_MANAGER);
                
                // 保存具体选择的预设（博越L 或 L6/L7）
                if (checkedId == R.id.rb_preset_boyue_l) {
                    appConfig.setTurnSignalPresetSelection("boyue_l");
                } else {
                    appConfig.setTurnSignalPresetSelection("l6l7");
                }
                
                checkCarSignalManagerConnection();
                BlindSpotService.update(requireContext());
            } else if (checkedId == R.id.rb_preset_car_api) {
                // VHAL gRPC 模式
                customKeywordsLayout.setVisibility(View.GONE);
                carApiStatusText.setVisibility(View.VISIBLE);
                carApiStatusText.setText("VHAL gRPC 服务状态: 检测中...");
                appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_VHAL_GRPC);
                checkVhalGrpcConnection();
                BlindSpotService.update(requireContext());
            } else {
                // Logcat 模式
                carApiStatusText.setVisibility(View.GONE);
                appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_LOGCAT);
                if (checkedId == R.id.rb_preset_custom) {
                    customKeywordsLayout.setVisibility(View.VISIBLE);
                } else {
                    customKeywordsLayout.setVisibility(View.GONE);
                    int presetIndex = (checkedId == R.id.rb_preset_xinghan7) ? 0 : 1;
                    applyPreset(presetIndex);
                }
                BlindSpotService.update(requireContext());
            }
        });

        android.text.TextWatcher turnSignalLogWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (isUpdatingFromPreset) return; // 预设填充时不触发保存
                if (turnSignalLeftLogEditText.getEditableText() == s) {
                    appConfig.setTurnSignalCustomLeftTriggerLog(s.toString());
                } else if (turnSignalRightLogEditText.getEditableText() == s) {
                    appConfig.setTurnSignalCustomRightTriggerLog(s.toString());
                } else {
                    return;
                }
                BlindSpotService.update(requireContext());
            }
        };
        turnSignalLeftLogEditText.addTextChangedListener(turnSignalLogWatcher);
        turnSignalRightLogEditText.addTextChangedListener(turnSignalLogWatcher);

        secondaryBlindSpotSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                secondaryBlindSpotSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setSecondaryDisplayEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        floatingWindowAnimationSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setFloatingWindowAnimationEnabled(isChecked);
        });

        mockFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                mockFloatingSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setMockTurnSignalFloatingEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        blindSpotCorrectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setBlindSpotCorrectionEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        mainFloatingAspectRatioLockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setMainFloatingAspectRatioLocked(isChecked);
        });

        resetMainFloatingButton.setOnClickListener(v -> {
            appConfig.resetMainFloatingBounds();
            BlindSpotService.update(requireContext());
            Toast.makeText(requireContext(), "主屏悬浮窗已重置", Toast.LENGTH_SHORT).show();
        });

        adjustBlindSpotCorrectionButton.setOnClickListener(v -> {
            if (!WakeUpHelper.hasOverlayPermission(requireContext())) {
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            if (getActivity() == null) return;
            androidx.fragment.app.FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, new BlindSpotCorrectionFragment());
            transaction.addToBackStack(null);
            transaction.commit();
        });

        adjustSecondaryBlindSpotWindowButton.setOnClickListener(v -> {
            if (!WakeUpHelper.hasOverlayPermission(requireContext())) {
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            if (getActivity() == null) return;
            androidx.fragment.app.FragmentTransaction transaction = getActivity().getSupportFragmentManager().beginTransaction();
            transaction.replace(R.id.fragment_container, new SecondaryBlindSpotAdjustFragment());
            transaction.addToBackStack(null);
            transaction.commit();
        });

        logcatDebugButton.setOnClickListener(v -> {
            String keyword = logFilterEditText.getText().toString().trim();
            if (keyword.isEmpty()) {
                // 没有输入关键词时弹窗提示
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                    .setTitle("提示")
                    .setMessage("未输入过滤关键字，日志量可能很大，可能导致界面卡顿。\n\n建议输入关键字进行过滤，是否继续？")
                    .setPositiveButton("继续打开", (dialog, which) -> {
                        android.content.Intent intent = new android.content.Intent(requireContext(), LogcatViewerActivity.class);
                        intent.putExtra("filter_keyword", "");
                        startActivity(intent);
                    })
                    .setNegativeButton("返回输入", null)
                    .show();
            } else {
                android.content.Intent intent = new android.content.Intent(requireContext(), LogcatViewerActivity.class);
                intent.putExtra("filter_keyword", keyword);
                startActivity(intent);
            }
        });

        menuButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).toggleDrawer();
            }
        });

        homeButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });
    }

    /**
     * 根据当前关键词匹配预设方案
     * @return 预设索引（0=星舰7, 1=E5），-1 表示自定义
     */
    private int findMatchingPreset(String leftKeyword, String rightKeyword) {
        if (leftKeyword == null || rightKeyword == null) return -1;
        for (int i = 0; i < TURN_SIGNAL_PRESETS.length; i++) {
            if (TURN_SIGNAL_PRESETS[i][1].equals(leftKeyword) && TURN_SIGNAL_PRESETS[i][2].equals(rightKeyword)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 应用预设方案：填充关键词并保存配置
     */
    private void applyPreset(int presetIndex) {
        if (presetIndex < 0 || presetIndex >= TURN_SIGNAL_PRESETS.length) return;
        String leftKeyword = TURN_SIGNAL_PRESETS[presetIndex][1];
        String rightKeyword = TURN_SIGNAL_PRESETS[presetIndex][2];

        isUpdatingFromPreset = true;
        turnSignalLeftLogEditText.setText(leftKeyword);
        turnSignalRightLogEditText.setText(rightKeyword);
        isUpdatingFromPreset = false;

        appConfig.setTurnSignalCustomLeftTriggerLog(leftKeyword);
        appConfig.setTurnSignalCustomRightTriggerLog(rightKeyword);
        BlindSpotService.update(requireContext());
    }

    private void maybeShowDisclaimerDialog() {
        if (disclaimerDialogShown) return;
        if (appConfig == null) return;
        if (appConfig.isBlindSpotDisclaimerAccepted()) return;
        disclaimerDialogShown = true;
        new BlindSpotDisclaimerDialogFragment().show(getChildFragmentManager(), "blind_spot_disclaimer");
    }

    /**
     * 异步检查 VHAL gRPC 服务连接状态并更新 UI
     */
    private void checkVhalGrpcConnection() {
        if (carApiStatusText == null) return;
        carApiStatusText.setText("VHAL gRPC 服务状态: 检测中...");
        carApiStatusText.setTextColor(getResources().getColor(R.color.text_secondary, null));

        new Thread(() -> {
            boolean reachable = VhalSignalObserver.testConnection();
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    if (carApiStatusText == null) return;
                    if (reachable) {
                        carApiStatusText.setText("VHAL gRPC 服务状态: ✓ 已连接");
                        carApiStatusText.setTextColor(0xFF4CAF50); // green
                    } else {
                        carApiStatusText.setText("VHAL gRPC 服务状态: ✗ 服务不可达");
                        carApiStatusText.setTextColor(0xFFF44336); // red
                    }
                });
            }
        }).start();
    }

    /**
     * 异步检查 CarSignalManager 服务连接状态并更新 UI
     */
    private void checkCarSignalManagerConnection() {
        if (carApiStatusText == null) return;
        carApiStatusText.setText("CarSignalManager 服务状态: 检测中...");
        carApiStatusText.setTextColor(getResources().getColor(R.color.text_secondary, null));

        new Thread(() -> {
            boolean reachable = CarSignalManagerObserver.testConnection(requireContext());
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    if (carApiStatusText == null) return;
                    if (reachable) {
                        carApiStatusText.setText("CarSignalManager 服务状态: ✓ 已连接");
                        carApiStatusText.setTextColor(0xFF4CAF50); // green
                    } else {
                        carApiStatusText.setText("CarSignalManager 服务状态: ✗ 服务不可达");
                        carApiStatusText.setTextColor(0xFFF44336); // red
                    }
                });
            }
        }).start();
    }

}
