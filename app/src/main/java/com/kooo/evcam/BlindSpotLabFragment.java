package com.kooo.evcam;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.switchmaterial.SwitchMaterial;

public class BlindSpotLabFragment extends Fragment {
    private SwitchMaterial mainFloatingSwitch;
    private Spinner mainFloatingCameraSpinner;
    private SwitchMaterial reuseMainFloatingSwitch;
    private Button setupBlindSpotPosButton;
    private Button saveButton;
    private Button backButton;
    private Button homeButton;

    private AppConfig appConfig;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_blind_spot_lab, container, false);
        appConfig = new AppConfig(requireContext());
        initViews(view);
        loadSettings();
        setupListeners();
        return view;
    }

    private void initViews(View view) {
        backButton = view.findViewById(R.id.btn_back);
        homeButton = view.findViewById(R.id.btn_home);

        mainFloatingSwitch = view.findViewById(R.id.switch_main_floating);
        mainFloatingCameraSpinner = view.findViewById(R.id.spinner_main_floating_camera);
        reuseMainFloatingSwitch = view.findViewById(R.id.switch_reuse_main_floating);
        setupBlindSpotPosButton = view.findViewById(R.id.btn_setup_blind_spot_pos);
        saveButton = view.findViewById(R.id.btn_save_apply);

        String[] cameraNames = {"前摄像头", "后摄像头", "左摄像头", "右摄像头"};
        ArrayAdapter<String> cameraAdapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, cameraNames);
        cameraAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mainFloatingCameraSpinner.setAdapter(cameraAdapter);
    }

    private void loadSettings() {
        mainFloatingSwitch.setChecked(appConfig.isMainFloatingEnabled());
        mainFloatingCameraSpinner.setSelection(getCameraIndex(appConfig.getMainFloatingCamera()));
        reuseMainFloatingSwitch.setChecked(appConfig.isTurnSignalReuseMainFloating());
        setupBlindSpotPosButton.setVisibility(appConfig.isTurnSignalReuseMainFloating() ? View.GONE : View.VISIBLE);
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

        mainFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked && !WakeUpHelper.hasOverlayPermission(requireContext())) {
                mainFloatingSwitch.setChecked(false);
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            appConfig.setMainFloatingEnabled(isChecked);
            BlindSpotService.update(requireContext());
        });

        mainFloatingCameraSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            private boolean first = true;

            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (first) {
                    first = false;
                    return;
                }
                appConfig.setMainFloatingCamera(getCameraPos(position));
                BlindSpotService.update(requireContext());
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        reuseMainFloatingSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            appConfig.setTurnSignalReuseMainFloating(isChecked);
            setupBlindSpotPosButton.setVisibility(isChecked ? View.GONE : View.VISIBLE);
            BlindSpotService.update(requireContext());
        });

        setupBlindSpotPosButton.setOnClickListener(v -> {
            if (!WakeUpHelper.hasOverlayPermission(requireContext())) {
                Toast.makeText(requireContext(), "请先授予悬浮窗权限", Toast.LENGTH_SHORT).show();
                WakeUpHelper.requestOverlayPermission(requireContext());
                return;
            }
            Intent intent = new Intent(requireContext(), BlindSpotService.class);
            intent.putExtra("action", "setup_blind_spot_window");
            requireContext().startService(intent);
        });

        saveButton.setOnClickListener(v -> {
            Toast.makeText(requireContext(), "配置已保存并应用", Toast.LENGTH_SHORT).show();
            BlindSpotService.update(requireContext());
        });
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
}

