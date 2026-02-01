package com.kooo.evcam.feishu.pb;

import java.io.IOException;

/**
 * Protobuf Header 消息
 * 
 * message Header {
 *   required string key = 1;
 *   required string value = 2;
 * }
 */
public class Pbbp2Header {
    private String key;
    private String value;

    public Pbbp2Header() {
    }

    public Pbbp2Header(String key, String value) {
        this.key = key;
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    /**
     * 从二进制数据解析 Header
     */
    public static Pbbp2Header parseFrom(byte[] data) throws IOException {
        Pbbp2Header header = new Pbbp2Header();
        ProtobufLite.Reader reader = new ProtobufLite.Reader(data);

        while (reader.hasMore()) {
            int tag = reader.readTag();
            if (tag == 0) break;

            int fieldNumber = ProtobufLite.Reader.getFieldNumber(tag);
            int wireType = ProtobufLite.Reader.getWireType(tag);

            switch (fieldNumber) {
                case 1: // key
                    header.key = reader.readString();
                    break;
                case 2: // value
                    header.value = reader.readString();
                    break;
                default:
                    reader.skipField(wireType);
                    break;
            }
        }

        return header;
    }

    /**
     * 序列化为二进制数据
     */
    public byte[] toByteArray() throws IOException {
        ProtobufLite.Writer writer = new ProtobufLite.Writer();

        if (key != null) {
            writer.writeString(1, key);
        }
        if (value != null) {
            writer.writeString(2, value);
        }

        return writer.toByteArray();
    }

    @Override
    public String toString() {
        return "Header{key='" + key + "', value='" + value + "'}";
    }
}
