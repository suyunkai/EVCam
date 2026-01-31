package com.kooo.evcam.telegram;

import com.kooo.evcam.AppLog;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Telegram Bot API 客户端
 * 负责与 Telegram 服务器进行 HTTP 通信
 */
public class TelegramApiClient {
    private static final String TAG = "TelegramApiClient";

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final TelegramConfig config;

    public TelegramApiClient(TelegramConfig config) {
        this.config = config;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)  // 连接超时15秒
                .readTimeout(45, TimeUnit.SECONDS)     // 读取超时45秒
                .writeTimeout(60, TimeUnit.SECONDS)    // 写入超时60秒（文件上传）
                .build();
    }

    /**
     * 构建 API URL
     * 使用配置的 API Host（支持自定义反向代理地址）
     */
    private String buildUrl(String method) {
        return config.getBotApiHost() + "/bot" + config.getBotToken() + "/" + method;
    }

    /**
     * 获取 Bot 信息（用于验证 Token 是否有效）
     */
    public JsonObject getMe() throws IOException {
        String url = buildUrl("getMe");

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            AppLog.d(TAG, "getMe 响应: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("getMe 失败: " + response.code() + ", " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            if (!jsonResponse.get("ok").getAsBoolean()) {
                throw new IOException("getMe 失败: " + responseBody);
            }

            return jsonResponse.getAsJsonObject("result");
        }
    }

    /**
     * 获取更新（Long Polling 方式）
     * @param offset 从此 update_id 开始获取
     * @param timeout 长轮询超时时间（秒）
     * @param limit 限制返回的更新数量（1-100，默认100）
     */
    public JsonArray getUpdates(long offset, int timeout, int limit) throws IOException {
        String url = buildUrl("getUpdates") +
                "?offset=" + offset +
                "&timeout=" + timeout +
                "&limit=" + Math.max(1, Math.min(limit, 100)) +
                "&allowed_updates=" + "[\"message\"]";

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        // 为 Long Polling 创建特殊的客户端，超时时间更长
        OkHttpClient longPollClient = httpClient.newBuilder()
                .readTimeout(timeout + 10, TimeUnit.SECONDS)
                .build();

        try (Response response = longPollClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";

            if (!response.isSuccessful()) {
                throw new IOException("getUpdates 失败: " + response.code() + ", " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            if (!jsonResponse.get("ok").getAsBoolean()) {
                throw new IOException("getUpdates 失败: " + responseBody);
            }

            return jsonResponse.getAsJsonArray("result");
        }
    }

    /**
     * 发送文本消息
     */
    public void sendMessage(long chatId, String text) throws IOException {
        String url = buildUrl("sendMessage");

        JsonObject body = new JsonObject();
        body.addProperty("chat_id", chatId);
        body.addProperty("text", text);
        body.addProperty("parse_mode", "HTML");

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送消息: chatId=" + chatId + ", text=" + text);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送消息失败，响应: " + responseBody);
                throw new IOException("发送消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "消息发送成功");
        }
    }

    /**
     * 发送图片
     */
    public void sendPhoto(long chatId, File photoFile) throws IOException {
        sendPhoto(chatId, photoFile, null);
    }

    /**
     * 发送图片（带说明文字）
     */
    public void sendPhoto(long chatId, File photoFile, String caption) throws IOException {
        String url = buildUrl("sendPhoto");

        RequestBody fileBody = RequestBody.create(
                MediaType.parse("image/jpeg"),
                photoFile
        );

        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("photo", photoFile.getName(), fileBody);

        if (caption != null && !caption.isEmpty()) {
            builder.addFormDataPart("caption", caption);
        }

        Request request = new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送图片失败，响应: " + responseBody);
                throw new IOException("发送图片失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "图片发送成功: " + photoFile.getName());
        }
    }

    /**
     * 发送视频
     */
    public void sendVideo(long chatId, File videoFile, File thumbnailFile, int duration) throws IOException {
        sendVideo(chatId, videoFile, thumbnailFile, duration, null);
    }

    /**
     * 发送视频（带说明文字）
     */
    public void sendVideo(long chatId, File videoFile, File thumbnailFile, int duration, String caption) throws IOException {
        String url = buildUrl("sendVideo");

        RequestBody videoBody = RequestBody.create(
                MediaType.parse("video/mp4"),
                videoFile
        );

        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("video", videoFile.getName(), videoBody)
                .addFormDataPart("duration", String.valueOf(duration))
                .addFormDataPart("supports_streaming", "true");

        if (thumbnailFile != null && thumbnailFile.exists()) {
            RequestBody thumbBody = RequestBody.create(
                    MediaType.parse("image/jpeg"),
                    thumbnailFile
            );
            builder.addFormDataPart("thumbnail", thumbnailFile.getName(), thumbBody);
        }

        if (caption != null && !caption.isEmpty()) {
            builder.addFormDataPart("caption", caption);
        }

        Request request = new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送视频失败，响应: " + responseBody);
                throw new IOException("发送视频失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "视频发送成功: " + videoFile.getName());
        }
    }

    /**
     * 发送文档/文件
     */
    public void sendDocument(long chatId, File file) throws IOException {
        sendDocument(chatId, file, null);
    }

    /**
     * 发送文档/文件（带说明文字）
     */
    public void sendDocument(long chatId, File file, String caption) throws IOException {
        String url = buildUrl("sendDocument");

        RequestBody fileBody = RequestBody.create(
                MediaType.parse("application/octet-stream"),
                file
        );

        MultipartBody.Builder builder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", String.valueOf(chatId))
                .addFormDataPart("document", file.getName(), fileBody);

        if (caption != null && !caption.isEmpty()) {
            builder.addFormDataPart("caption", caption);
        }

        Request request = new Request.Builder()
                .url(url)
                .post(builder.build())
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送文件失败，响应: " + responseBody);
                throw new IOException("发送文件失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "文件发送成功: " + file.getName());
        }
    }

    /**
     * 发送聊天操作（如"正在输入..."、"正在上传视频..."）
     * @param action typing, upload_photo, upload_video, upload_document 等
     */
    public void sendChatAction(long chatId, String action) {
        try {
            String url = buildUrl("sendChatAction");

            JsonObject body = new JsonObject();
            body.addProperty("chat_id", chatId);
            body.addProperty("action", action);

            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(
                            MediaType.parse("application/json"),
                            gson.toJson(body)
                    ))
                    .build();

            // 异步发送，不等待响应
            httpClient.newCall(request).execute().close();
        } catch (Exception e) {
            AppLog.w(TAG, "发送 ChatAction 失败: " + e.getMessage());
        }
    }
}
