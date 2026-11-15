// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Projects real AACP case readings onto the OEM six-element battery format. */
final class CaseBattery {
    static final long RETENTION_MS = 15 * 60 * 1000L;
    private record Reading(int level, int status, int knownLevel, long knownAt) {}
    private final Map<String, Reading> readings = new LinkedHashMap<>();

    synchronized int[] update(String address, boolean isMax, int[] battery, long now) {
        if (address == null || battery == null || battery.length < 6) return battery;
        if (isMax) {
            readings.remove(address);
            return battery;
        }
        Reading previous = readings.get(address);
        int known = previous == null ? -1 : previous.knownLevel();
        long knownAt = previous == null ? now : previous.knownAt();
        if (valid(battery[2])) {
            known = battery[2];
            knownAt = now;
        }
        Reading reading = new Reading(battery[2], battery[5], known, knownAt);
        readings.put(address, reading);
        if (readings.size() > 64) readings.remove(readings.keySet().iterator().next());
        return project(battery, reading, now);
    }

    synchronized int[] read(String address, int[] battery, long now) {
        if (battery == null || battery.length < 6) return battery;
        Reading reading = readings.get(address);
        return reading == null ? battery : project(battery, reading, now);
    }

    synchronized void disconnected(String address) {
        Reading reading = readings.get(address);
        if (reading != null) readings.put(address,
                new Reading(-1, 4, reading.knownLevel(), reading.knownAt()));
    }

    private static int[] project(int[] battery, Reading reading, long now) {
        int[] result = battery.clone();
        if (valid(reading.level())) {
            result[2] = reading.level();
            // DISCONNECTED means the case is asleep, not that its level is zero.
            result[5] = switch (reading.status()) { case 1, 2, 5 -> reading.status(); default -> 2; };
        } else if (valid(reading.knownLevel()) && now >= reading.knownAt()
                && now - reading.knownAt() < RETENTION_MS) {
            result[2] = reading.knownLevel();
            result[5] = 2; // A cached level never asserts that charging is ongoing.
        } else {
            result[2] = -1;
            result[5] = 4;
        }
        return result;
    }

    static boolean valid(int level) { return level >= 0 && level <= 100; }

    /** OEM otherwise ignores every changed report after this old state. */
    static boolean blocksUpdate(int[] previous, int[] next) {
        return previous != null && next != null && previous.length == 6 && next.length == 6
                && previous[3] == 1 && previous[4] == 1 && previous[5] == 4
                && !Arrays.equals(previous, next);
    }
}
