package com.tlsu.pods;

import static org.junit.Assert.*;
import org.junit.Test;

public class HeadOrientationTest {
    private static final String PLAYBACK_NESTED =
            "04000400170000001000450008803a10013a3e08101a3a01584668153b300000030082495ba61b01000000000000000000fbff0500fdfff186030000007000e703b3046f00ed03b000c7524d3855aa0000";
    private static final String HANDOFF_DIRECT =
            "04000400170000001000430008e45710011a3c100001a042684c3c31000003008222307f5d010000000000000000000000fdfff9ff818d818d818dd900a9035c05dc00aa035e019ad2da38659d0000";

    @Test public void acceptsCapturedStereoPlaybackEnvelopeAndWaitsForFusion() {
        // Second report after subscribing on OPD2515 during actual 48 kHz stereo playback.
        byte[] packet = java.util.HexFormat.of().parseHex(PLAYBACK_NESTED);
        int payload = HeadMotionCodec.payloadOffset(packet, packet.length);
        assertEquals(23, payload);
        assertNull(HeadOrientation.decode(packet, packet.length)); // startup flag 0x82
        assertNull(HeadMotionCodec.sample(packet, packet.length, 0)); // no change to call gestures
        packet[payload + 11] = (byte) 0x83;
        packet[payload + 21] = 0x40; // controlled unit quaternion component
        assertEquals(.5, HeadOrientation.decode(packet, packet.length).x(), 0);
        packet[16] = 2; // unobserved outer variant
        assertNull(HeadOrientation.decode(packet, packet.length));
        packet[16] = 1;
        packet[20] = 17; // different inner sensor
        assertNull(HeadOrientation.decode(packet, packet.length));
    }

    @Test public void acceptsFormatChangeAfterHandoffAndWaitsForFreshPose() {
        HeadOrientation head = new HeadOrientation();
        byte[] before = java.util.HexFormat.of().parseHex(PLAYBACK_NESTED);
        byte[] after = java.util.HexFormat.of().parseHex(HANDOFF_DIRECT);
        int payload = HeadMotionCodec.payloadOffset(after, after.length);
        assertEquals(21, payload);
        assertNull(HeadOrientation.decode(after, after.length)); // captured startup flag 0x82
        assertNull(HeadMotionCodec.sample(after, after.length, 0));

        // Supply fused samples around the gap using the two captures' actual clocks and layouts.
        before[23 + 11] = (byte) 0x83;
        HeadOrientation.Rotation first = head.accept(HeadOrientation.decode(before, before.length), PERIOD);
        after[payload + 11] = (byte) 0x83;
        after[payload + 21] = (byte) 0xc0;
        HeadOrientation.Pose returned = HeadOrientation.decode(after, after.length);
        assertEquals(-.5, returned.x(), 0);
        HeadOrientation.Rotation centered = head.accept(returned, 2 * PERIOD);
        assertEquals(0, centered.z());
        assertNotEquals(first.reset(), centered.reset());

        after[19] = 17; // different sensor inside the direct record
        assertNull(HeadOrientation.decode(after, after.length));
    }
    private static final long PERIOD=40_144_000;
    private static HeadOrientation.Pose pose(int n,double angle) {
        double half=Math.toRadians(angle)/2, v=Math.sin(half);
        return new HeadOrientation.Pose(n*PERIOD,Math.cos(half),.603642*v,-.643307*v,-.470927*v);
    }
    @Test public void signedTurnsHoldAndExplicitRecenter() {
        HeadOrientation yaw=new HeadOrientation();
        HeadOrientation.Rotation zero=yaw.accept(pose(1,15),PERIOD);
        assertEquals(0,zero.z());
        assertEquals(-30,180.0*yaw.accept(pose(2,-15),2*PERIOD).z()/32767,.02);
        assertEquals(-30,180.0*yaw.accept(pose(3,-15),3*PERIOD).z()/32767,.02);
        assertEquals(30,180.0*yaw.accept(pose(4,45),4*PERIOD).z()/32767,.02);
        yaw.recenter();
        HeadOrientation.Rotation centered=yaw.accept(pose(5,45),5*PERIOD);
        assertEquals(0,centered.z()); assertEquals((zero.reset()+1)&255,centered.reset());
        assertEquals(0,yaw.accept(pose(6,45),6*PERIOD).z());
    }
    @Test public void duplicatesAndBrokenTimebase() {
        HeadOrientation yaw=new HeadOrientation(); yaw.accept(pose(10,0),PERIOD);
        assertNull(yaw.accept(pose(10,7),2*PERIOD));
        assertNull(yaw.accept(pose(11,7),PERIOD));
        assertEquals(0,yaw.accept(pose(2,25),3*PERIOD).z());
        assertEquals(0,yaw.accept(pose(24,-20),26*PERIOD).z());
    }
    @Test public void orientationValidityIsSeparateFromGestureThresholds() {
        // Two real protobuf layouts with synthetic payloads. All offsets are payload-relative.
        for(boolean nested:new boolean[]{false,true}) {
            byte[] payload=new byte[58]; payload[0]=1; payload[1]=1; payload[9]=3;
            payload[10]=4; payload[11]=(byte)0x83; payload[21]=0x40;
            byte[] envelope=nested ? new byte[]{8,1,16,3,58,62,8,16,26,58} : new byte[]{8,1,16,3,26,60,16,0};
            byte[] report=new byte[12+envelope.length+58];
            report[0]=4;report[2]=4;report[4]=0x17;report[8]=0x10;report[10]=(byte)(report.length-12);
            System.arraycopy(envelope,0,report,12,envelope.length);
            int start=12+envelope.length; System.arraycopy(payload,0,report,start,58);
            assertNull(HeadMotionCodec.sample(report,report.length,0));
            assertEquals(.5,HeadOrientation.decode(report,report.length).x(),0);
            report[start+11]=(byte)0x82; assertNull(HeadOrientation.decode(report,report.length));
            report[start+11]=3; report[start+21]=127; report[start+23]=127;
            assertNull(HeadOrientation.decode(report,report.length));
        }
    }

    @Test public void calibrationRequiresFreshHeldSamplesInTheSameEpoch() {
        HeadOrientation head = new HeadOrientation();
        for (int i = 1; i <= 10; i++) head.accept(pose(i, 5), i*PERIOD);
        assertNotNull(head.stablePose(10*PERIOD));
        assertNull(head.stablePose(16*PERIOD));
        long epoch = head.epoch();
        head.accept(pose(11, 30), 11*PERIOD);
        assertNull(head.stablePose(11*PERIOD));
        assertEquals(epoch, head.epoch());
        head.accept(pose(30, 30), 30*PERIOD);
        assertNotEquals(epoch, head.epoch());
        assertNull(head.stablePose(30*PERIOD));
        head.recenter();
        assertEquals(0, head.epoch());
        assertNull(head.stablePose(30*PERIOD));
    }

    @Test public void personalAxesMapYawPitchRollAndCombinedRotation() {
        // In this fixture head X, Y, Z correspond to earbud Y, Z, X respectively.
        HeadOrientation.Pose front = new HeadOrientation.Pose(1, 1, 0, 0, 0);
        double s = Math.sin(Math.PI/12), c = Math.cos(Math.PI/12);
        HeadOrientation.Pose left = new HeadOrientation.Pose(2, c, s, 0, 0);
        HeadOrientation.Pose up = new HeadOrientation.Pose(3, c, 0, s, 0);
        HeadBasis basis = HeadBasis.calibrate(front, left, up);
        HeadOrientation head = new HeadOrientation();
        head.setBasis(HeadBasis.decode(basis.encode()));
        assertTrue(head.calibrated());
        head.accept(front, PERIOD);
        double units = 180.0 / 32767;
        assertEquals(30, units*head.accept(left, 2*PERIOD).z(), .01);
        assertEquals(30, units*head.accept(up, 3*PERIOD).x(), .01);
        assertEquals(-30, units*head.accept(new HeadOrientation.Pose(4, c, 0, 0, -s), 4*PERIOD).y(), .01);
        HeadOrientation.Rotation combined = head.accept(new HeadOrientation.Pose(5, c, s/Math.sqrt(2), -s/Math.sqrt(2), 0), 5*PERIOD);
        assertEquals(-30/Math.sqrt(2), units*combined.x(), .01);
        assertEquals(0, combined.y());
        assertEquals(30/Math.sqrt(2), units*combined.z(), .01);
        assertThrows(IllegalArgumentException.class, () -> HeadBasis.calibrate(front, left, left));
        assertThrows(IllegalArgumentException.class, () -> HeadBasis.calibrate(front, front, up));
        assertFalse(HeadBasis.nearFront(front, left));
        assertNull(HeadBasis.decode("NaN,0,0,0,0,1"));
    }
}
