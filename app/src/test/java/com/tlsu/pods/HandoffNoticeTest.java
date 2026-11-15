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
    @Test public void readingDoesNotConsumeAndOnlyAcknowledgementStopsDelivery(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,2,100);var event=state.observe(SELF,MAC,2,200);
        assertNotNull(state.pending(event.id(),210));assertNotNull(state.pending(event.id(),220));
        assertTrue(state.acknowledge(event.id(),230));assertNull(state.pending(event.id(),240));
        assertTrue(state.acknowledge(event.id(),250));assertNotNull(state.current(event.id(),250));
    }
    @Test public void aNewDestinationInvalidatesTheOldNotice(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,2,100);var old=state.observe(SELF,MAC,2,200);
        var latest=state.observe(SELF,IPHONE,1,300);
        assertNull(state.pending(old.id(),350));assertEquals(IPHONE,state.pending(latest.id(),350).source());
        assertFalse(state.acknowledge(old.id(),350));assertNotNull(state.pending(latest.id(),350));
    }
    @Test public void returningToThePhoneCancelsAPendingRemoteNotice(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,2,100);var old=state.observe(SELF,MAC,2,200);
        var current=state.observe(SELF,SELF,2,300);assertFalse(current.remote());assertNull(state.current(old.id(),350));
    }
    @Test public void anExpiredNoticeCannotBeDisplayedOrUsedForUndo(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,2,100);var event=state.observe(SELF,MAC,2,200);
        assertNull(state.pending(event.id(),200+HandoffNotice.MAX_AGE_MS+1));
        assertFalse(state.acknowledge(event.id(),200+HandoffNotice.MAX_AGE_MS+1));
    }
    @Test public void confirmedIdleReturnAllowsAnotherHandoffToTheSameMac(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,SELF,0,100);state.observe(SELF,MAC,2,200);
        assertFalse(state.selectedLocal(SELF,300).remote());assertTrue(state.observe(SELF,MAC,2,400).remote());
    }
    @Test public void unknownPeerKindsAreNotInvented(){
        assertEquals("已移至 另一台设备",HandoffNotice.title(null));
        assertEquals("已移至 iPhone",HandoffNotice.title("iPhone"));assertEquals("已移至 Mac",HandoffNotice.title("Mac"));
    }
    @Test public void confirmedLocalRouteEstablishesBaselineWithoutAnInitialSourcePacket(){
        HandoffNotice state=new HandoffNotice();assertNull(state.selectedLocal(SELF,100));
        assertTrue(state.observe(SELF,MAC,2,200).remote());
    }
    @Test public void remoteIntentAloneDoesNotAnnounceSuccess(){
        HandoffNotice state=new HandoffNotice();state.selectedLocal(SELF,100);
        assertNull(state.requestRemote(SELF,MAC,200));
        assertNull(state.observePlayback(SELF,MAC,true,210));
        assertNull(state.observeOwner(SELF,null,220));
    }
    @Test public void freshOwnershipAndPlaybackConfirmHandoffWithoutSourcePacket(){
        HandoffNotice state=new HandoffNotice();state.selectedLocal(SELF,100);
        state.requestRemote(SELF,MAC,200);assertNull(state.observeOwner(SELF,MAC,210));
        var event=state.observePlayback(SELF,MAC,true,220);
        assertTrue(event.remote());assertEquals(MAC,event.source());
        assertNull(state.observe(SELF,MAC,2,230));
    }
    @Test public void evidenceCanArriveJustBeforeOwnershipLoss(){
        HandoffNotice state=new HandoffNotice();state.selectedLocal(SELF,100);
        state.observeOwner(SELF,MAC,210);state.observePlayback(SELF,MAC,true,220);
        assertEquals(MAC,state.requestRemote(SELF,null,230).source());
    }
    @Test public void staleOrWrongPeerEvidenceCannotConfirmARequest(){
        HandoffNotice state=new HandoffNotice();state.selectedLocal(SELF,100);
        state.observeOwner(SELF,MAC,200);state.observePlayback(SELF,MAC,true,210);
        assertNull(state.requestRemote(SELF,MAC,1000));
        assertNull(state.observeOwner(SELF,IPHONE,1010));
        assertNull(state.observePlayback(SELF,IPHONE,true,1020));
        assertNull(state.observeOwner(SELF,MAC,1030));
        assertNull(state.observePlayback(SELF,MAC,true,5000));
    }
    @Test public void stoppedPlaybackOrLocalOwnershipCannotConfirmRemoteSuccess(){
        HandoffNotice state=new HandoffNotice();state.selectedLocal(SELF,100);
        state.requestRemote(SELF,MAC,200);state.observeOwner(SELF,MAC,210);
        assertNull(state.observePlayback(SELF,MAC,false,220));
        state.observeOwner(SELF,SELF,230);assertNull(state.observePlayback(SELF,MAC,true,240));
    }
    @Test public void confirmedReturnClearsEarlierRemoteEvidence(){
        HandoffNotice state=new HandoffNotice();state.observe(SELF,MAC,2,100);
        state.requestRemote(SELF,MAC,200);state.observeOwner(SELF,MAC,210);
        assertFalse(state.selectedLocal(SELF,220).remote());
        assertNull(state.observePlayback(SELF,MAC,true,230));
    }
    @Test public void resetInvalidatesPendingAndActionButPreservesEventIdentityOrder(){
        HandoffNotice state=new HandoffNotice();state.selectedLocal(SELF,100);var old=state.observe(SELF,MAC,2,200);
        state.reset();assertNull(state.pending(old.id(),210));assertNull(state.current(old.id(),210));
        state.selectedLocal(SELF,220);var next=state.observe(SELF,MAC,2,230);assertTrue(next.id()>old.id());
    }
    @Test public void disconnectCancelsDeliveryAndDropsPartialEvidence(){
        HandoffNotice state=new HandoffNotice();state.selectedLocal(SELF,100);var old=state.observe(SELF,MAC,2,200);
        state.requestRemote(SELF,IPHONE,210);state.observeOwner(SELF,IPHONE,220);
        state.disconnected();assertNull(state.pending(old.id(),230));assertFalse(state.acknowledge(old.id(),230));
        assertNull(state.observePlayback(SELF,IPHONE,true,240));
        assertFalse(state.selectedLocal(SELF,250).remote());
    }
}
