// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class StemConfigTest {
    @Test public void decodesAllFourPressTypesAndBothSides(){
        for(int side=1;side<=2;side++)for(int type=0;type<4;type++){
            byte[] frame={4,0,4,0,0x19,0,(byte)(5+type),(byte)side};
            assertEquals(new StemConfig.Press(side,type),StemConfig.parse(frame,8));
        }
    }
    @Test public void rejectsUnknownEventsInsteadOfGuessingTheirSideOrAction(){
        byte[] frame={4,0,4,0,0x19,0,5,1};
        assertThrows(IllegalArgumentException.class,()->StemConfig.parse(null,8));
        for(int len=0;len<8;len++){int n=len;assertThrows(IllegalArgumentException.class,()->StemConfig.parse(frame,n));}
        assertThrows(IllegalArgumentException.class,()->StemConfig.parse(frame,9));
        for(int index=0;index<8;index++){
            byte[] bad=frame.clone();bad[index]=(byte)255;
            assertThrows(IllegalArgumentException.class,()->StemConfig.parse(bad,8));
        }
        assertThrows(IllegalArgumentException.class,()->new StemConfig.Press(0,0));
        assertThrows(IllegalArgumentException.class,()->new StemConfig.Press(1,4));
    }
    @Test public void defaultConfigurationLeavesEveryNativePressTypeAlone(){
        StemConfig defaults=StemConfig.defaults();assertEquals(0,defaults.mask());
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x39,0,0,0,0},defaults.packet());
        assertFalse(defaults.handles(new StemConfig.Press(1,0),15));
    }
    @Test public void aCustomActionOnEitherSideSetsTheSharedTypeBit(){
        StemConfig config=StemConfig.defaults().with(1,0,StemConfig.ASSISTANT).with(2,3,StemConfig.NONE);
        assertEquals(9,config.mask());
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x39,9,0,0,0},config.packet());
        assertEquals(8,config.with(1,0,StemConfig.DEFAULT).mask());
    }
    @Test public void theOtherSideStillExecutesItsDefaultWhenTheSharedTypeIsRedirected(){
        StemConfig config=StemConfig.defaults().with(1,0,StemConfig.NEXT);
        var right=new StemConfig.Press(2,0);
        assertTrue(config.handles(right,1));assertEquals(StemConfig.PLAY_PAUSE,config.action(right));
        assertEquals(StemConfig.NEXT,config.action(new StemConfig.Press(1,0)));
    }
    @Test public void explicitDisableStillRedirectsThePressWithoutExecutingAnAction(){
        StemConfig config=StemConfig.defaults().with(2,1,StemConfig.NONE);
        assertEquals(2,config.mask());
        assertEquals(StemConfig.NONE,new StemConfig.Dispatch(config,2).take(new StemConfig.Press(2,1),1000));
    }
    @Test public void dispatchDoesNotHandleNativeOrUnappliedTypes(){
        StemConfig config=StemConfig.defaults().with(1,1,StemConfig.ASSISTANT);
        var dispatch=new StemConfig.Dispatch(config,0);var press=new StemConfig.Press(1,1);
        assertFalse(dispatch.handles(press));assertEquals(StemConfig.DEFAULT,dispatch.take(press,1000));
        dispatch.report(2);assertTrue(dispatch.handles(press));assertEquals(StemConfig.ASSISTANT,dispatch.take(press,1001));
        assertEquals(StemConfig.DEFAULT,dispatch.take(new StemConfig.Press(1,0),1500));
        dispatch.report(0);assertEquals(StemConfig.DEFAULT,dispatch.take(press,2000));
    }
    @Test public void identicalDuplicateEventsDoNotTogglePlaybackTwice(){
        var dispatch=new StemConfig.Dispatch(StemConfig.defaults().with(1,0,StemConfig.PLAY_PAUSE),1);
        var press=new StemConfig.Press(1,0);
        assertEquals(StemConfig.PLAY_PAUSE,dispatch.take(press,1000));
        assertEquals(StemConfig.DEFAULT,dispatch.take(press,1119));
        assertEquals(StemConfig.PLAY_PAUSE,dispatch.take(press,1120));
        assertEquals(StemConfig.DEFAULT,dispatch.take(press,100));
    }
    @Test public void distinctSidesAndTypesAreNotCollapsedAsDuplicates(){
        StemConfig config=StemConfig.defaults().with(1,0,StemConfig.ASSISTANT).with(2,1,StemConfig.PREVIOUS);
        var dispatch=new StemConfig.Dispatch(config,3);
        assertEquals(StemConfig.ASSISTANT,dispatch.take(new StemConfig.Press(1,0),1000));
        assertEquals(StemConfig.PLAY_PAUSE,dispatch.take(new StemConfig.Press(2,0),1001));
        assertEquals(StemConfig.PREVIOUS,dispatch.take(new StemConfig.Press(2,1),1002));
    }
    @Test public void reconnectGetsAnIndependentEventBaseline(){
        StemConfig config=StemConfig.defaults().with(1,0,StemConfig.ASSISTANT);var press=new StemConfig.Press(1,0);
        var old=new StemConfig.Dispatch(config,1);assertEquals(StemConfig.ASSISTANT,old.take(press,1000));
        var next=new StemConfig.Dispatch(config,1);assertEquals(StemConfig.ASSISTANT,next.take(press,1001));
    }
    @Test public void persistedMappingsAndCallerArraysCannotMutateEachOther(){
        int[] values=new int[8];values[0]=StemConfig.ASSISTANT;values[7]=StemConfig.NONE;
        StemConfig config=new StemConfig(values);values[0]=0;config.values()[7]=0;
        assertEquals("40000006",config.encode());assertEquals(config,StemConfig.decode(config.encode()));
        assertEquals(StemConfig.ASSISTANT,config.selected(1,0));assertEquals(StemConfig.NONE,config.selected(2,3));
    }
    @Test public void invalidMappingsCannotProduceAnArbitraryControlCommand(){
        assertThrows(IllegalArgumentException.class,()->new StemConfig(new int[7]));
        assertThrows(IllegalArgumentException.class,()->StemConfig.defaults().with(1,0,7));
        assertThrows(IllegalArgumentException.class,()->StemConfig.defaults().with(3,0,1));
        for(String invalid:new String[]{"","0000000","000000000","00000007","0000000x"})
            assertThrows(IllegalArgumentException.class,()->StemConfig.decode(invalid));
    }
    @Test public void cyclingUsesConfiguredModesAndWrapsBackToTheFirst(){
        assertEquals(3,StemConfig.nextMode(2,6));assertEquals(2,StemConfig.nextMode(3,6));
        assertEquals(2,StemConfig.nextMode(4,6));
        assertEquals(2,StemConfig.nextMode(1,15));assertEquals(3,StemConfig.nextMode(2,15));
        assertEquals(4,StemConfig.nextMode(3,15));assertEquals(1,StemConfig.nextMode(4,15));
    }
    @Test public void unavailableListeningStateDoesNotWriteAnInventedNoiseMode(){
        assertThrows(IllegalArgumentException.class,()->StemConfig.nextMode(0,6));
        assertThrows(IllegalArgumentException.class,()->StemConfig.nextMode(5,6));
        assertThrows(IllegalArgumentException.class,()->StemConfig.nextMode(2,2));
        assertThrows(IllegalArgumentException.class,()->StemConfig.nextMode(2,255));
    }
    @Test public void anIdlePeerSourceIsStillRemoteEvenWithoutAnOwnershipSnapshot(){
        assertFalse(StemConfig.localRoute(false,false,"Mac","phone"));
        assertFalse(StemConfig.localRoute(false,false,"Mac",null));
    }
    @Test public void remoteOwnershipOrPeerCallsPreventPhoneActions(){
        assertFalse(StemConfig.localRoute(true,false,"phone","phone"));
        assertFalse(StemConfig.localRoute(false,true,"phone","phone"));
        assertFalse(StemConfig.localRoute(true,false,null,"phone"));
    }
    @Test public void localSelectionAndUninitializedSourceCanUseTheNativePhoneConnection(){
        assertTrue(StemConfig.localRoute(false,false,"phone","phone"));
        assertTrue(StemConfig.localRoute(false,false,null,null));
        assertTrue(StemConfig.localRoute(false,false,"00:00:00:00:00:00",null));
    }
}
