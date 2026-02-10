# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

EVCam is an Android dashcam app for Geely Galaxy vehicles (E5, L6, L7). Written in Java using Camera2 API, it supports up to 4 simultaneous cameras, segmented recording, and remote control via DingTalk, Telegram, Feishu, and WeChat Mini Program. Licensed under GPL-3.0.

## Build Commands

```bash
# Build debug APK (Windows)
gradlew.bat assembleDebug

# Build release APK (uses AOSP test keystore, no extra config needed)
gradlew.bat assembleRelease

# Install to connected device
gradlew.bat installDebug

# Run unit tests
gradlew.bat test

# Run instrumented tests (requires connected device)
gradlew.bat connectedAndroidTest

# Full release (build + tag + GitHub Release) - interactive script
release.bat
```

APK output: `app/build/outputs/apk/{debug|release}/app-{debug|release}.apk`

Requires JDK 17+ (release.bat auto-detects JDK 17/21/25 from standard install paths).

## Architecture

Single-module Android project (`app/`). Package: `com.kooo.evcam`.

### Key Packages

| Package | Purpose |
|---------|---------|
| `camera/` | Camera2 wrappers, multi-camera orchestration, dual video encoding pipelines |
| `dingtalk/` | DingTalk bot integration (Stream SDK) |
| `telegram/` | Telegram bot integration (HTTP polling) |
| `feishu/` | Feishu/Lark bot integration (OkHttp WebSocket, custom protobuf-lite) |
| `wechat/` | WeChat Mini Program cloud integration |
| `remote/` | Unified remote command abstraction layer (`RemoteCommandDispatcher`, per-platform handlers) |
| `heartbeat/` | Status heartbeat reporting system |
| `playback/` | Video/photo gallery and playback UI |

### Core Classes (top-level package)

- **MainActivity** — Main UI controller (very large, ~235KB)
- **AppConfig** — Application configuration management
- **MultiCameraManager** — Orchestrates multiple cameras; delegates to `SingleCamera` instances
- **SingleCamera** — Camera2 API wrapper for a single camera
- **VideoRecorder** — MediaRecorder-based recording (hardware encoding, used by E5)
- **CodecVideoRecorder** — OpenGL + MediaCodec recording (software encoding, used by L6/L7)
- **BlindSpotService** — Blind-spot monitoring triggered by turn signals
- **VhalSignalObserver** — gRPC client to vehicle HAL for turn signal detection (localhost:40004)
- **StorageHelper** — Storage path management with USB drive detection and fallback
- **FloatingWindowService** — Overlay floating button showing recording status
- **CameraForegroundService** — Foreground service for background recording
- **KeepAliveManager/Receiver/Worker/AccessibilityService/Provider** — Multi-layered keep-alive system

### Dual Encoding Pipelines

- **MediaRecorder** (hardware): Default for E5 models. Simpler, uses `VideoRecorder`.
- **OpenGL + MediaCodec** (software): For L6/L7 models. Uses `CodecVideoRecorder` + `EglSurfaceEncoder`.

### Remote Control Architecture

All 4 platforms (DingTalk, Telegram, Feishu, WeChat) route through `remote/RemoteCommandDispatcher` with platform-specific handlers in `remote/handler/`. Upload logic uses `remote/upload/MediaUploadService` and `MediaFileFinder`.

### WeChat Mini Program

Separate project in `wechat-miniprogram/` with 5 pages and 13 cloud functions. Communication: Android app polls WeChat Cloud via HTTP; Mini Program uses Cloud SDK. See `wechat-miniprogram/ARCHITECTURE.md` for details.

## Coding Conventions

These rules originate from `.cursor/rules/` and apply at all times.

### 1. 代码可维护性 — 避免在 MainActivity 中堆积业务逻辑

修改或新增代码时，必须保持项目的可维护性和模块化设计。

**职责划分：**

| 类型 | 职责 | 示例 |
|------|------|------|
| MainActivity | UI 交互、相机操作、生命周期 | 启动/停止录制 |
| RemoteManager | 平台业务逻辑、命令处理 | 文件上传、结果上报 |
| CloudManager | 网络通信、API 调用 | HTTP 请求、Token 管理 |
| Helper/Util | 通用工具方法 | 文件查找、唤醒锁管理 |

```java
// ❌ BAD - 把业务逻辑写在 MainActivity
private void uploadWechatVideos(String commandId, String timestamp) {
    // 查找文件、上传、传输...大量业务代码
}

// ✅ GOOD - 委托给专门的 Manager 类
wechatRemoteManager.handleRecordingComplete(commandId, timestamp, callback);
```

- 新增远程平台功能时，参考 `DingTalkHandler`、`TelegramHandler` 等已验证的实现模式
- 使用现有工具类如 `MediaFileFinder`、`WakeUpHelper` 等
- 每个功能模块应有独立的包结构（如 `com.kooo.evcam.wechat`），逻辑自包含，可独立理解和修改

### 2. 版本名时间戳 — 每次修改代码后更新 versionName

每次修改或增加代码后，必须更新 `app/build.gradle.kts` 中的 `versionName`，格式：

```
versionName = "基础版本号-test-MMddHHmm"
```

- **基础版本号**：保持原有版本号不变（如 `1.0.9`）
- **时间戳格式**：`MMddHHmm`（月日时分，24小时制）
- 如果已有 `-test-` 后缀，替换为新时间即可
- 用户发布正式版时会自己移除 `-test-` 后缀

```kotlin
// 修改前
versionName = "1.0.9-test-02091153"
// 修改后（假设当前时间 2月10日 14:30）
versionName = "1.0.9-test-02101430"
```

### 3. Windows Shell 编码 — 命令参数只用英文

在 Windows 系统上执行 Shell 命令时，**必须避免在命令参数中使用中文字符**（IDE Shell 工具的编码限制，无法通过设置解决）。

```bash
# ❌ BAD - 中文会变成乱码
git commit -m "修复登录问题"
gh pr create --title "添加新功能" --body "实现了用户登录"

# ✅ GOOD - 使用英文
git commit -m "fix: resolve login issue"
gh pr create --title "Add new feature" --body "Implement user login"
```

代码文件中的中文注释不受影响（文件编码由编辑器控制）。

## Configuration Files (not in git)

These files contain credentials and must be created locally:
- `app/src/main/java/com/kooo/evcam/dingtalk/DingTalkConfig.java` — DingTalk Client ID/Secret
- `app/src/main/java/com/kooo/evcam/telegram/TelegramConfig.java` — Telegram Bot Token
- `app/src/main/java/com/kooo/evcam/feishu/FeishuConfig.java` — Feishu App credentials
- `app/src/main/java/com/kooo/evcam/wechat/WechatMiniConfig.java` — WeChat AppID/Secret/Cloud env
- `app/src/main/java/com/kooo/evcam/heartbeat/HeartbeatConfig.java` — Heartbeat API config

## Key Dependencies

- Camera2 API, MediaRecorder, MediaCodec + OpenGL ES
- OkHttp 4.12.0 (networking + WebSocket for Feishu)
- DingTalk Stream SDK 1.3.12
- gRPC 1.62.2 (VHAL turn signal monitoring)
- Glide 4.16.0, Gson 2.10.1, ZXing 3.5.1
- AndroidX WorkManager 2.9.0 (keep-alive scheduling)

## Debugging

```bash
# Camera subsystem logs
adb logcat -v time -s CameraService:V Camera3-Device:V MainActivity:D MultiCameraManager:D SingleCamera:D VideoRecorder:D

# App logs
adb logcat -v time | findstr "com.kooo.evcam"

# Camera hardware info
adb shell dumpsys media.camera
```
