package com.kooo.evcam;

import android.Manifest;
import android.content.Context;
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
    private Button btnStartRecord, btnStopRecord, btnTakePhoto;
    private MultiCameraManager cameraManager;
    private int textureReadyCount = 0;  // 记录准备好的TextureView数量

    // 录制按钮闪烁动画相关
    private android.os.Handler blinkHandler;
    private Runnable blinkRunnable;
    private boolean isBlinking = false;

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

    // 钉钉服务相关（移到 Activity 级别）
    private DingTalkConfig dingTalkConfig;
    private DingTalkApiClient dingTalkApiClient;
    private DingTalkStreamManager dingTalkStreamManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置字体缩放比例（1.3倍）
        adjustFontScale(1.2f);

        setContentView(R.layout.activity_main);

        // 设置状态栏沉浸式
        setupStatusBar();

        initViews();
        setupNavigationDrawer();

        // 初始化钉钉配置
        dingTalkConfig = new DingTalkConfig(this);

        // 初始化自动停止 Handler
        autoStopHandler = new android.os.Handler(android.os.Looper.getMainLooper());

        // 权限检查，但不立即初始化摄像头
        // 等待TextureView准备好后再初始化
        if (!checkPermissions()) {
            requestPermissions();
        }

        // 如果启用了自动启动，启动钉钉服务
        if (dingTalkConfig.isConfigured() && dingTalkConfig.isAutoStart()) {
            startDingTalkService();
        }
    }

    private void adjustFontScale(float scale) {
        android.content.res.Configuration configuration = getResources().getConfiguration();
        configuration.fontScale = scale;
        android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        getBaseContext().getResources().updateConfiguration(configuration, metrics);
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

        textureFront = findViewById(R.id.texture_front);
        textureBack = findViewById(R.id.texture_back);
        textureLeft = findViewById(R.id.texture_left);
        textureRight = findViewById(R.id.texture_right);
        btnStartRecord = findViewById(R.id.btn_start_record);
        btnStopRecord = findViewById(R.id.btn_stop_record);
        btnTakePhoto = findViewById(R.id.btn_take_photo);

        // 菜单按钮点击事件
        findViewById(R.id.btn_menu).setOnClickListener(v -> {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START);
            } else {
                drawerLayout.openDrawer(GravityCompat.START);
            }
        });

        btnStartRecord.setOnClickListener(v -> startRecording());
        btnStopRecord.setOnClickListener(v -> stopRecording());
        btnTakePhoto.setOnClickListener(v -> takePicture());

        // 为每个TextureView添加Surface监听器
        TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                textureReadyCount++;
                Log.d(TAG, "TextureView ready: " + textureReadyCount + "/4");

                // 当所有TextureView都准备好后，初始化摄像头
                if (textureReadyCount == 4 && checkPermissions()) {
                    initCamera();
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(@NonNull android.graphics.SurfaceTexture surface, int width, int height) {
                Log.d(TAG, "TextureView size changed: " + width + "x" + height);
            }

            @Override
            public boolean onSurfaceTextureDestroyed(@NonNull android.graphics.SurfaceTexture surface) {
                textureReadyCount--;
                Log.d(TAG, "TextureView destroyed, remaining: " + textureReadyCount);
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(@NonNull android.graphics.SurfaceTexture surface) {
                // 不需要处理每帧更新
            }
        };

        textureFront.setSurfaceTextureListener(surfaceTextureListener);
        textureBack.setSurfaceTextureListener(surfaceTextureListener);
        textureLeft.setSurfaceTextureListener(surfaceTextureListener);
        textureRight.setSurfaceTextureListener(surfaceTextureListener);
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
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return true;
        });

        // 默认选中录制界面
        navigationView.setCheckedItem(R.id.nav_recording);
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


    private boolean checkPermissions() {
        for (String permission : getRequiredPermissions()) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                Log.d(TAG, "Missing permission: " + permission);
                return false;
            }
        }
        return true;
    }

    private void requestPermissions() {
        Log.d(TAG, "Requesting permissions...");
        ActivityCompat.requestPermissions(this, getRequiredPermissions(), REQUEST_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_PERMISSIONS) {
            if (checkPermissions()) {
                // 权限已授予，但需要等待TextureView准备好
                // 如果TextureView已经准备好，立即初始化摄像头
                if (textureReadyCount == 4) {
                    initCamera();
                }
            } else {
                Toast.makeText(this, "需要相机和存储权限", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }

    private void initCamera() {
        // 确保所有TextureView都准备好
        if (textureReadyCount < 4) {
            Log.w(TAG, "Not all TextureViews are ready yet: " + textureReadyCount + "/4");
            return;
        }

        cameraManager = new MultiCameraManager(this);
        cameraManager.setMaxOpenCameras(4);

        // 设置摄像头状态回调
        cameraManager.setStatusCallback((cameraId, status) -> {
            Log.d(TAG, "摄像头 " + cameraId + ": " + status);

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

        // 设置预览尺寸回调
        cameraManager.setPreviewSizeCallback((cameraKey, cameraId, previewSize) -> {
            Log.d(TAG, "摄像头 " + cameraId + " 预览尺寸: " + previewSize.getWidth() + "x" + previewSize.getHeight());
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
                        Log.d(TAG, "设置 " + cameraKey + " 宽高比(旋转后): " + previewSize.getHeight() + ":" + previewSize.getWidth());

                        // 应用旋转变换（修正倒立问题）
                        int rotation = "left".equals(cameraKey) ? 270 : 90;  // 左顺时针270度(270)，右顺时针90度(90)
                        applyRotationTransform(textureView, previewSize, rotation, cameraKey);
                    } else {
                        // 前后摄像头：使用原始宽高比（1280x800，横向）
                        textureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
                        // 启用填满模式，避免黑边
                        textureView.setFillContainer(true);
                        Log.d(TAG, "设置 " + cameraKey + " 宽高比: " + previewSize.getWidth() + ":" + previewSize.getHeight() + ", 填满模式");
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

                Log.d(TAG, "========== 摄像头诊断信息 ==========");
                Log.d(TAG, "Available cameras: " + cameraIds.length);

                for (String id : cameraIds) {
                    Log.d(TAG, "---------- Camera ID: " + id + " ----------");

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
                        Log.d(TAG, "  Facing: " + facingStr);

                        // 打印支持的输出格式和分辨率
                        android.hardware.camera2.params.StreamConfigurationMap map =
                            characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

                        if (map != null) {
                            // 打印 ImageFormat.PRIVATE 的分辨率
                            android.util.Size[] privateSizes = map.getOutputSizes(android.graphics.ImageFormat.PRIVATE);
                            if (privateSizes != null && privateSizes.length > 0) {
                                Log.d(TAG, "  PRIVATE formats (" + privateSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(privateSizes.length, 5); i++) {
                                    Log.d(TAG, "    [" + i + "] " + privateSizes[i].getWidth() + "x" + privateSizes[i].getHeight());
                                }
                                if (privateSizes.length > 5) {
                                    Log.d(TAG, "    ... and " + (privateSizes.length - 5) + " more");
                                }
                            }

                            // 打印 ImageFormat.YUV_420_888 的分辨率
                            android.util.Size[] yuvSizes = map.getOutputSizes(android.graphics.ImageFormat.YUV_420_888);
                            if (yuvSizes != null && yuvSizes.length > 0) {
                                Log.d(TAG, "  YUV_420_888 formats (" + yuvSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(yuvSizes.length, 5); i++) {
                                    Log.d(TAG, "    [" + i + "] " + yuvSizes[i].getWidth() + "x" + yuvSizes[i].getHeight());
                                }
                                if (yuvSizes.length > 5) {
                                    Log.d(TAG, "    ... and " + (yuvSizes.length - 5) + " more");
                                }
                            }

                            // 打印 SurfaceTexture 的分辨率
                            android.util.Size[] textureSizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                            if (textureSizes != null && textureSizes.length > 0) {
                                Log.d(TAG, "  SurfaceTexture formats (" + textureSizes.length + " sizes):");
                                for (int i = 0; i < Math.min(textureSizes.length, 5); i++) {
                                    Log.d(TAG, "    [" + i + "] " + textureSizes[i].getWidth() + "x" + textureSizes[i].getHeight());
                                }
                                if (textureSizes.length > 5) {
                                    Log.d(TAG, "    ... and " + (textureSizes.length - 5) + " more");
                                }
                            }
                        } else {
                            Log.w(TAG, "  StreamConfigurationMap is NULL!");
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
                        Log.d(TAG, "  Hardware Level: " + hwLevelStr);

                    } catch (Exception e) {
                        Log.e(TAG, "  Error getting characteristics for camera " + id + ": " + e.getMessage());
                    }
                }

                Log.d(TAG, "========================================");

                // 根据可用摄像头数量初始化
                if (cameraIds.length >= 4) {
                    // 有4个或更多摄像头
                    // 修正摄像头位置映射：前=cameraIds[2], 后=cameraIds[1], 左=cameraIds[3], 右=cameraIds[0]
                    cameraManager.initCameras(
                            cameraIds[2], textureFront,  // 前摄像头使用 cameraIds[2]
                            cameraIds[1], textureBack,   // 后摄像头使用 cameraIds[1]
                            cameraIds[3], textureLeft,   // 左摄像头使用 cameraIds[3]（修正）
                            cameraIds[0], textureRight   // 右摄像头使用 cameraIds[0]（修正）
                    );
                } else if (cameraIds.length >= 2) {
                    // 只有2个摄像头，使用前两个位置
                    cameraManager.initCameras(
                            cameraIds[0], textureLeft,  // 复用第一个
                            cameraIds[1], textureRight,  // 复用第二个
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
                    return;
                }

                // 打开所有摄像头
                cameraManager.openAllCameras();

                Log.d(TAG, "Camera initialized with " + cameraIds.length + " cameras");
                Toast.makeText(this, "已打开 " + cameraIds.length + " 个摄像头", Toast.LENGTH_SHORT).show();

            } catch (CameraAccessException e) {
                Log.e(TAG, "Failed to access camera", e);
                Toast.makeText(this, "摄像头访问失败: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * 对 TextureView 应用旋转变换 (修正版 - 解决变形问题)
     * @param textureView 要旋转的 TextureView
     * @param previewSize 预览尺寸（原始的 1280x800）
     * @param rotation 旋转角度（90 或 270）
     * @param cameraKey 摄像头标识
     */
    private void applyRotationTransform(AutoFitTextureView textureView, android.util.Size previewSize,
                                        int rotation, String cameraKey) {
        // 延迟执行，确保 TextureView 已经完成布局
        textureView.post(() -> {
            int viewWidth = textureView.getWidth();
            int viewHeight = textureView.getHeight();

            if (viewWidth == 0 || viewHeight == 0) {
                Log.d(TAG, cameraKey + " TextureView 尺寸为0，延迟应用旋转");
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
            Log.d(TAG, cameraKey + " 应用修正旋转: " + rotation + "度");
        });
    }

    private void startRecording() {
        if (cameraManager != null && !cameraManager.isRecording()) {
            boolean success = cameraManager.startRecording();
            if (success) {
                btnStartRecord.setEnabled(false);
                btnStopRecord.setEnabled(true);

                // 开始闪烁动画
                startBlinkAnimation();

                Toast.makeText(this, "开始录制（每1分钟自动分段）", Toast.LENGTH_SHORT).show();
                Log.d(TAG, "Recording started");
            } else {
                Toast.makeText(this, "录制失败", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void stopRecording() {
        if (cameraManager != null) {
            cameraManager.stopRecording();
            btnStartRecord.setEnabled(true);
            btnStopRecord.setEnabled(false);

            // 停止闪烁动画，恢复红色
            stopBlinkAnimation();

            Toast.makeText(this, "录制已停止", Toast.LENGTH_SHORT).show();
            Log.d(TAG, "Recording stopped");
        }
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
                    blinkHandler.postDelayed(this, 500);  // 每500ms闪烁一次
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
            Log.d(TAG, "Picture taken");
        }
    }

    /**
     * 远程录制（由钉钉指令触发）
     * 自动录制 1 分钟视频并上传到钉钉
     */
    public void startRemoteRecording(String conversationId, String conversationType, String userId) {
        this.remoteConversationId = conversationId;
        this.remoteConversationType = conversationType;
        this.remoteUserId = userId;

        Log.d(TAG, "收到远程录制指令，开始录制 1 分钟视频...");

        // 如果正在录制，先停止
        if (cameraManager != null && cameraManager.isRecording()) {
            cameraManager.stopRecording();
            try {
                Thread.sleep(500);  // 等待停止完成
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        // 开始录制
        if (cameraManager != null) {
            boolean success = cameraManager.startRecording();
            if (success) {
                Log.d(TAG, "远程录制已开始");

                // 设置 1 分钟后自动停止
                autoStopRunnable = () -> {
                    Log.d(TAG, "1 分钟录制完成，正在停止...");
                    cameraManager.stopRecording();

                    // 等待录制完全停止
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        uploadRecordedVideos();
                    }, 1000);
                };

                autoStopHandler.postDelayed(autoStopRunnable, 60 * 1000);  // 60 秒
            } else {
                Log.e(TAG, "远程录制启动失败");
                sendErrorToRemote("录制启动失败");
            }
        } else {
            Log.e(TAG, "摄像头未初始化");
            sendErrorToRemote("摄像头未初始化");
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

        Log.d(TAG, "收到远程拍照指令，开始拍照...");

        // 拍照
        if (cameraManager != null) {
            cameraManager.takePicture();
            Log.d(TAG, "远程拍照已执行");

            // 等待拍照完成后上传
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                uploadPhotos();
            }, 2000);  // 等待2秒确保照片保存完成
        } else {
            Log.e(TAG, "摄像头未初始化");
            sendErrorToRemote("摄像头未初始化");
        }
    }

    /**
     * 上传录制的视频到钉钉
     */
    private void uploadRecordedVideos() {
        Log.d(TAG, "开始上传视频到钉钉...");

        // 获取录制的视频文件
        File videoDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM), "EVCam_Video");

        if (!videoDir.exists() || !videoDir.isDirectory()) {
            Log.e(TAG, "视频目录不存在");
            sendErrorToRemote("视频目录不存在");
            return;
        }

        // 获取最新的视频文件（最近 1 分钟内创建的）
        File[] files = videoDir.listFiles((dir, name) -> name.endsWith(".mp4"));
        if (files == null || files.length == 0) {
            Log.e(TAG, "没有找到视频文件");
            sendErrorToRemote("没有找到视频文件");
            return;
        }

        // 筛选最近 1 分钟内的文件
        long currentTime = System.currentTimeMillis();
        List<File> recentFiles = new ArrayList<>();
        for (File file : files) {
            if (currentTime - file.lastModified() < 90 * 1000) {  // 90 秒内
                recentFiles.add(file);
            }
        }

        if (recentFiles.isEmpty()) {
            Log.e(TAG, "没有找到最近录制的视频");
            sendErrorToRemote("没有找到最近录制的视频");
            return;
        }

        Log.d(TAG, "找到 " + recentFiles.size() + " 个视频文件");

        // 使用 Activity 级别的 API 客户端
        if (dingTalkApiClient != null && remoteConversationId != null) {
            VideoUploadService uploadService = new VideoUploadService(this, dingTalkApiClient);
            uploadService.uploadVideos(recentFiles, remoteConversationId, remoteConversationType, remoteUserId, new VideoUploadService.UploadCallback() {
                @Override
                public void onProgress(String message) {
                    Log.d(TAG, message);
                }

                @Override
                public void onSuccess(String message) {
                    Log.d(TAG, message);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "视频上传成功", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "上传失败: " + error);
                    sendErrorToRemote("上传失败: " + error);
                }
            });
        } else {
            Log.e(TAG, "钉钉服务未启动");
            sendErrorToRemote("钉钉服务未启动");
        }
    }

    /**
     * 上传拍摄的照片到钉钉
     */
    private void uploadPhotos() {
        Log.d(TAG, "开始上传照片到钉钉...");

        // 获取照片文件
        File photoDir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DCIM), "EVCam_Photo");

        if (!photoDir.exists() || !photoDir.isDirectory()) {
            Log.e(TAG, "照片目录不存在");
            sendErrorToRemote("照片目录不存在");
            return;
        }

        // 获取最新的照片文件（最近 10 秒内创建的）
        File[] files = photoDir.listFiles((dir, name) -> name.endsWith(".jpg"));
        if (files == null || files.length == 0) {
            Log.e(TAG, "没有找到照片文件");
            sendErrorToRemote("没有找到照片文件");
            return;
        }

        // 筛选最近 10 秒内的文件
        long currentTime = System.currentTimeMillis();
        List<File> recentFiles = new ArrayList<>();
        for (File file : files) {
            if (currentTime - file.lastModified() < 10 * 1000) {  // 10 秒内
                recentFiles.add(file);
            }
        }

        if (recentFiles.isEmpty()) {
            Log.e(TAG, "没有找到最近拍摄的照片");
            sendErrorToRemote("没有找到最近拍摄的照片");
            return;
        }

        Log.d(TAG, "找到 " + recentFiles.size() + " 张照片");

        // 使用 Activity 级别的 API 客户端
        if (dingTalkApiClient != null && remoteConversationId != null) {
            PhotoUploadService uploadService = new PhotoUploadService(this, dingTalkApiClient);
            uploadService.uploadPhotos(recentFiles, remoteConversationId, remoteConversationType, remoteUserId, new PhotoUploadService.UploadCallback() {
                @Override
                public void onProgress(String message) {
                    Log.d(TAG, message);
                }

                @Override
                public void onSuccess(String message) {
                    Log.d(TAG, message);
                    runOnUiThread(() -> Toast.makeText(MainActivity.this, "照片上传成功", Toast.LENGTH_SHORT).show());
                }

                @Override
                public void onError(String error) {
                    Log.e(TAG, "上传失败: " + error);
                    sendErrorToRemote("上传失败: " + error);
                }
            });
        } else {
            Log.e(TAG, "钉钉服务未启动");
            sendErrorToRemote("钉钉服务未启动");
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
                    dingTalkApiClient.sendTextMessage(remoteConversationId, remoteConversationType, "录制失败: " + error, remoteUserId);
                    Log.d(TAG, "错误消息已发送到钉钉");
                } catch (Exception e) {
                    Log.e(TAG, "发送错误消息失败", e);
                }
            }).start();
        }
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
            Log.d(TAG, "钉钉服务已在运行");
            return;
        }

        Log.d(TAG, "正在启动钉钉服务...");

        // 创建 API 客户端
        dingTalkApiClient = new DingTalkApiClient(dingTalkConfig);

        // 创建连接回调
        DingTalkStreamManager.ConnectionCallback connectionCallback = new DingTalkStreamManager.ConnectionCallback() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    Log.d(TAG, "钉钉服务已连接");
                    Toast.makeText(MainActivity.this, "钉钉服务已启动", Toast.LENGTH_SHORT).show();
                    // 通知 RemoteViewFragment 更新 UI
                    updateRemoteViewFragmentUI();
                });
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    Log.d(TAG, "钉钉服务已断开");
                    // 通知 RemoteViewFragment 更新 UI
                    updateRemoteViewFragmentUI();
                });
            }

            @Override
            public void onError(String error) {
                runOnUiThread(() -> {
                    Log.e(TAG, "钉钉服务连接失败: " + error);
                    Toast.makeText(MainActivity.this, "连接失败: " + error, Toast.LENGTH_LONG).show();
                    // 通知 RemoteViewFragment 更新 UI
                    updateRemoteViewFragmentUI();
                });
            }
        };

        // 创建指令回调
        DingTalkStreamManager.CommandCallback commandCallback = new DingTalkStreamManager.CommandCallback() {
            @Override
            public void onRecordCommand(String conversationId, String conversationType, String userId) {
                startRemoteRecording(conversationId, conversationType, userId);
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
            Log.d(TAG, "正在停止钉钉服务...");
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
    protected void onDestroy() {
        super.onDestroy();

        // 取消自动停止录制的任务
        if (autoStopHandler != null && autoStopRunnable != null) {
            autoStopHandler.removeCallbacks(autoStopRunnable);
        }

        // 停止钉钉服务
        if (dingTalkStreamManager != null) {
            dingTalkStreamManager.stop();
        }

        if (cameraManager != null) {
            cameraManager.release();
        }
    }

    @Override
    public void onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START);
        } else {
            super.onBackPressed();
        }
    }
}