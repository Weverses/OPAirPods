// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.Set;

/** One temporary audio loan. Missing remote packets never count as a pause. */
final class GameReturnState {
    static final long PAUSE_MS=1800;
    static final long CONTROL_RECOVERY_MS=15000;
    record Target(int uid,String packageName,Set<Integer> players) {
        Target { players=Set.copyOf(players); }
        // AudioTrack IDs change during a Bluetooth route rebuild. Identity is
        // the focused game UID/package; it must still have live audio players.
        boolean sameApp(Target next){return next!=null&&uid==next.uid&&packageName.equals(next.packageName);}
        boolean continues(Target next){return sameApp(next)
                &&!players.isEmpty()&&!next.players.isEmpty();}
    }
    private boolean localMedia,heardRemote,returning;
    private long generation,idleAt=-1,recoveryUntil=-1;
    private final AudioRebuildWindow audioWindow=new AudioRebuildWindow();
    private String peer;
    private Target target;

    synchronized long capture(Target game){
        if(target!=null||!localMedia||game==null)return 0;
        target=game;peer=null;heardRemote=false;returning=false;idleAt=-1;localMedia=false;
        return ++generation;
    }
    synchronized void source(String self,String source,int type,long now){
        if(self.equals(source)){
            if(type==2){if(!returning)cancel();localMedia=true;}
            return;
        }
        if(type==1){localMedia=false;cancel();return;}
        if(type==2){
            localMedia=false;
            if(target==null)return;
            if(returning||peer!=null&&!peer.equals(source)){cancel();return;}
            peer=source;heardRemote=true;idleAt=-1;recoveryUntil=-1;
        }else if(type==0&&heardRemote&&source.equals(peer)&&!returning&&idleAt<0)idleAt=now;
    }
    synchronized void remoteRequest(String sender){
        if(target!=null&&(returning||peer!=null&&!peer.equals(sender)))cancel();
        localMedia=false;
    }
    synchronized void playback(String sender,Boolean playing,boolean call,long now){
        if(target==null)return;
        if(call){cancel();return;}
        if(!sender.equals(peer)){
            if(Boolean.TRUE.equals(playing)&&heardRemote)cancel();
            return;
        }
        if(Boolean.TRUE.equals(playing)){
            if(returning){cancel();return;}
            heardRemote=true;idleAt=-1;recoveryUntil=-1;
        }else if(Boolean.FALSE.equals(playing)&&heardRemote&&!returning&&idleAt<0)idleAt=now;
    }
    synchronized boolean due(long id,long now){return current(id)&&!returning&&heardRemote&&idleAt>=0&&now>=idleAt+PAUSE_MS;}
    synchronized boolean begin(long id,long now){if(!due(id,now))return false;returning=true;return true;}
    synchronized boolean current(long id){return target!=null&&generation==id;}
    synchronized Target target(){return target;}
    synchronized String peer(){return peer;}
    synchronized boolean pending(){return target!=null;}
    synchronized boolean returning(){return returning;}
    synchronized boolean localMedia(){return localMedia;}
    synchronized boolean keepAudio(boolean sameFocusedApp,boolean audible,long now){
        if(target==null||!sameFocusedApp||recoveryUntil>=0&&now>=recoveryUntil)return false;
        return audioWindow.alive(sameFocusedApp,audible,now);
    }
    /** AACP alone can restart while ACL and the foreground game remain live.
     * Retain the game identity, invalidate delayed work, require fresh remote playback. */
    synchronized long controlLost(long now){
        if(target==null)return 0;
        heardRemote=false;returning=false;idleAt=-1;localMedia=false;recoveryUntil=now+CONTROL_RECOVERY_MS;
        return ++generation;
    }
    synchronized void selectedLocalGame(){if(!returning)cancel();localMedia=true;}
    synchronized void finish(long id,boolean success){if(current(id)){boolean local=success&&localMedia;cancel();localMedia=local;}}
    synchronized void cancel(){target=null;peer=null;heardRemote=false;returning=false;idleAt=-1;audioWindow.reset();recoveryUntil=-1;localMedia=false;generation++;}
    synchronized void disconnected(){cancel();localMedia=false;}
}
