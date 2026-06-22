package com.kooo.evcam.stream;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;

import com.kooo.evcam.AppConfig;
import com.kooo.evcam.AppLog;
import com.kooo.evcam.BlindSpotService;
import com.kooo.evcam.camera.MultiCameraManager;
import com.kooo.evcam.camera.SingleCamera;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MJPEG 流生命周期管理器。
 * <p>
 * 职责：
 * <ul>
 *   <li>启动/停止 {@link MjpegStreamServer}（NanoHTTPD MJPEG 服务）</li>
 *   <li>创建 {@link StreamGlEncoder}（独立 GL 管线）并挂载到目标 {@link SingleCamera}</li>
 *   <li>把 GL 读回的 RGBA → YUV420 → JPEG，写入 {@link MjpegFrameHolder}</li>
 *   <li>无客户端时暂停编码以省 CPU</li>
 *   <li>联动模式：跟随转向灯/车门补盲信号自动切换摄像头</li>
 *   <li>参数热更新（分辨率/质量/pan/cover/鱼眼），从 {@link AppConfig} 读取</li>
 * </ul>
 * <p>
 * 独立于副屏运行：副屏开关与 MJPEG 流互不影响。
 */
public class MjpegStreamManager {
    private static final String TAG = "MjpegStreamManager";

    /** 静态单例：App 全局只一个，便于从 MainActivity / CameraForegroundService 自动拉起。 */
    private static volatile MjpegStreamManager sInstance;

    /**
     * 确保已配置开启的 MJPEG 流处于运行状态。幂等。
     * <p>调用时机：
     * <ul>
     *   <li>{@code MainActivity} 相机就绪后</li>
     *   <li>{@code CameraForegroundService.onStartCommand} 自恢复时</li>
     *   <li>相机修复循环（{@code startCameraRepairLoop}）每 10 秒一次</li>
     * </ul>
     * 若配置未开启或已在运行，直接返回；否则创建并启动。
     */
    public static void ensureRunning(Context context) {
        try {
            AppConfig cfg = new AppConfig(context);
            if (!cfg.isMjpegStreamEnabled()) return;
            MjpegStreamManager inst = sInstance;
            if (inst != null && inst.isRunning()) {
                // 已运行：联动模式下若摄像头管理器变了（如重建），刷新一下引用
                return;
            }
            // 需要启动：等相机管理器就绪
            com.kooo.evcam.camera.MultiCameraManager cm =
                    com.kooo.evcam.camera.CameraManagerHolder.getInstance().getCameraManager();
            if (cm == null) {
                AppLog.d(TAG, "ensureRunning: camera manager not ready yet, will retry");
                return;
            }
            String cam = cfg.getMjpegStreamCamera();
            AppLog.i(TAG, "ensureRunning: auto-starting MJPEG stream, camera=" + cam);
            MjpegStreamManager m = new MjpegStreamManager(context, cfg, cam);
            String url = m.start(cm);
            if (url != null) {
                sInstance = m;
            } else {
                AppLog.w(TAG, "ensureRunning: start failed, will retry later");
            }
        } catch (Exception e) {
            AppLog.e(TAG, "ensureRunning error: " + e.getMessage(), e);
        }
    }

    /** 显式停止并释放单例（用户在 UI 关闭开关时调用）。 */
    public static void stopInstance() {
        MjpegStreamManager inst = sInstance;
        if (inst != null) {
            inst.stop();
            sInstance = null;
        }
    }

    /** 获取运行中的单例（可能为 null）。 */
    public static MjpegStreamManager getInstance() {
        return sInstance;
    }

    /** UI 手动启动时注册为单例。 */
    public static void setInstanceForUi(MjpegStreamManager m) {
        sInstance = m;
    }

    private final Context context;
    private final AppConfig appConfig;
    private final String defaultCameraPosition;  // 配置的默认摄像头（联动空闲时用）

    private volatile FrameStreamServer server;   // 当前使用的传输端（server 或 client）
    private MjpegFrameHolder frameHolder;
    private StreamGlEncoder glEncoder;
    private SingleCamera targetCamera;
    private String currentCameraPosition;  // 当前实际绑定的摄像头
    private JpegEncoderThread encoderThread;
    private MultiCameraManager multiCameraManager;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile int currentWidth;
    private volatile int currentHeight;

    // 联动模式
    private volatile boolean linkageMode;
    private volatile String lastActiveCamera = null;
    private android.os.Handler pollHandler;
    private Runnable linkagePollRunnable;
    private volatile boolean discoveryRunning = false;

    /** 客户端数变化回调（用于 UI 显示 + 决定是否暂停编码）。只在数量变化时触发。 */
    public interface ClientListener {
        void onClientCountChanged(int count);
    }
    private ClientListener clientListener;
    private int lastClientCount = -1;

    public MjpegStreamManager(Context context, AppConfig appConfig, String cameraPosition) {
        this.context = context.getApplicationContext();
        this.appConfig = appConfig;
        this.defaultCameraPosition = cameraPosition;
        this.currentCameraPosition = cameraPosition;
        this.linkageMode = appConfig.isMjpegStreamLinkageMode();
    }

    public void setClientListener(ClientListener l) {
        this.clientListener = l;
    }

    public boolean isRunning() {
        return running.get();
    }

    /** 按协议创建对应的服务器实例。 */
    private FrameStreamServer createServer(String proto, int port, MjpegFrameHolder holder) throws IOException {
        if (proto == null) proto = "HTTP";
        switch (proto.toUpperCase()) {
            case "TCP":
                return new RawTcpStreamServer(port, holder);
            case "UDP":
                return new UdpStreamServer(port, holder);
            case "HTTP":
            default:
                return new MjpegStreamServer(port, holder);
        }
    }

    private static boolean isClientMode(String mode) {
        return "CLIENT".equalsIgnoreCase(mode);
    }

    private static String normalizeProtocolForMode(String proto, String mode) {
        String p = proto == null ? "HTTP" : proto.trim().toUpperCase();
        if ("RTP".equals(p)) return isClientMode(mode) ? "RTP" : "HTTP";
        if ("UDP".equals(p)) return "UDP";
        if ("TCP".equals(p)) return "TCP";
        return isClientMode(mode) ? "TCP" : "HTTP";
    }

    public int getCurrentWidth() { return currentWidth; }
    public int getCurrentHeight() { return currentHeight; }
    public String getCurrentCameraPosition() { return currentCameraPosition; }

    /**
     * 启动 MJPEG 流。
     * @return 启动成功返回访问 URL，失败返回 null
     */
    public String start(MultiCameraManager multiCameraManager) {
        if (running.get()) {
            AppLog.w(TAG, "already running");
            return getAccessUrl();
        }
        this.multiCameraManager = multiCameraManager;
        if (multiCameraManager == null) {
            AppLog.e(TAG, "MultiCameraManager is null");
            return null;
        }

        // 联动模式：初始有补盲信号才绑摄像头；无信号则不推流
        if (linkageMode) {
            String active = BlindSpotService.getActiveBlindSpotCamera();
            if (isValidCameraPos(active, multiCameraManager)) {
                currentCameraPosition = active;
            } else {
                currentCameraPosition = defaultCameraPosition;
                targetCamera = null;  // 联动模式无信号时不绑摄像头
                AppLog.i(TAG, "linkage mode, no active signal, stream idle (no camera bound)");
            }
        } else {
            targetCamera = multiCameraManager.getCamera(currentCameraPosition);
        }

        if (targetCamera == null && !linkageMode) {
            AppLog.e(TAG, "camera not found: " + currentCameraPosition);
            return null;
        }

        int port = appConfig.getMjpegStreamPort();
        int w = appConfig.getMjpegStreamWidth();
        int h = appConfig.getMjpegStreamHeight();
        currentWidth = w;
        currentHeight = h;

        // 1. 起帧 holder + 按模式/协议创建服务器或客户端
        frameHolder = new MjpegFrameHolder();
        String mode = appConfig.getMjpegStreamMode();
        String proto = normalizeProtocolForMode(appConfig.getMjpegStreamProtocol(), mode);

        if (isClientMode(mode)) {
            // 客户端推流模式：主动连接 ESP32
            String clientHost = appConfig.getMjpegStreamClientHost().trim();
            int clientPort = appConfig.getMjpegStreamClientPort();
            if (clientHost.isEmpty()) {
                if (appConfig.isMjpegStreamAutoDiscover()) {
                    // 自动发现 ESP32，找到后再连接
                    AppLog.i(TAG, "client mode: no host, starting auto-discovery...");
                    startWithDiscovery(proto);
                } else {
                    AppLog.e(TAG, "client mode: no host configured and auto-discover disabled");
                    return null;
                }
            } else if (!startClientTransport(clientHost, clientPort, proto)) {
                return null;
            }
        } else {
            // 服务器模式：监听端口等连接
            try {
                server = createServer(proto, port, frameHolder);
            } catch (IOException e) {
                AppLog.e(TAG, "server create failed on port " + port + " proto=" + proto, e);
                server = null;
                return null;
            }
        }

        if (!isClientMode(mode) && server != null) {
            try {
                server.startServer();
            } catch (IOException e) {
                AppLog.e(TAG, "server start failed: " + e.getMessage(), e);
                server = null;
                return null;
            }
        }
        AppLog.i(TAG, "Stream started: mode=" + mode + " proto=" + proto);

        // 2. 起 GL 编码器
        glEncoder = new StreamGlEncoder(currentCameraPosition, w, h, this::onRgbaFrame);
        glEncoder.startRenderThread();
        if (targetCamera != null) {
            android.util.Size previewSize = targetCamera.getPreviewSize();
            int srcW = previewSize != null ? previewSize.getWidth() : 1280;
            int srcH = previewSize != null ? previewSize.getHeight() : 720;
            glEncoder.postOnRenderThread(() -> {
                glEncoder.init(srcW, srcH);
                applyParamsToEncoder();
                SingleCamera cam = targetCamera;
                if (cam != null) {
                    cam.setStreamSurface(glEncoder.getIntermediateSurface(), glEncoder.getRenderHandler());
                }
            });
        } else {
            // 联动模式无信号，GL 编码器延迟初始化，等信号到来再 init + 挂摄像头
            AppLog.i(TAG, "GL encoder deferred init (linkage idle)");
        }

        // 3. 起编码线程
        encoderThread = new JpegEncoderThread(frameHolder, () -> appConfig.getMjpegStreamQuality());
        encoderThread.start();

        // 4. 轮询：客户端数 + 联动摄像头切换（200ms 一次，联动响应快）
        pollHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        pollHandler.postDelayed(pollRunnable, 200);

        running.set(true);
        AppLog.i(TAG, "MJPEG stream started: " + w + "x" + h + " camera=" + currentCameraPosition
                + " linkage=" + linkageMode);
        return getAccessUrl();
    }

    private boolean startClientTransport(String host, int port, String proto) {
        String clientProto = normalizeProtocolForMode(proto, "CLIENT");
        FrameStreamServer client = new StreamClient(host, port, clientProto, frameHolder);
        synchronized (this) {
            if (server != null) {
                server.stopServer();
            }
            server = client;
            lastClientCount = -1;
        }
        try {
            client.startServer();
            AppLog.i(TAG, "client transport started: " + clientProto + " " + host + ":" + port);
            return true;
        } catch (IOException e) {
            AppLog.e(TAG, "client transport start failed: " + e.getMessage(), e);
            synchronized (this) {
                if (server == client) server = null;
            }
            return false;
        }
    }

    private void startWithDiscovery(String fallbackProto) {
        if (discoveryRunning) return;
        discoveryRunning = true;
        Esp32Discovery.discover(4000, new Esp32Discovery.DiscoveryCallback() {
            @Override
            public void onFound(String host, int port, String proto, int width, int height) {
                discoveryRunning = false;
                if (frameHolder == null) return;
                String clientProto = normalizeProtocolForMode(
                        proto == null || proto.trim().isEmpty() ? fallbackProto : proto, "CLIENT");
                appConfig.setMjpegStreamClientHost(host);
                appConfig.setMjpegStreamClientPort(port);
                appConfig.setMjpegStreamProtocol(clientProto);
                if (width >= 80 && height >= 80) {
                    appConfig.setMjpegStreamWidth(width);
                    appConfig.setMjpegStreamHeight(height);
                    applyParams();
                }
                AppLog.i(TAG, "discovery found ESP32, connecting " + clientProto
                        + " " + host + ":" + port);
                startClientTransport(host, port, clientProto);
            }

            @Override
            public void onTimeout() {
                discoveryRunning = false;
                AppLog.w(TAG, "ESP32 discovery timeout");
                if (running.get() && frameHolder != null && appConfig.isMjpegStreamAutoDiscover()
                        && appConfig.getMjpegStreamClientHost().trim().isEmpty()) {
                    android.os.Handler h = pollHandler != null
                            ? pollHandler
                            : new android.os.Handler(android.os.Looper.getMainLooper());
                    h.postDelayed(() -> startWithDiscovery(fallbackProto), 5000);
                }
            }
        });
    }

    private final Runnable pollRunnable = new Runnable() {
        @Override public void run() {
            if (!running.get()) return;
            // 客户端数（只在变化时回调）
            int n = server == null ? 0 : server.getClientCount();
            if (n != lastClientCount) {
                lastClientCount = n;
                if (clientListener != null) clientListener.onClientCountChanged(n);
            }
            if (encoderThread != null) encoderThread.setActive(n > 0);

            // 联动摄像头切换
            if (linkageMode) {
                checkLinkageSwitch();
            }

            pollHandler.postDelayed(this, 200);
        }
    };

    /** 联动模式：检查补盲活跃摄像头是否变化，变化则切换；无信号时解绑摄像头（不推流）。 */
    private void checkLinkageSwitch() {
        String active = BlindSpotService.getActiveBlindSpotCamera();
        boolean hasSignal = isValidCameraPos(active, multiCameraManager);
        String target = hasSignal ? active : null;

        if (target == null) {
            // 无补盲信号：解绑摄像头，清空帧（画面停黑）
            if (targetCamera != null) {
                AppLog.i(TAG, "linkage: signal lost, unbinding camera");
                if (glEncoder != null) {
                    targetCamera.setStreamSurface(null, null);
                }
                targetCamera = null;
                currentCameraPosition = defaultCameraPosition;
                if (frameHolder != null) frameHolder.clear();
            }
            return;
        }

        // 有补盲信号
        if (!active.equals(currentCameraPosition) || targetCamera == null) {
            AppLog.i(TAG, "linkage switch: " + currentCameraPosition + " → " + active);
            switchCamera(active);
        }
    }

    /** 检查 cameraPos 是否是 MultiCameraManager 中真实存在的摄像头。 */
    private boolean isValidCameraPos(String pos, MultiCameraManager cm) {
        if (pos == null || cm == null) return false;
        return cm.getCamera(pos) != null;
    }

    /**
     * 切换到新摄像头。解绑旧 → 绑新 → 更新鱼眼参数。
     * 处理 GL 编码器未初始化的情况（联动模式从空闲→有信号）。
     */
    private void switchCamera(String newPosition) {
        if (multiCameraManager == null) return;
        SingleCamera newCam = multiCameraManager.getCamera(newPosition);
        if (newCam == null) {
            AppLog.w(TAG, "switchCamera: target camera not found: " + newPosition);
            return;
        }
        // 解绑旧
        if (targetCamera != null && glEncoder != null) {
            targetCamera.setStreamSurface(null, null);
        }
        // 绑新
        targetCamera = newCam;
        currentCameraPosition = newPosition;
        if (glEncoder == null) return;

        android.util.Size ps = newCam.getPreviewSize();
        int srcW = ps != null ? ps.getWidth() : 1280;
        int srcH = ps != null ? ps.getHeight() : 720;
        glEncoder.postOnRenderThread(() -> {
            // GL 编码器可能尚未 init（联动空闲→有信号），此时先 init
            if (!glEncoder.isInitialized()) {
                glEncoder.init(srcW, srcH);
            } else {
                glEncoder.updateSrcSize(srcW, srcH);
            }
            applyParamsToEncoder();
            newCam.setStreamSurface(glEncoder.getIntermediateSurface(), glEncoder.getRenderHandler());
        });
    }

    /** 把当前 AppConfig 里的鱼眼/pan/cover 参数应用到 GL 编码器。 */
    private void applyParamsToEncoder() {
        if (glEncoder == null) return;
        // 鱼眼参数按当前摄像头位置取
        float k1 = appConfig.getFisheyeCorrectionK1(currentCameraPosition);
        float k2 = appConfig.getFisheyeCorrectionK2(currentCameraPosition);
        float zoom = appConfig.getFisheyeCorrectionZoom(currentCameraPosition);
        float cx = appConfig.getFisheyeCorrectionCenterX(currentCameraPosition);
        float cy = appConfig.getFisheyeCorrectionCenterY(currentCameraPosition);
        int rot = appConfig.getFisheyeCorrectionRotation(currentCameraPosition);
        glEncoder.setFisheyeParams(k1, k2, zoom, cx, cy, rot);
        glEncoder.setPan(appConfig.getMjpegStreamPanX(), appConfig.getMjpegStreamPanY());
        glEncoder.setCoverScale(appConfig.getMjpegStreamCoverScale());

        int w = appConfig.getMjpegStreamWidth();
        int h = appConfig.getMjpegStreamHeight();
        if (w != currentWidth || h != currentHeight) {
            currentWidth = w;
            currentHeight = h;
            glEncoder.resize(w, h);
        }
    }

    /** 外部参数变化后调用，把新参数推到 GL 线程。 */
    public void applyParams() {
        if (glEncoder == null) return;
        this.linkageMode = appConfig.isMjpegStreamLinkageMode();
        glEncoder.postOnRenderThread(this::applyParamsToEncoder);
    }

    /** GL 回调：RGBA → JPEG。在 GL 线程被调用，把任务交给编码线程异步处理。 */
    private void onRgbaFrame(ByteBuffer rgba, int w, int h) {
        if (encoderThread != null) {
            encoderThread.submitRgba(rgba, w, h);
        }
    }

    public void stop() {
        if (!running.getAndSet(false)) return;
        AppLog.i(TAG, "stopping MJPEG stream");

        if (pollHandler != null) {
            pollHandler.removeCallbacks(pollRunnable);
            pollHandler = null;
        }
        discoveryRunning = false;

        if (targetCamera != null) {
            targetCamera.setStreamSurface(null, null);
            targetCamera = null;
        }

        if (encoderThread != null) {
            encoderThread.shutdown();
            try { encoderThread.join(500); } catch (InterruptedException ignored) {}
            encoderThread = null;
        }

        if (glEncoder != null) {
            glEncoder.release();
            glEncoder.shutdownRenderThread();
            glEncoder = null;
        }

        if (server != null) {
            server.stopServer();
            server = null;
        }

        if (frameHolder != null) {
            frameHolder.clear();
            frameHolder = null;
        }
        lastClientCount = -1;
        if (sInstance == this) sInstance = null;
        AppLog.i(TAG, "MJPEG stream stopped");
    }

    public String getAccessUrl() {
        String mode = appConfig.getMjpegStreamMode();
        String proto = normalizeProtocolForMode(appConfig.getMjpegStreamProtocol(), mode);
        if (isClientMode(mode)) {
            String host = appConfig.getMjpegStreamClientHost().trim();
            if (host.isEmpty()) {
                return discoveryRunning ? "discover://:8444" : "client://not-configured";
            }
            return proto.toLowerCase() + "://" + host + ":" + appConfig.getMjpegStreamClientPort();
        }
        if (server == null) return null;
        int port = appConfig.getMjpegStreamPort();
        if ("HTTP".equalsIgnoreCase(proto)) {
            return "http://" + MjpegStreamServer.getLocalIp() + ":" + port + "/stream";
        }
        // TCP / UDP：返回 proto://port 形式（UI 只显示端口）
        return proto.toLowerCase() + "://:" + port;
    }
}

/**
 * JPEG 编码线程：接收 RGBA，转 YUV420，YuvImage.compressToJpeg，写入 holder。
 * <p>
 * RGBA→YUV 用手动循环。无客户端时 setActive(false) 暂停处理。
 */
class JpegEncoderThread extends Thread {
    private static final String TAG = "JpegEncoderThread";

    private final MjpegFrameHolder holder;
    private final java.util.function.IntSupplier qualitySupplier;
    private final java.util.concurrent.BlockingQueue<RgbaFrame> queue =
            new java.util.concurrent.LinkedBlockingQueue<>(2);
    private volatile boolean active = true;
    private volatile boolean stopped = false;

    private static class RgbaFrame {
        final ByteBuffer rgba;
        final int w, h;
        RgbaFrame(ByteBuffer rgba, int w, int h) { this.rgba = rgba; this.w = w; this.h = h; }
    }

    JpegEncoderThread(MjpegFrameHolder holder, java.util.function.IntSupplier qualitySupplier) {
        super("MjpegJpegEncoder");
        setDaemon(true);
        this.holder = holder;
        this.qualitySupplier = qualitySupplier;
    }

    void setActive(boolean active) {
        this.active = active;
        if (!active) queue.clear();
    }

    void submitRgba(ByteBuffer rgba, int w, int h) {
        if (!active || stopped) return;
        RgbaFrame f = new RgbaFrame(rgba, w, h);
        if (!queue.offer(f)) {
            queue.poll();
            queue.offer(f);
        }
    }

    @Override
    public void run() {
        while (!stopped) {
            RgbaFrame f;
            try {
                f = queue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }
            if (f == null) continue;
            byte[] jpg = encode(f);
            if (jpg != null) holder.update(jpg);
        }
    }

    private byte[] encode(RgbaFrame f) {
        int w = f.w, h = f.h;
        int[] argb = new int[w * h];
        ByteBuffer buf = f.rgba.duplicate();
        for (int i = 0; i < argb.length; i++) {
            int r = buf.get() & 0xff;
            int g = buf.get() & 0xff;
            int b = buf.get() & 0xff;
            int a = buf.get() & 0xff;
            argb[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        byte[] yuv = argbToNv21(argb, w, h);
        YuvImage yuvImage = new YuvImage(yuv, ImageFormat.NV21, w, h, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream(w * h / 4);
        int q = Math.max(1, Math.min(95, qualitySupplier.getAsInt()));
        yuvImage.compressToJpeg(new Rect(0, 0, w, h), q, out);
        return out.toByteArray();
    }

    private static byte[] argbToNv21(int[] argb, int w, int h) {
        int frameSize = w * h;
        int chromaSize = frameSize / 4;
        byte[] yuv = new byte[frameSize + chromaSize * 2];
        int yIdx = 0, uvIdx = frameSize;
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                int c = argb[j * w + i];
                int r = (c >> 16) & 0xff;
                int g = (c >> 8) & 0xff;
                int b = c & 0xff;
                int y = ((66 * r + 129 * g + 25 * b + 128) >> 8) + 16;
                yuv[yIdx++] = (byte) (y < 0 ? 0 : (y > 255 ? 255 : y));
                if (j % 2 == 0 && i % 2 == 0) {
                    int u = ((-38 * r - 74 * g + 112 * b + 128) >> 8) + 128;
                    int v = ((112 * r - 94 * g - 18 * b + 128) >> 8) + 128;
                    yuv[uvIdx++] = (byte) (v < 0 ? 0 : (v > 255 ? 255 : v));
                    yuv[uvIdx++] = (byte) (u < 0 ? 0 : (u > 255 ? 255 : u));
                }
            }
        }
        return yuv;
    }

    void shutdown() {
        stopped = true;
        interrupt();
    }
}
