// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class AdaptiveStrengthTest {
    @Test public void rightDragIncreasesStrengthAndReducesAmbientAllowance(){
        int strength=AdaptiveStrength.drag(50,45,100);
        assertEquals(95,strength);
        assertArrayEquals(new byte[]{5},AdaptiveStrength.command(strength));
        assertEquals(strength,AdaptiveStrength.fromReport(new byte[]{5,0,0,0}));
    }
    @Test public void draggingPastEitherEndStopsAtTheLimit(){
        assertEquals(0,AdaptiveStrength.drag(40,-200,100));
        assertEquals(100,AdaptiveStrength.drag(40,200,100));
        assertEquals(40,AdaptiveStrength.drag(40,200,0));
    }
    @Test public void approachingMidpointSnapsFromEitherSideAndDraggingOutReleases(){
        assertEquals(50,AdaptiveStrength.drag(30,15,100));
        assertEquals(50,AdaptiveStrength.drag(70,-15,100));
        assertEquals(50,AdaptiveStrength.drag(44,4,100));
        assertEquals(50,AdaptiveStrength.drag(56,-4,100));
        assertEquals(44,AdaptiveStrength.drag(50,-6,100));
        assertEquals(56,AdaptiveStrength.drag(50,6,100));
        assertArrayEquals(new byte[]{50},AdaptiveStrength.command(AdaptiveStrength.drag(44,4,100)));
        assertEquals(48,AdaptiveStrength.fromReport(new byte[]{52}));
    }
    @Test public void missingOrInvalidReportsDoNotInventAStrength(){
        assertEquals(-1,AdaptiveStrength.fromReport(null));
        assertEquals(-1,AdaptiveStrength.fromReport(new byte[0]));
        assertEquals(-1,AdaptiveStrength.fromReport(new byte[]{(byte)255}));
        assertEquals(-1,AdaptiveStrength.fromReport(new byte[]{101}));
    }
    @Test public void pendingStrengthSurvivesOldCacheAndFreshReportsTakePrecedence(){
        byte[] old=AdaptiveStrength.command(30),selected=AdaptiveStrength.command(80);
        ControlWrite write=new ControlWrite(selected,100,1000);
        int waiting=write.state(old,100,1100);
        assertEquals(80,AdaptiveStrength.fromReport(ControlWrite.displayedValue(old,selected,waiting)));
        byte[] actual=AdaptiveStrength.command(70);
        int fresh=write.state(actual,101,1100);
        assertEquals(ControlWrite.DIFFERENT,fresh);
        assertEquals(70,AdaptiveStrength.fromReport(ControlWrite.displayedValue(actual,selected,fresh)));
    }
}
