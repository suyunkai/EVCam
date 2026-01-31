package com.kooo.evcam.remote.core;

/**
 * 远程平台枚举
 * 定义支持的远程控制平台类型
 */
public enum RemotePlatform {
    DINGTALK("钉钉", "dingtalk"),
    TELEGRAM("Telegram", "telegram"),
    FEISHU("飞书", "feishu");

    private final String displayName;
    private final String code;

    RemotePlatform(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getCode() {
        return code;
    }

    /**
     * 根据代码获取平台枚举
     */
    public static RemotePlatform fromCode(String code) {
        for (RemotePlatform platform : values()) {
            if (platform.code.equalsIgnoreCase(code)) {
                return platform;
            }
        }
        return null;
    }
}
