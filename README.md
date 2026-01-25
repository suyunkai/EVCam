<div align="center">
  <img src="assets/logo.png" alt="EVCam Logo" width="200"/>
  
  # EVCam - 多摄像头录制应用
  
  <p>
    <strong>专业的Android多摄像头同步录制解决方案</strong>
  </p>
  
  <p>
    <img src="https://img.shields.io/badge/Android-9.0+-green?style=flat-square&logo=android" alt="Android"/>
    <img src="https://img.shields.io/badge/API-28+-brightgreen?style=flat-square" alt="API"/>
    <img src="https://img.shields.io/badge/License-MIT-blue?style=flat-square" alt="License"/>
    <img src="https://img.shields.io/badge/Language-Java-orange?style=flat-square&logo=java" alt="Java"/>
  </p>
</div>

---

## 📱 项目简介

EVCam 是一款功能强大的 Android 多摄像头录制应用，支持同时从最多 **4 个摄像头**进行视频录制。基于 Android Camera2 API 开发，专为需要多角度监控、全方位记录的场景设计（如安防监控、运动记录、教学演示等）。

### ✨ 核心特性

- 🎥 **多摄像头同步录制** - 同时支持最多4个摄像头（前、后、左、右）同步录制
- 🔄 **智能适配机制** - 自动适配设备摄像头数量（4→2→1摄像头智能降级）
- 📸 **照片抓拍功能** - 录制过程中可随时拍摄高清照片
- 🎬 **高清视频输出** - 1280x800 分辨率，H.264 编码，MP4 格式
- 🎯 **实时预览** - 4 路摄像头实时预览，所见即所得
- 📊 **集成日志系统** - 内置日志面板，实时监控摄像头状态
- 🔐 **权限智能管理** - 根据 Android 版本智能请求必要权限
- 💾 **本地存储** - 视频和照片自动保存至 DCIM 目录
- 🤖 **钉钉机器人集成** - 支持远程控制和文件上传
- 🔔 **后台保活** - 前台服务 + WorkManager + 无障碍服务三重保活
- 📱 **远程预览** - 通过钉钉机器人远程查看摄像头画面

---

## 🎯 应用场景

- **安防监控** - 多角度同步监控，不留死角
- **行车记录** - 前后左右全方位记录行车过程
- **运动拍摄** - 多机位捕捉运动精彩瞬间
- **会议记录** - 多角度记录会议内容
- **教学演示** - 同时录制讲师、学生、黑板等多个视角
- **设备测试** - 测试多摄像头设备性能

---

## 🛠️ 技术栈

- **开发语言**: Java
- **最低版本**: Android 9.0 (API 28)
- **目标版本**: Android 14+ (API 36)
- **摄像头API**: Camera2 API
- **构建工具**: Gradle 8.x (Kotlin DSL)
- **UI组件**: Material Design Components
- **图片加载**: Glide 4.16.0
- **网络库**: OkHttp 4.12.0
- **钉钉集成**: DingTalk Stream SDK 1.3.12
- **后台任务**: WorkManager 2.9.0

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

1. **授予权限** - 应用启动时会请求以下权限：
   - 📷 摄像头权限
   - 🎤 麦克风权限
   - 💾 存储权限（Android 12 及以下）

2. **摄像头预览** - 权限授予后，应用会自动初始化摄像头并显示预览

3. **检查日志** - 点击底部"显示日志"按钮，查看摄像头初始化状态

### 录制视频

1. 点击 **"开始录制"** 按钮
2. 所有摄像头同步开始录制
3. 录制过程中可以拍照（点击"拍照"按钮）
4. 点击 **"停止录制"** 结束录制

**视频存储位置**: `/sdcard/DCIM/EVCam_Video/`  
**文件命名格式**: `yyyyMMdd_HHmmss_{position}.mp4`（例如：`20260125_153045_front.mp4`）

### 拍摄照片

- 在预览或录制状态下，点击 **"拍照"** 按钮
- 照片同时从所有活动摄像头抓拍

**照片存储位置**: `/sdcard/DCIM/EVCam_Photo/`  
**文件命名格式**: `yyyyMMdd_HHmmss_{position}.jpg`

### 查看录制内容

应用内置了回放和相册功能：

1. 点击左上角菜单图标（☰）
2. 选择 **"视频回放"** 或 **"照片回放"**
3. 点击缩略图可全屏查看/播放

### 钉钉远程控制

配置钉钉机器人后，可通过钉钉发送命令：

- `拍照` - 远程拍照并上传
- `录制 <时长>` - 开始录制指定时长（秒）
- `状态` - 查询应用运行状态
- `预览` - 获取当前摄像头预览截图

---

## 🏗️ 架构说明

### 核心组件

```
EVCam/
├── MainActivity.java              # 主界面，UI控制器
├── camera/                        # 摄像头管理模块
│   ├── MultiCameraManager.java   # 多摄像头编排器
│   ├── SingleCamera.java          # 单摄像头封装（Camera2 API）
│   ├── VideoRecorder.java         # 视频录制器（MediaRecorder）
│   ├── CameraCallback.java        # 摄像头事件回调接口
│   └── RecordCallback.java        # 录制事件回调接口
├── dingtalk/                      # 钉钉集成模块
│   ├── DingTalkStreamManager.java # Stream客户端管理
│   ├── DingTalkCommandReceiver.java # 命令解析与执行
│   ├── PhotoUploadService.java    # 照片上传服务
│   └── VideoUploadService.java    # 视频上传服务
├── KeepAliveManager.java          # 保活管理器
├── CameraForegroundService.java   # 前台服务
├── PlaybackFragment.java          # 视频回放界面
└── PhotoPlaybackFragment.java     # 照片浏览界面
```

### 摄像头初始化流程

```
1. 权限检查 → 请求相机、音频、存储权限
2. TextureView 就绪 → 等待4个 TextureView 完成初始化
3. 摄像头探测 → 查询 CameraManager 获取可用摄像头
4. 自适应配置 → 根据摄像头数量分配：
   - 4+ 摄像头: 独立使用4个摄像头
   - 2-3 摄像头: 复用摄像头到多个 TextureView
   - 1 摄像头: 所有 TextureView 显示同一摄像头
5. 顺序打开 → 遵循系统限制顺序打开摄像头
6. 预览启动 → 建立 CaptureSession 开始预览
```

### 录制流程

```
用户点击"开始录制"
    ↓
MultiCameraManager 为每个摄像头创建 VideoRecorder
    ↓
VideoRecorder.prepare() 配置 MediaRecorder 并返回 Surface
    ↓
SingleCamera 将录制 Surface 添加到 CaptureSession
    ↓
所有录制器同步启动
    ↓
用户点击"停止录制"
    ↓
所有录制器停止 → 清除 Surface → 重建预览 Session
```

### 线程模型

- **主线程**: UI 更新、按钮响应、TextureView 回调
- **Camera HandlerThread**: 每个 SingleCamera 独立的后台线程处理 Camera2 API 调用
- **Logcat 读取线程**: 独立线程读取系统日志
- **钉钉 Stream 线程**: WebSocket 连接和消息处理
- **WorkManager 后台任务**: 定时保活任务

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

**解决方案**:
- 确保 TextureView 已触发 `onSurfaceTextureAvailable` 回调
- 在设置中手动授予权限，或重新安装应用
- 使用 `adb shell dumpsys media.camera` 查看设备摄像头信息
- 降低 `maxOpenCameras` 配置（默认为4）

### 2. 录制失败

**可能原因**:
- DCIM/EVCam_Video 目录不可写
- 摄像头未打开或预览未启动
- MediaRecorder 配置与摄像头能力不匹配
- 存储空间不足

**解决方案**:
- 检查存储权限是否授予
- 确保摄像头预览正常后再开始录制
- 查看 logcat 中的 MediaRecorder 错误信息
- 清理设备存储空间

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

### 5. 钉钉机器人无响应

**可能原因**:
- AppKey/AppSecret 配置错误
- 网络连接问题
- Stream 连接未建立

**解决方案**:
- 检查 `DingTalkConfig.java` 配置
- 确保设备联网
- 查看日志中的 WebSocket 连接状态
- 重启应用重新建立连接

---

## 📋 待办事项

- [ ] 支持更多视频编码格式（H.265/HEVC）
- [ ] 添加视频画质选择（高清/标清/流畅）
- [ ] 支持慢动作录制
- [ ] 实现时间戳水印
- [ ] 云端存储集成（OSS/S3）
- [ ] 视频剪辑功能
- [ ] 运动检测触发录制
- [ ] 多语言支持

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

本项目采用 MIT 许可证 - 详见 [LICENSE](LICENSE) 文件。

---

## 💖 支持作者

如果这个项目对你有帮助，欢迎打赏支持！

<div align="center">
  <img src="assets/donate.jpg" alt="赞赏码" width="300"/>
  <p><em>扫码请作者喝杯咖啡 ☕</em></p>
</div>

---

## 📧 联系方式

- **Issues**: [GitHub Issues](https://github.com/your-username/EVCam/issues)
- **Email**: your-email@example.com

---

<div align="center">
  <p>Made with ❤️ by EVCam Team</p>
  <p>© 2026 EVCam. All rights reserved.</p>
</div>
