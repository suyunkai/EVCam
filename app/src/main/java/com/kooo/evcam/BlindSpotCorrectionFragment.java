package com.kooo.evcam;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class BlindSpotCorrectionFragment extends Fragment {
    private static final float SCALE_MIN = 0.10f;
    private static final float SCALE_MAX = 3.00f;
    private static final float SCALE_STEP = 0.01f;

    private static final float TRANSLATE_MIN = -1.00f;
    private static final float TRANSLATE_MAX = 1.00f;
    private static final float TRANSLATE_STEP = 0.01f;

    private Button backButton;
    private Button homeButton;
    private Button btnCameraFront, btnCameraBack, btnCameraLeft, btnCameraRight;

    private SeekBar seekScaleX;
    private SeekBar seekScaleY;
    private SeekBar seekTranslateX;
    private SeekBar seekTranslateY;
    private SeekBar seekRotation;

    private TextView tvScaleX;
    private TextView tvScaleY;
    private TextView tvTranslateX;
    private TextView tvTranslateY;
    private TextView tvRotation;

    private Button btnMirrorH;
    private Button btnMirrorV;

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
        initSeekBars();
        loadFromConfig(currentCameraPos);
        setupListeners();
        updateCameraButtonStyles();
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
        btnCameraFront = view.findViewById(R.id.btn_camera_front);
        btnCameraBack = view.findViewById(R.id.btn_camera_back);
        btnCameraLeft = view.findViewById(R.id.btn_camera_left);
        btnCameraRight = view.findViewById(R.id.btn_camera_right);

        seekScaleX = view.findViewById(R.id.seek_scale_x);
        seekScaleY = view.findViewById(R.id.seek_scale_y);
        seekTranslateX = view.findViewById(R.id.seek_translate_x);
        seekTranslateY = view.findViewById(R.id.seek_translate_y);
        seekRotation = view.findViewById(R.id.seek_rotation);

        tvScaleX = view.findViewById(R.id.tv_scale_x);
        tvScaleY = view.findViewById(R.id.tv_scale_y);
        tvTranslateX = view.findViewById(R.id.tv_translate_x);
        tvTranslateY = view.findViewById(R.id.tv_translate_y);
        tvRotation = view.findViewById(R.id.tv_rotation);

        btnMirrorH = view.findViewById(R.id.btn_mirror_h);
        btnMirrorV = view.findViewById(R.id.btn_mirror_v);

        resetButton = view.findViewById(R.id.btn_reset);
        saveButton = view.findViewById(R.id.btn_save_apply);
    }

    private void initSeekBars() {
        seekScaleX.setMax((int) Math.round((SCALE_MAX - SCALE_MIN) / SCALE_STEP));
        seekScaleY.setMax((int) Math.round((SCALE_MAX - SCALE_MIN) / SCALE_STEP));
        seekTranslateX.setMax((int) Math.round((TRANSLATE_MAX - TRANSLATE_MIN) / TRANSLATE_STEP));
        seekTranslateY.setMax((int) Math.round((TRANSLATE_MAX - TRANSLATE_MIN) / TRANSLATE_STEP));

        seekRotation.setMax(360);
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

        View.OnClickListener cameraClickListener = v -> {
            String newCameraPos;
            if (v == btnCameraFront) newCameraPos = "front";
            else if (v == btnCameraBack) newCameraPos = "back";
            else if (v == btnCameraLeft) newCameraPos = "left";
            else if (v == btnCameraRight) newCameraPos = "right";
            else return;
            currentCameraPos = newCameraPos;
            updateCameraButtonStyles();
            loadFromConfig(currentCameraPos);
            startPreview(currentCameraPos);
        };
        btnCameraFront.setOnClickListener(cameraClickListener);
        btnCameraBack.setOnClickListener(cameraClickListener);
        btnCameraLeft.setOnClickListener(cameraClickListener);
        btnCameraRight.setOnClickListener(cameraClickListener);

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

        seekRotation.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                tvRotation.setText(progress + "°");
                appConfig.setBlindSpotCorrectionRotation(currentCameraPos, progress);
                BlindSpotService.update(requireContext());
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {}

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        btnMirrorH.setOnClickListener(v -> {
            boolean current = appConfig.getBlindSpotCorrectionMirrorH(currentCameraPos);
            appConfig.setBlindSpotCorrectionMirrorH(currentCameraPos, !current);
            updateMirrorButtonStyles();
            BlindSpotService.update(requireContext());
        });

        btnMirrorV.setOnClickListener(v -> {
            boolean current = appConfig.getBlindSpotCorrectionMirrorV(currentCameraPos);
            appConfig.setBlindSpotCorrectionMirrorV(currentCameraPos, !current);
            updateMirrorButtonStyles();
            BlindSpotService.update(requireContext());
        });

        resetButton.setOnClickListener(v -> {
            appConfig.resetBlindSpotCorrection(currentCameraPos);
            loadFromConfig(currentCameraPos);
            BlindSpotService.update(requireContext());
        });

        saveButton.setOnClickListener(v -> {
            BlindSpotService.update(requireContext());
            Toast.makeText(requireContext(), "Correction parameters saved and applied", Toast.LENGTH_SHORT).show();
        });
    }

    private void loadFromConfig(String cameraPos) {
        float scaleX = appConfig.getBlindSpotCorrectionScaleX(cameraPos);
        float scaleY = appConfig.getBlindSpotCorrectionScaleY(cameraPos);
        float translateX = appConfig.getBlindSpotCorrectionTranslateX(cameraPos);
        float translateY = appConfig.getBlindSpotCorrectionTranslateY(cameraPos);
        int rotation = appConfig.getBlindSpotCorrectionRotation(cameraPos);

        int px = scaleToProgress(scaleX);
        int py = scaleToProgress(scaleY);
        int tx = translateToProgress(translateX);
        int ty = translateToProgress(translateY);

        seekScaleX.setProgress(px);
        seekScaleY.setProgress(py);
        seekTranslateX.setProgress(tx);
        seekTranslateY.setProgress(ty);
        seekRotation.setProgress(rotation);

        tvScaleX.setText(format2(progressToScale(px)));
        tvScaleY.setText(format2(progressToScale(py)));
        tvTranslateX.setText(format2(progressToTranslate(tx)));
        tvTranslateY.setText(format2(progressToTranslate(ty)));
        tvRotation.setText(rotation + "°");

        updateMirrorButtonStyles();
    }

    private void startPreview(String cameraPos) {
        if (!WakeUpHelper.hasOverlayPermission(requireContext())) {
            Toast.makeText(requireContext(), "Please grant Floating Window permission first", Toast.LENGTH_SHORT).show();
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

    private void updateCameraButtonStyles() {
        Button[] buttons = {btnCameraFront, btnCameraBack, btnCameraLeft, btnCameraRight};
        String[] positions = {"front", "back", "left", "right"};
        for (int i = 0; i < buttons.length; i++) {
            if (positions[i].equals(currentCameraPos)) {
                buttons[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        getResources().getColor(R.color.button_accent, null)));
                buttons[i].setTextColor(getResources().getColor(R.color.white, null));
            } else {
                buttons[i].setBackgroundTintList(android.content.res.ColorStateList.valueOf(
                        getResources().getColor(R.color.button_background, null)));
                buttons[i].setTextColor(getResources().getColor(R.color.button_text, null));
            }
        }
    }

    private void updateMirrorButtonStyles() {
        boolean mirrorH = appConfig.getBlindSpotCorrectionMirrorH(currentCameraPos);
        boolean mirrorV = appConfig.getBlindSpotCorrectionMirrorV(currentCameraPos);

        int accentColor = getResources().getColor(R.color.button_accent, null);
        int normalColor = getResources().getColor(R.color.button_background, null);
        int whiteText = getResources().getColor(R.color.white, null);
        int normalText = getResources().getColor(R.color.button_text, null);

        btnMirrorH.setBackgroundTintList(ColorStateList.valueOf(mirrorH ? accentColor : normalColor));
        btnMirrorH.setTextColor(mirrorH ? whiteText : normalText);
        btnMirrorV.setBackgroundTintList(ColorStateList.valueOf(mirrorV ? accentColor : normalColor));
        btnMirrorV.setTextColor(mirrorV ? whiteText : normalText);
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

