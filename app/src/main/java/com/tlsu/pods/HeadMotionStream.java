// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.HashMap;
import java.util.Map;

/** The earbuds have one motion stream per control connection. Its last consumer stops it. */
final class HeadMotionStream {
    enum Consumer { GESTURE, SPATIAL }
    interface Writer { void send(String address, Object socket, boolean start, boolean alternate) throws Exception; }
    private static final class Session {
        final Object socket;
        final boolean alternate;
        final java.util.EnumSet<Consumer> owners = java.util.EnumSet.noneOf(Consumer.class);
        Session(Object socket,boolean alternate) { this.socket=socket; this.alternate=alternate; }
    }
    private final Map<String,Session> sessions = new HashMap<>();
    private final Writer writer;
    HeadMotionStream(Writer writer) { this.writer=writer; }
    synchronized void change(Consumer owner, String address, Object socket, boolean start, boolean alternate) throws Exception {
        Session s = sessions.get(address);
        if (start) {
            if (s == null || s.socket != socket) {
                writer.send(address, socket, true, alternate);
                s = new Session(socket, alternate);
                sessions.put(address, s);
            }
            s.owners.add(owner);
        } else if (s != null && s.socket == socket && s.owners.contains(owner)) {
            if (s.owners.size() == 1) {
                writer.send(address, socket, false, s.alternate);
                sessions.remove(address);
            } else s.owners.remove(owner);
        }
    }
    synchronized void forget(String address,Object socket) {
        Session s = sessions.get(address);
        if (s != null && s.socket == socket) sessions.remove(address);
    }
}
