// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Suppress the earbud's resume command while returning an already audible game. */
final class GameAutoplayGuard {
    static final long ROUTE_MS=25_000, SETTLE_MS=3_000, RELEASE_MS=2_000;
    private GameReturnState.Target game;
    private long until,releaseUntil=-1;

    synchronized void arm(GameReturnState.Target target,long now){game=target;until=now+ROUTE_MS;}
    synchronized void settle(long now){until=Math.min(until,now+SETTLE_MS);}
    synchronized void cancel(){game=null;until=0;}
    synchronized boolean protects(long now,GameReturnState.Target current){
        if(game!=null&&(now>=until||!game.sameApp(current)))cancel();
        return game!=null;
    }
    synchronized boolean blockPlay(boolean pressed,long now,GameReturnState.Target current){
        // A DOWN swallowed before the route completed must never leak its UP.
        if(!pressed&&now<releaseUntil){releaseUntil=-1;return true;}
        boolean block=protects(now,current);
        if(pressed)releaseUntil=block?now+RELEASE_MS:-1;
        return block;
    }
}
