package com.kooo.evcam;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.kooo.evcam.camera.SingleCamera;

/**
 * 摄像头预览悬浮窗服务
 * 
 * 功能：
 * 1. 显示单个摄像头的实时预览画面
 * 2. 支持切换显示不同的摄像头（front/back/left/right）
 * 3. 支持拖动、缩放
 * 4. 不影响正在进行的录制
 * 
 * 实现原理：
 * 通过定时从 SingleCamera 的 TextureView 获取 Bitmap 来显示，
 * 不需要修改 Camera2 会话配置，因此不会中断录制。
 */
public class CameraPreviewFloatingService extends Service {
    private static final String TAG = "CameraPreviewFloating";
    
    // Intent 参数
    public static final String EXTRA_CAMERA_POSITION = "camera_position";
    public static final String EXTRA_WINDOW_SIZE = "window_size";
    
    // 广播动作
    public static final String ACTION_SWITCH_CAMERA = "com.kooo.evcam.PREVIEW_SWITCH_CAMERA";
    public static final String ACTION_CLOSE_PREVIEW = "com.kooo.evcam.PREVIEW_CLOSE";
    public static final String ACTION_UPDATE_SIZE = "com.kooo.evcam.PREVIEW_UPDATE_SIZE";
    
    // 默认配置
    private static final int DEFAULT_WINDOW_WIDTH_DP = 240;
    private static final int DEFAULT_WINDOW_HEIGHT_DP = 180;
    private static final int MIN_WINDOW_SIZE_DP = 120;
    private static final int MAX_WINDOW_SIZE_DP = 480;
    private static final long FRAME_INTERVAL_MS = 100;  // 10fps，足够流畅且不消耗太多资源
    
    private WindowManager windowManager;
    private View floatingView;
    private WindowManager.LayoutParams layoutParams;
    
    // UI 组件
    private ImageView previewImageView;
    private TextView cameraLabel;
    private ImageButton btnClose;
    private ImageButton btnSwitchCamera;
    private ImageButton btnZoomIn;
    private ImageButton btnZoomOut;
    
    // 状态
    private String currentCameraPosition = "front";  // 当前显示的摄像头位置
    private int currentSizeDp;  // 当前窗口基础大小（宽度）
    private boolean isRunning = false;
    
    // 帧更新
    private Handler frameHandler;
    private Runnable frameUpdateRunnable;
    
    // 拖动相关
    private int initialX;
    private int initialY;
    private float initialTouchX;
    private float initialTouchY;
    private boolean isDragging = false;
    private static final int CLICK_THRESHOLD = 10;
    
    // 广播接收器
    private BroadcastReceiver commandReceiver;
    
    // 配置
    private AppConfig appConfig;
    
    @Override
    public void onCreate() {
        super.onCreate();
        AppLog.d(TAG, "CameraPreviewFloatingService onCreate");
        
        appConfig = new AppConfig(this);
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        frameHandler = new Handler(Looper.getMainLooper());
        currentSizeDp = DEFAULT_WINDOW_WIDTH_DP;
        
        registerCommandReceiver();
    }
    
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        AppLog.d(TAG, "CameraPreviewFloatingService onStartCommand");
        
        if (intent != null) {
            // 获取参数
            currentCameraPosition = intent.getStringExtra(EXTRA_CAMERA_POSITION);
            if (currentCameraPosition == null) {
                currentCameraPosition = "front";
            }
            
            int size = intent.getIntExtra(EXTRA_WINDOW_SIZE, 0);
            if (size > 0) {
                currentSizeDp = Math.max(MIN_WINDOW_SIZE_DP, Math.min(size, MAX_WINDOW_SIZE_DP));
            }
        }
        
        // 如果已经在运行，只更新摄像头
        if (isRunning) {
            updateCameraLabel();
            return START_NOT_STICKY;
        }
        
        // 创建悬浮窗
        if (createFloatingWindow()) {
            isRunning = true;
            startFrameUpdate();
        } else {
            stopSelf();
        }
        
        return START_NOT_STICKY;
    }
    
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        super.onDestroy();
        AppLog.d(TAG, "CameraPreviewFloatingService onDestroy");
        
        isRunning = false;
        stopFrameUpdate();
        
        // 移除悬浮窗
        if (floatingView != null && windowManager != null) {
            try {
                windowManager.removeView(floatingView);
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to remove floating view", e);
            }
        }
        
        // 注销广播接收器
        if (commandReceiver != null) {
            try {
                unregisterReceiver(commandReceiver);
            } catch (Exception e) {
                AppLog.e(TAG, "Failed to unregister receiver", e);
            }
        }
    }
    
    /**
     * 创建悬浮窗
     */
    private boolean createFloatingWindow() {
        // 检查权限
        if (!WakeUpHelper.hasOverlayPermission(this)) {
            AppLog.e(TAG, "No overlay permission");
            return false;
        }
        
        // 加载布局
        LayoutInflater inflater = LayoutInflater.from(this);
        floatingView = inflater.inflate(R.layout.floating_camera_preview, null);
        
        // 绑定 UI 组件
        previewImageView = floatingView.findViewById(R.id.preview_image);
        cameraLabel = floatingView.findViewById(R.id.camera_label);
        btnClose = floatingView.findViewById(R.id.btn_close);
        btnSwitchCamera = floatingView.findViewById(R.id.btn_switch_camera);
        btnZoomIn = floatingView.findViewById(R.id.btn_zoom_in);
        btnZoomOut = floatingView.findViewById(R.id.btn_zoom_out);
        
        // 设置按钮事件
        btnClose.setOnClickListener(v -> {
            AppLog.d(TAG, "Close button clicked");
            stopSelf();
        });
        
        btnSwitchCamera.setOnClickListener(v -> {
            switchToNextCamera();
        });
        
        btnZoomIn.setOnClickListener(v -> {
            zoomIn();
        });
        
        btnZoomOut.setOnClickListener(v -> {
            zoomOut();
        });
        
        // 更新摄像头标签
        updateCameraLabel();
        
        // 计算窗口大小
        float density = getResources().getDisplayMetrics().density;
        int widthPx = (int) (currentSizeDp * density);
        int heightPx = (int) (currentSizeDp * 0.75f * density);  // 4:3 比例
        
        // 设置窗口参数
        int windowType;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            windowType = WindowManager.LayoutParams.TYPE_PHONE;
        }
        
        layoutParams = new WindowManager.LayoutParams(
                widthPx,
                heightPx,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
        );
        
        layoutParams.gravity = Gravity.TOP | Gravity.START;
        
        // 默认位置：屏幕右下角
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        layoutParams.x = metrics.widthPixels - widthPx - 20;
        layoutParams.y = metrics.heightPixels - heightPx - 100;
        
        // 设置拖动事件（在整个悬浮窗上）
        floatingView.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        initialX = layoutParams.x;
                        initialY = layoutParams.y;
                        initialTouchX = event.getRawX();
                        initialTouchY = event.getRawY();
                        isDragging = false;
                        return true;
                        
                    case MotionEvent.ACTION_MOVE:
                        int deltaX = (int) (event.getRawX() - initialTouchX);
                        int deltaY = (int) (event.getRawY() - initialTouchY);
                        
                        if (Math.abs(deltaX) > CLICK_THRESHOLD || Math.abs(deltaY) > CLICK_THRESHOLD) {
                            isDragging = true;
                        }
                        
                        if (isDragging) {
                            DisplayMetrics m = getResources().getDisplayMetrics();
                            int screenWidth = m.widthPixels;
                            int screenHeight = m.heightPixels;
                            
                            int newX = initialX + deltaX;
                            int newY = initialY + deltaY;
                            
                            // 边界限制
                            newX = Math.max(0, Math.min(newX, screenWidth - layoutParams.width));
                            newY = Math.max(0, Math.min(newY, screenHeight - layoutParams.height));
                            
                            layoutParams.x = newX;
                            layoutParams.y = newY;
                            windowManager.updateViewLayout(floatingView, layoutParams);
                        }
                        return true;
                        
                    case MotionEvent.ACTION_UP:
                        return !isDragging;  // 如果不是拖动，让子视图处理点击
                }
                return false;
            }
        });
        
        // 添加到窗口
        try {
            windowManager.addView(floatingView, layoutParams);
            AppLog.d(TAG, "Floating preview window created: " + widthPx + "x" + heightPx);
            return true;
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to add floating view", e);
            return false;
        }
    }
    
    /**
     * 开始帧更新
     */
    private void startFrameUpdate() {
        stopFrameUpdate();
        
        frameUpdateRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) {
                    return;
                }
                
                updatePreviewFrame();
                frameHandler.postDelayed(this, FRAME_INTERVAL_MS);
            }
        };
        
        frameHandler.post(frameUpdateRunnable);
        AppLog.d(TAG, "Frame update started");
    }
    
    /**
     * 停止帧更新
     */
    private void stopFrameUpdate() {
        if (frameUpdateRunnable != null) {
            frameHandler.removeCallbacks(frameUpdateRunnable);
            frameUpdateRunnable = null;
        }
    }
    
    /**
     * 更新预览帧
     */
    private void updatePreviewFrame() {
        // 获取 MainActivity 实例
        MainActivity mainActivity = MainActivity.getInstance();
        if (mainActivity == null) {
            AppLog.w(TAG, "MainActivity not available");
            showNoSignal();
            return;
        }
        
        // 获取对应位置的摄像头
        SingleCamera camera = mainActivity.getCameraByPosition(currentCameraPosition);
        if (camera == null) {
            AppLog.w(TAG, "Camera not found for position: " + currentCameraPosition);
            showNoSignal();
            return;
        }
        
        // 获取当前帧
        Bitmap bitmap = camera.captureBitmap();
        if (bitmap != null) {
            previewImageView.setImageBitmap(bitmap);
            // 注意：这里不需要回收 bitmap，因为 ImageView 会管理它
            // 但上一帧的 bitmap 需要处理，ImageView 内部会处理
        } else {
            showNoSignal();
        }
    }
    
    /**
     * 显示无信号状态
     */
    private void showNoSignal() {
        // 设置一个灰色占位图或保持上一帧
        // 为了用户体验，保持上一帧而不是显示灰色
    }
    
    /**
     * 更新摄像头标签
     */
    private void updateCameraLabel() {
        if (cameraLabel != null) {
            String label;
            switch (currentCameraPosition) {
                case "front":
                    label = "前";
                    break;
                case "back":
                    label = "后";
                    break;
                case "left":
                    label = "左";
                    break;
                case "right":
                    label = "右";
                    break;
                default:
                    label = currentCameraPosition;
            }
            cameraLabel.setText(label);
        }
    }
    
    /**
     * 切换到下一个摄像头
     */
    private void switchToNextCamera() {
        String[] positions = {"front", "back", "left", "right"};
        int currentIndex = 0;
        for (int i = 0; i < positions.length; i++) {
            if (positions[i].equals(currentCameraPosition)) {
                currentIndex = i;
                break;
            }
        }
        
        // 切换到下一个
        currentCameraPosition = positions[(currentIndex + 1) % positions.length];
        updateCameraLabel();
        AppLog.d(TAG, "Switched to camera: " + currentCameraPosition);
    }
    
    /**
     * 切换到指定摄像头
     */
    private void switchToCamera(String position) {
        if (position != null && !position.equals(currentCameraPosition)) {
            currentCameraPosition = position;
            updateCameraLabel();
            AppLog.d(TAG, "Switched to camera: " + currentCameraPosition);
        }
    }
    
    /**
     * 放大窗口
     */
    private void zoomIn() {
        int newSize = currentSizeDp + 40;
        if (newSize <= MAX_WINDOW_SIZE_DP) {
            updateWindowSize(newSize);
        }
    }
    
    /**
     * 缩小窗口
     */
    private void zoomOut() {
        int newSize = currentSizeDp - 40;
        if (newSize >= MIN_WINDOW_SIZE_DP) {
            updateWindowSize(newSize);
        }
    }
    
    /**
     * 更新窗口大小
     */
    private void updateWindowSize(int sizeDp) {
        currentSizeDp = sizeDp;
        
        float density = getResources().getDisplayMetrics().density;
        int widthPx = (int) (sizeDp * density);
        int heightPx = (int) (sizeDp * 0.75f * density);
        
        layoutParams.width = widthPx;
        layoutParams.height = heightPx;
        
        // 确保不超出屏幕
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        if (layoutParams.x + widthPx > metrics.widthPixels) {
            layoutParams.x = metrics.widthPixels - widthPx;
        }
        if (layoutParams.y + heightPx > metrics.heightPixels) {
            layoutParams.y = metrics.heightPixels - heightPx;
        }
        
        try {
            windowManager.updateViewLayout(floatingView, layoutParams);
            AppLog.d(TAG, "Window resized to: " + widthPx + "x" + heightPx);
        } catch (Exception e) {
            AppLog.e(TAG, "Failed to resize window", e);
        }
    }
    
    /**
     * 注册命令广播接收器
     */
    private void registerCommandReceiver() {
        commandReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                if (action == null) return;
                
                switch (action) {
                    case ACTION_SWITCH_CAMERA:
                        String position = intent.getStringExtra(EXTRA_CAMERA_POSITION);
                        if (position != null) {
                            switchToCamera(position);
                        } else {
                            switchToNextCamera();
                        }
                        break;
                        
                    case ACTION_CLOSE_PREVIEW:
                        stopSelf();
                        break;
                        
                    case ACTION_UPDATE_SIZE:
                        int size = intent.getIntExtra(EXTRA_WINDOW_SIZE, currentSizeDp);
                        updateWindowSize(size);
                        break;
                }
            }
        };
        
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_SWITCH_CAMERA);
        filter.addAction(ACTION_CLOSE_PREVIEW);
        filter.addAction(ACTION_UPDATE_SIZE);
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(commandReceiver, filter);
        }
    }
    
    // ==================== 静态方法 ====================
    
    /**
     * 启动摄像头预览悬浮窗
     * @param context 上下文
     * @param cameraPosition 摄像头位置（front/back/left/right）
     */
    public static void start(Context context, String cameraPosition) {
        if (!WakeUpHelper.hasOverlayPermission(context)) {
            AppLog.w(TAG, "No overlay permission, cannot start preview");
            return;
        }
        
        Intent intent = new Intent(context, CameraPreviewFloatingService.class);
        intent.putExtra(EXTRA_CAMERA_POSITION, cameraPosition);
        context.startService(intent);
    }
    
    /**
     * 启动摄像头预览悬浮窗（带窗口大小）
     */
    public static void start(Context context, String cameraPosition, int windowSizeDp) {
        if (!WakeUpHelper.hasOverlayPermission(context)) {
            AppLog.w(TAG, "No overlay permission, cannot start preview");
            return;
        }
        
        Intent intent = new Intent(context, CameraPreviewFloatingService.class);
        intent.putExtra(EXTRA_CAMERA_POSITION, cameraPosition);
        intent.putExtra(EXTRA_WINDOW_SIZE, windowSizeDp);
        context.startService(intent);
    }
    
    /**
     * 停止摄像头预览悬浮窗
     */
    public static void stop(Context context) {
        Intent intent = new Intent(context, CameraPreviewFloatingService.class);
        context.stopService(intent);
    }
    
    /**
     * 切换摄像头（通过广播）
     */
    public static void switchCamera(Context context, String position) {
        Intent intent = new Intent(ACTION_SWITCH_CAMERA);
        intent.setPackage(context.getPackageName());
        intent.putExtra(EXTRA_CAMERA_POSITION, position);
        context.sendBroadcast(intent);
    }
    
    /**
     * 切换到下一个摄像头（通过广播）
     */
    public static void switchToNextCamera(Context context) {
        Intent intent = new Intent(ACTION_SWITCH_CAMERA);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }
}
