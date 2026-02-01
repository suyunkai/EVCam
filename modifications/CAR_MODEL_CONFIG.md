# 车型配置详细说明

## 概述

EVCam 支持多种车型的摄像头配置，每种车型有不同的摄像头数量、ID映射和分辨率设置。

## 配置结构

### CarModelConfig 类

```java
public static class CarModelConfig {
    public final String displayName;      // 显示名称
    public final int cameraCount;         // 摄像头数量
    public final String[] cameraIds;      // 摄像头ID列表
    public final String targetResolution; // 目标分辨率
    public final boolean panoramicMode;   // 全景模式
    public final boolean fisheyeCorrection; // 鱼眼校正
}
```

## 预设车型配置

### 1. 银河E5 (默认)

```java
CAR_MODEL_GALAXY_E5 = "galaxy_e5"

配置:
- 摄像头数量: 4
- 摄像头ID: ["0", "1", "2", "3"]
- 分辨率: 1280x800
- 全景模式: false
- 鱼眼校正: false

说明: 默认的4摄像头配置，适用于配备前后左右4个摄像头的车型
```

### 2. 领克07/08

```java
CAR_MODEL_LYNKCO_07 = "lynkco_07"

配置:
- 摄像头数量: 1
- 摄像头ID: ["0"]
- 分辨率: 2560x1600
- 全景模式: false
- 鱼眼校正: false

说明: 单摄像头配置，适用于只有前置行车记录仪的车型
特点: 高分辨率支持 (2560x1600)
```

### 3. 理想L7

```java
CAR_MODEL_L7 = "l7"

配置:
- 摄像头数量: 2
- 摄像头ID: ["0", "1"]
- 分辨率: 1920x1080
- 全景模式: false
- 鱼眼校正: false

说明: 双摄像头配置，前后双录
```

### 4. 理想L7多摄

```java
CAR_MODEL_L7_MULTI = "l7_multi"

配置:
- 摄像头数量: 4
- 摄像头ID: ["0", "1", "2", "3"]
- 分辨率: 1920x1080
- 全景模式: false
- 鱼眼校正: false

说明: 4摄像头配置，支持360度环视
```

### 5. 手机测试

```java
CAR_MODEL_PHONE = "phone"

配置:
- 摄像头数量: 2
- 摄像头ID: ["0", "1"] (前置/后置)
- 分辨率: 1920x1080
- 全景模式: false
- 鱼眼校正: false

说明: 用于在手机上测试APP功能
```

### 6. 自定义配置

```java
CAR_MODEL_CUSTOM = "custom"

配置:
- 摄像头数量: 用户自定义 (1-4)
- 摄像头ID: 用户自定义
- 分辨率: 用户自定义
- 全景模式: 用户自定义
- 鱼眼校正: 用户自定义

说明: 允许用户完全自定义所有参数
```

## 代码位置

### AppConfig.java 中的车型定义

```java
// 文件: app/src/main/java/com/kooo/evcam/AppConfig.java

// 车型常量
public static final String CAR_MODEL_GALAXY_E5 = "galaxy_e5";
public static final String CAR_MODEL_LYNKCO_07 = "lynkco_07";
public static final String CAR_MODEL_L7 = "l7";
public static final String CAR_MODEL_L7_MULTI = "l7_multi";
public static final String CAR_MODEL_PHONE = "phone";
public static final String CAR_MODEL_CUSTOM = "custom";

// 获取车型配置
public CarModelConfig getCarModelConfig() {
    String carModel = getCarModel();
    switch (carModel) {
        case CAR_MODEL_LYNKCO_07:
            return new CarModelConfig(
                "领克07/08",
                1,
                new String[]{"0"},
                "2560x1600",
                false,
                false
            );
        // ... 其他车型
    }
}
```

### MainActivity.java 中的初始化逻辑

```java
// 文件: app/src/main/java/com/kooo/evcam/MainActivity.java

private void initCameras(String[] cameraIds) {
    String carModel = appConfig.getCarModel();
    
    if (AppConfig.CAR_MODEL_LYNKCO_07.equals(carModel)) {
        // 领克07/08：单摄像头模式
        initCamerasForLynkCo07(cameraIds);
    } else if (AppConfig.CAR_MODEL_L7.equals(carModel) || 
               AppConfig.CAR_MODEL_L7_MULTI.equals(carModel)) {
        // 理想L7系列
        initCamerasForL7(cameraIds);
    } else if (AppConfig.CAR_MODEL_PHONE.equals(carModel)) {
        // 手机测试模式
        initCamerasForPhone(cameraIds);
    } else if (appConfig.isCustomCarModel()) {
        // 自定义配置
        initCamerasForCustom(cameraIds);
    } else {
        // 默认：银河E5
        initCamerasForGalaxyE5(cameraIds);
    }
}

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
    
    // 只初始化一个摄像头
    if (textureFront != null && frontId != null) {
        cameraManager.initCameras(
            frontId, textureFront,
            null, null,
            null, null,
            null, null
        );
    }
}
```

## 配置持久化

车型配置保存在 SharedPreferences 中：

**文件位置**: `/data/data/com.kooo.evcam/shared_prefs/app_config.xml`

**示例内容**:
```xml
<?xml version='1.0' encoding='utf-8' standalone='yes' ?>
<map>
    <boolean name="first_launch" value="false" />
    <string name="car_model">lynkco_07</string>
    <string name="camera_id_front">0</string>
    <string name="target_resolution">2560x1600</string>
</map>
```

## 如何添加新车型

1. **在 AppConfig.java 中添加常量**:
```java
public static final String CAR_MODEL_NEW_CAR = "new_car";
```

2. **在 getCarModelConfig() 中添加配置**:
```java
case CAR_MODEL_NEW_CAR:
    return new CarModelConfig(
        "新车型名称",
        2,                      // 摄像头数量
        new String[]{"0", "1"}, // 摄像头ID
        "1920x1080",            // 分辨率
        false,                  // 全景模式
        false                   // 鱼眼校正
    );
```

3. **在 MainActivity.java 中添加初始化逻辑**:
```java
} else if (AppConfig.CAR_MODEL_NEW_CAR.equals(carModel)) {
    initCamerasForNewCar(cameraIds);
}
```

4. **在 SettingsFragment.java 中添加选项**:
```java
// 添加到车型选择列表
carModelNames.add("新车型名称");
carModelValues.add(CAR_MODEL_NEW_CAR);
```

## 调试命令

```bash
# 查看当前配置
adb shell "run-as com.kooo.evcam cat /data/data/com.kooo.evcam/shared_prefs/app_config.xml"

# 查看可用摄像头
adb logcat -d | grep -i "cameraId\|摄像头"

# 查看初始化日志
adb logcat -d | grep -i "initCameras\|领克\|lynkco"
```
