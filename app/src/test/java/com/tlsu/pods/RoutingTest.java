package com.tlsu.pods;

import java.util.List;
import java.util.Map;
import org.junit.Test;
import static org.junit.Assert.*;
public class RoutingTest {
    private static final List<Routing.Peer> TWO_HOSTS=List.of(
            new Routing.Peer("self",2,4),new Routing.Peer("mac",2,3));

    private static Routing.PeerPlayback playback(String peer,boolean playing,int category,long sequence){
        return new Routing.PeerPlayback(new Routing.Message(peer,null,false,false,false,playing,category),sequence);
    }

    @Test public void macLivestreamKeepsAudioWhenAndroidStartsPlaying(){
        assertFalse(Routing.canAutomaticallySwitch("self","mac",2,10,TWO_HOSTS,Map.of(),false));
        assertFalse(Routing.canAutomaticallySwitch("self","mac",2,10,TWO_HOSTS,
                Map.of("mac",playback("mac",false,100,11)),false));
    }
    @Test public void macPauseAllowsAutomaticSwitchButResumingCancelsIt(){
        assertTrue(Routing.canAutomaticallySwitch("self",Routing.NO_SOURCE,0,11,TWO_HOSTS,Map.of(),false));
        assertFalse(Routing.canAutomaticallySwitch("self","mac",2,10,TWO_HOSTS,
                Map.of("mac",playback("mac",true,301,12)),false));
    }
    @Test public void gameReturnAcceptsAPauseNewerThanTheBorrowersMediaSource(){
        var paused=Map.of("mac",playback("mac",false,100,11));
        assertTrue(Routing.canAutomaticallySwitch("self","mac",2,10,TWO_HOSTS,paused,true));
        assertFalse(Routing.canAutomaticallySwitch("self","mac",2,12,TWO_HOSTS,paused,true));
        assertFalse(Routing.canAutomaticallySwitch("self","mac",2,12,TWO_HOSTS,
                Map.of("mac",playback("mac",true,301,13)),true));
    }
    @Test public void aFreshPlaybackReportCanProtectTheRouteBeforeSourceChanges(){
        var starting=playback("mac",true,301,11);
        assertFalse(Routing.canAutomaticallySwitch("self",Routing.NO_SOURCE,0,10,TWO_HOSTS,Map.of("mac",starting),false));
        assertTrue(Routing.canAutomaticallySwitch("self",Routing.NO_SOURCE,0,12,TWO_HOSTS,Map.of("mac",starting),false));
    }
    @Test public void aPauseFromADifferentDeviceCannotReleaseTheActiveSource(){
        assertFalse(Routing.canAutomaticallySwitch("self","mac",2,10,TWO_HOSTS,
                Map.of("ipad",playback("ipad",false,100,11)),true));
    }
    @Test public void remoteCallsAlwaysBlockAutomaticSwitch(){
        assertFalse(Routing.canAutomaticallySwitch("self","mac",1,10,TWO_HOSTS,
                Map.of("mac",playback("mac",false,100,11)),true));
        assertFalse(Routing.canAutomaticallySwitch("self",Routing.NO_SOURCE,0,12,TWO_HOSTS,
                Map.of("mac",playback("mac",true,501,11)),false));
    }
    @Test public void reconnectRequiresFreshEvidenceOfAnIdleRoute(){
        assertFalse(Routing.canAutomaticallySwitch("self",null,0,0,null,Map.of(),false));
        assertFalse(Routing.canAutomaticallySwitch("self",null,0,0,TWO_HOSTS,Map.of(),false));
        assertTrue(Routing.canAutomaticallySwitch("self",null,0,0,TWO_HOSTS,
                Map.of("mac",playback("mac",false,100,1)),false));
        assertTrue(Routing.canAutomaticallySwitch("self",Routing.NO_SOURCE,0,1,TWO_HOSTS,Map.of(),false));
    }
    @Test public void disconnectedDevicesDoNotBlockTheCurrentRoute(){
        var records=List.of(new Routing.Peer("self",2,4),new Routing.Peer("ipad",0,1));
        assertTrue(Routing.canAutomaticallySwitch("self",null,0,0,records,
                Map.of("ipad",playback("ipad",true,501,1)),false));
    }

    @Test public void threeHostRoutingUsesTheActualSourceRegardlessOfRecordOrder(){
        var peers=java.util.List.of(new Routing.Peer("phone",2,1),new Routing.Peer("mac",2,3),new Routing.Peer("self",2,1));
        assertEquals("mac",Routing.target("self",null,null,peers));
        assertEquals("phone",Routing.target("self",null,"phone",peers));
        assertEquals("phone",Routing.target("self","phone","mac",peers));
    }
    @Test public void anInactiveThirdHostCannotBecomeTheTakeoverTarget(){
        var peers=java.util.List.of(new Routing.Peer("phone",0,3),new Routing.Peer("mac",2,1));
        assertEquals("mac",Routing.target("self",null,"phone",peers));
        assertThrows(IllegalArgumentException.class,()->Routing.target("self","phone",null,peers));
    }
    @Test public void multipleIdleHostsNeedDiscoveryInsteadOfAnArbitraryFirstTarget(){
        var peers=java.util.List.of(new Routing.Peer("phone",2,1),new Routing.Peer("mac",2,1));
        assertNull(Routing.target("self",null,null,peers));
    }
    @Test public void resumingAfterPauseKeepsTheSelectedAndroidRoute(){
        var peers=java.util.List.of(new Routing.Peer("self",2,6),new Routing.Peer("mac",2,1));
        assertEquals("self",Routing.selectedHost(peers));
        assertFalse(Routing.needsReclaim("self","00:00:00:00:00:00",0,false,peers));
        assertTrue(Routing.needsReclaim("self","mac",2,false,peers));
        assertTrue(Routing.needsReclaim("self",null,0,true,peers));
    }
    @Test public void anInactivePeerCannotTriggerReclaimWhenTheSourceGoesIdle(){
        var peers=java.util.List.of(new Routing.Peer("self",2,4),new Routing.Peer("mac",0,3));
        assertNull(Routing.selectedHost(peers));
        assertFalse(Routing.needsReclaim("self",null,0,false,peers));
    }
    @Test public void anIdleLocalSourceKeepsItsRouteInTheAbsenceOfAnotherOwner(){
        var unselected=java.util.List.of(new Routing.Peer("self",2,4),new Routing.Peer("mac",2,1));
        var remoteSelected=java.util.List.of(new Routing.Peer("self",2,4),new Routing.Peer("mac",2,3));
        assertFalse(Routing.needsReclaim("self","self",0,false,unselected));
        assertTrue(Routing.needsReclaim("self","self",0,true,unselected));
        assertTrue(Routing.needsReclaim("self","self",0,false,remoteSelected));
    }
    @Test public void resumingCanReclaimFromASelectedRemoteHostEvenWhenItIsIdle(){
        var peers=java.util.List.of(new Routing.Peer("self",2,5),new Routing.Peer("mac",2,3));
        assertEquals("mac",Routing.selectedHost(peers));
        assertTrue(Routing.needsReclaim("self",null,0,false,peers));
        assertFalse(Routing.needsReclaim("self","self",2,true,peers));
    }
    @Test public void anIdleRouteDoesNotEraseAnExplicitCallReport(){
        var call=new Routing.Message("peer","Mac",false,false,false,true,501);
        var transition=new Routing.Message("peer","Mac",false,false,false,true,100);
        var stopped=new Routing.Message("peer","Mac",false,false,false,false,100);
        assertTrue(call.decisive());assertTrue(call.call());assertFalse(transition.decisive());
        assertTrue(stopped.decisive());assertFalse(stopped.call());
    }
    @Test public void routingMessagesKeepDictionaryKeysAndDoNotReadTheFollowingFrame() {
        String peer="AA:BB:CC:DD:EE:FF",self="11:22:33:44:55:66";
        byte[] nearby=Routing.showNearby(peer);assertEquals(nearby.length-14,(nearby[12]&255)+((nearby[13]&255)<<8));
        assertTrue(new String(nearby,java.nio.charset.StandardCharsets.ISO_8859_1).contains("localscore"));
        assertTrue(Routing.streamingInfo(self,peer,true).length>15);
        byte[] reverse=Routing.reverse(peer);reverse[4]=0x11;
        Routing.Message message=Routing.response(reverse,reverse.length);assertEquals(peer,message.sender());assertTrue(message.relinquish());assertTrue(message.reversed());
        byte[] first=Routing.mediaInfo(self,peer);first[4]=0x11;
        byte[] coalesced=new byte[first.length+reverse.length];System.arraycopy(first,0,coalesced,0,first.length);System.arraycopy(reverse,0,coalesced,first.length,reverse.length);
        assertFalse(Routing.response(coalesced,coalesced.length).relinquish());
        assertThrows(IllegalArgumentException.class,()->Routing.response(reverse,reverse.length-1));
    }
    @Test public void anUnknownPeerStreamingStateMustNotPretendToBeAPause(){
        byte[] body=Opack.encode(java.util.Map.of("hostStreamingState","UNKNOWN"));
        byte[] packet=java.util.Arrays.copyOf(Routing.mediaInfo("11:22:33:44:55:66","AA:BB:CC:DD:EE:FF"),15+body.length);
        packet[4]=0x11;packet[12]=(byte)(body.length+1);packet[13]=(byte)((body.length+1)>>8);
        System.arraycopy(body,0,packet,15,body.length);
        var message=Routing.response(packet,packet.length);assertNull(message.playing());assertFalse(message.decisive());
    }
    @Test public void framesHaveUpstreamSizesAndTargetByteOrder() {
        String self="11:22:33:44:55:66",peer="AA:BB:CC:DD:EE:FF";
        assertEquals(96,Routing.introduce(self,peer).length);
        assertEquals(122,Routing.mediaInfo(self,peer).length);
        byte[] packet=Routing.takeOver(peer);assertEquals(packet.length-14,(packet[12]&255)+((packet[13]&255)<<8));
        assertEquals(peer,Routing.mac(packet,6,true));assertEquals(0x10,packet[4]);
        assertThrows(IllegalArgumentException.class,()->Routing.takeOver("invalid"));
    }
    @Test public void truncatedPeerListsNeverExposePartialAddresses() {
        byte[] packet=new byte[17];packet[0]=4;packet[2]=4;packet[4]=0x2e;packet[8]=1;
        for(int i=0;i<6;i++)packet[9+i]=(byte)(i+1);
        assertEquals("01:02:03:04:05:06",Routing.peers(packet,17).get(0));
        for(int n=0;n<17;n++){final int size=n;assertThrows(IllegalArgumentException.class,()->Routing.peers(packet,size));}
        packet[8]=(byte)255;assertThrows(IllegalArgumentException.class,()->Routing.peers(packet,17));
    }
}
