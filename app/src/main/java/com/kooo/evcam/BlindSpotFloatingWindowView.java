package com.kooo.evcam;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.Size;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;
import android.content.Intent;
import android.graphics.Color;

import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

/**
 * 独立补盲悬浮窗视图
 * 支持拖动和边缘缩放
 */
public class BlindSpotFloatingWindowView extends FrameLayout {
    private static final String TAG = "BlindSpotFloatingWindowView";
    private static final int RESIZE_THRESHOLD = 50;

    private WindowManager windowManager;
    public WindowManager.LayoutParams params; // 改为public以便Service访问
    private AppConfig appConfig;
    private TextureView textureView;
    private Surface cachedSurface;
    private SingleCamera currentCamera;
    private String cameraPos = "right"; // 默认用右摄像头测试
    private boolean isSetupMode = false;
    private int currentRotation = 0;
    private boolean isAdjustPreviewMode = false;
    private boolean isSupervisionMode = false; // 超视模式标志
    private boolean isSupervisionAdjustMode = false; // 超视模式调整状态
    private BlindSpotFloatingWindowView supervisionPartner; // 超视模式配对窗口
    private View supervisionAdjustPanel; // 超视模式调整面板
    
    // 长按和拖拽相关
    private static final long LONG_PRESS_DURATION = 500; // 长按时间阈值
    private static final int TOUCH_SLOP = 20; // 触摸移动阈值
    private boolean isLongPress = false;
    private boolean isDragging = false;
    private float touchStartX, touchStartY;
    private long touchStartTime;
    private Handler longPressHandler = new Handler(Looper.getMainLooper());
    private Runnable longPressRunnable;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int retryBindCount = 0;
    private Runnable retryBindRunnable;
    private android.animation.ValueAnimator windowAnimator;
    private boolean pendingShowAnimation = false;
    private Runnable showAnimFallback;

    private float lastX, lastY;
    private float initialX, initialY;
    private int initialWidth, initialHeight;
    private boolean isResizing = false;
    private int resizeMode = 0;
    private boolean isCurrentlySwapped = false;
    private boolean hasUnsavedResize = false;

    private BlindSpotStatusBarView statusBar;
    private TurnSignalArrowView turnSignalArrowView;

    public BlindSpotFloatingWindowView(Context context, boolean isSetupMode) {
        super(context);
        this.isSetupMode = isSetupMode;
        appConfig = new AppConfig(context);
        windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        init();
    }

    private void init() {
        int layoutRes = appConfig.isMultiviewCarModel()
                ? R.layout.view_blind_spot_floating_multiview
                : R.layout.view_blind_spot_floating;
        LayoutInflater.from(getContext()).inflate(layoutRes, this);
        textureView = findViewById(R.id.blind_spot_texture_view);
        View saveLayout = findViewById(R.id.layout_save_config);
        View saveButton = findViewById(R.id.btn_save_blind_spot_config);
        View rotateButton = findViewById(R.id.btn_rotate_blind_spot);

        statusBar = findViewById(R.id.blind_spot_status_bar);
        turnSignalArrowView = findViewById(R.id.turn_signal_arrow_view);
        
        // 初始化关闭按钮 - 默认隐藏，在setSupervisionMode中控制显示
        TextView closeButton = findViewById(R.id.btn_close_supervision);
        if (closeButton != null) {
            closeButton.setVisibility(View.GONE); // 默认隐藏
            closeButton.setOnClickListener(v -> {
                AppLog.i(TAG, "🎯 关闭按钮被点击，发送广播关闭超视模式");
                try {
                    Context ctx = getContext();
                    Intent intent = new Intent("com.kooo.evcam.SUPERVISION_MODE_CHANGED");
                    intent.putExtra("enabled", false);
                    // 显式设置目标包名，确保广播能被接收
                    intent.setPackage(ctx.getPackageName());
                    ctx.sendBroadcast(intent);
                    AppLog.i(TAG, "🎯 广播已发送: SUPERVISION_MODE_CHANGED, package=" + ctx.getPackageName());
                } catch (Exception e) {
                    AppLog.e(TAG, "发送广播失败: " + e.getMessage());
                }
            });
        } else {
            AppLog.w(TAG, "关闭按钮未找到，无法设置点击监听器");
        }
        
        applyStatusBarStyle();

        // 统一使用转向灯补盲的旋转设置，与转向灯触发时保持一致
        currentRotation = appConfig.getTurnSignalFloatingRotation();
        applyTransformNow();

        if (isSetupMode) {
            saveLayout.setVisibility(View.VISIBLE);
            saveButton.setOnClickListener(v -> {
                hasUnsavedResize = false;
                // 若宽高因矫正旋转而交换过，保存前还原为基础值
                int saveW = params.width;
                int saveH = params.height;
                if (isCurrentlySwapped) {
                    saveW = params.height;
                    saveH = params.width;
                }
                appConfig.setTurnSignalFloatingBounds(params.x, params.y, saveW, saveH);
                appConfig.setTurnSignalFloatingRotation(currentRotation);
                dismiss();
            });
            rotateButton.setOnClickListener(v -> {
                currentRotation = (currentRotation + 90) % 360;
                applyTransformNow();
            });
        }

        // 根据摄像头位置选择配置
        // 超视模式下使用独立保存的尺寸和位置配置
        int initialWidth, initialHeight, initialX, initialY;
        if ("left".equals(cameraPos)) {
            // 左视窗：使用独立保存的尺寸和位置
            initialWidth = appConfig.getSupervisionLeftWidth();
            initialHeight = appConfig.getSupervisionLeftHeight();
            initialX = appConfig.getSupervisionLeftX();
            initialY = appConfig.getSupervisionLeftY();
            AppLog.i(TAG, "🎯 init()加载左视窗配置: x=" + initialX + ", y=" + initialY +
                    ", w=" + initialWidth + ", h=" + initialHeight);
        } else if ("right".equals(cameraPos)) {
            // 右视窗：使用独立保存的尺寸和位置
            initialWidth = appConfig.getSupervisionRightWidth();
            initialHeight = appConfig.getSupervisionRightHeight();
            initialX = appConfig.getSupervisionRightX();
            initialY = appConfig.getSupervisionRightY();
            AppLog.i(TAG, "🎯 init()加载右视窗配置: x=" + initialX + ", y=" + initialY +
                    ", w=" + initialWidth + ", h=" + initialHeight);
        } else {
            // 其他情况使用转向灯补盲配置
            initialWidth = appConfig.getTurnSignalFloatingWidth();
            initialHeight = appConfig.getTurnSignalFloatingHeight();
            initialX = appConfig.getTurnSignalFloatingX();
            initialY = appConfig.getTurnSignalFloatingY();
            AppLog.i(TAG, "🎯 init()加载补盲配置: x=" + initialX + ", y=" + initialY +
                    ", w=" + initialWidth + ", h=" + initialHeight);
        }

        params = new WindowManager.LayoutParams(
                initialWidth,
                initialHeight,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                        WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = initialX;
        params.y = initialY;

        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(android.graphics.SurfaceTexture surface, int width, int height) {
                MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    SingleCamera camera = cm.getCamera(cameraPos);
                    if (camera != null) {
                        Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            surface.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                        }
                    }
                }
                if (cachedSurface != null) cachedSurface.release();
                cachedSurface = new Surface(surface);
                startCameraPreview(cachedSurface);
                applyTransformNow();
            }

            @Override
            public void onSurfaceTextureSizeChanged(android.graphics.SurfaceTexture surface, int width, int height) {
                applyTransformNow();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(android.graphics.SurfaceTexture surface) {
                cancelRetryBind();
                stopCameraPreview();
                if (cachedSurface != null) {
                    cachedSurface.release();
                    cachedSurface = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surface) {
                if (pendingShowAnimation) {
                    pendingShowAnimation = false;
                    if (showAnimFallback != null) {
                        mainHandler.removeCallbacks(showAnimFallback);
                        showAnimFallback = null;
                    }
                    playShowAnimation();
                }
            }
        });
    }

    /**
     * 设置是否为超视模式
     */
    public void setSupervisionMode(boolean supervisionMode) {
        this.isSupervisionMode = supervisionMode;
        // 超视模式下应用圆角
        if (supervisionMode) {
            // 使用post确保视图有尺寸后再应用圆角
            post(() -> {
                applySupervisionCornerRadius();
                AppLog.d(TAG, "🎯 超视模式圆角已应用, view size=" + getWidth() + "x" + getHeight());
            });
            // 设置关闭按钮位置：左画面在右上角，右画面在左上角
            // 设置margin避开圆角区域
            TextView closeButton = findViewById(R.id.btn_close_supervision);
            if (closeButton != null) {
                FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) closeButton.getLayoutParams();
                int marginOffset = (int) (8 * getContext().getResources().getDisplayMetrics().density); // 8dp偏移量
                if ("right".equals(cameraPos)) {
                    // 右画面：关闭按钮在左上角，往右移动避开圆角
                    layoutParams.gravity = Gravity.TOP | Gravity.START;
                    layoutParams.setMargins(marginOffset, marginOffset, 0, 0);
                } else {
                    // 左画面：关闭按钮在右上角，往左移动避开圆角
                    layoutParams.gravity = Gravity.TOP | Gravity.END;
                    layoutParams.setMargins(0, marginOffset, marginOffset, 0);
                }
                closeButton.setLayoutParams(layoutParams);
            }
        }
        // 更新关闭按钮可见性（如果视图已经添加到窗口）
        if (getParent() != null) {
            updateCloseButtonVisibility();
        }
    }

    /**
     * 重新加载超视模式的位置和大小
     * init()已经根据cameraPos加载了正确的配置，此方法仅用于日志记录
     */
    public void reloadSupervisionBounds() {
        if (params == null || cameraPos == null) return;

        // init()已经根据cameraPos加载了正确的配置，这里只记录日志
        AppLog.d(TAG, "当前超视模式位置大小: camera=" + cameraPos +
                ", x=" + params.x + ", y=" + params.y +
                ", w=" + params.width + ", h=" + params.height);
    }

    /**
     * 应用超视模式圆角
     * 左画面：左边20dp圆角，右边5dp圆角
     * 右画面：右边20dp圆角，左边5dp圆角
     * 
     * 注意：使用setRoundRect而不是setConvexPath，因为某些车机系统不支持setConvexPath
     */
    private void applySupervisionCornerRadius() {
        final float bigRadius = 20 * getContext().getResources().getDisplayMetrics().density; // 20dp
        final float smallRadius = 5 * getContext().getResources().getDisplayMetrics().density; // 5dp
        
        // 设置黑色背景，确保圆角区域显示黑色而不是透明
        setBackgroundColor(android.graphics.Color.BLACK);
        // 确保子视图也被裁剪，防止画面超出圆角
        setClipChildren(true);
        
        // 使用 ViewOutlineProvider 实现圆角裁剪
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                int width = view.getWidth();
                int height = view.getHeight();
                if (width == 0 || height == 0) return;
                
                // 使用setRoundRect实现圆角，兼容性更好
                // 左画面：整体使用小圆角(5dp)，但左边区域视觉上会有大圆角效果
                // 右画面：整体使用小圆角(5dp)，但右边区域视觉上会有大圆角效果
                // 为了兼容性，统一使用20dp圆角，与转向灯窗口保持一致
                outline.setRoundRect(0, 0, width, height, bigRadius);
            }
        });
        setClipToOutline(true);
    }

    /**
     * 设置超视模式配对窗口
     * 当一个窗口大小变化时，另一个窗口会同步调整
     */
    public void setSupervisionPartner(BlindSpotFloatingWindowView partner) {
        this.supervisionPartner = partner;
    }

    /**
     * 同步调整大小到配对窗口
     * 等比例缩放，保持两个窗口大小一致
     */
    private void syncSizeToPartner(int newWidth, int newHeight) {
        if (supervisionPartner != null && supervisionPartner.params != null) {
            // 保持配对窗口的X,Y位置不变，只调整大小
            supervisionPartner.params.width = newWidth;
            supervisionPartner.params.height = newHeight;
            try {
                supervisionPartner.windowManager.updateViewLayout(supervisionPartner, supervisionPartner.params);
                // 立即保存配对窗口的配置（确保同步生效）
                if ("left".equals(supervisionPartner.cameraPos)) {
                    appConfig.setSupervisionLeftBounds(
                        supervisionPartner.params.x,
                        supervisionPartner.params.y,
                        supervisionPartner.params.width,
                        supervisionPartner.params.height
                    );
                } else if ("right".equals(supervisionPartner.cameraPos)) {
                    appConfig.setSupervisionRightBounds(
                        supervisionPartner.params.x,
                        supervisionPartner.params.y,
                        supervisionPartner.params.width,
                        supervisionPartner.params.height
                    );
                }
            } catch (Exception e) {
                AppLog.e(TAG, "同步调整配对窗口大小失败: " + e.getMessage());
            }
        }
    }

    /**
     * 同步位置到配对窗口（保持Y坐标一致）
     */
    private void syncPositionToPartner(int newY) {
        if (supervisionPartner != null && supervisionPartner.params != null) {
            // 保持配对窗口的Y坐标与当前窗口一致
            supervisionPartner.params.y = newY;
            try {
                supervisionPartner.windowManager.updateViewLayout(supervisionPartner, supervisionPartner.params);
                // 立即保存配对窗口的位置和大小
                if ("left".equals(supervisionPartner.cameraPos)) {
                    appConfig.setSupervisionLeftBounds(
                        supervisionPartner.params.x,
                        supervisionPartner.params.y,
                        supervisionPartner.params.width,
                        supervisionPartner.params.height
                    );
                } else if ("right".equals(supervisionPartner.cameraPos)) {
                    appConfig.setSupervisionRightBounds(
                        supervisionPartner.params.x,
                        supervisionPartner.params.y,
                        supervisionPartner.params.width,
                        supervisionPartner.params.height
                    );
                }
            } catch (Exception e) {
                AppLog.e(TAG, "同步调整配对窗口位置失败: " + e.getMessage());
            }
        }
    }

    /**
     * 保存超视模式窗口的位置和大小
     */
    private void saveSupervisionBounds() {
        if (params == null) return;

        // 保存当前窗口位置和大小
        if ("left".equals(cameraPos)) {
            appConfig.setSupervisionLeftBounds(params.x, params.y, params.width, params.height);
            AppLog.i(TAG, "🎯💾 保存左视窗: x=" + params.x + ", y=" + params.y +
                    ", w=" + params.width + ", h=" + params.height);
        } else if ("right".equals(cameraPos)) {
            appConfig.setSupervisionRightBounds(params.x, params.y, params.width, params.height);
            AppLog.i(TAG, "🎯💾 保存右视窗: x=" + params.x + ", y=" + params.y +
                    ", w=" + params.width + ", h=" + params.height);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // 超视模式使用与设置模式相同的交互方式（无需长按）
        if (isSupervisionMode) {
            return handleSupervisionTouchDirect(event);
        }
        
        if (!isSetupMode && !isAdjustPreviewMode) return super.onTouchEvent(event);

        float x = event.getRawX();
        float y = event.getRawY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x;
                lastY = y;
                initialX = params.x;
                initialY = params.y;
                initialWidth = params.width;
                initialHeight = params.height;

                if (isSetupMode) {
                    float localX = event.getX();
                    float localY = event.getY();
                    int w = getWidth();
                    int h = getHeight();
                    resizeMode = 0;
                    if (localX < RESIZE_THRESHOLD) resizeMode |= 1;
                    if (localX > w - RESIZE_THRESHOLD) resizeMode |= 2;
                    if (localY < RESIZE_THRESHOLD) resizeMode |= 4;
                    if (localY > h - RESIZE_THRESHOLD) resizeMode |= 8;
                    isResizing = resizeMode != 0;
                } else {
                    isResizing = false;
                    resizeMode = 0;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX;
                float dy = y - lastY;

                if (isSetupMode && isResizing) {
                    if ((resizeMode & 1) != 0) {
                        int newWidth = (int) (initialWidth - dx);
                        if (newWidth > 200) {
                            params.width = newWidth;
                            params.x = (int) (initialX + dx);
                        }
                    }
                    if ((resizeMode & 2) != 0) {
                        int newWidth = (int) (initialWidth + dx);
                        if (newWidth > 200) params.width = newWidth;
                    }
                    if ((resizeMode & 4) != 0) {
                        int newHeight = (int) (initialHeight - dy);
                        if (newHeight > 150) {
                            params.height = newHeight;
                            params.y = (int) (initialY + dy);
                        }
                    }
                    if ((resizeMode & 8) != 0) {
                        int newHeight = (int) (initialHeight + dy);
                        if (newHeight > 150) params.height = newHeight;
                    }
                    windowManager.updateViewLayout(this, params);
                } else if (isSetupMode || isAdjustPreviewMode) {
                    params.x = (int) (initialX + dx);
                    params.y = (int) (initialY + dy);
                    windowManager.updateViewLayout(this, params);
                }
                return true;

            case MotionEvent.ACTION_UP:
                if (isResizing) {
                    hasUnsavedResize = true;
                }
                isResizing = false;
                return true;
        }
        return super.onTouchEvent(event);
    }
    
    /**
     * 检查触摸点是否在关闭按钮上
     */
    private boolean isTouchOnCloseButton(float localX, float localY) {
        TextView closeButton = findViewById(R.id.btn_close_supervision);
        if (closeButton == null || closeButton.getVisibility() != View.VISIBLE) {
            return false;
        }
        // 获取关闭按钮在父布局中的位置
        int[] location = new int[2];
        closeButton.getLocationInWindow(location);
        // 转换为相对于当前View的坐标
        int[] parentLocation = new int[2];
        getLocationInWindow(parentLocation);
        float buttonLeft = location[0] - parentLocation[0];
        float buttonTop = location[1] - parentLocation[1];
        float buttonRight = buttonLeft + closeButton.getWidth();
        float buttonBottom = buttonTop + closeButton.getHeight();
        
        return localX >= buttonLeft && localX <= buttonRight 
            && localY >= buttonTop && localY <= buttonBottom;
    }
    
    /**
     * 处理超视模式的触摸事件 - 支持长按调出调整菜单
     */
    private boolean handleSupervisionTouchDirect(MotionEvent event) {
        float x = event.getRawX();
        float y = event.getRawY();
        float localX = event.getX();
        float localY = event.getY();

        // 检查是否点击了关闭按钮区域
        if (isTouchOnCloseButton(localX, localY)) {
            return false;
        }

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                lastX = x;
                lastY = y;
                initialX = params.x;
                initialY = params.y;
                initialWidth = params.width;
                initialHeight = params.height;
                touchStartX = localX;
                touchStartY = localY;
                touchStartTime = System.currentTimeMillis();
                isLongPress = false;
                isDragging = false;
                
                // 检测是否在边缘（用于缩放）
                int w = getWidth();
                int h = getHeight();
                resizeMode = 0;
                if (localX < RESIZE_THRESHOLD) resizeMode |= 1;
                if (localX > w - RESIZE_THRESHOLD) resizeMode |= 2;
                if (localY < RESIZE_THRESHOLD) resizeMode |= 4;
                if (localY > h - RESIZE_THRESHOLD) resizeMode |= 8;
                isResizing = resizeMode != 0;
                
                // 启动长按检测
                longPressHandler.postDelayed(() -> {
                    isLongPress = true;
                    showSupervisionAdjustDialog();
                }, LONG_PRESS_DURATION);
                
                return true;

            case MotionEvent.ACTION_MOVE:
                float dx = x - lastX;
                float dy = y - lastY;
                float moveDistance = Math.abs(localX - touchStartX) + Math.abs(localY - touchStartY);
                
                // 如果移动距离超过阈值，取消长按
                if (moveDistance > TOUCH_SLOP && !isLongPress) {
                    longPressHandler.removeCallbacksAndMessages(null);
                    isDragging = true;
                }
                
                // 如果在调整模式下，不处理移动
                if (isSupervisionAdjustMode) {
                    return true;
                }

                if (isResizing) {
                    int newWidth = initialWidth;
                    if ((resizeMode & 1) != 0) {
                        newWidth = (int) (initialWidth - dx);
                        if (newWidth > 200) {
                            params.x = (int) (initialX + dx);
                        }
                    } else if ((resizeMode & 2) != 0) {
                        newWidth = (int) (initialWidth + dx);
                    }

                    float aspectRatio = (float) initialHeight / initialWidth;
                    int newHeight = (int) (newWidth * aspectRatio);

                    if (newWidth > 200 && newHeight > 150) {
                        params.width = newWidth;
                        params.height = newHeight;

                        if ((resizeMode & 4) != 0) {
                            int heightDiff = initialHeight - newHeight;
                            params.y = (int) (initialY + heightDiff);
                        }
                    }

                    AppLog.d(TAG, "🎯 调整大小中: camera=" + cameraPos +
                            ", newW=" + newWidth + ", newH=" + newHeight +
                            ", params.x=" + params.x + ", params.y=" + params.y);
                    syncSizeToPartner(params.width, params.height);
                } else if (isDragging) {
                    params.x = (int) (initialX + dx);
                    params.y = (int) (initialY + dy);
                    syncPositionToPartner(params.y);
                }
                
                windowManager.updateViewLayout(this, params);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                longPressHandler.removeCallbacksAndMessages(null);

                AppLog.i(TAG, "🎯📤 ACTION_UP: isResizing=" + isResizing + ", isDragging=" + isDragging +
                        ", camera=" + cameraPos +
                        ", x=" + params.x + ", y=" + params.y +
                        ", w=" + params.width + ", h=" + params.height);

                // 无论移动还是调整大小，都直接保存
                if (isSupervisionMode) {
                    AppLog.i(TAG, "🎯💾 自动保存位置和大小");
                    saveSupervisionBounds();
                    hasUnsavedResize = true;
                }

                isResizing = false;
                resizeMode = 0;
                isDragging = false;
                isLongPress = false;
                return true;
        }
        return super.onTouchEvent(event);
    }
    
    /**
     * 显示超视模式调整对话框
     */
    private void showSupervisionAdjustDialog() {
        if (supervisionAdjustPanel != null && supervisionAdjustPanel.getParent() != null) {
            return; // 已经在显示
        }
        
        isSupervisionAdjustMode = true;
        
        // 创建调整面板
        Context ctx = getContext();
        LinearLayout panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.HORIZONTAL);
        panel.setBackgroundColor(0xCC000000); // 半透明黑色背景
        panel.setPadding(20, 10, 20, 10);
        
        // 重置按钮
        Button resetBtn = new Button(ctx);
        resetBtn.setText("重置");
        resetBtn.setTextColor(Color.WHITE);
        resetBtn.setBackgroundColor(0xFF666666);
        resetBtn.setOnClickListener(v -> {
            resetSupervisionBounds();
            hideSupervisionAdjustPanel();
        });
        
        // 保存按钮
        Button saveBtn = new Button(ctx);
        saveBtn.setText("保存");
        saveBtn.setTextColor(Color.WHITE);
        saveBtn.setBackgroundColor(0xFF4CAF50);
        saveBtn.setOnClickListener(v -> {
            // 强制保存当前位置和大小
            AppLog.i(TAG, "🎯 用户点击保存，当前位置: x=" + params.x + ", y=" + params.y +
                    ", w=" + params.width + ", h=" + params.height);
            saveSupervisionBounds();
            hasUnsavedResize = false;

            // 强制同步写入
            appConfig.forceSave();

            // 验证保存是否成功
            int savedX, savedY, savedW, savedH;
            if ("left".equals(cameraPos)) {
                savedX = appConfig.getSupervisionLeftX();
                savedY = appConfig.getSupervisionLeftY();
                savedW = appConfig.getSupervisionLeftWidth();
                savedH = appConfig.getSupervisionLeftHeight();
            } else {
                savedX = appConfig.getSupervisionRightX();
                savedY = appConfig.getSupervisionRightY();
                savedW = appConfig.getSupervisionRightWidth();
                savedH = appConfig.getSupervisionRightHeight();
            }
            AppLog.i(TAG, "🎯 保存后读取验证: x=" + savedX + ", y=" + savedY +
                    ", w=" + savedW + ", h=" + savedH);

            hideSupervisionAdjustPanel();
            Toast.makeText(ctx, "位置和大小已保存", Toast.LENGTH_SHORT).show();
        });
        
        // 关闭按钮
        Button closeBtn = new Button(ctx);
        closeBtn.setText("关闭");
        closeBtn.setTextColor(Color.WHITE);
        closeBtn.setBackgroundColor(0xFFF44336);
        closeBtn.setOnClickListener(v -> {
            hideSupervisionAdjustPanel();
        });
        
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        btnParams.setMargins(10, 0, 10, 0);
        
        panel.addView(resetBtn, btnParams);
        panel.addView(saveBtn, btnParams);
        panel.addView(closeBtn, btnParams);
        
        // 添加到窗口
        WindowManager.LayoutParams panelParams = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O ?
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY :
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        );
        panelParams.gravity = Gravity.CENTER;
        panelParams.y = 200; // 显示在屏幕中间偏下
        
        try {
            windowManager.addView(panel, panelParams);
            supervisionAdjustPanel = panel;
            AppLog.i(TAG, "显示超视模式调整面板");
        } catch (Exception e) {
            AppLog.e(TAG, "显示调整面板失败: " + e.getMessage());
            isSupervisionAdjustMode = false;
        }
    }
    
    /**
     * 隐藏超视模式调整面板
     */
    private void hideSupervisionAdjustPanel() {
        if (supervisionAdjustPanel != null) {
            try {
                windowManager.removeView(supervisionAdjustPanel);
            } catch (Exception e) {
                AppLog.e(TAG, "隐藏调整面板失败: " + e.getMessage());
            }
            supervisionAdjustPanel = null;
        }
        isSupervisionAdjustMode = false;
    }
    
    /**
     * 重置超视模式位置和大小为默认值
     */
    private void resetSupervisionBounds() {
        appConfig.resetSupervisionBounds();

        // 重新加载默认位置
        int newX, newY, newWidth, newHeight;
        if ("left".equals(cameraPos)) {
            newX = appConfig.getSupervisionLeftX();
            newY = appConfig.getSupervisionLeftY();
            newWidth = appConfig.getSupervisionLeftWidth();
            newHeight = appConfig.getSupervisionLeftHeight();
        } else {
            newX = appConfig.getSupervisionRightX();
            newY = appConfig.getSupervisionRightY();
            newWidth = appConfig.getSupervisionRightWidth();
            newHeight = appConfig.getSupervisionRightHeight();
        }

        params.x = newX;
        params.y = newY;
        params.width = newWidth;
        params.height = newHeight;

        windowManager.updateViewLayout(this, params);

        // 同步到配对窗口
        if (supervisionPartner != null) {
            supervisionPartner.reloadSupervisionBounds();
            try {
                supervisionPartner.windowManager.updateViewLayout(supervisionPartner, supervisionPartner.params);
            } catch (Exception e) {
                AppLog.e(TAG, "同步重置配对窗口失败: " + e.getMessage());
            }
        }

        AppLog.i(TAG, "重置超视模式位置大小为默认值");
        Toast.makeText(getContext(), "已重置为默认位置", Toast.LENGTH_SHORT).show();
    }

    /**
     * 仅设置摄像头位置（不触发预览切换）。
     * 用于 show() 前设定初始摄像头，避免 onSurfaceTextureAvailable 使用默认值。
     */
    public void setCameraPos(String cameraPos) {
        this.cameraPos = cameraPos;
        // 重新加载对应摄像头的配置（因为init()时cameraPos可能还是默认值）
        reloadSupervisionBoundsForCameraPos();
        // 统一使用转向灯补盲的旋转设置，与转向灯触发时保持一致
        currentRotation = appConfig.getTurnSignalFloatingRotation();
        AppLog.d(TAG, "🎯 setCameraPos更新旋转角度: camera=" + cameraPos + ", rotation=" + currentRotation);
        // 重新应用画面变换，确保使用正确的摄像头配置
        applyTransformNow();
    }

    /**
     * 根据cameraPos重新加载对应的配置
     * 用于在setCameraPos后更新窗口位置和大小
     * 使用独立保存的尺寸和位置
     */
    public void reloadSupervisionBoundsForCameraPos() {
        if (params == null || cameraPos == null) return;

        int newX, newY, newWidth, newHeight;
        if ("left".equals(cameraPos)) {
            newX = appConfig.getSupervisionLeftX();
            newY = appConfig.getSupervisionLeftY();
            newWidth = appConfig.getSupervisionLeftWidth();
            newHeight = appConfig.getSupervisionLeftHeight();
        } else if ("right".equals(cameraPos)) {
            newX = appConfig.getSupervisionRightX();
            newY = appConfig.getSupervisionRightY();
            newWidth = appConfig.getSupervisionRightWidth();
            newHeight = appConfig.getSupervisionRightHeight();
        } else {
            return;
        }

        params.x = newX;
        params.y = newY;
        params.width = newWidth;
        params.height = newHeight;

        // 如果视图已经添加到窗口，立即应用新的布局参数
        if (getParent() != null) {
            try {
                windowManager.updateViewLayout(this, params);
                AppLog.i(TAG, "🎯📥 根据cameraPos重新加载配置并应用: camera=" + cameraPos +
                        ", x=" + newX + ", y=" + newY +
                        ", w=" + newWidth + ", h=" + newHeight);
            } catch (Exception e) {
                AppLog.e(TAG, "更新窗口布局失败: " + e.getMessage());
            }
        } else {
            AppLog.i(TAG, "🎯📥 根据cameraPos重新加载配置(未添加到窗口): camera=" + cameraPos +
                    ", x=" + newX + ", y=" + newY +
                    ", w=" + newWidth + ", h=" + newHeight);
        }
    }

    public void setCamera(String cameraPos) {
        this.cameraPos = cameraPos;
        stopCameraPreview(true); // 切换摄像头时使用紧急模式清除旧surface
        applyTransformNow();

        // 更新 SurfaceTexture 的 buffer size 以匹配新摄像头的预览分辨率
        // 避免 Surface 尺寸与摄像头配置不匹配导致 session 创建失败
        if (textureView.isAvailable()) {
            android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
            if (st != null) {
                MultiCameraManager cm = CameraManagerHolder.getInstance().getCameraManager();
                if (cm != null) {
                    SingleCamera camera = cm.getCamera(cameraPos);
                    if (camera != null) {
                        Size previewSize = camera.getPreviewSize();
                        if (previewSize != null) {
                            st.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
                            AppLog.d(TAG, "Updated buffer size to " + previewSize.getWidth() + "x" + previewSize.getHeight() + " for " + cameraPos);
                        }
                    }
                }
            }
        }

        if (textureView.isAvailable() && cachedSurface != null && cachedSurface.isValid()) {
            startCameraPreview(cachedSurface, true);
        } else {
            scheduleRetryBind();
        }
    }

    private void startCameraPreview(Surface surface) {
        startCameraPreview(surface, false);
    }

    private void startCameraPreview(Surface surface, boolean urgent) {
        MultiCameraManager cameraManager = CameraManagerHolder.getInstance().getCameraManager();
        if (cameraManager == null) {
            cameraManager = CameraManagerHolder.getInstance().getOrInit(getContext());
            if (cameraManager == null) {
                scheduleRetryBind();
                return;
            }
        }

        currentCamera = cameraManager.getCamera(cameraPos);
        if (currentCamera == null) {
            scheduleRetryBind();
            return;
        }
        // 传递 SurfaceTexture 引用，便于 createCameraPreviewSession 统一设置 buffer 尺寸
        android.graphics.SurfaceTexture st = (textureView != null && textureView.isAvailable()) ? textureView.getSurfaceTexture() : null;
        currentCamera.setMainFloatingSurface(surface, st);

        // 如果摄像头硬件还未打开（后台初始化时不打开），先打开
        if (!currentCamera.isCameraOpened()) {
            AppLog.d(TAG, "Camera not opened yet, opening now for " + cameraPos);
            // 确保前台服务就绪后再打开相机（避免冷启动时 CAMERA_DISABLED）
            final SingleCamera cam = currentCamera;
            CameraForegroundService.whenReady(getContext(), cam::openCamera);
        } else {
            currentCamera.recreateSession(urgent);
        }
        cancelRetryBind();
    }

    private void stopCameraPreview() {
        stopCameraPreview(false);
    }

    private void stopCameraPreview(boolean urgent) {
        if (currentCamera != null) {
            // 立即停止推帧，防止 Surface 销毁后 queueBuffer abandoned 刷屏
            currentCamera.stopRepeatingNow();
            currentCamera.setMainFloatingSurface(null);
            currentCamera.recreateSession(urgent);
            currentCamera = null;
        }
    }

    public void show() {
        try {
            if (this.getParent() == null) {
                boolean animEnabled = appConfig.isFloatingWindowAnimationEnabled();

                // 等待首帧画面到达后再显示，避免黑屏闪烁
                if (animEnabled) {
                    setScaleX(0.85f);
                    setScaleY(0.85f);
                }
                params.alpha = 0f;
                pendingShowAnimation = true;

                windowManager.addView(this, params);
                if (isAdjustPreviewMode) {
                    moveToAdjustPreviewDefaultPosition();
                }
                applyTransformNow();
                
                // 视图添加到窗口后，更新关闭按钮可见性
                updateCloseButtonVisibility();

                // 安全超时：如果摄像头迟迟没有推帧，最多等 300ms 后也直接显示
                // 减少等待时间以加快补盲画面显示速度
                showAnimFallback = () -> {
                    if (pendingShowAnimation) {
                        pendingShowAnimation = false;
                        playShowAnimation();
                    }
                };
                mainHandler.postDelayed(showAnimFallback, 300);
            }
        } catch (Exception e) {
            AppLog.e(TAG, "Error showing blind spot floating window: " + e.getMessage());
        }
    }
    
    /**
     * 更新关闭按钮可见性
     */
    private void updateCloseButtonVisibility() {
        TextView closeButton = findViewById(R.id.btn_close_supervision);
        if (closeButton != null) {
            closeButton.setVisibility(isSupervisionMode ? View.VISIBLE : View.GONE);
            AppLog.d(TAG, "更新关闭按钮可见性: " + isSupervisionMode + ", 按钮=" + closeButton);
        } else {
            AppLog.w(TAG, "关闭按钮未找到!");
        }
    }

    private void playShowAnimation() {
        boolean animEnabled = appConfig.isFloatingWindowAnimationEnabled();

        if (!animEnabled) {
            // 无动效：直接显示
            setScaleX(1f);
            setScaleY(1f);
            params.alpha = 1f;
            try {
                if (getParent() != null) {
                    windowManager.updateViewLayout(BlindSpotFloatingWindowView.this, params);
                }
            } catch (Exception e) {}
            return;
        }

        // 有动效：缩放 + 淡入
        if (windowAnimator != null) windowAnimator.cancel();
        windowAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f);
        windowAnimator.setDuration(100);
        windowAnimator.setInterpolator(new android.view.animation.DecelerateInterpolator(1.5f));
        windowAnimator.addUpdateListener(animation -> {
            float val = (float) animation.getAnimatedValue();
            setScaleX(0.85f + 0.15f * val);
            setScaleY(0.85f + 0.15f * val);
            params.alpha = val;
            try {
                if (getParent() != null) {
                    windowManager.updateViewLayout(BlindSpotFloatingWindowView.this, params);
                }
            } catch (Exception e) {}
        });
        windowAnimator.start();
    }

    public void applyTransformNow() {
        // 矫正旋转更接近竖屏时，悬浮窗宽高互换，让画面自然填满不裁切
        int correctionRotation = 0;
        if (appConfig.isBlindSpotCorrectionEnabled() && cameraPos != null) {
            correctionRotation = appConfig.getBlindSpotCorrectionRotation(cameraPos);
        }

        // 根据模式选择尺寸配置
        int baseW, baseH;
        if (isSupervisionMode) {
            // 超视模式下使用独立保存的尺寸
            if ("left".equals(cameraPos)) {
                baseW = appConfig.getSupervisionLeftWidth();
                baseH = appConfig.getSupervisionLeftHeight();
            } else if ("right".equals(cameraPos)) {
                baseW = appConfig.getSupervisionRightWidth();
                baseH = appConfig.getSupervisionRightHeight();
            } else {
                baseW = appConfig.getTurnSignalFloatingWidth();
                baseH = appConfig.getTurnSignalFloatingHeight();
            }
        } else {
            // 转向灯模式使用补盲画面尺寸
            baseW = appConfig.getTurnSignalFloatingWidth();
            baseH = appConfig.getTurnSignalFloatingHeight();
        }

        boolean shouldSwap = BlindSpotCorrection.isCloserToPortrait(correctionRotation);
        isCurrentlySwapped = shouldSwap;
        int targetW = shouldSwap ? baseH : baseW;
        int targetH = shouldSwap ? baseW : baseH;

        // 用户正在拖动缩放或有未保存的缩放时，不覆盖 params，以免打断手势或丢失调整
        if (!isResizing && !hasUnsavedResize
                && params != null && (params.width != targetW || params.height != targetH)) {
            params.width = targetW;
            params.height = targetH;
            try {
                if (getParent() != null) {
                    windowManager.updateViewLayout(this, params);
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        BlindSpotCorrection.apply(textureView, appConfig, cameraPos, currentRotation, isCurrentlySwapped);
    }

    public void enableAdjustPreviewMode() {
        isAdjustPreviewMode = true;
        if (params != null) {
            int targetW = dpToPx(320);
            int targetH = dpToPx(180);
            if (targetW > 0 && targetH > 0) {
                params.width = targetW;
                params.height = targetH;
            }
        }
        moveToAdjustPreviewDefaultPosition();
    }

    private void moveToAdjustPreviewDefaultPosition() {
        if (params == null) return;
        DisplayMetrics metrics = new DisplayMetrics();
        try {
            windowManager.getDefaultDisplay().getRealMetrics(metrics);
        } catch (Exception e) {
            metrics = getResources().getDisplayMetrics();
        }
        int margin = dpToPx(16);
        int w = params.width > 0 ? params.width : dpToPx(320);
        int h = params.height > 0 ? params.height : dpToPx(180);
        int x = metrics.widthPixels - w - margin;
        int y = metrics.heightPixels - h - margin;
        if (x < 0) x = 0;
        if (y < 0) y = 0;
        setPosition(x, y);
    }

    public void setPosition(int x, int y) {
        if (params == null) return;
        params.x = x;
        params.y = y;
        if (getParent() != null) {
            try {
                windowManager.updateViewLayout(this, params);
            } catch (Exception e) {
                // Ignore
            }
        }
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    public void dismiss() {
        cancelRetryBind();
        stopCameraPreview();
        pendingShowAnimation = false;
        if (showAnimFallback != null) {
            mainHandler.removeCallbacks(showAnimFallback);
            showAnimFallback = null;
        }

        if (getParent() == null) return;

        boolean animEnabled = appConfig.isFloatingWindowAnimationEnabled();

        if (!animEnabled) {
            // 无动效：直接移除
            params.alpha = 1f;
            try {
                windowManager.removeView(this);
            } catch (Exception e) {}
            return;
        }

        // 关闭动效：缩放 + 淡出
        if (windowAnimator != null) {
            windowAnimator.cancel();
            windowAnimator = null;
        }
        windowAnimator = android.animation.ValueAnimator.ofFloat(1f, 0f);
        windowAnimator.setDuration(100);
        windowAnimator.setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f));
        windowAnimator.addUpdateListener(animation -> {
            float val = (float) animation.getAnimatedValue();
            setScaleX(0.85f + 0.15f * val);
            setScaleY(0.85f + 0.15f * val);
            params.alpha = val;
            try {
                if (getParent() != null) {
                    windowManager.updateViewLayout(BlindSpotFloatingWindowView.this, params);
                }
            } catch (Exception e1) {}
        });
        windowAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                params.alpha = 1f;
                try {
                    if (getParent() != null) {
                        windowManager.removeView(BlindSpotFloatingWindowView.this);
                    }
                } catch (Exception e) {}
                windowAnimator = null;
            }
        });
        windowAnimator.start();
    }

    private void scheduleRetryBind() {
        cancelRetryBind();
        retryBindCount++;
        long delayMs;
        if (retryBindCount <= 10) {
            delayMs = 500;
        } else if (retryBindCount <= 30) {
            delayMs = 1000;
        } else {
            delayMs = 3000;
        }
        retryBindRunnable = () -> {
            if (getParent() == null) return;
            if (textureView == null || !textureView.isAvailable()) {
                scheduleRetryBind();
                return;
            }
            if (cachedSurface == null || !cachedSurface.isValid()) {
                android.graphics.SurfaceTexture st = textureView.getSurfaceTexture();
                if (st == null) {
                    scheduleRetryBind();
                    return;
                }
                if (cachedSurface != null) {
                    try { cachedSurface.release(); } catch (Exception e) {}
                }
                cachedSurface = new Surface(st);
            }
            startCameraPreview(cachedSurface);
        };
        mainHandler.postDelayed(retryBindRunnable, delayMs);
    }

    private void cancelRetryBind() {
        if (retryBindRunnable != null) {
            mainHandler.removeCallbacks(retryBindRunnable);
            retryBindRunnable = null;
        }
        retryBindCount = 0;
    }

    private void applyStatusBarStyle() {
        if (statusBar == null) return;
        int style = appConfig.getBlindSpotStatusBarStyle();
        if (style == BlindSpotStatusBarView.STYLE_OFF) {
            statusBar.setVisibility(View.GONE);
        } else {
            statusBar.setVisibility(View.VISIBLE);
            statusBar.setAnimationStyle(style);
            statusBar.setEffectColor(appConfig.getBlindSpotStatusBarColor());
            int alpha = (int) (appConfig.getBlindSpotStatusBarBgOpacity() / 100f * 255);
            statusBar.setBackgroundColor(android.graphics.Color.argb(alpha, 0x1A, 0x1A, 0x1A));
        }
    }

    public void updateStatusLabel(String cameraPos) {
        if (statusBar != null) {
            applyStatusBarStyle();
            statusBar.setDirection(cameraPos);
        }
    }

    /**
     * 显示转向箭头
     * @param direction "left" 表示左转，"right" 表示右转
     */
    public void showTurnSignalArrow(String direction) {
        if (turnSignalArrowView != null) {
            turnSignalArrowView.showArrow(direction);
        }
    }

    /**
     * 隐藏转向箭头
     */
    public void hideTurnSignalArrow() {
        if (turnSignalArrowView != null) {
            turnSignalArrowView.hideArrow();
        }
    }

    /**
     * 更新悬浮窗位置和大小
     * @param x X坐标
     * @param y Y坐标
     * @param width 宽度
     * @param height 高度
     */
    public void updateBounds(int x, int y, int width, int height) {
        if (params != null && windowManager != null) {
            params.x = x;
            params.y = y;
            params.width = width;
            params.height = height;
            try {
                windowManager.updateViewLayout(this, params);
            } catch (Exception e) {
                AppLog.e(TAG, "更新悬浮窗位置失败: " + e.getMessage());
            }
        }
    }

    /**
     * 重新启动摄像头预览（用于应用从后台返回前台时）
     */
    public void restartCameraPreview() {
        AppLog.i(TAG, "重新启动摄像头预览: " + cameraPos);
        if (textureView != null && textureView.isAvailable()) {
            if (cachedSurface != null) {
                cachedSurface.release();
                cachedSurface = null;
            }
            cachedSurface = new Surface(textureView.getSurfaceTexture());
            startCameraPreview(cachedSurface);
        } else {
            AppLog.w(TAG, "TextureView 不可用，无法重新启动预览");
        }
    }
}
