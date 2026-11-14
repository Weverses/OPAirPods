package com.tlsu.pods;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class OpackTest {
    private static byte[] hex(String s){return java.util.HexFormat.of().parseHex(s);}
    @Test public void decodesIndependentTypedStringFixtures(){
        assertEquals("YES",Opack.decode(hex("e152686f737453747265616d696e67537461746543594553")).get("hostStreamingState"));
        assertEquals("Android",Opack.decode(hex("e14662744e616d6547416e64726f6964")).get("btName"));
        assertThrows(IllegalArgumentException.class,()->Opack.decode(hex("e152686f737453747265616d696e67537461746542594553")));
    }
    @Test public void stringsUseUtf8ByteLengthsAndFalseRemainsFalse(){
        Map<String,Object> input=new LinkedHashMap<>();input.put("btName","耳机");input.put("audioRoutingSetOwnershipToFalse",false);
        assertEquals(input,Opack.decode(Opack.encode(input)));
        byte[] p=Routing.takeOver("AA:BB:CC:DD:EE:FF");p[4]=0x11;
        assertTrue(Routing.response(p,p.length).relinquish());
    }
    @Test public void allRoutingFramesDecodeWithCompleteTypedBodies(){
        String self="11:22:33:44:55:66",target="AA:BB:CC:DD:EE:FF";
        for(boolean playing:new boolean[]{false,true}){
            byte[] frame=Routing.streamingInfo(self,target,playing);Map<String,Object> body=Opack.decode(Arrays.copyOfRange(frame,15,frame.length));
            assertEquals(playing?"YES":"NO",body.get("hostStreamingState"));assertEquals("Android",body.get("btName"));assertEquals(playing?301L:100L,body.get("otherDeviceAudioCategory"));
            assertEquals(frame.length-14,(frame[12]&255)+((frame[13]&255)<<8));
        }
    }
}
