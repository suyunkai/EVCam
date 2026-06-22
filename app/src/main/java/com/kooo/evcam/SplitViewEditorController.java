package com.kooo.evcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.config.SplitChannelConfig;

import java.util.ArrayList;
import java.util.List;

/**
 * 单通道分割配置界面的控制器。
 * 画面为横向长条：高度铺满、可左右平移；可通过前/后/左/右通道切换实时预览。
 */
public class SplitViewEditorController {

    public interface Callback {
        /**
         * 用户保存了分割配置
         */
        void onSaved(boolean cameraIdChanged);

        void onCancelled();
    }

    private static class Channel {
        final String label;
        final String id;

        Channel(String label, String id) {
            this.label = label;
            this.id = id;
        }
    }

    private final Activity activity;
    private final MultiCameraManager cameraManager;
    private final AppConfig appConfig;
    private final Callback callback;

    private View overlay;
    private SplitViewEditorView editorView;
    private Spinner cameraSpinner;
    private Button editButton;
    private Button deleteButton;
    private Button panButton;
    private HorizontalScrollView scrollView;
    private FrameLayout contentView;
    private TextView hintView;
    private androidx.activity.OnBackPressedCallback backCallback;

    private final List<Channel> channels = new ArrayList<>();
    private String initialCameraId;
    private String currentCameraId;
    private boolean initializingChannel = true;

    public SplitViewEditorController(Activity activity, MultiCameraManager cameraManager,
                                     AppConfig appConfig, Callback callback) {
        this.activity = activity;
        this.cameraManager = cameraManager;
        this.appConfig = appConfig;
        this.callback = callback;
    }

    public boolean isShowing() {
        return overlay != null;
    }

    public void show() {
        if (overlay != null) {
            return;
        }
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        overlay = LayoutInflater.from(activity).inflate(R.layout.view_split_editor, root, false);
        root.addView(overlay, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        editorView = overlay.findViewById(R.id.split_editor_canvas);
        cameraSpinner = overlay.findViewById(R.id.split_editor_camera_spinner);
        editButton = overlay.findViewById(R.id.btn_split_edit);
        deleteButton = overlay.findViewById(R.id.btn_split_delete);
        panButton = overlay.findViewById(R.id.btn_split_pan);
        scrollView = overlay.findViewById(R.id.split_editor_scroll);
        contentView = overlay.findViewById(R.id.split_editor_content);
        hintView = overlay.findViewById(R.id.split_editor_hint);

        SplitChannelConfig config = appConfig.getSplitChannelConfig();
        String managerCameraId = cameraManager != null ? cameraManager.getSplitPhysicalCameraId() : null;
        initialCameraId = config != null
                ? config.cameraId
                : (managerCameraId != null ? managerCameraId : appConfig.getSplitPhysicalCameraId());
        currentCameraId = initialCameraId;

        editorView.setRegions(config != null
                ? config.regions
                : new ArrayList<SplitChannelConfig.Region>());
        editorView.setListener(new SplitViewEditorView.Listener() {
            @Override
            public void onSelectionChanged(SplitChannelConfig.Region region) {
                boolean hasSelection = region != null;
                if (editButton != null) editButton.setEnabled(hasSelection);
                if (deleteButton != null) deleteButton.setEnabled(hasSelection);
            }

            @Override
            public void onRegionsChanged() {
                updateHint();
            }
        });
        updateHint();

        setupChannels();

        android.view.TextureView preview = overlay.findViewById(R.id.split_editor_texture);
        preview.setSurfaceTextureListener(new android.view.TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                if (cameraManager != null) {
                    cameraManager.attachSplitEditorPreview(preview);
                }
                updateContentWidth();
                scrollView.postDelayed(SplitViewEditorController.this::updateContentWidth, 800);
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                updateContentWidth();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                if (cameraManager != null) {
                    cameraManager.detachSplitEditorPreview();
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
            }
        });
        if (preview.isAvailable() && cameraManager != null) {
            cameraManager.attachSplitEditorPreview(preview);
        }

        panButton.setOnClickListener(v -> {
            boolean pan = !editorView.isPanMode();
            editorView.setPanMode(pan);
            panButton.setText(pan ? "画框画面" : "平移画面");
            updateHint();
        });

        overlay.findViewById(R.id.btn_split_quad).setOnClickListener(v -> editorView.applyQuadLayout());
        overlay.findViewById(R.id.btn_split_add).setOnClickListener(v -> editorView.addDefaultRegion());
        overlay.findViewById(R.id.btn_split_delete).setOnClickListener(v -> editorView.removeSelectedRegion());
        editButton.setOnClickListener(v -> showNumberLabelDialog());
        overlay.findViewById(R.id.btn_split_cancel).setOnClickListener(v -> cancel());
        overlay.findViewById(R.id.btn_split_save).setOnClickListener(v -> save());

        if (activity instanceof androidx.activity.ComponentActivity) {
            backCallback = new androidx.activity.OnBackPressedCallback(true) {
                @Override
                public void handleOnBackPressed() {
                    cancel();
                }
            };
            ((androidx.activity.ComponentActivity) activity)
                    .getOnBackPressedDispatcher().addCallback(backCallback);
        }

        scrollView.post(this::updateContentWidth);
        scrollView.postDelayed(this::updateContentWidth, 1500);
    }

    public void dismiss() {
        if (backCallback != null) {
            backCallback.remove();
            backCallback = null;
        }
        if (cameraManager != null) {
            cameraManager.detachSplitEditorPreview();
        }
        if (overlay != null) {
            ViewGroup parent = (ViewGroup) overlay.getParent();
            if (parent != null) {
                parent.removeView(overlay);
            }
            overlay = null;
        }
        editorView = null;
    }

    private void cancel() {
        // 取消时把通道恢复为进入配置界面时的通道
        if (currentCameraId != null && !currentCameraId.equals(initialCameraId) && cameraManager != null) {
            cameraManager.switchSplitPhysicalCamera(initialCameraId);
            currentCameraId = initialCameraId;
        }
        dismiss();
        if (callback != null) {
            callback.onCancelled();
        }
    }

    /**
     * 构建前/后/左/右通道（沿用自定义模式的摄像头映射），并补充其余物理通道
     */
    private void setupChannels() {
        channels.clear();
        List<String> available = new ArrayList<>();
        try {
            CameraManager cm = (CameraManager) activity.getSystemService(Context.CAMERA_SERVICE);
            if (cm != null) {
                for (String id : cm.getCameraIdList()) {
                    available.add(id);
                }
            }
        } catch (Exception e) {
            AppLog.e("SplitViewEditor", "获取摄像头列表失败", e);
        }
        if (available.isEmpty() && initialCameraId != null) {
            available.add(initialCameraId);
        }

        String[] keys = {"front", "back", "left", "right"};
        String[] names = {"前", "后", "左", "右"};
        for (int i = 0; i < keys.length; i++) {
            String id = appConfig.getCameraId(keys[i]);
            if (id == null || id.isEmpty() || !available.contains(id) || findChannel(id) != null) {
                continue;
            }
            channels.add(new Channel(names[i] + " (ID " + id + ")", id));
        }
        for (String id : available) {
            if (findChannel(id) == null) {
                channels.add(new Channel("通道 " + id, id));
            }
        }
        if (channels.isEmpty()) {
            channels.add(new Channel("当前通道 " + initialCameraId, initialCameraId));
        } else if (findChannel(initialCameraId) == null) {
            channels.add(0, new Channel("当前通道 " + initialCameraId, initialCameraId));
        }

        List<String> labels = new ArrayList<>();
        int selection = 0;
        for (int i = 0; i < channels.size(); i++) {
            labels.add(channels.get(i).label);
            if (channels.get(i).id.equals(currentCameraId)) {
                selection = i;
            }
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                R.layout.split_editor_spinner_item, labels);
        adapter.setDropDownViewResource(R.layout.split_editor_spinner_dropdown_item);
        initializingChannel = true;
        cameraSpinner.setAdapter(adapter);
        cameraSpinner.setSelection(selection);
        cameraSpinner.post(() -> initializingChannel = false);
        cameraSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (initializingChannel || position < 0 || position >= channels.size()) {
                    return;
                }
                switchChannel(channels.get(position).id);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });
    }

    private Channel findChannel(String id) {
        if (id == null) {
            return null;
        }
        for (Channel channel : channels) {
            if (id.equals(channel.id)) {
                return channel;
            }
        }
        return null;
    }

    private void switchChannel(String cameraId) {
        if (cameraId == null || cameraId.equals(currentCameraId)) {
            return;
        }
        currentCameraId = cameraId;
        if (cameraManager != null) {
            cameraManager.switchSplitPhysicalCamera(cameraId);
        }
        updateHint();
        scrollView.postDelayed(this::updateContentWidth, 600);
        scrollView.postDelayed(this::updateContentWidth, 1800);
    }

    /**
     * 按合成画面宽高比计算内容宽度，使画面高度铺满、左右可平移
     */
    private void updateContentWidth() {
        if (contentView == null || scrollView == null) {
            return;
        }
        int height = scrollView.getHeight();
        if (height <= 0) {
            scrollView.post(this::updateContentWidth);
            return;
        }
        android.util.Size size = cameraManager != null ? cameraManager.getSplitPreviewSize() : null;
        float aspect = (size != null && size.getHeight() > 0)
                ? (float) size.getWidth() / size.getHeight()
                : 4f;
        int width = Math.max(scrollView.getWidth(), Math.round(height * aspect));
        ViewGroup.LayoutParams lp = contentView.getLayoutParams();
        if (lp.width != width || lp.height != height) {
            lp.width = width;
            lp.height = height;
            contentView.setLayoutParams(lp);
        }
    }

    private void updateHint() {
        if (hintView == null || editorView == null) {
            return;
        }
        int count = editorView.getRegions().size();
        if (editorView.isPanMode()) {
            hintView.setText(count + "/" + SplitChannelConfig.MAX_REGIONS
                    + " 个区域 · 平移模式：左右拖动查看整条画面");
        } else {
            hintView.setText(count + "/" + SplitChannelConfig.MAX_REGIONS
                    + " 个区域 · 拖动空白处画框，拖动区域内移动，拖动角点缩放");
        }
    }

    private void showNumberLabelDialog() {
        SplitChannelConfig.Region region = editorView.getSelectedRegion();
        if (region == null) {
            return;
        }

        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (16 * activity.getResources().getDisplayMetrics().density);
        container.setPadding(padding, padding, padding, padding);

        TextView numberLabel = new TextView(activity);
        numberLabel.setText("编号（1~9）");
        numberLabel.setTextSize(16);
        container.addView(numberLabel);

        EditText numberInput = new EditText(activity);
        numberInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        numberInput.setText(String.valueOf(region.index));
        container.addView(numberInput);

        TextView labelTitle = new TextView(activity);
        labelTitle.setText("备注名称");
        labelTitle.setTextSize(16);
        labelTitle.setPadding(0, padding, 0, 0);
        container.addView(labelTitle);

        EditText labelInput = new EditText(activity);
        labelInput.setSingleLine(true);
        labelInput.setText(region.label == null ? "" : region.label);
        container.addView(labelInput);

        new AlertDialog.Builder(activity)
                .setTitle("画面编号与备注")
                .setView(container)
                .setPositiveButton("确定", (dialog, which) -> {
                    int number;
                    try {
                        number = Integer.parseInt(numberInput.getText().toString().trim());
                    } catch (Exception e) {
                        number = region.index;
                    }
                    number = Math.max(1, Math.min(9, number));
                    editorView.setSelectedRegionNumberAndLabel(number,
                            labelInput.getText().toString().trim());
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void save() {
        List<SplitChannelConfig.Region> regions = editorView.getRegions();
        if (regions.isEmpty()) {
            Toast.makeText(activity, "请至少绘制一个分割区域", Toast.LENGTH_SHORT).show();
            return;
        }

        SplitChannelConfig config = new SplitChannelConfig();
        config.cameraId = currentCameraId;
        config.regions.addAll(regions);
        config.normalizeSlots();

        appConfig.setSplitChannelConfig(config);

        if (cameraManager != null) {
            cameraManager.applySplitRegions(config.regions);
        }

        AppLog.d("SplitViewEditor", "分割配置已保存: camera=" + config.cameraId
                + ", regions=" + config.regions.size());

        dismiss();
        if (callback != null) {
            // 通道已在切换时实时生效，无需重启预览
            callback.onSaved(false);
        }
    }
}
