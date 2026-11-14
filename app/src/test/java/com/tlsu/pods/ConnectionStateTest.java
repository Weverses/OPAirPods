// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectionStateTest {
    @Test public void headsetDisconnectDoesNotHideConnectedMedia(){
        assertEquals(ConnectionState.CONNECTED,ConnectionState.resolve(true,false,2,0));
    }
    @Test public void mediaDisconnectDoesNotHideConnectedHeadset(){
        assertEquals(ConnectionState.CONNECTED,ConnectionState.resolve(true,false,0,2));
    }
    @Test public void aControlOnlyAclIsStillARealConnection(){
        assertEquals(ConnectionState.CONNECTED,ConnectionState.resolve(true,true,0,0));
    }
    @Test public void bluetoothOffOverridesStaleProfileStates(){
        assertEquals(ConnectionState.DISCONNECTED,ConnectionState.resolve(false,true,2,2));
    }
    @Test public void anActualDisconnectReplacesAConnectedCache(){
        int state=ConnectionState.resolve(true,false,0,0);
        assertEquals("STATE_DISCONNECTED",ConnectionState.reconcile("STATE_CONNECTED",state));
    }
    @Test public void aReconnectRepairsAnUninitializedHost(){
        int state=ConnectionState.resolve(true,true,null,null);
        assertEquals("STATE_CONNECTED",ConnectionState.reconcile("STATE_NONE",state));
    }
    @Test public void unavailableQueriesDoNotFabricateADisconnect(){
        int state=ConnectionState.resolve(true,null,null,null);
        assertEquals(ConnectionState.UNKNOWN,state);
        assertEquals("STATE_CONNECTED",ConnectionState.reconcile("STATE_CONNECTED",state));
    }
    @Test public void anUnfinishedConnectionIsNotCancelledByPolling(){
        assertEquals("STATE_START_BOND",ConnectionState.reconcile("STATE_START_BOND",ConnectionState.DISCONNECTED));
        assertEquals("STATE_BONDING",ConnectionState.reconcile("STATE_BONDING",ConnectionState.UNKNOWN));
    }
    @Test public void aCorrectCacheStillRepairsAnOldDisconnectedBanner(){
        String state=ConnectionState.reconcile("STATE_CONNECTED",ConnectionState.CONNECTED);
        assertTrue(ConnectionState.needsRender(ConnectionState.CONNECTED,state,true,true));
        assertTrue(ConnectionState.needsRender(ConnectionState.CONNECTED,state,false,true));
    }
    @Test public void anAlreadyCorrectBannerDoesNotRestartBatteryRendering(){
        assertFalse(ConnectionState.needsRender(ConnectionState.CONNECTED,"STATE_CONNECTED",false,false));
        assertFalse(ConnectionState.needsRender(ConnectionState.DISCONNECTED,"STATE_DISCONNECTED",true,true));
    }
    @Test public void unknownEvidenceDoesNotRewriteTheVisibleBanner(){
        assertFalse(ConnectionState.needsRender(ConnectionState.UNKNOWN,"STATE_CONNECTED",true,true));
        assertTrue(ConnectionState.needsRender(ConnectionState.DISCONNECTED,"STATE_DISCONNECTED",false,false));
    }
}
