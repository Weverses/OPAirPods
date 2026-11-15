// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;
import org.junit.Test;
import static org.junit.Assert.*;

public class AudioRebuildWindowTest {
    @Test public void transportRestartDoesNotCancelAGameForAShortTrackGap(){
        AudioRebuildWindow w=new AudioRebuildWindow();assertTrue(w.alive(true,true,100));
        assertTrue(w.alive(true,false,1000));assertTrue(w.alive(true,false,2499));
        assertTrue(w.alive(true,true,2500));
    }
    @Test public void sustainedSilenceCannotKeepARouteAliveForever(){
        AudioRebuildWindow w=new AudioRebuildWindow();assertTrue(w.alive(true,false,1000));
        assertFalse(w.alive(true,false,2500));
    }
    @Test public void foregroundChangeOrLockCancelsImmediatelyEvenDuringARebuild(){
        AudioRebuildWindow w=new AudioRebuildWindow();w.alive(true,false,1000);
        assertFalse(w.alive(false,false,1100));assertFalse(w.alive(false,true,1100));
    }
    @Test public void subsequentRebuildGetsItsOwnBoundedWindow(){
        AudioRebuildWindow w=new AudioRebuildWindow();w.alive(true,false,1000);w.alive(true,true,1100);
        assertTrue(w.alive(true,false,5000));assertFalse(w.alive(true,false,6500));
    }
    @Test public void cancellingALoanClearsItsPreviousGap(){
        AudioRebuildWindow w=new AudioRebuildWindow();w.alive(true,false,1000);w.reset();
        assertTrue(w.alive(true,false,10000));assertFalse(w.alive(true,false,11500));
    }
}
