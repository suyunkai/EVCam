package com.kooo.evcam.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

/**
 * 全局单例，持有 MultiCameraManager 实例。
 * 允许在后台（Service）中初始化摄像头，不依赖 MainActivity。
 * TextureView 可以在 MainActivity 打开后再绑定。
 */
public class CameraManagerHolder {
    private static final String TAG = "CameraManagerHolder";
    private static CameraManagerHolder instance;
    private MultiCameraManager cameraManager;

    private CameraManagerHolder() {}

    public static synchronized CameraManagerHolder getInstance() {
        if (instance == null) {
            instance = new CameraManagerHolder();
        }
        return instance;
    }

    /**
     * 获取已初始化的 MultiCameraManager，如果未初始化则在后台初始化（TextureView=null）。
     * 可从 Service 或 Activity 调用。
     */
    public synchronized MultiCameraManager getOrInit(Context context) {
        if (cameraManager != null && !cameraManager.isReleased()) {
            return cameraManager;
        }

        if (cameraManager != null) {
            AppLog.w(TAG, "Holder 中的 CameraManager 已被 release，丢弃并重新创建");
            cameraManager = null;
        }

        AppLog.d(TAG, "后台初始化摄像头（无 TextureView）...");
        AppConfig appConfig = new AppConfig(context);

        cameraManager = new MultiCameraManager(context.getApplicationContext());

        // 获取摄像头数量
        int cameraCount = getCameraCount(appConfig);
        cameraManager.setMaxOpenCameras(cameraCount);

        try {
            CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            if (cm == null) {
                AppLog.e(TAG, "CameraManager service not available");
                return cameraManager;
            }
            String[] cameraIds = cm.getCameraIdList();
            if (cameraIds.length == 0) {
                AppLog.e(TAG, "No cameras available");
                return cameraManager;
            }

            // 根据车型配置初始化摄像头（TextureView 全部传 null）
            initCamerasByCarModel(appConfig, cameraIds);

            // 设置录制模式
            boolean useCodecRecording = appConfig.shouldUseCodecRecording();
            cameraManager.setCodecRecordingMode(useCodecRecording);

            // 注意：不在后台调用 openAllCameras()
            // 部分设备/系统会禁止后台应用访问摄像头（CAMERA_DISABLED by policy）
            // 摄像头会在悬浮窗设置 Surface 并调用 recreateSession 时按需打开

            AppLog.d(TAG, "后台摄像头对象初始化完成，共 " + cameraCount + " 个摄像头（未打开硬件）");
        } catch (CameraAccessException e) {
            AppLog.e(TAG, "后台初始化摄像头失败: " + e.getMessage());
        }

        return cameraManager;
    }

    /**
     * 获取已初始化的 MultiCameraManager（不自动初始化）
     */
    public synchronized MultiCameraManager getCameraManager() {
        return cameraManager;
    }

    /**
     * 设置已有的 MultiCameraManager（由 MainActivity 初始化时调用）
     */
    public synchronized void setCameraManager(MultiCameraManager manager) {
        this.cameraManager = manager;
    }

    /**
     * 释放资源
     */
    public synchronized void release() {
        if (cameraManager != null) {
            cameraManager.release();
            cameraManager = null;
        }
    }

    private int getCameraCount(AppConfig appConfig) {
        String carModel = appConfig.getCarModel();
        if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
            return 2;
        } else if (appConfig.isCustomCarModel()) {
            return appConfig.getCameraCount();
        }
        return 4; // E5, L7, Xinghan7 等默认4摄
    }

    /**
     * 根据车型配置初始化摄像头（与 MainActivity 中的逻辑一致，但 TextureView 全部传 null）
     */
    private void initCamerasByCarModel(AppConfig appConfig, String[] cameraIds) {
        String carModel = appConfig.getCarModel();

        if (AppConfig.CAR_MODEL_L7.equals(carModel) || AppConfig.CAR_MODEL_L7_MULTI.equals(carModel)) {
            initCamerasForL7(cameraIds);
        } else if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
            initCamerasForPhone(cameraIds);
        } else if (AppConfig.CAR_MODEL_XINGHAN_7.equals(carModel)) {
            initCamerasForXinghan7(cameraIds);
        } else if (appConfig.isCustomCarModel()) {
            initCamerasForCustomModel(appConfig, cameraIds);
        } else {
            // 银河E5（默认）
            initCamerasForGalaxyE5(cameraIds);
        }
    }

    private void initCamerasForGalaxyE5(String[] cameraIds) {
        if (cameraIds.length >= 4) {
            cameraManager.initCameras(
                    cameraIds[2], null, cameraIds[1], null,
                    cameraIds[3], null, cameraIds[0], null);
        } else if (cameraIds.length >= 2) {
            cameraManager.initCameras(
                    null, null, null, null,
                    cameraIds[0], null, cameraIds[1], null);
        } else if (cameraIds.length == 1) {
            cameraManager.initCameras(
                    cameraIds[0], null, cameraIds[0], null,
                    cameraIds[0], null, cameraIds[0], null);
        }
    }

    private void initCamerasForL7(String[] cameraIds) {
        if (cameraIds.length >= 4) {
            cameraManager.initCameras(
                    cameraIds[2], null, cameraIds[3], null,
                    cameraIds[0], null, cameraIds[1], null);
        } else if (cameraIds.length >= 2) {
            cameraManager.initCameras(
                    cameraIds[0], null, cameraIds[1], null,
                    cameraIds[0], null, cameraIds[1], null);
        }
    }

    private void initCamerasForXinghan7(String[] cameraIds) {
        if (cameraIds.length >= 5) {
            cameraManager.initCameras(
                    cameraIds[3], null, cameraIds[2], null,
                    cameraIds[4], null, cameraIds[1], null);
        } else if (cameraIds.length >= 4) {
            cameraManager.initCameras(
                    cameraIds[3], null, cameraIds[2], null,
                    cameraIds[0], null, cameraIds[1], null);
        }
    }

    private void initCamerasForPhone(String[] cameraIds) {
        if (cameraIds.length >= 2) {
            cameraManager.initCameras(
                    cameraIds[1], null, cameraIds[0], null,
                    null, null, null, null);
        } else if (cameraIds.length == 1) {
            cameraManager.initCameras(
                    cameraIds[0], null, cameraIds[0], null,
                    null, null, null, null);
        }
    }

    private void initCamerasForCustomModel(AppConfig appConfig, String[] cameraIds) {
        String frontId = appConfig.getCameraId("front");
        String backId = appConfig.getCameraId("back");
        String leftId = appConfig.getCameraId("left");
        String rightId = appConfig.getCameraId("right");

        int count = appConfig.getCameraCount();
        switch (count) {
            case 1:
                cameraManager.initCameras(frontId, null, null, null, null, null, null, null);
                break;
            case 2:
                cameraManager.initCameras(frontId, null, backId, null, null, null, null, null);
                break;
            default:
                cameraManager.initCameras(frontId, null, backId, null, leftId, null, rightId, null);
                break;
        }
    }
}
