package com.kooo.evcam;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;
import android.text.method.KeyListener;
import android.text.TextWatcher;
import android.text.Editable;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

/**
 * 补盲选项设置界面
 */
public class BlindSpotSettingsFragment extends Fragment {
    private static final String TAG = "BlindSpotSettingsFragment";

    private SwitchMaterial mainFloatingSwitch;
    private Spinner mainFloatingCameraSpinner;
    
    private SwitchMaterial turnSignalLinkageSwitch;
    private SeekBar turnSignalTimeoutSeekBar;
    private TextView tvTurnSignalTimeout;
    private SwitchMaterial reuseMainFloatingSwitch;
    private Button setupBlindSpotPosButton;
    private Spinner turnSignalPresetSpinner;
    private EditText turnSignalLeftLogEditText;
    private EditText turnSignalRightLogEditText;
    private KeyListener turnSignalLeftKeyListener;
    private KeyListener turnSignalRightKeyListener;

    private SwitchMaterial secondaryDisplaySwitch;
    private Spinner cameraSpinner;
    private Spinner displaySpinner;
    private TextView displayInfoText;
    private SeekBar seekbarX, seekbarY, seekbarWidth, seekbarHeight;
    private EditText etX, etY, etWidth, etHeight;
    private Spinner rotationSpinner;
    private Spinner orientationSpinner;
    private SwitchMaterial borderSwitch;
    private SwitchMaterial mockFloatingSwitch;
    private Button saveButton;
    private Button logcatDebugButton;
    private android.widget.EditText logFilterEditText;
    private Button menuButton;
    private Button homeButton;

    private AppConfig appConfig;
    private DisplayManager displayManager;
    private List<Display> availableDisplays = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_secondary_display_settings, container, false);
        appConfig = new AppConfig(requireContext());
        displayManager = (DisplayManager) requireContext().getSystemService(Context.DISPLAY_SERVICE);
        initViews(view);
        loadSettings();
        setupListeners();
        return view;
    }

    private void initViews(View view) {
        // 主屏悬浮窗
        mainFloatingSwitch = view.findViewById(R.id.switch_main_floating);
        mainFloatingCameraSpinner = view.findViewById(R.id.spinner_main_floating_camera);

        // 转向灯联动
        turnSignalLinkageSwitch = view.findViewById(R.id.switch_turn_signal_linkage);
        turnSignalTimeoutSeekBar = view.findViewById(R.id.seekbar_turn_signal_timeout);
        tvTurnSignalTimeout = view.findViewById(R.id.tv_turn_signal_timeout_value);
        reuseMainFloatingSwitch = view.findViewById(R.id.switch_reuse_main_floating);
        setupBlindSpotPosButton = view.findViewById(R.id.btn_setup_blind_spot_pos);
        turnSignalPresetSpinner = view.findViewById(R.id.spinner_turn_signal_preset);
        turnSignalLeftLogEditText = view.findViewById(R.id.et_turn_signal_left_log);
        turnSignalRightLogEditText = view.findViewById(R.id.et_turn_signal_right_log);
        turnSignalLeftKeyListener = turnSignalLeftLogEditText.getKeyListener();
        turnSignalRightKeyListener = turnSignalRightLogEditText.getKeyListener();

        // 副屏显示
        secondaryDisplaySwitch = view.findViewById(R.id.switch_secondary_display);
        cameraSpinner = view.findViewById(R.id.spinner_camera_selection);
        displaySpinner = view.findViewById(R.id.spinner_display_selection);
        displayInfoText = view.findViewById(R.id.tv_display_info);
        seekbarX = view.findViewById(R.id.seekbar_x);
        seekbarY = view.findViewById(R.id.seekbar_y);
        seekbarWidth = view.findViewById(R.id.seekbar_width);
        seekbarHeight = view.findViewById(R.id.seekbar_height);
        etX = view.findViewById(R.id.et_x_value);
        etY = view.findViewById(R.id.et_y_value);
        etWidth = view.findViewById(R.id.et_width_value);
        etHeight = view.findViewById(R.id.et_height_value);
        rotationSpinner = view.findViewById(R.id.spinner_rotation);
        orientationSpinner = view.findViewById(R.id.spinner_screen_orientation);
        borderSwitch = view.findViewById(R.id.switch_border);
        
        mockFloatingSwitch = view.findViewById(R.id.switch_mock_floating);
        
        saveButton = view.findViewById(R.id.btn_save_apply);
        logcatDebugButton = view.findViewById(R.id.btn_logcat_debug);
        logFilterEditText = view.findViewById(R.id.et_log_filter);
        menuButton = view.findViewById(R.id.btn_menu);
        homeButton = view.findViewById(R.id.btn_home);

        // 初始化 Spinner 数据
        String[] cameras = {"front", "back", "left", "right"};
        String[] cameraNames = {"前摄像头", "后摄像头", "左摄像头", "右摄像头"};
        ArrayAdapter<String> cameraAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, cameraNames);
        cameraAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraSpinner.setAdapter(cameraAdapter);
        mainFloatingCameraSpinner.setAdapter(cameraAdapter);

        String[] rotations = {"0°", "90°", "180°", "270°"};
        ArrayAdapter<String> rotationAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, rotations);
        rotationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rotationSpinner.setAdapter(rotationAdapter);

        String[] orientations = {"正常 (0°)", "顺时针90°", "倒置 (180°)", "逆时针90°"};
        ArrayAdapter<String> orientationAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, orientations);
        orientationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        orientationSpinner.setAdapter(orientationAdapter);

        String[] turnSignalPresets = {"2026款星舰7（默认）", "自定义车型"};
        ArrayAdapter<String> turnSignalPresetAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, turnSignalPresets);
        turnSignalPresetAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        turnSignalPresetSpinner.setAdapter(turnSignalPresetAdapter);

        // 检测显示器
        updateDisplayList();
    }

    private void updateDisplayList() {
        Display[] displays = displayManager.getDisplays();
        availableDisplays.clear();
        List<String> displayNames = new ArrayList<>();
        for (Display d : displays) {
            availableDisplays.add(d);
            displayNames.add("Display " + d.getDisplayId() + (d.getDisplayId() == 0 ? " (主屏)" : ""));
        }
        ArrayAdapter<String> displayAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, displayNames);
        displayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        displaySpinner.setAdapter(displayAdapter);
    }

    private void loadSettings() {
        // 主屏悬浮窗
        mainFloatingSwitch.setChecked(appConfig.isMainFloatingEnabled());
        String mainCam = appConfig.getMainFloatingCamera();
        mainFloatingCameraSpinner.setSelection(getCameraIndex(mainCam));

        // 转向灯联动
        turnSignalLinkageSwitch.setChecked(appConfig.isTurnSignalLinkageEnabled());
        int timeout = appConfig.getTurnSignalTimeout();
        turnSignalTimeoutSeekBar.setProgress(timeout);
        tvTurnSignalTimeout.setText(timeout + "s");
        reuseMainFloatingSwitch.setChecked(appConfig.isTurnSignalReuseMainFloating());
        setupBlindSpotPosButton.setVisibility(appConfig.isTurnSignalReuseMainFloating() ? View.GONE : View.VISIBLE);
        turnSignalPresetSpinner.setSelection(appConfig.isTurnSignalCustomPreset() ? 1 : 0);
        applyTurnSignalPresetUi(appConfig.isTurnSignalCustomPreset() ? 1 : 0, false);

        // 副屏显示
        secondaryDisplaySwitch.setChecked(appConfig.isSecondaryDisplayEnabled());
        String camera = appConfig.getSecondaryDisplayCamera();
        cameraSpinner.setSelection(getCameraIndex(camera));

        int displayId = appConfig.getSecondaryDisplayId();
        for (int i = 0; i < availableDisplays.size(); i++) {
            if (availableDisplays.get(i).getDisplayId() == displayId) {
                displaySpinner.setSelection(i);
                updateDisplayInfo(availableDisplays.get(i));
                break;
            }
        }

        seekbarX.setProgress(appConfig.getSecondaryDisplayX());
        seekbarY.setProgress(appConfig.getSecondaryDisplayY());
        seekbarWidth.setProgress(appConfig.getSecondaryDisplayWidth());
        seekbarHeight.setProgress(appConfig.getSecondaryDisplayHeight());
        
        etX.setText(String.valueOf(appConfig.getSecondaryDisplayX()));
        etY.setText(String.valueOf(appConfig.getSecondaryDisplayY()));
        etWidth.setText(String.valueOf(appConfig.getSecondaryDisplayWidth()));
        etHeight.setText(String.valueOf(appConfig.getSecondaryDisplayHeight()));

        rotationSpinner.setSelection(appConfig.getSecondaryDisplayRotation() / 90);
        orientationSpinner.setSelection(appConfig.getSecondaryDisplayOrientation() / 90);
        borderSwitch.setChecked(appConfig.isSecondaryDisplayBorderEnabled());

        mockFloatingSwitch.setChecked(appConfig.isMockTurnSignalFloatingEnabled());
    }

    private void applyTurnSignalPresetUi(int presetIndex, boolean persist) {
        if (presetIndex == 0) {
            if (persist) {
                appConfig.setTurnSignalLogPreset(AppConfig.TURN_SIGNAL_LOG_PRESET_XINGHAN7_2026);
            }
            setTurnSignalLogEditable(false);
            turnSignalLeftLogEditText.setText("data1 = 85");
            turnSignalRightLogEditText.setText("data1 = 170");
        } else {
            if (persist) {
                appConfig.setTurnSignalLogPreset(AppConfig.TURN_SIGNAL_LOG_PRESET_CUSTOM);
            }
            setTurnSignalLogEditable(true);
            turnSignalLeftLogEditText.setText(appConfig.getTurnSignalCustomLeftTriggerLog());
            turnSignalRightLogEditText.setText(appConfig.getTurnSignalCustomRightTriggerLog());
        }
    }

    private void setTurnSignalLogEditable(boolean editable) {
        if (editable) {
            turnSignalLeftLogEditText.setKeyListener(turnSignalLeftKeyListener);
            turnSignalRightLogEditText.setKeyListener(turnSignalRightKeyListener);
            turnSignalLeftLogEditText.setCursorVisible(true);
            turnSignalRightLogEditText.setCursorVisible(true);
            turnSignalLeftLogEditText.setFocusable(true);
            turnSignalRightLogEditText.setFocusable(true);
            turnSignalLeftLogEditText.setFocusableInTouchMode(true);
            turnSignalRightLogEditText.setFocusableInTouchMode(true);
            turnSignalLeftLogEditText.setLongClickable(true);
            turnSignalRightLogEditText.setLongClickable(true);
            turnSignalLeftLogEditText.setTextIsSelectable(true);
            turnSignalRightLogEditText.setTextIsSelectable(true);
        } else {
            turnSignalLeftLogEditText.setKeyListener(null);
            turnSignalRightLogEditText.setKeyListener(null);
            turnSignalLeftLogEditText.setCursorVisible(false);
            turnSignalRightLogEditText.setCursorVisible(false);
            turnSignalLeftLogEditText.setFocusable(false);
            turnSignalRightLogEditText.setFocusable(false);
            turnSignalLeftLogEditText.setFocusableInTouchMode(false);
            turnSignalRightLogEditText.setFocusableInTouchMode(false);
            turnSignalLeftLogEditText.setLongClickable(false);
            turnSignalRightLogEditText.setLongClickable(false);
            turnSignalLeftLogEditText.setTextIsSelectable(false);
            turnSignalRightLogEditText.setTextIsSelectable(false);
        }
    }

    private int getCameraIndex(String pos) {
        switch (pos) {
            case "front": return 0;
            case "back": return 1;
            case "left": return 2;
            case "right": return 3;
            default: return 0;
        }
    }

    private String getCameraPos(int index) {
        switch (index) {
            case 0: return "front";
            case 1: return "back";
            case 2: return "left";
            case 3: return "right";
            default: return "front";
        }
    }

    private void setupListeners() {
        mainFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                mainFloatingSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
            }
        });

        turnSignalLinkageSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                turnSignalLinkageSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
            }
        });

        turnSignalTimeoutSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvTurnSignalTimeout.setText(progress + "s");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        turnSignalPresetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) {
                    first = false;
                    return;
                }
                applyTurnSignalPresetUi(position, true);
                BlindSpotService.update(requireContext());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        android.text.TextWatcher turnSignalLogWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(android.text.Editable s) {
                if (!appConfig.isTurnSignalCustomPreset()) return;
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

        secondaryDisplaySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                secondaryDisplaySwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
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

        displaySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                updateDisplayInfo(availableDisplays.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        SeekBar.OnSeekBarChangeListener seekBarChangeListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (seekBar == seekbarX) etX.setText(String.valueOf(progress));
                else if (seekBar == seekbarY) etY.setText(String.valueOf(progress));
                else if (seekBar == seekbarWidth) etWidth.setText(String.valueOf(progress));
                else if (seekBar == seekbarHeight) etHeight.setText(String.valueOf(progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        seekbarX.setOnSeekBarChangeListener(seekBarChangeListener);
        seekbarY.setOnSeekBarChangeListener(seekBarChangeListener);
        seekbarWidth.setOnSeekBarChangeListener(seekBarChangeListener);
        seekbarHeight.setOnSeekBarChangeListener(seekBarChangeListener);

        // EditText 监听器
        android.text.TextWatcher textWatcher = new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(android.text.Editable s) {
                try {
                    int val = Integer.parseInt(s.toString());
                    if (etX.getEditableText() == s) seekbarX.setProgress(val);
                    else if (etY.getEditableText() == s) seekbarY.setProgress(val);
                    else if (etWidth.getEditableText() == s) seekbarWidth.setProgress(val);
                    else if (etHeight.getEditableText() == s) seekbarHeight.setProgress(val);
                } catch (Exception e) {}
            }
        };
        etX.addTextChangedListener(textWatcher);
        etY.addTextChangedListener(textWatcher);
        etWidth.addTextChangedListener(textWatcher);
        etHeight.addTextChangedListener(textWatcher);

        reuseMainFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setTurnSignalReuseMainFloating(isChecked);
            setupBlindSpotPosButton.setVisibility(isChecked ? View.GONE : View.VISIBLE);
        });

        setupBlindSpotPosButton.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), BlindSpotService.class);
            intent.putExtra("action", "setup_blind_spot_window");
            requireContext().startService(intent);
        });

        saveButton.setOnClickListener(v -> saveAndApply());

        logcatDebugButton.setOnClickListener(v -> {
            android.content.Intent intent = new android.content.Intent(requireContext(), LogcatViewerActivity.class);
            intent.putExtra("filter_keyword", logFilterEditText.getText().toString());
            startActivity(intent);
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

    private void updateDisplayInfo(Display display) {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        display.getRealMetrics(metrics);
        displayInfoText.setText(String.format("当前屏幕分辨率: %d x %d", metrics.widthPixels, metrics.heightPixels));
        
        seekbarX.setMax(metrics.widthPixels);
        seekbarY.setMax(metrics.heightPixels);
        seekbarWidth.setMax(metrics.widthPixels);
        seekbarHeight.setMax(metrics.heightPixels);
    }

    private void saveAndApply() {
        // 主屏悬浮窗
        appConfig.setMainFloatingEnabled(mainFloatingSwitch.isChecked());
        appConfig.setMainFloatingCamera(getCameraPos(mainFloatingCameraSpinner.getSelectedItemPosition()));

        // 转向灯联动
        appConfig.setTurnSignalLinkageEnabled(turnSignalLinkageSwitch.isChecked());
        appConfig.setTurnSignalTimeout(turnSignalTimeoutSeekBar.getProgress());
        appConfig.setTurnSignalReuseMainFloating(reuseMainFloatingSwitch.isChecked());

        // 副屏显示
        appConfig.setSecondaryDisplayEnabled(secondaryDisplaySwitch.isChecked());
        appConfig.setSecondaryDisplayCamera(getCameraPos(cameraSpinner.getSelectedItemPosition()));
        
        int displayId = availableDisplays.get(displaySpinner.getSelectedItemPosition()).getDisplayId();
        appConfig.setSecondaryDisplayId(displayId);
        
        appConfig.setSecondaryDisplayBounds(
                seekbarX.getProgress(),
                seekbarY.getProgress(),
                seekbarWidth.getProgress(),
                seekbarHeight.getProgress()
        );
        
        appConfig.setSecondaryDisplayRotation(rotationSpinner.getSelectedItemPosition() * 90);
        appConfig.setSecondaryDisplayOrientation(orientationSpinner.getSelectedItemPosition() * 90);
        appConfig.setSecondaryDisplayBorderEnabled(borderSwitch.isChecked());

        Toast.makeText(requireContext(), "配置已保存并应用", Toast.LENGTH_SHORT).show();
        
        // 触发服务更新
        BlindSpotService.update(requireContext());
    }
}
