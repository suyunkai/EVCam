package com.kooo.evcam.dingtalk;


import com.kooo.evcam.AppLog;
import android.util.Log;

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
 * 钉钉 API 客户端
 * 负责与远程查看服务器进行 HTTP 通信
 */
public class DingTalkApiClient {
    private static final String TAG = "DingTalkApiClient";
    private static final String BASE_URL = "https://api.dingtalk.com";
    private static final String OAPI_URL = "https://oapi.dingtalk.com";

    private final OkHttpClient httpClient;
    private final Gson gson;
    private final DingTalkConfig config;

    public DingTalkApiClient(DingTalkConfig config) {
        this.config = config;
        this.gson = new Gson();
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 获取 Access Token (使用旧版 API)
     */
    public String getAccessToken() throws IOException {
        // 检查缓存的 token 是否有效
        if (config.isTokenValid()) {
            String cachedToken = config.getAccessToken();
            AppLog.d(TAG, "使用缓存的 Access Token");
            return cachedToken;
        }

        // 获取新的 token - 使用旧版 API
        String url = OAPI_URL + "/gettoken?appkey=" + config.getClientId() +
                     "&appsecret=" + config.getClientSecret();

        AppLog.d(TAG, "正在获取新的 Access Token...");

        Request request = new Request.Builder()
                .url(url)
                .get()
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            AppLog.d(TAG, "Access Token 响应: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("获取 Access Token 失败: " + response.code() + " - " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            // 检查错误码
            if (jsonResponse.has("errcode")) {
                int errcode = jsonResponse.get("errcode").getAsInt();
                if (errcode != 0) {
                    String errmsg = jsonResponse.has("errmsg") ? jsonResponse.get("errmsg").getAsString() : "Unknown error";
                    throw new IOException("获取 Access Token 失败: errcode=" + errcode + ", errmsg=" + errmsg);
                }
            }

            if (jsonResponse.has("access_token")) {
                String accessToken = jsonResponse.get("access_token").getAsString();
                long expireIn = jsonResponse.get("expires_in").getAsLong();

                // 提前 5 分钟过期
                long expireTime = System.currentTimeMillis() + (expireIn - 300) * 1000;
                config.saveAccessToken(accessToken, expireTime);

                AppLog.d(TAG, "Access Token 获取成功");
                return accessToken;
            } else {
                throw new IOException("响应中没有 access_token: " + responseBody);
            }
        }
    }

    /**
     * 通过 sessionWebhook 发送文本消息（推荐方式）
     */
    public void sendMessageViaWebhook(String webhookUrl, String text) throws IOException {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            throw new IOException("Webhook URL 为空");
        }

        // 构建消息体 - 按照自定义机器人的格式
        JsonObject textObj = new JsonObject();
        textObj.addProperty("content", text);

        JsonObject body = new JsonObject();
        body.addProperty("msgtype", "text");
        body.add("text", textObj);

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "通过 Webhook 发送消息: " + requestJson);

        Request request = new Request.Builder()
                .url(webhookUrl)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "Webhook 发送消息失败，响应: " + responseBody);
                throw new IOException("Webhook 发送消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "Webhook 消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 判断是否为群聊会话
     * 钉钉的 conversationType 值：
     * - "1": 单聊
     * - "2": 群聊
     */
    private boolean isGroupConversation(String conversationType) {
        return "2".equals(conversationType);
    }

    /**
     * 发送文本消息（自动判断群聊或单聊）
     * 群聊使用 Webhook，单聊使用 API
     */
    public void sendTextMessage(String conversationId, String conversationType, String text) throws IOException {
        sendTextMessage(conversationId, conversationType, text, null);
    }

    /**
     * 发送文本消息到群聊或单聊
     * @param conversationId 会话ID
     * @param conversationType 会话类型（"1"=单聊，"2"=群聊）
     * @param text 消息内容
     * @param userId 用户ID（单聊时必需）
     */
    public void sendTextMessage(String conversationId, String conversationType, String text, String userId) throws IOException {
        if (isGroupConversation(conversationType)) {
            // 群聊：使用 Webhook 方式
            String webhookUrl = config.getWebhookUrl();
            if (webhookUrl != null && !webhookUrl.isEmpty()) {
                sendMessageViaWebhook(webhookUrl, text);
                return;
            }
            // 如果没有 Webhook，使用群聊 API
            sendTextMessageToGroup(conversationId, text);
        } else {
            // 单聊：使用单聊 API
            if (userId == null || userId.isEmpty()) {
                throw new IOException("发送单聊文本消息需要提供 userId");
            }
            sendTextMessageToUser(userId, text);
        }
    }

    /**
     * 发送文本消息到群聊（使用 API 方式）
     */
    private void sendTextMessageToGroup(String conversationId, String text) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/groupMessages/send";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("content", text);

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.addProperty("openConversationId", conversationId);
        body.addProperty("msgKey", "sampleText");
        body.addProperty("msgParam", gson.toJson(msgParam));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送群聊文本消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送群聊文本消息失败，响应: " + responseBody);
                throw new IOException("发送群聊文本消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "群聊文本消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 发送文本消息到单聊
     */
    private void sendTextMessageToUser(String userId, String text) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/oToMessages/batchSend";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("content", text);

        // 构建 userIds 数组
        com.google.gson.JsonArray userIds = new com.google.gson.JsonArray();
        userIds.add(userId);

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.add("userIds", userIds);
        body.addProperty("msgKey", "sampleText");
        body.addProperty("msgParam", gson.toJson(msgParam));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送单聊文本消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送单聊文本消息失败，响应: " + responseBody);
                throw new IOException("发送单聊文本消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "单聊文本消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 上传文件到钉钉
     */
    public String uploadFile(File file) throws IOException {
        return uploadMedia(file, "file");
    }

    /**
     * 上传图片到钉钉
     */
    public String uploadImage(File imageFile) throws IOException {
        return uploadMedia(imageFile, "image");
    }

    /**
     * 上传媒体文件到钉钉
     * @param file 文件
     * @param type 类型：file, image, voice, video
     */
    private String uploadMedia(File file, String type) throws IOException {
        String accessToken = getAccessToken();
        String url = OAPI_URL + "/media/upload?access_token=" + accessToken + "&type=" + type;

        RequestBody fileBody = RequestBody.create(
                MediaType.parse("application/octet-stream"),
                file
        );

        MultipartBody requestBody = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("media", file.getName(), fileBody)
                .build();

        Request request = new Request.Builder()
                .url(url)
                .post(requestBody)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("上传媒体文件失败: " + response.code());
            }

            String responseBody = response.body().string();
            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse.has("media_id")) {
                String mediaId = jsonResponse.get("media_id").getAsString();
                AppLog.d(TAG, type + " 上传成功，media_id: " + mediaId);
                return mediaId;
            } else {
                throw new IOException("响应中没有 media_id: " + responseBody);
            }
        }
    }

    /**
     * 发送文件消息（自动判断群聊或单聊）
     * @param conversationId 会话ID
     * @param conversationType 会话类型（"1"=单聊，"2"=群聊）
     * @param mediaId 媒体文件ID
     * @param fileName 文件名
     * @param userId 用户ID（单聊时必需）
     */
    public void sendFileMessage(String conversationId, String conversationType, String mediaId, String fileName, String userId) throws IOException {
        if (isGroupConversation(conversationType)) {
            // 群聊：使用群聊 API
            sendFileMessageToGroup(conversationId, mediaId, fileName);
        } else {
            // 单聊：使用单聊 API
            if (userId == null || userId.isEmpty()) {
                throw new IOException("发送单聊文件消息需要提供 userId");
            }
            sendFileMessageToUser(userId, mediaId, fileName);
        }
    }

    /**
     * 发送文件消息到群聊
     * 使用群聊消息 API (orgGroupSend)
     */
    private void sendFileMessageToGroup(String conversationId, String mediaId, String fileName) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/groupMessages/send";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("mediaId", mediaId);
        msgParam.addProperty("fileName", fileName);

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.addProperty("openConversationId", conversationId);
        body.addProperty("msgKey", "sampleFile");
        body.addProperty("msgParam", gson.toJson(msgParam));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送群聊文件消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送群聊文件消息失败，响应: " + responseBody);
                throw new IOException("发送群聊文件消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "群聊文件消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 发送文件消息到单聊
     * 使用单聊消息 API (batchSendOTO)
     */
    public void sendFileMessageToUser(String userId, String mediaId, String fileName) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/oToMessages/batchSend";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("mediaId", mediaId);
        msgParam.addProperty("fileName", fileName);

        // 构建 userIds 数组
        com.google.gson.JsonArray userIds = new com.google.gson.JsonArray();
        if (userId != null && !userId.isEmpty()) {
            userIds.add(userId);
        } else {
            throw new IOException("发送单聊文件消息需要提供 userId");
        }

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.add("userIds", userIds);
        body.addProperty("msgKey", "sampleFile");
        body.addProperty("msgParam", gson.toJson(msgParam));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送单聊文件消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送单聊文件消息失败，响应: " + responseBody);
                throw new IOException("发送单聊文件消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "单聊文件消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 发送视频消息（自动判断群聊或单聊）
     * @param conversationId 会话ID
     * @param conversationType 会话类型（"1"=单聊，"2"=群聊）
     * @param videoMediaId 视频媒体ID
     * @param picMediaId 封面图媒体ID
     * @param duration 视频时长（秒）
     * @param userId 用户ID（单聊时必需）
     */
    public void sendVideoMessage(String conversationId, String conversationType, String videoMediaId, String picMediaId,
                                  int duration, String userId) throws IOException {
        if (isGroupConversation(conversationType)) {
            // 群聊：使用群聊 API
            sendVideoMessageToGroup(conversationId, videoMediaId, picMediaId, duration);
        } else {
            // 单聊：使用单聊 API
            if (userId == null || userId.isEmpty()) {
                throw new IOException("发送单聊视频消息需要提供 userId");
            }
            sendVideoMessageToUser(userId, videoMediaId, picMediaId, duration);
        }
    }

    /**
     * 发送视频消息到群聊
     */
    private void sendVideoMessageToGroup(String conversationId, String videoMediaId,
                                          String picMediaId, int duration) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/groupMessages/send";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("videoMediaId", videoMediaId);
        msgParam.addProperty("picMediaId", picMediaId);
        msgParam.addProperty("videoType", "mp4");
        msgParam.addProperty("duration", String.valueOf(duration));
        msgParam.addProperty("height", "200");  // 视频显示高度

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.addProperty("openConversationId", conversationId);
        body.addProperty("msgKey", "sampleVideo");
        body.addProperty("msgParam", gson.toJson(msgParam));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送群聊视频消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送群聊视频消息失败，响应: " + responseBody);
                throw new IOException("发送群聊视频消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "群聊视频消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 发送视频消息到单聊
     */
    private void sendVideoMessageToUser(String userId, String videoMediaId,
                                         String picMediaId, int duration) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/oToMessages/batchSend";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("videoMediaId", videoMediaId);
        msgParam.addProperty("picMediaId", picMediaId);
        msgParam.addProperty("videoType", "mp4");
        msgParam.addProperty("duration", String.valueOf(duration));
        msgParam.addProperty("height", "200");

        // 构建 userIds 数组
        com.google.gson.JsonArray userIds = new com.google.gson.JsonArray();
        userIds.add(userId);

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.add("userIds", userIds);
        body.addProperty("msgKey", "sampleVideo");
        body.addProperty("msgParam", gson.toJson(msgParam));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送单聊视频消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送单聊视频消息失败，响应: " + responseBody);
                throw new IOException("发送单聊视频消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "单聊视频消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 发送图片消息（自动判断群聊或单聊）
     * @param conversationId 会话ID
     * @param conversationType 会话类型（"1"=单聊，"2"=群聊）
     * @param photoURL 图片URL（钉钉上传后的URL）
     * @param userId 用户ID（单聊时必需）
     */
    public void sendImageMessage(String conversationId, String conversationType, String photoURL, String userId) throws IOException {
        if (isGroupConversation(conversationType)) {
            // 群聊：使用群聊 API
            sendImageMessageToGroup(conversationId, photoURL);
        } else {
            // 单聊：使用单聊 API
            if (userId == null || userId.isEmpty()) {
                throw new IOException("发送单聊图片消息需要提供 userId");
            }
            sendImageMessageToUser(userId, photoURL);
        }
    }

    /**
     * 发送图片消息到群聊
     */
    private void sendImageMessageToGroup(String conversationId, String photoURL) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/groupMessages/send";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("photoURL", photoURL);

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.addProperty("openConversationId", conversationId);
        body.addProperty("msgKey", "sampleImageMsg");
        body.addProperty("msgParam", gson.toJson(msgParam));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送群聊图片消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送群聊图片消息失败，响应: " + responseBody);
                throw new IOException("发送群聊图片消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "群聊图片消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 发送图片消息到单聊
     */
    private void sendImageMessageToUser(String userId, String photoURL) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/oToMessages/batchSend";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("photoURL", photoURL);

        // 构建 userIds 数组
        com.google.gson.JsonArray userIds = new com.google.gson.JsonArray();
        userIds.add(userId);

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.add("userIds", userIds);
        body.addProperty("msgKey", "sampleImageMsg");
        body.addProperty("msgParam", gson.toJson(msgParam));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送单聊图片消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送单聊图片消息失败，响应: " + responseBody);
                throw new IOException("发送单聊图片消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "单聊图片消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 发送Markdown消息（自动判断群聊或单聊）
     * @param conversationId 会话ID
     * @param conversationType 会话类型（"1"=单聊，"2"=群聊）
     * @param title 标题
     * @param text Markdown文本
     * @param userId 用户ID（单聊时必需）
     */
    public void sendMarkdownMessage(String conversationId, String conversationType, String title, String text, String userId) throws IOException {
        if (isGroupConversation(conversationType)) {
            // 群聊：使用群聊 API
            sendMarkdownMessageToGroup(conversationId, title, text);
        } else {
            // 单聊：使用单聊 API
            if (userId == null || userId.isEmpty()) {
                throw new IOException("发送单聊Markdown消息需要提供 userId");
            }
            sendMarkdownMessageToUser(userId, title, text);
        }
    }

    /**
     * 发送Markdown消息到群聊
     */
    private void sendMarkdownMessageToGroup(String conversationId, String title, String text) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/groupMessages/send";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("title", title);
        msgParam.addProperty("text", text);

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.addProperty("openConversationId", conversationId);
        body.addProperty("msgKey", "sampleMarkdown");
        body.addProperty("msgParam", gson.toJson(msgParam));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送群聊Markdown消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送群聊Markdown消息失败，响应: " + responseBody);
                throw new IOException("发送群聊Markdown消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "群聊Markdown消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * 发送Markdown消息到单聊
     */
    private void sendMarkdownMessageToUser(String userId, String title, String text) throws IOException {
        String accessToken = getAccessToken();
        String url = BASE_URL + "/v1.0/robot/oToMessages/batchSend";

        // 构建消息参数
        JsonObject msgParam = new JsonObject();
        msgParam.addProperty("title", title);
        msgParam.addProperty("text", text);

        // 构建 userIds 数组
        com.google.gson.JsonArray userIds = new com.google.gson.JsonArray();
        userIds.add(userId);

        JsonObject body = new JsonObject();
        body.addProperty("robotCode", config.getClientId());
        body.add("userIds", userIds);
        body.addProperty("msgKey", "sampleMarkdown");
        body.addProperty("msgParam", gson.toJson(msgParam));

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "发送单聊Markdown消息请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .header("x-acs-dingtalk-access-token", accessToken)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                AppLog.e(TAG, "发送单聊Markdown消息失败，响应: " + responseBody);
                throw new IOException("发送单聊Markdown消息失败: " + response.code() + ", " + responseBody);
            }
            AppLog.d(TAG, "单聊Markdown消息发送成功，响应: " + responseBody);
        }
    }

    /**
     * Stream 连接信息
     */
    public static class StreamConnection {
        public final String endpoint;
        public final String ticket;

        public StreamConnection(String endpoint, String ticket) {
            this.endpoint = endpoint;
            this.ticket = ticket;
        }
    }

    /**
     * 获取 Stream 连接信息
     */
    public StreamConnection getStreamConnection() throws IOException {
        String url = BASE_URL + "/v1.0/gateway/connections/open";

        // 构建 subscriptions 数组
        // 订阅机器人消息事件
        com.google.gson.JsonArray subscriptions = new com.google.gson.JsonArray();

        // 订阅所有事件（如果开放平台已配置具体事件）
        JsonObject subscription1 = new JsonObject();
        subscription1.addProperty("type", "CALLBACK");
        subscription1.addProperty("topic", "/v1.0/im/bot/messages/get");
        subscriptions.add(subscription1);

        // 也订阅通用回调
        JsonObject subscription2 = new JsonObject();
        subscription2.addProperty("type", "CALLBACK");
        subscription2.addProperty("topic", "*");
        subscriptions.add(subscription2);

        JsonObject body = new JsonObject();
        body.addProperty("clientId", config.getClientId());
        body.addProperty("clientSecret", config.getClientSecret());
        body.add("subscriptions", subscriptions);

        String requestJson = gson.toJson(body);
        AppLog.d(TAG, "Stream 请求: " + requestJson);

        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        MediaType.parse("application/json"),
                        requestJson
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body().string();
            AppLog.d(TAG, "Stream 响应: " + responseBody);

            if (!response.isSuccessful()) {
                throw new IOException("获取 Stream 连接信息失败: " + response.code() + " - " + responseBody);
            }

            JsonObject jsonResponse = gson.fromJson(responseBody, JsonObject.class);

            if (jsonResponse.has("endpoint") && jsonResponse.has("ticket")) {
                String endpoint = jsonResponse.get("endpoint").getAsString();
                String ticket = jsonResponse.get("ticket").getAsString();
                AppLog.d(TAG, "Stream 连接信息获取成功: " + endpoint);
                return new StreamConnection(endpoint, ticket);
            } else {
                throw new IOException("响应中缺少 endpoint 或 ticket: " + responseBody);
            }
        }
    }
}
