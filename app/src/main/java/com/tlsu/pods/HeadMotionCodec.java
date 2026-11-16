// SPDX-License-Identifier: GPL-3.0-or-later
// Tracking frames and sample offsets derived from LibrePods (C) 2025 contributors.
package com.tlsu.pods;

/** AACP sensor reports have a protobuf envelope with variable-length counters.
 * Sensor offsets are relative to the binary report, never to the whole packet. */
final class HeadMotionCodec {
    private HeadMotionCodec() {}
    record Sample(int horizontal, int vertical, long at) {}

    static byte[] tracking(boolean start, boolean alternate) {
        String hex = alternate
                ? start ? "040004001700000010000f000873420b081010021a0501409c0000"
                        : "040004001700000010000f000875420b081010021a050100000000"
                : start ? "04000400170000001000100008a102420b080e10021a0501409c0000"
                        : "040004001700000010001100087e1002420b084e10021a050100000000";
        byte[] result = new byte[hex.length() / 2];
        for (int i = 0; i < result.length; i++) result[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        return result;
    }

    static Sample sample(byte[] data, int length, long at) {
        if (!header(data, length, 0x17) || length < 70
                || data[6] != 0 || data[7] != 0 || data[8] != 0x10 || data[9] != 0
                || unsigned16(data, 10) != length - 12) return null;
        Envelope envelope = envelope(data, 12, length, 2);
        if (envelope == null || envelope.type != 3) return null;
        int payload;
        if (envelope.report >= 0) {
            if (envelope.nested >= 0 || envelope.reportLength != 60
                    || data[envelope.report] != 0x10 || data[envelope.report+1] != 0) return null;
            payload = envelope.report + 2;
        } else {
            if (envelope.nested < 0) return null;
            Envelope sensor = envelope(data, envelope.nested, envelope.nested + envelope.nestedLength, 1);
            if (sensor == null || sensor.type != 16 || sensor.report < 0
                    || sensor.reportLength != 58 || sensor.nested >= 0) return null;
            payload = sensor.report;
        }
        // Captured firmware reports either a 60-byte typed record in field 3,
        // or sensor type 16 + its 58-byte payload inside field 7. The payload
        // and axes are identical; envelope counters must never become motion.
        if (data[payload] != 1 || data[payload+9] != 3 || data[payload+10] != 0) return null;
        int horizontal = signed16(data, payload+28), vertical = signed16(data, payload+30);
        if (Math.abs(horizontal) > 6000 || Math.abs(vertical) > 6000) return null;
        return new Sample(horizontal, vertical, at);
    }

    private record Envelope(int type, int report, int reportLength, int nested, int nestedLength) {}

    private static Envelope envelope(byte[] data, int start, int end, int typeField) {
        int[] cursor = {start};
        int type = -1, report = -1, reportLength = 0, nested = -1, nestedLength = 0;
        while (cursor[0] < end) {
            long key = varint(data, cursor, end);
            if (key <= 0) return null;
            int field = (int)(key >>> 3), wire = (int)(key & 7);
            if (field <= 0 || key >>> 3 > 0x1fffffffL) return null;
            if (wire == 0) {
                long value = varint(data, cursor, end);
                if (value < 0) return null;
                if (field == typeField) { if (type != -1 || value > Integer.MAX_VALUE) return null; type = (int)value; }
            } else if (wire == 2) {
                long size = varint(data, cursor, end);
                if (size < 0 || size > end - cursor[0]) return null;
                if (field == 3) {
                    if (report != -1) return null;
                    report = cursor[0]; reportLength = (int)size;
                } else if (field == 7) {
                    if (nested != -1) return null;
                    nested = cursor[0]; nestedLength = (int)size;
                }
                cursor[0] += (int)size;
            } else if (wire == 1 || wire == 5) {
                int size = wire == 1 ? 8 : 4;
                if (size > end - cursor[0]) return null;
                cursor[0] += size;
            } else return null;
        }
        return new Envelope(type, report, reportLength, nested, nestedLength);
    }

    private static long varint(byte[] data, int[] cursor, int end) {
        long value = 0;
        for (int shift = 0; shift < 63 && cursor[0] < end; shift += 7) {
            int next = data[cursor[0]++] & 255;
            value |= (long)(next & 127) << shift;
            if ((next & 128) == 0) return value;
        }
        return -1;
    }
    private static int unsigned16(byte[] data, int offset) {
        return (data[offset] & 255) | ((data[offset+1] & 255) << 8);
    }

    static boolean header(byte[] data, int length, int opcode) {
        return data != null && length >= 6 && length <= data.length
                && data[0] == 4 && data[1] == 0 && data[2] == 4 && data[3] == 0
                && (data[4] & 255) == opcode && data[5] == 0;
    }

    static Boolean worn(byte[] data, int length) {
        if (!header(data, length, 6) || length < 8) return null;
        // 0 is in-ear, 1 out-of-ear, 2 in-case, 3 disconnected in the known layout.
        int first = data[6] & 255, second = data[7] & 255;
        if (first > 3 || second > 3) return false;
        return first == 0 || second == 0;
    }

    private static int signed16(byte[] data, int offset) {
        return (short) ((data[offset] & 255) | ((data[offset + 1] & 255) << 8));
    }
}
