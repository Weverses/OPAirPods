// SPDX-License-Identifier: GPL-3.0-or-later
// Layouts derived from LibrePods Transparency.kt and HearingAid.kt.
package com.tlsu.pods;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public final class AttCodec {
    public static final int TRANSPARENCY = 0x18, LOUD_SOUND = 0x1b, HEARING_AID = 0x2a;
    private AttCodec() {}

    public static void validateHandle(int h) {
        if (h != TRANSPARENCY && h != LOUD_SOUND && h != HEARING_AID) throw new IllegalArgumentException("不支持的 ATT 特征");
    }
    public static void validateValue(int h, byte[] value) {
        validateHandle(h);
        if (value == null || (h == LOUD_SOUND ? value.length != 1 || (value[0] != 0 && value[0] != 1)
                : h == TRANSPARENCY ? value.length != 100 && value.length != 104 : value.length != 104)) {
            throw new IllegalArgumentException("耳机返回了未知的设置格式，已停止写入");
        }
        if (h == HEARING_AID && (value[0] != 2 || (value[1] != 0 && value[1] != 2) || (value[2] != 0x60 && value[2] != 0x64) || value[3] != 0))
            throw new IllegalArgumentException("未知的助听器数据版本（头部 " + Protocol.hex(value,0,4) + "）");
    }
    public static int[] offsets(int handle, String field) {
        boolean hearing = handle == HEARING_AID;
        return switch(field) {
            case "enabled" -> {
                if (hearing) throw new IllegalArgumentException("助听器开关使用 AACP");
                yield new int[]{0};
            }
            case "amplification" -> new int[]{36, 84};
            case "tone" -> new int[]{40, 88};
            case "boost" -> new int[]{44, 92};
            case "noise" -> new int[]{48, 96};
            case "voice" -> new int[]{100};
            default -> throw new IllegalArgumentException("未知的调节项目");
        };
    }
    // Both layouts place the ear-specific fields at the same offsets. All EQ and
    // unknown bytes are retained; a write never replaces the audiogram with defaults.
    public static byte[] patch(int handle, byte[] original, String field, float value) {
        validateValue(handle, original);
        if (handle == LOUD_SOUND) {
            if (!field.equals("enabled") || (value != 0 && value != 1)) throw new IllegalArgumentException("无效的大音量降低状态");
            return new byte[]{(byte)value};
        }
        if (!Float.isFinite(value) || value < 0 || value > 1) throw new IllegalArgumentException("调节数值必须介于 0 和 1");
        if ((field.equals("enabled") || field.equals("boost")) && value != 0 && value != 1) throw new IllegalArgumentException("无效的开关状态");
        byte[] out = original.clone();
        ByteBuffer buffer = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
        for (int offset : offsets(handle, field)) {
            if (offset + 4 > out.length) throw new IllegalArgumentException("此耳机不支持本声调节");
            buffer.putFloat(offset, value);
        }
        if (handle == HEARING_AID) out[2] = 0x64;
        return out;
    }
    public static float value(int handle, byte[] data, String field) {
        validateValue(handle, data);
        if (handle == LOUD_SOUND) return data[0];
        int offset = offsets(handle, field)[0];
        if (offset + 4 > data.length) throw new IllegalArgumentException("此耳机不支持本声调节");
        return ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getFloat(offset);
    }
}
