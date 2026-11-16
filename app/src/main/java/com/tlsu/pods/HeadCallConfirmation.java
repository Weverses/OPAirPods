// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Sending answer/reject is not confirmation. Only observed outcomes qualify. */
final class HeadCallConfirmation<T> {
    enum Observation { PENDING, ACTIVE, REJECTED, DISCONNECTED_OTHER }
    enum Result { WAITING, SUCCESS, FAILED, TIMED_OUT, CANCELLED }
    final T call;
    final boolean answer;
    private final long deadline;
    private Result result=Result.WAITING;
    HeadCallConfirmation(T call,boolean answer,long now){this.call=call;this.answer=answer;deadline=now+5000;}
    Result observe(T call,Observation state,long now){
        if(result!=Result.WAITING||this.call!=call)return result;
        if(now>=deadline)return result=Result.TIMED_OUT;
        if(answer&&state==Observation.ACTIVE||!answer&&state==Observation.REJECTED)return result=Result.SUCCESS;
        if(state==Observation.DISCONNECTED_OTHER||answer&&state==Observation.REJECTED||!answer&&state==Observation.ACTIVE)
            return result=Result.FAILED;
        return result;
    }
    Result cancel(){if(result==Result.WAITING)result=Result.CANCELLED;return result;}
}
