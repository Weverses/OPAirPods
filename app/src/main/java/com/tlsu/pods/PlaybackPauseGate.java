// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** A route change may restart a player while AACP is reconnecting. Reassert
 * pause on that same player within a bounded window before taking ownership. */
final class PlaybackPauseGate {
    enum Action { READY, WAIT, PAUSE, TIMEOUT }
    private final long deadline;
    private long nextPause;
    PlaybackPauseGate(long now){deadline=now+2000;nextPause=now;}
    Action check(boolean paused,long now){
        if(paused)return Action.READY;
        if(now>=deadline)return Action.TIMEOUT;
        if(now>=nextPause){nextPause=now+300;return Action.PAUSE;}
        return Action.WAIT;
    }
}
