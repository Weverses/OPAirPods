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
    @Test public void aPhoneCallDoesNotConfirmAMusicTakeover(){
        assertFalse(HandoffConfirmation.confirmed(10,11,1,12,true,1,true));
        assertTrue(HandoffConfirmation.confirmed(10,11,1,12,true,2,true));
    }
    @Test public void aContinuousGameCanCompleteOnFreshLocalSelectionWithoutStartingAMusicPlayer(){
        assertTrue(HandoffConfirmation.gameRoute(10,11,1,12,true,0,true,true));
        assertFalse(HandoffConfirmation.confirmed(10,11,1,12,true,0,true));
    }
    @Test public void gameRouteRequiresFreshLocalSelectionAndKnownLocalOwnership(){
        assertFalse(HandoffConfirmation.gameRoute(10,11,1,9,true,0,true,true));
        assertTrue(HandoffConfirmation.gameRoute(10,9,1,12,true,0,true,true));
        assertFalse(HandoffConfirmation.gameRoute(10,11,1,12,false,0,true,true));
        assertFalse(HandoffConfirmation.gameRoute(10,13,0,12,true,0,true,true));
    }
    @Test public void alreadyOwnedGameStillNeedsFreshSelectionAndRealStreaming(){
        assertFalse(HandoffConfirmation.gameRoute(10,9,1,9,true,0,true,true));
        assertFalse(HandoffConfirmation.gameRoute(10,9,1,12,true,0,false,true));
        assertTrue(HandoffConfirmation.gameRoute(10,9,1,12,true,0,true,true));
    }
    @Test public void gameRouteDoesNotCompleteWithoutTheAudioProfileOrOriginalGame(){
        assertFalse(HandoffConfirmation.gameRoute(10,11,1,12,true,0,false,true));
        assertFalse(HandoffConfirmation.gameRoute(10,11,1,12,true,0,true,false));
    }
    @Test public void aCallOrUnknownAudioCategoryCannotConfirmTheGameRoute(){
        assertFalse(HandoffConfirmation.gameRoute(10,11,1,12,true,1,true,true));
        assertFalse(HandoffConfirmation.gameRoute(10,11,1,12,true,3,true,true));
        assertTrue(HandoffConfirmation.gameRoute(10,11,1,12,true,2,true,true));
    }
}
