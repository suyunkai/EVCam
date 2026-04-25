package com.kooo.evcam;

import android.content.Context;
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
    
    // 车门联动UI控件
    private LinearLayout doorLinkageSectionLayout; // 车门联动区域
    private SwitchMaterial doorLinkageSwitch; // 车门联动开关

    // 长视模式UI控件
    private SwitchMaterial longViewModeSwitch; // 长视模式开关
    private TextView tvLongViewModeDesc; // 长视模式描述
    private Button longViewAdjustButton; // 长视模式位置和大小调整按钮

    // 全景影像避让UI控件
    private SwitchMaterial avmAvoidanceSwitch;
    private LinearLayout avmAvoidanceDetailLayout;
    private EditText avmAvoidanceActivityEditText;
    private RadioGroup avmAvoidanceBehaviorGroup;

    // 转向灯触发log预设方案
    private static final String[][] TURN_SIGNAL_PRESETS = {
        // { presetId, leftKeyword, rightKeyword }
        { "xinghan7", "left front turn signal:1", "right front turn signal:1" },
    };

    private TextView carApiStatusText;

    private SwitchMaterial blindSpotGlobalSwitch;
    private android.widget.LinearLayout subFeaturesContainer;
    private SwitchMaterial secondaryBlindSpotSwitch;
    private Button adjustSecondaryBlindSpotWindowButton;
    private SwitchMaterial mockFloatingSwitch;
    private SwitchMaterial floatingWindowAnimationSwitch;
    private RadioGroup statusBarStyleGroup;
    private View statusBarColorPreview;
    private Button pickStatusBarColorButton;
    private SeekBar statusBarOpacitySeekBar;
    private TextView tvStatusBarOpacityValue;
    private SwitchMaterial blindSpotCorrectionSwitch;
    private Button adjustBlindSpotCorrectionButton;
    private SwitchMaterial mainFloatingAspectRatioLockSwitch;
    private SwitchMaterial mainFloatingLongPressDragSwitch;
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
        
        // 车门联动UI初始化
        doorLinkageSectionLayout = view.findViewById(R.id.ll_door_linkage_section);
        doorLinkageSwitch = view.findViewById(R.id.switch_door_linkage);

        // 长视模式UI初始化
        longViewModeSwitch = view.findViewById(R.id.switch_long_view_mode);
        tvLongViewModeDesc = view.findViewById(R.id.tv_long_view_mode_desc);
        longViewAdjustButton = view.findViewById(R.id.btn_long_view_adjust);
        
        // 长视模式调整按钮点击事件
        if (longViewAdjustButton != null) {
            longViewAdjustButton.setOnClickListener(v -> {
                if (!WakeUpHelper.hasOverlayPermission(requireContext())) {
                    Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                    WakeUpHelper.requestOverlayPermission(requireContext());
                    return;
                }
                // 打开长视模式设置界面（使用超视模式的配置）
                openLongViewSetupMode();
            });
        }

        // 全景影像避让UI初始化
        avmAvoidanceSwitch = view.findViewById(R.id.switch_avm_avoidance);
        avmAvoidanceDetailLayout = view.findViewById(R.id.layout_avm_avoidance_detail);
        avmAvoidanceActivityEditText = view.findViewById(R.id.et_avm_avoidance_activity);
        avmAvoidanceBehaviorGroup = view.findViewById(R.id.rg_avm_avoidance_behavior);

        mockFloatingSwitch = view.findViewById(R.id.switch_mock_floating);
        floatingWindowAnimationSwitch = view.findViewById(R.id.switch_floating_window_animation);
        statusBarStyleGroup = view.findViewById(R.id.rg_status_bar_style);
        statusBarColorPreview = view.findViewById(R.id.view_status_bar_color_preview);
        pickStatusBarColorButton = view.findViewById(R.id.btn_pick_status_bar_color);
        statusBarOpacitySeekBar = view.findViewById(R.id.seekbar_status_bar_opacity);
        tvStatusBarOpacityValue = view.findViewById(R.id.tv_status_bar_opacity_value);

        blindSpotCorrectionSwitch = view.findViewById(R.id.switch_blind_spot_correction);
        adjustBlindSpotCorrectionButton = view.findViewById(R.id.btn_adjust_blind_spot_correction);

        mainFloatingAspectRatioLockSwitch = view.findViewById(R.id.switch_main_floating_aspect_ratio_lock);
        mainFloatingLongPressDragSwitch = view.findViewById(R.id.switch_main_floating_long_press_drag);
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
        
        // 长视模式配置加载
        boolean longViewEnabled = appConfig.isLongViewModeEnabled();
        longViewModeSwitch.setChecked(longViewEnabled);
        if (longViewAdjustButton != null) {
            longViewAdjustButton.setVisibility(longViewEnabled ? View.VISIBLE : View.GONE);
        }

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
            carApiStatusText.setText("车辆API 服务状态: 检测中...");
            checkVhalGrpcConnection();
        } else {
            int matchedPreset = findMatchingPreset(currentLeft, currentRight);
            if (matchedPreset == 0) {
                turnSignalPresetGroup.check(R.id.rb_preset_xinghan7);
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

        int statusBarStyle = appConfig.getBlindSpotStatusBarStyle();
        switch (statusBarStyle) {
            case BlindSpotStatusBarView.STYLE_OFF:           statusBarStyleGroup.check(R.id.rb_style_off); break;
            case BlindSpotStatusBarView.STYLE_COMET:         statusBarStyleGroup.check(R.id.rb_style_comet); break;
            case BlindSpotStatusBarView.STYLE_RIPPLE:        statusBarStyleGroup.check(R.id.rb_style_ripple); break;
            case BlindSpotStatusBarView.STYLE_GRADIENT_FILL: statusBarStyleGroup.check(R.id.rb_style_gradient_fill); break;
            case BlindSpotStatusBarView.STYLE_ARROW_RIPPLE:  statusBarStyleGroup.check(R.id.rb_style_arrow_ripple); break;
            default:                                         statusBarStyleGroup.check(R.id.rb_style_sequential); break;
        }

        updateColorPreview(appConfig.getBlindSpotStatusBarColor());

        int opacity = appConfig.getBlindSpotStatusBarBgOpacity();
        statusBarOpacitySeekBar.setProgress(opacity);
        tvStatusBarOpacityValue.setText(opacity + "%");

        blindSpotCorrectionSwitch.setChecked(appConfig.isBlindSpotCorrectionEnabled());

        mainFloatingAspectRatioLockSwitch.setChecked(appConfig.isMainFloatingAspectRatioLocked());
        mainFloatingLongPressDragSwitch.setChecked(appConfig.isMainFloatingLongPressDragEnabled());
        
        // 车门联动配置加载
        doorLinkageSwitch.setChecked(appConfig.isDoorLinkageEnabled());
        
        // 根据转向联动的车型选择，决定是否显示车门联动区域
        updateDoorLinkageVisibility();

        // 全景影像避让配置加载
        boolean avmEnabled = appConfig.isAvmAvoidanceEnabled();
        avmAvoidanceSwitch.setChecked(avmEnabled);
        avmAvoidanceDetailLayout.setVisibility(avmEnabled ? View.VISIBLE : View.GONE);
        avmAvoidanceActivityEditText.setText(appConfig.getAvmAvoidanceActivity());
        switch (appConfig.getAvmAvoidanceBehavior()) {
            case AppConfig.AVM_AVOIDANCE_BEHAVIOR_STOP_RECORDING:
                avmAvoidanceBehaviorGroup.check(R.id.rb_avm_behavior_stop_recording);
                break;
            case AppConfig.AVM_AVOIDANCE_BEHAVIOR_STOP_PREVIEW_AND_RECORDING:
                avmAvoidanceBehaviorGroup.check(R.id.rb_avm_behavior_stop_preview_recording);
                break;
            case AppConfig.AVM_AVOIDANCE_BEHAVIOR_BACKGROUND:
            default:
                avmAvoidanceBehaviorGroup.check(R.id.rb_avm_behavior_background);
                break;
        }

    }

    private void updateSubFeaturesVisibility(boolean globalEnabled) {
        // 全局开关关闭时，隐藏所有子功能区域
        subFeaturesContainer.setVisibility(globalEnabled ? View.VISIBLE : View.GONE);
    }
    
    /**
     * 根据转向联动的车型选择，更新车门联动区域的可见性
     * 选择了银河L6/L7、博越L或车载API(E5/星舰7)时，显示车门联动开关
     */
    private void updateDoorLinkageVisibility() {
        // 检查车型选择
        String turnSignalPreset = appConfig.getTurnSignalPresetSelection();
        boolean supportsDoorLinkage = "l6l7".equals(turnSignalPreset)
                || "boyue_l".equals(turnSignalPreset)
                || "car_api".equals(turnSignalPreset);

        // 支持车门联动的车型才显示（不依赖转向联动开关）
        doorLinkageSectionLayout.setVisibility(supportsDoorLinkage ? View.VISIBLE : View.GONE);

        // 如果不应该显示（切换到其他车型），自动关闭车门联动
        if (!supportsDoorLinkage && appConfig.isDoorLinkageEnabled()) {
            appConfig.setDoorLinkageEnabled(false);
            doorLinkageSwitch.setChecked(false);
        }
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

        // 长视模式监听器
        longViewModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                longViewModeSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setLongViewModeEnabled(isChecked);
            // 显示/隐藏调整按钮
            if (longViewAdjustButton != null) {
                longViewAdjustButton.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            }
            // 长视模式启用时，显示提示
            if (isChecked) {
                Toast.makeText(requireContext(), "长视模式已启用：打转向灯将显示双画面", Toast.LENGTH_SHORT).show();
            }
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
                
                // 更新车门联动区域可见性
                updateDoorLinkageVisibility();
                
                checkCarSignalManagerConnection();
                BlindSpotService.update(requireContext());
            } else if (checkedId == R.id.rb_preset_car_api) {
                // 车辆API 模式
                customKeywordsLayout.setVisibility(View.GONE);
                carApiStatusText.setVisibility(View.VISIBLE);
                carApiStatusText.setText("车辆API 服务状态: 检测中...");
                appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_VHAL_GRPC);
                appConfig.setTurnSignalPresetSelection("car_api");
                
                // 更新车门联动区域可见性（会自动处理关闭逻辑）
                updateDoorLinkageVisibility();
                
                checkVhalGrpcConnection();
                BlindSpotService.update(requireContext());
            } else {
                // Logcat 模式
                carApiStatusText.setVisibility(View.GONE);
                appConfig.setTurnSignalTriggerMode(AppConfig.TRIGGER_MODE_LOGCAT);
                
                // 保存具体选择的预设
                if (checkedId == R.id.rb_preset_custom) {
                    appConfig.setTurnSignalPresetSelection("custom");
                    customKeywordsLayout.setVisibility(View.VISIBLE);
                } else if (checkedId == R.id.rb_preset_xinghan7) {
                    appConfig.setTurnSignalPresetSelection("xinghan7");
                    customKeywordsLayout.setVisibility(View.GONE);
                    applyPreset(0);
                } else {
                    appConfig.setTurnSignalPresetSelection("e5");
                    customKeywordsLayout.setVisibility(View.GONE);
                    applyPreset(1);
                }
                
                // 更新车门联动区域可见性（会自动处理关闭逻辑）
                updateDoorLinkageVisibility();
                
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

        statusBarStyleGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int style;
            if (checkedId == R.id.rb_style_off)            style = BlindSpotStatusBarView.STYLE_OFF;
            else if (checkedId == R.id.rb_style_comet)         style = BlindSpotStatusBarView.STYLE_COMET;
            else if (checkedId == R.id.rb_style_ripple)        style = BlindSpotStatusBarView.STYLE_RIPPLE;
            else if (checkedId == R.id.rb_style_gradient_fill) style = BlindSpotStatusBarView.STYLE_GRADIENT_FILL;
            else if (checkedId == R.id.rb_style_arrow_ripple)  style = BlindSpotStatusBarView.STYLE_ARROW_RIPPLE;
            else                                               style = BlindSpotStatusBarView.STYLE_SEQUENTIAL;
            appConfig.setBlindSpotStatusBarStyle(style);
            BlindSpotService.update(requireContext());
        });

        pickStatusBarColorButton.setOnClickListener(v -> showColorPickerDialog());

        statusBarOpacitySeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvStatusBarOpacityValue.setText(progress + "%");
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                appConfig.setBlindSpotStatusBarBgOpacity(seekBar.getProgress());
                BlindSpotService.update(requireContext());
            }
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

        // ==================== 车门联动监听器 ====================
        
        doorLinkageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                doorLinkageSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setDoorLinkageEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        blindSpotCorrectionSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setBlindSpotCorrectionEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        mainFloatingAspectRatioLockSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setMainFloatingAspectRatioLocked(isChecked);
        });

        mainFloatingLongPressDragSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setMainFloatingLongPressDragEnabled(isChecked);
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

        // 调整车门副屏悬浮窗位置按钮
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

        // ==================== 全景影像避让监听器 ====================

        avmAvoidanceSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setAvmAvoidanceEnabled(isChecked);
            avmAvoidanceDetailLayout.setVisibility(isChecked ? View.VISIBLE : View.GONE);
            BlindSpotService.update(requireContext());
        });

        avmAvoidanceActivityEditText.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                String activity = s.toString().trim();
                appConfig.setAvmAvoidanceActivity(activity);
                BlindSpotService.update(requireContext());
            }
        });

        avmAvoidanceBehaviorGroup.setOnCheckedChangeListener((group, checkedId) -> {
            int behavior;
            if (checkedId == R.id.rb_avm_behavior_stop_recording) {
                behavior = AppConfig.AVM_AVOIDANCE_BEHAVIOR_STOP_RECORDING;
            } else if (checkedId == R.id.rb_avm_behavior_stop_preview_recording) {
                behavior = AppConfig.AVM_AVOIDANCE_BEHAVIOR_STOP_PREVIEW_AND_RECORDING;
            } else {
                behavior = AppConfig.AVM_AVOIDANCE_BEHAVIOR_BACKGROUND;
            }
            appConfig.setAvmAvoidanceBehavior(behavior);
            BlindSpotService.update(requireContext());
        });

    }

    /**
     * 根据当前关键词匹配预设方案
     * @return 预设索引（0=星舰7），-1 表示自定义
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
     * 异步检查车辆API 服务连接状态并更新 UI
     */
    private void checkVhalGrpcConnection() {
        if (carApiStatusText == null) return;
        carApiStatusText.setText("车辆API 服务状态: 检测中...");
        carApiStatusText.setTextColor(getResources().getColor(R.color.text_secondary, null));

        new Thread(() -> {
            boolean reachable = VhalSignalObserver.testConnection();
            if (getActivity() != null && isAdded()) {
                getActivity().runOnUiThread(() -> {
                    if (carApiStatusText == null) return;
                    if (reachable) {
                        carApiStatusText.setText("车辆API 服务状态: ✓ 已连接");
                        carApiStatusText.setTextColor(0xFF4CAF50); // green
                    } else {
                        carApiStatusText.setText("车辆API 服务状态: ✗ 服务不可达");
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

    /**
     * 检查车门API连接状态
     */

    private void updateColorPreview(int color) {
        if (statusBarColorPreview == null) return;
        android.graphics.drawable.GradientDrawable gd = new android.graphics.drawable.GradientDrawable();
        gd.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        gd.setColor(color);
        gd.setStroke((int) (1.5f * getResources().getDisplayMetrics().density), 0x40FFFFFF);
        statusBarColorPreview.setBackground(gd);
    }

    private void showColorPickerDialog() {
        ColorPickerView picker = new ColorPickerView(requireContext());
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        picker.setPadding(pad, pad, pad, pad);
        picker.setColor(appConfig.getBlindSpotStatusBarColor());

        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext(), R.style.Theme_Cam_MaterialAlertDialog)
                .setTitle("选择动效颜色")
                .setView(picker)
                .setPositiveButton("确定", (dialog, which) -> {
                    int color = picker.getColor();
                    appConfig.setBlindSpotStatusBarColor(color);
                    updateColorPreview(color);
                    BlindSpotService.update(requireContext());
                })
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", (dialog, which) -> {
                    int defaultColor = 0xFFFFBF40;
                    appConfig.setBlindSpotStatusBarColor(defaultColor);
                    updateColorPreview(defaultColor);
                    BlindSpotService.update(requireContext());
                })
                .show();
    }
    
    /**
     * 打开长视模式设置界面
     * 使用超视模式的配置，显示左右两个画面供用户调整位置和大小
     */
    private void openLongViewSetupMode() {
        if (getContext() == null) return;
        
        Context context = getContext();
        
        // 创建左视悬浮窗（设置模式）
        BlindSpotFloatingWindowView leftWindow = new BlindSpotFloatingWindowView(context, true);
        leftWindow.setCameraPos("left");
        leftWindow.show();
        leftWindow.setCamera("left");
        leftWindow.updateStatusLabel("left");
        leftWindow.setSupervisionMode(true); // 启用超视模式交互
        
        // 创建右视悬浮窗（设置模式）
        BlindSpotFloatingWindowView rightWindow = new BlindSpotFloatingWindowView(context, true);
        rightWindow.setCameraPos("right");
        rightWindow.show();
        rightWindow.setCamera("right");
        rightWindow.updateStatusLabel("right");
        rightWindow.setSupervisionMode(true); // 启用超视模式交互
        
        // 设置配对关系，确保调整时同步
        leftWindow.setSupervisionPartner(rightWindow);
        rightWindow.setSupervisionPartner(leftWindow);
        
        // 启动前台服务（确保摄像头可用）
        CameraForegroundService.start(context, "长视模式设置", "正在调整长视模式位置和大小");
        
        // 确保摄像头已初始化
        com.kooo.evcam.camera.CameraManagerHolder.getInstance().getOrInit(context);
        
        Toast.makeText(context, "拖动窗口调整位置，拖动边缘调整大小\n调整完成后点击保存按钮", Toast.LENGTH_LONG).show();
    }
}
