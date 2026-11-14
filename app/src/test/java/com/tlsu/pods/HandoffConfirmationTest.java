package com.tlsu.pods;
import org.junit.Test;
import static org.junit.Assert.*;
public class HandoffConfirmationTest {
    @Test public void selectedButPausedCanResumeWithoutPretendingMediaIsConfirmed(){
        assertTrue(HandoffConfirmation.selected(10,11,1,12,true));
        assertFalse(HandoffConfirmation.confirmed(10,11,1,12,true,0,true));
        assertTrue(HandoffConfirmation.confirmed(10,11,1,13,true,2,true));
    }
    @Test public void ownerAckWhileTheOtherHostStillPlaysDoesNotPermitResume(){
        assertFalse(HandoffConfirmation.selected(10,12,1,11,false));
        assertFalse(HandoffConfirmation.confirmed(10,12,1,11,false,2,true));
        assertTrue(HandoffConfirmation.confirmed(10,12,1,11,false,2,false));
    }
    @Test public void staleSourceAndNewerOwnershipLossCannotConfirmANewClaim(){
        assertFalse(HandoffConfirmation.selected(10,12,1,9,true));
        assertFalse(HandoffConfirmation.selected(10,14,0,13,true));
        assertFalse(HandoffConfirmation.confirmed(10,14,0,13,true,2,true));
        assertTrue(HandoffConfirmation.selected(10,14,0,15,true));
    }
}
