// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Merges case readings without changing the OEM left and right battery fields. */
final class CaseBattery {
    static final long RETENTION_MS = 15 * 60 * 1000L;
    static final long BLE_CHARGING_MS = 30_000;
    enum Source { UNKNOWN, AACP, CACHED_AACP, BLE, BLE_ESTIMATE }
    record Display(int level, int status, Source source, long nextChangeAt) {
        boolean sameValue(Display other) { return other != null && level == other.level && status == other.status; }
    }
    private static final Display UNKNOWN = new Display(-1, 4, Source.UNKNOWN, 0);
    private record BleReading(int level, boolean charging, long at) {
        BleReading disconnected() { return new BleReading(level, false, at); }
        Display display(Source source, long now) {
            boolean freshCharge = charging && now - at < BLE_CHARGING_MS;
            return new Display(level, freshCharge ? 1 : 2, source, at + (freshCharge ? BLE_CHARGING_MS : RETENTION_MS));
        }
    }
    private static final class Reading {
        boolean max;
        int level = -1, status = 4, knownLevel = -1;
        long knownAt = -1;
        BleReading exact, estimate;

        Display display(long now) {
            if (valid(level)) {
                int charging = switch (status) { case 1, 2, 5 -> status; default -> 2; };
                return new Display(level, charging, Source.AACP, 0);
            }
            boolean cached = recent(knownAt, now);
            if (exact != null && recent(exact.at(), now) && (!cached || exact.at() >= knownAt))
                return exact.display(Source.BLE, now);
            if (cached) return new Display(knownLevel, 2, Source.CACHED_AACP, knownAt + RETENTION_MS);
            if (estimate != null && recent(estimate.at(), now)) return estimate.display(Source.BLE_ESTIMATE, now);
            return UNKNOWN;
        }
    }
    private final Map<String, Reading> readings = new LinkedHashMap<>();

    synchronized int[] update(String address, boolean isMax, int[] battery, long now) {
        if (address == null || battery == null || battery.length < 6) return battery;
        if (isMax) {
            Reading reading = new Reading();
            reading.max = true;
            remember(address, reading);
            return battery;
        }
        Reading reading = reading(address);
        reading.max = false;
        int level = battery[2], status = battery[5];
        if (level == 0 && !reportedZero(level, status)) level = -1;
        if (valid(level) && (status != 4 || level != reading.knownLevel)) {
            reading.knownLevel = level;
            reading.knownAt = now;
        }
        // The sleep report can include a last known level or a zero placeholder.
        reading.level = status == 4 ? -1 : level;
        reading.status = status;
        return project(battery, reading.display(now));
    }

    synchronized int[] read(String address, int[] battery, long now) {
        if (battery == null || battery.length < 6) return battery;
        Reading reading = readings.get(address);
        if (reading != null) return reading.max ? battery : project(battery, reading.display(now));
        if (battery[2] != 0 || reportedZero(battery[2], battery[5])) return battery;
        return project(battery, UNKNOWN);
    }

    synchronized boolean tracked(String address) {
        Reading reading = readings.get(address);
        return reading != null && !reading.max;
    }

    synchronized boolean needsBle(String address) {
        Reading reading = readings.get(address);
        return reading == null || !reading.max && !valid(reading.level);
    }

    synchronized Display display(String address, long now) {
        Reading reading = readings.get(address);
        return reading == null ? UNKNOWN : reading.display(now);
    }

    synchronized void updateBle(String address, CaseBatteryBleCodec.Sample sample, long at) {
        if (!needsBle(address) || !valid(sample.level())) return;
        Reading reading = reading(address);
        BleReading next = new BleReading(sample.level(), sample.charging(), at);
        if (sample.precise()) {
            if (reading.exact == null || reading.exact.at() <= at) reading.exact = next;
        } else if (reading.estimate == null || reading.estimate.at() <= at) {
            reading.estimate = next;
        }
    }

    synchronized void disconnected(String address) {
        Reading reading = readings.get(address);
        if (reading == null) return;
        reading.level = -1;
        reading.status = 4;
        if (reading.exact != null) reading.exact = reading.exact.disconnected();
        if (reading.estimate != null) reading.estimate = reading.estimate.disconnected();
    }

    private Reading reading(String address) {
        Reading reading = readings.get(address);
        if (reading == null) {
            reading = new Reading();
            remember(address, reading);
        }
        return reading;
    }

    private void remember(String address, Reading reading) {
        readings.put(address, reading);
        if (readings.size() > 64) readings.remove(readings.keySet().iterator().next());
    }

    private static boolean recent(long at, long now) {
        return at >= 0 && now >= at && now - at < RETENTION_MS;
    }

    private static int[] project(int[] battery, Display display) {
        if (battery[2] == display.level() && battery[5] == display.status()) return battery;
        int[] result = battery.clone();
        result[2] = display.level();
        result[5] = display.status();
        return result;
    }

    static boolean valid(int level) { return level >= 0 && level <= 100; }

    static boolean reportedZero(int level, int status) {
        return level == 0 && (status == 1 || status == 2 || status == 5);
    }

    /** OEM otherwise ignores every changed report after this old state. */
    static boolean blocksUpdate(int[] previous, int[] next) {
        return previous != null && next != null && previous.length == 6 && next.length == 6
                && previous[3] == 1 && previous[4] == 1 && previous[5] == 4
                && !Arrays.equals(previous, next);
    }
}
