// SPDX-License-Identifier: GPL-3.0-or-later
// AACP encoding derived from LibrePods (C) 2025 LibrePods contributors.
package com.tlsu.pods;

import java.util.Arrays;
import java.util.Map;

public final class Protocol {
    private Protocol() {}
    static String hex(byte[] bytes,int start,int end) {
        StringBuilder out=new StringBuilder((end-start)*2);
        for(int i=start;i<end;i++)out.append(Character.forDigit((bytes[i]>>4)&15,16)).append(Character.forDigit(bytes[i]&15,16));
        return out.toString();
    }
    // LibrePods linux/airpods_packets.h: the full notification mask has five FF bytes.
    public static byte[] requestState() { return new byte[]{4,0,4,0,0x0f,0,-1,-1,-1,-1,-1}; }
    // Only commands exposed by the UI are allowed across the bridge.
    public static final Map<Integer, String> COMMANDS = Map.ofEntries(
        Map.entry(0x01, "麦克风"), Map.entry(0x17, "按压速度"),
        Map.entry(0x18, "长按时长"), Map.entry(0x1b, "单耳降噪"),
        Map.entry(0x1a, "长按切换模式"), Map.entry(0x1f, "提示声音量"),
        Map.entry(0x24, "通话按键"), Map.entry(0x3b, "优化电池充电"),
        Map.entry(0x23, "音量滑动速度"), Map.entry(0x25, "滑动调节音量"),
        Map.entry(0x26, "个性化音量"), Map.entry(0x2c, "助听器"),
        Map.entry(0x2e, "自适应强度"), Map.entry(0x33, "听力辅助"),
        Map.entry(0x34, "允许关闭聆听模式"), Map.entry(0x35, "睡眠暂停"),
        Map.entry(0x20, "自动连接"));

    public static void validate(int command, byte[] value) {
        if (!COMMANDS.containsKey(command) || value == null) throw new IllegalArgumentException("不支持的控制命令");
        if (command == 0x1f) {
            if (value.length != 2 || (value[0]&255) > 100 || value[1] != 0x50) throw new IllegalArgumentException("无效的提示声音量");
            return;
        }
        if (command == 0x24) {
            if (value.length != 2 || value[0] != 0 || (value[1] != 2 && value[1] != 3)) throw new IllegalArgumentException("无效的通话按键配置");
            return;
        }
        if (command == 0x2c) {
            if (value.length != 2 || value[0] != 1 || (value[1] != 1 && value[1] != 2)) throw new IllegalArgumentException("无效的助听器状态");
            return;
        }
        if (value.length != 1) throw new IllegalArgumentException("无效的控制参数长度");
        int v = value[0] & 255;
        boolean valid = switch(command) {
            case 0x01 -> v >= 0 && v <= 2;
            case 0x17, 0x18, 0x23 -> v >= 0 && v <= 2;
            case 0x2e -> v >= 0 && v <= 100;
            case 0x1a -> ListeningModes.valid(v);
            default -> v == 1 || v == 2;
        };
        if (!valid) throw new IllegalArgumentException("控制参数超出范围");
    }

    // Reports carry four bytes, while selections contain only the meaningful bytes.
    static byte[] reportedValue(int command,byte[] reported) {
        int length=command==0x1f||command==0x24||command==0x2c?2:1;
        if(reported==null||reported.length<length)return null;
        byte[] value=Arrays.copyOf(reported,length);
        try{validate(command,value);return value;}
        catch(IllegalArgumentException ignored){return null;}
    }

    public static byte[] control(int command, byte[] value) {
        validate(command, value);
        byte[] packet = {4, 0, 4, 0, 9, 0, (byte) command, 0, 0, 0, 0};
        System.arraycopy(value, 0, packet, 7, value.length);
        return packet;
    }

    public static boolean isControl(byte[] data, int len) {
        return data != null && len >= 11 && len <= data.length && data[0] == 4 && data[1] == 0
            && data[2] == 4 && data[3] == 0 && data[4] == 9 && data[5] == 0;
    }

    public static boolean matches(byte[] actual, byte[] expected) {
        return actual != null && actual.length >= expected.length
            && Arrays.equals(Arrays.copyOf(actual, expected.length), expected);
    }
}
