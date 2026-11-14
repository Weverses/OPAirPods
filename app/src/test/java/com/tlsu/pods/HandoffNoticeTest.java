// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class HandoffNoticeTest {
    private static final String SELF="phone",MAC="mac",IPHONE="iphone";
    @Test public void anInitialRemoteSourceDoesNotPretendASwitchOccurred(){
        assertNull(new HandoffNotice().observe(SELF,MAC,2,100));
    }
    @Test public void anIdleLocalPhoneCanHandOffToAnActiveMac(){
        HandoffNotice state=new HandoffNotice();assertNull(state.observe(SELF,SELF,0,100));
        HandoffNotice.Event event=state.observe(SELF,MAC,2,200);
        assertTrue(event.remote());assertEquals(MAC,event.source());
    }
    @Test public void repeatedReportsAndIdleValidationDoNotSpam(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,2,100);state.observe(SELF,MAC,2,200);
        assertNull(state.observe(SELF,SELF,0,300));assertNull(state.observe(SELF,MAC,2,400));
    }
    @Test public void aNoticeIsConsumedOnlyOnce(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,2,100);var event=state.observe(SELF,MAC,2,200);
        assertNotNull(state.take(event.id(),210));assertNull(state.take(event.id(),220));
    }
    @Test public void aNewDestinationInvalidatesTheOldNotice(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,2,100);var old=state.observe(SELF,MAC,2,200);
        var latest=state.observe(SELF,IPHONE,1,300);
        assertNull(state.take(old.id(),350));assertEquals(IPHONE,state.take(latest.id(),350).source());
    }
    @Test public void returningToThePhoneCancelsAPendingRemoteNotice(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,2,100);var old=state.observe(SELF,MAC,2,200);
        var current=state.observe(SELF,SELF,2,300);assertFalse(current.remote());assertNull(state.current(old.id(),350));
    }
    @Test public void anExpiredNoticeCannotBeDisplayedOrUsedForUndo(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,2,100);var event=state.observe(SELF,MAC,2,200);
        assertNull(state.take(event.id(),200+HandoffNotice.MAX_AGE_MS+1));
    }
    @Test public void confirmedIdleReturnAllowsAnotherHandoffToTheSameMac(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,0,100);state.observe(SELF,MAC,2,200);
        assertFalse(state.selectedLocal(SELF,300).remote());assertTrue(state.observe(SELF,MAC,2,400).remote());
    }
    @Test public void unknownPeerKindsAreNotInvented(){
        assertEquals("已移至 另一台设备",HandoffNotice.title(null));
        assertEquals("已移至 iPhone",HandoffNotice.title("iPhone"));assertEquals("已移至 Mac",HandoffNotice.title("Mac"));
    }
}
