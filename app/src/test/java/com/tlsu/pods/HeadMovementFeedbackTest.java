// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class HeadMovementFeedbackTest {
    private final HeadMovementFeedback detector=new HeadMovementFeedback();
    private final List<HeadMovementFeedback.Direction> events=new ArrayList<>();
    private long at;
    private void samples(int h,int v,int n){for(int i=0;i<n;i++){
        var event=detector.accept(new HeadMotionCodec.Sample(h,v,at));at+=20;
        if(event!=HeadMovementFeedback.Direction.NONE)events.add(event);
    }}
    private void rest(){samples(0,0,25);}
    @Test public void leftThenRightProduceIndependentEarlyCues(){
        rest();samples(-300,0,15);samples(300,0,15);
        assertEquals(List.of(HeadMovementFeedback.Direction.LEFT,HeadMovementFeedback.Direction.RIGHT),events);
    }
    @Test public void upAndDownAreDistinct(){
        rest();samples(0,500,15);samples(0,-500,15);
        assertEquals(List.of(HeadMovementFeedback.Direction.UP,HeadMovementFeedback.Direction.DOWN),events);
    }
    @Test public void sustainedMotionDoesNotBeepForEverySample(){
        rest();samples(350,0,120);assertEquals(List.of(HeadMovementFeedback.Direction.RIGHT),events);
    }
    @Test public void sameDirectionCanSoundAgainAfterRest(){
        rest();samples(350,0,15);rest();samples(350,0,15);
        assertEquals(List.of(HeadMovementFeedback.Direction.RIGHT,HeadMovementFeedback.Direction.RIGHT),events);
    }
    @Test public void jitterAndStartupDoNotBeep(){samples(1000,0,10);detector.reset();at=0;rest();samples(100,90,100);assertTrue(events.isEmpty());}
    @Test public void simultaneousAxesDoNotEmitConflictingCues(){rest();samples(500,500,50);assertTrue(events.isEmpty());}
    @Test public void feedbackCanPrecedeACompleteGesture(){
        HeadGestureDetector gestures=new HeadGestureDetector();int complete=0;
        for(int i=0;i<50;i++){
            var sample=new HeadMotionCodec.Sample(i<25?0:250,0,at);at+=20;
            var event=detector.accept(sample);if(event!=HeadMovementFeedback.Direction.NONE)events.add(event);
            complete|=gestures.accept(sample);
        }
        assertEquals(List.of(HeadMovementFeedback.Direction.RIGHT),events);assertEquals(HeadGestureDetector.NONE,complete);
    }
    @Test public void dataGapRecalibratesBeforeSound(){rest();at+=1000;samples(1000,0,20);assertTrue(events.isEmpty());}
}
