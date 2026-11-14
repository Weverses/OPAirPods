// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Delivery and observed state are separate: an old matching cache is not an ACK. */
final class ControlWrite {
    static final int WAITING=1, UNCONFIRMED=2, CONFIRMED=3, DIFFERENT=4;
    static final long CONFIRMATION_TIMEOUT_MS=5000;
    private final byte[] expected;
    private final long baseline;
    final long deadline;

    ControlWrite(byte[] expected,long baseline,long sentAt){
        this.expected=expected.clone();this.baseline=baseline;
        deadline=sentAt+CONFIRMATION_TIMEOUT_MS;
    }
    byte[] expected(){return expected.clone();}
    int state(byte[] reported,long received,long now){
        if(reported!=null&&received>baseline)
            return Protocol.matches(reported,expected)?CONFIRMED:DIFFERENT;
        return waitingState(WAITING,deadline,now);
    }
    // Also used by the UI: a stale Binder snapshot must not wait forever when
    // the Bluetooth service becomes unavailable after a successful write.
    static int waitingState(int state,long deadline,long now){
        return state==WAITING&&now>=deadline?UNCONFIRMED:state;
    }
    // A missing echo must not make the user's selection jump back to an old
    // cache. Actual fresh reports still take precedence over the selection.
    static byte[] displayedValue(byte[] reported,byte[] selected,int state){
        byte[] value=(state==WAITING||state==UNCONFIRMED)&&selected!=null?selected:reported;
        return value==null?null:value.clone();
    }
}
