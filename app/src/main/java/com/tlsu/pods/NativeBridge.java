// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

final class NativeBridge {
    private NativeBridge() {}
    static native void setEnabled(boolean enabled);
    static native long[] status();
}
