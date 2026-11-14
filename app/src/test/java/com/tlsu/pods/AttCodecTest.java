package com.tlsu.pods;
import org.junit.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import static org.junit.Assert.*;

public class AttCodecTest {
    @Test public void acceptsObservedUnconfiguredHearingProfileWithoutEnrollingIt() {
        byte[] raw=new byte[104];raw[0]=2;raw[2]=0x60;
        byte[] updated=AttCodec.patch(0x2a,raw,"tone",0.5f);
        assertEquals(0,updated[1]);assertEquals(0x64,updated[2]);
        assertEquals(0.5f,AttCodec.value(0x2a,updated,"tone"),0);
    }
    @Test public void preservesAudiogramAndUnknownBytes() {
        byte[] original = new byte[104]; for (int i=0;i<original.length;i++) original[i]=(byte)(i+1);
        original[0]=2; original[1]=2; original[2]=0x60; original[3]=0;
        byte[] updated=AttCodec.patch(0x2a,original,"tone",0.75f);
        assertEquals(0.75f,ByteBuffer.wrap(updated).order(ByteOrder.LITTLE_ENDIAN).getFloat(40),0);
        assertEquals(0.75f,ByteBuffer.wrap(updated).order(ByteOrder.LITTLE_ENDIAN).getFloat(88),0);
        for(int i=0;i<104;i++) if(i!=2 && !(i>=40&&i<44) && !(i>=88&&i<92)) assertEquals(original[i],updated[i]);
        assertEquals(0x60,original[2]); assertEquals(0x64,updated[2]);
    }
    @Test public void acceptsBothTransparencyLayoutsButNeverInventsMissingFields() {
        for(int len:new int[]{100,104}) {
            byte[] data=new byte[len]; byte[] updated=AttCodec.patch(0x18,data,"enabled",1);
            assertEquals(len,updated.length); assertEquals(1f,AttCodec.value(0x18,updated,"enabled"),0);
        }
        assertThrows(IllegalArgumentException.class,()->AttCodec.patch(0x18,new byte[100],"voice",1));
        assertThrows(IllegalArgumentException.class,()->AttCodec.patch(0x18,new byte[99],"tone",1));
        assertThrows(IllegalArgumentException.class,()->AttCodec.patch(0x2a,new byte[104],"tone",1));
    }
    @Test public void rejectsNonFiniteOutOfRangeAndUnknownWrites() {
        for(float value:new float[]{Float.NaN,Float.POSITIVE_INFINITY,-0.1f,1.1f})
            assertThrows(IllegalArgumentException.class,()->AttCodec.patch(0x18,new byte[100],"tone",value));
        assertThrows(IllegalArgumentException.class,()->AttCodec.patch(0x18,new byte[100],"eq",0.5f));
        assertThrows(IllegalArgumentException.class,()->AttCodec.patch(0x18,new byte[100],"boost",0.5f));
        assertThrows(IllegalArgumentException.class,()->AttCodec.patch(0x1b,new byte[]{1},"enabled",2));
    }
}
