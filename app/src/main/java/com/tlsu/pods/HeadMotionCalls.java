// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.telecom.Call;
import android.telecom.DisconnectCause;
import android.telecom.InCallService;
import java.util.IdentityHashMap;
import java.util.function.BooleanSupplier;

/** Observes the Bluetooth process's existing InCallService. No default-dialer
 * role, extra service, global endCall(), or new phone permission is needed. */
final class HeadMotionCalls {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ColorPodsModule module;
    private final Runnable changed;
    private final IdentityHashMap<Call, Call.Callback> callbacks = new IdentityHashMap<>();
    private final IdentityHashMap<Call, Boolean> handled = new IdentityHashMap<>();
    private record Pending(HeadCallConfirmation<Call> confirmation,BooleanSupplier valid,
                           java.util.function.Consumer<HeadCallConfirmation.Result> completed){}
    private final IdentityHashMap<Call,Pending> pending=new IdentityHashMap<>();
    private InCallService service;
    private volatile Call ringing;
    private volatile boolean busy;
    private volatile boolean available;

    HeadMotionCalls(ColorPodsModule module, Runnable changed) { this.module = module; this.changed = changed; }

    void install(ClassLoader loader) {
        String[] keys={HostTargets.BT_CALL_CREATE, HostTargets.BT_CALL_DESTROY,
                HostTargets.BT_CALL_ADDED, HostTargets.BT_CALL_REMOVED};
        for(String key:keys)if(HostTargets.methodOrNull(key,loader)==null)
            throw new IllegalStateException("Head motion call hook unavailable: "+key);
        for (String key : keys) {
            var method = HostTargets.methodOrNull(key, loader);
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                InCallService source = (InCallService) chain.getThisObject();
                if (key.equals(HostTargets.BT_CALL_DESTROY)) main.post(() -> detach(source));
                else main.post(() -> observe(source));
                return result;
            });
        }
        available=true;
    }

    private void observe(InCallService source) {
        if (service != source) { detach(service); service = source; }
        inspectPending();
        var calls = source.getCalls();
        for (Call old : callbacks.keySet().toArray(new Call[0])) {
            if (calls.stream().noneMatch(c -> c == old)) {
                old.unregisterCallback(callbacks.remove(old)); handled.remove(old);
            }
        }
        for (Call call : calls) if (!callbacks.containsKey(call)) {
            Call.Callback callback = new Call.Callback() {
                @Override public void onStateChanged(Call c, int state) { update(); }
                @Override public void onDetailsChanged(Call c, Call.Details details) { update(); }
            };
            callbacks.put(call, callback); call.registerCallback(callback, main);
        }
        update();
    }

    private void detach(InCallService source) {
        if (source != service) return;
        for(Pending action:pending.values().toArray(new Pending[0]))complete(action,action.confirmation.cancel());
        callbacks.forEach(Call::unregisterCallback); callbacks.clear(); handled.clear();
        service = null; ringing = null; busy = false; changed.run();
    }

    private void update() {
        inspectPending();
        Call next = null; int count = 0; boolean occupied = false, live = false;
        for (Call call : callbacks.keySet()) {
            int state = call.getState();
            live |= state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING;
            if (state != Call.STATE_RINGING && state != Call.STATE_DISCONNECTED && state != Call.STATE_DISCONNECTING) occupied = true;
            if (state == Call.STATE_RINGING && !call.getDetails().hasProperty(Call.Details.PROPERTY_IS_EXTERNAL_CALL)
                    && !call.getDetails().hasProperty(Call.Details.PROPERTY_SELF_MANAGED)) {
                next = call; count++;
            }
        }
        // Ambiguous/call-waiting cases deliberately remain under manual control.
        busy = live;
        ringing = !occupied && count == 1 && !handled.containsKey(next) ? next : null;
        changed.run();
    }

    Call ringing() { return ringing; }
    boolean busy() { return busy; }
    boolean available() { return available; }

    void perform(Call expected, boolean answer, BooleanSupplier valid,BooleanSupplier stillValid,
                 Runnable dispatched,java.util.function.Consumer<HeadCallConfirmation.Result> completed) {
        main.post(() -> {
            Pending action=null;
            try {
                if (service != null && ringing == expected && expected.getState() == Call.STATE_RINGING
                        && callbacks.containsKey(expected) && !handled.containsKey(expected)
                        && service.getCalls().stream().anyMatch(c -> c == expected) && valid.getAsBoolean()) {
                    handled.put(expected, true); ringing = null;
                    action=new Pending(new HeadCallConfirmation<>(expected,answer,SystemClock.elapsedRealtime()),stillValid,completed);
                    pending.put(expected,action);
                    if (answer) expected.answer(0); else expected.reject(false, null);
                    dispatched.run();
                    Pending waiting=action;main.postDelayed(()->poll(waiting),100);
                    module.info("Head motion: dispatched " + (answer ? "answer" : "reject") + " to ringing call");
                }else completed.accept(HeadCallConfirmation.Result.CANCELLED);
            } catch (RuntimeException failure) {
                module.error("Head motion call action failed", failure);
                if(action!=null)complete(action,HeadCallConfirmation.Result.FAILED);
                else completed.accept(HeadCallConfirmation.Result.FAILED);
            }finally { update(); }
        });
    }
    private void inspectPending(){for(Pending action:pending.values().toArray(new Pending[0]))inspect(action);}
    private void poll(Pending action){
        if(pending.get(action.confirmation.call)!=action)return;
        inspect(action);
        if(pending.get(action.confirmation.call)==action)main.postDelayed(()->poll(action),100);
    }
    private void inspect(Pending action){
        if(pending.get(action.confirmation.call)!=action)return;
        if(!action.valid.getAsBoolean()){complete(action,action.confirmation.cancel());return;}
        Call call=action.confirmation.call;
        HeadCallConfirmation.Observation state=HeadCallConfirmation.Observation.PENDING;
        if(call.getState()==Call.STATE_ACTIVE)state=HeadCallConfirmation.Observation.ACTIVE;
        else if(call.getState()==Call.STATE_DISCONNECTED){
            Call.Details details=call.getDetails();
            DisconnectCause cause=details==null?null:details.getDisconnectCause();
            if(cause!=null&&cause.getCode()==DisconnectCause.REJECTED)state=HeadCallConfirmation.Observation.REJECTED;
            else if(cause!=null&&cause.getCode()!=DisconnectCause.UNKNOWN)state=HeadCallConfirmation.Observation.DISCONNECTED_OTHER;
        }
        HeadCallConfirmation.Result result=action.confirmation.observe(call,state,SystemClock.elapsedRealtime());
        if(result!=HeadCallConfirmation.Result.WAITING)complete(action,result);
    }
    private void complete(Pending action,HeadCallConfirmation.Result result){
        if(!pending.remove(action.confirmation.call,action))return;
        module.info("Head motion call outcome: "+result);
        action.completed.accept(result);
    }
}
