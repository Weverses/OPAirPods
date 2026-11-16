// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.HashMap;
import java.util.Map;

/** Per-earbud ownership, independent of A2DP connection and playback state. */
final class HandoffState {
    record Ticket(String address,int generation) {}
    private static final class State {int owns=-1,generation,recoveries;boolean remote,claiming,armed,selected,restoreRemote;}
    private final Map<String,State> devices=new HashMap<>();
    private State state(String address){return devices.computeIfAbsent(address,k->new State());}
    synchronized Ticket ownership(String address,int value){
        State s=state(address);int previous=s.owns;s.owns=value;
        if(s.claiming)return value==0&&s.selected?requestRemote(address):null;
        return value==0&&previous==1?remote(address):null;
    }
    synchronized Ticket remote(String address){State s=state(address);if(s.remote||s.claiming)return null;s.remote=true;return new Ticket(address,++s.generation);}
    /** A new routed yield/Reverse is an intent, not a possibly delayed source snapshot. */
    synchronized Ticket requestRemote(String address){State s=state(address);if(s.remote&&!s.claiming)return null;s.claiming=false;s.selected=false;s.remote=true;return new Ticket(address,++s.generation);}
    synchronized Ticket source(String address,boolean local,int type){
        State s=state(address);
        if(local){
            if(s.claiming){
                s.selected=s.armed;
                // Actual local audio may arrive before our OWN write. A later
                // pause must not roll that already-selected route back to a peer.
                if(type!=0)s.restoreRemote=false;
            }else if(type!=0)local(address);
            return null;
        }
        if(type==0)return null;
        if(s.claiming){
            if(s.selected)return requestRemote(address);
            s.restoreRemote=true;return null;
        }
        return remote(address);
    }
    synchronized void local(String address){State s=state(address);s.remote=false;s.claiming=false;s.generation++;s.recoveries=0;}
    synchronized Ticket beginClaim(String address){State s=state(address);if(!s.claiming)s.restoreRemote=s.remote;else if(s.selected)s.restoreRemote=false;s.claiming=true;s.armed=false;s.selected=false;s.remote=false;return new Ticket(address,++s.generation);}
    synchronized void arm(Ticket ticket){if(claiming(ticket)){State s=state(ticket.address());s.armed=true;s.selected=false;}}
    synchronized void cancel(String address){State s=state(address);if(s.claiming)s.remote=s.restoreRemote&&!s.selected;s.claiming=false;s.selected=false;s.generation++;}
    synchronized boolean claiming(String address){return state(address).claiming;}
    synchronized boolean claiming(Ticket ticket){State s=state(ticket.address());return s.claiming&&s.generation==ticket.generation();}
    synchronized Ticket finishClaim(String address,boolean success){
        State s=state(address);s.claiming=false;
        // A failed/cancelled playback request is not a remote takeover. Restore
        // a peer only if it held the route and no local selection superseded it.
        if(success||s.selected||!s.restoreRemote){local(address);return null;}
        return remote(address);
    }
    synchronized Ticket finishClaim(Ticket ticket,boolean success){return claiming(ticket)?finishClaim(ticket.address(),success):null;}
    synchronized void disconnected(String address){State s=state(address);s.owns=-1;if(s.claiming&&!s.armed){s.selected=false;return;}if(s.claiming)s.remote=s.restoreRemote&&!s.selected;s.selected=false;s.claiming=false;s.generation++;}
    synchronized boolean preparing(Ticket ticket){return claiming(ticket)&&!state(ticket.address()).armed;}
    synchronized boolean current(Ticket ticket){State s=state(ticket.address());return s.remote&&s.generation==ticket.generation();}
    synchronized boolean remoteOwns(String address){return state(address).remote;}
    synchronized Ticket recoveryTicket(String address){State s=state(address);return s.remote?new Ticket(address,s.generation):null;}
    synchronized int recovery(String address){State s=state(address);return s.remote&&s.recoveries<3?++s.recoveries:0;}
}
