<div align="center">
  <img src="assets/logo.png" alt="EVCam Logo" width="200"/>
  
  # EVCam - 电车记录仪
  
  <p>
    <strong>针对吉利银河系列车型定制开发的车内环视摄像头行车记录仪应用，兼顾随时远程监看的千里眼功能</strong>
  </p>
  
  <p>
    <img src="https://img.shields.io/badge/Android-9.0+-green?style=flat-square&logo=android" alt="Android"/>
    <img src="https://img.shields.io/badge/API-28+-brightgreen?style=flat-square" alt="API"/>
    <img src="https://img.shields.io/badge/License-GPLv3-blue?style=flat-square" alt="License"/>
    <img src="https://img.shields.io/badge/Language-Java-red?style=flat-square&logo=openjdk&logoColor=white" alt="Java"/>
  </p>
</div>

---

## 📱 项目简介

该应用支持吉利银河系列车型（银河E5、银河L6/L7等），理论上其它龙鹰一号无高阶智驾车型也可通用，同时支持手机端预览。支持同时从最多 **4 个摄像头**进行视频录制与拍照，支持通过**钉钉机器人**或**微信小程序**远程发送录制、拍照、实时预览指令进行远程监看。

### ✨ 核心特性

- 🎨 **仿FlymeAuto官方UI** - 仿照FlymeAuto官方界面设计，沉浸式状态栏，美观且符合车机使用习惯
- 🎥 **视频录制与照片抓拍** - 支持多摄像头同步录制和实时拍照，可选择参与录制的摄像头
- 👁️ **千里眼远程监看** - 支持钉钉机器人和微信小程序两种方式远程查看摄像头画面
- 📱 **微信小程序控制** - 扫码绑定设备，支持远程拍照、录像、实时预览和文件管理
- 🚗 **不受车速限制** - 随时可开启录制功能，突破官方30km/h车速限制
- 🔄 **自启动与后台保活** - 开机自启动 + 前台服务 + WorkManager + 无障碍服务多重保活机制
- 💾 **多存储位置支持** - 支持内部存储和U盘存储，自动清理超出限制的旧文件
- 🎬 **分段录制** - 支持1/3/5分钟自动分段，方便管理和回放
- ⏱️ **时间戳水印** - 可选在视频和照片上添加时间角标
- 🖼️ **悬浮窗快捷入口** - 可配置大小和透明度的悬浮按钮，实时显示录制状态
- 🌙 **息屏录制（锁车录制）** - 支持熄屏后继续录制，实现锁车监控
- 🔧 **多车型适配** - 支持银河E5、E5-多按钮、银河L6/L7、L7-多按钮、手机及自定义车型

---

## 🛠️ 技术栈

- **开发语言**: Java
- **最低版本**: Android 9.0 (API 28)
- **目标版本**: Android 14+ (API 36)
- **摄像头API**: Camera2 API
- **视频编码**: MediaRecorder（硬编码）/ OpenGL + MediaCodec（软编码）
- **构建工具**: Gradle 8.x (Kotlin DSL)
- **UI组件**: Material Design Components
- **图片加载**: Glide 4.16.0
- **网络库**: OkHttp 4.12.0
- **钉钉集成**: DingTalk Stream SDK 1.3.12
- **微信云开发**: 微信小程序 + 云函数 + 云数据库 + 云存储
- **后台任务**: WorkManager 2.9.0

### 🚗 支持车型

| 车型 | 摄像头数量 | 录制模式 | 备注 |
|------|-----------|---------|------|
| 银河E5 | 4 | MediaRecorder | 默认车型 |
| 银河E5-多按钮 | 4 | MediaRecorder | 简化操作界面 |
| 银河L6/L7 | 4 | OpenGL+MediaCodec | 自动适配编码模式 |
| 银河L7-多按钮 | 4 | OpenGL+MediaCodec | 简化操作界面 |
| 手机 | 2 | MediaRecorder | 前后摄像头 |
| 自定义车型 | 1/2/4 | 可选 | 完全自定义配置 |

---

## 📦 快速开始

### 环境要求

- **JDK**: 17 或更高版本（推荐 JDK 25）
- **Android Studio**: Hedgehog (2023.1.1) 或更高版本
- **Gradle**: 8.0+
- **测试设备**: Android 9.0+ 的真机（建议具有多个摄像头）

### 克隆项目

```bash
git clone https://github.com/your-username/EVCam.git
cd EVCam
```

### 配置 JDK（Windows）

项目提供了便捷的批处理脚本用于配置 JDK 25：

```batch
# 使用提供的脚本构建（自动设置 JAVA_HOME）
build-with-jdk25.bat
```

或者手动配置环境变量：

```batch
set JAVA_HOME=C:\Program Files\Java\jdk-25.0.2
set PATH=%JAVA_HOME%\bin;%PATH%
```

### 钉钉机器人配置

如需使用远程控制功能，需要配置钉钉机器人：

1. 创建钉钉企业内部应用（Stream模式）
2. 获取 `Client ID`（原 AppKey/SuiteKey）和 `Client Secret`（原 AppSecret/SuiteSecret）
3. 创建 `app/src/main/java/com/kooo/evcam/dingtalk/DingTalkConfig.java`：

```java
package com.kooo.evcam.dingtalk;

public class DingTalkConfig {
    // 钉钉应用凭证（新版参数）
    public static final String CLIENT_ID = "你的Client ID";
    public static final String CLIENT_SECRET = "你的Client Secret";
    
    // 上传模式配置
    public static final boolean ENABLE_UPLOAD = true; // 是否启用上传
}
```

**注意**: 
- 钉钉已将旧版的 AppKey/AppSecret 更名为 Client ID/Client Secret
- 如果不需要钉钉功能，可以在 `MainActivity.java` 中注释掉相关代码

### 微信小程序配置

如需使用微信小程序远程控制功能：

#### 1. 小程序端配置

1. 在微信开发者工具中打开 `wechat-miniprogram/` 目录
2. 创建云开发环境（微信开发者工具 → 云开发 → 创建环境）
3. 修改 `app.js` 中的云开发环境ID：

```javascript
wx.cloud.init({
  env: 'your-cloud-env-id',  // 替换为您的云开发环境ID
  traceUser: true,
});
```

4. 部署云函数（右键 `cloudfunctions/` 下每个文件夹 → 上传并部署）

#### 2. 车机端配置

创建 `app/src/main/java/com/kooo/evcam/wechat/WechatMiniConfig.java`：

```java
package com.kooo.evcam.wechat;

public class WechatMiniConfig {
    // 微信小程序凭证
    public static final String APP_ID = "你的小程序AppID";
    public static final String APP_SECRET = "你的小程序AppSecret";
    
    // 云开发环境ID
    public static final String CLOUD_ENV = "your-cloud-env-id";
}
```

**注意**: 
- 需要已认证的小程序才能使用云开发
- 云数据库需要创建 `devices`、`commands`、`files` 三个集合
- 详细架构说明参见 `wechat-miniprogram/ARCHITECTURE.md`

---

## 🔨 构建与安装

### 构建 Debug 版本

```bash
# Windows
gradlew.bat assembleDebug

# Linux/macOS
./gradlew assembleDebug
```

输出位置: `app\build\outputs\apk\debug\app-debug.apk`

### 构建 Release 版本

项目已配置 AOSP 公共测试签名，可直接构建：

```bash
# Windows
gradlew.bat assembleRelease

# Linux/macOS
./gradlew assembleRelease
```

输出位置: `app\build\outputs\apk\release\app-release.apk`

### 安装到设备

```bash
# 安装 Debug 版本
gradlew.bat installDebug

# 或使用 adb 手动安装
adb install app\build\outputs\apk\debug\app-debug.apk
```

---

## 📖 使用指南

### 首次启动

1. **选择车型** - 首次启动会弹出引导界面，选择您的车型（银河E5/L6/L7/手机/自定义）

2. **授予权限** - 请务必使用"应用管家"或其它权限管理软件，授予EVCam所有需要的权限

3. **摄像头预览** - 权限授予后，应用会自动初始化摄像头并显示预览

4. **检查日志** - 点击底部"显示日志"按钮，查看摄像头初始化状态

### 录制视频

1. 点击 **"开始录制"** 按钮（或点击悬浮窗）
2. 所有选中的摄像头同步开始录制
3. 录制自动按设定时长分段（默认1分钟）
4. 录制过程中可以拍照（点击"拍照"按钮）
5. 点击 **"停止录制"** 结束录制

**视频存储位置**: `/sdcard/DCIM/EVCam_Video/`（或 U盘）  
**文件命名格式**: `yyyyMMdd_HHmmss_{position}.mp4`（例如：`20260125_153045_front.mp4`）

### 拍摄照片

- 在预览或录制状态下，点击 **"拍照"** 按钮
- 照片同时从所有活动摄像头抓拍
- 可选择是否添加时间戳水印

**照片存储位置**: `/sdcard/DCIM/EVCam_Photo/`（或 U盘）  
**文件命名格式**: `yyyyMMdd_HHmmss_{position}.jpg`

### 查看录制内容

应用内置了回放和相册功能：

1. 点击左上角菜单图标（☰）
2. 选择 **"视频回放"** 或 **"照片回放"**
3. 点击缩略图可全屏查看/播放
4. 支持多选删除功能

### 悬浮窗功能

启用悬浮窗后，应用切到后台也能方便地控制录制：

- **红色圆点** - 未录制状态
- **绿色闪烁** - 录制中
- **点击** - 打开应用主界面
- **拖动** - 移动悬浮窗位置

在设置中可调整悬浮窗大小（10档可选）和透明度。

### 息屏录制（锁车录制）

开启息屏录制功能后：
- 熄灭屏幕时自动开始录制
- 点亮屏幕时自动停止录制
- 适合锁车后的安全监控场景

### 钉钉远程控制

配置钉钉机器人后，可通过钉钉发送命令：

- `拍照` - 远程拍照并上传
- `录制 <时长>` - 开始录制指定时长（秒）
- `状态` - 查询应用运行状态
- `预览` - 获取当前摄像头预览截图

### 微信小程序远程控制

使用微信小程序控制车机：

1. **扫码绑定** - 点击车机端菜单中的"小程序设置"，用微信扫描二维码绑定设备
2. **远程拍照** - 在小程序首页点击"一键拍照"
3. **远程录像** - 点击"一键录像"，可设置录制时长
4. **实时预览** - 进入预览页面，每2秒刷新一帧画面
5. **文件管理** - 查看、下载、删除云端存储的照片和视频

**小程序功能入口**:
- **首页** - 设备状态、快捷操作
- **控制页** - 录像/拍照参数设置
- **预览页** - 实时画面预览
- **文件页** - 云端文件管理

### 软件设置

点击菜单 → "软件设置"，可配置：

| 设置项 | 说明 |
|--------|------|
| 车型选择 | 选择车型或自定义摄像头配置 |
| 录制模式 | 自动/MediaRecorder/OpenGL+MediaCodec |
| 分段时长 | 1分钟/3分钟/5分钟 |
| 存储位置 | 内部存储/U盘 |
| 存储限制 | 视频和照片的最大存储空间（GB） |
| 录制摄像头 | 选择哪些摄像头参与录制 |
| 悬浮窗 | 开关、大小、透明度 |
| 时间角标 | 是否在视频/照片上添加时间戳 |
| 开机自启 | 开机后自动启动应用 |
| 启动自动录制 | 启动应用后自动开始录制 |
| 息屏录制 | 熄屏时自动录制 |
| 保活服务 | 防止应用被系统杀死 |
| 防止休眠 | 保持设备唤醒状态 |

### 分辨率/码率设置

点击菜单 → "分辨率设置"，可精细调整：

- **分辨率** - 选择摄像头支持的分辨率
- **码率** - 低/标准/高（影响视频质量和文件大小）
- **帧率** - 标准/低（降低帧率可减少文件大小）

### 色彩/降噪调节

点击主界面的调节按钮，可实时调整：

- 曝光补偿
- 白平衡模式
- 色调映射
- 边缘增强
- 降噪模式
- 特效模式

---

## 🏗️ 架构说明

### 核心组件

```
EVCam/
├── MainActivity.java              # 主界面，UI控制器
├── AppConfig.java                 # 应用配置管理
├── camera/                        # 摄像头管理模块
│   ├── MultiCameraManager.java   # 多摄像头编排器
│   ├── SingleCamera.java          # 单摄像头封装（Camera2 API）
│   ├── VideoRecorder.java         # 视频录制器（MediaRecorder）
│   ├── CodecVideoRecorder.java    # 视频录制器（OpenGL+MediaCodec）
│   ├── EglSurfaceEncoder.java     # EGL Surface 编码器
│   ├── ImageAdjustManager.java    # 图像调节管理器
│   ├── CameraCallback.java        # 摄像头事件回调接口
│   └── RecordCallback.java        # 录制事件回调接口
├── dingtalk/                      # 钉钉集成模块
│   ├── DingTalkStreamManager.java # Stream客户端管理
│   ├── DingTalkCommandReceiver.java # 命令解析与执行
│   ├── PhotoUploadService.java    # 照片上传服务
│   └── VideoUploadService.java    # 视频上传服务
├── wechat/                        # 微信小程序集成模块
│   ├── WechatCloudManager.java    # 微信云开发API管理
│   ├── WechatMiniConfig.java      # 设备ID与二维码管理
│   └── WechatRemoteManager.java   # 远程命令处理
├── FloatingWindowService.java     # 悬浮窗服务
├── StorageHelper.java             # 存储路径管理（含U盘检测）
├── StorageCleanupManager.java     # 存储自动清理
├── KeepAliveManager.java          # 保活管理器
├── CameraForegroundService.java   # 前台服务
├── SettingsFragment.java          # 软件设置界面
├── ResolutionSettingsFragment.java # 分辨率设置界面
├── CustomCameraConfigFragment.java # 自定义摄像头配置
├── PlaybackFragment.java          # 视频回放界面
└── PhotoPlaybackFragment.java     # 照片浏览界面
```

### 摄像头初始化流程

```
1. 权限检查 → 请求相机、音频、存储权限
2. TextureView 就绪 → 等待 TextureView 完成初始化
3. 摄像头探测 → 查询 CameraManager 获取可用摄像头
4. 自适应配置 → 根据车型配置分配摄像头：
   - 银河E5: 4摄像头，固定ID映射
   - 银河L6/L7: 4摄像头，使用Codec模式
   - 手机: 2摄像头（前后）
   - 自定义: 用户配置的摄像头
5. 顺序打开 → 遵循系统限制顺序打开摄像头
6. 预览启动 → 建立 CaptureSession 开始预览
```

### 录制流程

```
用户点击"开始录制"
    ↓
MultiCameraManager 为每个选中的摄像头创建 VideoRecorder/CodecVideoRecorder
    ↓
VideoRecorder.prepare() 配置录制器并返回 Surface
    ↓
SingleCamera 将录制 Surface 添加到 CaptureSession
    ↓
所有录制器同步启动 → 定时分段 → 自动创建新分段
    ↓
用户点击"停止录制"
    ↓
所有录制器停止 → 清除 Surface → 重建预览 Session
```

### 线程模型

- **主线程**: UI 更新、按钮响应、TextureView 回调
- **Camera HandlerThread**: 每个 SingleCamera 独立的后台线程处理 Camera2 API 调用
- **Codec 编码线程**: CodecVideoRecorder 的独立编码线程
- **Logcat 读取线程**: 独立线程读取系统日志
- **钉钉 Stream 线程**: WebSocket 连接和消息处理
- **微信云开发线程**: 心跳上报、命令轮询、文件上传
- **WorkManager 后台任务**: 定时保活任务
- **存储清理线程**: 定时检查并清理超限文件

---

## 🔍 开发调试

### 查看日志

```bash
# 查看摄像头相关日志（详细）
adb logcat -v time -s CameraService:V Camera3-Device:V Camera3-Stream:V Camera3-Output:V camera3:V MainActivity:D MultiCameraManager:D SingleCamera:D VideoRecorder:D

# 查看应用日志
adb logcat -v time | findstr "com.kooo.evcam"

# 清空日志缓冲区
adb logcat -c
```

### 设备管理

```bash
# 列出连接的设备
adb devices

# 卸载应用
adb uninstall com.kooo.evcam

# 手动授予权限
adb shell pm grant com.kooo.evcam android.permission.CAMERA
adb shell pm grant com.kooo.evcam android.permission.RECORD_AUDIO
adb shell pm grant com.kooo.evcam android.permission.WRITE_EXTERNAL_STORAGE
```

### 查看录制文件

```bash
# 查看视频列表
adb shell ls -la /sdcard/DCIM/EVCam_Video/

# 拉取视频到本地
adb pull /sdcard/DCIM/EVCam_Video/ ./recordings/

# 查看照片列表
adb shell ls -la /sdcard/DCIM/EVCam_Photo/

# 拉取照片到本地
adb pull /sdcard/DCIM/EVCam_Photo/ ./photos/
```

### 运行测试

```bash
# 单元测试
gradlew.bat test

# 设备测试（需要连接设备）
gradlew.bat connectedAndroidTest
```

---

## ❓ 常见问题

### 1. 摄像头无法打开

**可能原因**:
- TextureView 未就绪就尝试打开摄像头
- 权限未授予（检查 logcat 中的 "Missing permission" 错误）
- 设备无可用摄像头
- 超出系统同时打开摄像头数量限制
- 摄像头ID配置错误（自定义车型）

**解决方案**:
- 确保 TextureView 已触发 `onSurfaceTextureAvailable` 回调
- 在设置中手动授予权限，或重新安装应用
- 使用 `adb shell dumpsys media.camera` 查看设备摄像头信息
- 降低 `maxOpenCameras` 配置（默认为4）
- 检查自定义车型的摄像头ID设置

### 2. 录制失败

**可能原因**:
- DCIM/EVCam_Video 目录不可写
- 摄像头未打开或预览未启动
- MediaRecorder/MediaCodec 配置与摄像头能力不匹配
- 存储空间不足
- U盘写入速度过慢

**解决方案**:
- 检查存储权限是否授予
- 确保摄像头预览正常后再开始录制
- 查看 logcat 中的 MediaRecorder/MediaCodec 错误信息
- 清理设备存储空间或调整存储限制
- 对于L6/L7车型，尝试切换录制模式

### 3. 预览画面不显示

**可能原因**:
- TextureView 尺寸为零
- SurfaceTexture 不可用
- 摄像头预览分辨率不支持
- Camera2 API 报错

**解决方案**:
- 检查布局文件中 TextureView 的宽高设置
- 确认 `onSurfaceTextureAvailable` 回调已触发
- 查看日志中的分辨率协商过程
- 使用 `adb logcat -s CameraService:V` 查看底层错误

### 4. 应用被系统杀掉

**解决方案**:
- 启用前台服务（应用会显示通知）
- 在系统设置中关闭电池优化
- 允许应用自启动
- 启用无障碍服务（设置 → 无障碍 → EVCam保活服务）
- 启用"防止休眠"选项

### 5. 钉钉机器人无响应

**可能原因**:
- Client ID/Client Secret 配置错误
- 网络连接问题
- Stream 连接未建立

**解决方案**:
- 检查 `DingTalkConfig.java` 配置
- 确保设备联网
- 查看日志中的 WebSocket 连接状态
- 重启应用重新建立连接

### 6. U盘存储问题

**可能原因**:
- U盘未正确插入或未被识别
- U盘文件系统不支持
- U盘写入速度过慢导致录制卡顿

**解决方案**:
- 检查U盘是否正确插入
- 使用 FAT32 或 exFAT 格式的U盘
- 应用会自动使用中转写入机制缓解慢速U盘问题
- 如U盘不可用，应用会自动回退到内部存储

### 7. 微信小程序无法连接

**可能原因**:
- 云开发环境配置错误
- AppID/AppSecret 配置错误
- Access Token 获取失败
- 设备未正确注册

**解决方案**:
- 检查 `WechatMiniConfig.java` 中的配置
- 确认云开发环境ID与小程序端一致
- 查看日志中的 "WechatCloud" 相关错误
- 重新生成设备二维码并扫码绑定

### 8. 微信小程序预览不显示

**可能原因**:
- 车机端未正确启动预览流
- 云存储上传失败
- 小程序端图片路径错误
- 网络连接不稳定

**解决方案**:
- 检查车机端是否收到 `start_preview` 命令
- 查看云存储 `preview/{deviceId}/frame.jpg` 是否有更新
- 确保车机网络连接正常
- 尝试重新启动预览

---

## 📋 待办事项

- [x] ~~添加视频清晰度选择（高清/标清/流畅）~~ ✅ 已实现码率选择
- [x] ~~实现时间戳水印功能~~ ✅ 已实现
- [x] ~~微信小程序远程控制~~ ✅ 已实现（拍照、录像、预览、文件管理）
- [ ] 车外扬声器喊话功能
- [ ] 更多远程车控功能（空调、车窗、车门等）
- [ ] 根据指定车辆状态自动启动录制
- [ ] 手动上传功能（选择性上传录制内容）
- [x] ~~更多个性化设置项（录制时长、存储路径等）~~ ✅ 已实现

---

## 🤝 贡献指南

欢迎贡献代码、报告问题或提出新功能建议！

1. Fork 本仓库
2. 创建特性分支 (`git checkout -b feature/AmazingFeature`)
3. 提交更改 (`git commit -m 'Add some AmazingFeature'`)
4. 推送到分支 (`git push origin feature/AmazingFeature`)
5. 提交 Pull Request

---

## 📄 许可证

本项目采用 **GPL-3.0** 开源协议。

你可以自由地使用、修改和分发本项目，但需要遵守 GPL-3.0 的条款。

### 必须遵守
- 保留版权与许可声明，随分发提供 GPL-3.0 文本（LICENSE）。
- 任何修改或衍生作品在对外分发时，必须以 GPL-3.0 许可发布并提供对应源代码。
- 不得添加额外限制或技术手段，阻止他人行使 GPL 权利。

### 商业用途
GPL-3.0 **允许** 商业目的使用与分发/销售，但只要对外分发，就需要遵守上面的开源义务（提供源代码、保留声明、同许可等）。

详细条款请参阅 [LICENSE](LICENSE) 文件。

---

## 💖 支持作者

本项目100% Vibe Coding，已耗费数百元AI Agent订阅成本。如果这个项目对你有帮助，欢迎打赏支持！

<div align="center">
  <img src="assets/donate.jpg" alt="赞赏码" width="300"/>
  <p><em>扫码请作者喝杯咖啡 ☕</em></p>
</div>

---

## 📧 联系方式


- **wechat**: greenteacher46 (请备注来意)

---


