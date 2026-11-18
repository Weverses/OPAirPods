// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Converts the AirPods orientation into active rotation in Android head coordinates. */
final class HeadOrientation {
    // Left-turn axis measured on OPD2515. Pitch and roll require two independent calibration turns.
    private static final double AX = 0.603642, AY = -0.643307, AZ = -0.470927;
    private static final long BREAK_NS = 500_000_000L;
    private static final long FRESH_NS = 160_000_000L;
    private static final long HOLD_NS = 320_000_000L;

    record Pose(long clock, double w, double x, double y, double z) {
        double dot(Pose other) {
            return w * other.w + x * other.x + y * other.y + z * other.z;
        }

        // conjugate(this) * other, in the reference earbud's coordinates
        Pose relativeToHere(Pose other) {
            return new Pose(other.clock, dot(other),
                    w * other.x - x * other.w - y * other.z + z * other.y,
                    w * other.y + x * other.z - y * other.w - z * other.x,
                    w * other.z - x * other.y + y * other.x - z * other.w);
        }

        double[] components() { return new double[]{w, x, y, z}; }

        static Pose fromComponents(double[] values) {
            if (values == null || values.length != 4) throw new IllegalArgumentException("缺少标定姿态");
            Pose pose = new Pose(0, values[0], values[1], values[2], values[3]);
            if (!(Math.abs(pose.dot(pose) - 1) < 0.001)) throw new IllegalArgumentException("无效的标定姿态");
            return pose;
        }
    }

    record Rotation(short x, short y, short z, int reset) {}
    private Pose reference, stillAnchor, lastPose;
    private HeadBasis basis;
    private long previousClock, previousArrival, stillSince, epoch;
    private int stillSamples, reset;

    static Pose decode(byte[] packet, int length) {
        int p = HeadMotionCodec.payloadOffset(packet, length);
        return p < 0 ? null : decodePayload(packet, p);
    }

    static Pose decodePayload(byte[] packet, int p) {
        if ((packet[p + 11] & 3) != 3) return null;
        long clock = 0;
        for (int i = p + 8; i > p; i--) clock = (clock << 8) | (packet[i] & 255);
        if (clock <= 0) return null;
        double x = HeadMotionCodec.signed16(packet, p + 20) / 32768.0;
        double y = HeadMotionCodec.signed16(packet, p + 22) / 32768.0;
        double z = HeadMotionCodec.signed16(packet, p + 24) / 32768.0;
        double norm = x*x + y*y + z*z;
        if (norm > 1.00012) return null;
        // A rounded unit vector can be a few Q15 units beyond the unit sphere.
        double scale = norm > 1 ? 1 / Math.sqrt(norm) : 1;
        return new Pose(clock, Math.sqrt(Math.max(0, 1 - norm)), x*scale, y*scale, z*scale);
    }

    void recenter() {
        reference = null;
        stillAnchor = null;
        lastPose = null;
        epoch = 0;
    }

    void setBasis(HeadBasis value) { basis = value; recenter(); }
    boolean calibrated() { return basis != null; }
    long epoch() { return epoch; }

    Pose stablePose(long now) {
        if (lastPose == null || now < previousArrival || now - previousArrival > FRESH_NS
                || previousArrival - stillSince < HOLD_NS || stillSamples < 5) return null;
        return lastPose;
    }

    private static short encode(double radians) {
        double bounded = Math.max(-Math.PI, Math.min(Math.PI, radians));
        return (short) Math.round(bounded * 32767 / Math.PI);
    }

    Rotation accept(Pose pose, long arrival) {
        if (pose.clock == previousClock || arrival <= previousArrival) return null;
        long gap = arrival - previousArrival;
        if (pose.clock < previousClock || pose.clock - previousClock > BREAK_NS || gap > BREAK_NS) recenter();
        previousClock = pose.clock;
        previousArrival = arrival;
        if (stillAnchor == null || gap > FRESH_NS || Math.abs(stillAnchor.dot(pose)) < Math.cos(Math.toRadians(1.5))) {
            stillAnchor = pose;
            stillSince = arrival;
            stillSamples = 0;
        }
        stillSamples++;
        lastPose = pose;
        if (reference == null) {
            reference = pose;
            epoch = arrival;
            reset = (reset + 1) & 255;
            return new Rotation((short) 0, (short) 0, (short) 0, reset);
        }
        Pose r = reference.relativeToHere(pose);
        if (basis == null) {
            double projected = AX*r.x + AY*r.y + AZ*r.z;
            if (Math.hypot(r.w, projected) < 1e-6) return null;
            double angle = Math.IEEEremainder(2 * Math.atan2(projected, r.w), 2 * Math.PI);
            return new Rotation((short) 0, (short) 0, encode(angle), reset);
        }
        HeadBasis.Vector direction = new HeadBasis.Vector(r.x, r.y, r.z);
        double length = direction.length();
        double angle = 2 * Math.atan2(length, Math.abs(r.w));
        double scale = length < 1e-10 ? 0 : Math.copySign(angle / length, r.w);
        return new Rotation(encode(direction.dot(basis.x) * scale), encode(direction.dot(basis.y) * scale),
                encode(direction.dot(basis.z) * scale), reset);
    }
}
