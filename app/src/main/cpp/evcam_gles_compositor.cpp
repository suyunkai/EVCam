#include <android/native_window_jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <jni.h>

#include <mutex>
#include <chrono>
#include <string>
#include <unordered_map>

#ifndef EGL_RECORDABLE_ANDROID
#define EGL_RECORDABLE_ANDROID 0x3142
#endif

namespace {

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EVCamGLES", __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "EVCamGLES", __VA_ARGS__)

struct Input {
    jobject surfaceTexture = nullptr;
    GLuint texture = 0;
    bool dirty = false;
    bool previewPending = false;
    int64_t dirtyCount = 0;
    int64_t updateCount = 0;
    int64_t frameSignalCount = 0;
    int64_t previewScheduledCount = 0;
    int64_t previewCoalescedCount = 0;
    int64_t previewDelayedCount = 0;
    int64_t previewRenderCount = 0;
    int64_t previewDropCount = 0;
    int64_t previewSwapMs = 0;
    int64_t lastPreviewRenderMs = 0;
    int64_t lastPreviewError = 0;
};

struct Quad {
    GLfloat verts[8] = {0};
    GLfloat tex[8] = {0, 0, 1, 0, 0, 1, 1, 1};
};

struct RecordingState {
    bool recording = false;
    bool segmentSwitchPending = false;
    jlong generation = 0;
    int fps = 15;
    int segmentIndex = 0;
    int pendingSegmentIndex = 0;
    int64_t segmentDurationMs = 60000;
    int64_t nextSegmentWallClockMs = 0;
    int64_t pendingSegmentWallClockMs = 0;
    int64_t requestedFrames = 0;
    int64_t renderedFrames = 0;
    int64_t droppedFrames = 0;
    int64_t lastTickSteadyMs = 0;
};

struct Pipe {
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLConfig config = nullptr;
    EGLSurface pbuffer = EGL_NO_SURFACE;
    EGLSurface previewSurface[4] = {EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_SURFACE};
    EGLSurface encoderSurface = EGL_NO_SURFACE;
    ANativeWindow* previewWindow[4] = {nullptr, nullptr, nullptr, nullptr};
    ANativeWindow* encoderWindow = nullptr;
    jlong encoderGeneration = 0;
    Input input[4];
    Quad encoderQuad[4];
    Quad previewQuad[4];
    int previewQuadWidth[4] = {0, 0, 0, 0};
    int previewQuadHeight[4] = {0, 0, 0, 0};
    int64_t configVersion = 0;
    GLuint program = 0;
    GLint posLoc = -1;
    GLint texLoc = -1;
    GLint samplerLoc = -1;
    GLint fisheyeEnabledLoc = -1;
    GLint k1Loc = -1;
    GLint k2Loc = -1;
    GLint zoomLoc = -1;
    GLint centerLoc = -1;
    int width = 1280;
    int height = 720;
    int layoutMode = 0;
    int sideLeftRotation = 270;
    int sideRightRotation = 90;
    bool overlayEnabled = true;
    int encoderFps = 15;
    bool encoderPending = false;
    RecordingState recording;
    int64_t encoderFrameIndex = 0;
    int64_t encoderSignalCount = 0;
    int64_t encoderScheduledCount = 0;
    int64_t encoderCoalescedCount = 0;
    int64_t renderCount = 0;
    int64_t previewRenderCount = 0;
    int64_t encoderRenderCount = 0;
    int64_t encoderDropCount = 0;
    int64_t droppedCount = 0;
    int64_t noSurfaceCount = 0;
    int64_t lastRenderMs = 0;
    int previewMaxFps = 0;
    int64_t previewMinIntervalMs = 0;
    std::string lastRenderError = "OK";
    bool fisheyeEnabled[4] = {false, false, false, false};
    float fisheyeK1[4] = {0.35f, 0.35f, 0.35f, 0.35f};
    float fisheyeK2[4] = {0.10f, 0.10f, 0.10f, 0.10f};
    float fisheyeZoom[4] = {1.15f, 1.15f, 1.15f, 1.15f};
    float fisheyeCenterX[4] = {0.5f, 0.5f, 0.5f, 0.5f};
    float fisheyeCenterY[4] = {0.5f, 0.5f, 0.5f, 0.5f};
};

std::mutex gLock;
std::unordered_map<jlong, Pipe> gPipes;
jlong gNextHandle = 1;
std::string gLastError = "OK";
jmethodID gUpdateTexImageMethod = nullptr;
using EglPresentationTimeAndroidFn = EGLBoolean (*)(EGLDisplay, EGLSurface, EGLnsecsANDROID);
EglPresentationTimeAndroidFn gPresentationTimeAndroid = nullptr;

void SetError(const std::string& error) {
    gLastError = error;
    LOGE("%s", error.c_str());
}

std::string EglError(const char* what) {
    char buf[128];
    snprintf(buf, sizeof(buf), "%s egl=0x%04x", what, eglGetError());
    return std::string(buf);
}

std::string GlError(const char* what) {
    GLenum err = glGetError();
    if (err == GL_NO_ERROR) return std::string();
    char buf[128];
    snprintf(buf, sizeof(buf), "%s gl=0x%04x", what, err);
    return std::string(buf);
}

void ClearCurrent(EGLDisplay display) {
    if (display != EGL_NO_DISPLAY) {
        eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
    }
}

const char* VERT =
    "attribute vec4 aPosition;"
    "attribute vec2 aTexCoord;"
    "varying vec2 vTexCoord;"
    "void main(){gl_Position=aPosition;vTexCoord=aTexCoord;}";

const char* FRAG =
    "#extension GL_OES_EGL_image_external : require\n"
    "precision mediump float;"
    "varying vec2 vTexCoord;"
    "uniform samplerExternalOES uTexture;"
    "uniform int uFisheyeEnabled;"
    "uniform float uK1;"
    "uniform float uK2;"
    "uniform float uZoom;"
    "uniform vec2 uCenter;"
    "void main(){"
    "  if(uFisheyeEnabled==0){gl_FragColor=texture2D(uTexture,vTexCoord);return;}"
    "  vec2 coord=(vTexCoord-uCenter)/uZoom;"
    "  float r2=dot(coord,coord);"
    "  float r4=r2*r2;"
    "  float distortion=1.0+uK1*r2+uK2*r4;"
    "  vec2 corrected=coord*distortion+uCenter;"
    "  if(corrected.x<0.0||corrected.x>1.0||corrected.y<0.0||corrected.y>1.0){"
    "    gl_FragColor=vec4(0.0,0.0,0.0,1.0);"
    "  }else{"
    "    gl_FragColor=texture2D(uTexture,corrected);"
    "  }"
    "}";

GLuint Compile(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint ok = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetShaderInfoLog(shader, sizeof(log), nullptr, log);
        SetError(std::string("shader compile failed: ") + log);
    }
    return shader;
}

GLuint CreateProgram() {
    GLuint vs = Compile(GL_VERTEX_SHADER, VERT);
    GLuint fs = Compile(GL_FRAGMENT_SHADER, FRAG);
    GLuint program = glCreateProgram();
    glAttachShader(program, vs);
    glAttachShader(program, fs);
    glLinkProgram(program);
    GLint ok = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &ok);
    if (!ok) {
        char log[512] = {0};
        glGetProgramInfoLog(program, sizeof(log), nullptr, log);
        SetError(std::string("program link failed: ") + log);
    }
    glDeleteShader(vs);
    glDeleteShader(fs);
    return program;
}

bool InitEgl(Pipe& p) {
    if (p.display != EGL_NO_DISPLAY) return true;
    p.display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (p.display == EGL_NO_DISPLAY) { SetError("eglGetDisplay failed"); return false; }
    if (!eglInitialize(p.display, nullptr, nullptr)) { SetError(EglError("eglInitialize failed")); return false; }
    if (!gPresentationTimeAndroid) {
        gPresentationTimeAndroid = reinterpret_cast<EglPresentationTimeAndroidFn>(eglGetProcAddress("eglPresentationTimeANDROID"));
    }

    const EGLint attrs[] = {
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
        EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
        EGL_RECORDABLE_ANDROID, 1,
        EGL_NONE
    };
    EGLint count = 0;
    if (!eglChooseConfig(p.display, attrs, &p.config, 1, &count) || count <= 0) {
        SetError(EglError("eglChooseConfig failed"));
        return false;
    }

    const EGLint ctxAttrs[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
    p.context = eglCreateContext(p.display, p.config, EGL_NO_CONTEXT, ctxAttrs);
    if (p.context == EGL_NO_CONTEXT) { SetError(EglError("eglCreateContext failed")); return false; }

    const EGLint pbAttrs[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
    p.pbuffer = eglCreatePbufferSurface(p.display, p.config, pbAttrs);
    if (p.pbuffer == EGL_NO_SURFACE) { SetError(EglError("eglCreatePbufferSurface failed")); return false; }
    if (!eglMakeCurrent(p.display, p.pbuffer, p.pbuffer, p.context)) {
        SetError(EglError("eglMakeCurrent pbuffer failed"));
        return false;
    }
    p.program = CreateProgram();
    p.posLoc = glGetAttribLocation(p.program, "aPosition");
    p.texLoc = glGetAttribLocation(p.program, "aTexCoord");
    p.samplerLoc = glGetUniformLocation(p.program, "uTexture");
    p.fisheyeEnabledLoc = glGetUniformLocation(p.program, "uFisheyeEnabled");
    p.k1Loc = glGetUniformLocation(p.program, "uK1");
    p.k2Loc = glGetUniformLocation(p.program, "uK2");
    p.zoomLoc = glGetUniformLocation(p.program, "uZoom");
    p.centerLoc = glGetUniformLocation(p.program, "uCenter");
    if (p.program == 0 || p.posLoc < 0 || p.texLoc < 0 || p.samplerLoc < 0) {
        SetError("GLES program locations unavailable");
        return false;
    }
    ClearCurrent(p.display);
    LOGD("EGL initialized");
    return true;
}

bool MakePbufferCurrent(Pipe& p) {
    if (!InitEgl(p)) return false;
    if (p.pbuffer == EGL_NO_SURFACE) { SetError("missing pbuffer surface"); return false; }
    if (!eglMakeCurrent(p.display, p.pbuffer, p.pbuffer, p.context)) {
        SetError(EglError("eglMakeCurrent pbuffer failed"));
        return false;
    }
    return true;
}

bool MakeCurrent(Pipe& p, EGLSurface surface) {
    if (!InitEgl(p)) return false;
    if (surface == EGL_NO_SURFACE) { SetError("missing EGL surface"); return false; }
    if (!eglMakeCurrent(p.display, surface, surface, p.context)) {
        SetError(EglError("eglMakeCurrent failed"));
        return false;
    }
    return true;
}

bool UpdateSurfaceTexture(JNIEnv* env, jobject st) {
    if (!gUpdateTexImageMethod) {
        jclass cls = env->GetObjectClass(st);
        gUpdateTexImageMethod = env->GetMethodID(cls, "updateTexImage", "()V");
        env->DeleteLocalRef(cls);
    }
    env->CallVoidMethod(st, gUpdateTexImageMethod);
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
        SetError("SurfaceTexture.updateTexImage threw");
        return false;
    }
    return true;
}

void FillTexCoords(Quad& quad, float rotation) {
    const GLfloat* src = nullptr;
    static const GLfloat r0[] = {0,0, 1,0, 0,1, 1,1};
    static const GLfloat r90[] = {0,1, 0,0, 1,1, 1,0};
    static const GLfloat r270[] = {1,0, 1,1, 0,0, 0,1};
    if (rotation == 90.0f) src = r90;
    else if (rotation == 270.0f) src = r270;
    else src = r0;
    for (int i = 0; i < 8; ++i) quad.tex[i] = src[i];
}

void BuildQuadForCanvas(Quad& quad, float x, float y, float w, float h, float rotation, float canvasW, float canvasH) {
    if (canvasW <= 0.0f) canvasW = 1.0f;
    if (canvasH <= 0.0f) canvasH = 1.0f;
    const float x0 = x / canvasW * 2.0f - 1.0f;
    const float x1 = (x + w) / canvasW * 2.0f - 1.0f;
    const float y0 = 1.0f - y / canvasH * 2.0f;
    const float y1 = 1.0f - (y + h) / canvasH * 2.0f;
    const GLfloat verts[] = { x0, y0, x1, y0, x0, y1, x1, y1 };
    for (int i = 0; i < 8; ++i) quad.verts[i] = verts[i];
    FillTexCoords(quad, rotation);
}

void UpdateEncoderLayout(Pipe& p) {
    BuildQuadForCanvas(p.encoderQuad[0], 0, 0, p.width * 0.5f, p.height * 0.5f, 0, (float)p.width, (float)p.height);
    BuildQuadForCanvas(p.encoderQuad[1], 0, p.height * 0.5f, p.width * 0.5f, p.height * 0.5f, 0, (float)p.width, (float)p.height);
    BuildQuadForCanvas(p.encoderQuad[2], p.width * 0.5f, 0, p.width * 0.25f, p.height, (float)p.sideLeftRotation, (float)p.width, (float)p.height);
    BuildQuadForCanvas(p.encoderQuad[3], p.width * 0.75f, 0, p.width * 0.25f, p.height, (float)p.sideRightRotation, (float)p.width, (float)p.height);
    p.configVersion += 1;
}

void UpdatePreviewLayout(Pipe& p, int index, int width, int height) {
    if (index < 0 || index >= 4) return;
    float rotation = 0.0f;
    if (index == 2) rotation = (float)p.sideLeftRotation;
    if (index == 3) rotation = (float)p.sideRightRotation;
    BuildQuadForCanvas(p.previewQuad[index], 0, 0, (float)width, (float)height, rotation, (float)width, (float)height);
    p.previewQuadWidth[index] = width;
    p.previewQuadHeight[index] = height;
}

void DrawQuad(Pipe& p, int index, const Quad& quad) {
    if (index < 0 || index >= 4) return;
    Input& input = p.input[index];
    if (!input.texture) return;

    glUseProgram(p.program);
    glEnableVertexAttribArray(p.posLoc);
    glEnableVertexAttribArray(p.texLoc);
    glVertexAttribPointer(p.posLoc, 2, GL_FLOAT, GL_FALSE, 0, quad.verts);
    glVertexAttribPointer(p.texLoc, 2, GL_FLOAT, GL_FALSE, 0, quad.tex);
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, input.texture);
    glUniform1i(p.samplerLoc, 0);
    if (p.fisheyeEnabledLoc >= 0) glUniform1i(p.fisheyeEnabledLoc, p.fisheyeEnabled[index] ? 1 : 0);
    if (p.k1Loc >= 0) glUniform1f(p.k1Loc, p.fisheyeK1[index]);
    if (p.k2Loc >= 0) glUniform1f(p.k2Loc, p.fisheyeK2[index]);
    if (p.zoomLoc >= 0) glUniform1f(p.zoomLoc, p.fisheyeZoom[index]);
    if (p.centerLoc >= 0) glUniform2f(p.centerLoc, p.fisheyeCenterX[index], p.fisheyeCenterY[index]);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
}

void DrawOverlay(Pipe& p) {
    if (!p.overlayEnabled) return;
}

int64_t NowMs() {
    return std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now().time_since_epoch()
    ).count();
}

bool RenderPreviewLocked(JNIEnv* env, Pipe& p, int index) {
    if (index < 0 || index >= 4 || !p.input[index].surfaceTexture) return false;
    if (p.previewSurface[index] == EGL_NO_SURFACE) {
        p.input[index].previewDropCount += 1;
        return true;
    }
    auto start = std::chrono::steady_clock::now();
    if (!MakeCurrent(p, p.previewSurface[index])) return false;
    if (p.input[index].dirty) {
        p.input[index].dirtyCount += 1;
        if (!UpdateSurfaceTexture(env, p.input[index].surfaceTexture)) {
            ClearCurrent(p.display);
            return false;
        }
        p.input[index].dirty = false;
        p.input[index].updateCount += 1;
    }
    int vw = p.previewWindow[index] ? ANativeWindow_getWidth(p.previewWindow[index]) : p.width;
    int vh = p.previewWindow[index] ? ANativeWindow_getHeight(p.previewWindow[index]) : p.height;
    if (vw <= 0) vw = p.width;
    if (vh <= 0) vh = p.height;
    if (p.previewQuadWidth[index] != vw || p.previewQuadHeight[index] != vh) {
        UpdatePreviewLayout(p, index, vw, vh);
    }
    glViewport(0, 0, vw, vh);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    DrawQuad(p, index, p.previewQuad[index]);
    DrawOverlay(p);
    std::string glErr = GlError("renderPreview");
    if (!glErr.empty()) { SetError(glErr); ClearCurrent(p.display); return false; }
    if (!eglSwapBuffers(p.display, p.previewSurface[index])) {
        SetError(EglError("eglSwapBuffers preview failed"));
        ClearCurrent(p.display);
        p.input[index].previewDropCount += 1;
        return false;
    }
    ClearCurrent(p.display);
    p.previewRenderCount += 1;
    p.input[index].previewRenderCount += 1;
    p.input[index].previewSwapMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
    p.input[index].lastPreviewRenderMs = NowMs();
    return true;
}

int64_t PreviewDelayMs(const Pipe& p, const Input& input) {
    if (p.previewMinIntervalMs <= 0 || input.lastPreviewRenderMs <= 0) return 0;
    int64_t elapsed = NowMs() - input.lastPreviewRenderMs;
    int64_t remaining = p.previewMinIntervalMs - elapsed;
    return remaining > 0 ? remaining : 0;
}

int64_t FloorToSegment(int64_t wallClockMs, int64_t segmentDurationMs) {
    if (segmentDurationMs <= 0) segmentDurationMs = 60000;
    return wallClockMs - (wallClockMs % segmentDurationMs);
}

int64_t RecordingTickIntervalMs(const RecordingState& recording) {
    int fps = recording.fps <= 0 ? 15 : recording.fps;
    return 1000 / fps;
}

bool RequestEncoderRenderLocked(Pipe& p) {
    p.encoderSignalCount += 1;
    if (p.encoderSurface == EGL_NO_SURFACE || p.encoderWindow == nullptr || p.encoderGeneration == 0) {
        p.encoderDropCount += 1;
        p.noSurfaceCount += 1;
        return false;
    }
    if (p.encoderPending) {
        p.encoderCoalescedCount += 1;
        return false;
    }
    p.encoderPending = true;
    p.encoderScheduledCount += 1;
    return true;
}

bool HasDirtyInput(const Pipe& p) {
    for (int i = 0; i < 4; ++i) {
        if (p.input[i].surfaceTexture && p.input[i].dirty) return true;
    }
    return false;
}

bool UpdateDirtyInputsLocked(JNIEnv* env, Pipe& p) {
    for (int i = 0; i < 4; ++i) {
        if (p.input[i].surfaceTexture && p.input[i].dirty) {
            p.input[i].dirtyCount += 1;
            if (!UpdateSurfaceTexture(env, p.input[i].surfaceTexture)) return false;
            p.input[i].dirty = false;
            p.input[i].updateCount += 1;
        }
    }
    return true;
}

bool RenderEncoderLocked(JNIEnv* env, Pipe& p, bool requireDirty, bool* rendered = nullptr) {
    if (rendered) *rendered = false;
    if (p.encoderSurface == EGL_NO_SURFACE || p.encoderWindow == nullptr || p.encoderGeneration == 0) {
        p.encoderDropCount += 1;
        p.noSurfaceCount += 1;
        return true;
    }
    if (requireDirty && !HasDirtyInput(p)) {
        p.encoderDropCount += 1;
        return true;
    }
    auto start = std::chrono::steady_clock::now();
    if (!MakeCurrent(p, p.encoderSurface)) return false;
    if (!UpdateDirtyInputsLocked(env, p)) {
        ClearCurrent(p.display);
        return false;
    }
    p.renderCount += 1;
    glViewport(0, 0, p.width, p.height);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    DrawQuad(p, 0, p.encoderQuad[0]);
    DrawQuad(p, 1, p.encoderQuad[1]);
    DrawQuad(p, 2, p.encoderQuad[2]);
    DrawQuad(p, 3, p.encoderQuad[3]);
    DrawOverlay(p);
    std::string glErr = GlError("renderEncoder");
    if (!glErr.empty()) { SetError(glErr); ClearCurrent(p.display); return false; }
    int fps = p.encoderFps <= 0 ? 15 : p.encoderFps;
    if (gPresentationTimeAndroid) gPresentationTimeAndroid(p.display, p.encoderSurface, (p.encoderFrameIndex++ * 1000000000LL) / fps);
    bool ok = eglSwapBuffers(p.display, p.encoderSurface);
    ClearCurrent(p.display);
    p.lastRenderMs = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - start).count();
    if (ok) {
        p.encoderRenderCount += 1;
        p.lastRenderError = "OK";
        if (rendered) *rendered = true;
    } else {
        p.encoderDropCount += 1;
        p.lastRenderError = EglError("eglSwapBuffers encoder failed");
        SetError(p.lastRenderError);
    }
    return ok;
}

Pipe* Get(jlong handle) {
    auto it = gPipes.find(handle);
    if (it == gPipes.end()) { SetError("invalid native handle"); return nullptr; }
    return &it->second;
}

}

extern "C" JNIEXPORT jstring JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getNativeVersion(JNIEnv* env, jobject) {
    return env->NewStringUTF("gles-oes-low-1");
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_isVulkanAvailable(JNIEnv*, jobject) {
    return JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getVulkanSummary(JNIEnv* env, jobject) {
    return env->NewStringUTF("GLES/OES native compositor");
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_setCompositeConfig(JNIEnv*, jobject, jlong handle, jint width, jint height, jint sideLeftRotation, jint sideRightRotation, jint layoutMode) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    p->width = width;
    p->height = height;
    p->sideLeftRotation = sideLeftRotation;
    p->sideRightRotation = sideRightRotation;
    p->layoutMode = layoutMode;
    UpdateEncoderLayout(*p);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_setCompositorRuntimeConfig(JNIEnv* env, jobject, jlong handle, jint width, jint height, jint previewFps, jint encoderFps, jint sideLeftRotation, jint sideRightRotation, jint layoutMode, jbooleanArray fisheyeEnabled, jfloatArray k1, jfloatArray k2, jfloatArray zoom, jfloatArray centerX, jfloatArray centerY) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    p->width = width;
    p->height = height;
    p->sideLeftRotation = sideLeftRotation;
    p->sideRightRotation = sideRightRotation;
    p->layoutMode = layoutMode;
    if (previewFps <= 0) {
        p->previewMaxFps = 0;
        p->previewMinIntervalMs = 0;
    } else {
        p->previewMaxFps = previewFps < 1 ? 1 : (previewFps > 120 ? 120 : previewFps);
        p->previewMinIntervalMs = 1000 / p->previewMaxFps;
    }
    p->encoderFps = encoderFps < 1 ? 1 : (encoderFps > 120 ? 120 : encoderFps);
    if (fisheyeEnabled && k1 && k2 && zoom && centerX && centerY &&
        env->GetArrayLength(fisheyeEnabled) >= 4 && env->GetArrayLength(k1) >= 4 && env->GetArrayLength(k2) >= 4 &&
        env->GetArrayLength(zoom) >= 4 && env->GetArrayLength(centerX) >= 4 && env->GetArrayLength(centerY) >= 4) {
        jboolean* enabled = env->GetBooleanArrayElements(fisheyeEnabled, nullptr);
        jfloat* k1v = env->GetFloatArrayElements(k1, nullptr);
        jfloat* k2v = env->GetFloatArrayElements(k2, nullptr);
        jfloat* zoomv = env->GetFloatArrayElements(zoom, nullptr);
        jfloat* cx = env->GetFloatArrayElements(centerX, nullptr);
        jfloat* cy = env->GetFloatArrayElements(centerY, nullptr);
        if (enabled && k1v && k2v && zoomv && cx && cy) {
            for (int i = 0; i < 4; ++i) {
                p->fisheyeEnabled[i] = enabled[i] == JNI_TRUE;
                p->fisheyeK1[i] = k1v[i];
                p->fisheyeK2[i] = k2v[i];
                p->fisheyeZoom[i] = zoomv[i] <= 0.01f ? 1.0f : zoomv[i];
                p->fisheyeCenterX[i] = cx[i];
                p->fisheyeCenterY[i] = cy[i];
            }
        }
        if (enabled) env->ReleaseBooleanArrayElements(fisheyeEnabled, enabled, JNI_ABORT);
        if (k1v) env->ReleaseFloatArrayElements(k1, k1v, JNI_ABORT);
        if (k2v) env->ReleaseFloatArrayElements(k2, k2v, JNI_ABORT);
        if (zoomv) env->ReleaseFloatArrayElements(zoom, zoomv, JNI_ABORT);
        if (cx) env->ReleaseFloatArrayElements(centerX, cx, JNI_ABORT);
        if (cy) env->ReleaseFloatArrayElements(centerY, cy, JNI_ABORT);
    }
    UpdateEncoderLayout(*p);
    for (int i = 0; i < 4; ++i) {
        if (p->previewQuadWidth[i] > 0 && p->previewQuadHeight[i] > 0) UpdatePreviewLayout(*p, i, p->previewQuadWidth[i], p->previewQuadHeight[i]);
    }
    LOGD("runtime config size=%dx%d previewFps=%d encoderFps=%d config=%lld", p->width, p->height, p->previewMaxFps, p->encoderFps, (long long)p->configVersion);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_setPreviewMaxFps(JNIEnv*, jobject, jlong handle, jint fps) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    if (fps <= 0) {
        p->previewMaxFps = 0;
        p->previewMinIntervalMs = 0;
    } else {
        p->previewMaxFps = fps < 1 ? 1 : (fps > 120 ? 120 : fps);
        p->previewMinIntervalMs = 1000 / p->previewMaxFps;
    }
    LOGD("preview max fps=%d minIntervalMs=%lld", p->previewMaxFps, (long long)p->previewMinIntervalMs);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_setEncoderFps(JNIEnv*, jobject, jlong handle, jint fps) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    p->encoderFps = fps < 1 ? 1 : (fps > 120 ? 120 : fps);
    LOGD("encoder fps=%d", p->encoderFps);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_startRecordingSession(JNIEnv*, jobject, jlong handle, jint fps, jlong segmentDurationMs, jlong wallClockMs) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return 0;
    p->recording.recording = true;
    p->recording.generation += 1;
    p->recording.fps = fps < 1 ? 1 : (fps > 120 ? 120 : fps);
    p->encoderFps = p->recording.fps;
    p->recording.segmentDurationMs = segmentDurationMs <= 0 ? 60000 : segmentDurationMs;
    p->recording.segmentIndex = 0;
    p->recording.pendingSegmentIndex = 0;
    p->recording.segmentSwitchPending = false;
    p->recording.pendingSegmentWallClockMs = 0;
    p->recording.requestedFrames = 0;
    p->recording.renderedFrames = 0;
    p->recording.droppedFrames = 0;
    p->recording.lastTickSteadyMs = 0;
    p->encoderSignalCount = 0;
    p->encoderScheduledCount = 0;
    p->encoderCoalescedCount = 0;
    int64_t firstSegmentMs = FloorToSegment(wallClockMs, p->recording.segmentDurationMs);
    p->recording.nextSegmentWallClockMs = firstSegmentMs + p->recording.segmentDurationMs;
    p->encoderPending = false;
    return firstSegmentMs;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_stopRecordingSession(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    p->recording.recording = false;
    p->recording.segmentSwitchPending = false;
    p->recording.generation += 1;
    p->encoderPending = false;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_requestRecordingTick(JNIEnv*, jobject, jlong handle, jlong wallClockMs) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p || !p->recording.recording) return 0;
    p->recording.lastTickSteadyMs = NowMs();
    p->recording.requestedFrames += 1;
    jlong flags = 0;
    if (RequestEncoderRenderLocked(*p)) {
        flags |= 1L;
    } else {
        p->recording.droppedFrames += 1;
        flags |= 2L;
    }
    if (wallClockMs >= p->recording.nextSegmentWallClockMs && !p->recording.segmentSwitchPending) {
        p->recording.segmentSwitchPending = true;
        p->recording.pendingSegmentIndex = p->recording.segmentIndex + 1;
        p->recording.pendingSegmentWallClockMs = p->recording.nextSegmentWallClockMs;
        flags |= 4L;
        flags |= ((jlong)p->recording.pendingSegmentIndex << 32);
    }
    return flags;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getRecordingNextTickDelayMs(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p || !p->recording.recording) return -1;
    int64_t intervalMs = RecordingTickIntervalMs(p->recording);
    if (p->recording.lastTickSteadyMs <= 0) return 0;
    int64_t elapsedMs = NowMs() - p->recording.lastTickSteadyMs;
    int64_t remainingMs = intervalMs - elapsedMs;
    return remainingMs > 0 ? remainingMs : 0;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_beginNextRecordingSegment(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return 0;
    return p->recording.segmentSwitchPending ? p->recording.pendingSegmentWallClockMs : p->recording.nextSegmentWallClockMs;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_completeRecordingSegmentSwitch(JNIEnv*, jobject, jlong handle, jboolean success) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    if (!p->recording.segmentSwitchPending) return JNI_TRUE;
    if (success == JNI_TRUE) {
        p->recording.segmentIndex = p->recording.pendingSegmentIndex;
        p->recording.nextSegmentWallClockMs = p->recording.pendingSegmentWallClockMs + p->recording.segmentDurationMs;
    }
    p->recording.segmentSwitchPending = false;
    p->recording.pendingSegmentIndex = 0;
    p->recording.pendingSegmentWallClockMs = 0;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_markRecordingFrameRendered(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    p->recording.renderedFrames += 1;
    return JNI_TRUE;
}


extern "C" JNIEXPORT jstring JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getMetrics(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return env->NewStringUTF("invalid");
    char buf[1024];
    snprintf(buf, sizeof(buf), "r=%lld p=%lld e=%lld ed=%lld ns=%lld ms=%lld rec[%lld/%lld/%lld/%d] enc[%lld/%lld/%lld] pfps=%d/%lld cfg=%lld err=%s i0[%lld/%lld/%lld/%lld/%lld/%lld/%lld] i1[%lld/%lld/%lld/%lld/%lld/%lld/%lld] i2[%lld/%lld/%lld/%lld/%lld/%lld/%lld] i3[%lld/%lld/%lld/%lld/%lld/%lld/%lld]",
             (long long)p->renderCount, (long long)p->previewRenderCount, (long long)p->encoderRenderCount,
             (long long)p->encoderDropCount, (long long)p->noSurfaceCount, (long long)p->lastRenderMs,
             (long long)p->recording.requestedFrames, (long long)p->recording.renderedFrames, (long long)p->recording.droppedFrames, p->recording.segmentIndex,
             (long long)p->encoderSignalCount, (long long)p->encoderScheduledCount, (long long)p->encoderCoalescedCount,
             p->previewMaxFps, (long long)p->previewMinIntervalMs, (long long)p->configVersion, p->lastRenderError.c_str(),
             (long long)p->input[0].frameSignalCount, (long long)p->input[0].previewScheduledCount, (long long)p->input[0].previewDelayedCount, (long long)p->input[0].previewCoalescedCount, (long long)p->input[0].updateCount, (long long)p->input[0].previewRenderCount, (long long)p->input[0].previewDropCount,
             (long long)p->input[1].frameSignalCount, (long long)p->input[1].previewScheduledCount, (long long)p->input[1].previewDelayedCount, (long long)p->input[1].previewCoalescedCount, (long long)p->input[1].updateCount, (long long)p->input[1].previewRenderCount, (long long)p->input[1].previewDropCount,
             (long long)p->input[2].frameSignalCount, (long long)p->input[2].previewScheduledCount, (long long)p->input[2].previewDelayedCount, (long long)p->input[2].previewCoalescedCount, (long long)p->input[2].updateCount, (long long)p->input[2].previewRenderCount, (long long)p->input[2].previewDropCount,
             (long long)p->input[3].frameSignalCount, (long long)p->input[3].previewScheduledCount, (long long)p->input[3].previewDelayedCount, (long long)p->input[3].previewCoalescedCount, (long long)p->input[3].updateCount, (long long)p->input[3].previewRenderCount, (long long)p->input[3].previewDropCount);
    return env->NewStringUTF(buf);
}

extern "C" JNIEXPORT jlongArray JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getMetricsSnapshot(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    jlong values[48] = {0};
    if (p) {
        values[0] = p->previewRenderCount;
        values[1] = p->encoderRenderCount;
        values[2] = p->encoderDropCount;
        values[3] = p->noSurfaceCount;
        values[4] = p->lastRenderMs;
        values[5] = p->recording.requestedFrames;
        values[6] = p->recording.renderedFrames;
        values[7] = p->recording.droppedFrames;
        values[8] = p->recording.segmentIndex;
        values[9] = p->recording.segmentSwitchPending ? 1 : 0;
        values[10] = p->recording.pendingSegmentIndex;
        values[11] = p->recording.nextSegmentWallClockMs;
        values[12] = p->encoderSignalCount;
        values[13] = p->encoderScheduledCount;
        values[14] = p->encoderCoalescedCount;
        values[15] = p->previewMaxFps;
        values[16] = p->previewMinIntervalMs;
        values[17] = p->configVersion;
        values[18] = p->encoderPending ? 1 : 0;
        values[19] = p->recording.generation;
        for (int i = 0; i < 4; ++i) {
            int base = 20 + i * 7;
            values[base] = p->input[i].frameSignalCount;
            values[base + 1] = p->input[i].previewScheduledCount;
            values[base + 2] = p->input[i].previewDelayedCount;
            values[base + 3] = p->input[i].previewCoalescedCount;
            values[base + 4] = p->input[i].updateCount;
            values[base + 5] = p->input[i].previewRenderCount;
            values[base + 6] = p->input[i].previewDropCount;
        }
    }
    jlongArray result = env->NewLongArray(48);
    if (result) env->SetLongArrayRegion(result, 0, 48, values);
    return result;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_createCompositor(JNIEnv*, jobject, jint width, jint height) {
    std::lock_guard<std::mutex> lock(gLock);
    jlong handle = gNextHandle++;
    Pipe& p = gPipes[handle];
    p.width = width;
    p.height = height;
    if (!InitEgl(p)) {
        gPipes.erase(handle);
        return 0;
    }
    UpdateEncoderLayout(p);
    return handle;
}

extern "C" JNIEXPORT jint JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_createOesTexture(JNIEnv*, jobject, jlong handle, jint index) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p || index < 0 || index >= 4 || !InitEgl(*p)) return 0;
    if (!MakePbufferCurrent(*p)) return 0;
    GLuint tex = 0;
    glGenTextures(1, &tex);
    if (!tex) { SetError("glGenTextures returned 0"); return 0; }
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    std::string glErr = GlError("createOesTexture");
    if (!glErr.empty()) { SetError(glErr); glDeleteTextures(1, &tex); ClearCurrent(p->display); return 0; }
    p->input[index].texture = tex;
    ClearCurrent(p->display);
    LOGD("created OES texture index=%d tex=%u", index, tex);
    return static_cast<jint>(tex);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_createOesInput(JNIEnv* env, jobject, jlong handle, jint index, jobject surfaceTexture) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p || index < 0 || index >= 4) return JNI_FALSE;
    if (p->input[index].surfaceTexture) env->DeleteGlobalRef(p->input[index].surfaceTexture);
    p->input[index].surfaceTexture = env->NewGlobalRef(surfaceTexture);
    p->input[index].dirty = true;
    LOGD("created OES input index=%d", index);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_attachPreviewSurface(JNIEnv* env, jobject, jlong handle, jint index, jobject surface) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p || index < 0 || index >= 4 || !InitEgl(*p)) return JNI_FALSE;
    if (p->previewSurface[index] != EGL_NO_SURFACE) {
        eglDestroySurface(p->display, p->previewSurface[index]);
        p->previewSurface[index] = EGL_NO_SURFACE;
    }
    if (p->previewWindow[index]) ANativeWindow_release(p->previewWindow[index]);
    p->previewWindow[index] = ANativeWindow_fromSurface(env, surface);
    if (!p->previewWindow[index]) { SetError("preview window unavailable"); return JNI_FALSE; }
    p->previewSurface[index] = eglCreateWindowSurface(p->display, p->config, p->previewWindow[index], nullptr);
    if (p->previewSurface[index] == EGL_NO_SURFACE) {
        SetError(EglError("eglCreateWindowSurface preview failed"));
        ANativeWindow_release(p->previewWindow[index]);
        p->previewWindow[index] = nullptr;
        return JNI_FALSE;
    }
    UpdatePreviewLayout(*p, index, ANativeWindow_getWidth(p->previewWindow[index]), ANativeWindow_getHeight(p->previewWindow[index]));
    LOGD("attached preview surface index=%d size=%dx%d", index, ANativeWindow_getWidth(p->previewWindow[index]), ANativeWindow_getHeight(p->previewWindow[index]));
    return p->previewSurface[index] != EGL_NO_SURFACE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_detachPreviewSurface(JNIEnv*, jobject, jlong handle, jint index) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p || index < 0 || index >= 4) return JNI_FALSE;
    if (p->previewSurface[index] != EGL_NO_SURFACE) { eglDestroySurface(p->display, p->previewSurface[index]); p->previewSurface[index] = EGL_NO_SURFACE; }
    if (p->previewWindow[index]) { ANativeWindow_release(p->previewWindow[index]); p->previewWindow[index] = nullptr; }
    p->input[index].previewPending = false;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_setFisheyeCorrection(JNIEnv*, jobject, jlong handle, jboolean enabled, jfloat k1, jfloat k2, jfloat zoom, jfloat centerX, jfloat centerY) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    for (int i = 0; i < 4; ++i) {
        p->fisheyeEnabled[i] = enabled == JNI_TRUE;
        p->fisheyeK1[i] = k1;
        p->fisheyeK2[i] = k2;
        p->fisheyeZoom[i] = zoom <= 0.01f ? 1.0f : zoom;
        p->fisheyeCenterX[i] = centerX;
        p->fisheyeCenterY[i] = centerY;
    }
    LOGD("fisheye all enabled=%d k1=%f k2=%f zoom=%f center=%f,%f", enabled == JNI_TRUE ? 1 : 0, k1, k2, zoom <= 0.01f ? 1.0f : zoom, centerX, centerY);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_setFisheyeCorrectionForCamera(JNIEnv*, jobject, jlong handle, jint index, jboolean enabled, jfloat k1, jfloat k2, jfloat zoom, jfloat centerX, jfloat centerY) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p || index < 0 || index >= 4) return JNI_FALSE;
    p->fisheyeEnabled[index] = enabled == JNI_TRUE;
    p->fisheyeK1[index] = k1;
    p->fisheyeK2[index] = k2;
    p->fisheyeZoom[index] = zoom <= 0.01f ? 1.0f : zoom;
    p->fisheyeCenterX[index] = centerX;
    p->fisheyeCenterY[index] = centerY;
    LOGD("fisheye index=%d enabled=%d k1=%f k2=%f zoom=%f center=%f,%f", index, p->fisheyeEnabled[index] ? 1 : 0, p->fisheyeK1[index], p->fisheyeK2[index], p->fisheyeZoom[index], p->fisheyeCenterX[index], p->fisheyeCenterY[index]);
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_attachEncoderSurface(JNIEnv* env, jobject, jlong handle, jobject surface) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p || !InitEgl(*p)) return JNI_FALSE;
    if (p->encoderSurface != EGL_NO_SURFACE) { eglDestroySurface(p->display, p->encoderSurface); p->encoderSurface = EGL_NO_SURFACE; }
    if (p->encoderWindow) ANativeWindow_release(p->encoderWindow);
    p->encoderWindow = ANativeWindow_fromSurface(env, surface);
    if (!p->encoderWindow) { SetError("encoder window unavailable"); return JNI_FALSE; }
    p->encoderSurface = eglCreateWindowSurface(p->display, p->config, p->encoderWindow, nullptr);
    if (p->encoderSurface == EGL_NO_SURFACE) {
        SetError(EglError("eglCreateWindowSurface encoder failed"));
        ANativeWindow_release(p->encoderWindow);
        p->encoderWindow = nullptr;
        return JNI_FALSE;
    }
    p->encoderFrameIndex = 0;
    p->encoderPending = false;
    p->encoderGeneration += 1;
    return p->encoderSurface != EGL_NO_SURFACE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_detachEncoderSurface(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    if (p->encoderSurface != EGL_NO_SURFACE) { eglDestroySurface(p->display, p->encoderSurface); p->encoderSurface = EGL_NO_SURFACE; }
    if (p->encoderWindow) { ANativeWindow_release(p->encoderWindow); p->encoderWindow = nullptr; }
    p->encoderGeneration = 0;
    p->encoderFrameIndex = 0;
    p->encoderPending = false;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_renderPreview(JNIEnv* env, jobject, jlong handle, jint index) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    return RenderPreviewLocked(env, *p, index) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_requestPreviewRender(JNIEnv*, jobject, jlong handle, jint index) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p || index < 0 || index >= 4) return -1;
    Input& input = p->input[index];
    input.frameSignalCount += 1;
    input.dirty = true;
    if (!input.surfaceTexture || p->previewSurface[index] == EGL_NO_SURFACE) {
        input.previewDropCount += 1;
        return -1;
    }
    if (input.previewPending) {
        input.previewCoalescedCount += 1;
        return -1;
    }
    input.previewPending = true;
    input.previewScheduledCount += 1;
    int64_t delayMs = PreviewDelayMs(*p, input);
    if (delayMs > 0) input.previewDelayedCount += 1;
    return delayMs;
}

extern "C" JNIEXPORT jlong JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_signalPreviewFrame(JNIEnv* env, jobject obj, jlong handle, jint index) {
    return Java_com_kooo_evcam_v2_nativebridge_VulkanNative_requestPreviewRender(env, obj, handle, index);
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_renderScheduledPreview(JNIEnv* env, jobject, jlong handle, jint index) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p || index < 0 || index >= 4) return JNI_FALSE;
    Input& input = p->input[index];
    if (!input.previewPending) return JNI_TRUE;
    bool ok = RenderPreviewLocked(env, *p, index);
    input.previewPending = false;
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_requestEncoderRender(JNIEnv*, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    return RequestEncoderRenderLocked(*p) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_renderScheduledEncoder(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    if (!p->encoderPending) return JNI_TRUE;
    bool rendered = false;
    bool ok = RenderEncoderLocked(env, *p, true, &rendered);
    p->encoderPending = false;
    if (ok && rendered) p->recording.renderedFrames += 1;
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jint JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_renderScheduledEncoderResult(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return -1;
    if (!p->encoderPending) return 0;
    bool rendered = false;
    bool ok = RenderEncoderLocked(env, *p, true, &rendered);
    p->encoderPending = false;
    if (!ok) return -1;
    if (rendered) {
        p->recording.renderedFrames += 1;
        return 1;
    }
    return 0;
}

extern "C" JNIEXPORT jboolean JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_renderCompositor(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    Pipe* p = Get(handle);
    if (!p) return JNI_FALSE;
    return RenderEncoderLocked(env, *p, false) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_releaseCompositor(JNIEnv* env, jobject, jlong handle) {
    std::lock_guard<std::mutex> lock(gLock);
    auto it = gPipes.find(handle);
    if (it == gPipes.end()) return;
    Pipe& p = it->second;
    if (p.display != EGL_NO_DISPLAY) {
        MakePbufferCurrent(p);
        for (int i = 0; i < 4; ++i) {
            if (p.previewSurface[i] != EGL_NO_SURFACE) eglDestroySurface(p.display, p.previewSurface[i]);
            if (p.input[i].texture) glDeleteTextures(1, &p.input[i].texture);
        }
        if (p.encoderSurface != EGL_NO_SURFACE) eglDestroySurface(p.display, p.encoderSurface);
        if (p.program) glDeleteProgram(p.program);
        ClearCurrent(p.display);
        if (p.pbuffer != EGL_NO_SURFACE) eglDestroySurface(p.display, p.pbuffer);
        if (p.context != EGL_NO_CONTEXT) eglDestroyContext(p.display, p.context);
        eglTerminate(p.display);
    }
    for (int i = 0; i < 4; ++i) {
        if (p.input[i].surfaceTexture) env->DeleteGlobalRef(p.input[i].surfaceTexture);
        if (p.previewWindow[i]) ANativeWindow_release(p.previewWindow[i]);
    }
    if (p.encoderWindow) ANativeWindow_release(p.encoderWindow);
    gPipes.erase(it);
}

extern "C" JNIEXPORT jstring JNICALL Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getLastError(JNIEnv* env, jobject) {
    return env->NewStringUTF(gLastError.c_str());
}
