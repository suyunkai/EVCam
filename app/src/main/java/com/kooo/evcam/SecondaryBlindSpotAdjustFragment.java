package com.kooo.evcam;

import android.content.Context;
import android.content.Intent;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.ArrayList;
import java.util.List;

public class SecondaryBlindSpotAdjustFragment extends Fragment {
    private Button backButton;
    private Button homeButton;
    private Spinner displaySpinner;
    private TextView displayInfoText;
    private SeekBar seekbarX, seekbarY, seekbarWidth, seekbarHeight;
    private EditText etX, etY, etWidth, etHeight;
    private Spinner rotationSpinner;
    private Spinner orientationSpinner;
    private SwitchMaterial borderSwitch;
    private SeekBar seekbarAlpha;
    private TextView tvAlphaValue;
    private Button saveButton;

    private AppConfig appConfig;
    private DisplayManager displayManager;
    private final List<Display> availableDisplays = new ArrayList<>();
    /** 与 availableDisplays 对齐：真实屏幕用 Display.getDisplayId()，EVCC 仪表投屏占位项用 PICK_ID(-100)。 */
    private final List<Integer> availableDisplayIds = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_secondary_blind_spot_adjust, container, false);
        appConfig = new AppConfig(requireContext());
        displayManager = (DisplayManager) requireContext().getSystemService(Context.DISPLAY_SERVICE);
        initViews(view);
        initSpinners();
        updateDisplayList();
        loadSettings();
        setupListeners();
        enterAdjustMode();
        return view;
    }

    @Override
    public void onDestroyView() {
        exitAdjustMode();
        super.onDestroyView();
    }

    private void initViews(View view) {
        backButton = view.findViewById(R.id.btn_back);
        homeButton = view.findViewById(R.id.btn_home);
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
        seekbarAlpha = view.findViewById(R.id.seekbar_alpha);
        tvAlphaValue = view.findViewById(R.id.tv_alpha_value);
        saveButton = view.findViewById(R.id.btn_save_apply);
    }

    private void initSpinners() {
        String[] rotations = {"0°", "90°", "180°", "270°"};
        ArrayAdapter<String> rotationAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, rotations);
        rotationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        rotationSpinner.setAdapter(rotationAdapter);

        String[] orientations = {"Normal (0°)", "90° CW", "Inverted (180°)", "90° CCW"};
        ArrayAdapter<String> orientationAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, orientations);
        orientationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        orientationSpinner.setAdapter(orientationAdapter);
    }

    private void updateDisplayList() {
        Display[] displays = displayManager.getDisplays();
        availableDisplays.clear();
        availableDisplayIds.clear();
        List<String> displayNames = new ArrayList<>();
        for (Display d : displays) {
            availableDisplays.add(d);
            availableDisplayIds.add(d.getDisplayId());
            displayNames.add("Display " + d.getDisplayId() + (d.getDisplayId() == 0 ? " (Main Screen)" : ""));
        }
        // 追加 EVCC 仪表投屏占位项：实际 displayId 在运行时由 EvccDashcastDisplayResolver
        // 从 EVCC ContentProvider 解析；保存到 SharedPreferences 的就是 PICK_ID(-100)。
        availableDisplays.add(null);
        availableDisplayIds.add(EvccDashcastDisplayResolver.PICK_ID);
        displayNames.add("EVCC Instrument Cast");
        ArrayAdapter<String> displayAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, displayNames);
        displayAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        displaySpinner.setAdapter(displayAdapter);
    }

    private void loadSettings() {
        int displayId = appConfig.getSecondaryDisplayId();
        for (int i = 0; i < availableDisplayIds.size(); i++) {
            if (availableDisplayIds.get(i) == displayId) {
                displaySpinner.setSelection(i);
                updateDisplayInfoFor(i);
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

        int alpha = appConfig.getSecondaryDisplayAlpha();
        seekbarAlpha.setProgress(alpha);
        tvAlphaValue.setText(alpha + "%");
    }

    private void setupListeners() {
        backButton.setOnClickListener(v -> {
            if (getActivity() == null) return;
            getActivity().getSupportFragmentManager().popBackStack();
        });

        homeButton.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).goToRecordingInterface();
            }
        });

        displaySpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < 0 || position >= availableDisplayIds.size()) return;
                updateDisplayInfoFor(position);
                if (first) {
                    first = false;
                    return;
                }
                appConfig.setSecondaryDisplayId(availableDisplayIds.get(position));
                BlindSpotService.update(requireContext());
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
                persistBoundsAndUpdate();
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
                } catch (Exception e) {
                    return;
                }
                persistBoundsAndUpdate();
            }
        };
        etX.addTextChangedListener(textWatcher);
        etY.addTextChangedListener(textWatcher);
        etWidth.addTextChangedListener(textWatcher);
        etHeight.addTextChangedListener(textWatcher);

        rotationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) {
                    first = false;
                    return;
                }
                appConfig.setSecondaryDisplayRotation(position * 90);
                BlindSpotService.update(requireContext());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        orientationSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (first) {
                    first = false;
                    return;
                }
                appConfig.setSecondaryDisplayOrientation(position * 90);
                BlindSpotService.update(requireContext());
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        borderSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setSecondaryDisplayBorderEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        seekbarAlpha.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                tvAlphaValue.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                appConfig.setSecondaryDisplayAlpha(seekBar.getProgress());
                BlindSpotService.update(requireContext());
            }
        });

        saveButton.setOnClickListener(v -> {
            persistAllAndUpdate();
            Toast.makeText(requireContext(), "Configuration saved and applied", Toast.LENGTH_SHORT).show();
        });
    }

    private void updateDisplayInfoFor(int position) {
        if (position < 0 || position >= availableDisplayIds.size()) return;
        int idAtPos = availableDisplayIds.get(position);
        Display real = availableDisplays.get(position);
        if (idAtPos == EvccDashcastDisplayResolver.PICK_ID) {
            updateEvccPickInfo();
        } else if (real != null) {
            updateDisplayInfo(real);
        }
    }

    private void updateEvccPickInfo() {
        EvccDashcastDisplayResolver.Info info = EvccDashcastDisplayResolver.queryPrimary(requireContext());
        if (info != null) {
            displayInfoText.setText(String.format("EVCC Instrument Cast: id=%d %d x %d", info.displayId, info.widthPx, info.heightPx));
            // 用真实尺寸限制 SeekBar 上限，保持与普通屏一致的体验
            seekbarX.setMax(Math.max(info.widthPx, 1));
            seekbarY.setMax(Math.max(info.heightPx, 1));
            seekbarWidth.setMax(Math.max(info.widthPx, 1));
            seekbarHeight.setMax(Math.max(info.heightPx, 1));
        } else {
            displayInfoText.setText("EVCC Instrument Cast not running; display will cast automatically when EVCC starts");
        }
    }

    private void persistBoundsAndUpdate() {
        appConfig.setSecondaryDisplayBounds(
                seekbarX.getProgress(),
                seekbarY.getProgress(),
                seekbarWidth.getProgress(),
                seekbarHeight.getProgress()
        );
        BlindSpotService.update(requireContext());
    }

    private void persistAllAndUpdate() {
        int displayPosition = displaySpinner.getSelectedItemPosition();
        if (displayPosition >= 0 && displayPosition < availableDisplayIds.size()) {
            appConfig.setSecondaryDisplayId(availableDisplayIds.get(displayPosition));
        }
        persistBoundsAndUpdate();
        appConfig.setSecondaryDisplayRotation(rotationSpinner.getSelectedItemPosition() * 90);
        appConfig.setSecondaryDisplayOrientation(orientationSpinner.getSelectedItemPosition() * 90);
        appConfig.setSecondaryDisplayBorderEnabled(borderSwitch.isChecked());
        appConfig.setSecondaryDisplayAlpha(seekbarAlpha.getProgress());
        BlindSpotService.update(requireContext());
    }

    private void updateDisplayInfo(Display display) {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        display.getRealMetrics(metrics);
        displayInfoText.setText(String.format("Current Screen Resolution: %d x %d", metrics.widthPixels, metrics.heightPixels));

        seekbarX.setMax(metrics.widthPixels);
        seekbarY.setMax(metrics.heightPixels);
        seekbarWidth.setMax(metrics.widthPixels);
        seekbarHeight.setMax(metrics.heightPixels);
    }

    private void enterAdjustMode() {
        Intent intent = new Intent(requireContext(), BlindSpotService.class);
        intent.putExtra("action", "enter_secondary_display_adjust");
        requireContext().startService(intent);
    }

    private void exitAdjustMode() {
        Context context = getContext();
        if (context == null) return;
        Intent intent = new Intent(context, BlindSpotService.class);
        intent.putExtra("action", "exit_secondary_display_adjust");
        context.startService(intent);
    }
}
