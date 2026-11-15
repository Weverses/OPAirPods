// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class GameAutoplayGuardTest {
    private static final GameReturnState.Target GAME=new GameReturnState.Target(10001,"game",Set.of(10));
    @Test public void ordinaryHeadsetPlayPassesWithoutAGameHandoff(){
        GameAutoplayGuard g=new GameAutoplayGuard();assertFalse(g.blockPlay(true,100,GAME));assertFalse(g.blockPlay(false,200,GAME));
    }
    @Test public void routeResumeCannotStartTheBackgroundMusicSession(){
        GameAutoplayGuard g=new GameAutoplayGuard();g.arm(GAME,100);
        assertTrue(g.blockPlay(true,500,GAME));assertTrue(g.blockPlay(false,600,GAME));
    }
    @Test public void headsetControlsRecoverAfterTheSettlingWindow(){
        GameAutoplayGuard g=new GameAutoplayGuard();g.arm(GAME,100);g.settle(500);
        assertTrue(g.blockPlay(true,3499,GAME));assertTrue(g.blockPlay(false,3500,GAME));
        assertFalse(g.blockPlay(true,3600,GAME));assertFalse(g.blockPlay(false,3700,GAME));
    }
    @Test public void failedOrAbandonedRoutesCannotBlockHeadsetPlayIndefinitely(){
        GameAutoplayGuard g=new GameAutoplayGuard();g.arm(GAME,100);
        assertFalse(g.blockPlay(true,100+GameAutoplayGuard.ROUTE_MS,GAME));
    }
    @Test public void longRemoteVideosStayProtectedWhileTheLoanIsRefreshed(){
        GameAutoplayGuard g=new GameAutoplayGuard();g.arm(GAME,100);g.arm(GAME,24000);g.arm(GAME,47000);
        assertTrue(g.blockPlay(true,70000,GAME));
    }
    @Test public void changingForegroundOrLosingGameAudioReleasesProtection(){
        GameAutoplayGuard g=new GameAutoplayGuard();g.arm(GAME,100);
        assertFalse(g.blockPlay(true,500,new GameReturnState.Target(10002,"other",Set.of(20))));
        assertFalse(g.blockPlay(true,600,GAME));g.arm(GAME,700);
        assertFalse(g.blockPlay(true,800,null));
    }
    @Test public void gameTrackRebuildDoesNotLetTheRoutePlayCommandEscape(){
        GameAutoplayGuard g=new GameAutoplayGuard();g.arm(GAME,100);
        assertTrue(g.blockPlay(true,500,new GameReturnState.Target(10001,"game",Set.of(99))));
    }
    @Test public void transientTrackGapCannotReleaseAutoplayWhileTheSameGameIsFocused(){
        GameAutoplayGuard g=new GameAutoplayGuard();g.arm(GAME,100);
        assertTrue(g.blockPlay(true,500,new GameReturnState.Target(10001,"game",Set.of())));
        assertTrue(g.blockPlay(false,600,new GameReturnState.Target(10001,"game",Set.of())));
    }
    @Test public void manualChoiceCancelsProtectionButStillConsumesASwallowedKeysRelease(){
        GameAutoplayGuard g=new GameAutoplayGuard();g.arm(GAME,100);assertTrue(g.blockPlay(true,500,GAME));
        g.cancel();assertTrue(g.blockPlay(false,600,null));
        assertFalse(g.blockPlay(true,700,GAME));assertFalse(g.blockPlay(false,800,GAME));
    }
    @Test public void missingReleaseCannotSwallowANewPressAfterCancellation(){
        GameAutoplayGuard g=new GameAutoplayGuard();g.arm(GAME,100);assertTrue(g.blockPlay(true,500,GAME));g.cancel();
        assertFalse(g.blockPlay(true,600,GAME));assertFalse(g.blockPlay(false,700,GAME));
    }
    @Test public void missingReleaseExpiresInsteadOfSwallowingUnrelatedLateUps(){
        GameAutoplayGuard g=new GameAutoplayGuard();g.arm(GAME,100);assertTrue(g.blockPlay(true,500,GAME));g.cancel();
        assertFalse(g.blockPlay(false,2500,GAME));
    }
}
