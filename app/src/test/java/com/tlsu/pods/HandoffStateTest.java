package com.tlsu.pods;
import org.junit.Test;
import static org.junit.Assert.*;
public class HandoffStateTest {
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
}
