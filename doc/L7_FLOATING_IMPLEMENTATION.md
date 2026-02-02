# 银河 L7 浮动车型实现总结

## 概述
成功实现了"银河 L7 浮动"车型布局，支持4个摄像头的自由浮动、隐藏、放大和位置调整功能。

## 修改的文件

### 1. 布局文件
- **app/src/main/res/layout/activity_main_l7_floating.xml** (新建)
  - 创建浮动摄像头布局，使用 RelativeLayout 作为容器
  - 4个摄像头 FrameLayout，每个包含:
    - AutoFitTextureView (摄像头预览)
    - 标签 + 隐藏按钮 + 放大按钮
    - 拖动手柄 (右下角)
  - 底部保留原有6个功能按钮

### 2. 配置文件
- **app/src/main/java/com/kooo/evcam/AppConfig.java**
  - 新增常量: `CAR_MODEL_L7_FLOATING = "galaxy_l7_floating"`
  - 更新 `getCameraCount()`: L7_FLOATING 返回 4
  - 更新 `useCodecMode()`: L7_FLOATING 使用 Codec 模式

### 3. 主活动
- **app/src/main/java/com/kooo/evcam/MainActivity.java**
  - 新增字段: `FloatingCameraManager floatingCameraManager`
  - `setupLayoutByCarModel()`: 添加 L7_FLOATING 布局选择
  - `initCamera()`: L7_FLOATING 使用 L7 摄像头初始化逻辑
  - `configureTexture()`: L7_FLOATING 使用适应模式显示
  - 录制按钮文字更新: L7_FLOATING 支持"停止"/"录像"切换
  - `setupFloatingCameras()`: 初始化浮动摄像头功能

### 4. 浮动摄像头管理器
- **app/src/main/java/com/kooo/evcam/FloatingCameraManager.java** (新建)
  - **拖动功能**: DragTouchListener 实现触摸拖动
  - **隐藏功能**: toggleVisibility() 切换可见性
  - **放大功能**: cycleSize() 循环切换3种尺寸
  - **状态持久化**: SharedPreferences 保存位置、大小、可见性
  - **尺寸预设**:
    - 横向摄像头(前/后): 300x200, 450x300, 600x400
    - 纵向摄像头(左/右): 180x280, 270x420, 360x560

### 5. 设置界面
- **app/src/main/java/com/kooo/evcam/SettingsFragment.java**
  - `CAR_MODEL_OPTIONS`: 添加"银河L7-浮动"选项
  - `onItemSelected()`: 添加 L7_FLOATING 选择处理 (position 4)
  - 选中索引初始化: 添加 L7_FLOATING 判断

## 功能特性

### 1. 拖动移动
- 点击右下角拖动手柄可移动摄像头
- 自动限制在父容器范围内
- 松手后自动保存位置

### 2. 隐藏/显示
- 点击 "—" 按钮隐藏摄像头
- 再次点击恢复显示
- 状态持久化保存

### 3. 放大/缩小
- 点击 "+" 按钮循环切换尺寸 (小→中→大→小)
- 横向和纵向摄像头使用不同尺寸预设
- 保持宽高比

### 4. 状态持久化
使用 SharedPreferences 保存每个摄像头的:
- 位置 (x, y 坐标)
- 尺寸索引 (0-2)
- 可见性 (true/false)

### 5. 初始布局
- 前摄像头: 左上角 (300x200dp)
- 后摄像头: 右上角 (300x200dp)
- 左摄像头: 左下角 (180x280dp)
- 右摄像头: 右下角 (180x280dp)

## 车型切换

在设置界面选择"银河L7-浮动"，重启应用后生效:
1. 设置 → 车型配置
2. 选择"银河L7-浮动"
3. 退出应用并重新打开

## 技术细节

### 摄像头ID映射
```
front → 前摄像头
back  → 后摄像头
left  → 左摄像头
right → 右摄像头
```

### 资源ID命名
```
frame_front / frame_back / frame_left / frame_right  // FrameLayout
texture_front / texture_back / texture_left / texture_right  // TextureView
label_front / label_back / label_left / label_right  // 标签
btn_hide_front / btn_hide_back / btn_hide_left / btn_hide_right  // 隐藏按钮
btn_enlarge_front / btn_enlarge_back / btn_enlarge_left / btn_enlarge_right  // 放大按钮
drag_handle_front / drag_handle_back / drag_handle_left / drag_handle_right  // 拖动手柄
```

### 录制模式
L7-浮动使用 **OpenGL+MediaCodec** 模式，与 L7/L7-多按钮相同。

## 验证步骤

1. **布局加载验证**
   - 启动应用，检查4个摄像头是否正确显示
   - 检查初始位置是否符合预期

2. **拖动功能验证**
   - 点击拖动手柄，移动摄像头
   - 检查是否限制在容器内
   - 重启应用，检查位置是否恢复

3. **隐藏功能验证**
   - 点击隐藏按钮，检查摄像头是否消失
   - 重启应用，检查隐藏状态是否保持

4. **放大功能验证**
   - 点击放大按钮，循环切换尺寸
   - 检查宽高比是否保持
   - 重启应用，检查尺寸是否恢复

5. **录制功能验证**
   - 点击录像按钮，检查是否正常录制
   - 检查4路视频是否正常保存

## 已知限制

1. 拖动时没有动画效果
2. 放大/缩小没有过渡动画
3. 暂未实现摄像头层级调整（z-index）
4. 暂未实现双击放大到全屏功能

## 后续优化建议

1. 添加拖动和缩放的平滑动画
2. 添加摄像头边框高亮效果
3. 支持双击摄像头放大到全屏
4. 添加重置按钮，恢复默认布局
5. 支持手势缩放摄像头大小
6. 添加摄像头碰撞检测，避免重叠
