package com.kooo.evcam;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;

/**
 * 管理浮动摄像头功能：拖动、隐藏、放大/缩小
 */
public class FloatingCameraManager {
    private static final String TAG = "FloatingCameraManager";
    private static final String PREFS_NAME = "floating_camera_prefs";

    // 尺寸调整比例
    private static final float SCALE_STEP = 0.05f;  // 每次5%
    private static final float MIN_SCALE = 0.3f;    // 最小30%
    private static final float MAX_SCALE = 3.0f;    // 最大300%

    // 默认尺寸（width, height）
    private static final int[] DEFAULT_SIZE_HORIZONTAL = {300, 200};  // 横向摄像头
    private static final int[] DEFAULT_SIZE_VERTICAL = {180, 280};    // 纵向摄像头

    private final Context context;
    private final SharedPreferences prefs;

    public FloatingCameraManager(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * 初始化浮动摄像头功能
     */
    public void setupFloatingCamera(FrameLayout cameraFrame, String cameraId, boolean isVertical) {
        if (cameraFrame == null) {
            return;
        }

        // 恢复保存的位置和大小
        restoreCameraState(cameraFrame, cameraId, isVertical);

        // 设置拖动功能
        setupDragging(cameraFrame, cameraId);

        // 设置隐藏按钮
        setupHideButton(cameraFrame, cameraId);

        // 设置缩小按钮
        setupShrinkButton(cameraFrame, cameraId, isVertical);

        // 设置放大按钮
        setupEnlargeButton(cameraFrame, cameraId, isVertical);
    }

    /**
     * 设置拖动功能
     */
    private void setupDragging(FrameLayout cameraFrame, String cameraId) {
        ImageView dragHandle = cameraFrame.findViewWithTag("drag_handle_" + cameraId);
        if (dragHandle == null) {
            // 尝试通过ID查找
            int dragHandleId = context.getResources().getIdentifier("drag_handle_" + cameraId, "id", context.getPackageName());
            if (dragHandleId != 0) {
                dragHandle = cameraFrame.findViewById(dragHandleId);
            }
        }

        if (dragHandle != null) {
            dragHandle.setOnTouchListener(new DragTouchListener(cameraFrame, cameraId));
        }
    }

    /**
     * 设置隐藏按钮
     */
    private void setupHideButton(FrameLayout cameraFrame, String cameraId) {
        int btnId = context.getResources().getIdentifier("btn_hide_" + cameraId, "id", context.getPackageName());
        if (btnId != 0) {
            Button btnHide = cameraFrame.findViewById(btnId);
            if (btnHide != null) {
                btnHide.setOnClickListener(v -> toggleVisibility(cameraFrame, cameraId));
            }
        }
    }

    /**
     * 设置缩小按钮
     */
    private void setupShrinkButton(FrameLayout cameraFrame, String cameraId, boolean isVertical) {
        int btnId = context.getResources().getIdentifier("btn_shrink_" + cameraId, "id", context.getPackageName());
        if (btnId != 0) {
            Button btnShrink = cameraFrame.findViewById(btnId);
            if (btnShrink != null) {
                btnShrink.setOnClickListener(v -> adjustSize(cameraFrame, cameraId, isVertical, false));
            }
        }
    }

    /**
     * 设置放大按钮
     */
    private void setupEnlargeButton(FrameLayout cameraFrame, String cameraId, boolean isVertical) {
        int btnId = context.getResources().getIdentifier("btn_enlarge_" + cameraId, "id", context.getPackageName());
        if (btnId != 0) {
            Button btnEnlarge = cameraFrame.findViewById(btnId);
            if (btnEnlarge != null) {
                btnEnlarge.setOnClickListener(v -> adjustSize(cameraFrame, cameraId, isVertical, true));
            }
        }
    }

    /**
     * 切换摄像头可见性
     * 注意: 不能使用 GONE,否则 TextureView 不会初始化,导致摄像头无法打开
     * 使用透明度 + 不可点击来模拟隐藏效果
     */
    private void toggleVisibility(FrameLayout cameraFrame, String cameraId) {
        boolean isCurrentlyVisible = prefs.getBoolean(cameraId + "_visible", true);

        if (isCurrentlyVisible) {
            // 隐藏: 设置透明度为0,禁用点击
            cameraFrame.setAlpha(0f);
            cameraFrame.setClickable(false);
            cameraFrame.setFocusable(false);
            saveCameraVisibility(cameraId, false);
            AppLog.d(TAG, "Camera " + cameraId + " hidden (alpha=0)");
        } else {
            // 显示: 恢复透明度,启用点击
            cameraFrame.setAlpha(1f);
            cameraFrame.setClickable(true);
            cameraFrame.setFocusable(true);
            saveCameraVisibility(cameraId, true);
            AppLog.d(TAG, "Camera " + cameraId + " shown (alpha=1)");
        }
    }

    /**
     * 调整摄像头尺寸（按当前尺寸的5%增减）
     * @param cameraFrame 摄像头容器
     * @param cameraId 摄像头ID
     * @param isVertical 是否是纵向摄像头
     * @param enlarge true=放大, false=缩小
     */
    private void adjustSize(FrameLayout cameraFrame, String cameraId, boolean isVertical, boolean enlarge) {
        // 获取当前实际尺寸（像素）
        ViewGroup.LayoutParams params = cameraFrame.getLayoutParams();
        int currentWidthPx = params.width;
        int currentHeightPx = params.height;

        // 转换为 dp
        float density = context.getResources().getDisplayMetrics().density;
        int currentWidthDp = Math.round(currentWidthPx / density);
        int currentHeightDp = Math.round(currentHeightPx / density);

        // 按当前尺寸的5%计算变化量
        float scaleFactor = enlarge ? 1.05f : 0.95f;
        int newWidthDp = Math.round(currentWidthDp * scaleFactor);
        int newHeightDp = Math.round(currentHeightDp * scaleFactor);

        // 获取基础尺寸用于计算最小/最大限制
        int[] baseSize = isVertical ? DEFAULT_SIZE_VERTICAL : DEFAULT_SIZE_HORIZONTAL;
        int minWidthDp = Math.round(baseSize[0] * MIN_SCALE);
        int maxWidthDp = Math.round(baseSize[0] * MAX_SCALE);
        int minHeightDp = Math.round(baseSize[1] * MIN_SCALE);
        int maxHeightDp = Math.round(baseSize[1] * MAX_SCALE);

        // 限制在范围内
        newWidthDp = Math.max(minWidthDp, Math.min(maxWidthDp, newWidthDp));
        newHeightDp = Math.max(minHeightDp, Math.min(maxHeightDp, newHeightDp));

        // 检查是否达到极限
        if (newWidthDp == currentWidthDp && newHeightDp == currentHeightDp) {
            String limit = enlarge ? "最大" : "最小";
            AppLog.d(TAG, "Camera " + cameraId + " reached " + limit + " size limit");
            return;
        }

        // 应用新尺寸
        params.width = dpToPx(newWidthDp);
        params.height = dpToPx(newHeightDp);
        cameraFrame.setLayoutParams(params);

        // 计算并保存新的缩放比例（相对于基础尺寸）
        float newScale = (float) newWidthDp / baseSize[0];
        prefs.edit().putFloat(cameraId + "_scale", newScale).apply();

        int percentage = Math.round(newScale * 100);
        AppLog.d(TAG, "Camera " + cameraId + " resized to " + newWidthDp + "x" + newHeightDp + " dp (" + percentage + "%)");
    }

    /**
     * 恢复摄像头状态
     */
    private void restoreCameraState(FrameLayout cameraFrame, String cameraId, boolean isVertical) {
        // 恢复可见性 (使用透明度而不是 GONE)
        boolean isVisible = prefs.getBoolean(cameraId + "_visible", true);
        if (isVisible) {
            cameraFrame.setAlpha(1f);
            cameraFrame.setClickable(true);
            cameraFrame.setFocusable(true);
        } else {
            cameraFrame.setAlpha(0f);
            cameraFrame.setClickable(false);
            cameraFrame.setFocusable(false);
        }

        // 恢复位置
        int x = prefs.getInt(cameraId + "_x", -1);
        int y = prefs.getInt(cameraId + "_y", -1);
        if (x >= 0 && y >= 0) {
            RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) cameraFrame.getLayoutParams();
            params.leftMargin = x;
            params.topMargin = y;
            // 移除对齐规则
            params.removeRule(RelativeLayout.ALIGN_PARENT_TOP);
            params.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            params.removeRule(RelativeLayout.ALIGN_PARENT_START);
            params.removeRule(RelativeLayout.ALIGN_PARENT_END);
            cameraFrame.setLayoutParams(params);
        }

        // 恢复尺寸（使用缩放比例）
        float scale = prefs.getFloat(cameraId + "_scale", 1.0f);
        int[] baseSize = isVertical ? DEFAULT_SIZE_VERTICAL : DEFAULT_SIZE_HORIZONTAL;
        int width = Math.round(baseSize[0] * scale);
        int height = Math.round(baseSize[1] * scale);

        ViewGroup.LayoutParams params = cameraFrame.getLayoutParams();
        params.width = dpToPx(width);
        params.height = dpToPx(height);
        cameraFrame.setLayoutParams(params);

        int percentage = Math.round(scale * 100);
        AppLog.d(TAG, "Restored camera " + cameraId + " state: visible=" + isVisible + ", pos=(" + x + "," + y + "), size=" + width + "x" + height + " (" + percentage + "%)");
    }

    /**
     * 保存摄像头可见性
     */
    private void saveCameraVisibility(String cameraId, boolean visible) {
        prefs.edit().putBoolean(cameraId + "_visible", visible).apply();
    }

    /**
     * 保存摄像头位置
     */
    private void saveCameraPosition(String cameraId, int x, int y) {
        prefs.edit()
            .putInt(cameraId + "_x", x)
            .putInt(cameraId + "_y", y)
            .apply();
    }

    /**
     * 保存当前布局到预设
     * @param presetNumber 预设编号 (1-3)
     */
    public void saveLayoutPreset(int presetNumber, FrameLayout frameFront, FrameLayout frameBack,
                                 FrameLayout frameLeft, FrameLayout frameRight) {
        String prefix = "preset" + presetNumber + "_";
        SharedPreferences.Editor editor = prefs.edit();

        // 保存每个摄像头的状态
        saveCameraStateToPreset(editor, prefix, "front", frameFront);
        saveCameraStateToPreset(editor, prefix, "back", frameBack);
        saveCameraStateToPreset(editor, prefix, "left", frameLeft);
        saveCameraStateToPreset(editor, prefix, "right", frameRight);

        editor.apply();
        AppLog.d(TAG, "Saved layout preset " + presetNumber);
    }

    /**
     * 保存单个摄像头状态到预设
     */
    private void saveCameraStateToPreset(SharedPreferences.Editor editor, String prefix,
                                         String cameraId, FrameLayout cameraFrame) {
        if (cameraFrame == null) return;

        // 保存可见性
        boolean visible = cameraFrame.getAlpha() > 0.5f;
        editor.putBoolean(prefix + cameraId + "_visible", visible);

        // 保存位置
        editor.putInt(prefix + cameraId + "_x", (int) cameraFrame.getX());
        editor.putInt(prefix + cameraId + "_y", (int) cameraFrame.getY());

        // 保存缩放比例
        float scale = prefs.getFloat(cameraId + "_scale", 1.0f);
        editor.putFloat(prefix + cameraId + "_scale", scale);
    }

    /**
     * 加载预设布局
     * @param presetNumber 预设编号 (1-3)
     * @return true=成功加载, false=预设不存在
     */
    public boolean loadLayoutPreset(int presetNumber, FrameLayout frameFront, FrameLayout frameBack,
                                    FrameLayout frameLeft, FrameLayout frameRight) {
        String prefix = "preset" + presetNumber + "_";

        // 检查预设是否存在（至少有一个摄像头的数据）
        if (!prefs.contains(prefix + "front_x") && !prefs.contains(prefix + "back_x") &&
            !prefs.contains(prefix + "left_x") && !prefs.contains(prefix + "right_x")) {
            AppLog.d(TAG, "Preset " + presetNumber + " does not exist");
            return false;
        }

        // 加载每个摄像头的状态
        loadCameraStateFromPreset(prefix, "front", frameFront, false);
        loadCameraStateFromPreset(prefix, "back", frameBack, false);
        loadCameraStateFromPreset(prefix, "left", frameLeft, true);
        loadCameraStateFromPreset(prefix, "right", frameRight, true);

        AppLog.d(TAG, "Loaded layout preset " + presetNumber);
        return true;
    }

    /**
     * 从预设加载单个摄像头状态
     */
    private void loadCameraStateFromPreset(String prefix, String cameraId,
                                           FrameLayout cameraFrame, boolean isVertical) {
        if (cameraFrame == null) return;

        // 加载可见性
        boolean visible = prefs.getBoolean(prefix + cameraId + "_visible", true);
        if (visible) {
            cameraFrame.setAlpha(1f);
            cameraFrame.setClickable(true);
            cameraFrame.setFocusable(true);
        } else {
            cameraFrame.setAlpha(0f);
            cameraFrame.setClickable(false);
            cameraFrame.setFocusable(false);
        }
        saveCameraVisibility(cameraId, visible);

        // 加载位置
        int x = prefs.getInt(prefix + cameraId + "_x", -1);
        int y = prefs.getInt(prefix + cameraId + "_y", -1);
        if (x >= 0 && y >= 0) {
            cameraFrame.setX(x);
            cameraFrame.setY(y);
            saveCameraPosition(cameraId, x, y);
        }

        // 加载缩放比例
        float scale = prefs.getFloat(prefix + cameraId + "_scale", 1.0f);
        int[] baseSize = isVertical ? DEFAULT_SIZE_VERTICAL : DEFAULT_SIZE_HORIZONTAL;
        int width = Math.round(baseSize[0] * scale);
        int height = Math.round(baseSize[1] * scale);

        ViewGroup.LayoutParams params = cameraFrame.getLayoutParams();
        params.width = dpToPx(width);
        params.height = dpToPx(height);
        cameraFrame.setLayoutParams(params);

        prefs.edit().putFloat(cameraId + "_scale", scale).apply();
    }

    /**
     * 检查预设是否存在
     */
    public boolean hasPreset(int presetNumber) {
        String prefix = "preset" + presetNumber + "_";
        return prefs.contains(prefix + "front_x") || prefs.contains(prefix + "back_x") ||
               prefs.contains(prefix + "left_x") || prefs.contains(prefix + "right_x");
    }

    /**
     * 显示所有摄像头
     */
    public void showAllCameras(FrameLayout frameFront, FrameLayout frameBack, FrameLayout frameLeft, FrameLayout frameRight) {
        if (frameFront != null) {
            frameFront.setAlpha(1f);
            frameFront.setClickable(true);
            frameFront.setFocusable(true);
            saveCameraVisibility("front", true);
        }
        if (frameBack != null) {
            frameBack.setAlpha(1f);
            frameBack.setClickable(true);
            frameBack.setFocusable(true);
            saveCameraVisibility("back", true);
        }
        if (frameLeft != null) {
            frameLeft.setAlpha(1f);
            frameLeft.setClickable(true);
            frameLeft.setFocusable(true);
            saveCameraVisibility("left", true);
        }
        if (frameRight != null) {
            frameRight.setAlpha(1f);
            frameRight.setClickable(true);
            frameRight.setFocusable(true);
            saveCameraVisibility("right", true);
        }
        AppLog.d(TAG, "All cameras shown");
    }

    /**
     * 重置所有摄像头布局
     */
    public void resetAllCameras() {
        prefs.edit().clear().apply();
        AppLog.d(TAG, "All camera states reset");
    }

    /**
     * 拖动触摸监听器
     */
    private class DragTouchListener implements View.OnTouchListener {
        private final FrameLayout cameraFrame;
        private final String cameraId;
        private float dX, dY;
        private int lastAction;

        public DragTouchListener(FrameLayout cameraFrame, String cameraId) {
            this.cameraFrame = cameraFrame;
            this.cameraId = cameraId;
        }

        @Override
        public boolean onTouch(View view, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dX = cameraFrame.getX() - event.getRawX();
                    dY = cameraFrame.getY() - event.getRawY();
                    lastAction = MotionEvent.ACTION_DOWN;
                    break;

                case MotionEvent.ACTION_MOVE:
                    float newX = event.getRawX() + dX;
                    float newY = event.getRawY() + dY;

                    // 限制在父容器范围内
                    ViewGroup parent = (ViewGroup) cameraFrame.getParent();
                    if (parent != null) {
                        newX = Math.max(0, Math.min(newX, parent.getWidth() - cameraFrame.getWidth()));
                        newY = Math.max(0, Math.min(newY, parent.getHeight() - cameraFrame.getHeight()));
                    }

                    cameraFrame.setX(newX);
                    cameraFrame.setY(newY);
                    lastAction = MotionEvent.ACTION_MOVE;
                    break;

                case MotionEvent.ACTION_UP:
                    if (lastAction == MotionEvent.ACTION_MOVE) {
                        // 保存最终位置
                        saveCameraPosition(cameraId, (int) cameraFrame.getX(), (int) cameraFrame.getY());
                        AppLog.d(TAG, "Camera " + cameraId + " moved to (" + (int) cameraFrame.getX() + ", " + (int) cameraFrame.getY() + ")");
                    }
                    break;

                default:
                    return false;
            }
            return true;
        }
    }

    /**
     * dp转px
     */
    private int dpToPx(int dp) {
        float density = context.getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
