package com.tlsu.pods;
import org.junit.Test;
import static org.junit.Assert.*;

public class ProtocolTest {
    @Test public void requestsTheCompleteLinuxReferenceNotificationMask(){
        assertArrayEquals(new byte[]{4,0,4,0,15,0,-1,-1,-1,-1,-1},Protocol.requestState());
    }
    @Test public void matchesKnownUpstreamFrame() {
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x1b,1,0,0,0}, Protocol.control(0x1b,new byte[]{1}));
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x2c,1,2,0,0}, Protocol.control(0x2c,new byte[]{1,2}));
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x23,0,0,0,0},Protocol.control(0x23,new byte[]{0}));
    }
    @Test public void rejectsUnexposedAndMalformedCommands() {
        for (int command : new int[]{0x06,0x0d,0x39,0xff}) {
            assertThrows(IllegalArgumentException.class, () -> Protocol.control(command,new byte[]{1}));
        }
        assertThrows(IllegalArgumentException.class, () -> Protocol.control(0x2c,new byte[]{1}));
        assertThrows(IllegalArgumentException.class, () -> Protocol.control(0x25,new byte[]{0}));
        assertThrows(IllegalArgumentException.class, () -> Protocol.control(0x23,new byte[]{3}));
        assertThrows(IllegalArgumentException.class, () -> Protocol.control(0x2e,new byte[]{101}));
    }
    @Test public void parserRejectsTruncatedAndUnrelatedFrames() {
        byte[] frame = Protocol.control(0x01,new byte[]{0});
        assertTrue(Protocol.isControl(frame,11));
        for (int len=0;len<11;len++) assertFalse(Protocol.isControl(frame,len));
        assertFalse(Protocol.isControl(frame,12));
        frame[4]=4; assertFalse(Protocol.isControl(frame,11));
        assertFalse(Protocol.matches(new byte[]{1},new byte[]{1,2}));
    }
}
