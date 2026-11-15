// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Audio configuration events never authorize commands to unrelated sessions. */
final class PlaybackStartPolicy {
    enum Mode { IGNORE, GAME, AUDIO, PLAYER }
    private PlaybackStartPolicy(){}
    static Mode classify(boolean session,boolean gameSource,boolean focusedGame,boolean livePlayers){
        if(gameSource)return focusedGame&&livePlayers?Mode.GAME:Mode.IGNORE;
        if(session)return Mode.PLAYER;
        return livePlayers?Mode.AUDIO:Mode.IGNORE;
    }
}
