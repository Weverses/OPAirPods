// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

final class NativeBridge {
    private NativeBridge() {}
    static native void setEnabled(boolean enabled);
    static native long[] status();
    // Owned by the spatial worker. GET/SET feature requests run on the HID I/O thread.
    static native long openHeadTracker(byte[] bluetoothIdentity) throws java.io.IOException;
    static native int headTrackerControl(long tracker);
    static native boolean writeHeadPose(long tracker, short x, short y, short z, int reset);
    static native void closeHeadTracker(long tracker);
}
