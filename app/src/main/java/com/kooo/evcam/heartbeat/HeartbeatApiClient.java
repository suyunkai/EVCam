package com.kooo.evcam.heartbeat;

import android.util.Base64;

import com.kooo.evcam.AppLog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * 心跳推图 API 客户端
 * 负责 HTTPS 请求发送和签名生成
 */
public class HeartbeatApiClient {
    private static final String TAG = "HeartbeatApiClient";
    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
    
    private final OkHttpClient client;
    
    public HeartbeatApiClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)  // 写入超时较长（上传图片）
                .build();
    }
    
    /**
     * 发送心跳请求
     * 
     * @param serverUrl 服务器地址
     * @param vehicleId 车辆ID
     * @param secretKey 通信密钥
     * @param imageBytes 图片数据
     * @param imageWidth 图片宽度
     * @param imageHeight 图片高度
     * @param cameraCount 摄像头数量
     * @param appStatus App 状态 JSON 字符串
     * @return 请求是否成功
     */
    public HeartbeatResult sendHeartbeat(String serverUrl, String vehicleId, String secretKey,
                                          byte[] imageBytes, int imageWidth, int imageHeight,
                                          int cameraCount, String appStatus) {
        if (serverUrl == null || serverUrl.isEmpty()) {
            return new HeartbeatResult(false, "Server URL not configured");
        }
        
        if (imageBytes == null || imageBytes.length == 0) {
            return new HeartbeatResult(false, "Photo data is empty");
        }
        
        try {
            // 生成请求参数
            long timestamp = System.currentTimeMillis();
            String nonce = generateNonce();
            String signature = generateSignature(vehicleId, timestamp, nonce, secretKey);
            
            if (signature == null) {
                return new HeartbeatResult(false, "Signature generation failed");
            }
            
            // 构建 JSON 请求体
            String imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP);
            String jsonBody = buildJsonBody(vehicleId, timestamp, nonce, signature,
                    imageBase64, imageWidth, imageHeight, imageBytes.length, cameraCount, appStatus);
            
            // 发送请求
            RequestBody body = RequestBody.create(jsonBody, JSON);
            Request request = new Request.Builder()
                    .url(serverUrl)
                    .post(body)
                    .addHeader("Content-Type", "application/json")
                    .addHeader("X-Vehicle-Id", vehicleId)
                    .addHeader("X-Timestamp", String.valueOf(timestamp))
                    .addHeader("X-Nonce", nonce)
                    .addHeader("X-Signature", signature)
                    .build();
            
            AppLog.d(TAG, "发送心跳请求: " + serverUrl + ", 图片大小: " + (imageBytes.length / 1024) + "KB");
            
            try (Response response = client.newCall(request).execute()) {
                int code = response.code();
                String responseBody = response.body() != null ? response.body().string() : "";
                
                if (response.isSuccessful()) {
                    AppLog.d(TAG, "心跳请求成功: " + code);
                    return new HeartbeatResult(true, "Success", code, responseBody);
                } else {
                    AppLog.w(TAG, "心跳请求失败: " + code + ", " + responseBody);
                    return new HeartbeatResult(false, "HTTP " + code + ": " + responseBody, code, responseBody);
                }
            }
            
        } catch (IOException e) {
            AppLog.e(TAG, "心跳请求网络错误: " + e.getMessage());
            return new HeartbeatResult(false, "网络错误: " + e.getMessage());
        } catch (Exception e) {
            AppLog.e(TAG, "心跳请求异常: " + e.getMessage(), e);
            return new HeartbeatResult(false, "异常: " + e.getMessage());
        }
    }
    
    /**
     * 生成请求签名
     * signature = HMAC-SHA256(vehicleId + timestamp + nonce, secretKey)
     */
    public static String generateSignature(String vehicleId, long timestamp, String nonce, String secretKey) {
        if (secretKey == null || secretKey.isEmpty()) {
            return null;
        }
        
        String message = vehicleId + timestamp + nonce;
        
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256"
            );
            mac.init(keySpec);
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            
            // 转换为十六进制字符串
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
            
        } catch (Exception e) {
            AppLog.e(TAG, "签名生成失败: " + e.getMessage());
            return null;
        }
    }
    
    /**
     * 生成随机 nonce
     */
    public static String generateNonce() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * 构建 JSON 请求体
     */
    private String buildJsonBody(String vehicleId, long timestamp, String nonce, String signature,
                                  String imageBase64, int imageWidth, int imageHeight,
                                  int imageSizeBytes, int cameraCount, String appStatus) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        
        // 认证信息
        sb.append("\"vehicleId\":\"").append(escapeJson(vehicleId)).append("\",");
        sb.append("\"timestamp\":").append(timestamp).append(",");
        sb.append("\"nonce\":\"").append(escapeJson(nonce)).append("\",");
        sb.append("\"signature\":\"").append(escapeJson(signature)).append("\",");
        
        // 图片数据
        sb.append("\"imageBase64\":\"").append(imageBase64).append("\",");
        sb.append("\"imageWidth\":").append(imageWidth).append(",");
        sb.append("\"imageHeight\":").append(imageHeight).append(",");
        sb.append("\"imageSizeBytes\":").append(imageSizeBytes).append(",");
        sb.append("\"cameraCount\":").append(cameraCount).append(",");
        
        // App 状态（已经是 JSON 对象，直接嵌入）
        if (appStatus != null && !appStatus.isEmpty()) {
            sb.append("\"status\":").append(appStatus);
        } else {
            sb.append("\"status\":null");
        }
        
        sb.append("}");
        return sb.toString();
    }
    
    /**
     * 转义 JSON 字符串
     */
    private String escapeJson(String str) {
        if (str == null) {
            return "";
        }
        return str.replace("\\", "\\\\")
                  .replace("\"", "\\\"")
                  .replace("\n", "\\n")
                  .replace("\r", "\\r")
                  .replace("\t", "\\t");
    }
    
    /**
     * 心跳请求结果
     */
    public static class HeartbeatResult {
        public final boolean success;
        public final String message;
        public final int httpCode;
        public final String responseBody;
        
        public HeartbeatResult(boolean success, String message) {
            this(success, message, 0, null);
        }
        
        public HeartbeatResult(boolean success, String message, int httpCode, String responseBody) {
            this.success = success;
            this.message = message;
            this.httpCode = httpCode;
            this.responseBody = responseBody;
        }
    }
}
