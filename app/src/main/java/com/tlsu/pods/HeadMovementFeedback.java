// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Early movement feedback, independent of complete-gesture recognition.
 * Direction polarity follows the known LibrePods raw motion convention. */
final class HeadMovementFeedback {
    enum Direction { NONE, LEFT, RIGHT, UP, DOWN }
    private long first=-1, previous=-1, lastSound=-1000, quietSince=-1;
    private int calibration;
    private double baseH,baseV,h,v;
    private Direction last=Direction.NONE;

    void reset(){first=previous=quietSince=-1;lastSound=-1000;calibration=0;baseH=baseV=h=v=0;last=Direction.NONE;}
    Direction accept(HeadMotionCodec.Sample sample){
        long now=sample.at();
        if(previous>=0&&now<=previous)return Direction.NONE;
        if(previous>=0&&now-previous>250)reset();
        previous=now;if(first<0)first=now;
        if(calibration<10||now-first<250){
            baseH+=(sample.horizontal()-baseH)/++calibration;
            baseV+=(sample.vertical()-baseV)/calibration;return Direction.NONE;
        }
        h+=0.55*(sample.horizontal()-baseH-h);v+=0.55*(sample.vertical()-baseV-v);
        if(Math.abs(h)<90&&Math.abs(v)<130){
            if(quietSince<0)quietSince=now;
            if(now-quietSince>=160)last=Direction.NONE;
            return Direction.NONE;
        }
        quietSince=-1;
        Direction direction=Direction.NONE;
        if(Math.abs(h)>=180&&Math.abs(h)>Math.abs(v)*1.2)direction=h>0?Direction.RIGHT:Direction.LEFT;
        else if(Math.abs(v)>=260&&Math.abs(v)>Math.abs(h)*1.2)direction=v>0?Direction.UP:Direction.DOWN;
        if(direction==Direction.NONE||direction==last||now-lastSound<180)return Direction.NONE;
        last=direction;lastSound=now;return direction;
    }
}
