// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class HandoffDeliveryTest {
    @Test public void occupiedCardOrInitializationFailureLeavesNoticeAvailable(){
        HandoffNotice notice=new HandoffNotice();notice.selectedLocal("phone",100);
        var event=notice.observe("phone","mac",2,200);
        HandoffDelivery delivery=new HandoffDelivery();
        long first=delivery.begin(event.id(),15200,210);
        assertTrue(delivery.finish(event.id(),first,false,220));
        assertNotNull(notice.pending(event.id(),230));
        long retry=delivery.begin(event.id(),15200,1400);assertTrue(retry>first);
        assertTrue(delivery.finish(event.id(),retry,true,1500));
        assertTrue(delivery.needsAcknowledgement(event.id(),1500));
        notice.acknowledge(event.id(),1500);delivery.acknowledged(event.id());
        assertNull(notice.pending(event.id(),1600));
    }
    @Test public void duplicateBroadcastsDoNotStartTwoCardsWhileAwaitingDecision(){
        HandoffDelivery delivery=new HandoffDelivery();long token=delivery.begin(10,15000,100);
        assertTrue(token>0);assertEquals(0,delivery.begin(10,15000,200));
        assertTrue(delivery.finish(10,token,true,250));assertEquals(0,delivery.begin(10,15000,300));
    }
    @Test public void lostAcknowledgementRetriesWithoutDisplayingAgain(){
        HandoffDelivery delivery=new HandoffDelivery();long token=delivery.begin(10,15000,100);
        delivery.finish(10,token,true,200);
        assertTrue(delivery.needsAcknowledgement(10,1300));assertEquals(0,delivery.begin(10,15000,1300));
        delivery.acknowledged(10);assertFalse(delivery.needsAcknowledgement(10,1400));
    }
    @Test public void missingCallbackAllowsRetryAndLateCallbackCannotCompleteIt(){
        HandoffDelivery delivery=new HandoffDelivery();long first=delivery.begin(10,15000,100);
        long retry=delivery.begin(10,15000,100+HandoffDelivery.CALLBACK_TIMEOUT_MS);
        assertTrue(retry>first);assertFalse(delivery.finish(10,first,true,3200));
        assertFalse(delivery.needsAcknowledgement(10,3200));assertTrue(delivery.finish(10,retry,true,3300));
    }
    @Test public void returningToPhoneInvalidatesRemoteCallbackAndQueuedBroadcast(){
        HandoffDelivery delivery=new HandoffDelivery();long remote=delivery.begin(10,15000,100);
        long local=delivery.begin(11,15500,500);
        assertFalse(delivery.finish(10,remote,true,600));assertEquals(0,delivery.begin(10,15000,700));
        assertTrue(delivery.finish(11,local,true,800));
        delivery.acknowledged(10);assertTrue(delivery.needsAcknowledgement(11,900));
    }
    @Test public void expiredEventsCannotStartOrBeAcknowledged(){
        HandoffDelivery delivery=new HandoffDelivery();assertEquals(0,delivery.begin(10,100,101));
        long token=delivery.begin(11,200,150);assertFalse(delivery.finish(11,token,true,201));
        assertFalse(delivery.needsAcknowledgement(11,201));
    }
    @Test public void repeatedDecisionCannotUndoAcceptedPresentation(){
        HandoffDelivery delivery=new HandoffDelivery();long token=delivery.begin(10,15000,100);
        assertTrue(delivery.finish(10,token,true,200));assertFalse(delivery.finish(10,token,false,210));
        assertTrue(delivery.needsAcknowledgement(10,220));
    }
}
