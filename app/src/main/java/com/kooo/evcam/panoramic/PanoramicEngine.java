package com.kooo.evcam.panoramic;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.view.Surface;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.camera.CameraManagerHolder;
import com.kooo.evcam.config.RecordingConfig;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 全景引擎
 * 负责视频录制和补盲画面的渲染管理
 * 按照D:\yuan参考实现重构
 */
public class PanoramicEngine {
    private static final String TAG = "PanoramicEngine";

    // OpenGL顶点着色器
    private static final String VERTEX_SHADER =
            "attribute vec4 aPosition;\n" +
            "attribute vec2 aTexCoord;\n" +
            "varying vec2 vTexCoord;\n" +
            "void main() {\n" +
            "    gl_Position = aPosition;\n" +
            "    vTexCoord = aTexCoord;\n" +
            "}\n";

    // OpenGL片段着色器
    private static final String FRAGMENT_SHADER =
            "precision mediump float;\n" +
            "varying vec2 vTexCoord;\n" +
            "uniform sampler2D uTexture;\n" +
            "void main() {\n" +
            "    gl_FragColor = texture2D(uTexture, vTexCoord);\n" +
            "}\n";

    private final Context context;
    private final RecordingConfig recordingConfig;
    private HandlerThread renderThread;
    private Handler renderHandler;

    // 引擎状态
    private final AtomicBoolean isInitialized = new AtomicBoolean(false);
    private final AtomicBoolean isRecording = new AtomicBoolean(false);

    // 渲染参数
    private int targetWidth = 1280;
    private int targetHeight = 720;
    private float fboScale = 1.0f;
    private boolean isPortrait = false;
    private String inputFormat = "nv21";

    // 补盲相关
    private int blindSpotTargetIndex = -1;
    private SurfaceTexture blindSpotSurfaceTexture;
    private Surface blindSpotSurface;

    // 裁剪区域 [x, y, width, height] 归一化坐标
    private float[] frontCrop = new float[]{0.0f, 0.0f, 1.0f, 0.5f};
    private float[] backCrop = new float[]{0.0f, 0.5f, 1.0f, 0.5f};
    private float[] leftCrop = new float[]{0.0f, 0.5f, 0.5f, 0.5f};
    private float[] rightCrop = new float[]{0.5f, 0.5f, 0.5f, 0.5f};

    // OpenGL资源
    private int program = 0;
    private int positionHandle = 0;
    private int texCoordHandle = 0;
    private int textureHandle = 0;

    // 顶点数据
    private final FloatBuffer vertexBuffer;
    private final FloatBuffer texCoordBuffer;

    private static final float[] VERTICES = {
            -1.0f, -1.0f,  // 左下
             1.0f, -1.0f,  // 右下
            -1.0f,  1.0f,  // 左上
             1.0f,  1.0f   // 右上
    };

    private static final float[] TEX_COORDS = {
            0.0f, 1.0f,  // 左下
            1.0f, 1.0f,  // 右下
            0.0f, 0.0f,  // 左上
            1.0f, 0.0f   // 右上
    };

    public PanoramicEngine(Context context, RecordingConfig recordingConfig) {
        this.context = context.getApplicationContext();
        this.recordingConfig = recordingConfig;

        // 初始化顶点缓冲区
        ByteBuffer vbb = ByteBuffer.allocateDirect(VERTICES.length * 4);
        vbb.order(ByteOrder.nativeOrder());
        vertexBuffer = vbb.asFloatBuffer();
        vertexBuffer.put(VERTICES);
        vertexBuffer.position(0);

        ByteBuffer tbb = ByteBuffer.allocateDirect(TEX_COORDS.length * 4);
        tbb.order(ByteOrder.nativeOrder());
        texCoordBuffer = tbb.asFloatBuffer();
        texCoordBuffer.put(TEX_COORDS);
        texCoordBuffer.position(0);
    }

    // ========== 引擎初始化 ==========

    public void initialize() {
        if (isInitialized.get()) {
            return;
        }

        renderThread = new HandlerThread("PanoramicEngineRender");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        renderHandler.post(() -> {
            try {
                initializeGL();
                isInitialized.set(true);
                AppLog.d(TAG, "全景引擎初始化完成");
            } catch (Exception e) {
                AppLog.e(TAG, "全景引擎初始化失败", e);
            }
        });
    }

    private void initializeGL() {
        // 创建OpenGL程序
        int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);

        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertexShader);
        GLES20.glAttachShader(program, fragmentShader);
        GLES20.glLinkProgram(program);

        // 获取着色器变量句柄
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTexCoord");
        textureHandle = GLES20.glGetUniformLocation(program, "uTexture");
    }

    private int loadShader(int type, String shaderCode) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    // ========== 录制控制 ==========

    public void startRecording() {
        if (!isInitialized.get()) {
            AppLog.w(TAG, "引擎未初始化，无法开始录制");
            return;
        }

        isRecording.set(true);
        AppLog.d(TAG, "开始录制");
    }

    public void stopRecording() {
        isRecording.set(false);
        AppLog.d(TAG, "停止录制");
    }

    public boolean isRecording() {
        return isRecording.get();
    }

    // ========== 配置设置 ==========

    public void setTargetResolution(int width, int height) {
        this.targetWidth = width;
        this.targetHeight = height;
        AppLog.d(TAG, "目标分辨率设置: " + width + "x" + height);
    }

    public void setFboScale(float scale) {
        this.fboScale = scale;
        AppLog.d(TAG, "FBO缩放设置: " + scale);
    }

    public void setPortrait(boolean portrait) {
        this.isPortrait = portrait;
        AppLog.d(TAG, "竖屏模式: " + portrait);
    }

    public void setInputFormat(String format) {
        this.inputFormat = format;
        AppLog.d(TAG, "输入格式设置: " + format);
    }

    // ========== 裁剪区域设置 ==========

    public void setCropRegion(String position, float[] crop) {
        if (crop == null || crop.length != 4) {
            AppLog.w(TAG, "无效的裁剪区域");
            return;
        }

        switch (position) {
            case "front":
                frontCrop = crop.clone();
                break;
            case "back":
                backCrop = crop.clone();
                break;
            case "left":
                leftCrop = crop.clone();
                break;
            case "right":
                rightCrop = crop.clone();
                break;
            default:
                AppLog.w(TAG, "未知的摄像头位置: " + position);
                return;
        }
        AppLog.d(TAG, position + " 裁剪区域设置: [" + crop[0] + ", " + crop[1] + ", " + crop[2] + ", " + crop[3] + "]");
    }

    public float[] getCropRegion(String position) {
        switch (position) {
            case "front":
                return frontCrop.clone();
            case "back":
                return backCrop.clone();
            case "left":
                return leftCrop.clone();
            case "right":
                return rightCrop.clone();
            default:
                return new float[]{0.0f, 0.0f, 1.0f, 1.0f};
        }
    }

    // ========== 补盲相关 ==========

    public void setBlindSpotTargetIndex(int index) {
        this.blindSpotTargetIndex = index;
        AppLog.d(TAG, "补盲目标索引设置: " + index);
    }

    public int getBlindSpotTargetIndex() {
        return blindSpotTargetIndex;
    }

    public void setExternalTargetSurface(int targetIndex, Surface surface) {
        if (targetIndex == blindSpotTargetIndex) {
            this.blindSpotSurface = surface;
            AppLog.d(TAG, "补盲目标表面已设置");
        }
    }

    public void setExternalTargetOverlayStyle(int targetIndex, float rotationDegrees, float cornerRadius) {
        if (targetIndex == blindSpotTargetIndex) {
            AppLog.d(TAG, "补盲叠加层样式: rotation=" + rotationDegrees + ", cornerRadius=" + cornerRadius);
        }
    }

    public void setBlindSpotSurfaceTexture(SurfaceTexture surfaceTexture) {
        this.blindSpotSurfaceTexture = surfaceTexture;
    }

    public SurfaceTexture getBlindSpotSurfaceTexture() {
        return blindSpotSurfaceTexture;
    }

    // ========== 渲染方法 ==========

    public void renderFrame() {
        if (!isInitialized.get() || !isRecording.get()) {
            return;
        }

        renderHandler.post(() -> {
            try {
                performRender();
            } catch (Exception e) {
                AppLog.e(TAG, "渲染帧失败", e);
            }
        });
    }

    private void performRender() {
        // 使用OpenGL进行渲染
        GLES20.glUseProgram(program);

        // 设置顶点数据
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(positionHandle);

        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);
        GLES20.glEnableVertexAttribArray(texCoordHandle);

        // 绘制
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 禁用顶点数组
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);
    }

    // ========== 资源释放 ==========

    public void release() {
        isRecording.set(false);
        isInitialized.set(false);

        if (renderHandler != null) {
            renderHandler.post(() -> {
                if (program != 0) {
                    GLES20.glDeleteProgram(program);
                    program = 0;
                }
            });
        }

        if (renderThread != null) {
            renderThread.quitSafely();
            renderThread = null;
        }

        if (blindSpotSurface != null) {
            blindSpotSurface.release();
            blindSpotSurface = null;
        }

        AppLog.d(TAG, "全景引擎已释放");
    }

    // ========== 获取状态 ==========

    public boolean isInitialized() {
        return isInitialized.get();
    }

    public int getTargetWidth() {
        return targetWidth;
    }

    public int getTargetHeight() {
        return targetHeight;
    }

    public float getFboScale() {
        return fboScale;
    }

    public boolean isPortrait() {
        return isPortrait;
    }
}
