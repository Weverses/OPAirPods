// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import static org.junit.Assert.*;
import org.junit.Test;
import java.util.ArrayList;
import java.util.List;

public class HeadGestureDetectorTest {
    private final HeadGestureDetector detector=new HeadGestureDetector();
    private long time;
    private final List<Integer> gestures=new ArrayList<>();
    private void sample(int h,int v){int g=detector.accept(new HeadMotionCodec.Sample(h,v,time));time+=20;if(g!=0)gestures.add(g);}
    private void rest(){for(int i=0;i<25;i++)sample(0,0);}
    private void wave(int h,int v,int frames){for(int i=0;i<frames;i++){double scale=Math.sin(i*2*Math.PI/30);sample((int)(h*scale),(int)(v*scale));}}

    @Test public void nodAndShakeAreDistinguished(){
        rest();wave(80,1100,60);assertEquals(List.of(HeadGestureDetector.NOD),gestures);
        detector.reset();gestures.clear();rest();wave(1100,80,60);assertEquals(List.of(HeadGestureDetector.SHAKE),gestures);
    }
    @Test public void lowAmplitudeNoiseDoesNotProduceAnAction(){rest();wave(180,220,300);assertTrue(gestures.isEmpty());}
    @Test public void stationaryNoiseNeverQualifies(){rest();wave(100,90,500);assertTrue(gestures.isEmpty());}
    @Test public void singleTurnAndReturnNeverQualify(){rest();wave(1200,0,30);rest();assertTrue(gestures.isEmpty());}
    @Test public void briefBalancedMotionDoesNotImmediatelyBecomeShake(){rest();wave(1100,1000,45);assertTrue(gestures.isEmpty());}
    @Test public void sustainedShakeAllowsNearlyEqualPitchCoupling(){
        rest();wave(850,900,180);
        assertTrue("Repeated horizontal reversals must complete despite pitch coupling",gestures.contains(HeadGestureDetector.SHAKE));
        assertFalse(gestures.contains(HeadGestureDetector.NOD));
    }
    @Test public void slowCoupledShakeCanCompleteAtTheRealSensorRate(){
        rest();
        for(int i=0;i<120;i++){
            double scale=Math.sin(i*2*Math.PI/35);
            sample((int)(850*scale),(int)(900*scale));time+=20;
        }
        assertTrue(gestures.contains(HeadGestureDetector.SHAKE));assertFalse(gestures.contains(HeadGestureDetector.NOD));
    }
    @Test public void pitchDominantMixedMotionStillDoesNotBecomeShake(){
        rest();wave(850,1500,180);assertTrue(gestures.isEmpty());
    }
    @Test public void separatedSingleTurnsCannotAccumulateIntoCoupledShake(){
        rest();
        for(int attempt=0;attempt<4;attempt++){wave(850,900,30);rest();rest();rest();}
        assertTrue(gestures.isEmpty());
    }
    @Test public void nodWithSecondaryYawRemainsANod(){
        rest();wave(400,1100,180);
        assertTrue(gestures.contains(HeadGestureDetector.NOD));assertFalse(gestures.contains(HeadGestureDetector.SHAKE));
    }
    @Test public void staleExtremesCannotAccumulateAcrossLongPause(){
        rest();wave(0,1100,30);time+=3000;rest();wave(0,1100,30);assertTrue(gestures.isEmpty());
    }
    @Test public void disconnectResetDiscardsPartialGesture(){
        rest();wave(0,1100,30);detector.reset();rest();wave(0,1100,30);assertTrue(gestures.isEmpty());
    }
    @Test public void cooldownPreventsRepeatedResultsFromOneMovement(){rest();wave(0,1100,90);assertEquals(1,gestures.size());}
    @Test public void oldTimestampsCannotCompleteAWindow(){
        rest();wave(0,1100,30);long previous=time;time=100;wave(0,1100,30);assertTrue(gestures.isEmpty());time=previous;
    }
    @Test public void baselineIsCalibratedAway(){
        for(int i=0;i<100;i++)sample(1200,-900);assertTrue(gestures.isEmpty());
        for(int i=0;i<60;i++){double scale=Math.sin(i*2*Math.PI/30);sample(1200,-900+(int)(1100*scale));}
        assertEquals(List.of(HeadGestureDetector.NOD),gestures);
    }
    @Test public void steadyPitchOffsetCannotBlockShake(){
        rest();
        for(int i=0;i<50;i++)sample(0,900);
        for(int i=0;i<90;i++)sample((int)(1100*Math.sin(i*2*Math.PI/30)),900);
        assertTrue("A static pitch offset must not mask horizontal motion",gestures.contains(HeadGestureDetector.SHAKE));
        assertFalse(gestures.contains(HeadGestureDetector.NOD));
    }
    @Test public void shakeDoesNotNeedToCrossInitialNeutral(){
        rest();
        for(int i=0;i<50;i++)sample(1300,0);
        for(int i=0;i<90;i++)sample(1300+(int)(1100*Math.sin(i*2*Math.PI/30)),0);
        assertTrue("Shake can stay entirely on one side of initial baseline",gestures.contains(HeadGestureDetector.SHAKE));
    }
    @Test public void nodThenShakeWorksAfterChangingRestingPosture(){
        rest();wave(0,1100,60);assertTrue(gestures.contains(HeadGestureDetector.NOD));
        gestures.clear();for(int i=0;i<100;i++)sample(1000,800);
        for(int i=0;i<90;i++)sample(1000+(int)(1100*Math.sin(i*2*Math.PI/30)),800);
        assertTrue("Previous nod must not lock out later shakes",gestures.contains(HeadGestureDetector.SHAKE));
    }
    @Test public void gentleShakeDoesNotRequireLargeExcursions(){
        rest();wave(300,30,90);assertTrue(gestures.contains(HeadGestureDetector.SHAKE));
        assertFalse(gestures.contains(HeadGestureDetector.NOD));
    }
    @Test public void shakeAllowsSecondaryPitchMotion(){
        rest();wave(1100,650,90);assertTrue(gestures.contains(HeadGestureDetector.SHAKE));
        assertFalse(gestures.contains(HeadGestureDetector.NOD));
    }
    @Test public void gentleShakeAllowsSecondaryPitchMotion(){
        rest();wave(300,180,90);assertTrue(gestures.contains(HeadGestureDetector.SHAKE));
        assertFalse(gestures.contains(HeadGestureDetector.NOD));
    }
    @Test public void horizontalTremorBelowNewAmplitudeFloorIsRejected(){rest();wave(180,0,300);assertTrue(gestures.isEmpty());}
    @Test public void gentleSingleTurnAndReturnStillDoesNotQualify(){rest();wave(300,30,30);rest();assertTrue(gestures.isEmpty());}
    @Test public void realEnvelopeNodRemainsVerticalAcrossCounterGrowth(){
        encodedWave(70,1100,16350);
        assertEquals(List.of(HeadGestureDetector.NOD),gestures);
    }
    @Test public void realEnvelopeGentleShakeRemainsHorizontalAcrossCounterGrowth(){
        encodedWave(300,30,100);
        assertEquals(List.of(HeadGestureDetector.SHAKE),gestures);
    }
    private void encodedWave(int h,int v,long sequence){
        encodedWave(h,v,sequence,false);
    }
    @Test public void nestedEnvelopeNodKeepsItsAxisAcrossCounterGrowth(){
        encodedWave(70,1100,16350,true);
        assertEquals(List.of(HeadGestureDetector.NOD),gestures);
    }
    @Test public void nestedEnvelopeGentleShakeKeepsItsAxisAcrossCounterGrowth(){
        encodedWave(300,30,100,true);
        assertEquals(List.of(HeadGestureDetector.SHAKE),gestures);
    }
    private void encodedWave(int h,int v,long sequence,boolean nested){
        for(int i=0;i<115;i++){
            double scale=i<25?0:Math.sin((i-25)*2*Math.PI/30);
            byte[] packet=HeadMotionCodecTest.motion((int)(h*scale),(int)(v*scale),sequence+i,nested);
            int clockOffset=packet.length-26;
            int clock=2400+i;
            packet[clockOffset]=(byte)clock;packet[clockOffset+1]=(byte)(clock>>8);
            var value=HeadMotionCodec.sample(packet,packet.length,time);
            assertNotNull(value);
            int g=detector.accept(value);time+=20;if(g!=0)gestures.add(g);
        }
    }
}
