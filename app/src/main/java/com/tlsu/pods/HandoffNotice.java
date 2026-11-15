// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.HashMap;
import java.util.Map;

/** Confirm route changes independently of presentation and retain them until acknowledged. */
final class HandoffNotice {
    static final long MAX_AGE_MS=15000;
    static final long RETRY_MS=1200;
    private static final long EVIDENCE_MS=3000;
    record Event(long id,String source,boolean remote,long at) {}
    private record Playback(boolean active,long at) {}
    private String source;
    private long sequence=System.nanoTime();
    private Event latest;
    private boolean acknowledged;
    private boolean requested;
    private String candidate,owner;
    private long requestedAt,ownerAt;
    private final Map<String,Playback> playback=new HashMap<>();

    synchronized Event observe(String self,String next,int type,long now){
        if(type==0){if(source==null&&self.equals(next))source=self;return null;}
        if(next.equals(source))return null;
        clearEvidence();
        return change(self,next,now);
    }
    synchronized Event selectedLocal(String self,long now){
        if(self==null)return null;
        clearEvidence();return self.equals(source)?null:change(self,self,now);
    }
    // An intent or OWN=0 alone cannot establish either a destination or success.
    // Fallback requires a unique earbud-reported owner AND fresh playback from it.
    synchronized Event requestRemote(String self,String peer,long now){
        requested=true;candidate=peer;requestedAt=now;return corroborate(self,now);
    }
    synchronized Event observeOwner(String self,String peer,long now){
        owner=peer;ownerAt=now;return corroborate(self,now);
    }
    synchronized Event observePlayback(String self,String peer,boolean active,long now){
        playback.put(peer,new Playback(active,now));return corroborate(self,now);
    }
    private Event corroborate(String self,long now){
        if(!requested||owner==null||self.equals(owner)||candidate!=null&&!candidate.equals(owner))return null;
        Playback report=playback.get(owner);
        if(report==null||!report.active||!fresh(requestedAt,now)||!fresh(ownerAt,now)||!fresh(report.at,now)
                ||ownerAt<requestedAt-500||report.at<requestedAt-500)return null;
        String next=owner;clearEvidence();return next.equals(source)?null:change(self,next,now);
    }
    private static boolean fresh(long at,long now){return now>=at&&now-at<=EVIDENCE_MS;}
    private void clearEvidence(){requested=false;candidate=null;owner=null;playback.clear();}
    private Event change(String self,String next,long now){
        String previous=source;source=next;if(previous==null)return null;
        acknowledged=false;latest=new Event(++sequence,next,!self.equals(next),now);return latest;
    }
    synchronized Event current(long id,long now){return latest!=null&&latest.id==id&&now>=latest.at&&now-latest.at<=MAX_AGE_MS?latest:null;}
    synchronized Event pending(long id,long now){return acknowledged?null:current(id,now);}
    synchronized boolean acknowledge(long id,long now){if(current(id,now)==null)return false;acknowledged=true;return true;}
    synchronized void disconnected(){latest=null;acknowledged=false;clearEvidence();}
    synchronized void reset(){source=null;latest=null;acknowledged=false;clearEvidence();}
    static String title(String kind){return "已移至 "+(kind==null||kind.isBlank()?"另一台设备":kind);}
}
