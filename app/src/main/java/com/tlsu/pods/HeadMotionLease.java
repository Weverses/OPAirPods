// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Identity, rather than equality, ties delayed work to one connection and call. */
final class HeadMotionLease {
    final String address, owner;
    final Object connection, call;
    final long expires;
    private boolean active = true, consumed;

    HeadMotionLease(String address, Object connection, Object call, String owner, long expires) {
        this.address = address; this.connection = connection; this.call = call;
        this.owner = owner; this.expires = expires;
    }
    synchronized boolean valid(Object connection, Object call, long now) {
        return !consumed && current(connection,call,now);
    }
    synchronized boolean current(Object connection,Object call,long now){return active&&this.connection==connection&&this.call==call&&now<expires;}
    synchronized boolean consume(Object connection, Object call, long now) {
        if (!valid(connection, call, now)) return false;
        consumed = true; return true;
    }
    synchronized void cancel() { active = false; }
    static boolean answers(int gesture, boolean reversed) {
        if (gesture != HeadGestureDetector.NOD && gesture != HeadGestureDetector.SHAKE)
            throw new IllegalArgumentException("未知头部动作");
        return (gesture == HeadGestureDetector.NOD) != reversed;
    }
}
