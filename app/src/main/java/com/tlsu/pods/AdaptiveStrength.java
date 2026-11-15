// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** UI strength increases to the right; AACP's ambient allowance is inverted. */
final class AdaptiveStrength {
    private AdaptiveStrength() {}
    static int clamp(int value){return Math.max(0,Math.min(100,value));}
    static int fromReport(byte[] report){
        byte[] value=Protocol.reportedValue(0x2e,report);
        return value==null?-1:100-(value[0]&255);
    }
    static byte[] command(int strength){return new byte[]{(byte)(100-clamp(strength))};}
    static int drag(int start,float distance,float travel){
        if(travel<=0)return start;
        int value=clamp(Math.round(start+distance*100/travel));
        // Keep the midpoint easy to select, while allowing a continuous drag
        // to leave it once the finger moves beyond the center band.
        return Math.abs(value-50)<=5?50:value;
    }
}
