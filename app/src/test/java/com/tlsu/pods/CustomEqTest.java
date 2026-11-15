// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class CustomEqTest {
    @Test public void matchesTheUpstreamIndependentOpcodeAndLength(){
        CustomEq eq=new CustomEq(2,65,50,70);
        byte[] frame={4,0,4,0,0x63,0,5,0,1,2,65,50,70};
        assertArrayEquals(frame,eq.packet());assertEquals(eq,CustomEq.parse(frame,13));
        assertFalse(Protocol.isControl(frame,13));
    }
    @Test public void endpointsAndDisabledStateRoundTrip(){
        for(int state:new int[]{1,2}){
            CustomEq eq=new CustomEq(state,0,100,50);
            assertEquals(eq,CustomEq.parse(eq.packet(),13));assertEquals(state==2,eq.enabled());
            assertEquals(eq,CustomEq.fromValue(eq.value()));
        }
    }
    @Test public void aBandPatchPreservesEveryOtherBandAndTheEnableFlag(){
        CustomEq original=new CustomEq(2,65,45,70);
        assertEquals(new CustomEq(2,80,45,70),original.patch("low",80));
        assertEquals(new CustomEq(2,65,0,70),original.patch("mid",0));
        assertEquals(new CustomEq(2,65,45,100),original.patch("high",100));
        assertEquals(new CustomEq(1,65,45,70),original.patch("enabled",0));
        assertEquals(new CustomEq(2,65,45,70),original);
    }
    @Test public void resettingTheBandsDoesNotChangeTheEnableFlag(){
        assertEquals(new CustomEq(2,50,50,50),new CustomEq(2,0,100,70).patch("reset",0));
        assertEquals(CustomEq.flat(),new CustomEq(1,0,100,70).patch("reset",0));
    }
    @Test public void malformedFramesNeverBecomeAnInventedFlatReport(){
        byte[] packet=new CustomEq(2,60,40,80).packet();
        assertThrows(IllegalArgumentException.class,()->CustomEq.parse(null,13));
        for(int length=0;length<13;length++){
            int n=length;assertThrows(IllegalArgumentException.class,()->CustomEq.parse(packet,n));
        }
        assertThrows(IllegalArgumentException.class,()->CustomEq.parse(packet,14));
        for(int index:new int[]{0,1,2,3,4,5,6,7,8,9,10,11,12}){
            byte[] bad=packet.clone();bad[index]=(byte)255;
            assertThrows(IllegalArgumentException.class,()->CustomEq.parse(bad,bad.length));
        }
    }
    @Test public void rejectsOutOfRangeOrUnknownPatches(){
        for(int value:new int[]{-1,101,255})
            assertThrows(IllegalArgumentException.class,()->CustomEq.flat().patch("low",value));
        assertThrows(IllegalArgumentException.class,()->CustomEq.flat().patch("enabled",2));
        assertThrows(IllegalArgumentException.class,()->CustomEq.flat().patch("reset",50));
        assertThrows(IllegalArgumentException.class,()->CustomEq.flat().patch("unknown",50));
        assertThrows(IllegalArgumentException.class,()->new CustomEq(0,50,50,50));
        assertThrows(IllegalArgumentException.class,()->CustomEq.fromValue(new byte[]{2,50,50}));
    }
    @Test public void confirmationRequiresAFreshReportWithAllFourValues(){
        CustomEq eq=new CustomEq(2,65,45,70);ControlWrite write=new ControlWrite(eq.value(),10,1000);
        assertEquals(ControlWrite.WAITING,write.state(eq.value(),10,1100));
        assertEquals(ControlWrite.DIFFERENT,write.state(new byte[]{2,65,45,75},11,1200));
        assertEquals(ControlWrite.CONFIRMED,write.state(eq.value(),12,1300));
        assertArrayEquals(eq.value(),ControlWrite.displayedValue(CustomEq.flat().value(),eq.value(),ControlWrite.UNCONFIRMED));
    }
}
