package com.tlsu.pods;
import org.junit.Test;
import static org.junit.Assert.*;
public class RoutingTest {
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
