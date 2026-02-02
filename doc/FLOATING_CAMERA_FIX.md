# 浮动摄像头问题修复说明

## 问题描述
当隐藏3个摄像头后重新打开应用，会导致摄像头无法正常初始化。

## 根本原因
使用 `View.GONE` 隐藏摄像头时，其内部的 `TextureView` 不会触发 `SurfaceTextureListener` 回调，导致:
- `textureReadyCount` 计数不足
- 摄像头初始化条件不满足 (`textureReadyCount >= requiredTextureCount`)
- 摄像头无法打开

## 解决方案

### 1. 修改隐藏逻辑
**之前**: 使用 `setVisibility(View.GONE)` 隐藏
```java
cameraFrame.setVisibility(View.GONE);  // ❌ 导致 TextureView 不初始化
```

**现在**: 使用透明度 + 禁用交互模拟隐藏
```java
cameraFrame.setAlpha(0f);           // ✅ 完全透明
cameraFrame.setClickable(false);    // ✅ 禁用点击
cameraFrame.setFocusable(false);    // ✅禁用焦点
```

### 2. 优点
- ✅ `TextureView` 始终存在，正常触发 Surface 回调
- ✅ 摄像头可以正常初始化
- ✅ 隐藏效果与 `GONE` 相同（完全透明 + 不可交互）
- ✅ 性能开销可忽略（只是透明度变化）

### 3. 新增功能

#### "显示全部"按钮
- 位置: 录制状态显示下方左侧
- 功能: 一键显示所有隐藏的摄像头
- 用途: 避免隐藏后找不到摄像头

#### "重置布局"按钮
- 位置: 录制状态显示下方右侧
- 功能: 清除所有保存的状态，恢复默认布局
- 流程: 点击 → 确认对话框 → 清除状态 → 自动重启应用

## 修改的文件

1. **FloatingCameraManager.java**
   - `toggleVisibility()`: 改用透明度实现隐藏/显示
   - `restoreCameraState()`: 恢复时设置透明度而非 visibility
   - 新增 `showAllCameras()`: 显示所有摄像头
   - 新增 `resetAllCameras()`: 重置所有状态

2. **activity_main_l7_floating.xml**
   - 新增"显示全部"按钮 (`btn_show_all_cameras`)
   - 新增"重置布局"按钮 (`btn_reset_layout`)

3. **MainActivity.java**
   - `setupFloatingCameras()`: 添加两个按钮的事件处理

## 使用说明

### 隐藏摄像头
点击摄像头上的 "—" 按钮

### 显示摄像头
方法1: 再次点击隐藏位置的 "—" 按钮
方法2: 点击"显示全部"按钮（一键显示所有）

### 重置布局
1. 点击"重置布局"按钮
2. 确认对话框点击"确定"
3. 应用自动重启，恢复默认布局

## 技术细节

### 隐藏状态 (alpha = 0)
- 摄像头完全透明
- 不可点击，不可获得焦点
- TextureView 仍然存在并正常工作
- 摄像头流正常录制（如果开启录制）

### 显示状态 (alpha = 1)
- 摄像头正常显示
- 可点击，可拖动
- 所有功能正常

## 验证步骤

1. **隐藏3个摄像头**
   - 点击3个摄像头的 "—" 按钮
   - 确认它们完全透明

2. **关闭应用**
   - 完全退出应用（返回键或"关闭"按钮）

3. **重新打开应用**
   - 启动应用
   - ✅ 应该看到1个可见摄像头正常工作
   - ✅ 隐藏的3个摄像头保持透明
   - ✅ 所有摄像头都可以正常录制

4. **显示全部**
   - 点击"显示全部"按钮
   - ✅ 所有摄像头恢复可见

5. **重置布局**
   - 点击"重置布局"按钮
   - 确认对话框点击"确定"
   - ✅ 应用自动重启
   - ✅ 所有摄像头恢复默认位置和大小

## 已修复的问题

- ✅ 隐藏摄像头后重启无法打开摄像头
- ✅ TextureView 计数不足导致初始化失败
- ✅ 隐藏的摄像头无法找回

## 构建状态
✅ 编译成功
✅ 无错误
✅ APK 已生成: `app/build/outputs/apk/debug/app-debug.apk`
