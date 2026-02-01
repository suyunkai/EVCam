# 代码修改清单

本文档列出了所有修改的代码文件及其具体修改内容。

## 车机端 Android 代码修改

### 1. AppConfig.java

**文件路径**: `app/src/main/java/com/kooo/evcam/AppConfig.java`

**修改内容**:

#### 添加领克07/08车型常量

```java
// 位置: 类常量定义区域
public static final String CAR_MODEL_LYNKCO_07 = "lynkco_07";
```

#### 添加领克07/08配置

```java
// 位置: getCarModelConfig() 方法中
case CAR_MODEL_LYNKCO_07:
    return new CarModelConfig(
        "领克07/08",
        1,                    // 单摄像头
        new String[]{"0"},    // 摄像头ID
        "2560x1600",          // 高分辨率
        false,                // 无全景
        false                 // 无鱼眼校正
    );
```

---

### 2. MainActivity.java

**文件路径**: `app/src/main/java/com/kooo/evcam/MainActivity.java`

**修改内容**:

#### 添加领克07初始化分支

```java
// 位置: initCameras() 方法中
String carModel = appConfig.getCarModel();
if (AppConfig.CAR_MODEL_LYNKCO_07.equals(carModel)) {
    // 领克07/08：单摄像头模式
    initCamerasForLynkCo07(cameraIds);
} else if (...) {
    // 其他车型
}
```

#### 添加领克07专用初始化方法

```java
// 位置: 类方法区域
private void initCamerasForLynkCo07(String[] cameraIds) {
    String frontId = appConfig.getCameraId("front");
    
    // 验证摄像头ID有效性
    boolean validId = false;
    for (String id : cameraIds) {
        if (id.equals(frontId)) {
            validId = true;
            break;
        }
    }
    
    if (!validId && cameraIds.length > 0) {
        frontId = cameraIds[0];
        AppLog.w(TAG, "领克07配置的摄像头ID无效，使用默认摄像头: " + frontId);
    }
    
    if (textureFront != null && frontId != null) {
        cameraManager.initCameras(
                frontId, textureFront,
                null, null,
                null, null,
                null, null
        );
        AppLog.d(TAG, "领克07初始化：摄像头ID=" + frontId + 
                ", 分辨率=" + appConfig.getTargetResolution());
    } else {
        Toast.makeText(this, "没有可用的摄像头", Toast.LENGTH_SHORT).show();
    }
}
```

#### 添加微信云管理器启动方法

```java
// 位置: 类方法区域
public void startWechatCloudManager() {
    if (!isWechatCloudManagerRunning()) {
        startWechatCloudService();
    }
}

public boolean isWechatCloudManagerRunning() {
    return wechatCloudManager != null && wechatCloudManager.isRunning();
}
```

---

### 3. WechatCloudManager.java

**文件路径**: `app/src/main/java/com/kooo/evcam/wechat/WechatCloudManager.java`

**修改内容**:

#### 添加强制刷新Token方法

```java
// 位置: 类方法区域
private void forceRefreshToken() {
    accessToken = null;
    tokenExpireTime = 0;
    refreshAccessToken();
}
```

#### 修改心跳更新方法

```java
// 位置: updateHeartbeat() 方法
private void updateHeartbeat() {
    try {
        if (!refreshAccessToken()) {
            handleError("刷新 token 失败");
            return;
        }
        
        long now = System.currentTimeMillis();
        String query = "db.collection(\"devices\").where({deviceId:\"" + 
                config.getDeviceId() + "\"}).update({data:{" +
                "lastHeartbeat:" + now + "," +
                "updateTime:" + now +
                "}})";
        
        JsonObject result = executeDbUpdate(query);
        
        if (result != null) {
            consecutiveErrors = 0;
            if (!isConnected) {
                isConnected = true;
                mainHandler.post(() -> connectionCallback.onConnected());
            }
            AppLog.d(TAG, "心跳更新成功，设备ID: " + config.getDeviceId());
        } else {
            // 可能是 token 过期，强制刷新
            AppLog.w(TAG, "心跳更新返回null，强制刷新token");
            forceRefreshToken();
        }
    } catch (Exception e) {
        AppLog.e(TAG, "更新心跳失败", e);
        handleError("心跳失败: " + e.getMessage());
    }
}
```

#### 添加数据库查询重试机制

```java
// 位置: executeDbQuery() 方法改造
private JsonObject executeDbQuery(String query) throws IOException {
    return executeDbQueryWithRetry(query, true);
}

private JsonObject executeDbQueryWithRetry(String query, boolean allowRetry) throws IOException {
    String url = DB_QUERY_URL + "?access_token=" + accessToken;
    
    // ... 执行请求 ...
    
    if (result.has("errcode")) {
        int errcode = result.get("errcode").getAsInt();
        if (errcode == 40001 && allowRetry) {
            // Token 过期，强制刷新后重试
            AppLog.w(TAG, "Token过期(40001)，刷新后重试查询");
            forceRefreshToken();
            return executeDbQueryWithRetry(query, false);
        } else if (errcode != 0) {
            AppLog.w(TAG, "数据库查询错误: " + responseBody);
            return null;
        }
    }
    return result;
}
```

#### 添加数据库更新重试机制

```java
// 位置: executeDbUpdate() 方法改造
private JsonObject executeDbUpdate(String query) throws IOException {
    return executeDbUpdateWithRetry(query, true);
}

private JsonObject executeDbUpdateWithRetry(String query, boolean allowRetry) throws IOException {
    String url = DB_UPDATE_URL + "?access_token=" + accessToken;
    
    // ... 执行请求 ...
    
    if (result.has("errcode")) {
        int errcode = result.get("errcode").getAsInt();
        if (errcode == 40001 && allowRetry) {
            // Token 过期，强制刷新后重试
            AppLog.w(TAG, "Token过期(40001)，刷新后重试更新");
            forceRefreshToken();
            return executeDbUpdateWithRetry(query, false);
        } else if (errcode != 0) {
            AppLog.w(TAG, "数据库更新错误: " + responseBody);
            return null;
        }
    }
    return result;
}
```

---

### 4. WechatMiniConfig.java

**文件路径**: `app/src/main/java/com/kooo/evcam/wechat/WechatMiniConfig.java`

**修改内容**:

#### 添加小程序AppID配置

```java
// 位置: 常量定义区域
private static final String KEY_MINI_PROGRAM_APP_ID = "mini_program_app_id";
private static final String DEFAULT_MINI_PROGRAM_APP_ID = "wx1df526b63078a9e5";

// 位置: 类方法区域
public String getMiniProgramAppId() {
    return prefs.getString(KEY_MINI_PROGRAM_APP_ID, DEFAULT_MINI_PROGRAM_APP_ID);
}

public void setMiniProgramAppId(String appId) {
    prefs.edit().putString(KEY_MINI_PROGRAM_APP_ID, appId).apply();
}
```

---

### 5. QrCodeGenerator.java

**文件路径**: `app/src/main/java/com/kooo/evcam/wechat/QrCodeGenerator.java`

**修改内容**:

```java
// 简化二维码生成，直接使用JSON数据
public static Bitmap generateMiniProgramQrCode(WechatMiniConfig config, int size) {
    // 直接使用JSON格式绑定数据
    // 用户需要在小程序内扫描
    return generateBindQrCodeWithBranding(config, size);
}
```

---

### 6. WechatMiniFragment.java

**文件路径**: `app/src/main/java/com/kooo/evcam/WechatMiniFragment.java`

**修改内容**:

- 简化视图引用（移除服务器配置相关控件）
- 添加状态指示器
- 自动启动微信云服务
- 更新二维码生成逻辑

---

### 7. fragment_wechat_mini.xml

**文件路径**: `app/src/main/res/layout/fragment_wechat_mini.xml`

**修改内容**:

- 简化布局，只保留二维码和连接状态
- 移除服务器配置区域
- 移除连接控制区域
- 放大二维码显示区域
- 添加状态指示器

---

## 小程序代码修改

### 1. app.json

**修改内容**: 添加预览页面路由

```json
{
  "pages": [
    "pages/index/index",
    "pages/scan/scan",
    "pages/control/control",
    "pages/files/files",
    "pages/preview/preview"  // 新增
  ]
}
```

---

### 2. pages/preview/ (新增)

**新增文件**:
- `preview.js` - 预览页面逻辑
- `preview.wxml` - 预览页面结构
- `preview.wxss` - 预览页面样式
- `preview.json` - 预览页面配置

**主要功能**:
- 发送 start_preview/stop_preview 命令
- 每2秒刷新预览图
- 显示LIVE指示器
- 错误处理和重试

---

### 3. pages/index/

**修改内容**:
- 添加"实时预览"入口按钮
- 添加 goToPreview() 方法
- 添加心跳调试信息显示
- 优化设备状态刷新逻辑

---

### 4. pages/control/

**修改内容**:
- 添加"预览"按钮
- 添加 goToPreview() 方法
- 改用emoji图标
- 添加命令执行提示

---

## 资源文件修改

### 新增 drawable 文件

- `status_indicator_online.xml` - 在线状态指示器（绿色）
- `status_indicator_offline.xml` - 离线状态指示器（红色）
- `status_indicator_connecting.xml` - 连接中状态指示器（黄色）

---

## 构建配置修改

### build.gradle.kts

**修改内容**: 更新版本号

```kotlin
versionName = "0.9.9-test-01312115"
```
