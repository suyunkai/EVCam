package com.kooo.evcam.feishu;

import com.kooo.evcam.AppLog;

import com.google.gson.Gson;
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
 * 飞书 API 客户端
 * 负责与飞书服务器进行 HTTP 通信
 */
public class FeishuApiClient {
    private static final String TAG = "FeishuApiClient";
    private static final String BASE_URL = "https://open.feishu.cn/open-apis";

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final FeishuConfig config;

    public FeishuApiClient(FeishuConfig config) {
        this.config = config;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS) // 上传大文件需要更长时间
                .build();
    }

    /**
     * 获取 Tenant Access Token
     */
    public String getTenantAccessToken() throws IOException {
        // 检查缓存的 token 是否有效
        if (config.isTokenValid()) {
            String cachedToken = config.getAccessToken();
            AppLog.d(TAG, "使用缓存的 Access Token");
            return cachedToken;
        }

        // 获取新的 token
        String url = BASE_URL + "/auth/v3/tenant_access_token/internal";

        JsonObject body = new JsonObject();
        body.addProperty("app_id", config.getAppId());
        body.addProperty("app_secret", config.getAppSecret());

        AppLog.d(TAG, "正在获取新的 Access Token...");

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        gson.toJson(body)
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            AppLog.d(TAG, "Access Token 响应: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("获取 Access Token 失败: " + response.code() + " - " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            // 检查错误码
            int code = jsonResponse.has("code") ? jsonResponse.get("code").getAsInt() : -1;
            if (code != 0) {
                String msg = jsonResponse.has("msg") ? jsonResponse.get("msg").getAsString() : "Unknown error";
                throw new IOException("获取 Access Token 失败: code=" + code + ", msg=" + msg);
            }

            if (jsonResponse.has("tenant_access_token")) {
                String accessToken = jsonResponse.get("tenant_access_token").getAsString();
                int expire = jsonResponse.get("expire").getAsInt();

                // 提前 5 分钟过期
                long expireTime = System.currentTimeMillis() + (expire - 300) * 1000L;
                config.saveAccessToken(accessToken, expireTime);

                AppLog.d(TAG, "Access Token 获取成功");
                return accessToken;
            } else {
                throw new IOException("响应中没有 tenant_access_token: " + responseBody);
            }
        }
    }

    /**
     * 获取 WebSocket 连接信息（用于长连接接收消息）
     * 注意：需要在飞书开发者后台开启"长连接"模式
     * 
     * 根据飞书官方 SDK 实现，此接口需要直接传递 AppID 和 AppSecret，
     * 而不是使用 Bearer Token 认证。
     */
    public WebSocketConnection getWebSocketConnection() throws IOException {
        // 注意：WebSocket endpoint 不使用 /open-apis 前缀
        // 正确的 URL 是 https://open.feishu.cn/callback/ws/endpoint
        String url = "https://open.feishu.cn/callback/ws/endpoint";

        // 飞书官方 SDK 使用的请求格式：直接传递 AppID 和 AppSecret
        JsonObject body = new JsonObject();
        body.addProperty("AppID", config.getAppId());
        body.addProperty("AppSecret", config.getAppSecret());

        AppLog.d(TAG, "正在获取 WebSocket 连接地址...");

        Request request = new Request.Builder()
                .url(url)
                .header("locale", "zh")
                .post(RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"),
                        gson.toJson(body)
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            AppLog.d(TAG, "WebSocket 连接信息响应: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("获取 WebSocket 连接失败: " + response.code() + " - " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            int code = jsonResponse.has("code") ? jsonResponse.get("code").getAsInt() : -1;
            if (code != 0) {
                String msg = jsonResponse.has("msg") ? jsonResponse.get("msg").getAsString() : "Unknown error";
                throw new IOException("获取 WebSocket 连接失败: code=" + code + ", msg=" + msg);
            }

            JsonObject data = jsonResponse.getAsJsonObject("data");
            // 注意：飞书返回的字段名是大写 "URL"
            String wsUrl = data.get("URL").getAsString();

            AppLog.d(TAG, "WebSocket URL 获取成功: " + wsUrl);
            return new WebSocketConnection(wsUrl);
        }
    }

    /**
     * 发送文本消息
     * @param receiveIdType 接收者类型：open_id, user_id, union_id, email, chat_id
     * @param receiveId 接收者ID
     * @param text 消息内容
     */
    public void sendTextMessage(String receiveIdType, String receiveId, String text) throws IOException {
        String accessToken = getTenantAccessToken();
        String url = BASE_URL + "/im/v1/messages?receive_id_type=" + receiveIdType;

        // 构建消息内容
        JsonObject content = new JsonObject();
        content.addProperty("text", text);

        JsonObject body = new JsonObject();
        body.addProperty("receive_id", receiveId);
        body.addProperty("msg_type", "text");
        body.addProperty("content", gson.toJson(content));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送文本消息: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送消息失败: " + responseBody);
                throw new IOException("发送消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "消息发送成功: " + responseBody);
        }
    }

    /**
     * 回复消息
     * @param messageId 原消息ID
     * @param text 回复内容
     */
    public void replyMessage(String messageId, String text) throws IOException {
        String accessToken = getTenantAccessToken();
        String url = BASE_URL + "/im/v1/messages/" + messageId + "/reply";

        // 构建消息内容
        JsonObject content = new JsonObject();
        content.addProperty("text", text);

        JsonObject body = new JsonObject();
        body.addProperty("msg_type", "text");
        body.addProperty("content", gson.toJson(content));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "回复消息: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "回复消息失败: " + responseBody);
                throw new IOException("回复消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "回复消息成功: " + responseBody);
        }
    }

    /**
     * 上传图片
     * @param imageFile 图片文件
     * @return image_key
     */
    public String uploadImage(File imageFile) throws IOException {
        String accessToken = getTenantAccessToken();
        String url = BASE_URL + "/im/v1/images";

        RequestBody fileBody = RequestBody.create(
                MediaType.parse("image/jpeg"),
                imageFile
        );

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image_type", "message")
                .addFormDataPart("image", imageFile.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "上传图片失败: " + responseBody);
                throw new IOException("上传图片失败: " + response.code() + ", " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            JsonObject data = jsonResponse.getAsJsonObject("data");
            String imageKey = data.get("image_key").getAsString();

            AppLog.d(TAG, "图片上传成功: " + imageKey);
            return imageKey;
        }
    }

    /**
     * 发送图片消息
     */
    public void sendImageMessage(String receiveIdType, String receiveId, String imageKey) throws IOException {
        String accessToken = getTenantAccessToken();
        String url = BASE_URL + "/im/v1/messages?receive_id_type=" + receiveIdType;

        // 构建消息内容
        JsonObject content = new JsonObject();
        content.addProperty("image_key", imageKey);

        JsonObject body = new JsonObject();
        body.addProperty("receive_id", receiveId);
        body.addProperty("msg_type", "image");
        body.addProperty("content", gson.toJson(content));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送图片消息: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送图片消息失败: " + responseBody);
                throw new IOException("发送图片消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "图片消息发送成功: " + responseBody);
        }
    }

    /**
     * 上传文件（不带时长参数）
     * @param file 文件
     * @param fileType 文件类型：opus, mp4, pdf, doc, xls, ppt, stream
     * @return file_key
     */
    public String uploadFile(File file, String fileType) throws IOException {
        return uploadFile(file, fileType, -1);
    }

    /**
     * 上传文件（带时长参数，用于视频/音频）
     * @param file 文件
     * @param fileType 文件类型：opus, mp4, pdf, doc, xls, ppt, stream
     * @param durationMs 文件时长（毫秒），-1 表示不传递
     * @return file_key
     */
    public String uploadFile(File file, String fileType, int durationMs) throws IOException {
        String accessToken = getTenantAccessToken();
        String url = BASE_URL + "/im/v1/files";

        RequestBody fileBody = RequestBody.create(
                MediaType.parse("application/octet-stream"),
                file
        );

        MultipartBody.Builder bodyBuilder = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file_type", fileType)
                .addFormDataPart("file_name", file.getName())
                .addFormDataPart("file", file.getName(), fileBody);

        // 如果有时长参数，添加到请求中（视频/音频文件需要此参数才能显示时长）
        if (durationMs > 0) {
            bodyBuilder.addFormDataPart("duration", String.valueOf(durationMs));
            AppLog.d(TAG, "上传文件带时长参数: " + durationMs + "ms");
        }

        MultipartBody requestBody = bodyBuilder.build();

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "上传文件失败: " + responseBody);
                throw new IOException("上传文件失败: " + response.code() + ", " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);
            JsonObject data = jsonResponse.getAsJsonObject("data");
            String fileKey = data.get("file_key").getAsString();

            AppLog.d(TAG, "文件上传成功: " + fileKey);
            return fileKey;
        }
    }

    /**
     * 发送文件消息（用于普通文件如 pdf, doc 等）
     */
    public void sendFileMessage(String receiveIdType, String receiveId, String fileKey) throws IOException {
        String accessToken = getTenantAccessToken();
        String url = BASE_URL + "/im/v1/messages?receive_id_type=" + receiveIdType;

        // 构建消息内容
        JsonObject content = new JsonObject();
        content.addProperty("file_key", fileKey);

        JsonObject body = new JsonObject();
        body.addProperty("receive_id", receiveId);
        body.addProperty("msg_type", "file");
        body.addProperty("content", gson.toJson(content));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送文件消息: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送文件消息失败: " + responseBody);
                throw new IOException("发送文件消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "文件消息发送成功: " + responseBody);
        }
    }

    /**
     * 发送视频消息（用于 mp4 等视频文件）
     * @param receiveIdType 接收者类型
     * @param receiveId 接收者 ID
     * @param fileKey 视频文件的 file_key
     * @param imageKey 视频封面图片的 image_key（可选，传 null 则不显示封面）
     */
    public void sendVideoMessage(String receiveIdType, String receiveId, String fileKey, String imageKey) throws IOException {
        String accessToken = getTenantAccessToken();
        String url = BASE_URL + "/im/v1/messages?receive_id_type=" + receiveIdType;

        // 构建消息内容
        JsonObject content = new JsonObject();
        content.addProperty("file_key", fileKey);
        if (imageKey != null && !imageKey.isEmpty()) {
            content.addProperty("image_key", imageKey);
        }

        JsonObject body = new JsonObject();
        body.addProperty("receive_id", receiveId);
        body.addProperty("msg_type", "media");
        body.addProperty("content", gson.toJson(content));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送视频消息: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送视频消息失败: " + responseBody);
                throw new IOException("发送视频消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "视频消息发送成功: " + responseBody);
        }
    }

    /**
     * WebSocket 连接信息
     */
    public static class WebSocketConnection {
        public final String url;

        public WebSocketConnection(String url) {
            this.url = url;
        }
    }
}
