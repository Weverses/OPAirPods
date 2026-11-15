// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameReturnStateTest {
    private static final String SELF="phone",MAC="mac";
    private static final GameReturnState.Target GAME=new GameReturnState.Target(10001,"game",Set.of(10,11));
    private static GameReturnState localGame(){GameReturnState s=new GameReturnState();s.source(SELF,SELF,2,10);return s;}
    private static long loan(GameReturnState s){long id=s.capture(GAME);s.source(SELF,MAC,2,100);return id;}

    @Test public void macPauseReturnsTheContinuouslyRunningGameAfterDebounce(){
        GameReturnState s=localGame();long id=loan(s);s.source(SELF,MAC,0,500);
        assertFalse(s.due(id,2299));assertTrue(s.due(id,2300));assertTrue(s.begin(id,2300));
        assertFalse(s.begin(id,2400));s.source(SELF,SELF,2,2500);assertTrue(s.current(id));
        s.finish(id,true);assertFalse(s.pending());assertTrue(s.capture(GAME)>id);
    }
    @Test public void explicitPeerPauseWorksWhenSourcePacketStaysMedia(){
        GameReturnState s=localGame();long id=loan(s);s.playback(MAC,false,false,500);
        assertTrue(s.due(id,2300));
    }
    @Test public void silenceWithoutAStopPacketNeverTriggersReturn(){
        GameReturnState s=localGame();long id=loan(s);assertFalse(s.due(id,1000000));
    }
    @Test public void initialRemoteSnapshotDoesNotBorrowFromAGameNeverUsingTheEarbuds(){
        GameReturnState s=new GameReturnState();s.source(SELF,MAC,2,10);assertEquals(0,s.capture(GAME));
    }
    @Test public void noForegroundGameDoesNotArm(){assertEquals(0,localGame().capture(null));}
    @Test public void startupIdleAndStopFromAnUnconfirmedPeerDoNotReturn(){
        GameReturnState s=localGame();long id=s.capture(GAME);
        s.source(SELF,MAC,0,500);s.playback(MAC,false,false,600);assertFalse(s.due(id,5000));
    }
    @Test public void repeatedIdleReportsDoNotPostponeReturnForever(){
        GameReturnState s=localGame();long id=loan(s);s.source(SELF,MAC,0,500);
        s.source(SELF,MAC,0,1500);s.playback(MAC,false,false,2000);assertTrue(s.due(id,2300));
    }
    @Test public void briefPauseOrResumeCancelsThePendingTimer(){
        GameReturnState s=localGame();long id=loan(s);s.source(SELF,MAC,0,500);
        s.playback(MAC,true,false,1000);assertFalse(s.due(id,5000));
        s.playback(MAC,false,false,6000);assertFalse(s.due(id,7000));assertTrue(s.due(id,7800));
    }
    @Test public void resumedSourceAlsoCancelsThePendingTimer(){
        GameReturnState s=localGame();long id=loan(s);s.playback(MAC,false,false,500);
        s.source(SELF,MAC,2,1000);assertFalse(s.due(id,5000));
    }
    @Test public void anotherPeersIdleDoesNotReleaseTheCurrentMac(){
        GameReturnState s=localGame();long id=loan(s);s.playback("ipad",false,false,500);
        assertFalse(s.due(id,5000));
    }
    @Test public void anotherPeerTakingOverCancelsTheLoan(){
        GameReturnState s=localGame();long id=loan(s);s.source(SELF,"ipad",2,500);
        assertFalse(s.current(id));assertFalse(s.pending());
    }
    @Test public void anExplicitSwitchToAnotherPeerCancelsTheLoan(){
        GameReturnState s=localGame();loan(s);s.remoteRequest("ipad");assertFalse(s.pending());
    }
    @Test public void callsCancelReturnEvenIfTheyLaterStop(){
        GameReturnState s=localGame();long id=loan(s);s.playback(MAC,true,true,500);
        s.playback(MAC,false,false,1000);assertFalse(s.due(id,10000));
    }
    @Test public void sourceCallAlsoCancelsReturn(){
        GameReturnState s=localGame();loan(s);s.source(SELF,MAC,1,500);assertFalse(s.pending());
    }
    @Test public void manualChoiceOrScreenAndForegroundCancellationInvalidatesDelayedWork(){
        GameReturnState s=localGame();long id=loan(s);s.source(SELF,MAC,0,500);s.cancel();
        assertFalse(s.begin(id,5000));
    }
    @Test public void disconnectRequiresFreshLocalMediaBeforeAnotherLoan(){
        GameReturnState s=localGame();loan(s);s.disconnected();assertEquals(0,s.capture(GAME));
    }
    @Test public void failedReturnDoesNotImmediatelyRearmAndFightTheMac(){
        GameReturnState s=localGame();long id=loan(s);s.source(SELF,MAC,0,500);s.begin(id,2300);
        s.source(SELF,SELF,2,2400);s.finish(id,false);assertEquals(0,s.capture(GAME));
    }
    @Test public void lateCompletionCannotCancelANewerLoan(){
        GameReturnState s=localGame();long old=loan(s);s.cancel();s.source(SELF,SELF,2,1000);
        long next=s.capture(GAME);s.finish(old,false);assertTrue(s.current(next));
    }
    @Test public void sameFocusedGameCanRebuildItsAudioPlayersButMustRemainAudible(){
        assertTrue(GAME.continues(new GameReturnState.Target(10001,"game",Set.of(11,12))));
        assertTrue(GAME.continues(new GameReturnState.Target(10001,"game",Set.of(12))));
        assertFalse(GAME.continues(new GameReturnState.Target(10001,"game",Set.of())));
        assertFalse(GAME.continues(new GameReturnState.Target(10002,"game",Set.of(10))));
        assertFalse(GAME.continues(new GameReturnState.Target(10001,"other",Set.of(10))));assertFalse(GAME.continues(null));
    }
    @Test public void peerResumeDuringReturnInvalidatesTheRouteTransaction(){
        GameReturnState s=localGame();long id=loan(s);s.source(SELF,MAC,0,500);s.begin(id,2300);
        s.playback(MAC,true,false,2400);assertFalse(s.current(id));
    }
    @Test public void newRemoteRequestDuringReturnAlsoInvalidatesTheRoute(){
        GameReturnState s=localGame();long id=loan(s);s.source(SELF,MAC,0,500);s.begin(id,2300);
        s.remoteRequest(MAC);assertFalse(s.current(id));
    }
    @Test public void localIdleValidationDoesNotPretendTheGameReturned(){
        GameReturnState s=localGame();long id=loan(s);s.source(SELF,SELF,0,500);assertTrue(s.current(id));
    }
    @Test public void routeConfirmationWithAContinuousGameCanStartTheNextLoanWithoutMediaRestart(){
        GameReturnState s=localGame();long id=loan(s);s.source(SELF,MAC,0,500);s.begin(id,2300);
        s.source(SELF,SELF,0,2400);s.selectedLocalGame();s.finish(id,true);
        assertTrue(s.localMedia());assertTrue(s.capture(GAME)>id);
    }
    @Test public void shortAudioRebuildKeepsTheLoanButSustainedSilenceCancelsIt(){
        GameReturnState s=localGame();loan(s);
        assertTrue(s.keepAudio(true,false,1000));assertTrue(s.keepAudio(true,false,2499));
        assertFalse(s.keepAudio(true,false,2500));
    }
    @Test public void audioRecoveryResetsTheRebuildGracePeriod(){
        GameReturnState s=localGame();loan(s);assertTrue(s.keepAudio(true,false,1000));
        assertTrue(s.keepAudio(true,true,1800));assertTrue(s.keepAudio(true,false,5000));
        assertTrue(s.keepAudio(true,false,6000));
    }
    @Test public void leavingTheForegroundOrLockingDoesNotGetAnAudioGracePeriod(){
        GameReturnState s=localGame();loan(s);assertFalse(s.keepAudio(false,true,1000));
        assertFalse(s.keepAudio(false,false,1000));
    }
    @Test public void controlOnlyReconnectRetainsIdentityAndInvalidatesOldWork(){
        GameReturnState s=localGame();long old=loan(s);s.source(SELF,MAC,0,500);
        long next=s.controlLost(1000);assertTrue(s.current(next));assertFalse(s.current(old));assertEquals(GAME,s.target());
        s.finish(old,false);assertTrue(s.current(next));assertFalse(s.begin(next,10000));
    }
    @Test public void reconnectNeedsFreshRemotePlaybackBeforeAnotherPauseCanReturn(){
        GameReturnState s=localGame();loan(s);long id=s.controlLost(1000);
        s.playback(MAC,false,false,2000);s.source(SELF,MAC,0,2100);assertFalse(s.due(id,5000));
        s.source(SELF,MAC,2,6000);s.playback(MAC,false,false,7000);
        assertTrue(s.due(id,8800));assertTrue(s.keepAudio(true,true,50000));
    }
    @Test public void missingRemoteAfterReconnectExpiresThePreservedLoan(){
        GameReturnState s=localGame();loan(s);s.controlLost(1000);
        assertTrue(s.keepAudio(true,true,15999));assertFalse(s.keepAudio(true,true,16000));
    }
    @Test public void differentPeerAfterReconnectStillCancelsTheLoan(){
        GameReturnState s=localGame();loan(s);s.controlLost(1000);s.source(SELF,"ipad",2,2000);
        assertFalse(s.pending());
    }
}
