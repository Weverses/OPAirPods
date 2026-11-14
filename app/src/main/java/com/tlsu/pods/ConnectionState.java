// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Device-specific link evidence. Missing data is not a disconnection. */
final class ConnectionState {
    static final int UNKNOWN=-1, DISCONNECTED=0, CONNECTED=2;
    static int resolve(boolean bluetoothEnabled,Boolean acl,Integer a2dp,Integer headset){
        if(!bluetoothEnabled)return DISCONNECTED;
        if(Boolean.TRUE.equals(acl)||Integer.valueOf(CONNECTED).equals(a2dp)||Integer.valueOf(CONNECTED).equals(headset))return CONNECTED;
        return Boolean.FALSE.equals(acl)?DISCONNECTED:UNKNOWN;
    }
    static String reconcile(String original,int observed){
        if(observed==CONNECTED)return "STATE_CONNECTED";
        if(observed==DISCONNECTED&&!"STATE_START_BOND".equals(original)&&!"STATE_BONDING".equals(original))return "STATE_DISCONNECTED";
        return original;
    }
    static boolean needsRender(int observed,String resolved,boolean disconnectedHint,boolean connectStrip){
        if(observed==UNKNOWN)return false;
        return "STATE_CONNECTED".equals(resolved)?disconnectedHint||connectStrip:!disconnectedHint;
    }
}
