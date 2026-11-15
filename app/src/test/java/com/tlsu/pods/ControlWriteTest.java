package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class ControlWriteTest {
    @Test public void oldMatchingCacheCannotConfirmEvenASameValueWrite(){
        ControlWrite write=new ControlWrite(new byte[]{1},10,1000);
        assertEquals(ControlWrite.WAITING,write.state(new byte[]{1,0,0,0},10,1001));
        assertEquals(ControlWrite.UNCONFIRMED,write.state(new byte[]{1,0,0,0},10,6000));
    }
    @Test public void waitingEndsAtTheDeadlineWithoutAnyNewReport(){
        ControlWrite write=new ControlWrite(new byte[]{1},10,1000);
        assertEquals(ControlWrite.WAITING,write.state(null,0,5999));
        assertEquals(ControlWrite.UNCONFIRMED,write.state(null,0,6000));
        assertEquals(ControlWrite.UNCONFIRMED,write.state(null,0,600_000));
    }
    @Test public void matchingReplyDuringSocketFlushIsStillUsable(){
        // The sequence baseline is captured before write/flush, the object is
        // published after flush, and the reader may have replied in between.
        ControlWrite write=new ControlWrite(new byte[]{2},10,1200);
        assertEquals(ControlWrite.CONFIRMED,write.state(new byte[]{2,0,0,0},11,1200));
    }
    @Test public void aDifferentFreshReportIsNotMistakenForSuccess(){
        ControlWrite write=new ControlWrite(new byte[]{1},10,1000);
        assertEquals(ControlWrite.DIFFERENT,write.state(new byte[]{2,0,0,0},11,1100));
        assertEquals(ControlWrite.DIFFERENT,write.state(new byte[]{2,0,0,0},11,9000));
    }
    @Test public void aLateMatchingReportResolvesAnExpiredWrite(){
        ControlWrite write=new ControlWrite(new byte[]{1},10,1000);
        assertEquals(ControlWrite.UNCONFIRMED,write.state(null,0,9000));
        assertEquals(ControlWrite.CONFIRMED,write.state(new byte[]{1,0,0,0},12,9001));
    }
    @Test public void theLatestReportWinsAfterAnotherDeviceChangesTheSetting(){
        ControlWrite write=new ControlWrite(new byte[]{1},10,1000);
        assertEquals(ControlWrite.CONFIRMED,write.state(new byte[]{1},11,1100));
        assertEquals(ControlWrite.DIFFERENT,write.state(new byte[]{2},12,1200));
    }
    @Test public void anotherWriteNeedsAnotherFreshReport(){
        ControlWrite first=new ControlWrite(new byte[]{1},10,1000);
        assertEquals(ControlWrite.CONFIRMED,first.state(new byte[]{1},11,1100));
        ControlWrite next=new ControlWrite(new byte[]{2},11,1200);
        assertEquals(ControlWrite.WAITING,next.state(new byte[]{1},11,1300));
        assertEquals(ControlWrite.CONFIRMED,next.state(new byte[]{2},12,1400));
    }
    @Test public void hearingAidConfirmationChecksBothBytes(){
        ControlWrite write=new ControlWrite(new byte[]{1,1},10,1000);
        assertEquals(ControlWrite.DIFFERENT,write.state(new byte[]{1,2,0,0},11,1100));
        assertEquals(ControlWrite.CONFIRMED,write.state(new byte[]{1,1,0,0},12,1200));
    }
    @Test public void callConfirmationChecksTheActionByteRatherThanOnlyTheZeroPrefix(){
        ControlWrite write=new ControlWrite(new byte[]{0,3},10,1000);
        assertEquals(ControlWrite.WAITING,write.state(new byte[]{0,3,0,0},10,1100));
        assertEquals(ControlWrite.DIFFERENT,write.state(new byte[]{0,2,0,0},11,1200));
        assertEquals(ControlWrite.CONFIRMED,write.state(new byte[]{0,3,0,0},12,1300));
    }
    @Test public void toneVolumeConfirmationIncludesTheFixedSecondByte(){
        ControlWrite write=new ControlWrite(new byte[]{70,0x50},10,1000);
        assertEquals(ControlWrite.DIFFERENT,write.state(new byte[]{70,0,0,0},11,1200));
        assertEquals(ControlWrite.DIFFERENT,write.state(new byte[]{75,0x50,0,0},12,1300));
        assertEquals(ControlWrite.CONFIRMED,write.state(new byte[]{70,0x50,0,0},13,1400));
    }
    @Test public void theUiExpiresACachedWaitingSnapshotEvenIfBinderStopsResponding(){
        assertEquals(ControlWrite.UNCONFIRMED,ControlWrite.waitingState(ControlWrite.WAITING,6000,6000));
        for(int state:new int[]{ControlWrite.CONFIRMED,ControlWrite.DIFFERENT,ControlWrite.UNCONFIRMED})
            assertEquals(state,ControlWrite.waitingState(state,6000,9000));
    }
    @Test public void expectedBytesCannotBeChangedByTheCaller(){
        byte[] expected={1};ControlWrite write=new ControlWrite(expected,10,1000);
        expected[0]=2;write.expected()[0]=2;
        assertEquals(ControlWrite.CONFIRMED,write.state(new byte[]{1},11,1100));
    }
    @Test public void selectionIsVisibleBeforeANotificationArrives(){
        byte[] reported={0},selected={1};
        assertArrayEquals(selected,ControlWrite.displayedValue(reported,selected,ControlWrite.WAITING));
        assertArrayEquals(new byte[]{0},reported);
    }
    @Test public void timeoutDoesNotRestoreTheOldCachedValue(){
        assertArrayEquals(new byte[]{1},ControlWrite.displayedValue(new byte[]{0},new byte[]{1},ControlWrite.UNCONFIRMED));
        assertArrayEquals(new byte[]{1},ControlWrite.displayedValue(null,new byte[]{1},ControlWrite.UNCONFIRMED));
    }
    @Test public void aFreshEarbudReportOverridesTheDisplayedSelection(){
        assertArrayEquals(new byte[]{2},ControlWrite.displayedValue(new byte[]{2},new byte[]{1},ControlWrite.DIFFERENT));
        assertArrayEquals(new byte[]{1},ControlWrite.displayedValue(new byte[]{1},new byte[]{1},ControlWrite.CONFIRMED));
        assertNull(ControlWrite.displayedValue(null,null,0));
    }
}
