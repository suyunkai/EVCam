package com.kooo.evcam.wechat;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.kooo.evcam.AppLog;
import com.kooo.evcam.WakeUpHelper;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 微信云开发管理器
 * 使用微信官方 HTTP API 直接操作云数据库
 */
public class WechatCloudManager {
    private static final String TAG = "WechatCloudManager";
    
    // 小程序凭证
    private static final String APP_ID = "wx1df526b63078a9e5";
    private static final String APP_SECRET = "7b6fd801e42004289cb831c6b85f52d9";
    
    // 云开发环境ID
    private static final String CLOUD_ENV = "cloudbase-0gt2twhpdc512c30";
    
    // 微信API地址
    private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String DB_QUERY_URL = "https://api.weixin.qq.com/tcb/databasequery";
    private static final String DB_UPDATE_URL = "https://api.weixin.qq.com/tcb/databaseupdate";
    private static final String DB_ADD_URL = "https://api.weixin.qq.com/tcb/databaseadd";
    private static final String UPLOAD_FILE_URL = "https://api.weixin.qq.com/tcb/uploadfile";
    
    // 心跳间隔（毫秒）
    private static final long HEARTBEAT_INTERVAL = 15000;     // 15秒心跳
    
    // 命令轮询间隔（毫秒）
    private static final long POLL_INTERVAL_NORMAL = 5000;    // 5秒轮询
    private static final long POLL_INTERVAL_ACTIVE = 2000;    // 活跃时2秒轮询
    
    private static final int MAX_CONSECUTIVE_ERRORS = 10;
    private static final long ERROR_BACKOFF_MS = 60000;
    
    // Token 缓存
    private static final long TOKEN_EXPIRE_BUFFER = 300000;   // 提前5分钟刷新token

    private final Context context;
    private final WechatMiniConfig config;
    private final ConnectionCallback connectionCallback;
    private final Handler mainHandler;
    private final Handler workHandler;
    private final OkHttpClient httpClient;
    private final Gson gson;

    private boolean isRunning = false;
    private boolean isConnected = false;
    private int consecutiveErrors = 0;
    private CommandCallback currentCommandCallback;
    private Runnable heartbeatRunnable;
    private Runnable pollRunnable;
    private long lastCommandTime = 0;
    
    // Access Token 缓存
    private String accessToken = null;
    private long tokenExpireTime = 0;

    public interface ConnectionCallback {
        void onConnected();
        void onDisconnected();
        void onError(String error);
        void onBindStatusChanged(boolean bound, String userNickname);
    }

    public interface CommandCallback {
        void onRecordCommand(String commandId, int durationSeconds);
        void onPhotoCommand(String commandId);
        String getStatusInfo();
        String onStartRecordingCommand();
        String onStopRecordingCommand();
        void onStartPreviewCommand();
        void onStopPreviewCommand();
    }

    public WechatCloudManager(Context context, WechatMiniConfig config, ConnectionCallback callback) {
        this.context = context;
        this.config = config;
        this.connectionCallback = callback;
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.workHandler = new Handler(Looper.getMainLooper());
        this.gson = new Gson();
        
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * 启动云开发连接
     */
    public void start(CommandCallback commandCallback) {
        if (isRunning) {
            AppLog.w(TAG, "云开发管理器已在运行");
            return;
        }

        this.currentCommandCallback = commandCallback;
        isRunning = true;
        consecutiveErrors = 0;

        AppLog.d(TAG, "启动微信云开发连接...");
        AppLog.d(TAG, "设备ID: " + config.getDeviceId());

        // 获取 token 并开始
        new Thread(() -> {
            if (refreshAccessToken()) {
                isConnected = true;
                mainHandler.post(() -> connectionCallback.onConnected());
                
                // 确保设备存在于数据库
                ensureDeviceExists();
                
                // 启动心跳和轮询
                startHeartbeat();
                startPolling();
            } else {
                handleError("获取 access_token 失败");
            }
        }).start();
    }

    /**
     * 获取/刷新 Access Token
     */
    private synchronized boolean refreshAccessToken() {
        // 检查缓存是否有效
        if (accessToken != null && System.currentTimeMillis() < tokenExpireTime - TOKEN_EXPIRE_BUFFER) {
            return true;
        }
        
        try {
            String url = TOKEN_URL + "?grant_type=client_credential&appid=" + APP_ID + "&secret=" + APP_SECRET;
            
            Request request = new Request.Builder()
                    .url(url)
                    .get()
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                AppLog.d(TAG, "Token响应: " + responseBody);
                
                if (response.isSuccessful()) {
                    JsonObject result = gson.fromJson(responseBody, JsonObject.class);
                    
                    if (result.has("access_token")) {
                        accessToken = result.get("access_token").getAsString();
                        int expiresIn = result.get("expires_in").getAsInt();
                        tokenExpireTime = System.currentTimeMillis() + expiresIn * 1000L;
                        
                        AppLog.d(TAG, "获取 access_token 成功，有效期: " + expiresIn + "秒");
                        return true;
                    } else {
                        String errMsg = result.has("errmsg") ? result.get("errmsg").getAsString() : "未知错误";
                        AppLog.e(TAG, "获取 token 失败: " + errMsg);
                        return false;
                    }
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "获取 token 异常", e);
        }
        return false;
    }

    /**
     * 确保设备存在于数据库中
     */
    private void ensureDeviceExists() {
        try {
            // 查询设备是否存在
            String query = "db.collection(\"devices\").where({deviceId:\"" + config.getDeviceId() + "\"}).get()";
            JsonObject queryResult = executeDbQuery(query);
            
            if (queryResult != null && queryResult.has("data")) {
                JsonArray data = queryResult.getAsJsonArray("data");
                if (data.size() == 0) {
                    // 设备不存在，创建新记录
                    AppLog.d(TAG, "设备不存在，创建新记录");
                    createDeviceRecord();
                } else {
                    AppLog.d(TAG, "设备已存在于数据库");
                    // 更新心跳
                    updateHeartbeat();
                }
            }
        } catch (Exception e) {
            AppLog.e(TAG, "检查设备存在性失败", e);
        }
    }

    /**
     * 创建设备记录
     */
    private void createDeviceRecord() {
        try {
            long now = System.currentTimeMillis();
            String data = "{" +
                    "\"deviceId\":\"" + config.getDeviceId() + "\"," +
                    "\"deviceName\":\"" + config.getDeviceName() + "\"," +
                    "\"deviceModel\":\"" + android.os.Build.MODEL + "\"," +
                    "\"boundUserId\":\"\"," +
                    "\"lastHeartbeat\":" + now + "," +
                    "\"recording\":false," +
                    "\"statusInfo\":\"\"," +
                    "\"createTime\":" + now + "," +
                    "\"updateTime\":" + now +
                    "}";
            
            String query = "db.collection(\"devices\").add({data:" + data + "})";
            executeDbAdd(query);
            AppLog.d(TAG, "设备记录创建成功");
        } catch (Exception e) {
            AppLog.e(TAG, "创建设备记录失败", e);
        }
    }

    /**
     * 启动心跳定时任务
     */
    private void startHeartbeat() {
        stopHeartbeat();
        
        heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                
                new Thread(() -> {
                    updateHeartbeat();
                }).start();
                
                workHandler.postDelayed(this, HEARTBEAT_INTERVAL);
            }
        };
        
        // 立即发送一次心跳
        new Thread(() -> updateHeartbeat()).start();
        
        workHandler.postDelayed(heartbeatRunnable, HEARTBEAT_INTERVAL);
        AppLog.d(TAG, "心跳已启动，间隔: " + HEARTBEAT_INTERVAL + "ms");
    }

    /**
     * 更新心跳
     */
    private void updateHeartbeat() {
        try {
            // 刷新token
            if (!refreshAccessToken()) {
                handleError("刷新 token 失败");
                return;
            }
            
            long now = System.currentTimeMillis();
            
            // 简化：只更新心跳时间，不更新 statusInfo（避免特殊字符问题）
            String query = "db.collection(\"devices\").where({deviceId:\"" + config.getDeviceId() + "\"}).update({data:{" +
                    "lastHeartbeat:" + now + "," +
                    "updateTime:" + now +
                    "}})";
            
            JsonObject result = executeDbUpdate(query);
            
            if (result != null) {
                consecutiveErrors = 0;
                if (!isConnected) {
                    isConnected = true;
                    mainHandler.post(() -> connectionCallback.onConnected());
                }
                AppLog.d(TAG, "心跳更新成功，设备ID: " + config.getDeviceId());
            } else {
                // 可能是 token 过期，强制刷新
                AppLog.w(TAG, "心跳更新返回null，强制刷新token");
                forceRefreshToken();
            }
        } catch (Exception e) {
            AppLog.e(TAG, "更新心跳失败", e);
            handleError("心跳失败: " + e.getMessage());
        }
    }
    
    /**
     * 强制刷新 token（清除缓存后重新获取）
     */
    private void forceRefreshToken() {
        accessToken = null;
        tokenExpireTime = 0;
        refreshAccessToken();
    }

    /**
     * 停止心跳
     */
    private void stopHeartbeat() {
        if (heartbeatRunnable != null) {
            workHandler.removeCallbacks(heartbeatRunnable);
            heartbeatRunnable = null;
        }
    }

    /**
     * 启动命令轮询
     */
    private void startPolling() {
        stopPolling();
        
        pollRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isRunning) return;
                
                new Thread(() -> pollCommands()).start();
                
                long interval = calculatePollInterval();
                workHandler.postDelayed(this, interval);
            }
        };
        
        workHandler.postDelayed(pollRunnable, POLL_INTERVAL_NORMAL);
        AppLog.d(TAG, "命令轮询已启动");
    }

    /**
     * 停止轮询
     */
    private void stopPolling() {
        if (pollRunnable != null) {
            workHandler.removeCallbacks(pollRunnable);
            pollRunnable = null;
        }
    }

    /**
     * 计算轮询间隔
     */
    private long calculatePollInterval() {
        long timeSinceLastCommand = System.currentTimeMillis() - lastCommandTime;
        
        if (timeSinceLastCommand < 60000) {
            return POLL_INTERVAL_ACTIVE;
        } else {
            return POLL_INTERVAL_NORMAL;
        }
    }

    /**
     * 轮询获取待执行的命令
     */
    private void pollCommands() {
        try {
            if (!refreshAccessToken()) {
                return;
            }
            
            // 查询待处理的命令
            String query = "db.collection(\"commands\").where({deviceId:\"" + config.getDeviceId() + "\",status:\"pending\"}).orderBy(\"createTime\",\"asc\").limit(10).get()";
            
            JsonObject result = executeDbQuery(query);
            
            if (result != null && result.has("data")) {
                JsonArray commands = result.getAsJsonArray("data");
                
                if (commands.size() > 0) {
                    AppLog.d(TAG, "收到 " + commands.size() + " 个待处理命令");
                    
                    for (int i = 0; i < commands.size(); i++) {
                        String cmdStr = commands.get(i).getAsString();
                        JsonObject cmd = gson.fromJson(cmdStr, JsonObject.class);
                        processCommand(cmd);
                    }
                }
            }
        } catch (Exception e) {
            AppLog.w(TAG, "轮询命令失败: " + e.getMessage());
        }
    }

    /**
     * 处理命令
     */
    private void processCommand(JsonObject cmd) {
        String commandId = cmd.has("commandId") ? cmd.get("commandId").getAsString() : "";
        String docId = cmd.has("_id") ? cmd.get("_id").getAsString() : "";
        String command = cmd.has("command") ? cmd.get("command").getAsString() : "";
        JsonObject params = cmd.has("params") ? cmd.getAsJsonObject("params") : new JsonObject();
        
        AppLog.d(TAG, "处理命令: " + command + ", ID: " + commandId);
        lastCommandTime = System.currentTimeMillis();
        
        // 标记命令为执行中
        updateCommandStatus(docId, "executing");
        
        mainHandler.post(() -> {
            switch (command) {
                case "record":
                    int duration = params.has("duration") ? params.get("duration").getAsInt() : 60;
                    AppLog.d(TAG, "执行录制命令，时长: " + duration + "秒");
                    WakeUpHelper.launchForWechatRecording(context, commandId, duration);
                    break;
                    
                case "photo":
                    AppLog.d(TAG, "执行拍照命令");
                    WakeUpHelper.launchForWechatPhoto(context, commandId);
                    break;
                    
                case "status":
                    if (currentCommandCallback != null) {
                        String statusInfo = currentCommandCallback.getStatusInfo();
                        reportCommandResult(commandId, docId, true, statusInfo);
                    }
                    break;
                    
                case "start_recording":
                    if (currentCommandCallback != null) {
                        String result = currentCommandCallback.onStartRecordingCommand();
                        reportCommandResult(commandId, docId, true, result);
                    }
                    break;
                    
                case "stop_recording":
                    if (currentCommandCallback != null) {
                        String result = currentCommandCallback.onStopRecordingCommand();
                        reportCommandResult(commandId, docId, true, result);
                    }
                    break;
                    
                case "start_preview":
                    AppLog.d(TAG, "执行开启预览命令");
                    if (currentCommandCallback != null) {
                        currentCommandCallback.onStartPreviewCommand();
                        reportCommandResult(commandId, docId, true, "预览已开启");
                    }
                    break;
                    
                case "stop_preview":
                    AppLog.d(TAG, "执行停止预览命令");
                    if (currentCommandCallback != null) {
                        currentCommandCallback.onStopPreviewCommand();
                        reportCommandResult(commandId, docId, true, "预览已停止");
                    }
                    break;
                    
                default:
                    AppLog.w(TAG, "未知命令: " + command);
                    reportCommandResult(commandId, docId, false, "未知命令: " + command);
                    break;
            }
        });
    }

    /**
     * 更新命令状态
     */
    private void updateCommandStatus(String docId, String status) {
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                String query = "db.collection(\"commands\").doc(\"" + docId + "\").update({data:{" +
                        "status:\"" + status + "\"," +
                        "updateTime:" + now +
                        "}})";
                executeDbUpdate(query);
            } catch (Exception e) {
                AppLog.e(TAG, "更新命令状态失败", e);
            }
        }).start();
    }

    /**
     * 上报命令执行结果
     */
    public void reportCommandResult(String commandId, String docId, boolean success, String message) {
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                String status = success ? "completed" : "failed";
                String query = "db.collection(\"commands\").doc(\"" + docId + "\").update({data:{" +
                        "status:\"" + status + "\"," +
                        "result:\"" + escapeString(message) + "\"," +
                        "completedTime:" + now + "," +
                        "updateTime:" + now +
                        "}})";
                
                executeDbUpdate(query);
                AppLog.d(TAG, "命令结果上报成功");
            } catch (Exception e) {
                AppLog.e(TAG, "上报命令结果失败", e);
            }
        }).start();
    }
    
    /**
     * 上报命令执行结果（简化版，用于外部调用）
     */
    public void reportCommandResult(String commandId, boolean success, String message) {
        if (commandId == null || commandId.isEmpty()) {
            AppLog.w(TAG, "命令ID为空，无法报告结果");
            return;
        }
        
        // 通过 commandId 查找文档ID
        new Thread(() -> {
            try {
                AppLog.d(TAG, "查找命令文档: " + commandId);
                String query = "db.collection(\"commands\").where({commandId:\"" + commandId + "\"}).get()";
                JsonObject result = executeDbQuery(query);
                
                if (result != null && result.has("data")) {
                    JsonArray data = result.getAsJsonArray("data");
                    AppLog.d(TAG, "查询结果数量: " + data.size());
                    
                    if (data.size() > 0) {
                        // 数据可能是字符串或对象，需要处理两种情况
                        String docId = "";
                        try {
                            // 尝试作为字符串解析
                            if (data.get(0).isJsonPrimitive()) {
                                String cmdStr = data.get(0).getAsString();
                                JsonObject cmd = gson.fromJson(cmdStr, JsonObject.class);
                                docId = cmd.has("_id") ? cmd.get("_id").getAsString() : "";
                            } else {
                                // 直接作为对象处理
                                JsonObject cmd = data.get(0).getAsJsonObject();
                                docId = cmd.has("_id") ? cmd.get("_id").getAsString() : "";
                            }
                        } catch (Exception e) {
                            AppLog.e(TAG, "解析命令文档失败", e);
                        }
                        
                        if (!docId.isEmpty()) {
                            AppLog.d(TAG, "找到命令文档ID: " + docId);
                            reportCommandResult(commandId, docId, success, message);
                        } else {
                            // 尝试直接通过 commandId 更新
                            AppLog.d(TAG, "未找到文档ID，尝试直接更新");
                            updateCommandByCommandId(commandId, success, message);
                        }
                    } else {
                        AppLog.w(TAG, "未找到命令记录: " + commandId);
                    }
                } else {
                    AppLog.w(TAG, "查询命令失败或无数据");
                }
            } catch (Exception e) {
                AppLog.e(TAG, "查找命令文档失败", e);
            }
        }).start();
    }
    
    /**
     * 直接通过 commandId 更新命令状态（备用方法）
     */
    private void updateCommandByCommandId(String commandId, boolean success, String message) {
        try {
            long now = System.currentTimeMillis();
            String status = success ? "completed" : "failed";
            String query = "db.collection(\"commands\").where({commandId:\"" + commandId + "\"}).update({data:{" +
                    "status:\"" + status + "\"," +
                    "result:\"" + escapeString(message) + "\"," +
                    "completedTime:" + now + "," +
                    "updateTime:" + now +
                    "}})";
            
            JsonObject result = executeDbUpdate(query);
            if (result != null) {
                AppLog.d(TAG, "命令状态更新成功（通过commandId）");
            }
        } catch (Exception e) {
            AppLog.e(TAG, "更新命令状态失败", e);
        }
    }

    /**
     * 执行数据库查询
     */
    private JsonObject executeDbQuery(String query) throws IOException {
        return executeDbQueryWithRetry(query, true);
    }
    
    private JsonObject executeDbQueryWithRetry(String query, boolean allowRetry) throws IOException {
        String url = DB_QUERY_URL + "?access_token=" + accessToken;
        
        JsonObject body = new JsonObject();
        body.addProperty("env", CLOUD_ENV);
        body.addProperty("query", query);
        
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"),
                        gson.toJson(body)
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (response.isSuccessful()) {
                JsonObject result = gson.fromJson(responseBody, JsonObject.class);
                if (result.has("errcode")) {
                    int errcode = result.get("errcode").getAsInt();
                    if (errcode == 40001 && allowRetry) {
                        // Token 过期，强制刷新后重试
                        AppLog.w(TAG, "Token过期(40001)，刷新后重试查询");
                        forceRefreshToken();
                        return executeDbQueryWithRetry(query, false);
                    } else if (errcode != 0) {
                        AppLog.w(TAG, "数据库查询错误: " + responseBody);
                        return null;
                    }
                }
                return result;
            }
        }
        return null;
    }

    /**
     * 执行数据库更新
     */
    private JsonObject executeDbUpdate(String query) throws IOException {
        return executeDbUpdateWithRetry(query, true);
    }
    
    private JsonObject executeDbUpdateWithRetry(String query, boolean allowRetry) throws IOException {
        String url = DB_UPDATE_URL + "?access_token=" + accessToken;
        
        JsonObject body = new JsonObject();
        body.addProperty("env", CLOUD_ENV);
        body.addProperty("query", query);
        
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"),
                        gson.toJson(body)
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (response.isSuccessful()) {
                JsonObject result = gson.fromJson(responseBody, JsonObject.class);
                if (result.has("errcode")) {
                    int errcode = result.get("errcode").getAsInt();
                    if (errcode == 40001 && allowRetry) {
                        // Token 过期，强制刷新后重试
                        AppLog.w(TAG, "Token过期(40001)，刷新后重试更新");
                        forceRefreshToken();
                        return executeDbUpdateWithRetry(query, false);
                    } else if (errcode != 0) {
                        AppLog.w(TAG, "数据库更新错误: " + responseBody);
                        return null;
                    }
                }
                return result;
            }
        }
        return null;
    }

    /**
     * 执行数据库添加
     */
    private JsonObject executeDbAdd(String query) throws IOException {
        String url = DB_ADD_URL + "?access_token=" + accessToken;
        
        JsonObject body = new JsonObject();
        body.addProperty("env", CLOUD_ENV);
        body.addProperty("query", query);
        
        Request request = new Request.Builder()
                .url(url)
                .post(RequestBody.create(
                        MediaType.parse("application/json; charset=utf-8"),
                        gson.toJson(body)
                ))
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            
            if (response.isSuccessful()) {
                JsonObject result = gson.fromJson(responseBody, JsonObject.class);
                if (result.has("errcode") && result.get("errcode").getAsInt() != 0) {
                    AppLog.w(TAG, "数据库添加错误: " + responseBody);
                    return null;
                }
                return result;
            }
        }
        return null;
    }

    /**
     * 转义字符串中的特殊字符
     */
    private String escapeString(String str) {
        if (str == null) return "";
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }

    /**
     * 处理错误
     */
    private void handleError(String error) {
        consecutiveErrors++;
        AppLog.w(TAG, "错误 (" + consecutiveErrors + "/" + MAX_CONSECUTIVE_ERRORS + "): " + error);
        
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            AppLog.e(TAG, "连续错误次数过多，暂停连接 " + (ERROR_BACKOFF_MS / 1000) + " 秒");
            
            isConnected = false;
            stopHeartbeat();
            stopPolling();
            mainHandler.post(() -> {
                connectionCallback.onError(error);
                connectionCallback.onDisconnected();
            });
            
            // 退避后重试
            workHandler.postDelayed(() -> {
                if (isRunning) {
                    consecutiveErrors = 0;
                    accessToken = null;  // 清除token缓存
                    new Thread(() -> {
                        if (refreshAccessToken()) {
                            isConnected = true;
                            mainHandler.post(() -> connectionCallback.onConnected());
                            startHeartbeat();
                            startPolling();
                        }
                    }).start();
                }
            }, ERROR_BACKOFF_MS);
        }
    }

    /**
     * 上传文件到微信云存储
     * @param file 要上传的文件
     * @param cloudPath 云存储路径，如 "photos/xxx.jpg"
     * @return 云存储 fileID，失败返回 null
     */
    public String uploadFileToCloud(java.io.File file, String cloudPath) {
        try {
            if (!refreshAccessToken()) {
                AppLog.e(TAG, "刷新token失败，无法上传文件");
                return null;
            }
            
            // 第一步：获取上传链接
            String url = UPLOAD_FILE_URL + "?access_token=" + accessToken;
            
            JsonObject body = new JsonObject();
            body.addProperty("env", CLOUD_ENV);
            body.addProperty("path", cloudPath);
            
            Request request = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(
                            MediaType.parse("application/json; charset=utf-8"),
                            gson.toJson(body)
                    ))
                    .build();
            
            String uploadUrl = null;
            String authorization = null;
            String token = null;
            String cosFileId = null;
            String fileId = null;
            
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                AppLog.d(TAG, "获取上传链接响应: " + responseBody);
                
                if (response.isSuccessful()) {
                    JsonObject result = gson.fromJson(responseBody, JsonObject.class);
                    if (result.has("errcode") && result.get("errcode").getAsInt() != 0) {
                        AppLog.e(TAG, "获取上传链接失败: " + responseBody);
                        return null;
                    }
                    
                    uploadUrl = result.has("url") ? result.get("url").getAsString() : null;
                    authorization = result.has("authorization") ? result.get("authorization").getAsString() : null;
                    token = result.has("token") ? result.get("token").getAsString() : null;
                    cosFileId = result.has("cos_file_id") ? result.get("cos_file_id").getAsString() : null;
                    fileId = result.has("file_id") ? result.get("file_id").getAsString() : null;
                }
            }
            
            if (uploadUrl == null || authorization == null || token == null) {
                AppLog.e(TAG, "获取上传凭证失败");
                return null;
            }
            
            // 第二步：上传文件
            AppLog.d(TAG, "开始上传文件: " + file.getName() + " -> " + cloudPath);
            
            // 构建 multipart 请求
            okhttp3.MultipartBody.Builder multipartBuilder = new okhttp3.MultipartBody.Builder()
                    .setType(okhttp3.MultipartBody.FORM)
                    .addFormDataPart("key", cloudPath)
                    .addFormDataPart("Signature", authorization)
                    .addFormDataPart("x-cos-security-token", token)
                    .addFormDataPart("x-cos-meta-fileid", cosFileId);
            
            // 添加文件
            String mimeType = cloudPath.endsWith(".jpg") || cloudPath.endsWith(".jpeg") 
                    ? "image/jpeg" 
                    : cloudPath.endsWith(".png") ? "image/png" 
                    : cloudPath.endsWith(".mp4") ? "video/mp4" 
                    : "application/octet-stream";
            
            multipartBuilder.addFormDataPart("file", file.getName(),
                    RequestBody.create(MediaType.parse(mimeType), file));
            
            Request uploadRequest = new Request.Builder()
                    .url(uploadUrl)
                    .post(multipartBuilder.build())
                    .build();
            
            try (Response uploadResponse = httpClient.newCall(uploadRequest).execute()) {
                if (uploadResponse.isSuccessful()) {
                    AppLog.d(TAG, "文件上传成功: " + fileId);
                    return fileId;
                } else {
                    String errorBody = uploadResponse.body() != null ? uploadResponse.body().string() : "";
                    AppLog.e(TAG, "文件上传失败: " + uploadResponse.code() + " - " + errorBody);
                    return null;
                }
            }
            
        } catch (Exception e) {
            AppLog.e(TAG, "上传文件异常", e);
            return null;
        }
    }
    
    /**
     * 上传照片并更新数据库记录
     */
    public void uploadPhotoToCloudAsync(java.io.File file, String commandId, UploadCallback callback) {
        new Thread(() -> {
            try {
                String deviceId = config.getDeviceId();
                String fileName = file.getName();
                long now = System.currentTimeMillis();
                
                // 生成云存储路径
                String cloudPath = "photos/" + deviceId + "/" + fileName;
                
                // 上传文件
                String fileId = uploadFileToCloud(file, cloudPath);
                
                if (fileId != null) {
                    // 添加或更新文件记录到数据库
                    String recordId = "file_" + deviceId + "_" + now + "_" + fileName.hashCode();
                    String query = "db.collection(\"files\").add({data:{" +
                            "fileId:\"" + escapeString(fileId) + "\"," +
                            "deviceId:\"" + escapeString(deviceId) + "\"," +
                            "fileName:\"" + escapeString(fileName) + "\"," +
                            "fileType:\"photo\"," +
                            "fileSize:" + file.length() + "," +
                            "cloudPath:\"" + escapeString(cloudPath) + "\"," +
                            "commandId:\"" + (commandId != null ? commandId : "") + "\"," +
                            "uploaded:true," +
                            "createTime:" + now + "," +
                            "updateTime:" + now +
                            "}})";
                    
                    executeDbAdd(query);
                    AppLog.d(TAG, "照片上传成功并记录到数据库: " + fileName);
                    
                    if (callback != null) {
                        mainHandler.post(() -> callback.onSuccess(fileId));
                    }
                } else {
                    AppLog.e(TAG, "照片上传失败: " + fileName);
                    if (callback != null) {
                        mainHandler.post(() -> callback.onError("上传失败"));
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "上传照片异常", e);
                if (callback != null) {
                    mainHandler.post(() -> callback.onError(e.getMessage()));
                }
            }
        }).start();
    }
    
    /**
     * 批量上传照片
     */
    public void uploadPhotosToCloudAsync(java.util.List<java.io.File> files, String commandId, BatchUploadCallback callback) {
        new Thread(() -> {
            int successCount = 0;
            int failCount = 0;
            java.util.List<String> fileIds = new java.util.ArrayList<>();
            
            for (java.io.File file : files) {
                try {
                    String deviceId = config.getDeviceId();
                    String fileName = file.getName();
                    long now = System.currentTimeMillis();
                    
                    String cloudPath = "photos/" + deviceId + "/" + fileName;
                    String fileId = uploadFileToCloud(file, cloudPath);
                    
                    if (fileId != null) {
                        fileIds.add(fileId);
                        successCount++;
                        
                        // 记录到数据库
                        String query = "db.collection(\"files\").add({data:{" +
                                "fileId:\"" + escapeString(fileId) + "\"," +
                                "deviceId:\"" + escapeString(deviceId) + "\"," +
                                "fileName:\"" + escapeString(fileName) + "\"," +
                                "fileType:\"photo\"," +
                                "fileSize:" + file.length() + "," +
                                "cloudPath:\"" + escapeString(cloudPath) + "\"," +
                                "commandId:\"" + (commandId != null ? commandId : "") + "\"," +
                                "uploaded:true," +
                                "createTime:" + now + "," +
                                "updateTime:" + now +
                                "}})";
                        executeDbAdd(query);
                        
                        AppLog.d(TAG, "照片 " + successCount + "/" + files.size() + " 上传成功: " + fileName);
                    } else {
                        failCount++;
                    }
                } catch (Exception e) {
                    failCount++;
                    AppLog.e(TAG, "上传照片异常: " + file.getName(), e);
                }
            }
            
            final int finalSuccess = successCount;
            final int finalFail = failCount;
            if (callback != null) {
                mainHandler.post(() -> callback.onComplete(finalSuccess, finalFail, fileIds));
            }
        }).start();
    }
    
    /**
     * 上传实时预览截图
     */
    public void uploadPreviewFrame(byte[] jpegData, PreviewCallback callback) {
        new Thread(() -> {
            try {
                if (!refreshAccessToken()) {
                    return;
                }
                
                String deviceId = config.getDeviceId();
                long now = System.currentTimeMillis();
                String cloudPath = "preview/" + deviceId + "/frame.jpg";
                
                // 创建临时文件
                java.io.File tempFile = new java.io.File(context.getCacheDir(), "preview_frame.jpg");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(tempFile);
                fos.write(jpegData);
                fos.close();
                
                // 上传
                String fileId = uploadFileToCloud(tempFile, cloudPath);
                
                // 删除临时文件
                tempFile.delete();
                
                if (fileId != null) {
                    // 更新设备的预览信息
                    String query = "db.collection(\"devices\").where({deviceId:\"" + deviceId + "\"}).update({data:{" +
                            "previewFileId:\"" + escapeString(fileId) + "\"," +
                            "previewTime:" + now +
                            "}})";
                    executeDbUpdate(query);
                    
                    if (callback != null) {
                        mainHandler.post(() -> callback.onPreviewUploaded(fileId));
                    }
                }
            } catch (Exception e) {
                AppLog.e(TAG, "上传预览帧失败", e);
            }
        }).start();
    }
    
    // 上传回调接口
    public interface UploadCallback {
        void onSuccess(String fileId);
        void onError(String error);
    }
    
    public interface BatchUploadCallback {
        void onComplete(int successCount, int failCount, java.util.List<String> fileIds);
    }
    
    public interface PreviewCallback {
        void onPreviewUploaded(String fileId);
    }
    
    /**
     * 添加文件记录到云数据库
     */
    public void addFileRecord(String fileId, String deviceId, String fileName, String fileType, 
            long fileSize, String cloudPath, String commandId) {
        new Thread(() -> {
            try {
                long now = System.currentTimeMillis();
                String query = "db.collection(\"files\").add({data:{" +
                        "fileId:\"" + escapeString(fileId) + "\"," +
                        "deviceId:\"" + escapeString(deviceId) + "\"," +
                        "fileName:\"" + escapeString(fileName) + "\"," +
                        "fileType:\"" + fileType + "\"," +
                        "fileSize:" + fileSize + "," +
                        "cloudPath:\"" + escapeString(cloudPath) + "\"," +
                        "commandId:\"" + (commandId != null ? escapeString(commandId) : "") + "\"," +
                        "uploaded:true," +
                        "createTime:" + now + "," +
                        "updateTime:" + now +
                        "}})";
                
                executeDbAdd(query);
                AppLog.d(TAG, "文件记录已添加: " + fileName);
            } catch (Exception e) {
                AppLog.e(TAG, "添加文件记录失败", e);
            }
        }).start();
    }

    /**
     * 同步文件信息到云数据库
     * @param files 文件列表
     * @param fileType 文件类型：video 或 photo
     * @param commandId 关联的命令ID（可选）
     */
    public void syncFilesToCloud(java.util.List<java.io.File> files, String fileType, String commandId) {
        if (files == null || files.isEmpty()) {
            AppLog.w(TAG, "没有文件需要同步");
            return;
        }
        
        new Thread(() -> {
            try {
                // 确保有有效的 token
                if (!refreshAccessToken()) {
                    AppLog.e(TAG, "刷新token失败，无法同步文件");
                    return;
                }
                
                String deviceId = config.getDeviceId();
                long now = System.currentTimeMillis();
                
                for (java.io.File file : files) {
                    try {
                        String fileName = file.getName();
                        long fileSize = file.length();
                        String localPath = file.getAbsolutePath();
                        
                        // 生成唯一文件ID
                        String fileId = "file_" + deviceId + "_" + now + "_" + fileName.hashCode();
                        
                        // 添加文件记录到云数据库
                        String query = "db.collection(\"files\").add({data:{" +
                                "fileId:\"" + fileId + "\"," +
                                "deviceId:\"" + escapeString(deviceId) + "\"," +
                                "fileName:\"" + escapeString(fileName) + "\"," +
                                "fileType:\"" + fileType + "\"," +
                                "fileSize:" + fileSize + "," +
                                "localPath:\"" + escapeString(localPath) + "\"," +
                                "commandId:\"" + (commandId != null ? commandId : "") + "\"," +
                                "uploaded:false," +
                                "createTime:" + now + "," +
                                "updateTime:" + now +
                                "}})";
                        
                        JsonObject result = executeDbAdd(query);
                        if (result != null) {
                            AppLog.d(TAG, "文件信息已同步: " + fileName);
                        } else {
                            AppLog.w(TAG, "文件信息同步失败: " + fileName);
                        }
                    } catch (Exception e) {
                        AppLog.e(TAG, "同步单个文件失败: " + file.getName(), e);
                    }
                }
                
                AppLog.d(TAG, "文件同步完成，共 " + files.size() + " 个文件");
                
            } catch (Exception e) {
                AppLog.e(TAG, "同步文件到云端失败", e);
            }
        }).start();
    }
    
    /**
     * 同步单个文件信息到云数据库
     */
    public void syncFileToCloud(java.io.File file, String fileType, String commandId) {
        java.util.List<java.io.File> files = new java.util.ArrayList<>();
        files.add(file);
        syncFilesToCloud(files, fileType, commandId);
    }

    /**
     * 停止云开发连接
     */
    public void stop() {
        isRunning = false;
        isConnected = false;
        stopHeartbeat();
        stopPolling();
        
        mainHandler.post(() -> connectionCallback.onDisconnected());
        AppLog.d(TAG, "云开发连接已停止");
    }

    /**
     * 检查是否正在运行
     */
    public boolean isRunning() {
        return isRunning && isConnected;
    }
    
    /**
     * 获取云开发环境ID
     */
    public static String getCloudEnvId() {
        return CLOUD_ENV;
    }
}
