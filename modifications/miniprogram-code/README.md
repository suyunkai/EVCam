# EVCam 微信小程序代码

本目录包含 EVCam 微信小程序的完整源代码。

## 目录结构

```
miniprogram-code/
├── app.js                    # 小程序入口
├── app.json                  # 小程序配置
├── app.wxss                  # 全局样式
├── sitemap.json              # 站点地图
├── envList.js                # 环境配置
│
├── pages/                    # 页面目录
│   ├── index/                # 首页
│   │   ├── index.js
│   │   ├── index.wxml
│   │   ├── index.wxss
│   │   └── index.json
│   │
│   ├── scan/                 # 扫码绑定页
│   │   ├── scan.js
│   │   ├── scan.wxml
│   │   ├── scan.wxss
│   │   └── scan.json
│   │
│   ├── control/              # 远程控制页
│   │   ├── control.js
│   │   ├── control.wxml
│   │   ├── control.wxss
│   │   └── control.json
│   │
│   ├── preview/              # 实时预览页 (新增)
│   │   ├── preview.js
│   │   ├── preview.wxml
│   │   ├── preview.wxss
│   │   └── preview.json
│   │
│   └── files/                # 文件管理页
│       ├── files.js
│       ├── files.wxml
│       ├── files.wxss
│       └── files.json
│
├── components/               # 组件目录
│   └── cloudTipModal/        # 云开发提示组件
│
├── images/                   # 图片资源
│
└── cloudfunctions/           # 云函数目录
    ├── bindDevice/           # 绑定设备
    ├── unbindDevice/         # 解绑设备
    ├── getDeviceStatus/      # 获取设备状态
    ├── getDeviceList/        # 获取设备列表
    ├── commandSend/          # 发送命令
    ├── commandPoll/          # 轮询命令状态
    ├── commandResult/        # 命令结果上报
    ├── heartbeat/            # 心跳上报
    ├── getFileList/          # 获取文件列表
    ├── deleteFile/           # 删除文件
    ├── uploadFile/           # 文件上传
    └── deviceRegister/       # 设备注册
```

## 页面说明

### 首页 (pages/index)

**功能**:
- 显示已绑定设备信息
- 设备在线/离线状态
- 快捷操作（一键拍照、一键录像）
- 入口：实时预览、远程控制、查看文件
- 解绑设备

**关键方法**:
- `checkDevice()` - 检查设备绑定状态
- `refreshDeviceStatus()` - 刷新设备状态
- `goToPreview()` - 跳转到预览页
- `quickRecord()` - 快捷录像
- `quickPhoto()` - 快捷拍照

### 扫码页 (pages/scan)

**功能**:
- 扫描车机二维码
- 解析设备信息
- 确认绑定

**关键方法**:
- `startScan()` - 开始扫码
- `handleScanResult()` - 处理扫码结果
- `confirmBind()` - 确认绑定

### 控制页 (pages/control)

**功能**:
- 录像控制（可设置时长）
- 拍照控制
- 预览入口
- 查询设备状态

**关键方法**:
- `startRecord()` - 开始录像
- `takePhoto()` - 拍照
- `goToPreview()` - 跳转预览
- `queryStatus()` - 查询状态

### 预览页 (pages/preview) - 新增

**功能**:
- 实时预览摄像头画面
- 每2秒刷新一次
- 开始/停止预览
- 显示LIVE指示器

**关键方法**:
- `startPreview()` - 发送开始预览命令
- `stopPreview()` - 发送停止预览命令
- `fetchPreviewImage()` - 获取最新预览图
- `startPreviewRefresh()` - 开始定时刷新

### 文件页 (pages/files)

**功能**:
- 显示照片和视频列表
- 预览文件
- 下载文件
- 删除文件

## 云函数说明

### bindDevice
绑定设备到当前用户，如果设备不存在则自动创建。

### unbindDevice
解除设备与用户的绑定关系。

### getDeviceStatus
获取设备在线状态，通过心跳时间判断（45秒超时）。

### commandSend
发送命令到设备（拍照、录像、开始/停止预览）。

### commandPoll
轮询命令执行状态。

### heartbeat
设备心跳上报（车机端可选使用）。

### getFileList
获取设备上传的文件列表。

### deleteFile
删除云存储中的文件。

## 配置说明

### app.json

```json
{
  "pages": [
    "pages/index/index",
    "pages/scan/scan",
    "pages/control/control",
    "pages/files/files",
    "pages/preview/preview"  // 新增预览页
  ],
  "window": {
    "navigationBarBackgroundColor": "#1a73e8",
    "navigationBarTitleText": "EVCam"
  }
}
```

### app.js

```javascript
wx.cloud.init({
  env: 'cloudbase-0gt2twhpdc512c30',  // 云开发环境ID
  traceUser: true,
});
```

## 部署说明

1. 在微信开发者工具中打开此目录
2. 修改 `app.js` 中的云开发环境ID
3. 右键点击 `cloudfunctions` 目录下的每个云函数
4. 选择"上传并部署：云端安装依赖"
5. 编译并预览小程序

## 注意事项

1. **云开发环境**: 需要在微信开发者工具中创建云开发环境
2. **存储桶ID**: 预览功能需要正确的存储桶ID
3. **权限设置**: 确保云数据库和云存储的权限正确配置
4. **AppID**: 需要使用已注册的小程序AppID
