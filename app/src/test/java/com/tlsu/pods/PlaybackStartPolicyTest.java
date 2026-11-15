// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.tlsu.pods.PlaybackStartPolicy.Mode.*;

public class PlaybackStartPolicyTest {
    @Test public void identifiedForegroundGameUsesTheGameRoute(){
        assertEquals(GAME,PlaybackStartPolicy.classify(false,true,true,true));
    }
    @Test public void gameSnapshotMissingDuringReconnectionCannotFallBackToMusic(){
        assertEquals(IGNORE,PlaybackStartPolicy.classify(false,true,false,true));
        assertEquals(IGNORE,PlaybackStartPolicy.classify(false,true,true,false));
    }
    @Test public void gamesWithSessionsAlsoCannotFallBackWhenForegroundIdentityIsLost(){
        assertEquals(IGNORE,PlaybackStartPolicy.classify(true,true,false,true));
        assertEquals(GAME,PlaybackStartPolicy.classify(true,true,true,true));
    }
    @Test public void rawAudioWithoutAGameDeclarationStillNeverControlsAPlayer(){
        assertEquals(AUDIO,PlaybackStartPolicy.classify(false,false,false,true));
        assertEquals(AUDIO,PlaybackStartPolicy.classify(false,false,true,true));
    }
    @Test public void unknownOrStoppedAudioDoesNotAuthorizeAnySwitch(){
        assertEquals(IGNORE,PlaybackStartPolicy.classify(false,false,false,false));
    }
    @Test public void ordinarySessionPlaybackRetainsTheTargetedPlayerPath(){
        assertEquals(PLAYER,PlaybackStartPolicy.classify(true,false,false,false));
        assertEquals(PLAYER,PlaybackStartPolicy.classify(true,false,false,true));
    }
}
