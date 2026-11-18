// SPDX-License-Identifier: GPL-3.0-or-later
// Stem configuration bits and events follow LibrePods AACPManager.kt.
package com.tlsu.pods;

import java.util.Arrays;

final class StemConfig {
    static final int DEFAULT=0,PLAY_PAUSE=1,NEXT=2,PREVIOUS=3,ASSISTANT=4,CYCLE=5,NONE=6;
    record Press(int side,int type) {
        Press {index(side,type);}
        int bit(){return 1<<type;}
    }
    private final int[] actions;
    StemConfig(int[] actions){
        if(actions==null||actions.length!=8)throw new IllegalArgumentException("无效的耳柄配置");
        for(int action:actions)if(action<DEFAULT||action>NONE)throw new IllegalArgumentException("未知的耳柄动作");
        this.actions=actions.clone();
    }
    static StemConfig defaults(){return new StemConfig(new int[8]);}
    static int index(int side,int type){
        if(side<1||side>2||type<0||type>3)throw new IllegalArgumentException("未知的耳柄按压");
        return (side-1)*4+type;
    }
    int[] values(){return actions.clone();}
    int selected(int side,int type){return actions[index(side,type)];}
    int action(Press press){
        int selected=selected(press.side,press.type);
        return selected!=DEFAULT?selected:switch(press.type){case 0->PLAY_PAUSE;case 1->NEXT;case 2->PREVIOUS;default->CYCLE;};
    }
    StemConfig with(int side,int type,int action){int[] next=values();next[index(side,type)]=action;return new StemConfig(next);}
    int mask(){int mask=0;for(int type=0;type<4;type++)if(actions[type]!=DEFAULT||actions[type+4]!=DEFAULT)mask|=1<<type;return mask;}
    boolean handles(Press press,int appliedMask){return (mask()&press.bit())!=0&&(appliedMask&press.bit())!=0;}
    static final class Dispatch {
        private final StemConfig config;
        private int appliedMask;
        private Press last;
        private long at;
        Dispatch(StemConfig config,int appliedMask){this.config=config;report(appliedMask);}
        synchronized void report(int mask){if(mask<0||mask>15)throw new IllegalArgumentException("无效的耳柄配置位");appliedMask=mask;}
        synchronized boolean handles(Press press){return config.handles(press,appliedMask);}
        synchronized int take(Press press,long now){
            if(!handles(press)||now<at||press.equals(last)&&now-at<120)return DEFAULT;
            last=press;at=now;return config.action(press);
        }
    }
    byte[] packet(){return new byte[]{4,0,4,0,9,0,0x39,(byte)mask(),0,0,0};}
    static boolean isPressPacket(byte[] data, int length) {
        return length == 8 && Routing.header(data, length) && data[4] == 0x19;
    }
    static Press parse(byte[] data,int length){
        if(!isPressPacket(data,length))
            throw new IllegalArgumentException("未知的耳柄事件");
        return new Press(data[7]&255,(data[6]&255)-5);
    }
    String encode(){StringBuilder out=new StringBuilder();for(int action:actions)out.append((char)('0'+action));return out.toString();}
    static StemConfig decode(String encoded){
        if(encoded==null||encoded.length()!=8)throw new IllegalArgumentException("无效的已保存耳柄配置");
        return new StemConfig(encoded.chars().map(c->c-'0').toArray());
    }
    static int nextMode(int current,int mask){
        if(current<1||current>4||!ListeningModes.valid(mask))throw new IllegalArgumentException("聆听模式状态不可用");
        for(int step=1;step<=4;step++){int next=(current-1+step)%4+1;if((mask&(1<<(next-1)))!=0)return next;}
        throw new IllegalArgumentException("聆听模式状态不可用");
    }
    static boolean localRoute(boolean remoteOwner,boolean remoteCall,String source,String self){
        return !remoteOwner&&!remoteCall&&(source==null||"00:00:00:00:00:00".equals(source)||source.equals(self));
    }
    @Override public boolean equals(Object other){return other instanceof StemConfig c&&Arrays.equals(actions,c.actions);}
    @Override public int hashCode(){return Arrays.hashCode(actions);}
}
