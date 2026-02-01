# EVCam 车机端 Android 代码

本目录包含 EVCam 车机端 Android 应用的核心源代码文件。

## 目录结构

```
android-code/
├── README.md                      # 本说明文件
├── build.gradle.kts               # 构建配置
│
├── MainActivity.java              # 主活动
├── AppConfig.java                 # 应用配置（车型配置）
├── WechatMiniFragment.java        # 微信小程序绑定界面
├── SettingsFragment.java          # 设置界面
│
├── wechat/                        # 微信相关
│   ├── WechatCloudManager.java    # 微信云开发管理器（核心）
│   ├── WechatMiniConfig.java      # 微信小程序配置
│   └── QrCodeGenerator.java       # 二维码生成器
│
├── camera/                        # 摄像头相关
│   ├── SingleCamera.java          # 单摄像头控制
│   └── MultiCameraManager.java    # 多摄像头管理器
│
└── res/                           # 资源文件
    ├── activity_main.xml          # 主界面布局
    ├── fragment_wechat_mini.xml   # 微信绑定界面布局
    ├── status_indicator_online.xml    # 在线状态指示器
    ├── status_indicator_offline.xml   # 离线状态指示器
    └── status_indicator_connecting.xml # 连接中状态指示器
```

## 核心文件说明

### MainActivity.java

**主活动 - 应用入口**

主要功能:
- 摄像头初始化和管理
- 微信云开发服务启动
- 远程命令处理（拍照、录像、预览）
- 实时预览流管理

关键方法:
```java
// 启动微信云开发服务
public void startWechatCloudService()

// 初始化摄像头（根据车型）
private void initCameras(String[] cameraIds)

// 领克07专用初始化
private void initCamerasForLynkCo07(String[] cameraIds)

// 启动实时预览流
public void startPreviewStream()

// 停止实时预览流
public void stopPreviewStream()
```

### AppConfig.java

**应用配置管理器**

主要功能:
- 车型配置管理
- 摄像头参数设置
- 分辨率配置
- 持久化存储

支持的车型:
```java
public static final String CAR_MODEL_GALAXY_E5 = "galaxy_e5";   // 银河E5
public static final String CAR_MODEL_LYNKCO_07 = "lynkco_07";   // 领克07/08
public static final String CAR_MODEL_L7 = "l7";                  // 理想L7
public static final String CAR_MODEL_L7_MULTI = "l7_multi";     // 理想L7多摄
public static final String CAR_MODEL_PHONE = "phone";           // 手机测试
public static final String CAR_MODEL_CUSTOM = "custom";         // 自定义
```

车型配置结构:
```java
public static class CarModelConfig {
    public final String displayName;      // 显示名称
    public final int cameraCount;         // 摄像头数量
    public final String[] cameraIds;      // 摄像头ID列表
    public final String targetResolution; // 目标分辨率
    public final boolean panoramicMode;   // 全景模式
    public final boolean fisheyeCorrection; // 鱼眼校正
}
```

### wechat/WechatCloudManager.java

**微信云开发管理器 - 核心通信组件**

主要功能:
- Access Token 获取和刷新
- 心跳上报（每30秒）
- 命令轮询（每3秒）
- 文件上传到云存储
- 预览帧上传

配置常量:
```java
// 小程序凭证
private static final String APP_ID = "wx1df526b63078a9e5";
private static final String APP_SECRET = "7b6fd801e42004289cb831c6b85f52d9";

// 云开发环境ID
private static final String CLOUD_ENV = "cloudbase-0gt2twhpdc512c30";

// 定时任务间隔
private static final long HEARTBEAT_INTERVAL = 30 * 1000;  // 心跳30秒
private static final long POLL_INTERVAL = 3 * 1000;        // 轮询3秒
```

关键方法:
```java
// 启动云开发连接
public void start(CommandCallback commandCallback)

// 更新心跳
private void updateHeartbeat()

// 轮询命令
private void pollCommands()

// 上传预览帧
public void uploadPreviewFrame(byte[] jpegData, PreviewCallback callback)

// 执行数据库查询（带重试）
private JsonObject executeDbQueryWithRetry(String query, boolean allowRetry)

// 执行数据库更新（带重试）
private JsonObject executeDbUpdateWithRetry(String query, boolean allowRetry)
```

### wechat/WechatMiniConfig.java

**微信小程序配置管理**

主要功能:
- 设备ID生成和存储
- 设备名称管理
- 绑定状态管理
- 二维码数据生成

设备ID格式:
```java
// 格式: EV-{UUID前8位}-{时间戳后4位}
// 示例: EV-A524A4EF-5476
private String generateDeviceId() {
    String uuid = UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase();
    String timestamp = String.valueOf(System.currentTimeMillis() % 10000);
    return "EV-" + uuid + "-" + timestamp;
}
```

### wechat/QrCodeGenerator.java

**二维码生成器**

主要功能:
- 生成设备绑定二维码
- 添加品牌标识
- 支持自定义尺寸

二维码数据格式:
```json
{
  "type": "evcam_bind",
  "deviceId": "EV-07C70FEE-7786",
  "deviceName": "领克07行车记录仪",
  "serverUrl": "https://your-server.com/api",
  "timestamp": 1769834728675
}
```

### WechatMiniFragment.java

**微信小程序绑定界面**

主要功能:
- 显示绑定二维码
- 显示连接状态
- 自动启动云开发服务
- 解绑功能

### camera/SingleCamera.java

**单摄像头控制器**

主要功能:
- Camera2 API 封装
- 预览管理
- 拍照功能
- 录像功能
- 预览帧捕获

### camera/MultiCameraManager.java

**多摄像头管理器**

主要功能:
- 管理多个 SingleCamera 实例
- 协调多路预览
- 统一的拍照/录像接口

## 资源文件

### fragment_wechat_mini.xml

简化后的微信绑定界面布局:
- 连接状态指示器
- 二维码显示区域（放大）
- 已绑定状态显示
- 设备ID显示

### status_indicator_*.xml

状态指示器图标:
- `online` - 绿色圆点
- `offline` - 红色圆点
- `connecting` - 黄色圆点

## 构建配置

### build.gradle.kts

```kotlin
android {
    namespace = "com.kooo.evcam"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kooo.evcam"
        minSdk = 28
        targetSdk = 36
        versionCode = 12
        versionName = "0.9.9-test-01312115"
    }
}

dependencies {
    // ZXing 二维码
    implementation("com.google.zxing:core:3.5.1")
    
    // OkHttp 网络请求
    implementation("com.squareup.okhttp3:okhttp:4.11.0")
    
    // Gson JSON解析
    implementation("com.google.code.gson:gson:2.10.1")
}
```

## 使用说明

### 更换小程序凭证

在 `WechatCloudManager.java` 中修改:
```java
private static final String APP_ID = "您的小程序AppID";
private static final String APP_SECRET = "您的小程序AppSecret";
private static final String CLOUD_ENV = "您的云开发环境ID";
```

### 添加新车型

1. 在 `AppConfig.java` 中添加常量:
```java
public static final String CAR_MODEL_NEW = "new_car";
```

2. 在 `getCarModelConfig()` 中添加配置:
```java
case CAR_MODEL_NEW:
    return new CarModelConfig("新车型", 2, new String[]{"0", "1"}, "1920x1080", false, false);
```

3. 在 `MainActivity.java` 中添加初始化逻辑

### 调试命令

```bash
# 查看日志
adb logcat | grep -iE "WechatCloud|MainActivity|Camera"

# 查看配置
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/app_config.xml"

# 查看设备ID
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/wechat_mini_config.xml"
```
