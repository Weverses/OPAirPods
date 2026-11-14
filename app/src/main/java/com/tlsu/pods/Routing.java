// SPDX-License-Identifier: GPL-3.0-or-later
// TiPi / Smart Routing encodings adapted from LibrePods AACPManager.kt.
package com.tlsu.pods;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class Routing {
    private Routing() {}
    static boolean header(byte[] d, int n) {
        return d != null && n >= 6 && n <= d.length && d[0]==4 && d[1]==0 && d[2]==4 && d[3]==0 && d[5]==0;
    }
    static String mac(byte[] data, int offset, boolean reverse) {
        if (offset < 0 || offset + 6 > data.length) throw new IllegalArgumentException("Truncated MAC");
        StringBuilder out = new StringBuilder();
        for (int i=0;i<6;i++) { if(i>0)out.append(':'); out.append(String.format(Locale.ROOT,"%02X",data[offset+(reverse?5-i:i)]&255)); }
        return out.toString();
    }
    static List<String> peers(byte[] data, int n) {
        if (!header(data,n) || data[4]!=0x2e || n<9) throw new IllegalArgumentException("Truncated TiPi header");
        int count=data[8]&255;
        if (count>8 || 9+count*8>n) throw new IllegalArgumentException("Truncated TiPi records");
        List<String> result=new ArrayList<>();
        for(int i=0;i<count;i++) result.add(mac(data,9+i*8,false));
        return result;
    }
    private static byte[] reversedMac(String mac) {
        if(mac==null || !mac.matches("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}")) throw new IllegalArgumentException("无效的设备地址");
        String[] parts=mac.split(":"); byte[] out=new byte[6];
        for(int i=0;i<6;i++)out[5-i]=(byte)Integer.parseInt(parts[i],16);
        return out;
    }
    private static java.util.Map<String,Object> dict(Object... pairs){
        java.util.Map<String,Object> values=new java.util.LinkedHashMap<>();
        for(int i=0;i<pairs.length;i+=2)values.put((String)pairs[i],pairs[i+1]);return values;
    }
    private static byte[] frame(String target,java.util.Map<String,Object> values){
        byte[] body=Opack.encode(values);int size=body.length+1;
        byte[] out=new byte[14+size];out[0]=4;out[2]=4;out[4]=0x10;
        System.arraycopy(reversedMac(target),0,out,6,6);out[12]=(byte)size;out[13]=(byte)(size>>8);out[14]=1;
        System.arraycopy(body,0,out,15,body.length);return out;
    }
    static byte[] introduce(String self,String target){reversedMac(self);return frame(target,dict("idleTime",0,"newTipi",true,"btAddress",self,"btName","Android","nearbyAudioScore",6));}
    static byte[] mediaInfo(String self,String target){return streamingInfo(self,target,false);}
    static byte[] streamingInfo(String self,String target,boolean playing){
        reversedMac(self);return frame(target,dict("playingApp",playing?"Android":"NA","hostStreamingState",playing?"YES":"NO","btAddress",self,"btName","Android","otherDeviceAudioCategory",playing?301:100));
    }
    static byte[] takeOver(String target){return frame(target,dict("localscore",100,"reason","Hijackv2","audioRoutingScore",301,"audioRoutingSetOwnershipToFalse",true,"remotescore",301));}
    static byte[] showNearby(String target){return frame(target,dict("SmartRoutingKeyShowNearbyUI",true,"localscore",301,"reason","Hijackv2","audioRoutingScore",301,"audioRoutingSetOwnershipToFalse",true,"remotescore",301));}
    static byte[] reverse(String target){return frame(target,dict("audioRoutingSetOwnershipToFalse",true,"audioRoutingShowReverseUI",true,"reason","ReverseBannerTapped"));}
    static byte[] ownership(boolean own){return new byte[]{4,0,4,0,9,0,6,(byte)(own?1:0),0,0,0};}
    record Peer(String address,int role,int state){boolean takingOver(){return state>=0x10&&(state&2)==0&&state!=0x15;}}
    static List<Peer> peerRecords(byte[] data,int n){
        peers(data,n);List<Peer> result=new ArrayList<>();for(int i=0;i<(data[8]&255);i++){int at=9+i*8;result.add(new Peer(mac(data,at,false),data[at+6]&255,data[at+7]&255));}return result;
    }
    record Message(String sender,String kind,boolean relinquish,boolean reversed,boolean showNearby,Boolean playing,Integer category){
        boolean decisive(){return Boolean.FALSE.equals(playing)||Boolean.TRUE.equals(playing)&&category!=null&&category>100;}
        boolean call(){return Boolean.TRUE.equals(playing)&&category!=null&&category>=500;}
    }
    static Message response(byte[] data,int n){
        if(!header(data,n)||data[4]!=0x11||n<16||data[14]!=1)throw new IllegalArgumentException("Truncated Smart Routing response");
        int size=14+(data[12]&255)+((data[13]&255)<<8);
        if(size>n||size>4096||size<16)throw new IllegalArgumentException("Truncated Smart Routing dictionary");
        var values=Opack.decode(java.util.Arrays.copyOfRange(data,15,size));String name=String.valueOf(values.getOrDefault("btName",""));
        String kind=null;for(String candidate:new String[]{"iPad","Mac","iPhone","Linux","Android"})if(name.contains(candidate)){kind=candidate;break;}
        Object streaming=values.get("hostStreamingState"),category=values.get("otherDeviceAudioCategory");
        Boolean playing=streaming instanceof String?"YES".equals(streaming):null;
        return new Message(mac(data,6,true),kind,Boolean.TRUE.equals(values.get("audioRoutingSetOwnershipToFalse")),"ReverseBannerTapped".equals(values.get("reason")),Boolean.TRUE.equals(values.get("SmartRoutingKeyShowNearbyUI")),playing,category instanceof Number?((Number)category).intValue():null);
    }
}
