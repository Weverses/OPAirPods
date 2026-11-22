// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.HexFormat;
import org.junit.Test;

import static org.junit.Assert.*;

public class CaseBatteryBleCodecTest {
    private static final byte[] IRK = hex("9b7d390aa610103405adc857a33402ec");
    private static final byte[] KEY = hex("00112233445566778899aabbccddeeff");

    private static byte[] hex(String value) { return HexFormat.of().parseHex(value.replace(" ", "")); }

    private static byte[] advertisement(int flags, String encrypted) {
        byte[] data = hex("0719012420000000000000" + encrypted);
        data[7] = (byte) flags;
        return data;
    }

    @Test public void readsBoundedKeyRecordsOfBothTypes() {
        byte[] response = hex("04000400310002 01001000 9b7d390aa610103405adc857a33402ec"
                + "04001000 00112233445566778899aabbccddeeff");
        var keys = CaseBatteryBleCodec.keys(response, response.length);
        assertArrayEquals(IRK, keys.identity());
        assertArrayEquals(KEY, keys.encryption());
        assertArrayEquals(hex("0400040030000500"), CaseBatteryBleCodec.requestKeys(5));
        assertNull(CaseBatteryBleCodec.keys(response, response.length - 1));
        response[10] = 1;
        assertNull(CaseBatteryBleCodec.keys(response, response.length));
    }

    @Test public void verifiesResolvableAndAlreadyResolvedAddresses() {
        assertTrue(CaseBatteryBleCodec.matchesAddress("70:81:94:0D:FB:AA", "20:00:00:00:00:01", IRK));
        assertFalse(CaseBatteryBleCodec.matchesAddress("70:81:94:0D:FB:AB", "20:00:00:00:00:01", IRK));
        assertFalse(CaseBatteryBleCodec.matchesAddress("70:81:94:0D:FB:AA", "20:00:00:00:00:01", KEY));
        assertTrue(CaseBatteryBleCodec.matchesAddress("20:00:00:00:00:01", "20:00:00:00:00:01", IRK));
        assertFalse(CaseBatteryBleCodec.matchesAddress("20:00:00:00:00:01", "20:00:00:00:00:01", null));
    }

    @Test public void decryptsPreciseCaseLevelAndRetainsGenuineZero() {
        assertEquals(new CaseBatteryBleCodec.Sample(67, true, true), CaseBatteryBleCodec.battery(
                advertisement(0x46, "601d7544fcf3284aa89bf8caa90140a3"), KEY));
        assertEquals(new CaseBatteryBleCodec.Sample(0, false, true), CaseBatteryBleCodec.battery(
                advertisement(0x46, "76837235fbdcbcf3598c7786e6a1f1ac"), KEY));
        assertNull(CaseBatteryBleCodec.battery(advertisement(0x46, "d68bf4ae89e5e971c0a2f9c84201ef59"), KEY));
    }

    @Test public void readsCoarseFallbackAndIgnoresUnavailableOrUnrelatedAdvertisements() {
        String unknown = "00000000000000000000000000000000";
        assertEquals(new CaseBatteryBleCodec.Sample(60, true, false), CaseBatteryBleCodec.battery(advertisement(0x46, unknown), null));
        assertNull(CaseBatteryBleCodec.battery(advertisement(0x4f, unknown), null));
        byte[] max = advertisement(0x46, unknown);
        max[3] = 0x0a;
        assertNull(CaseBatteryBleCodec.battery(max, null));
        assertNull(CaseBatteryBleCodec.battery(hex("0700"), null));
    }
}
