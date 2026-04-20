package com.kooo.evcam.camera;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.view.Surface;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 鱼眼矫正器
 * 使用 OpenGL ES 2.0 对摄像头预览画面进行鱼眼（桶形畸变）矫正。
 * 仅作用于预览流，不影响录制流。
 *
 * 工作流程：
 * 1. 创建中间 SurfaceTexture，摄像头输出到此 SurfaceTexture（Camera2 唯一的预览输出）
 * 2. 使用 OES 纹理读取摄像头帧
 * 3. 通过鱼眼矫正片段着色器渲染到主 TextureView 的 Surface
 * 4. 同一帧同时渲染到所有附加输出（补盲悬浮窗、副屏等），共享同一个 GL 管线
 *
 * 矫正模型：Brown-Conrady 径向畸变
 *   r_corrected = r * (1.0 + k1 * r² + k2 * r⁴)
 * 其中 k1/k2 为畸变系数，r 为到画面中心的归一化距离。
 * k1 > 0 进行桶形畸变矫正（将鱼眼画面向内收缩）
 * k1 < 0 增加桶形畸变
 */
public class FisheyeCorrector {
    private static final String TAG = "FisheyeCorrector";

    // ===== Shaders =====

    private static final String VERTEX_SHADER =
            "uniform mat4 uMVPMatrix;\n" +
            "attribute vec4 aPosition;\n" +
            "attribute vec4 aTextureCoord;\n" +
            "varying vec2 vTextureCoord;\n" +
            "void main() {\n" +
            "    gl_Position = uMVPMatrix * aPosition;\n" +
            "    vTextureCoord = aTextureCoord.xy;\n" +
            "}\n";

    /**
     * 鱼眼矫正片段着色器
     * 在屏幕空间计算畸变偏移，再变换到 OES 纹理空间采样
     * 支持旋转
     */
    private static final String FRAGMENT_SHADER_FISHEYE =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform float uK1;\n" +           // 主畸变系数
            "uniform float uK2;\n" +           // 二次畸变系数
            "uniform float uZoom;\n" +         // 矫正后缩放（用于裁切黑边）
            "uniform vec2 uCenter;\n" +        // 畸变中心偏移 (0.5, 0.5) 为画面正中
            "uniform float uRotation;\n" +      // 旋转角度（度数）
            "const float PI = 3.14159265;\n" +
            "void main() {\n" +
            "    // 应用旋转\n" +
            "    float angle = uRotation * PI / 180.0;\n" +
            "    vec2 center = vec2(0.5, 0.5);\n" +
            "    vec2 rotatedCoord = vec2(\n" +
            "        cos(angle) * (vTextureCoord.x - center.x) - sin(angle) * (vTextureCoord.y - center.y) + center.x,\n" +
            "        sin(angle) * (vTextureCoord.x - center.x) + cos(angle) * (vTextureCoord.y - center.y) + center.y\n" +
            "    );\n" +
            "    vec2 coord = (rotatedCoord - uCenter) / uZoom;\n" +
            "    float r = length(coord);\n" +
            "    float r2 = r * r;\n" +
            "    float r4 = r2 * r2;\n" +
            "    float distortion = 1.0 + uK1 * r2 + uK2 * r4;\n" +
            "    vec2 corrected = coord * distortion + uCenter;\n" +
            "    if (corrected.x < 0.0 || corrected.x > 1.0 ||\n" +
            "        corrected.y < 0.0 || corrected.y > 1.0) {\n" +
            "        gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);\n" +
            "    } else {\n" +
            "        vec2 texCoord = (uTexMatrix * vec4(corrected, 0.0, 1.0)).xy;\n" +
            "        gl_FragColor = texture2D(sTexture, texCoord);\n" +
            "    }\n" +
            "}\n";

    // 全屏四边形顶点
    private static final float[] VERTICES = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
    };

    // 纹理坐标
    private static final float[] TEXTURE_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    // ===== 成员变量 =====
    private final String cameraId;
    private final String cameraPosition;
    private final int width;
    private final int height;

    // EGL
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    // GL
    private int program;
    private int oesTextureId;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // Shader handles
    private int positionHandle;
    private int texCoordHandle;
    private int mvpMatrixHandle;
    private int texMatrixHandle;
    private int textureHandle;
    private int k1Handle;
    private int k2Handle;
    private int zoomHandle;
    private int centerHandle;
    private int rotationHandle;

    // 变换矩阵
    private final float[] mvpMatrix = new float[16];
    private final float[] texMatrix = new float[16];

    // 中间 SurfaceTexture（摄像头输出到此处）
    private SurfaceTexture intermediateSurfaceTexture;
    private Surface intermediateSurface;

    // 附加输出 Surface（补盲悬浮窗、副屏等）
    // key: "mainFloating" / "secondaryDisplay" 等标识
    private final LinkedHashMap<String, EGLSurface> extraEglSurfaces = new LinkedHashMap<>();
    private final LinkedHashMap<String, Surface> extraRawSurfaces = new LinkedHashMap<>();

    // 矫正参数（可实时更新）
    private volatile float k1 = 0.0f;
    private volatile float k2 = 0.0f;
    private volatile float zoom = 1.0f;
    private volatile float centerX = 0.5f;
    private volatile float centerY = 0.5f;
    private volatile int rotation = 0;

    // 状态
    private boolean isInitialized = false;
    private boolean isReleased = false;
    private Handler renderHandler;

    public FisheyeCorrector(String cameraId, String cameraPosition, int width, int height) {
        this.cameraId = cameraId;
        this.cameraPosition = cameraPosition;
        this.width = width;
        this.height = height;
        Matrix.setIdentityM(mvpMatrix, 0);
    }

    /**
     * 初始化 EGL/GL，绑定到 TextureView 的输出 Surface
     *
     * @param outputSurface TextureView 的 Surface（通过 new Surface(textureView.getSurfaceTexture()) 获取）
     * @param handler       用于帧回调的 Handler（应为摄像头后台线程）
     * @return 中间 Surface，应添加到 Camera2 的 OutputConfiguration 作为预览输出
     */
    public Surface initialize(Surface outputSurface, Handler handler) {
        if (isInitialized) {
            AppLog.w(TAG, "Camera " + cameraId + " FisheyeCorrector already initialized");
            return intermediateSurface;
        }

        this.renderHandler = handler;
        AppLog.d(TAG, "Camera " + cameraId + " Initializing FisheyeCorrector " + width + "x" + height);

        try {
            initEgl(outputSurface);
            initGl();

            // 创建中间 SurfaceTexture
            intermediateSurfaceTexture = new SurfaceTexture(oesTextureId);
            intermediateSurfaceTexture.setDefaultBufferSize(width, height);
            intermediateSurface = new Surface(intermediateSurfaceTexture);

            // 帧到达时进行矫正渲染
            intermediateSurfaceTexture.setOnFrameAvailableListener(st -> drawFrame(), handler);

            isInitialized = true;
            AppLog.d(TAG, "Camera " + cameraId + " FisheyeCorrector initialized OK, textureId=" + oesTextureId);
            return intermediateSurface;

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " Failed to init FisheyeCorrector", e);
            release();
            throw new RuntimeException("FisheyeCorrector init failed", e);
        }
    }

    /**
     * 从 AppConfig 加载当前摄像头的矫正参数
     */
    public void loadParams(AppConfig appConfig) {
        if (appConfig == null || cameraPosition == null) return;
        this.k1 = appConfig.getFisheyeCorrectionK1(cameraPosition);
        this.k2 = appConfig.getFisheyeCorrectionK2(cameraPosition);
        this.zoom = appConfig.getFisheyeCorrectionZoom(cameraPosition);
        this.centerX = appConfig.getFisheyeCorrectionCenterX(cameraPosition);
        this.centerY = appConfig.getFisheyeCorrectionCenterY(cameraPosition);
        AppLog.d(TAG, "Camera " + cameraId + " params loaded: k1=" + k1 + " k2=" + k2 +
                " zoom=" + zoom + " cx=" + centerX + " cy=" + centerY);
    }

    /**
     * 实时更新矫正参数（从悬浮窗调节时调用）
     */
    public void updateParams(float k1, float k2, float zoom, float centerX, float centerY) {
        updateParams(k1, k2, zoom, centerX, centerY, 0);
    }

    /**
     * 实时更新矫正参数（支持旋转）
     */
    public void updateParams(float k1, float k2, float zoom, float centerX, float centerY, int rotation) {
        this.k1 = k1;
        this.k2 = k2;
        this.zoom = zoom;
        this.centerX = centerX;
        this.centerY = centerY;
        this.rotation = rotation;
    }

    /**
     * 获取中间 Surface（供 Camera2 作为预览输出目标）
     */
    public Surface getIntermediateSurface() {
        return intermediateSurface;
    }

    /**
     * 获取中间 SurfaceTexture
     */
    public SurfaceTexture getIntermediateSurfaceTexture() {
        return intermediateSurfaceTexture;
    }

    public boolean isInitialized() {
        return isInitialized && !isReleased;
    }

    // ===== 附加输出管理 =====

    /**
     * 添加一个附加输出 Surface（如补盲悬浮窗、副屏）。
     * 每帧会同时渲染到主输出和所有附加输出，共享同一个 GL 矫正管线。
     * 必须在 GL 线程（Handler）上调用，或确保 EGL context 可用。
     *
     * @param tag     唯一标识，如 "mainFloating"、"secondaryDisplay"
     * @param surface 附加输出的 Surface
     */
    public void addOutputSurface(String tag, Surface surface) {
        if (!isInitialized || isReleased) return;
        if (surface == null || !surface.isValid()) return;

        // 先移除旧的同名 Surface
        removeOutputSurface(tag);

        try {
            makeCurrent();
            int[] attribs = { EGL14.EGL_NONE };
            EGLSurface eglSurf = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0);
            if (eglSurf == EGL14.EGL_NO_SURFACE) {
                AppLog.e(TAG, "Camera " + cameraId + " failed to create EGL surface for extra output: " + tag);
                return;
            }
            extraEglSurfaces.put(tag, eglSurf);
            extraRawSurfaces.put(tag, surface);
            AppLog.d(TAG, "Camera " + cameraId + " added extra output surface: " + tag);
        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " error adding extra output " + tag, e);
        }
    }

    /**
     * 移除一个附加输出 Surface。
     */
    public void removeOutputSurface(String tag) {
        EGLSurface eglSurf = extraEglSurfaces.remove(tag);
        extraRawSurfaces.remove(tag);
        if (eglSurf != null && eglSurf != EGL14.EGL_NO_SURFACE) {
            try {
                // 确保当前 context 不在被移除的 surface 上
                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                    EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
                }
                EGL14.eglDestroySurface(eglDisplay, eglSurf);
            } catch (Exception e) {
                // ignore
            }
            AppLog.d(TAG, "Camera " + cameraId + " removed extra output surface: " + tag);
        }
    }

    /**
     * 检查是否存在指定标识的附加输出
     */
    public boolean hasOutputSurface(String tag) {
        return extraEglSurfaces.containsKey(tag);
    }

    // ===== 渲染 =====

    private void drawFrame() {
        if (!isInitialized || isReleased) return;
        if (intermediateSurfaceTexture == null) return;

        try {
            makeCurrent();

            // 更新 OES 纹理（每帧只调用一次，纹理在所有 EGL Surface 间共享）
            intermediateSurfaceTexture.updateTexImage();
            intermediateSurfaceTexture.getTransformMatrix(texMatrix);

            // 设置 shader 共享状态（Uniform / 纹理绑定 / 顶点属性）
            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);
            GLES20.glUniform1i(textureHandle, 0);
            GLES20.glUniform1f(k1Handle, k1);
            GLES20.glUniform1f(k2Handle, k2);
            GLES20.glUniform1f(zoomHandle, zoom);
            GLES20.glUniform2f(centerHandle, centerX, centerY);
            GLES20.glUniform1f(rotationHandle, rotation);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            // ── 1. 渲染到主输出（TextureView）──
            renderToSurface(eglSurface);

            // ── 2. 渲染到所有附加输出（补盲悬浮窗、副屏等）──
            if (!extraEglSurfaces.isEmpty()) {
                Iterator<Map.Entry<String, EGLSurface>> it = extraEglSurfaces.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<String, EGLSurface> entry = it.next();
                    Surface raw = extraRawSurfaces.get(entry.getKey());
                    if (raw == null || !raw.isValid()) {
                        // Surface 已失效，自动清理
                        try { EGL14.eglDestroySurface(eglDisplay, entry.getValue()); } catch (Exception ignored) {}
                        extraRawSurfaces.remove(entry.getKey());
                        it.remove();
                        AppLog.d(TAG, "Camera " + cameraId + " auto-removed invalid extra surface: " + entry.getKey());
                        continue;
                    }
                    renderToSurface(entry.getValue());
                }
                // 恢复主 EGL Surface 为 current
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
            }

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);

        } catch (Exception e) {
            AppLog.e(TAG, "Camera " + cameraId + " drawFrame error", e);
        }
    }

    /**
     * 渲染矫正帧到指定 EGL Surface（主输出或附加输出共用）
     */
    private void renderToSurface(EGLSurface targetSurface) {
        EGL14.eglMakeCurrent(eglDisplay, targetSurface, targetSurface, eglContext);

        int[] vw = new int[1], vh = new int[1];
        EGL14.eglQuerySurface(eglDisplay, targetSurface, EGL14.EGL_WIDTH, vw, 0);
        EGL14.eglQuerySurface(eglDisplay, targetSurface, EGL14.EGL_HEIGHT, vh, 0);
        GLES20.glViewport(0, 0, vw[0] > 0 ? vw[0] : width, vh[0] > 0 ? vh[0] : height);

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        EGL14.eglSwapBuffers(eglDisplay, targetSurface);
    }

    // ===== EGL / GL 初始化 =====

    private void initEgl(Surface outputSurface) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new RuntimeException("Unable to get EGL display");
        }

        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new RuntimeException("Unable to initialize EGL");
        }

        int[] attribList = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_NONE
        };

        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfigs, 0)) {
            throw new RuntimeException("Unable to find suitable EGL config");
        }
        eglConfig = configs[0];

        int[] contextAttribs = {
                EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL14.EGL_NONE
        };
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("Unable to create EGL context");
        }

        int[] surfaceAttribs = { EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, outputSurface, surfaceAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Unable to create EGL window surface");
        }

        makeCurrent();
        AppLog.d(TAG, "Camera " + cameraId + " EGL setup complete");
    }

    private void initGl() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER_FISHEYE);
        if (program == 0) {
            throw new RuntimeException("Unable to create shader program");
        }

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
        textureHandle = GLES20.glGetUniformLocation(program, "sTexture");
        k1Handle = GLES20.glGetUniformLocation(program, "uK1");
        k2Handle = GLES20.glGetUniformLocation(program, "uK2");
        zoomHandle = GLES20.glGetUniformLocation(program, "uZoom");
        centerHandle = GLES20.glGetUniformLocation(program, "uCenter");
        rotationHandle = GLES20.glGetUniformLocation(program, "uRotation");

        // OES 纹理
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        oesTextureId = textures[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        vertexBuffer = createFloatBuffer(VERTICES);
        texCoordBuffer = createFloatBuffer(TEXTURE_COORDS);

        AppLog.d(TAG, "Camera " + cameraId + " GL setup complete, oesTextureId=" + oesTextureId);
    }

    // ===== 释放 =====

    public void release() {
        if (isReleased) return;
        AppLog.d(TAG, "Camera " + cameraId + " Releasing FisheyeCorrector");

        isReleased = true;
        isInitialized = false;

        // 释放所有附加输出 EGL Surface
        for (Map.Entry<String, EGLSurface> entry : extraEglSurfaces.entrySet()) {
            try { EGL14.eglDestroySurface(eglDisplay, entry.getValue()); } catch (Exception ignored) {}
        }
        extraEglSurfaces.clear();
        extraRawSurfaces.clear();

        if (intermediateSurfaceTexture != null) {
            intermediateSurfaceTexture.setOnFrameAvailableListener(null);
            intermediateSurfaceTexture.release();
            intermediateSurfaceTexture = null;
        }

        if (intermediateSurface != null) {
            intermediateSurface.release();
            intermediateSurface = null;
        }

        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }

        if (oesTextureId != 0) {
            int[] tex = { oesTextureId };
            GLES20.glDeleteTextures(1, tex, 0);
            oesTextureId = 0;
        }

        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }

        renderHandler = null;
        AppLog.d(TAG, "Camera " + cameraId + " FisheyeCorrector released");
    }

    // ===== 工具方法 =====

    private void makeCurrent() {
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vs == 0) return 0;
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (fs == 0) { GLES20.glDeleteShader(vs); return 0; }

        int prog = GLES20.glCreateProgram();
        if (prog == 0) { AppLog.e(TAG, "glCreateProgram failed"); return 0; }

        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);
        GLES20.glLinkProgram(prog);

        int[] linkStatus = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0);
        if (linkStatus[0] != GLES20.GL_TRUE) {
            AppLog.e(TAG, "Link failed: " + GLES20.glGetProgramInfoLog(prog));
            GLES20.glDeleteProgram(prog);
            return 0;
        }

        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return prog;
    }

    private int loadShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) return 0;
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] compiled = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0);
        if (compiled[0] == 0) {
            AppLog.e(TAG, "Compile shader failed: " + GLES20.glGetShaderInfoLog(shader));
            GLES20.glDeleteShader(shader);
            return 0;
        }
        return shader;
    }

    private FloatBuffer createFloatBuffer(float[] data) {
        ByteBuffer bb = ByteBuffer.allocateDirect(data.length * 4);
        bb.order(ByteOrder.nativeOrder());
        FloatBuffer fb = bb.asFloatBuffer();
        fb.put(data);
        fb.position(0);
        return fb;
    }

    private void checkGlError(String op) {
        int error;
        while ((error = GLES20.glGetError()) != GLES20.GL_NO_ERROR) {
            AppLog.e(TAG, "Camera " + cameraId + " " + op + ": glError " + error);
        }
    }
}
