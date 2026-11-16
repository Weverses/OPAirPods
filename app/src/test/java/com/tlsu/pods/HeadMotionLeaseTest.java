// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import static org.junit.Assert.*;
import org.junit.Test;

public class HeadMotionLeaseTest {
    @Test public void consumedActionCanWaitForConfirmationUntilCancelled(){
        Object call=new Object(),connection=new Object();var lease=new HeadMotionLease("headset",connection,call,"call",1000);
        assertTrue(lease.consume(connection,call,100));assertFalse(lease.valid(connection,call,200));
        assertTrue(lease.current(connection,call,200));lease.cancel();assertFalse(lease.current(connection,call,300));
    }
    @Test public void lateGestureCannotOperateReplacementCallOrConnection(){
        Object call=new Object(),connection=new Object();
        var lease=new HeadMotionLease("headset",connection,call,"call",1000);
        assertFalse(lease.consume(new Object(),call,100));
        assertFalse(lease.consume(connection,new Object(),100));
        assertTrue(lease.consume(connection,call,100));
        assertFalse(lease.consume(connection,call,101));
    }
    @Test public void answerOnPhoneCancelsPendingReject(){
        Object connection=new Object(),call=new Object();
        var lease=new HeadMotionLease("headset",connection,call,"call",1000);
        lease.cancel();assertFalse(lease.consume(connection,call,500));
    }
    @Test public void timeoutIsExclusive(){
        Object connection=new Object(),call=new Object();
        var lease=new HeadMotionLease("headset",connection,call,"call",1000);
        assertTrue(lease.valid(connection,call,999));assertFalse(lease.consume(connection,call,1000));
    }
    @Test public void swappedMappingIsSymmetric(){
        assertTrue(HeadMotionLease.answers(HeadGestureDetector.NOD,false));
        assertFalse(HeadMotionLease.answers(HeadGestureDetector.SHAKE,false));
        assertFalse(HeadMotionLease.answers(HeadGestureDetector.NOD,true));
        assertTrue(HeadMotionLease.answers(HeadGestureDetector.SHAKE,true));
    }
    @Test(expected=IllegalArgumentException.class) public void unknownGestureNeverMapsToReject(){HeadMotionLease.answers(0,false);}
}
