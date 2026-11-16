// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Bounded, timestamp-based detector for the raw horizontal/vertical motion pair.
 * Uses peak-to-trough excursions, independent of the starting head pose.
 * Requires alternating turns on one axis; a single turn never qualifies.
 * Thresholds need confirmation against recordings from the target firmware. */
final class HeadGestureDetector {
    static final int NONE = 0, NOD = 1, SHAKE = 2;
    private static final long GAP_MS = 250, WINDOW_MS = 2200, COOLDOWN_MS = 1200;
    // Horizontal motion is weaker and carries some pitch on the tested earbuds.
    // Thresholds are raw sensor units; they do not express physical head angles.
    private final Axis horizontal = new Axis(220, 450, 3600), vertical = new Axis(400, 800, WINDOW_MS);
    private long first = -1, previous = -1, cooldown;
    private double baseH, baseV, smoothH, smoothV;
    private int calibration;

    void reset() {
        first = previous = -1; cooldown = 0; calibration = 0;
        baseH = baseV = smoothH = smoothV = 0;
        horizontal.clear(); vertical.clear();
    }

    int accept(HeadMotionCodec.Sample sample) {
        long now = sample.at();
        if (previous >= 0 && now <= previous) return NONE;
        if (previous >= 0 && now - previous > GAP_MS) reset();
        previous = now;
        if (first < 0) first = now;
        if (calibration < 10 || now - first < 250) {
            baseH += (sample.horizontal() - baseH) / ++calibration;
            baseV += (sample.vertical() - baseV) / calibration;
            return NONE;
        }
        smoothH += 0.55 * (sample.horizontal() - baseH - smoothH);
        smoothV += 0.55 * (sample.vertical() - baseV - smoothV);
        if (now < cooldown) return NONE;
        horizontal.accept(smoothH, now); vertical.accept(smoothV, now);
        boolean h = horizontal.complete(now), v = vertical.complete(now);
        int result = NONE;
        if (v && vertical.range() > horizontal.range() * 2.2) result = NOD;
        else if (h && horizontal.range() > vertical.range() * 1.4) result = SHAKE;
        // Natural yaw can carry almost as much pitch. Demand a longer chain
        // of real horizontal reversals before accepting this ambiguous path.
        else if (horizontal.complete(now, 5, 3600)
                && horizontal.range() > vertical.range() * 0.9) result = SHAKE;
        if (result != NONE) {
            horizontal.clear(); vertical.clear(); cooldown = now + COOLDOWN_MS;
        }
        return result;
    }

    String diagnostic() {
        return "turns="+horizontal.extremes+"/"+vertical.extremes
                +" ranges="+Math.round(horizontal.range())+"/"+Math.round(vertical.range());
    }

    private static final class Axis {
        private static final int CAPACITY = 128;
        private static final long RANGE_MS = 900;
        private final double[] values = new double[CAPACITY];
        private final long[] times = new long[CAPACITY];
        private final int prominence, excursion;
        private final long window;
        private int size, next, direction, extremes, lastKind;
        private long began, changed, turningAt;
        private double turning = Double.NaN, lastExtreme;

        Axis(int prominence, int excursion, long window) {
            this.prominence = prominence; this.excursion = excursion; this.window = window;
        }

        void clear() {
            size = next = 0;
            clearTurns();
        }
        private void clearTurns() {
            direction = extremes = lastKind = 0;
            began = changed = turningAt = 0; turning = Double.NaN; lastExtreme = 0;
        }
        void accept(double value, long now) {
            // Expiring one axis's turn chain must retain its recent range;
            // otherwise the other axis can falsely appear dominant.
            if (extremes > 0 && now - began > window) clearTurns();
            values[next] = value; times[next] = now; next = (next + 1) % CAPACITY;
            size = Math.min(size + 1, CAPACITY);
            if (Double.isNaN(turning)) { turning = value; turningAt = now; return; }
            if (direction == 0) {
                if (Math.abs(value - turning) >= prominence) {
                    direction = value > turning ? 1 : -1; turning = value; turningAt = now;
                }
            } else if (direction > 0) {
                if (value >= turning) { turning = value; turningAt = now; }
                else if (turning - value >= prominence) {
                    turn(1, turning, turningAt); direction = -1; turning = value; turningAt = now;
                }
            } else {
                if (value <= turning) { turning = value; turningAt = now; }
                else if (value - turning >= prominence) {
                    turn(-1, turning, turningAt); direction = 1; turning = value; turningAt = now;
                }
            }
        }
        private void turn(int kind, double value, long at) {
            // The longer yaw window must not combine unrelated turns across
            // a pause. Real adjacent left/right peaks stay within one second.
            if (extremes > 0 && at - changed > 1000) extremes = lastKind = 0;
            if (extremes > 0) {
                if (kind == lastKind) { lastExtreme = value; changed = at; return; }
                if (Math.abs(value - lastExtreme) < excursion) return;
                if (at - changed < 100) { extremes = lastKind = 0; return; }
            }
            if (extremes == 0) began = at;
            lastKind = kind; lastExtreme = value; changed = at; extremes++;
        }
        double range() {
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            long now = times[(next + CAPACITY - 1) % CAPACITY];
            for (int i = 0; i < size; i++) if (now - times[i] <= RANGE_MS) {
                min = Math.min(min, values[i]); max = Math.max(max, values[i]);
            }
            return size == 0 ? 0 : max - min;
        }
        boolean complete(long now) {
            return complete(now, 3, WINDOW_MS);
        }
        boolean complete(long now, int required, long within) {
            return extremes >= required && now - began >= 300 && now - began <= within
                    && now - changed <= 350;
        }
    }
}
