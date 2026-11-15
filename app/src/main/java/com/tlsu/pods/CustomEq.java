// SPDX-License-Identifier: GPL-3.0-or-later
// Wire format derived from LibrePods data/CustomEq.kt and AACPManager.kt.
package com.tlsu.pods;

record CustomEq(int state,int low,int mid,int high) {
    CustomEq {
        if((state!=1&&state!=2)||low<0||low>100||mid<0||mid>100||high<0||high>100)
            throw new IllegalArgumentException("无效的均衡器参数");
    }
    static CustomEq flat(){return new CustomEq(1,50,50,50);}
    boolean enabled(){return state==2;}
    byte[] value(){return new byte[]{(byte)state,(byte)low,(byte)mid,(byte)high};}
    static CustomEq fromValue(byte[] value){
        if(value==null||value.length!=4)throw new IllegalArgumentException("无效的均衡器状态");
        return new CustomEq(value[0]&255,value[1]&255,value[2]&255,value[3]&255);
    }
    byte[] packet(){return new byte[]{4,0,4,0,0x63,0,5,0,1,(byte)state,(byte)low,(byte)mid,(byte)high};}
    static CustomEq parse(byte[] data,int length){
        if(data==null||length!=13||length>data.length||!Routing.header(data,length)||data[4]!=0x63||data[5]!=0
                ||data[6]!=5||data[7]!=0||data[8]!=1)throw new IllegalArgumentException("未知的均衡器报文");
        return new CustomEq(data[9]&255,data[10]&255,data[11]&255,data[12]&255);
    }
    CustomEq patch(String field,int value){
        return switch(field){
            case "enabled"->{if(value!=0&&value!=1)throw new IllegalArgumentException("无效的均衡器开关");yield new CustomEq(value==1?2:1,low,mid,high);}
            case "low"->new CustomEq(state,value,mid,high);
            case "mid"->new CustomEq(state,low,value,high);
            case "high"->new CustomEq(state,low,mid,value);
            case "reset"->{if(value!=0)throw new IllegalArgumentException("无效的重置参数");yield new CustomEq(state,50,50,50);}
            default->throw new IllegalArgumentException("未知的均衡器频段");
        };
    }
}
