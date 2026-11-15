// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** A cached native card is not necessarily visible. Connection confirmations
 * for the same earbuds may yield to a newer, confirmed routing change. */
final class HandoffCardPolicy {
    static final long CONNECTION_GRACE_MS=2000;
    private Object blocker;
    private long since;

    boolean defer(Object card,boolean showing,boolean owned,boolean sameDevice,String kind,long now){
        if(card==null||!showing||owned){blocker=null;return false;}
        if(card!=blocker){blocker=card;since=now;}
        boolean connection="connected".equals(kind)||"airpods_max_connected".equals(kind);
        return !sameDevice||!connection||now-since<CONNECTION_GRACE_MS;
    }
}
