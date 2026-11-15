// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Earbud-confirmed listening changes; the first report only establishes a baseline. */
final class ListeningModeNotice {
    record Event(long id,int mode,long at) {}
    private int mode;
    private long sequence=System.nanoTime();
    private Event latest;
    private boolean acknowledged;

    synchronized Event observe(int next,long now){
        if(name(next)==null||next==mode)return null;
        int previous=mode;mode=next;
        if(previous==0)return null;
        acknowledged=false;latest=new Event(++sequence,next,now);return latest;
    }
    synchronized Event pending(long id,long now){
        return !acknowledged&&current(id,now)?latest:null;
    }
    private boolean current(long id,long now){
        return latest!=null&&latest.id==id&&now>=latest.at&&now-latest.at<=HandoffNotice.MAX_AGE_MS;
    }
    synchronized boolean acknowledge(long id,long now){
        if(!current(id,now))return false;
        acknowledged=true;return true;
    }
    synchronized void disconnected(){mode=0;latest=null;acknowledged=false;}
    synchronized int mode(){return mode;}
    static String name(int mode){
        return switch(mode){case 1->"关闭";case 2->"降噪";case 3->"通透";case 4->"自适应";default->null;};
    }
    static String title(int mode){return name(mode);}
}
