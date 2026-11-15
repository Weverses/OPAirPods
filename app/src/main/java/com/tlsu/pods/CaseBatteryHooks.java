// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.bluetooth.BluetoothDevice;
import android.os.SystemClock;
import java.lang.reflect.Method;

/** Keeps the native settings page, cards and battery callbacks on the same data. */
final class CaseBatteryHooks {
    private CaseBatteryHooks() {}

    static void installBluetooth(ColorPodsModule module, ClassLoader loader, CaseBattery state) {
        Method update = HostTargets.methodOrNull(HostTargets.EXT_MANAGER_BATTERY_UPDATE, loader);
        Method get = HostTargets.methodOrNull(HostTargets.EXT_MANAGER_BATTERY_GET, loader);
        Method notify = HostTargets.methodOrNull(HostTargets.EXT_MANAGER_BATTERY_NOTIFY, loader);
        if (update == null || get == null || notify == null) throw new IllegalStateException("Native battery methods unresolved");
        module.hook(update).intercept(chain -> {
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            if (device == null) return chain.proceed();
            // A replaced socket must not overwrite the new connection's readings.
            if (!module.currentBatteryReader(device)) return null;
            int[] raw = new int[6];
            for (int i = 0; i < 6; i++) raw[i] = (int) chain.getArg(i + 2);
            boolean isMax = (boolean) chain.getArg(1);
            state.update(device.getAddress(), isMax, raw, SystemClock.elapsedRealtime());
            // Keep the OEM storage, throttling and notification chain. Only
            // release its erroneous unchanged-state guard when data changed.
            try {
                Object info = Reflect.call(chain.getThisObject(), "getOrCreateDeviceInfo",
                        new Class[]{BluetoothDevice.class}, device);
                if (info != null) {
                    int[] previous = {
                            (int) Reflect.field(info, "batteryLevelLeft"),
                            (int) Reflect.field(info, "batteryLevelRight"),
                            (int) Reflect.field(info, "batteryLevelCase"),
                            (int) Reflect.field(info, "chargingStatusLeft"),
                            (int) Reflect.field(info, "chargingStatusRight"),
                            (int) Reflect.field(info, "chargingStatusCase")};
                    if (CaseBattery.blocksUpdate(previous, raw)) {
                        var field = info.getClass().getDeclaredField("chargingStatusCase");
                        field.setAccessible(true);
                        field.setInt(info, -1);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                module.error("Native battery unchanged-state guard unavailable", failure);
            }
            // Raw component availability stays in OEM storage. Projection is
            // applied only to outgoing display snapshots and callbacks below.
            return chain.proceed();
        });
        module.hook(get).intercept(chain -> {
            int[] battery = (int[]) chain.proceed();
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            return device == null ? battery : state.read(device.getAddress(), battery, SystemClock.elapsedRealtime());
        });
        module.hook(notify).intercept(chain -> {
            BluetoothDevice device = (BluetoothDevice) chain.getArg(0);
            if (device == null) return chain.proceed();
            int[] battery = state.read(device.getAddress(), (int[]) chain.getArg(1), SystemClock.elapsedRealtime());
            return chain.proceed(new Object[]{device, battery});
        });
        // parseAirPodsPacket can inline updateBatteryInfo in precompiled OEM code.
        Method parse = HostTargets.methodOrNull(HostTargets.EXT_MANAGER_PARSE, loader);
        if (parse != null) module.deoptimize(parse);
        module.deoptimize(update);
        // Wear-state updates can also notify battery data.
        Method wear = HostTargets.methodOrNull(HostTargets.EXT_MANAGER_POD_STATUS, loader);
        if (wear != null) module.deoptimize(wear);
    }

    static void installUi(ColorPodsModule module, ClassLoader loader) {
        Method visible = HostTargets.methodOrNull(HostTargets.UI_CASE_BATTERY_VISIBLE, loader);
        Method update = HostTargets.methodOrNull(HostTargets.UI_ACTIVITY_BATTERY_UPDATE, loader);
        if (visible == null || update == null) throw new IllegalStateException("Native case visibility unresolved");
        module.hook(visible).intercept(chain -> {
            int[] battery = (int[]) chain.getArg(0);
            // Zero is a real reading; keep the original layout and low-battery icon.
            if (battery != null && battery.length >= 6 && battery[2] == 0 && battery[5] != 4) return true;
            return chain.proceed();
        });
        module.deoptimize(update);
    }
}
