// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import static org.junit.Assert.*;
import org.junit.Test;

public class HeadMotionCodecTest {
    @Test public void framesMatchUpstreamWireFormat() {
        assertEquals("04000400170000001000100008a102420b080e10021a0501409c0000", hex(HeadMotionCodec.tracking(true,false)));
        assertEquals("040004001700000010001100087e1002420b084e10021a050100000000", hex(HeadMotionCodec.tracking(false,false)));
        assertEquals("040004001700000010000f000873420b081010021a0501409c0000", hex(HeadMotionCodec.tracking(true,true)));
        assertEquals("040004001700000010000f000875420b081010021a050100000000", hex(HeadMotionCodec.tracking(false,true)));
    }
    @Test public void samplesAreSignedLittleEndianAndCopiedFromReusableBuffer() {
        byte[] packet=motion(-1234,2345);
        var sample=HeadMotionCodec.sample(packet,packet.length,123);
        packet[49]=0;
        assertEquals(-1234,sample.horizontal());assertEquals(2345,sample.vertical());assertEquals(123,sample.at());
    }
    @Test public void configurationRepliesTruncationAndOtherOpcodesAreNotSamples() {
        assertNull(HeadMotionCodec.sample(null,70,0));
        byte[] data=motion(0,0);
        for(int length=0;length<data.length;length++)assertNull(HeadMotionCodec.sample(data,length,0));
        assertNull(HeadMotionCodec.sample(data,data.length+1,0));
        data[5]=1;assertNull(HeadMotionCodec.sample(data,data.length,0));
        data[5]=0;data[4]=9;assertNull(HeadMotionCodec.sample(data,data.length,0));
        assertNull(HeadMotionCodec.sample(HeadMotionCodec.tracking(true,true),27,0));
    }
    @Test public void calibrationOutliersAreRejected() {
        assertNull(HeadMotionCodec.sample(motion(6001,0),79,0));
        assertNull(HeadMotionCodec.sample(motion(0,-32768),79,0));
    }
    @Test public void realWearPacketRequiresAtLeastOneKnownInEarState() {
        byte[] packet={4,0,4,0,6,0,0,2};
        assertEquals(Boolean.TRUE,HeadMotionCodec.worn(packet,8));
        packet[6]=1;assertEquals(Boolean.FALSE,HeadMotionCodec.worn(packet,8));
        packet[7]=0;assertEquals(Boolean.TRUE,HeadMotionCodec.worn(packet,8));
        packet[7]=(byte)255;assertEquals(Boolean.FALSE,HeadMotionCodec.worn(packet,8));
        assertNull(HeadMotionCodec.worn(packet,7));
    }
    private static String hex(byte[] data){return Protocol.hex(data,0,data.length);}
    static byte[] motion(int horizontal,int vertical){return motion(horizontal,vertical,9471);}
    static byte[] motion(int horizontal,int vertical,long counter){
        return motion(horizontal,vertical,counter,false);
    }
    static byte[] motion(int horizontal,int vertical,long counter,boolean nested){
        java.io.ByteArrayOutputStream body=new java.io.ByteArrayOutputStream();
        body.write(8);
        do {int next=(int)(counter&127);counter>>>=7;body.write(next|(counter==0?0:128));}while(counter!=0);
        body.write(16);body.write(3);
        if(nested){body.write(58);body.write(62);body.write(8);body.write(16);body.write(26);body.write(58);}
        else {body.write(26);body.write(60);}
        byte[] record=new byte[60];record[0]=16;record[2]=1;record[11]=3;
        record[30]=(byte)horizontal;record[31]=(byte)(horizontal>>8);
        record[32]=(byte)vertical;record[33]=(byte)(vertical>>8);
        // This counter follows the gyro axes and must never become vertical motion.
        record[34]=8;record[35]=10;
        body.write(record,nested?2:0,nested?58:60);
        byte[] data=new byte[12+body.size()];data[0]=data[2]=4;data[4]=23;data[8]=16;
        data[10]=(byte)body.size();data[11]=(byte)(body.size()>>8);
        System.arraycopy(body.toByteArray(),0,data,12,body.size());return data;
    }
    @Test public void capturedReportDoesNotTreatFollowingClockAsPitch(){
        byte[] packet=java.util.HexFormat.of().parseHex("04000400170000001000430008ff4910031a3c100001388bb5e31d1c0000030082a5e6cf5c06000000000000000000f6fff4ff0700080a032268dee3ffbf033f05e5ffd8033f013caf843a39cc0000");
        var value=HeadMotionCodec.sample(packet,packet.length,0);
        assertNotNull(value);assertEquals(-12,value.horizontal());assertEquals(7,value.vertical());
    }
    @Test public void variableLengthSequenceDoesNotMoveTheMotionAxes(){
        for(long counter:new long[]{0,127,128,16383,16384,2097151,2097152,Long.MAX_VALUE}){
            byte[] packet=motion(-1234,2345,counter);
            var value=HeadMotionCodec.sample(packet,packet.length,0);
            assertNotNull(value);assertEquals(-1234,value.horizontal());assertEquals(2345,value.vertical());
        }
    }
    @Test public void sensorRecordAndEnvelopeAreValidated(){
        byte[] packet=motion(1000,2000);packet[10]--;assertNull(HeadMotionCodec.sample(packet,packet.length,0));
        packet=motion(1000,2000);packet[16]=2;assertNull(HeadMotionCodec.sample(packet,packet.length,0));
        packet=motion(1000,2000);packet[19]=0;assertNull(HeadMotionCodec.sample(packet,packet.length,0));
        packet=motion(1000,2000);packet[18]=61;assertNull(HeadMotionCodec.sample(packet,packet.length,0));
        packet=motion(1000,2000);packet[12]=0;assertNull(HeadMotionCodec.sample(packet,packet.length,0));
    }
    @Test public void capturedNestedReportUsesSameAxesWithoutItsSensorTypePrefix(){
        byte[] packet=java.util.HexFormat.of().parseHex("040004001700000010004500089b4210033a3e08101a3a0110c761a8ff1e00000300826e75ae1907000000000000000000f4ffffff0700fb11032268def6fd6a032d04f1fd76032a004b8dc339b0120000");
        var value=HeadMotionCodec.sample(packet,packet.length,42);
        assertNotNull(value);assertEquals(-1,value.horizontal());assertEquals(7,value.vertical());assertEquals(42,value.at());
    }
    @Test public void nestedReportAxesSurviveSequenceGrowth(){
        for(long counter:new long[]{0,127,128,16383,16384,2097151,2097152,Long.MAX_VALUE}){
            byte[] packet=motion(-1234,2345,counter,true);
            var value=HeadMotionCodec.sample(packet,packet.length,0);
            assertNotNull(value);assertEquals(-1234,value.horizontal());assertEquals(2345,value.vertical());
        }
    }
    @Test public void nestedSensorTypeLengthMarkersAndTruncationAreValidated(){
        byte[] packet=motion(1000,2000,9471,true);
        for(int length=0;length<packet.length;length++)assertNull(HeadMotionCodec.sample(packet,length,0));
        for(int offset:new int[]{18,20,22,23,32}){
            packet=motion(1000,2000,9471,true);packet[offset]--;
            assertNull("Invalid nested field at "+offset,HeadMotionCodec.sample(packet,packet.length,0));
        }
        packet=motion(6001,0,9471,true);assertNull(HeadMotionCodec.sample(packet,packet.length,0));
        packet=motion(0,-32768,9471,true);assertNull(HeadMotionCodec.sample(packet,packet.length,0));
    }
    @Test public void ambiguousDirectAndNestedSensorReportsAreRejected(){
        byte[] nested=motion(1000,0,9471,true), direct=motion(0,1000);
        byte[] packet=java.util.Arrays.copyOf(nested,nested.length+62);
        packet[nested.length]=26;packet[nested.length+1]=60;
        System.arraycopy(direct,direct.length-60,packet,nested.length+2,60);
        packet[10]=(byte)(packet.length-12);packet[11]=(byte)((packet.length-12)>>8);
        assertNull(HeadMotionCodec.sample(packet,packet.length,0));
    }
}
