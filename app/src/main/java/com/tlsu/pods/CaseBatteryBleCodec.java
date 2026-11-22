// SPDX-License-Identifier: GPL-3.0-or-later
// Proximity keys and advertisements adapted from LibrePods, copyright 2025 LibrePods contributors.
package com.tlsu.pods;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

final class CaseBatteryBleCodec {
    static final int APPLE = 76;
    static final byte[] PROXIMITY_PREFIX = {7, 25};
    static final int IRK = 1, ENCRYPTION = 4;

    record Keys(byte[] identity, byte[] encryption) {}
    record Sample(int level, boolean charging, boolean precise) {}

    private CaseBatteryBleCodec() {}

    static byte[] requestKeys(int mask) {
        return new byte[]{4, 0, 4, 0, 0x30, 0, (byte) mask, 0};
    }

    static Keys keys(byte[] bytes, int length) {
        if (!Routing.header(bytes, length) || bytes[4] != 0x31 || length < 7) return null;
        byte[] identity = null, encryption = null;
        int count = bytes[6] & 0xff, offset = 7;
        for (int i = 0; i < count; i++) {
            if (length - offset < 4) return null;
            int kind = bytes[offset] & 0xff;
            int size = (bytes[offset + 2] & 0xff) | ((bytes[offset + 3] & 0xff) << 8);
            offset += 4;
            if (size > length - offset) return null;
            if (size == 16 && kind == IRK) identity = Arrays.copyOfRange(bytes, offset, offset + size);
            if (size == 16 && kind == ENCRYPTION) encryption = Arrays.copyOfRange(bytes, offset, offset + size);
            offset += size;
        }
        return identity == null && encryption == null ? null : new Keys(identity, encryption);
    }

    static boolean matchesAddress(String address, String pairedAddress, byte[] irk) {
        if (address == null || irk == null || irk.length != 16) return false;
        // Some Android stacks already resolve the advertising address to the bonded identity.
        if (address.equalsIgnoreCase(pairedAddress)) return true;
        if (address.length() != 17) return false;
        byte[] mac = new byte[6];
        for (int i = 0; i < mac.length; i++) {
            int at = i * 3;
            int high = Character.digit(address.charAt(at), 16), low = Character.digit(address.charAt(at + 1), 16);
            if (high < 0 || low < 0 || (i < 5 && address.charAt(at + 2) != ':')) return false;
            mac[i] = (byte) ((high << 4) | low);
        }
        if ((mac[0] & 0xc0) != 0x40) return false;
        byte[] key = new byte[16], block = new byte[16];
        for (int i = 0; i < key.length; i++) key[i] = irk[key.length - 1 - i];
        System.arraycopy(mac, 0, block, 13, 3);
        byte[] hash = aes(Cipher.ENCRYPT_MODE, key, block);
        return hash != null && hash[13] == mac[3] && hash[14] == mac[4] && hash[15] == mac[5];
    }

    static Sample battery(byte[] advertisement, byte[] encryptionKey) {
        if (advertisement == null || advertisement.length < 27
                || advertisement[0] != PROXIMITY_PREFIX[0] || advertisement[1] != PROXIMITY_PREFIX[1]) return null;
        // AirPods Max use this format, but do not have a separately powered case.
        int model = (advertisement[3] & 0xff) | ((advertisement[4] & 0xff) << 8);
        if (model == 0x200a || model == 0x201f) return null;
        if (encryptionKey != null) {
            byte[] block = Arrays.copyOfRange(advertisement, advertisement.length - 16, advertisement.length);
            byte[] decrypted = aes(Cipher.DECRYPT_MODE, encryptionKey, block);
            if (decrypted != null) {
                int level = decrypted[3] & 0x7f;
                if (!CaseBattery.valid(level)) return null;
                return new Sample(level, (decrypted[3] & 0x80) != 0, true);
            }
        }
        int flags = advertisement[7] & 0xff;
        int level = flags & 0x0f;
        return level == 0x0f ? null : new Sample(Math.min(level * 10, 100), (flags & 0x40) != 0, false);
    }

    // BLE specifies raw AES blocks without padding for both operations.
    private static byte[] aes(int mode, byte[] key, byte[] block) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"));
            return cipher.doFinal(block);
        } catch (GeneralSecurityException e) {
            return null;
        }
    }
}
