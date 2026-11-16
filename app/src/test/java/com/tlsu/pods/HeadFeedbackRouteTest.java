// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import static org.junit.Assert.*;
import static com.tlsu.pods.HeadFeedbackRoute.AudioState.*;
import static com.tlsu.pods.HeadFeedbackRoute.Kind.*;
import java.util.List;
import org.junit.Test;

public class HeadFeedbackRouteTest {
    private static final String ADDRESS="AA:BB:CC:DD:EE:FF";
    private final HeadFeedbackRoute.Endpoint media=new HeadFeedbackRoute.Endpoint(1,ADDRESS,A2DP);
    private final HeadFeedbackRoute.Endpoint sco=new HeadFeedbackRoute.Endpoint(2,ADDRESS,SCO);
    private final HeadFeedbackRoute.Endpoint ble=new HeadFeedbackRoute.Endpoint(3,ADDRESS,BLE);
    private final HeadFeedbackRoute.Endpoint phone=new HeadFeedbackRoute.Endpoint(4,"",OTHER);
    private final HeadFeedbackRoute.Endpoint other=new HeadFeedbackRoute.Endpoint(5,"11:22:33:44:55:66",SCO);

    @Test public void experienceKeepsItsStereoMediaOutput(){
        assertEquals(media,HeadFeedbackRoute.select(ADDRESS,false,IDLE,sco,List.of(sco,media)));
        assertEquals(new HeadFeedbackRoute.Format(48000,2,false),HeadFeedbackRoute.format(media,false,IDLE));
        assertNull(HeadFeedbackRoute.select(ADDRESS,false,IDLE,sco,List.of(sco)));
    }
    @Test public void ringingMovementUsesCommunicationUsageEvenBeforeScoConnects(){
        assertEquals(media,HeadFeedbackRoute.select(ADDRESS,true,RINGING,phone,List.of(phone,media,sco)));
        assertEquals(new HeadFeedbackRoute.Format(48000,2,true),HeadFeedbackRoute.format(media,true,RINGING));
    }
    @Test public void ringingMovementAndConfirmationFollowExistingSco(){
        assertEquals(sco,HeadFeedbackRoute.select(ADDRESS,true,RINGING,sco,List.of(media,sco)));
        assertEquals(sco,HeadFeedbackRoute.select(ADDRESS,true,VOICE,sco,List.of(media,sco)));
        assertEquals(new HeadFeedbackRoute.Format(16000,1,true),HeadFeedbackRoute.format(sco,true,VOICE));
    }
    @Test public void activeVoiceWaitsForScoInsteadOfUsingSuspendedA2dp(){
        assertNull(HeadFeedbackRoute.select(ADDRESS,true,VOICE,media,List.of(media,sco)));
        assertNull(HeadFeedbackRoute.select(ADDRESS,true,VOICE,null,List.of(media,sco)));
    }
    @Test public void bleCommunicationRouteUsesCallUsage(){
        assertEquals(ble,HeadFeedbackRoute.select(ADDRESS,true,VOICE,ble,List.of(media,ble)));
        assertTrue(HeadFeedbackRoute.format(ble,true,VOICE).communication());
    }
    @Test public void anotherHeadsetOrPhoneCannotBecomeTheCallOutput(){
        assertNull(HeadFeedbackRoute.select(ADDRESS,true,VOICE,other,List.of(media,sco,other)));
        assertNull(HeadFeedbackRoute.select(ADDRESS,true,VOICE,phone,List.of(media,sco,phone)));
        assertNull(HeadFeedbackRoute.select(ADDRESS,true,RINGING,other,List.of(other,phone)));
    }
    @Test public void staleCommunicationEndpointIsNotAUsableOutput(){
        assertNull(HeadFeedbackRoute.select(ADDRESS,true,VOICE,sco,List.of(media)));
    }
    @Test public void rejectConfirmationReturnsToMediaWhenCallAudioEnds(){
        assertEquals(media,HeadFeedbackRoute.select(ADDRESS,true,IDLE,sco,List.of(sco,media)));
        var ringing=HeadFeedbackRoute.format(media,true,RINGING);
        var ended=HeadFeedbackRoute.format(media,true,IDLE);
        assertFalse(ended.communication());assertNotEquals(ringing,ended);
    }
}
