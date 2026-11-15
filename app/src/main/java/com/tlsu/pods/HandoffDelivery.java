// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Main-thread presentation state. Late callbacks cannot complete a newer attempt. */
final class HandoffDelivery {
    static final long CALLBACK_TIMEOUT_MS=3000;
    private long latest=Long.MIN_VALUE,expiresAt,attempt,waitingUntil;
    private boolean waiting,shown,acknowledged;

    long begin(long id,long expires,long now){
        if(now>expires||id<latest)return 0;
        if(id!=latest){latest=id;expiresAt=expires;waiting=false;shown=false;acknowledged=false;}
        if(shown||waiting&&now<waitingUntil)return 0;
        waiting=true;waitingUntil=now+CALLBACK_TIMEOUT_MS;return ++attempt;
    }
    boolean finish(long id,long token,boolean accepted,long now){
        if(id!=latest||token!=attempt||!waiting||now>expiresAt)return false;
        waiting=false;shown=accepted;return true;
    }
    boolean needsAcknowledgement(long id,long now){return id==latest&&shown&&!acknowledged&&now<=expiresAt;}
    void acknowledged(long id){if(id==latest&&shown)acknowledged=true;}
}
