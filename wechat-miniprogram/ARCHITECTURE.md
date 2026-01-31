# 微信小程序集成说明

## 架构概述

```
┌─────────────────┐     HTTP API      ┌─────────────────┐
│   车机端 APP    │ ◄──────────────► │   微信云开发     │
│   (Android)     │                   │   (云函数+数据库) │
└─────────────────┘                   └─────────────────┘
                                              ▲
                                              │ 云开发SDK
                                              ▼
                                      ┌─────────────────┐
                                      │   微信小程序    │
                                      │   (前端)        │
                                      └─────────────────┘
```

## 车机端配置

### WechatCloudManager.java

**文件位置**: `app/src/main/java/com/kooo/evcam/wechat/WechatCloudManager.java`

#### 核心配置

```java
// 小程序凭证（替换为您的小程序信息）
private static final String APP_ID = "wx1df526b63078a9e5";
private static final String APP_SECRET = "7b6fd801e42004289cb831c6b85f52d9";

// 云开发环境ID（在微信开发者工具中创建）
private static final String CLOUD_ENV = "cloudbase-0gt2twhpdc512c30";

// 微信API地址
private static final String TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
private static final String DB_QUERY_URL = "https://api.weixin.qq.com/tcb/databasequery";
private static final String DB_UPDATE_URL = "https://api.weixin.qq.com/tcb/databaseupdate";
private static final String DB_ADD_URL = "https://api.weixin.qq.com/tcb/databaseadd";
private static final String UPLOAD_URL = "https://api.weixin.qq.com/tcb/uploadfile";

// 定时任务间隔
private static final long HEARTBEAT_INTERVAL = 30 * 1000;  // 心跳30秒
private static final long POLL_INTERVAL = 3 * 1000;        // 轮询3秒
```

#### 主要功能

1. **获取 Access Token**
```java
private synchronized boolean refreshAccessToken() {
    String url = TOKEN_URL + "?grant_type=client_credential&appid=" + APP_ID + "&secret=" + APP_SECRET;
    // 发送请求获取token，有效期2小时
    // 自动缓存并在过期前刷新
}
```

2. **心跳上报**
```java
private void updateHeartbeat() {
    long now = System.currentTimeMillis();
    String query = "db.collection(\"devices\").where({deviceId:\"" + deviceId + "\"}).update({data:{" +
            "lastHeartbeat:" + now + "," +
            "updateTime:" + now +
            "}})";
    executeDbUpdate(query);
}
```

3. **命令轮询**
```java
private void pollCommands() {
    String query = "db.collection(\"commands\").where({deviceId:\"" + deviceId + "\",status:\"pending\"}).get()";
    JsonObject result = executeDbQuery(query);
    // 处理待执行命令
}
```

4. **40001错误自动重试**
```java
private JsonObject executeDbQueryWithRetry(String query, boolean allowRetry) {
    // 执行查询
    if (errcode == 40001 && allowRetry) {
        // Token 过期，强制刷新后重试
        forceRefreshToken();
        return executeDbQueryWithRetry(query, false);
    }
}
```

### WechatMiniConfig.java

**文件位置**: `app/src/main/java/com/kooo/evcam/wechat/WechatMiniConfig.java`

#### 设备ID管理

```java
// 设备ID格式: EV-{UUID前8位}-{时间戳后4位}
private String generateDeviceId() {
    String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    String timestamp = String.valueOf(System.currentTimeMillis() % 10000);
    return "EV-" + uuid + "-" + timestamp;
}

// 获取二维码数据
public String getQrCodeData() {
    return "{" +
            "\"type\":\"evcam_bind\"," +
            "\"deviceId\":\"" + getDeviceId() + "\"," +
            "\"deviceName\":\"" + getDeviceName() + "\"," +
            "\"serverUrl\":\"" + getServerUrl() + "\"," +
            "\"timestamp\":" + System.currentTimeMillis() +
            "}";
}
```

## 小程序端配置

### 云函数列表

| 云函数名 | 功能 | 调用方 |
|---------|------|-------|
| `bindDevice` | 绑定设备 | 小程序 |
| `unbindDevice` | 解绑设备 | 小程序 |
| `getDeviceStatus` | 获取设备状态 | 小程序 |
| `commandSend` | 发送命令 | 小程序 |
| `commandPoll` | 轮询命令状态 | 小程序 |
| `commandResult` | 上报命令结果 | 车机端 |
| `heartbeat` | 心跳上报 | 车机端(可选) |
| `getFileList` | 获取文件列表 | 小程序 |
| `deleteFile` | 删除文件 | 小程序 |

### 数据库集合

#### devices 集合

```javascript
{
  "_id": "xxx",
  "deviceId": "EV-07C70FEE-7786",
  "deviceName": "领克07行车记录仪",
  "deviceSecret": "随机密钥",
  "boundUserId": "用户openid",
  "boundTime": Date,
  "lastHeartbeat": 1769844000000,  // 毫秒时间戳
  "statusInfo": "",
  "recording": false,
  "registerTime": Date,
  "updateTime": Date
}
```

#### commands 集合

```javascript
{
  "_id": "xxx",
  "commandId": "CMD-xxx",
  "deviceId": "EV-07C70FEE-7786",
  "userId": "用户openid",
  "command": "photo",  // photo, record, start_preview, stop_preview
  "params": { "duration": 60 },
  "status": "pending",  // pending, executing, completed, failed
  "result": "",
  "createTime": Date,
  "updateTime": Date
}
```

#### files 集合

```javascript
{
  "_id": "xxx",
  "deviceId": "EV-07C70FEE-7786",
  "fileId": "cloud://xxx/xxx.jpg",
  "fileName": "20260131_152030_front.jpg",
  "fileType": "photo",  // photo, video
  "fileSize": 1234567,
  "createTime": Date
}
```

### 关键页面

#### 1. 首页 (pages/index)

- 显示绑定状态
- 快捷操作（录像、拍照）
- 实时预览入口
- 设备状态刷新

#### 2. 扫码页 (pages/scan)

- 扫描车机二维码
- 解析设备信息
- 确认绑定

#### 3. 控制页 (pages/control)

- 录像控制
- 拍照控制
- 预览入口
- 设备状态查询

#### 4. 预览页 (pages/preview)

- 实时预览显示
- 开始/停止预览
- 刷新功能

#### 5. 文件页 (pages/files)

- 文件列表
- 预览/下载
- 删除功能

## 通信流程

### 设备绑定流程

```
1. 车机生成二维码（包含deviceId）
2. 用户扫码
3. 小程序解析二维码数据
4. 小程序调用 bindDevice 云函数
5. 云函数创建/更新 devices 记录
6. 绑定成功，保存到本地
```

### 心跳流程

```
1. 车机启动 WechatCloudManager
2. 获取 access_token
3. 每30秒更新 devices.lastHeartbeat
4. 小程序获取状态时检查 lastHeartbeat
5. 超过45秒未更新则判定为离线
```

### 命令执行流程

```
1. 小程序调用 commandSend
2. 云函数创建 commands 记录（status: pending）
3. 车机轮询 commands 集合
4. 车机获取到命令，更新 status: executing
5. 车机执行命令（拍照/录像/预览）
6. 车机上传文件到云存储
7. 车机更新 commands（status: completed）
8. 小程序轮询命令状态
9. 显示执行结果
```

### 预览流程

```
1. 小程序发送 start_preview 命令
2. 车机收到命令，启动预览流
3. 车机每2秒截取一帧
4. 车机上传到: preview/{deviceId}/frame.jpg
5. 小程序每2秒获取最新图片
6. 显示在预览页面
7. 用户退出，发送 stop_preview
8. 车机停止预览流
```

## 云存储路径规范

```
云存储根目录/
├── preview/
│   └── {deviceId}/
│       └── frame.jpg          # 预览帧（会被覆盖）
├── photos/
│   └── {deviceId}/
│       └── 20260131_152030.jpg # 照片
└── videos/
    └── {deviceId}/
        └── 20260131_152030.mp4 # 视频
```

## 调试技巧

### 查看车机日志

```bash
# 查看微信云开发相关日志
adb logcat -d | grep -iE "WechatCloud|token|心跳|命令|preview"

# 实时查看
adb logcat | grep -iE "WechatCloud"
```

### 小程序调试

1. 在微信开发者工具中打开控制台
2. 查看 `console.log` 输出
3. 查看网络请求
4. 查看云函数调用记录（云开发控制台）

### 数据库调试

1. 打开微信开发者工具
2. 进入云开发控制台
3. 查看数据库集合
4. 检查 devices、commands 记录

## 安全注意事项

1. **APP_SECRET 保护**: 不要泄露 APP_SECRET，建议使用环境变量
2. **设备验证**: 可以添加 deviceSecret 验证
3. **用户权限**: 确保用户只能访问自己绑定的设备
4. **Token 安全**: access_token 应该在服务端使用，不要暴露给前端
