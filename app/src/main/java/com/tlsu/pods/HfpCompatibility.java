// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Newer AirPods can send IPHONEACCEV without the required CR. Do not
 * advertise this legacy battery extension: the OEM AACP reader supplies battery. */
final class HfpCompatibility {
    private HfpCompatibility() {}
    static String response(boolean airpods, String original) {
        return airpods && "+XAPL=iPhone,2".equals(original) ? "+XAPL=iPhone,0" : original;
    }
}
