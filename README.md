<div align="center">
  <img src="assets/logo.png" alt="EVDashcam Logo" width="200"/>
  
  # EVDashcam

  <p>
    <strong>A customized surround-view dashcam & remote surveillance application designed for electric vehicle infotainment systems (Geely Galaxy series & DragonEagle-1 platforms), featuring multi-camera recording, blind-spot monitoring, remote bot control, and HTTP/RTP live streaming.</strong>
  </p>
  
  <p>
    <img src="https://img.shields.io/badge/Version-1.6.6-blue?style=flat-square" alt="Version"/>
    <img src="https://img.shields.io/badge/Android-9.0+-green?style=flat-square&logo=android" alt="Android"/>
    <img src="https://img.shields.io/badge/API-28+-brightgreen?style=flat-square" alt="API"/>
    <img src="https://img.shields.io/badge/License-GPLv3-blue?style=flat-square" alt="License"/>
    <img src="https://img.shields.io/badge/Language-Java-red?style=flat-square&logo=openjdk&logoColor=white" alt="Java"/>
  </p>
</div>

---

## 📱 Project Overview

**EVDashcam** is an open-source vehicle dashcam application tailored for Geely Galaxy series models (Galaxy E5, L6, L7, etc.) and other DragonEagle-1 head units without high-level factory driving assistance. It also runs on Android phones and tablets.

The app supports simultaneous video recording and snapshot capture across up to **4 cameras** (Front, Rear, Left, Right), provides **blind-spot turn assistance**, supports **local HTTP MJPEG / RTP streaming**, and features remote control via **Telegram**, **Feishu**, and **DingTalk** bots.

### ✨ Key Features

- 🎨 **FlymeAuto-Inspired UI** - Immersive, modern dark vehicle interface with intuitive controls tailored for automotive touchscreens.
- 🎥 **Multi-Camera Synchronous Recording & Snapshots** - Record from 1, 2, or 4 cameras simultaneously, with selectable active camera feeds.
- 👁️ **Remote Surveillance & Bots** - Remote control via Telegram, Feishu, and DingTalk bots: trigger photo capture, recording, and receive status reports on your phone.
- 🚀 **No Vehicle Speed Limit** - Start and continue recording at any speed, bypassing factory 30 km/h camera restrictions.
- 🪞 **Blind Spot Assistance & Dual-View** - Automatically display camera overlay feeds when turn signals are engaged, with customizable window bounds and secondary display support.
- 📡 **HTTP MJPEG & RTP Camera Streaming** - Stream camera feeds directly to browsers, local network clients, or ESP32 displays over TCP/UDP/RTP.
- 📲 **Local Web & QR File Transfer** - Built-in lightweight HTTP server to download videos and photos directly to smartphones via Wi-Fi hotspot or local network without unplugging the USB drive.
- 🔄 **Auto-Move Local Footage to USB** - Automatically detects USB insertion and migrates footage stored on internal display memory to USB, with disk sync verification before deletion.
- ⚡ **Silent / Headless Background Autostart** - Begins recording automatically on boot without popping up over vehicle navigation or dashboard apps.
- 🔒 **Record Only With USB Detected** - Optional guard that restricts dashcam recording strictly to external USB drives, protecting internal flash memory.
- 🔄 **Auto-Start & Multi-Layer Keep-Alive** - Boot autostart + Foreground Service + Accessibility Service + WorkManager to prevent OS task killing.
- 💾 **Dual Storage & Smart Cleanup** - Supports internal storage and USB drives (FAT32/exFAT) with automatic disk-space cleanup when thresholds are reached.
- 🎬 **Segmented Loop Recording** - Automatically segments video into 1, 3, or 5-minute files for seamless loop management.
- ⏱️ **Timestamp Watermarks** - Customizable on-screen date, time, and custom vehicle label watermarks on recordings.
- 🖼️ **Configurable Floating Record Button** - Minimalist floating widget showing live recording status (Idle / Recording) with draggable position.
- 🌙 **Screen-Off Recording (Sentry Mode)** - Continues recording when the vehicle screen sleeps or locks for parked security monitoring.
- 🔧 **Broad Vehicle Model Support** - Presets for Galaxy E5, E5 (Multi-button), Galaxy L6/L7, L7 (Multi-button), Android smartphones, and fully custom camera configurations.

---

## 🛠️ Technology Stack

- **Language**: Java 17+
- **Minimum SDK**: Android 9.0 (API 28)
- **Target SDK**: Android 14+ (API 36)
- **Camera API**: Android Camera2 API
- **Video Encoders**: MediaRecorder (Hardware) / OpenGL ES + MediaCodec (Software / Pipeline)
- **Build System**: Gradle 8.x (Kotlin DSL)
- **UI Architecture**: Material Design Components, Custom Surface Views
- **Image Loading**: Glide 4.16.0
- **Networking**: OkHttp 4.12.0
- **Remote Bot Integrations**:
  - Telegram Bot API
  - Feishu WebSocket Long Connection
  - DingTalk Stream SDK 1.3.12
- **Background Orchestration**: AndroidX WorkManager 2.9.0

### 🚗 Vehicle Model Compatibility

| Vehicle Model | Camera Count | Default Encoding Mode | Notes |
|---|---|---|---|
| **Galaxy E5** | 4 | MediaRecorder | Default preset |
| **Galaxy E5 (Multi-button)** | 4 | MediaRecorder | Simplified button UI |
| **Galaxy L6 / L7** | 4 | OpenGL + MediaCodec | Auto-adapted pipeline |
| **Galaxy L7 (Multi-button)** | 4 | OpenGL + MediaCodec | Simplified button UI |
| **Smartphones / Tablets** | 2 | MediaRecorder | Front + Rear cameras |
| **Custom Configuration** | 1 / 2 / 4 | Configurable | Custom camera IDs and mappings |

---

## 📦 Getting Started

### Prerequisites

- **JDK**: JDK 17 or higher (JDK 25 supported)
- **Android SDK**: Build Tools 36.0.0, Platform API 36
- **Gradle**: 8.0+
- **Test Device**: Android 9.0+ head unit or smartphone with camera access

### Cloning the Repository

```bash
git clone https://github.com/BradTiming/EVCam.git
cd EVCam
```

### Build Environment Setup (Windows)

Configure your `local.properties` with your Android SDK location:
```properties
sdk.dir=C\:\\Users\\<username>\\AppData\\Local\\Android\\Sdk
```

---

## 🔨 Building and Installation

### Build Debug APK

```bash
# Windows
.\gradlew.bat assembleDebug

# Linux / macOS
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

### Build Release APK

The project includes an AOSP public test key for easy testing:

```bash
# Windows
.\gradlew.bat assembleRelease

# Linux / macOS
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

### Install via ADB

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## 📖 User Guide

### 1. First-Time Launch & Permissions
1. **Model Selection**: On first launch, select your vehicle preset (e.g., Galaxy E5, Galaxy L6/L7, Smartphone, or Custom).
2. **Grant Permissions**: Navigate to **Menu → Permission Settings** and grant Camera, Storage, Microphone, and Floating Window permissions. An **ADB One-Click Permission Tool** is included for root or PC-based granting.
3. **Camera Preview**: Once granted, live camera feeds will initialize and display on the main dashboard.

### 2. Video Recording & Snapshots
- Tap **Start Recording** (or tap the floating widget) to start recording across all active camera streams.
- Tap **Photo** to capture instant full-resolution photos from all active cameras.
- Tap **Stop Recording** to finish and flush video segments to disk.
- **Video Storage Path**: `/sdcard/DCIM/EVCam_Video/` (or external USB: `EVCam_Video/`)
- **Photo Storage Path**: `/sdcard/DCIM/EVCam_Photo/` (or external USB: `EVCam_Photo/`)

### 3. File Browser & QR Transfer
- Open **Menu → Video Playback** or **Photo Playback** to preview, play, or delete media.
- Tap **Share / QR Transfer** to launch the built-in HTTP server. Scan the on-screen QR code with your phone (connected via hotspot or the same Wi-Fi) to view and download recordings directly in your mobile browser.

### 4. Remote Bots Setup (Telegram, Feishu, DingTalk)
- **Telegram**: Go to **Menu → Telegram Settings**, paste your Bot Token and Chat ID whitelist, and toggle enabled. Send `/help` or `/record` to your bot.
- **Feishu / DingTalk**: Enter your App Credentials to interactively control the dashcam from anywhere.

---

## 🔍 Debugging & Logs

```bash
# Filter all EVDashcam logs
adb logcat -v time | findstr "com.kooo.evcam"

# Inspect Camera2 service events
adb logcat -v time -s CameraService:V Camera3-Device:V MainActivity:D MultiCameraManager:D SingleCamera:D

# Clear log buffer
adb logcat -c
```

---

## ⚠️ Safety Disclaimer

Please see [DISCLAIMER.md](DISCLAIMER.md) for critical safety information regarding blind spot monitoring and automotive usage. This software is an experimental open-source project and is not intended for primary vehicle control or critical safety decisions.

---

## 📄 License

This project is licensed under the **GNU General Public License v3.0 (GPL-3.0)**. See the [LICENSE](LICENSE) file for complete details.
