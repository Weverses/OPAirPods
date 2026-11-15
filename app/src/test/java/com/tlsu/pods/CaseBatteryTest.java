// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import static org.junit.Assert.*;
import org.junit.Test;

public class CaseBatteryTest {
    private static int[] report(int level, int status) {
        return new int[]{72, 81, level, 2, 1, status};
    }

    @Test public void asleepCaseWithRealLevelIsVisibleWithoutCharging() {
        CaseBattery state = new CaseBattery();
        int[] raw = report(63, 4);
        assertArrayEquals(report(63, 2), state.update("A", false, raw, 100));
        assertArrayEquals(report(63, 4), raw);
    }

    @Test public void unknownReadingsNeverInventALevel() {
        for (int unknown : new int[]{-1, 101, 128, 255}) {
            assertArrayEquals(report(-1, 4), new CaseBattery().update("A", false, report(unknown, 1), 100));
        }
    }

    @Test public void lidCloseKeepsLastLevelButClearsCharging() {
        CaseBattery state = new CaseBattery();
        assertArrayEquals(report(63, 1), state.update("A", false, report(63, 1), 100));
        assertArrayEquals(report(63, 2), state.update("A", false, report(255, 4), 200));
        assertArrayEquals(report(63, 2), state.read("A", report(63, 1), 300));
    }

    @Test public void unknownUpdatesAndReadsDoNotExtendRetention() {
        CaseBattery state = new CaseBattery();
        state.update("A", false, report(63, 1), 100);
        state.update("A", false, report(-1, 4), 101);
        state.read("A", report(63, 2), CaseBattery.RETENTION_MS);
        assertEquals(63, state.update("A", false, report(-1, 4), CaseBattery.RETENTION_MS + 99)[2]);
        assertArrayEquals(report(-1, 4), state.read("A", report(63, 2), CaseBattery.RETENTION_MS + 100));
    }

    @Test public void freshZeroReplacesPreviousLevelAndSurvivesLidClose() {
        CaseBattery state = new CaseBattery();
        state.update("A", false, report(63, 2), 100);
        assertArrayEquals(report(0, 2), state.update("A", false, report(0, 2), 200));
        assertArrayEquals(report(0, 2), state.update("A", false, report(-1, 4), 300));
    }

    @Test public void newReportReplacesCacheAndPreservesOptimizedChargingStatus() {
        CaseBattery state = new CaseBattery();
        state.update("A", false, report(63, 2), 100);
        assertArrayEquals(report(80, 5), state.update("A", false, report(80, 5), 200));
        assertArrayEquals(report(80, 2), state.update("A", false, report(-1, 4), 300));
    }

    @Test public void differentDevicesNeverShareLevels() {
        CaseBattery state = new CaseBattery();
        state.update("A", false, report(63, 2), 100);
        assertArrayEquals(report(-1, 4), state.update("B", false, report(-1, 4), 200));
        assertArrayEquals(report(63, 2), state.update("A", false, report(-1, 4), 300));
    }

    @Test public void reconnectDoesNotReviveChargingOrResetCacheAge() {
        CaseBattery state = new CaseBattery();
        state.update("A", false, report(63, 1), 100);
        state.disconnected("A");
        assertArrayEquals(report(63, 2), state.read("A", report(-1, -1), 200));
        assertArrayEquals(report(-1, 4), state.read("A", report(-1, -1), CaseBattery.RETENTION_MS + 100));
    }

    @Test public void airpodsMaxHasNoCaseAndClearsAnyOldCache() {
        CaseBattery state = new CaseBattery();
        state.update("A", false, report(63, 2), 100);
        int[] max = {75, -1, -1, 1, -1, -1};
        assertSame(max, state.update("A", true, max, 200));
        assertSame(max, state.read("A", max, 300));
    }

    @Test public void malformedAndUntrackedReadsPassThrough() {
        CaseBattery state = new CaseBattery();
        assertNull(state.update("A", false, null, 100));
        int[] shortReport = {72};
        assertSame(shortReport, state.update("A", false, shortReport, 100));
        int[] original = report(63, 1);
        assertSame(original, state.read("A", original, 100));
    }

    @Test public void oemFrozenStateMustAcceptChangedCaseAndBudReports() {
        int[] previous = {80, 80, -1, 1, 1, 4};
        assertTrue(CaseBattery.blocksUpdate(previous, new int[]{80, 80, 63, 1, 1, 2}));
        assertTrue(CaseBattery.blocksUpdate(previous, new int[]{79, 80, -1, 2, 1, 4}));
        assertFalse(CaseBattery.blocksUpdate(previous, previous.clone()));
        assertFalse(CaseBattery.blocksUpdate(report(63, 2), report(64, 2)));
    }
}
