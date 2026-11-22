// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Recheck the removal when the OEM's delayed ear-detection pause runs. */
final class EarDetectionPause {
    private static final long PAUSE_DELAY_MS = 500;
    private int worn = -1;
    private int beforeRemoval;
    private long removedAt = -1;

    synchronized void wear(boolean left, boolean right, long now) {
        int count = (left ? 1 : 0) + (right ? 1 : 0);
        if (worn >= 0 && count < worn) {
            beforeRemoval = removedAt < 0 ? worn : beforeRemoval;
            removedAt = now;
        } else if (count >= beforeRemoval) {
            removedAt = -1;
        }
        worn = count;
    }

    synchronized boolean allow(long now) {
        if (worn < 0) return true;
        if (removedAt < 0 || now - removedAt < PAUSE_DELAY_MS) return false;
        removedAt = -1;
        return true;
    }

    synchronized void disconnected() {
        worn = 0;
        removedAt = -1;
    }
}
