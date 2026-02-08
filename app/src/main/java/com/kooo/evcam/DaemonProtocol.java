package com.kooo.evcam;

/**
 * EVCC root daemon 通信协议常量。
 * 通信方式：JSON-lines over abstract Unix domain socket。
 *
 * 此文件仅包含客户端所需的常量，不包含任何 root 逻辑。
 * daemon 由 EVCC 应用管理和启动。
 */
public class DaemonProtocol {
    /** Abstract socket name（由 EVCC daemon 监听） */
    public static final String SOCKET_NAME = "evcc_car_daemon";

    // Commands (client → server)
    public static final String CMD_GET_INT = "getInt";
    public static final String CMD_IS_CONNECTED = "isConnected";
    public static final String CMD_START_MONITOR = "startMonitor";
    public static final String CMD_STOP_MONITOR = "stopMonitor";

    // JSON keys
    public static final String KEY_ID = "id";
    public static final String KEY_CMD = "cmd";
    public static final String KEY_TYPE = "type";
    public static final String KEY_STATUS = "status";
    public static final String KEY_PROP_ID = "propId";
    public static final String KEY_AREA_ID = "areaId";
    public static final String KEY_VALUE = "value";
    public static final String KEY_INT_VALUE = "intValue";
    public static final String KEY_BOOL_VALUE = "boolValue";
    public static final String KEY_EVENT = "event";
    public static final String KEY_MESSAGE = "message";

    // Type values
    public static final String TYPE_RESPONSE = "response";
    public static final String TYPE_EVENT = "event";

    // Status values
    public static final String STATUS_OK = "ok";

    // Event names
    public static final String EVENT_PROPERTY_CHANGED = "propertyChanged";
}
