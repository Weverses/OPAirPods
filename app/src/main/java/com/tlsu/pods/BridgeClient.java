// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.content.Context;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;

final class BridgeClient {
    static final String DESCRIPTOR = "com.tlsu.pods.BluetoothBridge.v1";
    static final int TRANSACTION_BASE = 0x00c01000;
    static final int STATUS = 1, VENDOR = 2, CONTROL = 3, ATT_READ = 4, ATT_PATCH = 5, ROUTE = 6, MULTIPOINT = 7, HANDOFF_NOTICE=8;
    private final Context context;
    BridgeClient(Context context) { this.context = context; }
    Bundle request(int operation, Bundle request) throws Exception {
        // Use the already-authorized OEM Bluetooth Binder. This has no package
        // visibility / external-provider dependency and is refreshed after death.
        Class<?> cls = Class.forName("android.bluetooth.BluetoothAdapterExtImpl");
        var getInstance = cls.getDeclaredMethod("getInstance"); getInstance.setAccessible(true);
        Object ext = getInstance.invoke(null);
        var field = cls.getDeclaredField("mOplusBluetooth"); field.setAccessible(true);
        Object service = field.get(null);
        if (service == null) { Reflect.call(ext, "getService", new Class[0]); service = field.get(null); }
        IBinder backend = service instanceof android.os.IInterface ? ((android.os.IInterface)service).asBinder() : null;
        if (backend == null || !backend.isBinderAlive()) throw new IllegalStateException("系统蓝牙服务尚未就绪");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR); data.writeBundle(request);
            if (!backend.transact(TRANSACTION_BASE + operation, data, reply, 0)) throw new IllegalStateException("蓝牙作用域尚未加载 ColorPods，请重启蓝牙进程");
            reply.readException();
            Bundle result = reply.readBundle(BridgeClient.class.getClassLoader());
            if (result == null) throw new IllegalStateException("蓝牙模块没有返回结果");
            if (result.containsKey("error")) throw new IllegalStateException(result.getString("error"));
            return result;
        } finally { data.recycle(); reply.recycle(); }
    }
    static Bundle forDevice(String address) { Bundle b = new Bundle(); b.putString("address", address); return b; }
}
