// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

final class QuickAudioState {
    static final int NATIVE_CONVERSATION = 16;
    private static final int NATIVE_NOISE = 4;
    private static final int NATIVE_ADAPTIVE = 8;
    private static final int NATIVE_OFF = 512;
    private static final int NATIVE_TRANSPARENCY = 1024;
    private static final int COLOROS_TRANSPARENCY = 2;
    private static final int COLOROS_CANCELLATION = 5;
    private static final int COLOROS_ADAPTIVE = 10;

    private QuickAudioState() {}

    static int listeningModes(int current, int nativeFeatures, int stemModes, int allowOff, boolean adaptiveReport) {
        int features = Math.max(0, nativeFeatures);
        int configured = Math.max(0, stemModes);
        boolean knownMode = ListeningModeNotice.name(current) != null;
        boolean adaptive = current == 4 || adaptiveReport || (configured & 8) != 0
                || (features & NATIVE_ADAPTIVE) != 0;
        if (!knownMode && !adaptive && (features & NATIVE_NOISE) == 0 && configured == 0) return 0;
        int modes = 1 << 1;
        if (knownMode || adaptive || (configured & 4) != 0 || (features & NATIVE_TRANSPARENCY) != 0) {
            modes |= 1 << 2;
        }
        if (current == 1 || allowOff == 1 || (allowOff < 0
                && ((configured & 1) != 0 || (features & NATIVE_OFF) != 0))) {
            modes |= 1;
        }
        if (adaptive) modes |= 8;
        return modes;
    }

    static boolean hasMode(int modes, int selection) {
        return selection >= 1 && selection <= 4 && (modes & (1 << (selection - 1))) != 0;
    }

    static int colorOsNoise(int mode) {
        return switch (mode) {
            case 2 -> COLOROS_CANCELLATION;
            case 3 -> COLOROS_TRANSPARENCY;
            case 4 -> COLOROS_ADAPTIVE;
            default -> 1;
        };
    }

}
