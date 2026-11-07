package com.kooo.evcam.camera;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import com.kooo.evcam.AppLog;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单通道分割渲染器。
 *
 * 车机把多路摄像头合并成一路画面输出时，本类负责把这一路画面按不同的裁剪区域
 * 分别渲染到多个输出 Surface（TextureView 预览、MediaCodec 编码输入），
 * 从而实现“一个物理通道，多个逻辑画面”的预览与独立编码。
 *
 * 输入：摄像头输出到一个中间 SurfaceTexture（OES 纹理）。
 * 输出：若干个带归一化裁剪矩形的 EGL Surface。
 */
public class SplitViewRenderer {
    private static final String TAG = "SplitViewRenderer";

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
     * 把整幅画面的指定子区域放大到整个输出。
     * 采样坐标系以画面左上角为原点（与用户框选区域一致），
     * 因此在映射时对四边形坐标的 y 轴做一次翻转。
     */
    private static final String FRAGMENT_SHADER =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;\n" +
            "varying vec2 vTextureCoord;\n" +
            "uniform samplerExternalOES sTexture;\n" +
            "uniform mat4 uTexMatrix;\n" +
            "uniform vec4 uCrop;\n" +          // x, y, w, h（归一化，左上角原点）
            "uniform float uRotateStep;\n" +   // 0/1/2/3 表示 0/90/180/270
            "uniform float uMirror;\n" +
            "void main() {\n" +
            "    vec2 img = vec2(vTextureCoord.x, 1.0 - vTextureCoord.y);\n" +
            "    if (uMirror > 0.5) {\n" +
            "        img.x = 1.0 - img.x;\n" +
            "    }\n" +
            "    if (uRotateStep > 2.5) {\n" +
            "        img = vec2(1.0 - img.y, img.x);\n" +
            "    } else if (uRotateStep > 1.5) {\n" +
            "        img = vec2(1.0 - img.x, 1.0 - img.y);\n" +
            "    } else if (uRotateStep > 0.5) {\n" +
            "        img = vec2(img.y, 1.0 - img.x);\n" +
            "    }\n" +
            "    vec2 sampleCoord = uCrop.xy + img * uCrop.zw;\n" +
            "    gl_FragColor = texture2D(sTexture, sampleCoord);\n" +
            "}\n";

    private static final float[] VERTICES = {
            -1.0f, -1.0f,
             1.0f, -1.0f,
            -1.0f,  1.0f,
             1.0f,  1.0f,
    };

    private static final float[] TEXTURE_COORDS = {
            0.0f, 0.0f,
            1.0f, 0.0f,
            0.0f, 1.0f,
            1.0f, 1.0f,
    };

    private static class Target {
        String tag;
        Surface surface;
        EGLSurface eglSurface;
        float x, y, w, h;
        int rotation;
        boolean mirror;
    }

    private final int frameWidth;
    private final int frameHeight;

    private HandlerThread renderThread;
    private Handler renderHandler;

    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface pbufferSurface = EGL14.EGL_NO_SURFACE;
    private EGLConfig eglConfig;

    private int program;
    private int oesTextureId;
    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    private int positionHandle;
    private int texCoordHandle;
    private int mvpMatrixHandle;
    private int texMatrixHandle;
    private int textureHandle;
    private int cropHandle;
    private int rotateHandle;
    private int mirrorHandle;

    private final float[] mvpMatrix = new float[16];
    private final float[] texMatrix = new float[16];

    private SurfaceTexture inputSurfaceTexture;
    private Surface inputSurface;

    private final Map<String, Target> targets = new LinkedHashMap<>();

    private volatile boolean initialized = false;
    private volatile boolean released = false;

    public SplitViewRenderer(int frameWidth, int frameHeight) {
        this.frameWidth = Math.max(2, frameWidth);
        this.frameHeight = Math.max(2, frameHeight);
        Matrix.setIdentityM(mvpMatrix, 0);
    }

    /**
     * 初始化 GL 线程与中间 Surface。
     * @return 应作为 Camera2 输出的 Surface
     */
    public Surface initialize() {
        if (initialized) {
            return inputSurface;
        }

        renderThread = new HandlerThread("SplitViewRenderer");
        renderThread.start();
        renderHandler = new Handler(renderThread.getLooper());

        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final Exception[] error = {null};

        renderHandler.post(() -> {
            try {
                initEgl();
                initGl();

                inputSurfaceTexture = new SurfaceTexture(oesTextureId);
                inputSurfaceTexture.setDefaultBufferSize(frameWidth, frameHeight);
                inputSurface = new Surface(inputSurfaceTexture);
                inputSurfaceTexture.setOnFrameAvailableListener(st -> drawFrame(), renderHandler);

                initialized = true;
                AppLog.d(TAG, "Initialized, input " + frameWidth + "x" + frameHeight + ", textureId=" + oesTextureId);
            } catch (Exception e) {
                error[0] = e;
                AppLog.e(TAG, "Failed to initialize", e);
            } finally {
                latch.countDown();
            }
        });

        try {
            if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                throw new RuntimeException("SplitViewRenderer init timeout");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        if (error[0] != null || !initialized) {
            release();
            throw new RuntimeException("SplitViewRenderer init failed", error[0]);
        }
        return inputSurface;
    }

    public SurfaceTexture getInputSurfaceTexture() {
        return inputSurfaceTexture;
    }

    public Surface getInputSurface() {
        return inputSurface;
    }

    public boolean isInitialized() {
        return initialized && !released;
    }

    /**
     * 添加/更新一个输出目标（线程安全）
     */
    public void addTarget(final String tag, final Surface surface,
                          final float x, final float y, final float w, final float h,
                          final int rotation, final boolean mirror) {
        if (tag == null || surface == null || !surface.isValid()) {
            return;
        }
        if (!initialized || released || renderHandler == null) {
            return;
        }
        renderHandler.post(() -> addTargetInternal(tag, surface, x, y, w, h, rotation, mirror, 0));
    }

    private void addTargetInternal(final String tag, final Surface surface,
                                   final float x, final float y, final float w, final float h,
                                   final int rotation, final boolean mirror, final int attempt) {
        if (released || surface == null || !surface.isValid()) {
            return;
        }
        removeTargetInternal(tag);
        try {
            makeCurrent(pbufferSurface);
            int[] attribs = {EGL14.EGL_NONE};
            EGLSurface eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, eglConfig, surface, attribs, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                AppLog.e(TAG, "Failed to create EGL surface for " + tag + " (attempt " + attempt + ")");
                if (attempt < 2 && renderHandler != null) {
                    // 刚创建/切换 Surface 时偶发失败，稍后重试，避免画面永久白屏
                    renderHandler.postDelayed(() -> addTargetInternal(tag, surface, x, y, w, h,
                            rotation, mirror, attempt + 1), 150);
                }
                return;
            }
            Target target = new Target();
            target.tag = tag;
            target.surface = surface;
            target.eglSurface = eglSurface;
            target.x = x;
            target.y = y;
            target.w = w;
            target.h = h;
            target.rotation = rotation;
            target.mirror = mirror;
            targets.put(tag, target);
            AppLog.d(TAG, "Target added: " + tag + " crop=" + x + "," + y + "," + w + "," + h);
        } catch (Exception e) {
            AppLog.e(TAG, "Error adding target " + tag, e);
            if (attempt < 2 && renderHandler != null) {
                renderHandler.postDelayed(() -> addTargetInternal(tag, surface, x, y, w, h,
                        rotation, mirror, attempt + 1), 150);
            }
        }
    }

    public void removeTarget(final String tag) {
        if (renderHandler == null) {
            return;
        }
        renderHandler.post(() -> removeTargetInternal(tag));
    }

    /**
     * 仅更新已有目标的裁剪区域，不重建 EGL 输出（用于保存分割配置后实时生效）
     */
    public void updateTarget(final String tag, final float x, final float y,
                             final float w, final float h, final int rotation, final boolean mirror) {
        if (renderHandler == null) {
            return;
        }
        renderHandler.post(() -> {
            Target target = targets.get(tag);
            if (target != null) {
                target.x = x;
                target.y = y;
                target.w = w;
                target.h = h;
                target.rotation = rotation;
                target.mirror = mirror;
            }
        });
    }

    public void removeAllTargets() {
        if (renderHandler == null) {
            return;
        }
        renderHandler.post(() -> {
            List<String> tags = new ArrayList<>(targets.keySet());
            for (String tag : tags) {
                removeTargetInternal(tag);
            }
        });
    }

    public boolean hasTarget(String tag) {
        return targets.containsKey(tag);
    }

    private void removeTargetInternal(String tag) {
        Target target = targets.remove(tag);
        if (target == null) {
            return;
        }
        if (target.eglSurface != null && target.eglSurface != EGL14.EGL_NO_SURFACE) {
            try {
                makeCurrent(pbufferSurface);
                EGL14.eglDestroySurface(eglDisplay, target.eglSurface);
            } catch (Exception ignored) {
            }
        }
        AppLog.d(TAG, "Target removed: " + tag);
    }

    private void drawFrame() {
        if (!initialized || released) {
            return;
        }
        try {
            makeCurrent(pbufferSurface);

            inputSurfaceTexture.updateTexImage();
            inputSurfaceTexture.getTransformMatrix(texMatrix);

            long timestampNs = inputSurfaceTexture.getTimestamp();
            if (timestampNs <= 0) {
                timestampNs = System.nanoTime();
            }

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId);
            GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);
            GLES20.glUniformMatrix4fv(texMatrixHandle, 1, false, texMatrix, 0);
            GLES20.glUniform1i(textureHandle, 0);
            GLES20.glEnableVertexAttribArray(positionHandle);
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
            GLES20.glEnableVertexAttribArray(texCoordHandle);
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

            Iterator<Map.Entry<String, Target>> iterator = targets.entrySet().iterator();
            while (iterator.hasNext()) {
                Target target = iterator.next().getValue();
                if (target.surface == null || !target.surface.isValid()
                        || target.eglSurface == null || target.eglSurface == EGL14.EGL_NO_SURFACE) {
                    if (target.eglSurface != null && target.eglSurface != EGL14.EGL_NO_SURFACE) {
                        try {
                            EGL14.eglDestroySurface(eglDisplay, target.eglSurface);
                        } catch (Exception ignored) {
                        }
                    }
                    iterator.remove();
                    AppLog.d(TAG, "Auto removed invalid target: " + target.tag);
                    continue;
                }

                if (!EGL14.eglMakeCurrent(eglDisplay, target.eglSurface, target.eglSurface, eglContext)) {
                    continue;
                }
                int[] vw = new int[1];
                int[] vh = new int[1];
                EGL14.eglQuerySurface(eglDisplay, target.eglSurface, EGL14.EGL_WIDTH, vw, 0);
                EGL14.eglQuerySurface(eglDisplay, target.eglSurface, EGL14.EGL_HEIGHT, vh, 0);
                GLES20.glViewport(0, 0, vw[0] > 0 ? vw[0] : frameWidth, vh[0] > 0 ? vh[0] : frameHeight);

                GLES20.glUniform4f(cropHandle, target.x, target.y, target.w, target.h);
                GLES20.glUniform1f(rotateHandle, (target.rotation / 90) % 4);
                GLES20.glUniform1f(mirrorHandle, target.mirror ? 1f : 0f);

                GLES20.glClearColor(0f, 0f, 0f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
                EGLExt.eglPresentationTimeANDROID(eglDisplay, target.eglSurface, timestampNs);
                EGL14.eglSwapBuffers(eglDisplay, target.eglSurface);
            }

            GLES20.glDisableVertexAttribArray(positionHandle);
            GLES20.glDisableVertexAttribArray(texCoordHandle);

            makeCurrent(pbufferSurface);
        } catch (Exception e) {
            AppLog.e(TAG, "drawFrame error", e);
        }
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;

        if (renderHandler != null) {
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            renderHandler.post(() -> {
                try {
                    for (Target target : targets.values()) {
                        if (target.eglSurface != null && target.eglSurface != EGL14.EGL_NO_SURFACE) {
                            try {
                                EGL14.eglDestroySurface(eglDisplay, target.eglSurface);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    targets.clear();

                    if (inputSurfaceTexture != null) {
                        inputSurfaceTexture.setOnFrameAvailableListener(null);
                        inputSurfaceTexture.release();
                        inputSurfaceTexture = null;
                    }
                    if (inputSurface != null) {
                        inputSurface.release();
                        inputSurface = null;
                    }
                    if (program != 0) {
                        GLES20.glDeleteProgram(program);
                        program = 0;
                    }
                    if (oesTextureId != 0) {
                        int[] tex = {oesTextureId};
                        GLES20.glDeleteTextures(1, tex, 0);
                        oesTextureId = 0;
                    }
                    if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                        if (pbufferSurface != EGL14.EGL_NO_SURFACE) {
                            EGL14.eglDestroySurface(eglDisplay, pbufferSurface);
                            pbufferSurface = EGL14.EGL_NO_SURFACE;
                        }
                        if (eglContext != EGL14.EGL_NO_CONTEXT) {
                            EGL14.eglDestroyContext(eglDisplay, eglContext);
                            eglContext = EGL14.EGL_NO_CONTEXT;
                        }
                        EGL14.eglTerminate(eglDisplay);
                        eglDisplay = EGL14.EGL_NO_DISPLAY;
                    }
                } catch (Exception e) {
                    AppLog.e(TAG, "release error", e);
                } finally {
                    latch.countDown();
                }
            });
            try {
                latch.await(3, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        initialized = false;
        if (renderThread != null) {
            renderThread.quitSafely();
            renderThread = null;
            renderHandler = null;
        }
        AppLog.d(TAG, "Released");
    }

    // ===== EGL / GL =====

    private void initEgl() {
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
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
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

        int[] pbufferAttribs = {
                EGL14.EGL_WIDTH, 2,
                EGL14.EGL_HEIGHT, 2,
                EGL14.EGL_NONE
        };
        pbufferSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs, 0);
        if (pbufferSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("Unable to create pbuffer surface");
        }
        makeCurrent(pbufferSurface);
    }

    private void initGl() {
        program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
        if (program == 0) {
            throw new RuntimeException("Unable to create shader program");
        }

        positionHandle = GLES20.glGetAttribLocation(program, "aPosition");
        texCoordHandle = GLES20.glGetAttribLocation(program, "aTextureCoord");
        mvpMatrixHandle = GLES20.glGetUniformLocation(program, "uMVPMatrix");
        texMatrixHandle = GLES20.glGetUniformLocation(program, "uTexMatrix");
        textureHandle = GLES20.glGetUniformLocation(program, "sTexture");
        cropHandle = GLES20.glGetUniformLocation(program, "uCrop");
        rotateHandle = GLES20.glGetUniformLocation(program, "uRotateStep");
        mirrorHandle = GLES20.glGetUniformLocation(program, "uMirror");

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
    }

    private void makeCurrent(EGLSurface surface) {
        if (!EGL14.eglMakeCurrent(eglDisplay, surface, surface, eglContext)) {
            throw new RuntimeException("eglMakeCurrent failed");
        }
    }

    private int createProgram(String vertexSource, String fragmentSource) {
        int vs = loadShader(GLES20.GL_VERTEX_SHADER, vertexSource);
        if (vs == 0) return 0;
        int fs = loadShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
        if (fs == 0) {
            GLES20.glDeleteShader(vs);
            return 0;
        }
        int prog = GLES20.glCreateProgram();
        if (prog == 0) return 0;
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
}
