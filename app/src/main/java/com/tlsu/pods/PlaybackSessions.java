// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Playback credentials belong to one connection lifetime, including queued work. */
final class PlaybackSessions<T> {
    static final class Session<T> {
        private final String address;
        private List<T> paused=List.of();
        private Session(String address){this.address=address;}
    }
    private final Map<String,Session<T>> sessions=new HashMap<>();

    synchronized Session<T> capture(String address){
        return sessions.computeIfAbsent(address,Session::new);
    }
    synchronized boolean current(Session<T> session){
        return sessions.get(session.address)==session;
    }
    synchronized List<T> paused(Session<T> session){
        return current(session)?session.paused:List.of();
    }
    synchronized List<T> pause(Session<T> session,Supplier<List<T>> action){
        if(!current(session))return List.of();
        List<T> paused=List.copyOf(action.get());
        if(!paused.isEmpty())session.paused=paused;
        return paused;
    }
    // Serialize the media command itself with invalidation, not only its cache write.
    synchronized boolean runIfCurrent(Session<T> session,BooleanSupplier valid,Runnable action){
        if(!current(session)||!valid.getAsBoolean())return false;
        action.run();return true;
    }
    synchronized void clear(Session<T> session){
        session.paused=List.of();
    }
    synchronized void disconnected(String address){
        Session<T> ended=sessions.remove(address);
        if(ended!=null)ended.paused=List.of();
    }
}
