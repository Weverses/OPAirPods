// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Executor;

/** Warms Quick Settings while the media output is already routed to an AirPods session. */
final class QuickAudioSnapshots {
    private static final AudioAttributes MEDIA = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).build();
    private static final int[] RETRIES_MS = {160, 400, 950, 1800};
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Executor io;
    private AudioManager audio;
    private BridgeClient bridge;
    private String mediaAddress;
    private Bundle snapshot;
    private int routeRevision;

    QuickAudioSnapshots(Executor io) { this.io = io; }

    void start(Context context) {
        if (audio != null) return;
        audio = context.getSystemService(AudioManager.class);
        if (audio == null) return;
        bridge = new BridgeClient(context.getApplicationContext());
        io.execute(() -> {
            try {
                listenToMediaRoute();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                try {
                    audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
                        @Override public void onAudioDevicesAdded(AudioDeviceInfo[] devices) { readMediaRoute(); }
                        @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] devices) { readMediaRoute(); }
                    }, main);
                } catch (RuntimeException | LinkageError ignored) {
                    // A live read remains available as a fallback.
                }
            } finally {
                main.post(this::readMediaRoute);
            }
        });
    }

    private void listenToMediaRoute() throws ReflectiveOperationException {
        Class<?> listener = Class.forName("android.media.AudioManager$OnDevicesForAttributesChangedListener");
        Class<?> device = Class.forName("android.media.AudioDeviceAttributes");
        var type = device.getMethod("getType");
        var address = device.getMethod("getAddress");
        Object callback = Proxy.newProxyInstance(listener.getClassLoader(), new Class<?>[]{listener},
                (self, method, args) -> {
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(self);
                        case "equals" -> self == args[0];
                        case "toString" -> "ColorPods media route listener";
                        case "onDevicesForAttributesChanged" -> {
                            try {
                                List<?> devices = (List<?>) args[1];
                                Object output = devices.size() == 1 ? devices.get(0) : null;
                                String target = output != null
                                        && (int) type.invoke(output) == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                                        ? normalize((String) address.invoke(output)) : null;
                                setMediaRoute(target);
                            } catch (ReflectiveOperationException | RuntimeException ignored) {
                                readMediaRoute();
                            }
                            yield null;
                        }
                        default -> null;
                    };
                });
        AudioManager.class.getMethod("addOnDevicesForAttributesChangedListener", AudioAttributes.class,
                        Executor.class, listener)
                .invoke(audio, MEDIA, (Executor) main::post, callback);
    }

    private void readMediaRoute() {
        int revision = routeRevision;
        io.execute(() -> {
            try {
                List<AudioDeviceInfo> outputs = audio.getAudioDevicesForAttributes(MEDIA);
                String address = outputs.size() == 1 && outputs.get(0).getType() == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                        ? normalize(outputs.get(0).getAddress()) : null;
                main.post(() -> {
                    if (revision == routeRevision) setMediaRoute(address);
                });
            } catch (RuntimeException ignored) {
                // Live reads still run when the panel opens.
            }
        });
    }

    private void setMediaRoute(String address) {
        if (Objects.equals(mediaAddress, address)) return;
        mediaAddress = address;
        snapshot = null;
        int revision = ++routeRevision;
        if (address != null) prefetch(revision, 0);
    }

    private void prefetch(int revision, int attempt) {
        if (revision != routeRevision || snapshot != null) return;
        io.execute(() -> {
            Bundle result = null;
            try {
                result = bridge.request(BridgeClient.QUICK_AUDIO, new Bundle());
            } catch (Exception ignored) {
                // Bluetooth can be initializing when the output first becomes active.
            }
            Bundle response = result;
            main.post(() -> {
                if (revision != routeRevision || snapshot != null) return;
                remember(response);
                if (snapshot == null && attempt < RETRIES_MS.length) {
                    main.postDelayed(() -> prefetch(revision, attempt + 1), RETRIES_MS[attempt]);
                }
            });
        });
    }

    void remember(Bundle status) {
        if (mediaAddress == null || status == null) return;
        if (mediaAddress.equals(normalize(status.getString("address")))) snapshot = new Bundle(status);
        else if (!status.containsKey("address")) snapshot = null;
    }

    Bundle current() { return snapshot == null ? null : new Bundle(snapshot); }

    private static String normalize(String address) {
        if (address == null) return null;
        String canonical = address.toUpperCase(Locale.ROOT);
        return BluetoothAdapter.checkBluetoothAddress(canonical) ? canonical : null;
    }
}
