// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Only confirmed source changes produce notices; idle/ownership packets do not. */
final class HandoffNotice {
    static final long MAX_AGE_MS=15000;
    record Event(long id,String source,boolean remote,long at) {}
    private String source;
    private long sequence=System.nanoTime();
    private Event latest;
    private boolean consumed;

    synchronized Event observe(String self,String next,int type,long now){
        if(type==0){if(source==null&&self.equals(next))source=self;return null;}
        if(next.equals(source))return null;
        return change(self,next,now);
    }
    synchronized Event selectedLocal(String self,long now){return self==null||self.equals(source)?null:change(self,self,now);}
    private Event change(String self,String next,long now){
        String previous=source;source=next;if(previous==null)return null;
        consumed=false;latest=new Event(++sequence,next,!self.equals(next),now);return latest;
    }
    synchronized Event current(long id,long now){return latest!=null&&latest.id==id&&now>=latest.at&&now-latest.at<=MAX_AGE_MS?latest:null;}
    synchronized Event take(long id,long now){Event event=current(id,now);if(event==null||consumed)return null;consumed=true;return event;}
    static String title(String kind){return "已移至 "+(kind==null||kind.isBlank()?"另一台设备":kind);}
}
