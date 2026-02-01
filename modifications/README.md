# EVCam 车机端 APP 修改文档

本文档详细记录了 EVCam 项目中针对车机端 APP 的所有修改，包括车型配置、微信小程序集成等功能。

## 目录

1. [车型配置](#车型配置)
2. [微信小程序集成](#微信小程序集成)
3. [实时预览功能](#实时预览功能)
4. [常见问题排查](#常见问题排查)

---

## 车型配置

### 支持的车型

| 车型标识 | 车型名称 | 摄像头数量 | 默认摄像头ID | 分辨率 |
|---------|---------|-----------|-------------|--------|
| `galaxy_e5` | 银河E5 | 4 | 0,1,2,3 | 1280x800 |
| `lynkco_07` | 领克07/08 | 1 | 0 | 2560x1600 |
| `l7` | 理想L7 | 2 | 0,1 | 1920x1080 |
| `phone` | 手机测试 | 2 | 0,1 | 1920x1080 |
| `custom` | 自定义 | 可配置 | 可配置 | 可配置 |

### 领克07/08 配置详情

领克07/08 使用单摄像头配置，适用于只有一个前置摄像头的车机系统。

**配置文件位置**: `app/src/main/java/com/kooo/evcam/AppConfig.java`

**关键配置**:
```java
public static final String CAR_MODEL_LYNKCO_07 = "lynkco_07";

// 领克07默认配置
case CAR_MODEL_LYNKCO_07:
    return new CarModelConfig(
        "领克07/08",
        1,                    // 摄像头数量
        new String[]{"0"},    // 摄像头ID列表
        "2560x1600",          // 目标分辨率
        false,                // 全景模式
        false                 // 鱼眼校正
    );
```

### 如何切换车型

1. **通过设置界面**:
   - 打开 EVCam APP
   - 点击右上角设置图标
   - 选择"车型配置"
   - 选择对应的车型

2. **通过 ADB 命令**:
   ```bash
   # 停止应用
   adb shell am force-stop com.kooo.evcam
   
   # 启动并设置车型（需要应用支持）
   adb shell am start -n com.kooo.evcam/.MainActivity --es car_model "lynkco_07"
   ```

---

## 微信小程序集成

### 功能概述

车机端 APP 通过微信云开发 HTTP API 与小程序通信，支持：
- 设备绑定/解绑
- 心跳上报（每30秒）
- 远程命令执行（拍照、录像、预览）
- 文件上传到云存储

### 配置文件

**位置**: `app/src/main/java/com/kooo/evcam/wechat/WechatCloudManager.java`

**关键配置**:
```java
// 小程序凭证（需要替换为您自己的）
private static final String APP_ID = "wx1df526b63078a9e5";
private static final String APP_SECRET = "7b6fd801e42004289cb831c6b85f52d9";

// 云开发环境ID
private static final String CLOUD_ENV = "cloudbase-0gt2twhpdc512c30";

// 心跳间隔
private static final long HEARTBEAT_INTERVAL = 30 * 1000;  // 30秒

// 命令轮询间隔
private static final long POLL_INTERVAL = 3 * 1000;  // 3秒
```

### 设备ID生成规则

**位置**: `app/src/main/java/com/kooo/evcam/wechat/WechatMiniConfig.java`

```java
// 格式: EV-{UUID前8位}-{时间戳后4位}
// 示例: EV-A524A4EF-5476
private String generateDeviceId() {
    String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    String timestamp = String.valueOf(System.currentTimeMillis() % 10000);
    return "EV-" + uuid + "-" + timestamp;
}
```

### 二维码数据格式

```json
{
  "type": "evcam_bind",
  "deviceId": "EV-07C70FEE-7786",
  "deviceName": "领克07行车记录仪",
  "serverUrl": "https://your-server.com/api",
  "timestamp": 1769834728675
}
```

---

## 实时预览功能

### 工作原理

1. 小程序发送 `start_preview` 命令
2. 车机收到命令后启动预览流
3. 车机每2秒截取一帧预览图并上传到云存储
4. 小程序每2秒从云存储获取最新图片并显示
5. 退出预览时发送 `stop_preview` 命令

### 云存储路径

```
preview/{deviceId}/frame.jpg

示例:
cloud://cloudbase-0gt2twhpdc512c30.636c-cloudbase-0gt2twhpdc512c30-1301176645/preview/EV-07C70FEE-7786/frame.jpg
```

### 相关代码

**车机端上传**: `MainActivity.java`
```java
// 预览间隔
private static final long PREVIEW_INTERVAL = 2000;  // 2秒

// 启动预览流
public void startPreviewStream() {
    // 每2秒截取一帧并上传
    previewRunnable = new Runnable() {
        @Override
        public void run() {
            captureAndUploadPreviewFrame();
            previewHandler.postDelayed(this, PREVIEW_INTERVAL);
        }
    };
    previewHandler.post(previewRunnable);
}
```

**小程序获取**: `preview.js`
```javascript
// 从云存储获取预览图
fetchPreviewImage: function() {
    const cloudPath = `preview/${deviceId}/frame.jpg`;
    wx.cloud.getTempFileURL({
        fileList: [fileId]
    }).then(res => {
        // 显示图片
    });
}
```

---

## 常见问题排查

### 1. 设备显示离线

**可能原因**:
- 设备ID不匹配（小程序绑定的ID与车机实际ID不同）
- 网络问题导致心跳发送失败
- access_token 过期

**排查步骤**:
```bash
# 查看车机设备ID
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/wechat_mini_config.xml"

# 查看心跳日志
adb logcat -d | grep -i "heartbeat\|心跳"

# 查看token状态
adb logcat -d | grep -i "access_token\|40001"
```

**解决方案**:
1. 在小程序中解绑设备
2. 重新扫描车机上的二维码绑定

### 2. 预览图获取失败

**可能原因**:
- 云存储桶ID不匹配
- 设备ID不匹配
- 预览流未启动

**排查步骤**:
```bash
# 查看预览上传日志
adb logcat -d | grep -i "preview\|预览"

# 确认上传路径
# 应该类似: cloud://cloudbase-xxx.636c-cloudbase-xxx-1301176645/preview/EV-xxx/frame.jpg
```

### 3. 闪屏问题

**原因**: 车型配置错误，APP尝试打开超过设备支持的摄像头数量

**排查步骤**:
```bash
# 查看摄像头错误
adb logcat -d | grep -i "MAX_CAMERAS_IN_USE\|已达到最大摄像头"

# 查看当前配置
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/app_config.xml"
```

**解决方案**:
1. 在设置中选择正确的车型
2. 或重新安装APP

### 4. access_token 过期 (错误码 40001)

**原因**: 微信API的access_token有效期为2小时

**解决方案**: 已在代码中添加自动重试机制
```java
// executeDbQueryWithRetry / executeDbUpdateWithRetry
if (errcode == 40001 && allowRetry) {
    forceRefreshToken();
    return executeDbQueryWithRetry(query, false);
}
```

---

## 修改文件清单

### 车机端 (Android)

| 文件 | 修改内容 |
|-----|---------|
| `AppConfig.java` | 添加领克07/08车型配置 |
| `MainActivity.java` | 添加领克07初始化逻辑、预览流功能 |
| `WechatCloudManager.java` | 添加40001错误自动重试、心跳日志优化 |
| `WechatMiniConfig.java` | 添加小程序AppID配置 |
| `QrCodeGenerator.java` | 优化二维码生成逻辑 |
| `WechatMiniFragment.java` | 简化绑定界面 |
| `fragment_wechat_mini.xml` | 简化UI布局 |

### 小程序端

| 文件 | 修改内容 |
|-----|---------|
| `app.json` | 添加预览页面路由 |
| `pages/preview/*` | 新增实时预览页面 |
| `pages/index/*` | 添加预览入口、调试信息 |
| `pages/control/*` | 添加预览按钮、命令提示 |

---

## 版本信息

- **文档版本**: 1.0
- **适用APP版本**: 0.9.9-test-01312115
- **最后更新**: 2026-01-31
