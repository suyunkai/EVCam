package com.kooo.evcam.v2.permissions;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 银河E5（E245）系统白名单配置助手
 * 
 * 将 EVCam 添加到车机系统的三个白名单配置文件中：
 * 1. geely_lifectl_start_list.xml - 系统启动列表
 * 2. ecarx_str_policies.xml - Ecarx STR 白名单
 * 3. bgms_config.xml - BGMS 后台管理白名单
 * 
 * 通过 ADB TCP 协议（localhost:5555）执行，与"一键获取权限"使用相同的通道。
 */
public class SystemWhitelistHelper {

    private static final String TAG = "SystemWhitelistHelper";
    private static final String SCRIPT_ASSET_NAME = "add_evcam_config.sh";
    private static final String RESTORE_SCRIPT_ASSET_NAME = "restore_evcam_config.sh";

    private final Context context;
    private AdbPermissionHelper adbHelper;

    /**
     * 回调接口，与 AdbPermissionHelper.Callback 一致
     */
    public interface Callback {
        /** 实时日志输出（在主线程回调） */
        void onLog(String message);
        /** 执行完成（在主线程回调） */
        void onComplete(boolean success);
    }

    public SystemWhitelistHelper(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * 执行白名单配置。
     * 1. 将脚本从 assets 复制到应用缓存目录
     * 2. 通过 ADB TCP 协议执行脚本（与一键获取权限相同的通道）
     * 3. 实时输出脚本日志
     */
    public void executeWhitelistSetup(Callback callback) {
        // 步骤 1：将脚本从 assets 复制到缓存目录
        callback.onLog("[INFO] 正在准备脚本文件...");

        File scriptFile = copyScriptFromAssets(SCRIPT_ASSET_NAME);
        if (scriptFile == null) {
            callback.onLog("[ERROR] 无法准备脚本文件");
            callback.onComplete(false);
            return;
        }
        callback.onLog("[OK] 脚本已准备: " + scriptFile.getAbsolutePath());
        callback.onLog("");

        // 步骤 2：通过 ADB 执行脚本
        if (adbHelper == null) {
            adbHelper = new AdbPermissionHelper(context);
        }

        // 应用内部路径 /data/data/... 对 ADB shell 可见，直接使用
        adbHelper.executeScriptFile(scriptFile.getAbsolutePath(), new AdbPermissionHelper.Callback() {
            @Override
            public void onLog(String message) {
                callback.onLog(message);
            }

            @Override
            public void onComplete(boolean allSuccess) {
                // 清理临时文件
                if (scriptFile.exists()) {
                    scriptFile.delete();
                }
                callback.onComplete(allSuccess);
            }
        });
    }

    /**
     * 执行白名单恢复。
     * 1. 将恢复脚本从 assets 复制到应用缓存目录
     * 2. 通过 ADB TCP 协议执行脚本
     * 3. 实时输出脚本日志
     */
    public void executeWhitelistRestore(Callback callback) {
        callback.onLog("[INFO] 正在准备恢复脚本...");

        File scriptFile = copyScriptFromAssets(RESTORE_SCRIPT_ASSET_NAME);
        if (scriptFile == null) {
            callback.onLog("[ERROR] 无法准备恢复脚本");
            callback.onComplete(false);
            return;
        }
        callback.onLog("[OK] 脚本已准备: " + scriptFile.getAbsolutePath());
        callback.onLog("");

        if (adbHelper == null) {
            adbHelper = new AdbPermissionHelper(context);
        }

        adbHelper.executeScriptFile(scriptFile.getAbsolutePath(), new AdbPermissionHelper.Callback() {
            @Override
            public void onLog(String message) {
                callback.onLog(message);
            }

            @Override
            public void onComplete(boolean allSuccess) {
                if (scriptFile.exists()) {
                    scriptFile.delete();
                }
                callback.onComplete(allSuccess);
            }
        });
    }

    /**
     * 将脚本文件从 assets 复制到应用缓存目录
     */
    private File copyScriptFromAssets(String assetName) {
        File cacheDir = context.getCacheDir();
        File scriptFile = new File(cacheDir, assetName);

        try (InputStream is = context.getAssets().open(assetName);
             OutputStream os = new FileOutputStream(scriptFile)) {

            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();

            scriptFile.setReadable(true, false);
            return scriptFile;

        } catch (IOException e) {
            Log.e(TAG, "复制脚本文件失败", e);
            return null;
        }
    }
}
