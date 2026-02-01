package com.kooo.evcam.feishu.pb;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 轻量级 Protobuf 编解码工具类
 * 仅支持飞书 WebSocket 协议所需的功能
 */
public class ProtobufLite {

    // Wire types
    public static final int WIRE_TYPE_VARINT = 0;
    public static final int WIRE_TYPE_64BIT = 1;  // fixed64, sfixed64, double
    public static final int WIRE_TYPE_LENGTH_DELIMITED = 2;
    public static final int WIRE_TYPE_32BIT = 5;  // fixed32, sfixed32, float

    /**
     * Protobuf 读取器
     */
    public static class Reader {
        private final ByteArrayInputStream input;
        private final int limit;
        private int position = 0;

        public Reader(byte[] data) {
            this.input = new ByteArrayInputStream(data);
            this.limit = data.length;
        }

        public boolean hasMore() {
            return position < limit;
        }

        public int readTag() throws IOException {
            if (!hasMore()) return 0;
            return (int) readVarint();
        }

        public static int getFieldNumber(int tag) {
            return tag >>> 3;
        }

        public static int getWireType(int tag) {
            return tag & 0x07;
        }

        public long readVarint() throws IOException {
            long result = 0;
            int shift = 0;
            while (true) {
                int b = input.read();
                if (b == -1) throw new IOException("Unexpected end of stream");
                position++;
                result |= (long) (b & 0x7F) << shift;
                if ((b & 0x80) == 0) break;
                shift += 7;
            }
            return result;
        }

        public int readInt32() throws IOException {
            return (int) readVarint();
        }

        public long readUInt64() throws IOException {
            return readVarint();
        }

        public byte[] readBytes() throws IOException {
            int length = (int) readVarint();
            if (length < 0) {
                throw new IOException("Invalid negative length: " + length);
            }
            if (length == 0) {
                return new byte[0];
            }
            byte[] data = new byte[length];
            int totalRead = 0;
            while (totalRead < length) {
                int read = input.read(data, totalRead, length - totalRead);
                if (read == -1) {
                    throw new IOException("Unexpected end of stream, expected " + length + " bytes, got " + totalRead);
                }
                totalRead += read;
            }
            position += length;
            return data;
        }

        public String readString() throws IOException {
            return new String(readBytes(), StandardCharsets.UTF_8);
        }

        public void skipField(int wireType) throws IOException {
            switch (wireType) {
                case WIRE_TYPE_VARINT:
                    readVarint();
                    break;
                case WIRE_TYPE_64BIT:
                    // 跳过 8 字节
                    skipBytes(8);
                    break;
                case WIRE_TYPE_LENGTH_DELIMITED:
                    readBytes();
                    break;
                case WIRE_TYPE_32BIT:
                    // 跳过 4 字节
                    skipBytes(4);
                    break;
                default:
                    // 未知 wire type，尝试跳过（可能是 deprecated group types 3/4）
                    // 记录警告但不抛异常
                    break;
            }
        }

        private void skipBytes(int count) throws IOException {
            long skipped = input.skip(count);
            position += (int) skipped;
            if (skipped < count) {
                throw new IOException("Unexpected end of stream while skipping");
            }
        }
    }

    /**
     * Protobuf 写入器
     */
    public static class Writer {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();

        public byte[] toByteArray() {
            return output.toByteArray();
        }

        public void writeTag(int fieldNumber, int wireType) throws IOException {
            writeVarint((fieldNumber << 3) | wireType);
        }

        public void writeVarint(long value) throws IOException {
            while (true) {
                if ((value & ~0x7FL) == 0) {
                    output.write((int) value);
                    return;
                } else {
                    output.write(((int) value & 0x7F) | 0x80);
                    value >>>= 7;
                }
            }
        }

        public void writeInt32(int fieldNumber, int value) throws IOException {
            writeTag(fieldNumber, WIRE_TYPE_VARINT);
            writeVarint(value);
        }

        public void writeUInt64(int fieldNumber, long value) throws IOException {
            writeTag(fieldNumber, WIRE_TYPE_VARINT);
            writeVarint(value);
        }

        public void writeBytes(int fieldNumber, byte[] value) throws IOException {
            writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED);
            writeVarint(value.length);
            output.write(value);
        }

        public void writeString(int fieldNumber, String value) throws IOException {
            if (value != null && !value.isEmpty()) {
                writeBytes(fieldNumber, value.getBytes(StandardCharsets.UTF_8));
            }
        }

        public void writeMessage(int fieldNumber, byte[] messageBytes) throws IOException {
            writeTag(fieldNumber, WIRE_TYPE_LENGTH_DELIMITED);
            writeVarint(messageBytes.length);
            output.write(messageBytes);
        }
    }
}
