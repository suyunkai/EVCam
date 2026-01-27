package com.kooo.evcam;


import com.kooo.evcam.AppLog;
import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.google.android.material.navigation.NavigationView;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;
import com.kooo.evcam.dingtalk.DingTalkApiClient;
import com.kooo.evcam.dingtalk.DingTalkConfig;
import com.kooo.evcam.dingtalk.DingTalkStreamManager;
import com.kooo.evcam.dingtalk.PhotoUploadService;
import com.kooo.evcam.dingtalk.VideoUploadService;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int REQUEST_PERMISSIONS = 100;

    // 根据Android版本动态获取需要的权限
    private String[] getRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            };
        } else {
            // Android 12及以下
            return new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    Manifest.permission.READ_EXTERNAL_STORAGE
            };
        }
    }

    private AutoFitTextureView textureFront, textureBack, textureLeft, textureRight;
    private Button btnStartRecord, btnExit, btnTakePhoto;
    private MultiCameraManager cameraManager;
    private int textureReadyCount = 0;  // 记录准备好的TextureView数量
    private int requiredTextureCount = 4;  // 需要准备好的TextureView数量（根据摄像头数量）
    private boolean isRecording = false;  // 录制状态标志
    private boolean isInBackground = false;  // 是否在后台
    private boolean pendingRemoteCommand = false;  // 是否有待处理的远程命令
    private boolean isRemoteWakeUp = false;  // 是否是远程命令唤醒的（用于完成后自动退回后台）
    
    // 防双击保护
    private long lastRecordButtonClickTime = 0;  // 上次点击录制按钮的时间
    private static final long RECORD_BUTTON_CLICK_INTERVAL = 1000;  // 最小点击间隔（1秒）
    private boolean shouldMoveToBackgroundOnReady = false;  // 开机自启动后，窗口准备好时移到后台
    
    // 车型配置相关
    private AppConfig appConfig;
    private int configuredCameraCount = 4;  // 配置的摄像头数量

    // 录制按钮闪烁动画相关
    private android.os.Handler blinkHandler;
    private Runnable blinkRunnable;
    private boolean isBlinking = false;

    // 录制状态显示相关
    private TextView tvRecordingStats;
    private android.os.Handler recordingTimerHandler;
    private Runnable recordingTimerRunnable;
    private long recordingStartTime = 0;  // 录制开始时间
    private int currentSegmentCount = 1;  // 当前分段数
    private boolean isRecordingStatsEnabled = true;  // 录制状态显示开关
    private long lastStatsClickTime = 0;  // 上次点击录制状态显示的时间
    private static final long DOUBLE_CLICK_INTERVAL = 500;  // 双击判定间隔（毫秒）

    // 导航相关
    private DrawerLayout drawerLayout;
    private NavigationView navigationView;
    private View recordingLayout;  // 录制界面布局
    private View fragmentContainer;  // Fragment容器


    // 远程录制相关
    private String remoteConversationId;  // 钉钉会话 ID
    private String remoteConversationType;  // 钉钉会话类型（"1"=单聊，"2"=群聊）
    private String remoteUserId;  // 钉钉用户 ID
    private android.os.Handler autoStopHandler;  // 自动停止录制的 Handler
    private Runnable autoStopRunnable;  // 自动停止录制的 Runnable
    private String remoteRecordingTimestamp;  // 远程录制统一时间戳（用于文件命名和查找）

    // 钉钉服务相关（移到 Activity 级别）
    private DingTalkConfig dingTalkConfig;
    private DingTalkApiClient dingTalkApiClient;
    private DingTalkStreamManager dingTalkStreamManager;
    
    // 存储清理管理器
    private StorageCleanupManager storageCleanupManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        AppLog.init(this);

        // 设置字体缩放比例（1.3倍）
        adjustFontScale(1.2f);

        // 初始化应用配置
        appConfig = new AppConfig(this);
        
        // 根据车型配置设置布局和摄像头数量
        setupLayoutByCarModel();

        // 设置状态栏沉浸式
        setupStatusBar();

        initViews();
        setupNavigationDrawer();

        // 检查是否首次启动
        checkFirstLaunch();

        // 初始化钉钉配置
        dingTalkConfig = new DingTalkConfig(this);

        // 初始化自动停止 Handler
        autoStopHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        // 初始化远程录制时间戳
        remoteRecordingTimestamp = null;

        // 权限检查，但不立即初始化摄像头
        // 等待TextureView准备好后再初始化
        if (!checkPermissions()) {
            requestPermissions();
        }

        // 如果启用了自动启动，启动钉钉服务
        if (dingTalkConfig.isConfigured() && dingTalkConfig.isAutoStart()) {
            startDingTalkService();
        }

        // 启动定时保活任务（如果用户启用了）
        if (appConfig.isKeepAliveEnabled()) {
            KeepAliveManager.startKeepAliveWork(this);
            AppLog.d(TAG, "定时保活任务已启动");
        } else {
            AppLog.d(TAG, "定时保活任务已禁用，跳过启动");
        }
        
        // 启动存储清理任务（如果用户设置了限制）
        storageCleanupManager = new StorageCleanupManager(this);
        storageCleanupManager.start();

        // 检查是否是开机自启动
        boolean autoStartFromBoot = getIntent().getBooleanExtra("auto_start_from_boot", false);
        if (autoStartFromBoot) {
            AppLog.d(TAG, "开机自启动模式：等待窗口准备好后移到后台");
            
            // 清除标志，避免后续重复检测
            getIntent().removeExtra("auto_start_from_boot");
            
            // 设置标志，等待 onWindowFocusChanged 时再移到后台
            // 这确保 Activity 完全初始化后再执行，避免中断初始化过程
            shouldMoveToBackgroundOnReady = true;
        }

        // 检查是否有启动时传入的远程命令（冷启动）
        handleRemoteCommandFromIntent(getIntent());

        // 启动悬浮窗服务（如果已启用）
        if (appConfig.isFloatingWindowEnabled() && WakeUpHelper.hasOverlayPermission(this)) {
            FloatingWindowService.start(this);
            AppLog.d(TAG, "悬浮窗服务已启动");
            
            // 延迟发送当前状态（等待服务启动完成）
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                // 发送当前录制状态
                broadcastCurrentRecordingState();
                // 应用在前台，隐藏悬浮窗
                FloatingWindowService.sendAppForegroundState(this, true);
            }, 500);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AppLog.d(TAG, "onNewIntent called");
        
        // 处理远程命令
        handleRemoteCommandFromIntent(intent);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        
        // 如果是开机自启动模式，窗口准备好后自动移到后台
        if (hasFocus && shouldMoveToBackgroundOnReady) {
            AppLog.d(TAG, "开机自启动：窗口已就绪，移到后台（无感启动）");
            shouldMoveToBackgroundOnReady = false;  // 清除标志，避免重复执行
            
            // 延迟移到后台，确保初始化完成
            new android.os.Handler().postDelayed(() -> {
                moveTaskToBack(true);  // 将应用移到后台
                AppLog.d(TAG, "应用已移到后台，开机自启动完成");
            }, 500);  // 延迟 500ms
        }
    }

    /**
     * 处理来自 Intent 的远程命令
     * 由 WakeUpHelper 启动时传入
     */
    private void handleRemoteCommandFromIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getStringExtra("remote_action");
        if (action == null || action.isEmpty()) {
            return;
        }

        AppLog.d(TAG, "Received remote command from intent: " + action);

        // 先切换到主界面（录制界面），确保显示正确的界面
        showRecordingInterface();
        AppLog.d(TAG, "Switched to recording interface");

        // 提取参数
        String conversationId = intent.getStringExtra("remote_conversation_id");
        String conversationType = intent.getStringExtra("remote_conversation_type");
        String userId = intent.getStringExtra("remote_user_id");
        int duration = intent.getIntExtra("remote_duration", 60);

        // 清除 Intent 中的命令，避免重复执行
        intent.removeExtra("remote_action");

        // 标记有待处理的远程命令
        pendingRemoteCommand = true;
        
        // 标记这是远程唤醒，完成后需要自动退回后台
        isRemoteWakeUp = true;
        AppLog.d(TAG, "Remote wake-up flag set, will return to background after completion");

        // 延迟执行命令，等待摄像头准备好
        // 如果从后台唤醒，摄像头需要时间重新连接
        int delay = isInBackground ? 3000 : 1500;
        
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            pendingRemoteCommand = false;
            
            // 检查摄像头是否准备好
            if (cameraManager == null) {
                AppLog.e(TAG, "CameraManager is null");
                executeRemoteCommand(action, conversationId, conversationType, userId, duration);
                return;
            }
            
            int connectedCount = cameraManager.getConnectedCameraCount();
            AppLog.d(TAG, "Connected cameras: " + connectedCount + "/4");
            
            // 如果连接的摄像头少于4个，继续等待
            if (connectedCount < 4) {
                AppLog.w(TAG, "Only " + connectedCount + " cameras connected, waiting 1.5s more...");
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    int finalCount = cameraManager.getConnectedCameraCount();
                    AppLog.d(TAG, "After waiting, connected cameras: " + finalCount + "/4");
                    if (finalCount < 4) {
                        AppLog.w(TAG, "Still only " + finalCount + " cameras ready, executing anyway");
                    }
                    executeRemoteCommand(action, conversationId, conversationType, userId, duration);
                }, 1500);
            } else {
                AppLog.d(TAG, "All 4 cameras ready, executing command");
                executeRemoteCommand(action, conversationId, conversationType, userId, duration);
            }
        }, delay);
    }

    /**
     * 执行远程命令
     */
    private void executeRemoteCommand(String action, String conversationId, 
            String conversationType, String userId, int duration) {
        AppLog.d(TAG, "Executing remote command: " + action);
        
        if ("record".equals(action)) {
            AppLog.d(TAG, "Starting remote recording for " + duration + " seconds");
            startRemoteRecording(conversationId, conversationType, userId, duration);
        } else if ("photo".equals(action)) {
            AppLog.d(TAG, "Taking remote photo");
            startRemotePhoto(conversationId, conversationType, userId);
        } else {
            AppLog.w(TAG, "Unknown remote action: " + action);
        }
    }

    private void adjustFontScale(float scale) {
        android.content.res.Configuration configuration = getResources().getConfiguration();
        configuration.fontScale = scale;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        getBaseContext().getResources().updateConfiguration(configuration, metrics);
    }
    
    /**
     * 根据车型配置设置布局
     */
    private void setupLayoutByCarModel() {
        // 默认使用4摄像头布局（银河E5专用）
        int layoutId = R.layout.activity_main;
        configuredCameraCount = 4;
        requiredTextureCount = 4;

        String carModel = appConfig.getCarModel();
        
        // 银河L6/L7：竖屏四宫格布局
        if (AppConfig.CAR_MODEL_L7.equals(carModel)) {
            layoutId = R.layout.activity_main_l7;
            configuredCameraCount = 4;
            requiredTextureCount = 4;
            AppLog.d(TAG, "使用银河L6/L7配置：竖屏四宫格布局");
        }
        // 银河L7-多按钮：竖屏四宫格布局（顶部多功能按钮）
        else if (AppConfig.CAR_MODEL_L7_MULTI.equals(carModel)) {
            layoutId = R.layout.activity_main_l7_multi;
            configuredCameraCount = 4;
            requiredTextureCount = 4;
            AppLog.d(TAG, "使用银河L7-多按钮配置：竖屏四宫格+顶部快捷按钮布局");
        }
        // 手机：自适应2摄像头布局
        else if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
            layoutId = R.layout.activity_main_phone;
            configuredCameraCount = 2;
            requiredTextureCount = 2;
            AppLog.d(TAG, "使用手机配置：自适应2摄像头布局");
        }
        // 自定义车型：根据配置选择布局
        else if (appConfig.isCustomCarModel()) {
            configuredCameraCount = appConfig.getCameraCount();
            String orientation = appConfig.getScreenOrientation();

            switch (configuredCameraCount) {
                case 1:
                    layoutId = R.layout.activity_main_1cam;
                    requiredTextureCount = 1;
                    AppLog.d(TAG, "使用自定义车型：1摄像头布局");
                    break;
                case 2:
                    layoutId = R.layout.activity_main_2cam;
                    requiredTextureCount = 2;
                    AppLog.d(TAG, "使用自定义车型：2摄像头布局");
                    break;
                case 4:
                    // 4摄像头：根据屏幕方向选择布局
                    if ("portrait".equals(orientation)) {
                        layoutId = R.layout.activity_main_4cam_portrait;
                        AppLog.d(TAG, "使用自定义车型：4摄像头竖屏布局");
                    } else {
                        layoutId = R.layout.activity_main_4cam;
                        AppLog.d(TAG, "使用自定义车型：4摄像头横屏布局");
                    }
                    requiredTextureCount = 4;
                    break;
                default:
                    // 无效的摄像头数量，使用自定义4摄像头横屏布局
                    layoutId = R.layout.activity_main_4cam;
                    configuredCameraCount = 4;
                    requiredTextureCount = 4;
                    AppLog.d(TAG, "使用自定义车型：4摄像头横屏布局（默认）");
                    break;
            }
        }
        // 银河E5：横屏四摄像头布局
        else {
            AppLog.d(TAG, "使用银河E5默认配置：4摄像头布局");
        }

        setContentView(layoutId);
    }

    private void setupStatusBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            // 设置状态栏颜色为菜单栏背景色
            getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.menu_background));

            // 根据当前主题模式设置状态栏图标颜色
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                int nightModeFlags = getResources().getConfiguration().uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
                if (nightModeFlags == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
                    // 夜间模式：清除浅色状态栏标志，使用深色图标变为浅色图标
                    getWindow().getDecorView().setSystemUiVisibility(0);
                } else {
                    // 日间模式：设置状态栏图标为深色（因为背景是浅色）
                    getWindow().getDecorView().setSystemUiVisibility(
                        View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                    );
                }
            }
        }
    }

    private void initViews() {
        drawerLayout = findViewById(R.id.drawer_layout);
        navigationView = findViewById(R.id.nav_view);
        recordingLayout = findViewById(R.id.main);
        fragmentContainer = findViewById(R.id.fragment_container);

        // 根据布局获取TextureView（不同布局有不同数量的TextureView）
        textureFront = findViewById(R.id.texture_front);
        textureBack = findViewById(R.id.texture_back);  // 1摄布局中为null
        textureLeft = findViewById(R.id.texture_left);  // 1摄和2摄布局中为null
        textureRight = findViewById(R.id.texture_right);  // 1摄和2摄布局中为null
        
        btnStartRecord = findViewById(R.id.btn_start_record);
        btnExit = findViewById(R.id.btn_exit);
        btnTakePhoto = findViewById(R.id.btn_take_photo);
        
        // 初始化录制状态显示
        tvRecordingStats = findViewById(R.id.tv_recording_stats);
        initRecordingStatsDisplay();
        
        // 更新摄像头标签（如果是自定义车型）
        updateCameraLabels();

        // 菜单按钮点击事件
        findViewById(R.id.btn_menu).setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });
        
        // 多按钮布局的快捷导航按钮（仅在 L7-多按钮 布局中存在）
        View btnVideoPlayback = findViewById(R.id.btn_video_playback);
        if (btnVideoPlayback != null) {
            btnVideoPlayback.setOnClickListener(v -> showPlaybackInterface());
        }
        
        View btnPhotoPlayback = findViewById(R.id.btn_photo_playback);
        if (btnPhotoPlayback != null) {
            btnPhotoPlayback.setOnClickListener(v -> showPhotoPlaybackInterface());
        }
        
        View btnRemoteView = findViewById(R.id.btn_remote_view);
        if (btnRemoteView != null) {
            btnRemoteView.setOnClickListener(v -> showRemoteViewInterface());
        }
        
        View btnSettings = findViewById(R.id.btn_settings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> showSettingsInterface());
        }

        // 录制按钮：点击切换录制状态
        btnStartRecord.setOnClickListener(v -> toggleRecording());

        // 退出按钮：完全退出应用
        btnExit.setOnClickListener(v -> exitApp());

        btnTakePhoto.setOnClickListener(v -> takePicture());

        // 为每个TextureView添加Surface监听器
        TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                textureReadyCount++;
                AppLog.d(TAG, "TextureView ready: " + textureReadyCount + "/" + requiredTextureCount);

                // 当所有需要的TextureView都准备好后，初始化摄像头
                if (textureReadyCount >= requiredTextureCount && checkPermissions()) {
                    initCamera();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                AppLog.d(TAG, "TextureView size changed: " + width + "x" + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
                textureReadyCount--;
                AppLog.d(TAG, "TextureView destroyed, remaining: " + textureReadyCount);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {
                // 不需要处理每帧更新
            }
        };

        // 根据配置的摄像头数量设置监听器
        if (textureFront != null) {
            textureFront.setSurfaceTextureListener(surfaceTextureListener);
        }
        if (textureBack != null && configuredCameraCount >= 2) {
            textureBack.setSurfaceTextureListener(surfaceTextureListener);
        }
        if (textureLeft != null && configuredCameraCount >= 4) {
            textureLeft.setSurfaceTextureListener(surfaceTextureListener);
        }
        if (textureRight != null && configuredCameraCount >= 4) {
            textureRight.setSurfaceTextureListener(surfaceTextureListener);
        }
    }
    
    /**
     * 更新摄像头标签（自定义车型时使用自定义名称）
     */
    private void updateCameraLabels() {
        if (!appConfig.isCustomCarModel()) {
            return;
        }
        
        // 获取标签控件（根据布局可能存在或不存在）
        TextView labelFront = findViewById(R.id.label_front);
        TextView labelBack = findViewById(R.id.label_back);
        TextView labelLeft = findViewById(R.id.label_left);
        TextView labelRight = findViewById(R.id.label_right);
        
        // 设置自定义名称，如果名称为空则隐藏标签
        if (labelFront != null) {
            updateCameraLabel(labelFront, appConfig.getCameraName("front"));
        }
        if (labelBack != null && configuredCameraCount >= 2) {
            updateCameraLabel(labelBack, appConfig.getCameraName("back"));
        }
        if (labelLeft != null && configuredCameraCount >= 4) {
            updateCameraLabel(labelLeft, appConfig.getCameraName("left"));
        }
        if (labelRight != null && configuredCameraCount >= 4) {
            updateCameraLabel(labelRight, appConfig.getCameraName("right"));
        }
    }
    
    /**
     * 更新单个摄像头标签，如果名称为空则隐藏
     */
    private void updateCameraLabel(TextView label, String name) {
        if (name == null || name.trim().isEmpty()) {
            label.setVisibility(View.GONE);
        } else {
            label.setText(name);
            label.setVisibility(View.VISIBLE);
        }
    }
    
    /**
     * 初始化录制状态显示
     */
    private void initRecordingStatsDisplay() {
        if (tvRecordingStats == null) {
            return;
        }
        
        // 从设置加载显示开关状态
        isRecordingStatsEnabled = appConfig.isRecordingStatsEnabled();
        
        // 初始化计时器 Handler
        recordingTimerHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        
        // 确保 View 可点击（即使 INVISIBLE 也能响应点击）
        tvRecordingStats.setClickable(true);
        tvRecordingStats.setFocusable(true);
        
        // 设置双击切换显示/隐藏
        tvRecordingStats.setOnClickListener(v -> {
            long currentTime = System.currentTimeMillis();
            if (currentTime - lastStatsClickTime < DOUBLE_CLICK_INTERVAL) {
                // 双击：切换显示状态
                toggleRecordingStatsDisplay();
                lastStatsClickTime = 0;  // 重置，避免三连击触发
            } else {
                lastStatsClickTime = currentTime;
            }
            AppLog.d(TAG, "录制状态显示被点击, isRecording=" + isRecording + ", enabled=" + isRecordingStatsEnabled);
        });
    }
    
    /**
     * 切换录制状态显示的开关
     */
    private void toggleRecordingStatsDisplay() {
        isRecordingStatsEnabled = !isRecordingStatsEnabled;
        appConfig.setRecordingStatsEnabled(isRecordingStatsEnabled);
        
        if (tvRecordingStats != null && isRecording) {
            if (isRecordingStatsEnabled) {
                // 显示状态（使用 alpha 恢复可见）
                tvRecordingStats.setAlpha(1.0f);
                Toast.makeText(this, "录制状态显示已开启", Toast.LENGTH_SHORT).show();
            } else {
                // 使用 alpha=0 隐藏，但保持 VISIBLE 状态以响应点击
                tvRecordingStats.setAlpha(0.0f);
                Toast.makeText(this, "录制状态显示已关闭", Toast.LENGTH_SHORT).show();
            }
        }
        
        AppLog.d(TAG, "录制状态显示切换: " + (isRecordingStatsEnabled ? "开启" : "关闭"));
    }
    
    /**
     * 开始录制计时器
     */
    private void startRecordingTimer() {
        recordingStartTime = System.currentTimeMillis();
        currentSegmentCount = 1;
        
        if (tvRecordingStats != null) {
            // 始终设为 VISIBLE，通过 alpha 控制可见性
            tvRecordingStats.setVisibility(View.VISIBLE);
            tvRecordingStats.setAlpha(isRecordingStatsEnabled ? 1.0f : 0.0f);
            updateRecordingStatsDisplay();
        }
        
        // 创建定时更新任务
        recordingTimerRunnable = new Runnable() {
            @Override
            public void run() {
                if (isRecording) {
                    updateRecordingStatsDisplay();
                    recordingTimerHandler.postDelayed(this, 1000);  // 每秒更新一次
                }
            }
        };
        
        recordingTimerHandler.post(recordingTimerRunnable);
    }
    
    /**
     * 停止录制计时器
     */
    private void stopRecordingTimer() {
        if (recordingTimerHandler != null && recordingTimerRunnable != null) {
            recordingTimerHandler.removeCallbacks(recordingTimerRunnable);
        }
        
        // 隐藏录制状态显示
        if (tvRecordingStats != null) {
            tvRecordingStats.setVisibility(View.GONE);
        }
        
        recordingStartTime = 0;
        currentSegmentCount = 1;
    }
    
    /**
     * 更新录制状态显示
     */
    private void updateRecordingStatsDisplay() {
        if (tvRecordingStats == null) {
            return;
        }
        
        // 计算录制时长
        long elapsedMs = System.currentTimeMillis() - recordingStartTime;
        long totalSeconds = elapsedMs / 1000;
        long minutes = totalSeconds / 60;
        long seconds = totalSeconds % 60;
        
        // 格式化时间：MM:SS / 分段数（即使隐藏也更新文本，便于双击显示时立即看到正确时间）
        String timeStr = String.format(java.util.Locale.getDefault(), "%02d:%02d / %d", minutes, seconds, currentSegmentCount);
        tvRecordingStats.setText(timeStr);
    }
    
    /**
     * 当分段切换时调用，更新分段计数
     */
    public void onSegmentSwitch(int newSegmentIndex) {
        currentSegmentCount = newSegmentIndex + 1;  // 分段索引从0开始，显示从1开始
        AppLog.d(TAG, "分段切换: 第 " + currentSegmentCount + " 段");
        
        // 立即更新显示
        runOnUiThread(this::updateRecordingStatsDisplay);
    }
    
    /**
     * 刷新录制状态显示设置（从设置界面返回时调用）
     */
    public void refreshRecordingStatsSettings() {
        isRecordingStatsEnabled = appConfig.isRecordingStatsEnabled();
        
        // 如果正在录制，根据新设置显示或隐藏（通过 alpha 控制，保持可点击）
        if (isRecording && tvRecordingStats != null) {
            tvRecordingStats.setAlpha(isRecordingStatsEnabled ? 1.0f : 0.0f);
        }
    }

    /**
     * 设置导航抽屉
     */
    private void setupNavigationDrawer() {
        // 设置导航菜单点击监听
        navigationView.setNavigationItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.nav_recording) {
                // 显示录制界面
                showRecordingInterface();
            } else if (itemId == R.id.nav_playback) {
                // 显示回看界面
                showPlaybackInterface();
            } else if (itemId == R.id.nav_photo_playback) {
                // 显示图片回看界面
                showPhotoPlaybackInterface();
            } else if (itemId == R.id.nav_remote_view) {
                // 显示远程查看界面
                showRemoteViewInterface();
            } else if (itemId == R.id.nav_settings) {
                showSettingsInterface();
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // 默认选中录制界面
        navigationView.setCheckedItem(R.id.nav_recording);
    }

    /**
     * 检查并处理首次启动
     * 首次启动时自动进入设置界面并显示引导弹窗
     */
    private void checkFirstLaunch() {
        if (appConfig == null || !appConfig.isFirstLaunch()) {
            return;
        }

        AppLog.d(TAG, "检测到首次启动，进入设置界面");

        // 标记首次启动已完成（在显示弹窗前标记，避免重复触发）
        appConfig.setFirstLaunchCompleted();

        // 延迟执行，确保 UI 完全初始化
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // 进入设置界面
            showSettingsInterface();
            navigationView.setCheckedItem(R.id.nav_settings);

            // 显示引导弹窗
            showFirstLaunchGuideDialog();
        }, 300);
    }

    /**
     * 显示首次启动引导弹窗（美化版）
     */
    private void showFirstLaunchGuideDialog() {
        // 创建自定义对话框
        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_first_launch_guide);
        dialog.setCancelable(false);

        // 设置对话框窗口属性
        android.view.Window window = dialog.getWindow();
        if (window != null) {
            // 设置背景透明（让圆角生效）
            window.setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            // 设置对话框宽度
            android.view.WindowManager.LayoutParams params = window.getAttributes();
            params.width = (int) (getResources().getDisplayMetrics().widthPixels * 0.85);
            window.setAttributes(params);
        }

        // 加载二维码图片
        android.widget.ImageView ivQrcode = dialog.findViewById(R.id.iv_qrcode);
        loadQrcodeImage(ivQrcode);

        // 设置确认按钮点击事件
        dialog.findViewById(R.id.btn_confirm).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    /**
     * 加载打赏二维码图片（URL经过混淆处理）
     */
    private void loadQrcodeImage(android.widget.ImageView imageView) {
        // URL混淆存储，防止被轻易修改
        // 原始URL经过Base64编码后分段存储
        final String[] p = {
            "aHR0cHM6Ly9ldmNhbS5jaGF0d2Vi", // 第一段
            "LmNsb3VkLzE3Njk0NzcxOTc4NTUu", // 第二段  
            "anBn"                           // 第三段
        };
        
        new Thread(() -> {
            try {
                // 组合并解码URL
                String encoded = p[0] + p[1] + p[2];
                String url = new String(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT));
                
                // 下载图片
                java.net.URL imageUrl = new java.net.URL(url);
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) imageUrl.openConnection();
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoInput(true);
                conn.connect();
                
                java.io.InputStream is = conn.getInputStream();
                final android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeStream(is);
                is.close();
                conn.disconnect();
                
                // 在主线程更新UI
                if (bitmap != null) {
                    runOnUiThread(() -> imageView.setImageBitmap(bitmap));
                }
            } catch (Exception e) {
                AppLog.e(TAG, "加载二维码图片失败: " + e.getMessage());
            }
        }).start();
    }

    /**
     * 显示录制界面
     */
    private void showRecordingInterface() {
        // 清除所有Fragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        for (Fragment fragment : fragmentManager.getFragments()) {
            fragmentManager.beginTransaction().remove(fragment).commit();
        }

        // 显示录制布局，隐藏Fragment容器
        recordingLayout.setVisibility(View.VISIBLE);
        fragmentContainer.setVisibility(View.GONE);
    }

    /**
     * 显示回看界面
     */
    private void showPlaybackInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示PlaybackFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new PlaybackFragment());
        transaction.commit();
    }

    /**
     * 显示图片回看界面
     */
    private void showPhotoPlaybackInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示PhotoPlaybackFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new PhotoPlaybackFragment());
        transaction.commit();
    }

    /**
     * 显示远程查看界面
     */
    private void showRemoteViewInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示RemoteViewFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new RemoteViewFragment());
        transaction.commit();
    }

    /**
     * 显示软件设置界面
     */
    private void showSettingsInterface() {
        // 隐藏录制布局，显示Fragment容器
        recordingLayout.setVisibility(View.GONE);
        fragmentContainer.setVisibility(View.VISIBLE);

        // 显示SettingsFragment
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.replace(R.id.fragment_container, new SettingsFragment());
        transaction.commit();
    }


    private boolean checkPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                AppLog.d(TAG, "Missing permission: " + permission);
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        AppLog.d(TAG, "Requesting permissions...");
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkPermissions()) {
                // 权限已授予，但需要等待TextureView准备好
                // 如果TextureView已经准备好，立即初始化摄像头
                if (textureReadyCount >= requiredTextureCount) {
                    initCamera();
                }
            } else {
                Toast.makeText(this, "需要相机和存储权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initCamera() {
        // 确保所有需要的TextureView都准备好
        if (textureReadyCount < requiredTextureCount) {
            AppLog.w(TAG, "Not all TextureViews are ready yet: " + textureReadyCount + "/" + requiredTextureCount);
            return;
        }
        
        // 防止重复初始化：如果 cameraManager 已经存在，直接返回
        if (cameraManager != null) {
            AppLog.d(TAG, "Camera already initialized, skipping");
            return;
        }

        cameraManager = new MultiCameraManager(this);
        cameraManager.setMaxOpenCameras(configuredCameraCount);

        // 设置摄像头状态回调
        cameraManager.setStatusCallback((cameraId, status) -> {
            AppLog.d(TAG, "摄像头 " + cameraId + ": " + status);

            // 如果摄像头断开或被占用，提示用户
            if (status.contains("错误") || status.contains("断开")) {
                runOnUiThread(() -> {
                    if (status.contains("ERROR_CAMERA_IN_USE") || status.contains("DISCONNECTED")) {
                        Toast.makeText(MainActivity.this,
                            "摄像头 " + cameraId + " 被占用，正在自动重连...",
                            Toast.LENGTH_SHORT).show();
                    } else if (status.contains("max reconnect attempts")) {
                        Toast.makeText(MainActivity.this,
                            "摄像头 " + cameraId + " 重连失败，请手动重启应用",
                            Toast.LENGTH_LONG).show();
                    }
                });
            }
        });

        // 设置分段切换回调
        cameraManager.setSegmentSwitchCallback(newSegmentIndex -> {
            onSegmentSwitch(newSegmentIndex);
        });

        // 设置预览尺寸回调
        cameraManager.setPreviewSizeCallback((cameraKey, cameraId, previewSize) -> {
            AppLog.d(TAG, "摄像头 " + cameraId + " 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
            // 根据 camera key 设置对应 TextureView 的宽高比
            runOnUiThread(() -> {
                final AutoFitTextureView textureView;
                switch (cameraKey) {
                    case "front":
                        textureView = textureFront;
                        break;
                    case "back":
                        textureView = textureBack;
                        break;
                    case "left":
                        textureView = textureLeft;
                        break;
                    case "right":
                        textureView = textureRight;
                        break;
                    default:
                        textureView = null;
                        break;
                }
                if (textureView != null) {
                    // 判断是否需要旋转
                    boolean needRotation = "left".equals(cameraKey) || "right".equals(cameraKey);

                    if (needRotation) {
                        // 左右摄像头：容器使用旋转后的宽高比（800x1280，竖向）
                        textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
                        AppLog.d(TAG, "设置 " + cameraKey + " 宽高比(旋转后): " + previewSize.getHeight() + ":" + previewSize.getWidth());

                        // 应用旋转变换（修正倒立问题）
                        int rotation = "left".equals(cameraKey) ? 270 : 90;  // 左顺时针270度(270)，右顺时针90度(90)
                        applyRotationTransform(textureView, previewSize, rotation, cameraKey);
                    } else {
                        // 前后摄像头
                        String carModel = appConfig.getCarModel();
                        
                        // 手机车型：预览是竖向的，需要应用缩放变换保持比例
                        if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
                            // 手机竖屏模式：应用缩放变换保持宽高比
                            textureView.setFillContainer(false);
                            applyPhoneScaleTransform(textureView, previewSize, cameraKey);
                            AppLog.d(TAG, "设置 " + cameraKey + " 手机缩放变换, 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
                        } else {
                            // 其他车型：使用原始宽高比（1280x800，横向）
                            textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                            
                            // 根据车型和摄像头数量决定显示模式
                            // L7车型（包括L7-多按钮）和1摄/2摄模式：使用适应模式，完整显示画面
                            // E5的4摄模式：启用填满模式，避免黑边
                            boolean isL7Layout = AppConfig.CAR_MODEL_L7.equals(carModel) || AppConfig.CAR_MODEL_L7_MULTI.equals(carModel);
                            boolean useFillMode = configuredCameraCount >= 4 && !isL7Layout;
                            
                            if (useFillMode) {
                                // 4摄模式（E5）：启用填满模式，避免黑边
                                textureView.setFillContainer(true);
                                AppLog.d(TAG, "设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight() + ", 填满模式");
                            } else {
                                // L7车型或1摄/2摄模式：使用适应模式，完整显示画面
                                textureView.setFillContainer(false);
                                AppLog.d(TAG, "设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight() + ", 适应模式");
                            }
                        }
                    }
                }
            });
        });

        // 等待TextureView准备好
        textureFront.post(() -> {
            try {
                // 检测可用的摄像头
                CameraManager cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                String[] cameraIds = cm.getCameraIdList();

                AppLog.d(TAG, "========== 摄像头诊断信息 ==========");
                AppLog.d(TAG, "Available cameras: " + cameraIds.length);

                for (String id : cameraIds) {
                    AppLog.d(TAG, "---------- Camera ID: " + id + " ----------");

                    try {
                        android.hardware.camera2.CameraCharacteristics characteristics = cm.getCameraCharacteristics(id);

                        // 打印摄像头方向
                        Integer facing = characteristics.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING);
                        String facingStr = "UNKNOWN";
                        if (facing != null) {
                            switch (facing) {
                                case android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT:
                                    facingStr = "FRONT";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK:
                                    facingStr = "BACK";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.LENS_FACING_EXTERNAL:
                                    facingStr = "EXTERNAL";
                                    break;
                            }
                        }
                        AppLog.d(TAG, "  Facing: " + facingStr);

                        // 打印支持的输出格式和分辨率
                        android.hardware.camera2.params.StreamConfigurationMap map =
                            characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                        if (map != null) {
                            // 打印 ImageFormat.PRIVATE 的分辨率
                            android.util.Size[] privateSizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE);
                            if (privateSizes != null && privateSizes.length > 0) {
                                AppLog.d(TAG, "  PRIVATE formats (" + privateSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(privateSizes.length, 5); i++) {
                                    AppLog.d(TAG, "    [" + i + "] " + privateSizes[i].getWidth() + "x" + privateSizes[i].getHeight());
                                }
                                if (privateSizes.length > 5) {
                                    AppLog.d(TAG, "    ... and " + (privateSizes.length - 5) + " more");
                                }
                            }

                            // 打印 ImageFormat.YUV_420_888 的分辨率
                            android.util.Size[] yuvSizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                            if (yuvSizes != null && yuvSizes.length > 0) {
                                AppLog.d(TAG, "  YUV_420_888 formats (" + yuvSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(yuvSizes.length, 5); i++) {
                                    AppLog.d(TAG, "    [" + i + "] " + yuvSizes[i].getWidth() + "x" + yuvSizes[i].getHeight());
                                }
                                if (yuvSizes.length > 5) {
                                    AppLog.d(TAG, "    ... and " + (yuvSizes.length - 5) + " more");
                                }
                            }

                            // 打印 SurfaceTexture 的分辨率
                            android.util.Size[] textureSizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                            if (textureSizes != null && textureSizes.length > 0) {
                                AppLog.d(TAG, "  SurfaceTexture formats (" + textureSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(textureSizes.length, 5); i++) {
                                    AppLog.d(TAG, "    [" + i + "] " + textureSizes[i].getWidth() + "x" + textureSizes[i].getHeight());
                                }
                                if (textureSizes.length > 5) {
                                    AppLog.d(TAG, "    ... and " + (textureSizes.length - 5) + " more");
                                }
                            }
                        } else {
                            AppLog.w(TAG, "  StreamConfigurationMap is NULL!");
                        }

                        // 打印硬件级别
                        Integer hwLevel = characteristics.get(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
                        String hwLevelStr = "UNKNOWN";
                        if (hwLevel != null) {
                            switch (hwLevel) {
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                                    hwLevelStr = "LEGACY";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                                    hwLevelStr = "LIMITED";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                                    hwLevelStr = "FULL";
                                    break;
                                case android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                                    hwLevelStr = "LEVEL_3";
                                    break;
                            }
                        }
                        AppLog.d(TAG, "  Hardware Level: " + hwLevelStr);

                    } catch (Exception e) {
                        AppLog.e(TAG, "  Error getting characteristics for camera " + id + ": " + e.getMessage());
                    }
                }

                AppLog.d(TAG, "========================================");

                // 根据车型配置初始化摄像头
                String carModel = appConfig.getCarModel();
                if (AppConfig.CAR_MODEL_L7.equals(carModel) || AppConfig.CAR_MODEL_L7_MULTI.equals(carModel)) {
                    // 银河L6/L7 / L7-多按钮：使用固定映射
                    initCamerasForL7(cameraIds);
                } else if (appConfig.isCustomCarModel()) {
                    // 自定义车型：使用用户配置的摄像头映射
                    initCamerasForCustomModel(cameraIds);
                } else {
                    // 银河E5：使用固定映射
                    initCamerasForGalaxyE5(cameraIds);
                }
                
                // 根据设置决定录制模式（支持用户手动选择）
                boolean useCodecRecording = appConfig.shouldUseCodecRecording();
                cameraManager.setCodecRecordingMode(useCodecRecording);
                String recordingMode = appConfig.getRecordingMode();
                String modeDesc = useCodecRecording ? "OpenGL + MediaCodec" : "MediaRecorder";
                AppLog.d(TAG, "录制模式: " + modeDesc + " (设置: " + recordingMode + ")");

                // 打开所有摄像头
                cameraManager.openAllCameras();

                AppLog.d(TAG, "Camera initialized with " + configuredCameraCount + " cameras");
                Toast.makeText(this, "已打开 " + configuredCameraCount + " 个摄像头", Toast.LENGTH_SHORT).show();

            } catch (CameraAccessException e) {
                AppLog.e(TAG, "Failed to access camera", e);
                Toast.makeText(this, "摄像头访问失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }
    
    /**
     * 银河E5车型：使用固定的摄像头映射
     */
    private void initCamerasForGalaxyE5(String[] cameraIds) {
        if (cameraIds.length >= 4) {
            // 有4个或更多摄像头
            // 修正摄像头位置映射：前=cameraIds[2], 后=cameraIds[1], 左=cameraIds[3], 右=cameraIds[0]
            cameraManager.initCameras(
                    cameraIds[2], textureFront,  // 前摄像头使用 cameraIds[2]
                    cameraIds[1], textureBack,   // 后摄像头使用 cameraIds[1]
                    cameraIds[3], textureLeft,   // 左摄像头使用 cameraIds[3]
                    cameraIds[0], textureRight   // 右摄像头使用 cameraIds[0]
            );
        } else if (cameraIds.length >= 2) {
            // 只有2个摄像头，使用前两个位置
            cameraManager.initCameras(
                    cameraIds[0], textureLeft,
                    cameraIds[1], textureRight,
                    cameraIds[0], textureFront,
                    cameraIds[1], textureBack
            );
        } else if (cameraIds.length == 1) {
            // 只有1个摄像头，所有位置使用同一个
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[0], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[0], textureRight
            );
        } else {
            Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 银河L6/L7车型：使用固定的摄像头映射（竖屏四宫格）
     * 前=2, 后=3, 左=0, 右=1
     */
    private void initCamerasForL7(String[] cameraIds) {
        if (cameraIds.length >= 4) {
            // 有4个或更多摄像头
            cameraManager.initCameras(
                    cameraIds[2], textureFront,  // 前摄像头使用 cameraIds[2]
                    cameraIds[3], textureBack,   // 后摄像头使用 cameraIds[3]
                    cameraIds[0], textureLeft,   // 左摄像头使用 cameraIds[0]
                    cameraIds[1], textureRight   // 右摄像头使用 cameraIds[1]
            );
        } else if (cameraIds.length >= 2) {
            // 只有2个摄像头，复用到四个位置
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[1], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[1], textureRight
            );
        } else if (cameraIds.length == 1) {
            // 只有1个摄像头，所有位置使用同一个
            cameraManager.initCameras(
                    cameraIds[0], textureFront,
                    cameraIds[0], textureBack,
                    cameraIds[0], textureLeft,
                    cameraIds[0], textureRight
            );
        } else {
            Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
        }
    }
    
    /**
     * 自定义车型：使用用户配置的摄像头映射
     */
    private void initCamerasForCustomModel(String[] cameraIds) {
        // 获取用户配置的摄像头ID
        String frontId = appConfig.getCameraId("front");
        String backId = appConfig.getCameraId("back");
        String leftId = appConfig.getCameraId("left");
        String rightId = appConfig.getCameraId("right");
        
        AppLog.d(TAG, "自定义车型配置 - 摄像头数量: " + configuredCameraCount);
        AppLog.d(TAG, "  前: " + frontId + ", 后: " + backId + ", 左: " + leftId + ", 右: " + rightId);
        
        switch (configuredCameraCount) {
            case 1:
                // 1摄像头模式
                if (textureFront != null) {
                    cameraManager.initCameras(
                            frontId, textureFront,
                            null, null,
                            null, null,
                            null, null
                    );
                }
                break;
            case 2:
                // 2摄像头模式
                if (textureFront != null && textureBack != null) {
                    cameraManager.initCameras(
                            frontId, textureFront,
                            backId, textureBack,
                            null, null,
                            null, null
                    );
                }
                break;
            default:
                // 4摄像头模式
                if (textureFront != null && textureBack != null && textureLeft != null && textureRight != null) {
                    cameraManager.initCameras(
                            frontId, textureFront,
                            backId, textureBack,
                            leftId, textureLeft,
                            rightId, textureRight
                    );

                    // 设置自定义旋转角度（仅用于自定义车型）
                    setCustomRotationForCameras();
                }
                break;
        }
    }

    /**
     * 为自定义车型的摄像头设置旋转角度
     */
    private void setCustomRotationForCameras() {
        if (!appConfig.isCustomCarModel()) {
            return;  // 只对自定义车型应用
        }

        // 获取并设置每个摄像头的旋转角度
        int frontRotation = appConfig.getCameraRotation("front");
        int backRotation = appConfig.getCameraRotation("back");
        int leftRotation = appConfig.getCameraRotation("left");
        int rightRotation = appConfig.getCameraRotation("right");

        AppLog.d(TAG, "设置自定义旋转角度 - 前:" + frontRotation + "° 后:" + backRotation + "° 左:" + leftRotation + "° 右:" + rightRotation + "°");

        // 为每个摄像头设置旋转角度
        if (cameraManager != null) {
            SingleCamera frontCamera = cameraManager.getCamera("front");
            SingleCamera backCamera = cameraManager.getCamera("back");
            SingleCamera leftCamera = cameraManager.getCamera("left");
            SingleCamera rightCamera = cameraManager.getCamera("right");

            if (frontCamera != null) frontCamera.setCustomRotation(frontRotation);
            if (backCamera != null) backCamera.setCustomRotation(backRotation);
            if (leftCamera != null) leftCamera.setCustomRotation(leftRotation);
            if (rightCamera != null) rightCamera.setCustomRotation(rightRotation);
        }
    }

    /**
     * 对 TextureView 应用旋转变换 (修正版 - 解决变形问题)
     * @param textureView 要旋转的 TextureView
     * @param previewSize 预览尺寸（原始的 1280x800）
     * @param rotation 旋转角度（90 或 270）
     * @param cameraKey 摄像头标识
     */
    /**
     * 应用手机缩放变换，保持摄像头预览的宽高比不被拉伸
     */
    private void applyPhoneScaleTransform(AutoFitTextureView textureView, android.util.Size previewSize, String cameraKey) {
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();

            if (viewWidth == 0 || viewHeight == 0) {
                AppLog.d(TAG, cameraKey + " TextureView 尺寸为0，延迟应用缩放");
                textureView.postDelayed(() -> applyPhoneScaleTransform(textureView, previewSize, cameraKey), 100);
                return;
            }

            int previewWidth = previewSize.getWidth();
            int previewHeight = previewSize.getHeight();

            android.graphics.Matrix matrix = new android.graphics.Matrix();

            float centerX = viewWidth / 2f;
            float centerY = viewHeight / 2f;

            // 计算缩放比例，使用 FIT_CENTER 策略（保持比例，完整显示）
            float scaleX = (float) viewWidth / previewWidth;
            float scaleY = (float) viewHeight / previewHeight;
            float scale = Math.min(scaleX, scaleY);  // 取较小值，确保完整显示

            // 计算缩放后的尺寸
            float scaledWidth = previewWidth * scale;
            float scaledHeight = previewHeight * scale;

            // 计算偏移量，使内容居中
            float dx = (viewWidth - scaledWidth) / 2f;
            float dy = (viewHeight - scaledHeight) / 2f;

            // 设置变换矩阵：先缩放，再平移居中
            matrix.setScale(scale, scale);
            matrix.postTranslate(dx, dy);

            textureView.setTransform(matrix);
            AppLog.d(TAG, cameraKey + " 应用手机缩放变换: view=" + viewWidth + "x" + viewHeight + 
                    ", preview=" + previewWidth + "x" + previewHeight + 
                    ", scale=" + scale);
        });
    }

    private void applyRotationTransform(AutoFitTextureView textureView, android.util.Size previewSize,
                                        int rotation, String cameraKey) {
        // 延迟执行，确保 TextureView 已经完成布局
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();

            if (viewWidth == 0 || viewHeight == 0) {
                AppLog.d(TAG, cameraKey + " TextureView 尺寸为0，延迟应用旋转");
                // 如果视图还没有尺寸，再次延迟
                textureView.postDelayed(() -> applyRotationTransform(textureView, previewSize, rotation, cameraKey), 100);
                return;
            }

            android.graphics.Matrix matrix = new android.graphics.Matrix();
            android.graphics.RectF viewRect = new android.graphics.RectF(0, 0, viewWidth, viewHeight);
            
            // 缓冲区矩形，使用 float 精度
            android.graphics.RectF bufferRect = new android.graphics.RectF(0, 0, previewSize.getWidth(), previewSize.getHeight());

            float centerX = viewRect.centerX();
            float centerY = viewRect.centerY();

            if (rotation == 90 || rotation == 270) {
                // 1. 将 bufferRect 中心移动到 viewRect 中心
                bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
                
                // 2. 将 buffer 映射到 view，这一步会处理拉伸校正
                matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL);
                
                // 3. 计算缩放比例以填满屏幕 (Center Crop)
                // 因为旋转了 90 度，所以 viewHeight 对应 previewWidth，viewWidth 对应 previewHeight
                float scale = Math.max(
                        (float) viewHeight / previewSize.getWidth(),
                        (float) viewWidth / previewSize.getHeight());
                
                // 4. 应用缩放
                matrix.postScale(scale, scale, centerX, centerY);
                
                // 5. 应用旋转
                matrix.postRotate(rotation, centerX, centerY);
            } else if (android.view.Surface.ROTATION_180 == rotation) {
                // 如果需要处理 180 度翻转
                matrix.postRotate(180, centerX, centerY);
            }

            textureView.setTransform(matrix);
            AppLog.d(TAG, cameraKey + " 应用修正旋转: " + rotation + "度");
        });
    }

    /**
     * 切换录制状态（开始/停止）
     */
    private void toggleRecording() {
        // 防双击保护
        long currentTime = System.currentTimeMillis();
        if (currentTime - lastRecordButtonClickTime < RECORD_BUTTON_CLICK_INTERVAL) {
            AppLog.d(TAG, "录制按钮点击过快，忽略（间隔: " + (currentTime - lastRecordButtonClickTime) + "ms）");
            return;
        }
        lastRecordButtonClickTime = currentTime;
        
        if (isRecording) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void startRecording() {
        if (cameraManager != null && !cameraManager.isRecording()) {
            boolean success = cameraManager.startRecording();
            if (success) {
                isRecording = true;

                // 启动前台服务保护（防止后台录制被中断）
                CameraForegroundService.start(this, "正在录制视频", "录制进行中，点击返回应用");

                // 开始闪烁动画
                startBlinkAnimation();
                
                // 启动录制计时器
                startRecordingTimer();

                // 发送录制状态广播（通知悬浮窗）
                FloatingWindowService.sendRecordingStateChanged(this, true);

                Toast.makeText(this, "开始录制（每1分钟自动分段）", Toast.LENGTH_SHORT).show();
                AppLog.d(TAG, "Recording started with foreground service protection");
            } else {
                Toast.makeText(this, "录制失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopRecording() {
        if (cameraManager != null) {
            cameraManager.stopRecording();
            isRecording = false;

            // 停止前台服务
            CameraForegroundService.stop(this);

            // 停止闪烁动画，恢复红色
            stopBlinkAnimation();
            
            // 停止录制计时器
            stopRecordingTimer();

            // 发送录制状态广播（通知悬浮窗）
            FloatingWindowService.sendRecordingStateChanged(this, false);

            Toast.makeText(this, "录制已停止", Toast.LENGTH_SHORT).show();
            AppLog.d(TAG, "Recording stopped, foreground service stopped");
        }
    }

    /**
     * 完全退出应用（包括后台进程）
     */
    private void exitApp() {
        // 停止录制（如果正在录制）
        if (isRecording) {
            stopRecording();
        }

        // 停止前台服务（确保清理）
        CameraForegroundService.stop(this);

        // 停止钉钉服务
        if (dingTalkStreamManager != null) {
            dingTalkStreamManager.stop();
        }

        // 释放摄像头资源
        if (cameraManager != null) {
            cameraManager.release();
        }

        // 结束所有Activity并退出应用
        finishAffinity();

        // 完全退出进程
        System.exit(0);
    }

    private void startBlinkAnimation() {
        if (blinkHandler == null) {
            blinkHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        }

        isBlinking = true;
        blinkRunnable = new Runnable() {
            @Override
            public void run() {
                if (isBlinking) {
                    // 切换颜色：绿色和深绿色交替
                    int currentColor = btnStartRecord.getTextColors().getDefaultColor();
                    if (currentColor == 0xFF00FF00) {  // 亮绿色
                        btnStartRecord.setTextColor(0xFF006400);  // 深绿色
                    } else {
                        btnStartRecord.setTextColor(0xFF00FF00);  // 亮绿色
                    }
                    blinkHandler.postDelayed(this, 1000);  // 每500ms闪烁一次
                }
            }
        };

        // 初始设置为绿色
        btnStartRecord.setTextColor(0xFF00FF00);
        blinkHandler.post(blinkRunnable);
    }

    private void stopBlinkAnimation() {
        isBlinking = false;
        if (blinkHandler != null && blinkRunnable != null) {
            blinkHandler.removeCallbacks(blinkRunnable);
        }
        // 恢复红色
        btnStartRecord.setTextColor(0xFFFF0000);
    }

    private void takePicture() {
        if (cameraManager != null) {
            cameraManager.takePicture();
            Toast.makeText(this, "拍照完成", Toast.LENGTH_SHORT).show();
            AppLog.d(TAG, "Picture taken");
        }
    }

    /**
     * 远程录制（由钉钉指令触发）
     * 自动录制指定时长视频并上传到钉钉
     */
    public void startRemoteRecording(String conversationId, String conversationType, String userId, int durationSeconds) {
        this.remoteConversationId = conversationId;
        this.remoteConversationType = conversationType;
        this.remoteUserId = userId;

        AppLog.d(TAG, "收到远程录制指令，开始录制 " + durationSeconds + " 秒视频...");

        // 第一步：检查摄像头管理器是否初始化
        if (cameraManager == null) {
            AppLog.e(TAG, "摄像头管理器未初始化");
            sendErrorToRemote("摄像头未初始化");
            returnToBackgroundIfRemoteWakeUp();
            return;
        }

        // 第二步：检查是否有已连接的摄像头
        if (!cameraManager.hasConnectedCameras()) {
            AppLog.e(TAG, "没有可用的相机");
            sendErrorToRemote("没有可用的相机（可能在后台被限制）");
            returnToBackgroundIfRemoteWakeUp();
            return;
        }

        // 第三步：生成统一的时间戳（用于文件命名和后续查找）
        remoteRecordingTimestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        AppLog.d(TAG, "录制统一时间戳: " + remoteRecordingTimestamp);

        // 第四步：如果正在录制，先停止
        if (cameraManager.isRecording()) {
            cameraManager.stopRecording();
            try {
                Thread.sleep(500);  // 等待停止完成
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // 第五步：开始录制（使用统一时间戳）
        boolean success = cameraManager.startRecording(remoteRecordingTimestamp);
        if (success) {
            AppLog.d(TAG, "远程录制已开始");

            // 启动前台服务保护（防止后台录制被中断）
            CameraForegroundService.start(this, "远程录制进行中", "正在录制 " + durationSeconds + " 秒视频...");

            // 发送录制状态广播（通知悬浮窗）
            FloatingWindowService.sendRecordingStateChanged(this, true);

            // 设置指定时长后自动停止
            autoStopRunnable = () -> {
                AppLog.d(TAG, durationSeconds + " 秒录制完成，正在停止...");
                cameraManager.stopRecording();

                // 停止前台服务
                CameraForegroundService.stop(this);

                // 发送录制状态广播（通知悬浮窗）
                FloatingWindowService.sendRecordingStateChanged(this, false);

                // 等待录制完全停止
                new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                    uploadRecordedVideos();
                }, 1000);
            };

            autoStopHandler.postDelayed(autoStopRunnable, durationSeconds * 1000L);  // 转换为毫秒
        } else {
            AppLog.e(TAG, "远程录制启动失败");
            sendErrorToRemote("录制启动失败");
            returnToBackgroundIfRemoteWakeUp();
        }
    }

    /**
     * 远程拍照（由钉钉指令触发）
     * 拍摄照片并上传到钉钉
     */
    public void startRemotePhoto(String conversationId, String conversationType, String userId) {
        this.remoteConversationId = conversationId;
        this.remoteConversationType = conversationType;
        this.remoteUserId = userId;

        AppLog.d(TAG, "收到远程拍照指令，开始拍照...");

        // 第一步：检查摄像头管理器是否初始化
        if (cameraManager == null) {
            AppLog.e(TAG, "摄像头管理器未初始化");
            sendErrorToRemote("摄像头未初始化");
            returnToBackgroundIfRemoteWakeUp();
            return;
        }

        // 第二步：检查是否有已连接的摄像头
        if (!cameraManager.hasConnectedCameras()) {
            AppLog.e(TAG, "没有可用的相机");
            sendErrorToRemote("没有可用的相机（可能在后台被限制）");
            returnToBackgroundIfRemoteWakeUp();
            return;
        }

        // 第三步：生成统一的时间戳（用于文件命名和后续查找）
        remoteRecordingTimestamp = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.getDefault())
                .format(new java.util.Date());
        AppLog.d(TAG, "拍照统一时间戳: " + remoteRecordingTimestamp);

        // 第四步：执行拍照（传递统一时间戳）
        cameraManager.takePicture(remoteRecordingTimestamp);
        AppLog.d(TAG, "远程拍照已执行（拍照间隔300ms，保存间隔1s）");

        // 等待所有摄像头拍照完成后上传
        // 时间线：拍照(0-0.9s) + 最后保存延迟(3s) + 保存时间(1s) = 5s
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            uploadPhotos();
        }, 5000);  // 等待5秒确保所有照片保存完成
    }

    /**
     * 上传录制的视频到钉钉
     */
    private void uploadRecordedVideos() {
        AppLog.d(TAG, "开始上传视频到钉钉...");

        // 检查时间戳
        if (remoteRecordingTimestamp == null || remoteRecordingTimestamp.isEmpty()) {
            AppLog.e(TAG, "录制时间戳为空，无法查找视频文件");
            sendErrorToRemote("录制失败：时间戳丢失");
            returnToBackgroundIfRemoteWakeUp();
            return;
        }

        // 获取录制的视频文件
        File videoDir = StorageHelper.getVideoDir(this);

        if (!videoDir.exists() || !videoDir.isDirectory()) {
            AppLog.e(TAG, "视频目录不存在");
            sendErrorToRemote("视频目录不存在");
            returnToBackgroundIfRemoteWakeUp();
            return;
        }

        // 直接过滤：只获取本次录制的视频文件（文件名格式: 20260124_235933_front.mp4）
        File[] files = videoDir.listFiles((dir, name) -> 
            name.endsWith(".mp4") && name.startsWith(remoteRecordingTimestamp + "_")
        );

        if (files == null || files.length == 0) {
            AppLog.e(TAG, "没有找到时间戳为 " + remoteRecordingTimestamp + " 的视频文件（录制可能失败）");
            sendErrorToRemote("录制失败：未生成视频文件");
            returnToBackgroundIfRemoteWakeUp();
            return;
        }

        // 转换为 List 并记录日志
        List<File> recentFiles = new ArrayList<>(Arrays.asList(files));
        AppLog.d(TAG, "找到 " + recentFiles.size() + " 个时间戳为 " + remoteRecordingTimestamp + " 的视频文件");
        for (File file : recentFiles) {
            AppLog.d(TAG, "  - " + file.getName());
        }

        // 使用 Activity 级别的 API 客户端
        if (dingTalkApiClient != null && remoteConversationId != null) {
            VideoUploadService uploadService = new VideoUploadService(this, dingTalkApiClient);
            uploadService.uploadVideos(recentFiles, remoteConversationId, remoteConversationType, remoteUserId, new VideoUploadService.UploadCallback() {
                @Override
                public void onProgress(String message) {
                    AppLog.d(TAG, message);
                }

                @Override
                public void onSuccess(String message) {
                    AppLog.d(TAG, message);
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "视频上传成功", Toast.LENGTH_SHORT).show();
                        // 上传完成后自动退回后台
                        returnToBackgroundIfRemoteWakeUp();
                    });
                }

                @Override
                public void onError(String error) {
                    AppLog.e(TAG, "上传失败: " + error);
                    sendErrorToRemote("上传失败: " + error);
                    // 即使失败也退回后台
                    runOnUiThread(() -> returnToBackgroundIfRemoteWakeUp());
                }
            });
        } else {
            AppLog.e(TAG, "钉钉服务未启动");
            sendErrorToRemote("钉钉服务未启动");
            returnToBackgroundIfRemoteWakeUp();
        }
    }

    /**
     * 上传拍摄的照片到钉钉
     */
    private void uploadPhotos() {
        AppLog.d(TAG, "开始上传照片到钉钉...");

        // 检查时间戳
        if (remoteRecordingTimestamp == null || remoteRecordingTimestamp.isEmpty()) {
            AppLog.e(TAG, "拍照时间戳为空，无法查找照片文件");
            sendErrorToRemote("拍照失败：时间戳丢失");
            returnToBackgroundIfRemoteWakeUp();
            return;
        }

        // 获取照片文件
        File photoDir = StorageHelper.getPhotoDir(this);

        if (!photoDir.exists() || !photoDir.isDirectory()) {
            AppLog.e(TAG, "照片目录不存在");
            sendErrorToRemote("照片目录不存在");
            returnToBackgroundIfRemoteWakeUp();
            return;
        }

        // 直接过滤：只获取本次拍摄的照片文件（精确匹配时间戳，秒级）
        // 文件名格式: 20260124_235933_front.jpg
        File[] files = photoDir.listFiles((dir, name) -> 
            name.endsWith(".jpg") && name.startsWith(remoteRecordingTimestamp + "_")
        );

        if (files == null || files.length == 0) {
            AppLog.e(TAG, "没有找到时间戳为 " + remoteRecordingTimestamp + " 的照片文件（拍照可能失败）");
            sendErrorToRemote("拍照失败：未生成照片文件");
            returnToBackgroundIfRemoteWakeUp();
            return;
        }

        // 转换为 List 并记录日志
        List<File> recentFiles = new ArrayList<>(Arrays.asList(files));
        AppLog.d(TAG, "找到 " + recentFiles.size() + " 张时间戳为 " + remoteRecordingTimestamp + " 的照片");
        for (File file : recentFiles) {
            AppLog.d(TAG, "  - " + file.getName());
        }

        // 使用 Activity 级别的 API 客户端
        if (dingTalkApiClient != null && remoteConversationId != null) {
            PhotoUploadService uploadService = new PhotoUploadService(this, dingTalkApiClient);
            uploadService.uploadPhotos(recentFiles, remoteConversationId, remoteConversationType, remoteUserId, new PhotoUploadService.UploadCallback() {
                @Override
                public void onProgress(String message) {
                    AppLog.d(TAG, message);
                }

                @Override
                public void onSuccess(String message) {
                    AppLog.d(TAG, message);
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "照片上传成功", Toast.LENGTH_SHORT).show();
                        // 上传完成后自动退回后台
                        returnToBackgroundIfRemoteWakeUp();
                    });
                }

                @Override
                public void onError(String error) {
                    AppLog.e(TAG, "上传失败: " + error);
                    sendErrorToRemote("上传失败: " + error);
                    // 即使失败也退回后台
                    runOnUiThread(() -> returnToBackgroundIfRemoteWakeUp());
                }
            });
        } else {
            AppLog.e(TAG, "钉钉服务未启动");
            sendErrorToRemote("钉钉服务未启动");
            returnToBackgroundIfRemoteWakeUp();
        }
    }

    /**
     * 发送错误消息到钉钉
     */
    private void sendErrorToRemote(String error) {
        if (remoteConversationId == null) {
            return;
        }

        if (dingTalkApiClient != null) {
            new Thread(() -> {
                try {
                    // 延迟1秒发送错误消息，确保确认消息（Webhook）先到达钉钉并被用户看到
                    // 原因：虽然现在命令已在确认消息发送后执行，但仍需考虑网络延迟和服务器处理时间
                    AppLog.d(TAG, "错误消息将在1秒后发送，确保确认消息先到达...");
                    Thread.sleep(1000);
                    
                    dingTalkApiClient.sendTextMessage(remoteConversationId, remoteConversationType, "录制失败: " + error, remoteUserId);
                    AppLog.d(TAG, "错误消息已发送到钉钉");
                } catch (InterruptedException e) {
                    AppLog.w(TAG, "错误消息延迟被中断");
                } catch (Exception e) {
                    AppLog.e(TAG, "发送错误消息失败", e);
                }
            }).start();
        }
    }

    /**
     * 如果是远程唤醒的，完成后自动退回后台
     * 延迟2秒后执行，让用户看到上传成功的提示
     */
    private void returnToBackgroundIfRemoteWakeUp() {
        if (!isRemoteWakeUp) {
            AppLog.d(TAG, "Not a remote wake-up, staying in foreground");
            return;
        }

        AppLog.d(TAG, "Remote command completed, will return to background in 2 seconds");

        // 延迟2秒后退回后台，让用户看到 Toast 提示
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
            // 重置标记
            isRemoteWakeUp = false;

            // 释放唤醒锁，让屏幕可以自然熄灭
            WakeUpHelper.releaseWakeLock();

            // 将应用退到后台
            AppLog.d(TAG, "Moving task to back...");
            moveTaskToBack(true);

            AppLog.d(TAG, "Returned to background successfully");
        }, 2000);
    }

    /**
     * 启动钉钉服务
     */
    public void startDingTalkService() {
        if (!dingTalkConfig.isConfigured()) {
            Toast.makeText(this, "请先配置钉钉参数", Toast.LENGTH_SHORT).show();
            return;
        }

        if (dingTalkStreamManager != null && dingTalkStreamManager.isRunning()) {
            AppLog.d(TAG, "钉钉服务已在运行");
            return;
        }

        AppLog.d(TAG, "正在启动钉钉服务...");

        // 创建 API 客户端
        dingTalkApiClient = new DingTalkApiClient(dingTalkConfig);

        // 创建连接回调
        DingTalkStreamManager.ConnectionCallback connectionCallback = new DingTalkStreamManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    AppLog.d(TAG, "钉钉服务已连接");
                    Toast.makeText(MainActivity.this, "钉钉服务已启动", Toast.LENGTH_SHORT).show();
                    // 通知 RemoteViewFragment 更新 UI
                    updateRemoteViewFragmentUI();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    AppLog.d(TAG, "钉钉服务已断开");
                    // 通知 RemoteViewFragment 更新 UI
                    updateRemoteViewFragmentUI();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    AppLog.e(TAG, "钉钉服务连接失败: " + error);
                    Toast.makeText(MainActivity.this, "连接失败: " + error, Toast.LENGTH_LONG).show();
                    // 通知 RemoteViewFragment 更新 UI
                    updateRemoteViewFragmentUI();
                });
            }
        };

        // 创建指令回调
        DingTalkStreamManager.CommandCallback commandCallback = new DingTalkStreamManager.CommandCallback() {
            @Override
            public void onRecordCommand(String conversationId, String conversationType, String userId, int durationSeconds) {
                startRemoteRecording(conversationId, conversationType, userId, durationSeconds);
            }

            @Override
            public void onPhotoCommand(String conversationId, String conversationType, String userId) {
                startRemotePhoto(conversationId, conversationType, userId);
            }
        };

        // 创建并启动 Stream 管理器（启用自动重连）
        dingTalkStreamManager = new DingTalkStreamManager(this, dingTalkConfig, dingTalkApiClient, connectionCallback);
        dingTalkStreamManager.start(commandCallback, true); // 启用自动重连
    }

    /**
     * 停止钉钉服务
     */
    public void stopDingTalkService() {
        if (dingTalkStreamManager != null) {
            AppLog.d(TAG, "正在停止钉钉服务...");
            dingTalkStreamManager.stop();
            dingTalkStreamManager = null;
            dingTalkApiClient = null;
            Toast.makeText(this, "钉钉服务已停止", Toast.LENGTH_SHORT).show();
            // 通知 RemoteViewFragment 更新 UI
            updateRemoteViewFragmentUI();
        }
    }

    /**
     * 获取钉钉服务运行状态
     */
    public boolean isDingTalkServiceRunning() {
        return dingTalkStreamManager != null && dingTalkStreamManager.isRunning();
    }

    /**
     * 获取钉钉 API 客户端
     */
    public DingTalkApiClient getDingTalkApiClient() {
        return dingTalkApiClient;
    }

    /**
     * 获取钉钉配置
     */
    public DingTalkConfig getDingTalkConfig() {
        return dingTalkConfig;
    }

    /**
     * 获取当前录制状态（供外部查询）
     */
    public boolean isCurrentlyRecording() {
        return isRecording;
    }

    /**
     * 发送当前录制状态广播（供悬浮窗服务查询）
     */
    public void broadcastCurrentRecordingState() {
        FloatingWindowService.sendRecordingStateChanged(this, isRecording);
    }
    
    /**
     * 重启存储清理任务（配置更改后调用）
     */
    public void restartStorageCleanupTask() {
        if (storageCleanupManager != null) {
            storageCleanupManager.stop();
        }
        storageCleanupManager = new StorageCleanupManager(this);
        storageCleanupManager.start();
        AppLog.d(TAG, "存储清理任务已重启");
    }

    /**
     * 通知 RemoteViewFragment 更新 UI
     */
    private void updateRemoteViewFragmentUI() {
        FragmentManager fragmentManager = getSupportFragmentManager();
        Fragment fragment = fragmentManager.findFragmentById(R.id.fragment_container);
        if (fragment instanceof RemoteViewFragment) {
            ((RemoteViewFragment) fragment).updateServiceStatus();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        isInBackground = true;
        AppLog.d(TAG, "onPause called, isRecording=" + isRecording);
        
        // 通知悬浮窗服务：应用进入后台，显示悬浮窗
        if (appConfig.isFloatingWindowEnabled()) {
            FloatingWindowService.sendAppForegroundState(this, false);
        }
        
        // 根据是否正在录制，决定如何处理摄像头
        if (cameraManager != null) {
            if (isRecording) {
                // 正在录制：保持摄像头连接（有前台服务保护）
                AppLog.d(TAG, "Recording in progress, keeping cameras connected (protected by foreground service)");
            } else {
                // 未录制：主动断开摄像头，避免后台拍照黑屏问题
                AppLog.d(TAG, "Not recording, closing all cameras to avoid background issues");
                cameraManager.closeAllCameras();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean wasInBackground = isInBackground;
        isInBackground = false;
        AppLog.d(TAG, "onResume called, wasInBackground=" + wasInBackground + ", isRecording=" + isRecording);
        
        // 通知悬浮窗服务：应用进入前台，隐藏悬浮窗
        if (appConfig.isFloatingWindowEnabled()) {
            FloatingWindowService.sendAppForegroundState(this, true);
        }
        
        // 返回前台时，检查摄像头连接状态
        if (cameraManager != null && wasInBackground) {
            // 延迟500ms后重新打开摄像头
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                // 只在没有正在录制时重新打开（录制时摄像头应该保持连接）
                if (!isRecording) {
                    AppLog.d(TAG, "Reopening cameras after returning from background");
                    cameraManager.openAllCameras();
                    
                    // 检查是否有待处理的远程命令
                    if (pendingRemoteCommand) {
                        AppLog.d(TAG, "Has pending remote command, will execute after cameras ready");
                        // 等待摄像头准备好后执行命令（在 handleRemoteCommand 中处理）
                    }
                } else {
                    AppLog.d(TAG, "Recording in progress, cameras should still be connected");
                }
            }, 500);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // 取消自动停止录制的任务
        if (autoStopHandler != null && autoStopRunnable != null) {
            autoStopHandler.removeCallbacks(autoStopRunnable);
        }

        // 停止前台服务（确保清理）
        CameraForegroundService.stop(this);

        // 停止钉钉服务
        if (dingTalkStreamManager != null) {
            dingTalkStreamManager.stop();
        }
        
        // 停止存储清理任务
        if (storageCleanupManager != null) {
            storageCleanupManager.stop();
        }

        if (cameraManager != null) {
            cameraManager.release();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
            // 如果 Fragment 返回栈不为空（在二级菜单中），则返回上一级
            getSupportFragmentManager().popBackStack();
            AppLog.d(TAG, "Popped fragment back stack, returning to previous screen");
        } else {
            // 按返回键时将应用移到后台，而不是关闭Activity
            // 这样下次打开应用时能快速恢复，无需重新创建Activity
            moveTaskToBack(true);
            AppLog.d(TAG, "Moved to background via back button");
        }
     }
}