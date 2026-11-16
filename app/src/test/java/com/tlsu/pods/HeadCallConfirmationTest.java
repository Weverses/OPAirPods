// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;
import static com.tlsu.pods.HeadCallConfirmation.Observation.*;
import static com.tlsu.pods.HeadCallConfirmation.Result.*;

public class HeadCallConfirmationTest {
    @Test public void dispatchedRequestIsNotSuccess(){
        Object call=new Object();var confirmation=new HeadCallConfirmation<>(call,true,0);
        assertEquals(WAITING,confirmation.observe(call,PENDING,100));
        assertEquals(SUCCESS,confirmation.observe(call,ACTIVE,200));
    }
    @Test public void rejectNeedsExplicitRejection(){
        Object call=new Object();var confirmation=new HeadCallConfirmation<>(call,false,0);
        assertEquals(WAITING,confirmation.observe(call,PENDING,100));
        assertEquals(SUCCESS,confirmation.observe(call,REJECTED,200));
    }
    @Test public void remoteHangupNeverConfirmsReject(){
        Object call=new Object();var confirmation=new HeadCallConfirmation<>(call,false,0);
        assertEquals(FAILED,confirmation.observe(call,DISCONNECTED_OTHER,200));
    }
    @Test public void answeredOnAnotherPathDoesNotConfirmPendingRejection(){
        Object call=new Object();var confirmation=new HeadCallConfirmation<>(call,false,0);
        assertEquals(FAILED,confirmation.observe(call,ACTIVE,200));
    }
    @Test public void anotherCallCannotConfirmThisRequest(){
        Object call=new Object();var confirmation=new HeadCallConfirmation<>(call,true,0);
        assertEquals(WAITING,confirmation.observe(new Object(),ACTIVE,200));
    }
    @Test public void missingConfirmationTimesOutWithoutSuccess(){
        Object call=new Object();var confirmation=new HeadCallConfirmation<>(call,false,0);
        assertEquals(WAITING,confirmation.observe(call,PENDING,4999));
        assertEquals(TIMED_OUT,confirmation.observe(call,REJECTED,5000));
    }
    @Test public void disconnectOrFeatureCancellationSuppressesLateSuccess(){
        Object call=new Object();var confirmation=new HeadCallConfirmation<>(call,true,0);
        assertEquals(CANCELLED,confirmation.cancel());assertEquals(CANCELLED,confirmation.observe(call,ACTIVE,200));
    }
    @Test public void callEndingAfterSuccessfulAnswerDoesNotRewriteOutcome(){
        Object call=new Object();var confirmation=new HeadCallConfirmation<>(call,true,0);
        assertEquals(SUCCESS,confirmation.observe(call,ACTIVE,200));
        assertEquals(SUCCESS,confirmation.observe(call,DISCONNECTED_OTHER,300));
    }
}
