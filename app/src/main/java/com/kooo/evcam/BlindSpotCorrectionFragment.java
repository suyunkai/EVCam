package com.kooo.evcam;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class BlindSpotCorrectionFragment extends Fragment {
    private static final float SCALE_MIN = 0.10f;
    private static final float SCALE_MAX = 8.00f;
    private static final float SCALE_STEP = 0.01f;

    private static final float TRANSLATE_MIN = -5.00f;
    private static final float TRANSLATE_MAX = 5.00f;
    private static final float TRANSLATE_STEP = 0.01f;

    private Button backButton;
    private Button homeButton;
    private Spinner cameraSpinner;

    private SeekBar seekScaleX;
    private SeekBar seekScaleY;
    private SeekBar seekTranslateX;
    private SeekBar seekTranslateY;

    private TextView tvScaleX;
    private TextView tvScaleY;
    private TextView tvTranslateX;
    private TextView tvTranslateY;

    private Button resetButton;
    private Button saveButton;

    private AppConfig appConfig;
    private String currentCameraPos = "front";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_blind_spot_correction, container, false);
        appConfig = new AppConfig(requireContext());
        initViews(view);
        initSpinners();
        initSeekBars();
        loadFromConfig(currentCameraPos);
        setupListeners();
        startPreview(currentCameraPos);
        return view;
    }

    @Override
    public void onDestroyView() {
        stopPreview();
        super.onDestroyView();
    }

    private void initViews(View view) {
        backButton = view.findViewById(R.id.btn_back);
        homeButton = view.findViewById(R.id.btn_home);
        cameraSpinner = view.findViewById(R.id.spinner_camera);

        seekScaleX = view.findViewById(R.id.seek_scale_x);
        seekScaleY = view.findViewById(R.id.seek_scale_y);
        seekTranslateX = view.findViewById(R.id.seek_translate_x);
        seekTranslateY = view.findViewById(R.id.seek_translate_y);

        tvScaleX = view.findViewById(R.id.tv_scale_x);
        tvScaleY = view.findViewById(R.id.tv_scale_y);
        tvTranslateX = view.findViewById(R.id.tv_translate_x);
        tvTranslateY = view.findViewById(R.id.tv_translate_y);

        resetButton = view.findViewById(R.id.btn_reset);
        saveButton = view.findViewById(R.id.btn_save_apply);
    }

    private void initSpinners() {
        String[] cameraNames = {"前摄像头", "后摄像头", "左摄像头", "右摄像头"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, cameraNames);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cameraSpinner.setAdapter(adapter);
        cameraSpinner.setSelection(getCameraIndex(currentCameraPos));
    }

    private void initSeekBars() {
        seekScaleX.setMax((int) Math.round((SCALE_MAX - SCALE_MIN) / SCALE_STEP));
        seekScaleY.setMax((int) Math.round((SCALE_MAX - SCALE_MIN) / SCALE_STEP));
        seekTranslateX.setMax((int) Math.round((TRANSLATE_MAX - TRANSLATE_MIN) / TRANSLATE_STEP));
        seekTranslateY.setMax((int) Math.round((TRANSLATE_MAX - TRANSLATE_MIN) / TRANSLATE_STEP));
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

        cameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String newCameraPos = getCameraPos(position);
                if (first) {
                    first = false;
                    currentCameraPos = newCameraPos;
                    return;
                }
                currentCameraPos = newCameraPos;
                loadFromConfig(currentCameraPos);
                startPreview(currentCameraPos);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        SeekBar.OnSeekBarChangeListener seekListener = new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                if (seekBar == seekScaleX) {
                    float v = progressToScale(progress);
                    tvScaleX.setText(format2(v));
                    appConfig.setBlindSpotCorrectionScaleX(currentCameraPos, v);
                } else if (seekBar == seekScaleY) {
                    float v = progressToScale(progress);
                    tvScaleY.setText(format2(v));
                    appConfig.setBlindSpotCorrectionScaleY(currentCameraPos, v);
                } else if (seekBar == seekTranslateX) {
                    float v = progressToTranslate(progress);
                    tvTranslateX.setText(format2(v));
                    appConfig.setBlindSpotCorrectionTranslateX(currentCameraPos, v);
                } else if (seekBar == seekTranslateY) {
                    float v = progressToTranslate(progress);
                    tvTranslateY.setText(format2(v));
                    appConfig.setBlindSpotCorrectionTranslateY(currentCameraPos, v);
                } else {
                    return;
                }

                BlindSpotService.update(requireContext());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        };

        seekScaleX.setOnSeekBarChangeListener(seekListener);
        seekScaleY.setOnSeekBarChangeListener(seekListener);
        seekTranslateX.setOnSeekBarChangeListener(seekListener);
        seekTranslateY.setOnSeekBarChangeListener(seekListener);

        resetButton.setOnClickListener(v -> {
            appConfig.resetBlindSpotCorrection(currentCameraPos);
            loadFromConfig(currentCameraPos);
            BlindSpotService.update(requireContext());
        });

        saveButton.setOnClickListener(v -> {
            BlindSpotService.update(requireContext());
            Toast.makeText(requireContext(), "矫正参数已保存并应用", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadFromConfig(String cameraPos) {
        float scaleX = appConfig.getBlindSpotCorrectionScaleX(cameraPos);
        float scaleY = appConfig.getBlindSpotCorrectionScaleY(cameraPos);
        float translateX = appConfig.getBlindSpotCorrectionTranslateX(cameraPos);
        float translateY = appConfig.getBlindSpotCorrectionTranslateY(cameraPos);

        int px = scaleToProgress(scaleX);
        int py = scaleToProgress(scaleY);
        int tx = translateToProgress(translateX);
        int ty = translateToProgress(translateY);

        seekScaleX.setProgress(px);
        seekScaleY.setProgress(py);
        seekTranslateX.setProgress(tx);
        seekTranslateY.setProgress(ty);

        tvScaleX.setText(format2(progressToScale(px)));
        tvScaleY.setText(format2(progressToScale(py)));
        tvTranslateX.setText(format2(progressToTranslate(tx)));
        tvTranslateY.setText(format2(progressToTranslate(ty)));
    }

    private void startPreview(String cameraPos) {
        if (!WakeUpHelper.hasOverlayPermission(requireContext())) {
            Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
            WakeUpHelper.requestOverlayPermission(requireContext());
            return;
        }
        Intent intent = new Intent(requireContext(), BlindSpotService.class);
        intent.putExtra("action", "preview_blind_spot");
        intent.putExtra("camera_pos", cameraPos);
        requireContext().startService(intent);
    }

    private void stopPreview() {
        Intent intent = new Intent(requireContext(), BlindSpotService.class);
        intent.putExtra("action", "stop_preview_blind_spot");
        requireContext().startService(intent);
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

    private int scaleToProgress(float scale) {
        float clamped = clamp(scale, SCALE_MIN, SCALE_MAX);
        return (int) Math.round((clamped - SCALE_MIN) / SCALE_STEP);
    }

    private float progressToScale(int progress) {
        return SCALE_MIN + progress * SCALE_STEP;
    }

    private int translateToProgress(float translate) {
        float clamped = clamp(translate, TRANSLATE_MIN, TRANSLATE_MAX);
        return (int) Math.round((clamped - TRANSLATE_MIN) / TRANSLATE_STEP);
    }

    private float progressToTranslate(int progress) {
        return TRANSLATE_MIN + progress * TRANSLATE_STEP;
    }

    private float clamp(float v, float min, float max) {
        if (v < min) return min;
        if (v > max) return max;
        return v;
    }

    private String format2(float v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}

