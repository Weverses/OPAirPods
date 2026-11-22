// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.content.Intent;
import android.os.Bundle;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Adapts the native OPPO earphone tiles to the current AirPods media route. */
final class QuickAudioNative {
    private static final String SLIDER = "com.oplus.systemui.qs.slider.";
    private static final String DEVICE_MARKER = "com.tlsu.pods";

    record Route(Object owner, Object controller, Object device, String address,
                 int listening, int listeningModes, int spatial, boolean tracking) {}

    private final Constructor<?> device;
    private final Method activeDevice;
    private final Method deviceAddress;
    private final Method supportType;
    private final Method refreshTile;
    private final Method updateTiles;
    private volatile Route route;

    QuickAudioNative(ColorPodsModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> controller = loader.loadClass(SLIDER + "detail.data.EarphoneController");
        Class<?> active = loader.loadClass(controller.getName() + "$ActiveDevice");
        Class<?> repository = loader.loadClass(SLIDER + "detail.data.OplusQsSliderDetailTileRepository");
        Class<?> tile = loader.loadClass(SLIDER + "detail.tile.QsDetailTileImpl");
        Class<?> noise = loader.loadClass(SLIDER + "detail.tile.volume.NoiseReductionDetailTile");
        Class<?> spatial = loader.loadClass(SLIDER + "detail.tile.volume.SpatialAudioDetailTile");
        Class<?> state = loader.loadClass("com.android.systemui.plugins.qs.QSTile$BooleanState");
        device = active.getConstructor(String.class, String.class, Map.class);
        activeDevice = Reflect.method(controller, "getActiveDevice");
        deviceAddress = Reflect.method(active, "getAddress");
        supportType = Reflect.method(active, "getSupportType");
        refreshTile = Reflect.method(tile, "refreshState", Object.class);
        updateTiles = Reflect.method(repository, "updateTiles");
        Method noiseStatus = Reflect.method(controller, "getNoiseReductionMode", String.class);
        Method spatialStatus = Reflect.method(controller, "getSpatialAudioType", String.class);
        Method noiseIntent = Reflect.method(noise, "getLongClickIntent");
        Method spatialIntent = Reflect.method(spatial, "getLongClickIntent");
        Method noiseLabel = Reflect.method(noise, "getSecondaryLabel",
                loader.loadClass("com.android.systemui.plugins.qs.QSTile$State"));

        module.hook(noiseStatus).intercept(chain -> {
            Route current = route;
            return matches(current, chain.getThisObject(), chain.getArg(0)) && current.listeningModes() != 0
                    ? QuickAudioState.colorOsNoise(current.listening()) : chain.proceed();
        });
        module.hook(spatialStatus).intercept(chain -> {
            Route current = route;
            return matches(current, chain.getThisObject(), chain.getArg(0)) && current.spatial() >= 0
                    ? current.spatial() : chain.proceed();
        });
        for (String setter : List.of("setNoiseReductionMode", "setSpatialAudioType")) {
            module.hook(Reflect.method(controller, setter, active, int.class)).intercept(chain ->
                    isAdapted(chain.getArg(0)) ? null : chain.proceed());
        }
        module.hook(noiseIntent).intercept(chain -> {
            Intent adapted = settingsIntent(chain.getThisObject());
            return adapted != null ? adapted : chain.proceed();
        });
        module.hook(spatialIntent).intercept(chain -> {
            Intent adapted = settingsIntent(chain.getThisObject());
            return adapted != null ? adapted : chain.proceed();
        });
        module.hook(noiseLabel).intercept(chain -> {
            Route current = route;
            return current != null && current.listening() < 1 && isCurrentTile(current, chain.getThisObject())
                    ? "状态未知" : chain.proceed();
        });
        for (Class<?> type : List.of(noise, spatial)) {
            module.deoptimize(Reflect.method(type, "handleUpdateState", state, Object.class));
            boolean listening = type == noise;
            module.hook(Reflect.method(type, "isAvailable")).intercept(chain -> {
                Route current = route;
                if (current == null || !isCurrentTile(current, chain.getThisObject())) return chain.proceed();
                // The OEM noise tile's wear flow belongs to Melody headphones.
                // AirPods capability comes from the system AirPods service instead.
                return listening ? Integer.bitCount(current.listeningModes()) > 1 : current.spatial() >= 0;
            });
        }
        module.deoptimize(updateTiles);
        module.deoptimize(Reflect.method(repository, "createTiles", int.class));
        module.hook(activeDevice).intercept(chain -> {
            Object original = chain.proceed();
            Route current = route;
            if (current == null || current.controller() != chain.getThisObject()) return original;
            if (original != null && !matches(current, chain.getThisObject(), deviceAddress.invoke(original))) {
                return original;
            }
            return current.device();
        });
    }

    private static boolean matches(Route route, Object controller, Object address) {
        return route != null && route.controller() == controller && route.address().equals(address);
    }

    private boolean isAdapted(Object device) throws ReflectiveOperationException {
        if (device == null) return false;
        var supported = (Map<?, ?>) supportType.invoke(device);
        return supported != null && supported.containsKey(DEVICE_MARKER);
    }

    private Intent settingsIntent(Object tile) throws ReflectiveOperationException {
        Route current = route;
        if (current == null || !isCurrentTile(current, tile)) return null;
        return new Intent("com.oplus.mydevices.AIRPODS_SETTING")
                .setClassName("com.heytap.mydevices", "com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingActivity")
                .putExtra("address", current.address());
    }

    private boolean isCurrentTile(Route current, Object tile) throws ReflectiveOperationException {
        Object controller = Reflect.field(tile, "earphoneController");
        if (controller != current.controller()) return false;
        Object original = Reflect.field(controller, "activeDevice");
        return original == null || current.address().equals(deviceAddress.invoke(original));
    }

    void prepare(Object owner, Object controller, Bundle status) throws ReflectiveOperationException {
        String address = deviceFor(controller, status);
        if (address != null) route = createRoute(owner, controller, status, address);
    }

    boolean update(Object owner, Object controller, Object repository, Bundle status)
            throws ReflectiveOperationException {
        Route previous = route;
        String address = deviceFor(controller, status);
        if (address == null) {
            if (clear(owner)) updateTiles.invoke(repository);
            return false;
        }
        int listening = status.getInt("listening");
        int modes = status.getInt("listeningModes");
        int spatial = status.getInt("spatial", -1);
        boolean tracking = status.getBoolean("trackingAvailable");
        boolean availabilityChanged = !matches(previous, controller, address) || previous.owner() != owner
                || previous.listeningModes() != modes || (previous.spatial() >= 0) != (spatial >= 0)
                || previous.tracking() != tracking;
        if (availabilityChanged) {
            route = createRoute(owner, controller, status, address);
            updateTiles.invoke(repository);
        } else if (previous.listening() != listening || previous.spatial() != spatial) {
            route = new Route(owner, controller, previous.device(), address, listening, modes, spatial, tracking);
        } else {
            return true;
        }
        for (Object tile : (Iterable<?>) Reflect.field(repository, "tempTiles")) {
            String spec = (String) Reflect.call(tile, "getTileSpec", new Class<?>[0]);
            if (spec.equals("noisereduction") || spec.equals("spatialaudio")) refreshTile.invoke(tile, (Object) null);
        }
        return true;
    }

    private String deviceFor(Object controller, Bundle status) throws ReflectiveOperationException {
        String address = status.getString("address");
        if (address == null) return null;
        Object original = Reflect.field(controller, "activeDevice");
        return original == null || address.equals(deviceAddress.invoke(original)) ? address : null;
    }

    private Route createRoute(Object owner, Object controller, Bundle status, String address)
            throws ReflectiveOperationException {
        int modes = status.getInt("listeningModes");
        int spatial = status.getInt("spatial", -1);
        boolean tracking = status.getBoolean("trackingAvailable");
        Map<String, List<Integer>> capabilities = new HashMap<>();
        capabilities.put(DEVICE_MARKER, List.of());
        if (modes != 0) {
            List<Integer> supported = new ArrayList<>(4);
            for (int selected = 1; selected <= 4; selected++) {
                if (QuickAudioState.hasMode(modes, selected)) supported.add(QuickAudioState.colorOsNoise(selected));
            }
            capabilities.put("noise", supported);
        }
        if (spatial >= 0) capabilities.put("spatial", tracking ? List.of(0, 1, 2) : List.of(0, 1));
        return new Route(owner, controller, device.newInstance("AirPods", address, capabilities),
                address, status.getInt("listening"), modes, spatial, tracking);
    }

    boolean clear(Object owner) {
        Route current = route;
        if (current == null || current.owner() != owner) return false;
        route = null;
        return true;
    }
}
