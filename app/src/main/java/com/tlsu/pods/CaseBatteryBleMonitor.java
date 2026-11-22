// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Observes proximity broadcasts only while a bonded AirPods session needs a case reading. */
@SuppressLint("MissingPermission") // Uses the Bluetooth host's granted Nearby Devices permissions.
final class CaseBatteryBleMonitor {
    interface Transport {
        boolean current(BluetoothDevice device, BluetoothSocket socket);
        void request(BluetoothDevice device, BluetoothSocket socket, int mask);
        void publish(BluetoothDevice device, BluetoothSocket socket);
        void sample(BluetoothDevice device, BluetoothSocket socket, CaseBatteryBleCodec.Sample sample, long at);
    }

    private static final long KEY_DELAY_MS = 1500, KEY_RETRY_MS = 3500, SCAN_RETRY_MS = 30_000;
    private final class Entry {
        final BluetoothDevice device;
        final BluetoothSocket socket;
        final String address;
        byte[] identity, encryption;
        int requests;
        boolean requesting;
        CaseBattery.Display shown;
        long refreshAt;
        final Runnable refresh = () -> {
            refreshAt = 0;
            if (isCurrent(this)) refreshDisplay(this, SystemClock.elapsedRealtime(), true);
        };

        Entry(BluetoothDevice device, BluetoothSocket socket) {
            this.device = device;
            this.socket = socket;
            address = device.getAddress();
            shown = battery.display(address, SystemClock.elapsedRealtime());
        }
    }

    private final Handler worker;
    private final Map<String, Entry> entries = new HashMap<>();
    private final ColorPodsModule module;
    private final CaseBattery battery;
    private final Transport transport;
    private final Runnable retryScan = this::refreshScan;
    private Context context;
    private BluetoothLeScanner scanner;
    private ScanCallback callback;
    private long scanRetryAt;

    CaseBatteryBleMonitor(ColorPodsModule module, CaseBattery battery, Transport transport) {
        this.module = module;
        this.battery = battery;
        this.transport = transport;
        HandlerThread thread = new HandlerThread("ColorPodsCaseBattery");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    void attach(Context context) {
        worker.post(() -> {
            this.context = context;
            refreshScan();
        });
    }

    void opened(BluetoothDevice device, BluetoothSocket socket) {
        worker.post(() -> {
            if (!transport.current(device, socket)) return;
            try {
                if (device.getBondState() != BluetoothDevice.BOND_BONDED) return;
            } catch (SecurityException ignored) {
                return;
            }
            Entry entry = new Entry(device, socket);
            Entry previous = entries.put(entry.address, entry);
            if (previous != null) worker.removeCallbacks(previous.refresh);
            refreshDisplay(entry, SystemClock.elapsedRealtime(), false);
            requestKeys(entry, KEY_DELAY_MS);
            refreshScan();
        });
    }

    void closed(String address) {
        worker.post(() -> {
            Entry old = entries.remove(address);
            if (old != null) worker.removeCallbacks(old.refresh);
            refreshScan();
        });
    }

    void nativeChanged(String address) {
        worker.post(() -> {
            Entry entry = entries.get(address);
            if (entry == null || !isCurrent(entry)) return;
            refreshDisplay(entry, SystemClock.elapsedRealtime(), false);
            requestKeys(entry, KEY_DELAY_MS);
            refreshScan();
        });
    }

    void keys(String address, BluetoothSocket socket, CaseBatteryBleCodec.Keys keys) {
        worker.post(() -> {
            Entry entry = entries.get(address);
            if (entry == null || entry.socket != socket || !isCurrent(entry)) return;
            boolean hadIdentity = entry.identity != null;
            if (keys.identity() != null) entry.identity = keys.identity();
            if (keys.encryption() != null) entry.encryption = keys.encryption();
            if (!hadIdentity && entry.identity != null) module.info("AirPods case battery: BLE identity ready");
            refreshScan();
        });
    }

    private boolean isCurrent(Entry entry) {
        return entries.get(entry.address) == entry && transport.current(entry.device, entry.socket);
    }

    private boolean needKeys(Entry entry) {
        return isCurrent(entry) && battery.needsBle(entry.address) && entry.requests < 3
                && (entry.identity == null || entry.encryption == null);
    }

    private void requestKeys(Entry entry, long delay) {
        if (entry.requesting || !needKeys(entry)) return;
        entry.requesting = true;
        worker.postDelayed(() -> {
            entry.requesting = false;
            if (!needKeys(entry)) return;
            int mask = CaseBatteryBleCodec.IRK | CaseBatteryBleCodec.ENCRYPTION;
            if (entry.identity != null) mask = CaseBatteryBleCodec.ENCRYPTION;
            else if (entry.encryption != null || entry.requests == 2) mask = CaseBatteryBleCodec.IRK;
            entry.requests++;
            transport.request(entry.device, entry.socket, mask);
            requestKeys(entry, KEY_RETRY_MS);
        }, delay);
    }

    private boolean needsScan(Entry entry) {
        return entry.identity != null && isCurrent(entry) && battery.needsBle(entry.address);
    }

    private void refreshScan() {
        worker.removeCallbacks(retryScan);
        if (entries.values().stream().noneMatch(this::needsScan)) {
            stopScan();
            return;
        }
        if (callback != null || context == null) return;
        long delay = scanRetryAt - SystemClock.elapsedRealtime();
        if (delay > 0) {
            worker.postDelayed(retryScan, delay);
            return;
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
            module.info("AirPods case battery: Bluetooth host lacks BLE scan access");
            return;
        }
        try {
            BluetoothManager manager = context.getSystemService(BluetoothManager.class);
            var adapter = manager == null ? null : manager.getAdapter();
            scanner = adapter == null ? null : adapter.getBluetoothLeScanner();
            if (scanner == null) {
                deferScan();
                return;
            }
            callback = new ScanCallback() {
                @Override public void onScanResult(int type, ScanResult result) {
                    worker.post(() -> { if (callback == this) received(result); });
                }

                @Override public void onScanFailed(int errorCode) {
                    worker.post(() -> {
                        if (callback != this) return;
                        module.info("AirPods case battery: BLE scan failed, code=" + errorCode);
                        stopScan();
                        deferScan();
                    });
                }
            };
            ScanFilter filter = new ScanFilter.Builder().setManufacturerData(CaseBatteryBleCodec.APPLE,
                    CaseBatteryBleCodec.PROXIMITY_PREFIX).build();
            ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_POWER).build();
            scanner.startScan(List.of(filter), settings, callback);
        } catch (RuntimeException e) {
            stopScan();
            module.error("AirPods case battery: BLE scan unavailable", e);
            deferScan();
        }
    }

    private void deferScan() {
        scanRetryAt = SystemClock.elapsedRealtime() + SCAN_RETRY_MS;
        worker.removeCallbacks(retryScan);
        worker.postDelayed(retryScan, SCAN_RETRY_MS);
    }

    private void stopScan() {
        ScanCallback old = callback;
        callback = null;
        try {
            if (old != null) scanner.stopScan(old);
        } catch (RuntimeException ignored) {
            // Turning off Bluetooth can remove the scanner before this callback runs.
        } finally {
            scanner = null;
        }
    }

    private void received(ScanResult result) {
        if (result.getScanRecord() == null || result.getDevice() == null) return;
        long now = SystemClock.elapsedRealtime(), at = result.getTimestampNanos() / 1_000_000;
        if (at < 0 || at > now || now - at >= CaseBattery.BLE_CHARGING_MS) return;
        byte[] data = result.getScanRecord().getManufacturerSpecificData(CaseBatteryBleCodec.APPLE);
        if (data == null) return;

        String advertiser = result.getDevice().getAddress();
        Entry matched = null;
        for (Entry entry : entries.values()) {
            if (!needsScan(entry) || !CaseBatteryBleCodec.matchesAddress(advertiser, entry.address, entry.identity)) continue;
            if (matched != null) return;
            matched = entry;
        }
        if (matched == null) return;
        CaseBatteryBleCodec.Sample sample = CaseBatteryBleCodec.battery(data, matched.encryption);
        if (sample == null) return;
        transport.sample(matched.device, matched.socket, sample, at);
        if (isCurrent(matched)) refreshDisplay(matched, now, true);
    }

    private void refreshDisplay(Entry entry, long now, boolean publish) {
        CaseBattery.Display display = battery.display(entry.address, now);
        boolean changed = !display.sameValue(entry.shown);
        entry.shown = display;
        if (publish && changed) transport.publish(entry.device, entry.socket);

        long next = display.nextChangeAt();
        if (next != 0 && entry.refreshAt != 0 && entry.refreshAt <= next) return;
        worker.removeCallbacks(entry.refresh);
        entry.refreshAt = next;
        if (next != 0) worker.postDelayed(entry.refresh, Math.max(1, next - now));
    }
}
