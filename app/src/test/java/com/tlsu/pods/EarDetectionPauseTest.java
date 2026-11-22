// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class EarDetectionPauseTest {
    @Test public void briefSensorDropDoesNotPauseAfterReinsertion() {
        EarDetectionPause pause = new EarDetectionPause();
        pause.wear(true, true, 0);
        pause.wear(true, false, 100);
        pause.wear(true, true, 200);
        assertFalse(pause.allow(600));
    }

    @Test public void sustainedSingleEarRemovalStillPauses() {
        EarDetectionPause pause = new EarDetectionPause();
        pause.wear(true, true, 0);
        pause.wear(false, true, 100);
        assertTrue(pause.allow(600));
        assertFalse(pause.allow(700));
    }

    @Test public void oldCallbackDoesNotPauseBeforeANewRemovalSettles() {
        EarDetectionPause pause = new EarDetectionPause();
        pause.wear(true, true, 0);
        pause.wear(false, true, 100);
        pause.wear(true, true, 200);
        pause.wear(true, false, 300);
        assertFalse(pause.allow(600));
        assertTrue(pause.allow(800));
    }

    @Test public void disconnectInvalidatesPendingPause() {
        EarDetectionPause pause = new EarDetectionPause();
        pause.wear(true, true, 0);
        pause.wear(false, true, 100);
        pause.disconnected();
        assertFalse(pause.allow(600));
    }
}
