package com.kooo.evcam.stream;

import com.kooo.evcam.AppLog;

import java.io.IOException;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端推流：EVCam 主动连接 ESP32 推流。
 * <p>
 * 支持：
 * <ul>
 *   <li>TCP：帧格式 {@code [4B 小端 uint32 len][JPEG]}，断线自动重连</li>
 *   <li>UDP：16B 分片头 + ≤1400B 数据，无连接</li>
 *   <li>RTP：RTP/JPEG（payload type 26），可推送到指定地址</li>
 * </ul>
 * TCP/UDP 协议格式与 Python 脚本一致，RTP 使用 RFC 2435 JPEG payload。
 * <p>
 * 实现 {@link FrameStreamServer} 接口以复用 {@link MjpegStreamManager} 的多态逻辑：
 * {@code startServer()} = 建立连接，{@code getClientCount()} = 1 表示已连接。
 */
public class StreamClient implements FrameStreamServer {
    private static final String TAG = "StreamClient";
    private static final int RECONNECT_INTERVAL_MS = 2000;
    private static final int UDP_MAGIC = 0x5354;
    private static final int UDP_CHUNK_SIZE = 1400;
    private static final int RTP_PAYLOAD_TYPE_JPEG = 26;
    private static final int RTP_PACKET_MTU = 1400;
    private static final int RTP_HEADER_SIZE = 12;
    private static final int RTP_JPEG_HEADER_SIZE = 8;
    private static final int RTP_QUANT_HEADER_SIZE = 4;

    private final String host;
    private final int port;
    private final String protocol;
    private final MjpegFrameHolder frameHolder;

    private Thread clientThread;
    private volatile boolean stopped = false;
    private final AtomicInteger connected = new AtomicInteger(0);
    private int rtpSequence = (int) (System.nanoTime() & 0xffff);
    private final int rtpSsrc = (int) (System.currentTimeMillis()
            ^ System.identityHashCode(this));
    private final int rtpStartTimestamp = (int) (System.nanoTime() >>> 16);
    private final long rtpStartNanos = System.nanoTime();

    public StreamClient(String host, int port, String protocol, MjpegFrameHolder frameHolder) {
        this.host = host;
        this.port = port;
        this.protocol = protocol != null ? protocol.toUpperCase() : "TCP";
        this.frameHolder = frameHolder;
    }

    @Override
    public void startServer() {
        stopped = false;
        clientThread = new Thread(this::clientLoop, "StreamClient-" + protocol);
        clientThread.setDaemon(true);
        clientThread.start();
    }

    private void clientLoop() {
        if ("RTP".equals(protocol)) {
            rtpLoop();
        } else if ("UDP".equals(protocol)) {
            udpLoop();
        } else {
            tcpLoop();
        }
    }

    /** TCP 模式：建立连接 → 持续推帧 → 断线重连。 */
    private void tcpLoop() {
        byte[] lastSent = null;
        while (!stopped) {
            Socket socket = null;
            try {
                AppLog.i(TAG, "TCP connecting to " + host + ":" + port);
                socket = new Socket();
                socket.connect(new InetSocketAddress(host, port), 5000);
                socket.setTcpNoDelay(true);
                connected.set(1);
                lastSent = null;
                AppLog.i(TAG, "TCP connected to " + host + ":" + port);

                OutputStream out = socket.getOutputStream();
                while (!stopped && !socket.isClosed()) {
                    byte[] jpg = frameHolder.get();
                    if (jpg == null || jpg == lastSent) {
                        try { Thread.sleep(5); } catch (InterruptedException ie) { break; }
                        continue;
                    }
                    lastSent = jpg;
                    byte[] header = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(jpg.length).array();
                    out.write(header);
                    out.write(jpg);
                    out.flush();
                }
            } catch (Exception e) {
                if (!stopped) {
                    AppLog.w(TAG, "TCP error: " + e.getMessage()
                            + ", reconnecting in " + RECONNECT_INTERVAL_MS + "ms");
                }
            } finally {
                connected.set(0);
                if (socket != null) {
                    try { socket.close(); } catch (IOException ignored) {}
                }
            }
            // 重连等待
            if (stopped) break;
            try {
                Thread.sleep(RECONNECT_INTERVAL_MS);
            } catch (InterruptedException ie) {
                break;
            }
        }
    }

    /** UDP 模式：直接 sendto 目标地址，无连接概念。 */
    private void udpLoop() {
        byte[] lastSent = null;
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            InetSocketAddress target = new InetSocketAddress(host, port);
            connected.set(1);
            AppLog.i(TAG, "UDP target " + host + ":" + port);

            while (!stopped) {
                byte[] jpg = frameHolder.get();
                if (jpg == null || jpg == lastSent) {
                    try { Thread.sleep(5); } catch (InterruptedException ie) { break; }
                    continue;
                }
                lastSent = jpg;
                sendUdpFrame(sock, target, jpg);
            }
        } catch (Exception e) {
            if (!stopped) AppLog.w(TAG, "UDP error: " + e.getMessage());
        } finally {
            connected.set(0);
            if (sock != null) sock.close();
        }
    }

    private void sendUdpFrame(DatagramSocket sock, InetSocketAddress target, byte[] jpg) throws IOException {
        int total = jpg.length;
        int n = (total + UDP_CHUNK_SIZE - 1) / UDP_CHUNK_SIZE;
        for (int i = 0; i < n; i++) {
            int off = i * UDP_CHUNK_SIZE;
            int len = (i == n - 1) ? (total - off) : UDP_CHUNK_SIZE;
            byte[] packet = new byte[16 + len];
            ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort((short) UDP_MAGIC)
                    .putShort((short) 0)
                    .putInt(total)
                    .putInt(i)
                    .putInt(n);
            System.arraycopy(jpg, off, packet, 16, len);
            sock.send(new DatagramPacket(packet, packet.length, target));
        }
    }

    /** RTP/JPEG 模式：把 JPEG 熵编码扫描数据按 RFC 2435 分片后发 RTP 包。 */
    private void rtpLoop() {
        byte[] lastSent = null;
        DatagramSocket sock = null;
        try {
            sock = new DatagramSocket();
            InetSocketAddress target = new InetSocketAddress(host, port);
            connected.set(1);
            AppLog.i(TAG, "RTP/JPEG target " + host + ":" + port);

            while (!stopped) {
                byte[] jpg = frameHolder.get();
                if (jpg == null || jpg == lastSent) {
                    try { Thread.sleep(5); } catch (InterruptedException ie) { break; }
                    continue;
                }
                lastSent = jpg;
                JpegRtpFrame frame = JpegRtpFrame.parse(jpg);
                if (frame == null) {
                    AppLog.w(TAG, "skip JPEG frame unsupported by RTP/JPEG payload");
                    continue;
                }
                sendRtpJpegFrame(sock, target, frame);
            }
        } catch (Exception e) {
            if (!stopped) AppLog.w(TAG, "RTP error: " + e.getMessage());
        } finally {
            connected.set(0);
            if (sock != null) sock.close();
        }
    }

    private void sendRtpJpegFrame(DatagramSocket sock, InetSocketAddress target,
                                  JpegRtpFrame frame) throws IOException {
        int timestamp = currentRtpTimestamp();
        int offset = 0;
        while (offset < frame.scan.length && !stopped) {
            boolean first = offset == 0;
            int extraHeader = first && frame.quantTables != null
                    ? RTP_QUANT_HEADER_SIZE + frame.quantTables.length
                    : 0;
            int maxPayload = RTP_PACKET_MTU - RTP_HEADER_SIZE - RTP_JPEG_HEADER_SIZE - extraHeader;
            int len = Math.min(maxPayload, frame.scan.length - offset);
            boolean last = offset + len >= frame.scan.length;

            byte[] packet = new byte[RTP_HEADER_SIZE + RTP_JPEG_HEADER_SIZE + extraHeader + len];
            ByteBuffer out = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN);

            out.put((byte) 0x80); // RTP v2
            out.put((byte) ((last ? 0x80 : 0x00) | RTP_PAYLOAD_TYPE_JPEG));
            out.putShort((short) (rtpSequence++ & 0xffff));
            out.putInt(timestamp);
            out.putInt(rtpSsrc);

            out.put((byte) 0); // type-specific: progressive frame
            out.put((byte) ((offset >> 16) & 0xff));
            out.put((byte) ((offset >> 8) & 0xff));
            out.put((byte) (offset & 0xff));
            out.put((byte) frame.type);
            out.put((byte) frame.q);
            out.put((byte) frame.widthBlocks);
            out.put((byte) frame.heightBlocks);

            if (first && frame.quantTables != null) {
                out.put((byte) 0); // MBZ
                out.put((byte) 0); // 8-bit quantization tables
                out.putShort((short) frame.quantTables.length);
                out.put(frame.quantTables);
            }

            out.put(frame.scan, offset, len);
            sock.send(new DatagramPacket(packet, packet.length, target));
            offset += len;
        }
    }

    private int currentRtpTimestamp() {
        long elapsed = System.nanoTime() - rtpStartNanos;
        long ticks = elapsed * 90_000L / 1_000_000_000L;
        return rtpStartTimestamp + (int) ticks;
    }

    private static final class JpegRtpFrame {
        final int widthBlocks;
        final int heightBlocks;
        final int type;
        final int q;
        final byte[] quantTables;
        final byte[] scan;

        JpegRtpFrame(int width, int height, int type, byte[] quantTables, byte[] scan) {
            this.widthBlocks = Math.min(255, Math.max(1, (width + 7) / 8));
            this.heightBlocks = Math.min(255, Math.max(1, (height + 7) / 8));
            this.type = type;
            this.quantTables = quantTables;
            this.q = quantTables != null ? 255 : 50;
            this.scan = scan;
        }

        static JpegRtpFrame parse(byte[] jpg) {
            if (jpg == null || jpg.length < 4
                    || (jpg[0] & 0xff) != 0xff || (jpg[1] & 0xff) != 0xd8) {
                return null;
            }
            int pos = 2;
            int width = 0;
            int height = 0;
            int type = 1; // Android NV21 JPEG is normally 4:2:0.
            byte[] q0 = null;
            byte[] q1 = null;
            int scanStart = -1;
            int scanEnd = -1;

            while (pos + 3 < jpg.length) {
                if ((jpg[pos] & 0xff) != 0xff) {
                    pos++;
                    continue;
                }
                while (pos < jpg.length && (jpg[pos] & 0xff) == 0xff) pos++;
                if (pos >= jpg.length) break;
                int marker = jpg[pos++] & 0xff;
                if (marker == 0xd9) break; // EOI
                if (marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7)) continue;
                if (pos + 2 > jpg.length) break;
                int len = ((jpg[pos] & 0xff) << 8) | (jpg[pos + 1] & 0xff);
                if (len < 2 || pos + len > jpg.length) break;
                int data = pos + 2;
                int end = pos + len;

                if (marker == 0xdb) {
                    int p = data;
                    while (p < end) {
                        int pqTq = jpg[p++] & 0xff;
                        int precision = (pqTq >> 4) & 0x0f;
                        int tableId = pqTq & 0x0f;
                        int tableLen = precision == 0 ? 64 : 128;
                        if (p + tableLen > end) break;
                        if (precision == 0 && (tableId == 0 || tableId == 1)) {
                            byte[] table = new byte[64];
                            System.arraycopy(jpg, p, table, 0, 64);
                            if (tableId == 0) q0 = table;
                            else q1 = table;
                        }
                        p += tableLen;
                    }
                } else if (marker == 0xc0 && data + 8 < end) {
                    height = ((jpg[data + 1] & 0xff) << 8) | (jpg[data + 2] & 0xff);
                    width = ((jpg[data + 3] & 0xff) << 8) | (jpg[data + 4] & 0xff);
                    int components = jpg[data + 5] & 0xff;
                    if (components > 0 && data + 8 <= end) {
                        int sampling = jpg[data + 7] & 0xff;
                        int hSamp = (sampling >> 4) & 0x0f;
                        int vSamp = sampling & 0x0f;
                        type = hSamp == 2 && vSamp == 1 ? 0 : 1;
                    }
                } else if (marker == 0xda) {
                    scanStart = end;
                    scanEnd = findScanEnd(jpg, scanStart);
                    break;
                }
                pos = end;
            }

            if (width <= 0 || height <= 0 || scanStart < 0 || scanEnd <= scanStart) {
                return null;
            }
            byte[] scan = new byte[scanEnd - scanStart];
            System.arraycopy(jpg, scanStart, scan, 0, scan.length);
            byte[] qTables = null;
            if (q0 != null && q1 != null) {
                qTables = new byte[128];
                System.arraycopy(q0, 0, qTables, 0, 64);
                System.arraycopy(q1, 0, qTables, 64, 64);
            }
            return new JpegRtpFrame(width, height, type, qTables, scan);
        }

        private static int findScanEnd(byte[] jpg, int start) {
            for (int i = start; i + 1 < jpg.length; i++) {
                if ((jpg[i] & 0xff) != 0xff) continue;
                int next = jpg[i + 1] & 0xff;
                if (next == 0x00 || (next >= 0xd0 && next <= 0xd7)) {
                    i++;
                    continue;
                }
                if (next == 0xd9) return i;
                return i;
            }
            if (jpg.length >= 2 && (jpg[jpg.length - 2] & 0xff) == 0xff
                    && (jpg[jpg.length - 1] & 0xff) == 0xd9) {
                return jpg.length - 2;
            }
            return jpg.length;
        }
    }

    @Override
    public void stopServer() {
        stopped = true;
        connected.set(0);
        if (clientThread != null) clientThread.interrupt();
    }

    @Override
    public int getClientCount() {
        return connected.get();
    }
}
