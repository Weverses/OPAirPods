package com.tlsu.pods;
import org.junit.Test;
import static org.junit.Assert.*;
public class HandoffStateTest {
    @Test public void pausingBeforeAnAutomaticClaimWasSentDoesNotYieldToAnIdlePeer(){
        HandoffState s=new HandoffState();s.source("buds",true,2);
        var claim=s.beginClaim("buds");s.source("buds",false,0);
        assertNull(s.finishClaim(claim,false));assertFalse(s.claiming(claim));assertFalse(s.remoteOwns("buds"));
        assertNull(s.recoveryTicket("buds"));
    }
    @Test public void cancellingAnUnconfirmedInitialClaimCannotInventARemoteOwner(){
        HandoffState s=new HandoffState();var claim=s.beginClaim("buds");
        s.arm(claim);s.source("buds",false,0);
        assertNull(s.finishClaim(claim,false));assertFalse(s.remoteOwns("buds"));
    }
    @Test public void pausingAfterLocalSelectionKeepsTheRouteEvenWhenPlaybackWasNotConfirmed(){
        HandoffState s=new HandoffState();s.remote("buds");var claim=s.beginClaim("buds");
        s.arm(claim);s.source("buds",true,0);s.source("buds",false,0);
        assertNull(s.finishClaim(claim,false));assertFalse(s.remoteOwns("buds"));
        assertNull(s.recoveryTicket("buds"));
    }
    @Test public void localAudioArrivingBeforeTheClaimWriteSupersedesThePreviousRemoteRoute(){
        HandoffState s=new HandoffState();s.remote("buds");var claim=s.beginClaim("buds");
        s.source("buds",true,2);s.source("buds",false,0);
        assertNull(s.finishClaim(claim,false));assertFalse(s.remoteOwns("buds"));
    }
    @Test public void failedTakeoverBeforeLocalSelectionStillRestoresTheActualRemoteOwner(){
        HandoffState s=new HandoffState();s.remote("buds");var claim=s.beginClaim("buds");
        s.source("buds",true,0);s.arm(claim);
        var remote=s.finishClaim(claim,false);
        assertNotNull(remote);assertTrue(s.current(remote));assertTrue(s.remoteOwns("buds"));
    }
    @Test public void remoteAudioDiscoveredDuringPreparationIsRetainedIfTheClaimFails(){
        HandoffState s=new HandoffState();var claim=s.beginClaim("buds");
        assertNull(s.source("buds",false,2));assertTrue(s.claiming(claim));
        var remote=s.finishClaim(claim,false);assertNotNull(remote);assertTrue(s.current(remote));
    }
    @Test public void replacementClaimsRetainTheOriginalRemoteOwnerUntilLocalSelection(){
        HandoffState s=new HandoffState();s.remote("buds");var old=s.beginClaim("buds");var replacement=s.beginClaim("buds");
        assertNull(s.finishClaim(old,false));assertTrue(s.claiming(replacement));
        var remote=s.finishClaim(replacement,false);assertNotNull(remote);assertTrue(s.current(remote));
    }
    @Test public void aReplacementAfterLocalSelectionDoesNotRestoreTheOldRemoteOwner(){
        HandoffState s=new HandoffState();s.remote("buds");var old=s.beginClaim("buds");
        s.arm(old);s.source("buds",true,0);var replacement=s.beginClaim("buds");
        assertNull(s.finishClaim(old,false));assertTrue(s.claiming(replacement));
        assertNull(s.finishClaim(replacement,false));assertFalse(s.remoteOwns("buds"));
    }
    @Test public void disablingAfterLocalSelectionDoesNotRestoreTheOldRemoteOwner(){
        HandoffState s=new HandoffState();s.remote("buds");var claim=s.beginClaim("buds");
        s.arm(claim);s.source("buds",true,0);s.cancel("buds");
        assertFalse(s.remoteOwns("buds"));assertFalse(s.claiming(claim));assertNull(s.finishClaim(claim,false));
    }
    @Test public void losingTheControlChannelDoesNotTurnALocalClaimIntoARemoteHandoff(){
        HandoffState s=new HandoffState();s.source("buds",true,2);var claim=s.beginClaim("buds");
        s.arm(claim);s.disconnected("buds");
        assertFalse(s.claiming(claim));assertFalse(s.remoteOwns("buds"));assertNull(s.finishClaim(claim,false));
    }
    @Test public void explicitRemotePlaybackStillWinsAfterLocalSelectionAndPause(){
        HandoffState s=new HandoffState();s.remote("buds");var claim=s.beginClaim("buds");
        s.arm(claim);s.source("buds",true,0);s.source("buds",false,0);
        var remote=s.source("buds",false,2);
        assertNotNull(remote);assertTrue(s.current(remote));assertNull(s.finishClaim(claim,false));assertTrue(s.remoteOwns("buds"));
    }
    @Test public void aDelayedRecoveryCannotReviveAfterALocalAndRemoteRoundTrip(){
        HandoffState s=new HandoffState();assertNull(s.recoveryTicket("buds"));
        s.remote("buds");var old=s.recoveryTicket("buds");assertTrue(s.current(old));
        s.local("buds");assertNull(s.recoveryTicket("buds"));s.remote("buds");
        assertFalse(s.current(old));assertTrue(s.current(s.recoveryTicket("buds")));
    }
    @Test public void aDelayedRecoveryDoesNotBelongToTheNextConnection(){
        HandoffState s=new HandoffState();s.remote("buds");var old=s.recoveryTicket("buds");
        s.disconnected("buds");
        assertFalse(s.current(old));assertTrue(s.current(s.recoveryTicket("buds")));
    }
    @Test public void handlesOwnershipLossOnceWithoutTreatingInitialZeroAsAHandoff(){
        HandoffState s=new HandoffState();assertNull(s.ownership("earbuds",0));
        s.ownership("earbuds",1);HandoffState.Ticket ticket=s.ownership("earbuds",0);
        assertNotNull(ticket);assertTrue(s.current(ticket));assertNull(s.remote("earbuds"));assertNull(s.ownership("earbuds",0));
    }
    @Test public void localTakeoverCancelsQueuedRemoteHandoff(){
        HandoffState s=new HandoffState();HandoffState.Ticket ticket=s.remote("earbuds");s.local("earbuds");
        assertFalse(s.current(ticket));assertFalse(s.remoteOwns("earbuds"));
        assertNotNull(s.remote("earbuds"));
    }
    @Test public void staleRemoteEventsCannotCancelAnExplicitClaim(){
        HandoffState s=new HandoffState();s.remote("earbuds");var claim=s.beginClaim("earbuds");
        assertNull(s.remote("earbuds"));assertNull(s.ownership("earbuds",0));assertNull(s.ownership("earbuds",1));assertTrue(s.claiming(claim));
        s.finishClaim("earbuds",true);assertFalse(s.remoteOwns("earbuds"));assertFalse(s.claiming(claim));
        assertNotNull(s.remote("earbuds"));
    }
    @Test public void anOldValidationTaskCannotFinishANewerManualClaim(){
        HandoffState s=new HandoffState();var validation=s.beginClaim("earbuds");var manual=s.beginClaim("earbuds");
        assertFalse(s.claiming(validation));assertTrue(s.claiming(manual));
    }
    @Test public void finishingAnEndedClaimCannotResetTheNewClaim(){
        HandoffState s=new HandoffState();var ended=s.beginClaim("earbuds");s.disconnected("earbuds");var next=s.beginClaim("earbuds");
        assertNull(s.finishClaim(ended,true));assertTrue(s.claiming(next));
        assertNull(s.finishClaim(ended,false));assertTrue(s.claiming(next));
    }
    @Test public void disconnectKeepsRemoteOwnershipButRecoveryIsBounded(){
        HandoffState s=new HandoffState();HandoffState.Ticket ticket=s.remote("earbuds");s.disconnected("earbuds");
        assertFalse(s.current(ticket));assertTrue(s.remoteOwns("earbuds"));
        assertEquals(1,s.recovery("earbuds"));assertEquals(2,s.recovery("earbuds"));assertEquals(3,s.recovery("earbuds"));assertEquals(0,s.recovery("earbuds"));
        s.local("earbuds");assertEquals(0,s.recovery("earbuds"));s.remote("earbuds");assertEquals(1,s.recovery("earbuds"));
    }
    @Test public void aNewPeerYieldCancelsEvenAnUnselectedClaim(){
        HandoffState s=new HandoffState();var claim=s.beginClaim("buds");
        var remote=s.requestRemote("buds");assertFalse(s.claiming(claim));assertTrue(s.current(remote));
        assertNull(s.finishClaim(claim,true));assertTrue(s.remoteOwns("buds"));
    }
    @Test public void oldSourceSnapshotIsDifferentFromANewHandoffAfterSelection(){
        HandoffState s=new HandoffState();var claim=s.beginClaim("buds");
        assertNull(s.source("buds",false,2));assertTrue(s.claiming(claim));
        s.arm(claim);s.source("buds",true,0);var remote=s.source("buds",false,2);
        assertNotNull(remote);assertFalse(s.claiming(claim));assertTrue(s.current(remote));
    }
    @Test public void ownershipLossAfterLocalSelectionCancelsTheClaim(){
        HandoffState s=new HandoffState();var claim=s.beginClaim("buds");s.arm(claim);s.source("buds",true,0);
        assertNotNull(s.ownership("buds",0));assertFalse(s.claiming(claim));
    }
    @Test public void disablingInvalidatesOldWorkWithoutFinishingItsReplacement(){
        HandoffState s=new HandoffState();s.remote("buds");var old=s.beginClaim("buds");s.cancel("buds");
        assertFalse(s.claiming(old));assertTrue(s.remoteOwns("buds"));var next=s.beginClaim("buds");
        assertNull(s.finishClaim(old,false));assertTrue(s.claiming(next));
    }
    @Test public void anOwnershipAckAloneCannotEraseAnObservedRemoteSource(){
        HandoffState s=new HandoffState();s.remote("buds");s.ownership("buds",1);
        assertTrue(s.remoteOwns("buds"));s.source("buds",true,2);assertFalse(s.remoteOwns("buds"));
    }
    @Test public void reconnectSnapshotsCannotCancelPlaybackBeforeOwnershipWasSent(){
        HandoffState s=new HandoffState();var claim=s.beginClaim("buds");
        s.source("buds",true,0);assertNull(s.ownership("buds",0));
        assertNull(s.source("buds",false,2));assertTrue(s.claiming(claim));
        s.arm(claim);s.source("buds",true,0);
        assertNotNull(s.ownership("buds",0));assertFalse(s.claiming(claim));
    }
    @Test public void armingAnOldClaimCannotChangeItsReplacement(){
        HandoffState s=new HandoffState();var old=s.beginClaim("buds");var current=s.beginClaim("buds");
        s.arm(old);s.source("buds",true,0);assertNull(s.ownership("buds",0));assertTrue(s.claiming(current));
        assertNotNull(s.requestRemote("buds"));assertFalse(s.claiming(current));
    }
    @Test public void aPlaybackIntentSurvivesTransportEventsWhileItIsStillReconnecting(){
        HandoffState s=new HandoffState();var claim=s.beginClaim("buds");
        s.disconnected("buds");assertTrue(s.preparing(claim));
        s.source("buds",true,0);s.ownership("buds",0);assertTrue(s.claiming(claim));
        s.arm(claim);s.disconnected("buds");assertFalse(s.claiming(claim));
    }
    @Test public void anExplicitPeerRequestCanStillCancelAnIntentWaitingForReconnect(){
        HandoffState s=new HandoffState();var claim=s.beginClaim("buds");s.disconnected("buds");
        assertNotNull(s.requestRemote("buds"));assertFalse(s.preparing(claim));
    }
    @Test public void aLocalSelectionFoundDuringPreparationPreventsRollbackToThePeer(){
        HandoffState s=new HandoffState();s.remote("buds");var claim=s.beginClaim("buds");
        s.retainLocal(claim);
        assertFalse(s.claiming(claim));assertFalse(s.remoteOwns("buds"));
        assertNull(s.finishClaim(claim,false));
    }
    @Test public void stalePreparationCannotClearANewRemoteTakeover(){
        HandoffState s=new HandoffState();var claim=s.beginClaim("buds");var remote=s.requestRemote("buds");
        s.retainLocal(claim);
        assertTrue(s.current(remote));
    }
}
