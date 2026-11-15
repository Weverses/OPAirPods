// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Keep identity during a short AudioTrack rebuild; this never proves audibility. */
final class AudioRebuildWindow {
    static final long GAP_MS=1500;
    private long missingAt=-1;
    synchronized boolean alive(boolean focused,boolean audible,long now){
        if(!focused)return false;
        if(audible){reset();return true;}
        if(missingAt<0)missingAt=now;
        return now-missingAt<GAP_MS;
    }
    synchronized void reset(){missingAt=-1;}
}
