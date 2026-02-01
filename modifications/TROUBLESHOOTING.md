# 故障排除指南

## 快速诊断命令

```bash
# 查看应用是否在运行
adb shell ps | grep com.kooo.evcam

# 查看完整日志
adb logcat -d | grep -iE "evcam|WechatCloud" | tail -100

# 查看配置文件
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/app_config.xml"
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/wechat_mini_config.xml"

# 清除日志后实时监控
adb logcat -c && adb logcat | grep -iE "WechatCloud|Camera|摄像头"
```

---

## 问题1: 设备显示离线

### 症状
- 小程序首页显示"设备离线"
- 显示"上次心跳: XX分钟前"或"从未收到心跳"

### 原因分析

#### A. 设备ID不匹配
**最常见原因**

**诊断**:
```bash
# 查看车机设备ID
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/wechat_mini_config.xml" | grep device_id

# 输出示例: <string name="device_id">EV-07C70FEE-7786</string>
```

然后在小程序中查看绑定的设备ID，两者必须一致。

**解决方案**:
1. 在小程序中解绑设备
2. 重新扫描车机上的二维码
3. 确认绑定

#### B. 网络问题
**诊断**:
```bash
adb logcat -d | grep -iE "token|网络|network|connection"
```

**解决方案**:
- 确保车机已连接网络
- 检查是否能访问微信API

#### C. Access Token 过期
**诊断**:
```bash
adb logcat -d | grep -i "40001"
# 如果看到 errcode:40001，说明token过期
```

**解决方案**:
- 代码已添加自动重试机制
- 重启APP应该能自动恢复

#### D. 云服务未启动
**诊断**:
```bash
adb logcat -d | grep -iE "WechatCloudManager|启动|start"
```

**解决方案**:
- 检查APP是否完全启动
- 检查绑定界面是否显示"已连接"

---

## 问题2: 预览图获取失败

### 症状
- 进入预览页面后显示"获取预览图失败"
- 预览页面一直显示加载中

### 原因分析

#### A. 设备ID不匹配
同上，需要重新扫码绑定

#### B. 云存储桶ID不匹配
**诊断**:
```bash
# 查看车机上传路径
adb logcat -d | grep -i "上传成功\|upload"
# 输出示例: cloud://cloudbase-xxx.636c-cloudbase-xxx-1301176645/preview/...
```

检查小程序中的存储桶ID是否与车机上传的一致。

**解决方案**:
已在 preview.js 中添加多存储桶支持:
```javascript
const fileId1 = `cloud://cloudbase-xxx.636c-cloudbase-xxx-1301176645/${cloudPath}`;
const fileId2 = `cloud://cloudbase-xxx.636c-cloudbase-xxx-1330571541/${cloudPath}`;
wx.cloud.getTempFileURL({ fileList: [fileId1, fileId2] })
```

#### C. 预览流未启动
**诊断**:
```bash
adb logcat -d | grep -iE "preview|预览|start_preview"
```

**解决方案**:
- 确保小程序发送了 start_preview 命令
- 确保车机收到并执行了命令

---

## 问题3: 画面闪屏

### 症状
- APP界面不断闪烁
- 日志中出现大量 "ERROR_MAX_CAMERAS_IN_USE" 错误

### 原因分析
车型配置错误，APP尝试打开超过设备支持的摄像头数量

### 诊断
```bash
adb logcat -d | grep -iE "MAX_CAMERAS_IN_USE|已达到最大摄像头"
```

如果看到类似日志:
```
E SingleCamera: Camera 1 error: ERROR_MAX_CAMERAS_IN_USE (2) - Too many cameras open
```

说明配置的摄像头数量超过设备限制。

### 解决方案

1. **查看当前配置**:
```bash
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/app_config.xml"
```

2. **选择正确的车型**:
   - 打开APP设置
   - 选择正确的车型（如领克07只需1个摄像头）

3. **重启APP**:
```bash
adb shell am force-stop com.kooo.evcam
adb shell am start -n com.kooo.evcam/.MainActivity
```

---

## 问题4: 心跳更新失败

### 症状
- 日志显示 "心跳更新返回null"
- 设备在小程序中显示离线

### 原因分析

#### A. Token 过期 (40001)
```bash
adb logcat -d | grep -i "40001"
```

**解决方案**: 代码已添加自动重试

#### B. 设备记录不存在
```bash
adb logcat -d | grep -i "设备不存在"
```

**解决方案**: 
- 重新扫码绑定
- 检查云数据库 devices 集合

#### C. 网络超时
```bash
adb logcat -d | grep -iE "timeout|超时"
```

**解决方案**:
- 检查网络连接
- 重启APP

---

## 问题5: 命令执行失败

### 症状
- 小程序发送拍照/录像命令后无响应
- 命令一直显示"执行中"

### 诊断
```bash
# 车机端
adb logcat -d | grep -iE "command|命令|poll"

# 小程序端
# 查看控制台日志
```

### 原因分析

#### A. 命令未被轮询到
- 设备ID不匹配
- 轮询间隔问题

#### B. 命令执行异常
```bash
adb logcat -d | grep -iE "error|exception|失败"
```

---

## 问题6: 二维码扫描无反应

### 症状
- 扫描车机二维码后小程序无反应
- 或显示"无效的二维码"

### 原因分析

#### A. 二维码格式错误
二维码应包含JSON数据:
```json
{
  "type": "evcam_bind",
  "deviceId": "EV-xxx",
  "deviceName": "xxx",
  "timestamp": xxx
}
```

#### B. 小程序解析失败
检查扫码页面控制台是否有错误

### 解决方案
1. 在车机APP中刷新二维码
2. 确保二维码清晰完整
3. 检查小程序是否有最新版本

---

## 日志关键词速查

| 关键词 | 含义 |
|-------|------|
| `心跳更新成功` | 心跳正常 |
| `心跳更新返回null` | 心跳失败，需检查原因 |
| `40001` | Token过期 |
| `Token过期(40001)，刷新后重试` | 自动重试中 |
| `MAX_CAMERAS_IN_USE` | 摄像头数量超限 |
| `预览帧上传成功` | 预览正常 |
| `获取上传链接失败` | 上传失败 |
| `命令已接收` | 命令收到 |
| `命令执行完成` | 命令成功 |

---

## 完整重置步骤

如果问题无法解决，可以尝试完全重置:

```bash
# 1. 停止应用
adb shell am force-stop com.kooo.evcam

# 2. 清除应用数据
adb shell pm clear com.kooo.evcam

# 3. 重新安装（如需要）
adb install -r app-debug.apk

# 4. 启动应用
adb shell am start -n com.kooo.evcam/.MainActivity

# 5. 在设置中配置正确的车型

# 6. 在小程序中解绑并重新扫码绑定
```

---

## 联系支持

如果以上方法都无法解决问题，请收集以下信息:

1. 完整日志:
```bash
adb logcat -d > full_log.txt
```

2. 配置文件:
```bash
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/app_config.xml"
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/wechat_mini_config.xml"
```

3. 小程序控制台截图

4. 问题描述和复现步骤
