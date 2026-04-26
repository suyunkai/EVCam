package com.kooo.evcam.v2.service;

import android.util.Log;

import com.kooo.evcam.VhalNative;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.okhttp.OkHttpChannelBuilder;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;

final class V2VhalStreamClient {
    static final class Connection {
        final ManagedChannel channel;
        final String sessionId;
        Connection(ManagedChannel channel, String sessionId) {
            this.channel = channel;
            this.sessionId = sessionId;
        }
    }

    interface Callback {
        void onBatch(byte[] data);
        void onStreamCompleted();
        void onStreamError(Throwable t);
    }

    private static final long STREAM_TIMEOUT_MS = 120_000;
    private static final int MAX_RETRIES = 3;
    private static final long FIRST_SEND_ALL_DELAY_MS = 500L;

    private final String tag;
    private final String connectedLog;
    private final String streamTimeoutLog;
    private final String sendAllSuccessLog;
    private final String sendAllExhaustedLog;

    V2VhalStreamClient(String tag, String connectedLog, String streamTimeoutLog, String sendAllSuccessLog, String sendAllExhaustedLog) {
        this.tag = tag;
        this.connectedLog = connectedLog;
        this.streamTimeoutLog = streamTimeoutLog;
        this.sendAllSuccessLog = sendAllSuccessLog;
        this.sendAllExhaustedLog = sendAllExhaustedLog;
    }

    Connection connect() {
        String sessionId = UUID.randomUUID().toString();
        Metadata headers = new Metadata();
        headers.put(Metadata.Key.of("session_id", Metadata.ASCII_STRING_MARSHALLER), sessionId);
        headers.put(Metadata.Key.of("client_id", Metadata.ASCII_STRING_MARSHALLER), "evcam_signal");
        ManagedChannel channel = OkHttpChannelBuilder.forAddress(VhalNative.getGrpcHost(), VhalNative.getGrpcPort())
                .usePlaintext()
                .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
                .build();
        Log.d(tag, connectedLog + sessionId);
        return new Connection(channel, sessionId);
    }

    void disconnect(ManagedChannel channel) {
        if (channel == null) return;
        try {
            channel.shutdown();
            if (!channel.awaitTermination(2, TimeUnit.SECONDS)) channel.shutdownNow();
        } catch (Throwable ignored) {
            try { channel.shutdownNow(); } catch (Throwable ignored2) {}
        }
    }

    void streamProperties(String streamMethodName, ManagedChannel active, Callback callback) throws InterruptedException {
        if (active == null) return;
        CountDownLatch latch = new CountDownLatch(1);
        MethodDescriptor<byte[], byte[]> streamMethod = MethodDescriptor.<byte[], byte[]>newBuilder()
                .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
                .setFullMethodName(streamMethodName)
                .setRequestMarshaller(ByteMarshaller.INSTANCE)
                .setResponseMarshaller(ByteMarshaller.INSTANCE)
                .build();
        ClientCalls.asyncServerStreamingCall(active.newCall(streamMethod, CallOptions.DEFAULT), new byte[0], new StreamObserver<byte[]>() {
            @Override public void onNext(byte[] value) { callback.onBatch(value); }
            @Override public void onError(Throwable t) { callback.onStreamError(t); latch.countDown(); }
            @Override public void onCompleted() { callback.onStreamCompleted(); latch.countDown(); }
        });
        requestAllProperties(active, VhalNative.getSendAllMethod());
        if (!latch.await(STREAM_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            Log.w(tag, streamTimeoutLog);
        }
    }

    private void requestAllProperties(ManagedChannel active, String sendAllMethodName) {
        new Thread(() -> {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    Thread.sleep(attempt == 1 ? FIRST_SEND_ALL_DELAY_MS : FIRST_SEND_ALL_DELAY_MS * attempt);
                    MethodDescriptor<byte[], byte[]> method = MethodDescriptor.<byte[], byte[]>newBuilder()
                            .setType(MethodDescriptor.MethodType.UNARY)
                            .setFullMethodName(sendAllMethodName)
                            .setRequestMarshaller(ByteMarshaller.INSTANCE)
                            .setResponseMarshaller(ByteMarshaller.INSTANCE)
                            .build();
                    ClientCalls.blockingUnaryCall(active.newCall(method, CallOptions.DEFAULT), new byte[0]);
                    Log.d(tag, sendAllSuccessLog + attempt);
                    return;
                } catch (Throwable error) {
                    Log.w(tag, "SendAll attempt " + attempt + "/" + MAX_RETRIES + " failed: " + error.getMessage());
                }
            }
            Log.e(tag, sendAllExhaustedLog);
        }, tag + "SendAll").start();
    }

    private enum ByteMarshaller implements MethodDescriptor.Marshaller<byte[]> {
        INSTANCE;
        @Override public InputStream stream(byte[] value) { return new ByteArrayInputStream(value); }
        @Override public byte[] parse(InputStream stream) {
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int len;
                while ((len = stream.read(buf)) != -1) baos.write(buf, 0, len);
                return baos.toByteArray();
            } catch (Exception e) { return new byte[0]; }
        }
    }
}
