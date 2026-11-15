// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class ListeningModeNoticeTest {
    @Test public void initialSnapshotAndRepeatedReportsStaySilent(){
        ListeningModeNotice state=new ListeningModeNotice();
        assertNull(state.observe(2,100));assertNull(state.observe(2,200));
        var event=state.observe(3,300);
        assertEquals(3,event.mode());assertNull(state.observe(3,400));
        assertEquals(event,state.pending(event.id(),500));
    }
    @Test public void invalidModesCannotEstablishOrChangeTheBaseline(){
        ListeningModeNotice state=new ListeningModeNotice();
        assertNull(state.observe(0,100));assertNull(state.observe(255,200));
        assertNull(state.observe(2,300));assertNull(state.observe(5,400));
        assertNull(state.observe(2,500));assertNotNull(state.observe(4,600));
    }
    @Test public void eachListeningModeHasTheRequestedSubtitle(){
        ListeningModeNotice state=new ListeningModeNotice();state.observe(4,100);
        String[] names={"关闭","降噪","通透","自适应"};
        for(int mode=1;mode<=4;mode++){
            var event=state.observe(mode,100+mode);
            assertEquals("已切换至 "+names[mode-1],ListeningModeNotice.title(event.mode()));
        }
    }
    @Test public void failedDeliveryCanRetryUntilAcknowledged(){
        ListeningModeNotice state=new ListeningModeNotice();state.observe(1,100);
        var event=state.observe(2,200);
        assertEquals(event,state.pending(event.id(),300));assertEquals(event,state.pending(event.id(),400));
        assertTrue(state.acknowledge(event.id(),500));assertNull(state.pending(event.id(),600));
        assertTrue(state.acknowledge(event.id(),700));
        assertNotNull(state.observe(3,800));
    }
    @Test public void rapidChangesInvalidateOldRetriesAndAcknowledgements(){
        ListeningModeNotice state=new ListeningModeNotice();state.observe(1,100);
        var old=state.observe(2,200);var latest=state.observe(3,300);
        assertTrue(latest.id()>old.id());assertNull(state.pending(old.id(),400));
        assertFalse(state.acknowledge(old.id(),400));assertEquals(latest,state.pending(latest.id(),400));
        var back=state.observe(2,500);
        assertNull(state.pending(latest.id(),600));assertEquals(back,state.pending(back.id(),600));
    }
    @Test public void expirationAndDisconnectCancelDeliveryAndResetTheBaseline(){
        ListeningModeNotice state=new ListeningModeNotice();state.observe(1,100);
        var event=state.observe(2,200);
        assertNull(state.pending(event.id(),199));
        assertNotNull(state.pending(event.id(),200+HandoffNotice.MAX_AGE_MS));
        assertNull(state.pending(event.id(),201+HandoffNotice.MAX_AGE_MS));
        assertFalse(state.acknowledge(event.id(),201+HandoffNotice.MAX_AGE_MS));
        var pending=state.observe(3,20000);state.disconnected();
        assertNull(state.pending(pending.id(),20100));assertFalse(state.acknowledge(pending.id(),20100));
        assertNull(state.observe(4,20200));
        assertTrue(state.observe(1,20300).id()>pending.id());
    }
}
