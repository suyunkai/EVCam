# 快速开始指南

## 前置条件

### 车机端
- Android 9.0 (API 28) 或更高版本
- 网络连接（WiFi或移动数据）
- 摄像头权限

### 小程序端
- 微信最新版本
- 微信云开发环境已配置
- 云函数已部署

---

## 第一步: 安装车机端 APP

### 方式1: 通过 ADB 安装

```bash
# 连接车机
adb devices

# 安装 APK
adb install -r app-debug.apk

# 或安装 release 版本
adb install -r app-release.apk
```

### 方式2: 直接安装
将 APK 文件传输到车机存储，然后在文件管理器中点击安装。

---

## 第二步: 配置车型

### 通过设置界面

1. 打开 EVCam APP
2. 点击右上角设置图标 ⚙️
3. 找到"车型配置"选项
4. 选择您的车型:
   - **银河E5**: 4摄像头
   - **领克07/08**: 1摄像头
   - **理想L7**: 2摄像头
   - **手机测试**: 用于手机测试
   - **自定义**: 自行配置

### 领克07/08 推荐配置

| 配置项 | 值 |
|-------|-----|
| 车型 | 领克07/08 |
| 摄像头数量 | 1 |
| 摄像头ID | 0 |
| 分辨率 | 2560x1600 |

---

## 第三步: 绑定微信小程序

### 车机端操作

1. 打开 EVCam APP
2. 点击底部导航栏的"微信小程序"
3. 等待二维码生成
4. 确保状态显示"已连接"（绿点）

### 小程序端操作

1. 打开微信
2. 扫描车机上的二维码
   - 或在小程序中点击"扫码绑定设备"
3. 确认设备信息
4. 点击"确认绑定"

### 验证绑定

绑定成功后:
- 小程序首页显示设备信息
- 设备状态显示"在线"（绿色）
- 可以看到设备ID

---

## 第四步: 测试功能

### 测试拍照

1. 在小程序首页点击"一键拍照"
2. 等待提示完成
3. 点击"查看文件"或进入文件页面
4. 确认照片已保存

### 测试录像

1. 在小程序点击"一键录像"
2. 默认录制60秒
3. 等待完成提示
4. 在文件页面查看视频

### 测试实时预览

1. 在小程序点击"实时预览"
2. 等待连接建立
3. 应该看到摄像头画面
4. 画面每2秒刷新一次

---

## 常见问题快速解决

### Q: 设备显示离线？

**A**: 检查设备ID是否匹配
```bash
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/wechat_mini_config.xml" | grep device_id
```
如果ID不匹配，请在小程序中解绑后重新扫码。

### Q: 画面闪烁？

**A**: 车型配置错误，摄像头数量超过设备支持。
1. 打开设置
2. 选择正确的车型（如领克07选择1摄像头）
3. 重启APP

### Q: 预览失败？

**A**: 
1. 确保设备在线
2. 确保设备ID匹配
3. 检查网络连接

### Q: 心跳失败？

**A**: 通常是 Token 过期，APP会自动重试。如果持续失败:
1. 检查网络
2. 重启APP

---

## ADB 快捷命令

```bash
# 启动APP
adb shell am start -n com.kooo.evcam/.MainActivity

# 停止APP
adb shell am force-stop com.kooo.evcam

# 查看日志
adb logcat | grep -iE "WechatCloud|evcam"

# 查看配置
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/app_config.xml"

# 清除数据（重置）
adb shell pm clear com.kooo.evcam
```

---

## 下一步

- 查看 [车型配置详细说明](CAR_MODEL_CONFIG.md)
- 查看 [微信小程序集成](WECHAT_MINIPROGRAM.md)
- 查看 [故障排除指南](TROUBLESHOOTING.md)
- 查看 [代码修改清单](CODE_CHANGES.md)
