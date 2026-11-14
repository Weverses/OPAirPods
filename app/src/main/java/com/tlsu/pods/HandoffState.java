// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.HashMap;
import java.util.Map;

/** Per-earbud ownership, independent of A2DP connection and playback state. */
final class HandoffState {
    record Ticket(String address,int generation) {}
    private static final class State {int owns=-1,generation,recoveries;boolean remote,claiming;}
    private final Map<String,State> devices=new HashMap<>();
    private State state(String address){return devices.computeIfAbsent(address,k->new State());}
    synchronized Ticket ownership(String address,int value){
        State s=state(address);int previous=s.owns;s.owns=value;
        if(s.claiming)return null;
        if(value==1){s.remote=false;s.generation++;return null;}
        return previous==1?remote(address):null;
    }
    synchronized Ticket remote(String address){State s=state(address);if(s.remote||s.claiming)return null;s.remote=true;return new Ticket(address,++s.generation);}
    synchronized void local(String address){State s=state(address);s.remote=false;s.claiming=false;s.generation++;s.recoveries=0;}
    synchronized Ticket beginClaim(String address){State s=state(address);s.claiming=true;s.remote=false;return new Ticket(address,++s.generation);}
    synchronized boolean claiming(String address){return state(address).claiming;}
    synchronized boolean claiming(Ticket ticket){State s=state(ticket.address());return s.claiming&&s.generation==ticket.generation();}
    synchronized Ticket finishClaim(String address,boolean success){State s=state(address);s.claiming=false;if(success){local(address);return null;}return remote(address);}
    synchronized Ticket finishClaim(Ticket ticket,boolean success){return claiming(ticket)?finishClaim(ticket.address(),success):null;}
    synchronized void disconnected(String address){State s=state(address);if(s.claiming)s.remote=true;s.claiming=false;s.owns=-1;s.generation++;}
    synchronized boolean current(Ticket ticket){State s=state(ticket.address());return s.remote&&s.generation==ticket.generation();}
    synchronized boolean remoteOwns(String address){return state(address).remote;}
    synchronized int recovery(String address){State s=state(address);return s.remote&&s.recoveries<3?++s.recoveries:0;}
}
