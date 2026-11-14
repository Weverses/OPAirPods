package com.tlsu.pods;
import org.junit.Test;
import static org.junit.Assert.*;
public class PlaybackPauseGateTest {
    @Test public void aPlayerRestartedDuringReconnectGetsPausedAndCanCompleteTheSameIntent(){
        var gate=new PlaybackPauseGate(3500);
        assertEquals(PlaybackPauseGate.Action.PAUSE,gate.check(false,3500));
        assertEquals(PlaybackPauseGate.Action.WAIT,gate.check(false,3600));
        assertEquals(PlaybackPauseGate.Action.READY,gate.check(true,3700));
    }
    @Test public void aPlayerThatIgnoresPauseCannotCauseUnboundedTakeovers(){
        var gate=new PlaybackPauseGate(1000);
        assertEquals(PlaybackPauseGate.Action.PAUSE,gate.check(false,1000));
        assertEquals(PlaybackPauseGate.Action.PAUSE,gate.check(false,1300));
        assertEquals(PlaybackPauseGate.Action.TIMEOUT,gate.check(false,3000));
    }
    @Test public void anAlreadyPausedPlayerDoesNotGetAnotherCommand(){
        assertEquals(PlaybackPauseGate.Action.READY,new PlaybackPauseGate(0).check(true,0));
    }
}
