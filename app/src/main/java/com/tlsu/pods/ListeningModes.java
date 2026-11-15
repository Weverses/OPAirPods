// SPDX-License-Identifier: GPL-3.0-or-later
// Listening mode bits and the two-mode minimum follow LibrePods.
package com.tlsu.pods;

final class ListeningModes {
    private ListeningModes(){}
    static boolean valid(int mask){return (mask&~15)==0&&Integer.bitCount(mask)>=2;}
    static String name(int bit){
        return switch(bit){case 1->"关闭";case 2->"降噪";case 4->"通透";case 8->"自适应";default->throw new IllegalArgumentException("未知的聆听模式");};
    }
    static boolean available(int mask,int bit,boolean offAllowed,boolean adaptiveSupported){
        name(bit);
        return valid(mask)&&((mask&bit)!=0||(bit!=1||offAllowed)&&(bit!=8||adaptiveSupported));
    }
    static int select(int mask,int bit,boolean selected){
        name(bit);
        if(!valid(mask))throw new IllegalArgumentException("尚未收到有效的长按模式设置");
        int next=selected?mask|bit:mask&~bit;
        if(!valid(next))throw new IllegalArgumentException("请至少保留两种聆听模式");
        return next;
    }
    static String label(int mask){
        java.util.List<String> names=new java.util.ArrayList<>();
        for(int bit:new int[]{1,2,4,8})if((mask&bit)!=0)names.add(name(bit));
        return String.join("、",names);
    }
}
