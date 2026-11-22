package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class QuickAudioStateTest {
    @Test public void capabilitiesComeFromReportedListeningModeAndSettings() {
        assertEquals(0, QuickAudioState.listeningModes(-1, -1, -1, -1, false));
        assertEquals(6, QuickAudioState.listeningModes(2, 0, 7, 2, false));
        assertEquals(7, QuickAudioState.listeningModes(3, 0, 7, -1, false));
        assertEquals(14, QuickAudioState.listeningModes(4, 0, 6, -1, false));
        assertEquals(15, QuickAudioState.listeningModes(2, 0, 14, 1, false));
        assertEquals(14, QuickAudioState.listeningModes(2, 0, 6, 2, true));
        assertEquals(14, QuickAudioState.listeningModes(-1, 4 | 8 | 1024, -1, 2, false));
        assertEquals(7, QuickAudioState.listeningModes(-1, 4 | 512 | 1024, -1, -1, false));
    }

    @Test public void modeSelectionRespectsCapabilitiesAndRejectsOutOfRangeValues() {
        assertFalse(QuickAudioState.hasMode(6, 1));
        assertTrue(QuickAudioState.hasMode(6, 2));
        assertTrue(QuickAudioState.hasMode(6, 3));
        assertFalse(QuickAudioState.hasMode(6, 4));
        assertFalse(QuickAudioState.hasMode(15, -1));
        assertFalse(QuickAudioState.hasMode(15, 5));
    }

    @Test public void nativeTilesUseTheColorOsNoiseModeValues() {
        assertEquals(1, QuickAudioState.colorOsNoise(1));
        assertEquals(5, QuickAudioState.colorOsNoise(2));
        assertEquals(2, QuickAudioState.colorOsNoise(3));
        assertEquals(10, QuickAudioState.colorOsNoise(4));
    }
}
