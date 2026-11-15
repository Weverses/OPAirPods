// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class HandoffCardPolicyTest {
    @Test public void cachedHiddenCardDoesNotBlockRoutingNotice(){
        assertFalse(new HandoffCardPolicy().defer(new Object(),false,false,true,"connected",100));
    }
    @Test public void visibleConnectionCardCannotStarveMacHandoffForEntireEventLifetime(){
        HandoffNotice notice=new HandoffNotice();notice.selectedLocal("phone",100);
        var event=notice.observe("phone","mac",2,200);
        HandoffCardPolicy policy=new HandoffCardPolicy();HandoffDelivery delivery=new HandoffDelivery();Object card=new Object();
        boolean accepted=false;
        for(long now=800;now<event.at()+HandoffNotice.MAX_AGE_MS;now+=HandoffNotice.RETRY_MS){
            long token=delivery.begin(event.id(),event.at()+HandoffNotice.MAX_AGE_MS,now);
            if(token==0)continue;
            boolean defer=policy.defer(card,true,false,true,"connected",now);
            delivery.finish(event.id(),token,!defer,now);
            if(!defer){accepted=notice.acknowledge(event.id(),now);break;}
        }
        assertTrue(accepted);assertNull(notice.pending(event.id(),4000));
    }
    @Test public void anotherDevicesVisibleCardIsProtected(){
        HandoffCardPolicy policy=new HandoffCardPolicy();Object card=new Object();
        assertTrue(policy.defer(card,true,false,false,"connected",100));
        assertTrue(policy.defer(card,true,false,false,"connected",20000));
    }
    @Test public void visibleLowBatteryWarningIsProtectedEvenForSameEarbuds(){
        HandoffCardPolicy policy=new HandoffCardPolicy();Object card=new Object();
        assertTrue(policy.defer(card,true,false,true,"lowBattery",100));
        assertTrue(policy.defer(card,true,false,true,"lowBattery",20000));
    }
    @Test public void unknownVisibleCardIsProtected(){
        HandoffCardPolicy policy=new HandoffCardPolicy();Object card=new Object();
        assertTrue(policy.defer(card,true,false,true,"future-type",100));
        assertTrue(policy.defer(card,true,false,true,"future-type",20000));
    }
    @Test public void moduleCardAndEmptySlotDoNotDelayANewerRoute(){
        HandoffCardPolicy policy=new HandoffCardPolicy();
        assertFalse(policy.defer(new Object(),true,true,true,"setActive",100));
        assertFalse(policy.defer(null,false,false,false,null,200));
    }
    @Test public void newlyArrivingConnectionCardReceivesItsOwnGracePeriod(){
        HandoffCardPolicy policy=new HandoffCardPolicy();Object first=new Object(),second=new Object();
        assertTrue(policy.defer(first,true,false,true,"connected",100));
        assertFalse(policy.defer(first,true,false,true,"connected",2100));
        assertTrue(policy.defer(second,true,false,true,"airpods_max_connected",2200));
        assertFalse(policy.defer(second,true,false,true,"airpods_max_connected",4200));
    }
}
