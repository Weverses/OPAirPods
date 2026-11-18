// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Orthonormal head axes in the earbud reference frame. X points right, Y forward, Z up. */
final class HeadBasis {
    record Vector(double x, double y, double z) {
        double dot(Vector v) { return x*v.x + y*v.y + z*v.z; }
        double length() { return Math.sqrt(dot(this)); }
        Vector scale(double k) { return new Vector(x*k, y*k, z*k); }
        Vector minus(Vector v) { return new Vector(x-v.x, y-v.y, z-v.z); }
        Vector cross(Vector v) { return new Vector(y*v.z - z*v.y, z*v.x - x*v.z, x*v.y - y*v.x); }
        Vector unit() {
            double size = length();
            if (!Double.isFinite(size) || size < 1e-6) throw new IllegalArgumentException("无效的标定方向");
            return scale(1 / size);
        }
    }
    final Vector x, y, z;
    private HeadBasis(Vector upTurn, Vector leftTurn) {
        z = leftTurn.unit();
        Vector up = upTurn.unit();
        if (Math.abs(up.dot(z)) > .45) throw new IllegalArgumentException("两个动作方向过于接近，请先转回正前方再抬头");
        x = up.minus(z.scale(up.dot(z))).unit();
        y = z.cross(x);
    }
    static HeadBasis calibrate(HeadOrientation.Pose front, HeadOrientation.Pose left, HeadOrientation.Pose up) {
        return new HeadBasis(turn(front, up), turn(front, left));
    }
    static Vector turn(HeadOrientation.Pose front, HeadOrientation.Pose q) {
        HeadOrientation.Pose r = front.relativeToHere(q);
        Vector v = new Vector(r.x(), r.y(), r.z());
        double angle = Math.toDegrees(2 * Math.atan2(v.length(), Math.abs(r.w())));
        if (angle < 12 || angle > 85) throw new IllegalArgumentException("请转动约 30°，保持后再确认");
        return r.w() < 0 ? v.scale(-1) : v;
    }
    static boolean nearFront(HeadOrientation.Pose front, HeadOrientation.Pose q) {
        return Math.abs(front.dot(q)) >= Math.cos(Math.toRadians(7.5));
    }
    String encode() { return x.x + "," + x.y + "," + x.z + "," + z.x + "," + z.y + "," + z.z; }
    static HeadBasis decode(String saved) {
        if (saved == null) return null;
        String[] fields = saved.split(",", -1);
        if (fields.length != 6) return null;
        try {
            double[] v = new double[6];
            for (int i = 0; i < v.length; i++) v[i] = Double.parseDouble(fields[i]);
            return new HeadBasis(new Vector(v[0], v[1], v[2]), new Vector(v[3], v[4], v[5]));
        } catch (IllegalArgumentException e) { return null; }
    }
}
