package com.kooo.evcam.feishu.pb;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Protobuf Frame 消息（飞书 WebSocket 协议）
 * 
 * message Frame {
 *   required uint64 SeqID = 1;
 *   required uint64 LogID = 2;
 *   required int32 service = 3;
 *   required int32 method = 4;
 *   repeated Header headers = 5;
 *   optional string payload_encoding = 6;
 *   optional string payload_type = 7;
 *   optional bytes payload = 8;
 *   optional string LogIDNew = 9;
 * }
 */
public class Pbbp2Frame {
    
    // Frame method 常量
    public static final int METHOD_CONTROL = 0;
    public static final int METHOD_DATA = 1;
    
    // Header type 常量
    public static final String HEADER_TYPE = "type";
    public static final String HEADER_MESSAGE_ID = "message_id";
    public static final String HEADER_TRACE_ID = "trace_id";
    public static final String HEADER_SUM = "sum";
    public static final String HEADER_SEQ = "seq";
    public static final String HEADER_BIZ_RT = "biz_rt";
    
    // Message type 常量
    public static final String TYPE_PING = "ping";
    public static final String TYPE_PONG = "pong";
    public static final String TYPE_EVENT = "event";
    public static final String TYPE_CARD = "card";

    private long seqID;
    private long logID;
    private int service;
    private int method;
    private List<Pbbp2Header> headers = new ArrayList<>();
    private String payloadEncoding;
    private String payloadType;
    private byte[] payload;
    private String logIDNew;

    public Pbbp2Frame() {
    }

    // Getters and Setters
    public long getSeqID() {
        return seqID;
    }

    public void setSeqID(long seqID) {
        this.seqID = seqID;
    }

    public long getLogID() {
        return logID;
    }

    public void setLogID(long logID) {
        this.logID = logID;
    }

    public int getService() {
        return service;
    }

    public void setService(int service) {
        this.service = service;
    }

    public int getMethod() {
        return method;
    }

    public void setMethod(int method) {
        this.method = method;
    }

    public List<Pbbp2Header> getHeaders() {
        return headers;
    }

    public void setHeaders(List<Pbbp2Header> headers) {
        this.headers = headers;
    }

    public void addHeader(Pbbp2Header header) {
        this.headers.add(header);
    }

    public void addHeader(String key, String value) {
        this.headers.add(new Pbbp2Header(key, value));
    }

    public String getPayloadEncoding() {
        return payloadEncoding;
    }

    public void setPayloadEncoding(String payloadEncoding) {
        this.payloadEncoding = payloadEncoding;
    }

    public String getPayloadType() {
        return payloadType;
    }

    public void setPayloadType(String payloadType) {
        this.payloadType = payloadType;
    }

    public byte[] getPayload() {
        return payload;
    }

    public void setPayload(byte[] payload) {
        this.payload = payload;
    }

    public String getPayloadAsString() {
        if (payload == null) return null;
        return new String(payload, StandardCharsets.UTF_8);
    }

    public void setPayloadFromString(String payloadStr) {
        if (payloadStr != null) {
            this.payload = payloadStr.getBytes(StandardCharsets.UTF_8);
        }
    }

    public String getLogIDNew() {
        return logIDNew;
    }

    public void setLogIDNew(String logIDNew) {
        this.logIDNew = logIDNew;
    }

    /**
     * 获取指定 header 的值
     */
    public String getHeaderValue(String key) {
        for (Pbbp2Header header : headers) {
            if (key.equals(header.getKey())) {
                return header.getValue();
            }
        }
        return null;
    }

    /**
     * 判断是否是控制帧
     */
    public boolean isControlFrame() {
        return method == METHOD_CONTROL;
    }

    /**
     * 判断是否是数据帧
     */
    public boolean isDataFrame() {
        return method == METHOD_DATA;
    }

    /**
     * 获取消息类型（从 header 中）
     */
    public String getMessageType() {
        return getHeaderValue(HEADER_TYPE);
    }

    /**
     * 从二进制数据解析 Frame
     */
    public static Pbbp2Frame parseFrom(byte[] data) throws IOException {
        Pbbp2Frame frame = new Pbbp2Frame();
        ProtobufLite.Reader reader = new ProtobufLite.Reader(data);

        while (reader.hasMore()) {
            int tag = reader.readTag();
            if (tag == 0) break;

            int fieldNumber = ProtobufLite.Reader.getFieldNumber(tag);
            int wireType = ProtobufLite.Reader.getWireType(tag);

            switch (fieldNumber) {
                case 1: // SeqID
                    frame.seqID = reader.readUInt64();
                    break;
                case 2: // LogID
                    frame.logID = reader.readUInt64();
                    break;
                case 3: // service
                    frame.service = reader.readInt32();
                    break;
                case 4: // method
                    frame.method = reader.readInt32();
                    break;
                case 5: // headers
                    byte[] headerBytes = reader.readBytes();
                    Pbbp2Header header = Pbbp2Header.parseFrom(headerBytes);
                    frame.headers.add(header);
                    break;
                case 6: // payload_encoding
                    frame.payloadEncoding = reader.readString();
                    break;
                case 7: // payload_type
                    frame.payloadType = reader.readString();
                    break;
                case 8: // payload
                    frame.payload = reader.readBytes();
                    break;
                case 9: // LogIDNew
                    frame.logIDNew = reader.readString();
                    break;
                default:
                    reader.skipField(wireType);
                    break;
            }
        }

        return frame;
    }

    /**
     * 序列化为二进制数据
     */
    public byte[] toByteArray() throws IOException {
        ProtobufLite.Writer writer = new ProtobufLite.Writer();

        // 必填字段
        writer.writeUInt64(1, seqID);
        writer.writeUInt64(2, logID);
        writer.writeInt32(3, service);
        writer.writeInt32(4, method);

        // headers
        for (Pbbp2Header header : headers) {
            writer.writeMessage(5, header.toByteArray());
        }

        // 可选字段
        if (payloadEncoding != null && !payloadEncoding.isEmpty()) {
            writer.writeString(6, payloadEncoding);
        }
        if (payloadType != null && !payloadType.isEmpty()) {
            writer.writeString(7, payloadType);
        }
        if (payload != null && payload.length > 0) {
            writer.writeBytes(8, payload);
        }
        if (logIDNew != null && !logIDNew.isEmpty()) {
            writer.writeString(9, logIDNew);
        }

        return writer.toByteArray();
    }

    /**
     * 创建 Ping 帧
     */
    public static Pbbp2Frame createPingFrame(int serviceId) throws IOException {
        Pbbp2Frame frame = new Pbbp2Frame();
        frame.setSeqID(0);
        frame.setLogID(0);
        frame.setService(serviceId);
        frame.setMethod(METHOD_CONTROL);
        frame.addHeader(HEADER_TYPE, TYPE_PING);
        return frame;
    }

    /**
     * 复制当前帧并设置新的 payload（用于响应）
     */
    public Pbbp2Frame copyWithPayload(byte[] newPayload) throws IOException {
        Pbbp2Frame response = new Pbbp2Frame();
        response.seqID = this.seqID;
        response.logID = this.logID;
        response.service = this.service;
        response.method = this.method;
        response.headers = new ArrayList<>(this.headers);
        response.payloadEncoding = this.payloadEncoding;
        response.payloadType = this.payloadType;
        response.payload = newPayload;
        response.logIDNew = this.logIDNew;
        return response;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("Frame{seqID=").append(seqID);
        sb.append(", logID=").append(logID);
        sb.append(", service=").append(service);
        sb.append(", method=").append(method);
        sb.append(", headers=").append(headers);
        if (payloadEncoding != null) sb.append(", payloadEncoding='").append(payloadEncoding).append("'");
        if (payloadType != null) sb.append(", payloadType='").append(payloadType).append("'");
        if (payload != null) sb.append(", payload=").append(payload.length).append(" bytes");
        if (logIDNew != null) sb.append(", logIDNew='").append(logIDNew).append("'");
        sb.append("}");
        return sb.toString();
    }
}
