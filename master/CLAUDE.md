# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is an Android multi-camera recording application that simultaneously captures video from up to 4 cameras. The app uses the Camera2 API and is designed for devices with multiple camera sensors (e.g., surveillance or multi-angle recording scenarios).

**Package**: `com.kooo.evcam`
**Min SDK**: API 28 (Android 9.0)
**Target SDK**: API 36 (Android 14+)
**Build System**: Gradle with Kotlin DSL

## Build Commands

**IMPORTANT**: The user will handle builds manually. Do NOT attempt to run build commands automatically.

### Manual Build Instructions (for user)

The project requires JDK 17 or higher. A helper script is provided for building with JDK 25:

```bash
# Build using the provided batch file (sets JAVA_HOME to JDK 25)
build-with-jdk25.bat

# Or build manually after setting JAVA_HOME
set JAVA_HOME=C:\Program Files\Java\jdk-25.0.2
set PATH=%JAVA_HOME%\bin;%PATH%
gradlew.bat assembleDebug
```

### Build Commands Reference

```bash
# Build debug APK (Windows: use gradlew.bat, Linux/Mac: use ./gradlew)
gradlew.bat assembleDebug

# Build release APK
gradlew.bat assembleRelease

# Install debug build to connected device
gradlew.bat installDebug

# Clean build
gradlew.bat clean

# Run unit tests
gradlew.bat test

# Run instrumented tests (requires connected device/emulator)
gradlew.bat connectedAndroidTest
```

**Output Location**: `app\build\outputs\apk\debug\app-debug.apk`

### Release Build & Signing

The project is configured with **AOSP public test signing** for release builds.

```bash
# Build signed release APK
gradlew.bat assembleRelease

# Output: app\build\outputs\apk\release\app-release.apk
```

**For detailed release instructions**, see [RELEASE_GUIDE.md](RELEASE_GUIDE.md).

**Quick release to GitHub**:
```bash
release.bat v1.0.0
```

## Development Commands

```bash
# List connected devices
adb devices

# View real-time logs (filtered for camera operations)
adb logcat -v time -s CameraService:V Camera3-Device:V Camera3-Stream:V Camera3-Output:V camera3:V MainActivity:D MultiCameraManager:D SingleCamera:D VideoRecorder:D

# View app-specific logs only
adb logcat -v time | findstr "com.kooo.evcam"

# Clear logcat buffer
adb logcat -c

# Uninstall app
adb uninstall com.kooo.evcam

# Grant permissions manually (useful for testing)
adb shell pm grant com.kooo.evcam android.permission.CAMERA
adb shell pm grant com.kooo.evcam android.permission.RECORD_AUDIO
adb shell pm grant com.kooo.evcam android.permission.WRITE_EXTERNAL_STORAGE

# Check recorded videos on device
adb shell ls -la /sdcard/DCIM/EVCam_Video/

# Pull recorded videos from device
adb pull /sdcard/DCIM/EVCam_Video/ ./recordings/

# Check photos on device
adb shell ls -la /sdcard/DCIM/EVCam_Photo/

# Pull photos from device
adb pull /sdcard/DCIM/EVCam_Photo/ ./photos/
```

## Architecture

### Core Components

The application follows a layered architecture with clear separation between UI, camera management, and recording:

**MainActivity** ([MainActivity.java](app/src/main/java/com/kooo/evcam/MainActivity.java))
- Entry point and UI controller
- Manages 4 TextureView instances for camera previews
- Handles runtime permissions (camera, audio, storage)
- Implements adaptive camera initialization (waits for all TextureViews to be ready)
- Includes integrated logging system with logcat reader

**MultiCameraManager** ([MultiCameraManager.java](app/src/main/java/com/kooo/evcam/camera/MultiCameraManager.java))
- Orchestrates multiple SingleCamera instances
- Manages camera lifecycle across all active cameras
- Coordinates synchronized recording start/stop
- Implements fallback logic: 4→2→1 camera configurations
- Uses LinkedHashMap to maintain camera order (front, back, left, right)

**SingleCamera** ([SingleCamera.java](app/src/main/java/com/kooo/evcam/camera/SingleCamera.java))
- Wraps Camera2 API for individual camera control
- Manages camera device lifecycle (open, configure, close)
- Handles preview sessions with TextureView
- Uses HandlerThread for background camera operations
- Target resolution: 1280x800 (referenced as "guardapp" standard)

**VideoRecorder** ([VideoRecorder.java](app/src/main/java/com/kooo/evcam/camera/VideoRecorder.java))
- Encapsulates MediaRecorder for video recording
- Configuration: MP4 format, H.264 encoding, 1Mbps bitrate, 30fps
- Saves to: `/DCIM/EVCam_Video/` directory
- Filename format: `yyyyMMdd_HHmmss_{position}.mp4` (e.g., `20260123_151030_front.mp4`)

**Photo Capture**
- Uses ImageReader for capturing still images
- Saves to: `/DCIM/EVCam_Photo/` directory
- Filename format: `yyyyMMdd_HHmmss_{position}.jpg` (e.g., `20260123_151030_back.jpg`)
- Resolution: Same as preview (1280x800)

### Camera Initialization Flow

1. **Permission Check**: MainActivity requests camera, audio, and storage permissions (version-aware)
2. **TextureView Readiness**: Waits for all 4 TextureViews to report `onSurfaceTextureAvailable`
3. **Camera Detection**: Queries CameraManager for available camera IDs
4. **Adaptive Configuration**:
   - 4+ cameras: Uses all 4 positions independently
   - 2-3 cameras: Reuses cameras across TextureViews
   - 1 camera: Shows same camera in all 4 positions
5. **Sequential Opening**: Opens cameras respecting system limits (maxOpenCameras)
6. **Preview Start**: Establishes capture sessions with preview surfaces

### Recording Flow

1. User taps "Start Recording" button
2. MultiCameraManager creates VideoRecorder for each active camera
3. VideoRecorder.prepare() configures MediaRecorder and returns recording Surface
4. SingleCamera adds recording Surface to capture session (alongside preview Surface)
5. All recorders start simultaneously
6. On stop: recorders stop, surfaces cleared, sessions recreated for preview-only

### Threading Model

- **Main Thread**: UI updates, button handlers, TextureView callbacks
- **Background HandlerThread**: Camera operations (one per SingleCamera instance)
- **Logcat Reader Thread**: Separate thread for reading system camera logs

## Key Technical Details

### Resolution Strategy
The app targets **1280x800** resolution for both preview and recording. The SingleCamera class implements a fallback strategy:
1. Exact match: 1280x800
2. Closest match: Finds nearest resolution by aspect ratio and size
3. Falls back to largest available if no good match

### Camera Resource Management
- Android limits concurrent camera access (typically 2-3 cameras)
- The app respects `maxOpenCameras` setting (default: 4)
- Cameras are opened sequentially with proper error handling
- Resources are released in onDestroy() lifecycle method

### Permission Handling
Version-aware permission requests:
- **Android 13+**: CAMERA, RECORD_AUDIO only
- **Android 12 and below**: Adds WRITE_EXTERNAL_STORAGE, READ_EXTERNAL_STORAGE

### Logging System
MainActivity includes a collapsible logging panel that:
- Displays timestamped application events
- Integrates logcat output filtered for camera-related tags
- Auto-scrolls to latest entries
- Limits buffer to 20,000 characters to prevent memory issues

## Important Patterns

### Camera Reuse Pattern
When fewer than 4 cameras are available, the app reuses camera IDs across TextureViews. This is intentional for testing and fallback scenarios. Each SingleCamera instance is independent even if sharing the same cameraId.

### Surface Management
- Preview Surface: Created from TextureView's SurfaceTexture
- Recording Surface: Obtained from MediaRecorder during recording
- Both surfaces are added to the same CameraCaptureSession during recording
- Recording surface is removed when recording stops

### Error Recovery
The app includes comprehensive error handling:
- Camera errors trigger callbacks with specific error codes
- Failed camera opens don't crash the app
- Recording failures are logged and reported to UI
- TextureView lifecycle is tracked to prevent premature camera access

## File Locations

### Source Code
- Main activity: `app/src/main/java/com/kooo/evcam/MainActivity.java`
- Camera package: `app/src/main/java/com/kooo/evcam/camera/`

### Resources
- Main layout: `app/src/main/res/layout/activity_main.xml`
- Manifest: `app/src/main/AndroidManifest.xml`

### Build Configuration
- Project-level: `build.gradle.kts`
- App-level: `app/build.gradle.kts`
- Version catalog: `gradle/libs.versions.toml`

## Common Issues

### Camera Won't Open
- Check that TextureView is available before opening camera
- Verify permissions are granted (check logcat for "Missing permission")
- Ensure device has available cameras (check CameraManager.getCameraIdList())
- Respect maxOpenCameras limit (some devices can't open 4 simultaneously)

### Recording Fails
- Verify DCIM/EVCam_Video directory is writable
- Check that camera is opened and preview is running before starting recording
- Ensure MediaRecorder configuration matches camera capabilities
- Confirm audio permission is granted if recording with audio

### Photo Capture Fails
- Verify DCIM/EVCam_Photo directory is writable
- Check that ImageReader is properly initialized in camera session
- Ensure camera is opened and preview is running before taking photos

### Preview Not Showing
- Verify TextureView dimensions are non-zero
- Check that SurfaceTexture is available
- Ensure camera preview size is supported by the device
- Look for Camera2 API errors in logcat (filtered by CameraService tags)
