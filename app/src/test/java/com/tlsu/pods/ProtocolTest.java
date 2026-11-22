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
    @Test public void encodesUpstreamChimeCallAndChargingFrames(){
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x1f,70,0x50,0,0},Protocol.control(0x1f,new byte[]{70,0x50}));
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x24,0,3,0,0},Protocol.control(0x24,new byte[]{0,3}));
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x24,0,2,0,0},Protocol.control(0x24,new byte[]{0,2}));
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x3b,1,0,0,0},Protocol.control(0x3b,new byte[]{1}));
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x3b,2,0,0,0},Protocol.control(0x3b,new byte[]{2}));
        assertArrayEquals(new byte[]{4,0,4,0,9,0,0x1a,6,0,0,0},Protocol.control(0x1a,new byte[]{6}));
    }
    @Test public void chimeVolumeRequiresTheFixedSecondByteAndAnUnsignedPercentage(){
        for(int volume:new int[]{0,100})Protocol.validate(0x1f,new byte[]{(byte)volume,0x50});
        for(byte[] value:new byte[][]{{70},{70,0},{70,0x50,0},{101,0x50},{-1,0x50},{}})
            assertThrows(IllegalArgumentException.class,()->Protocol.control(0x1f,value));
    }
    @Test public void callConfigurationCannotBeMistakenForASingleByteSwitch(){
        for(byte[] value:new byte[][]{{1},{2},{3},{0,1},{0,0},{1,2},{0,3,0},{0,-1}})
            assertThrows(IllegalArgumentException.class,()->Protocol.control(0x24,value));
    }
    @Test public void chargingOnlyAcceptsTheUpstreamBooleanValues(){
        for(byte[] value:new byte[][]{{0},{3},{-1},{1,0},{}})
            assertThrows(IllegalArgumentException.class,()->Protocol.control(0x3b,value));
    }
    @Test public void onlyKnownCombinationsWithAtLeastTwoModesCanCrossTheBridge(){
        java.util.Set<Integer> valid=java.util.Set.of(3,5,6,7,9,10,11,12,13,14,15);
        for(int mask=0;mask<256;mask++){
            byte[] value={(byte)mask};
            if(valid.contains(mask))Protocol.validate(0x1a,value);
            else assertThrows(IllegalArgumentException.class,()->Protocol.control(0x1a,value));
        }
        assertThrows(IllegalArgumentException.class,()->Protocol.control(0x1a,new byte[]{6,0}));
    }
    @Test public void paddedReportsUseTheSameValuesAsTheSettingsSelections(){
        assertArrayEquals(new byte[]{70,0x50},Protocol.reportedValue(0x1f,new byte[]{70,0x50,0,0}));
        assertArrayEquals(new byte[]{0,2},Protocol.reportedValue(0x24,new byte[]{0,2,0,0}));
        assertArrayEquals(new byte[]{6},Protocol.reportedValue(0x1a,new byte[]{6,0,0,0}));
        assertArrayEquals(new byte[]{1},Protocol.reportedValue(0x3b,new byte[]{1,0,0,0}));
        assertArrayEquals(new byte[]{0,3},Protocol.reportedValue(0x24,new byte[]{0,3}));
    }
    @Test public void missingTruncatedOrMalformedReportsStayUnknown(){
        assertNull(Protocol.reportedValue(0x1f,null));assertNull(Protocol.reportedValue(0x1f,new byte[]{70}));
        assertNull(Protocol.reportedValue(0x1f,new byte[]{70,0}));assertNull(Protocol.reportedValue(0x24,new byte[]{0}));
        assertNull(Protocol.reportedValue(0x24,new byte[]{0,0,0,0}));assertNull(Protocol.reportedValue(0x3b,new byte[]{0,0,0,0}));
        assertNull(Protocol.reportedValue(0x1a,new byte[]{2,0,0,0}));assertNull(Protocol.reportedValue(0xff,new byte[]{1}));
    }
    @Test public void rejectsUnexposedAndMalformedCommands() {
        for (int command : new int[]{0x06,0x0d,0x28,0x39,0xff}) {
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
