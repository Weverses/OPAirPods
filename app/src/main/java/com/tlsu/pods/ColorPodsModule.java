// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.app.Application;
import android.content.Context;
import android.util.Log;
import io.github.libxposed.api.XposedModule;

public final class ColorPodsModule extends XposedModule {
    private BluetoothBackend backend;
    private volatile boolean managerHooked;
    private final ThreadLocal<android.bluetooth.BluetoothSocket> readerSocket=new ThreadLocal<>();

    void info(String message) { log(Log.INFO, "ColorPods", message); }
    void error(String message, Throwable t) { log(Log.ERROR, "ColorPods", message, t); }

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        info("API " + getApiVersion() + " loaded in " + param.getProcessName());
    }
    @Override public void onPackageReady(PackageReadyParam param) {
        if (param.getPackageName().equals("com.heytap.mydevices")) {
            try { NativeUi.install(this, param.getClassLoader()); }
            catch (Throwable e) { error("MyDevices UI adapter unavailable", e); }
        }
        if (!param.getPackageName().equals("com.android.bluetooth")) return;
        backend = new BluetoothBackend(this);
        try {
            String library = getModuleApplicationInfo().nativeLibraryDir + "/libcolorpods.so";
            System.load(library);
            backend.nativeLoaded = true;
            info("Native module loaded from " + library);
        } catch (Throwable e) { error("Native VendorID module unavailable", e); }
        try {
            Class<?> hfp=Class.forName("com.android.bluetooth.hfp.HeadsetNativeInterface",false,param.getClassLoader());
            // Hook the JNI boundary: ART can inline the public Java wrapper into
            // already compiled OEM callers, bypassing a hook on that wrapper.
            hook(Reflect.method(hfp,"atResponseStringNative",String.class,byte[].class)).intercept(chain->{
                String original=(String)chain.getArg(0);
                if(!"+XAPL=iPhone,2".equals(original))return chain.proceed();
                var device=android.bluetooth.BluetoothAdapter.getDefaultAdapter().getRemoteDevice((byte[])chain.getArg(1));
                String response=HfpCompatibility.response(backend.isAirPods(device),original);
                if(!java.util.Objects.equals(original,response)){
                    info("AirPods HFP: disabled legacy battery AT extension; AACP battery retained");
                    return chain.proceed(new Object[]{response,chain.getArg(1)});
                }
                return chain.proceed();
            });
            info("AirPods HFP compatibility hook installed");
        }catch(Throwable e){error("AirPods HFP compatibility hook unavailable",e);}
        try {
            Class<?> stub = Class.forName("android.bluetooth.IOplusBluetooth$Stub", false, param.getClassLoader());
            hook(Reflect.method(stub, "onTransact", int.class, android.os.Parcel.class, android.os.Parcel.class, int.class)).intercept(chain -> {
                int code = (int) chain.getArg(0) - BridgeClient.TRANSACTION_BASE;
                if (code >= BridgeClient.STATUS && code <= BridgeClient.MULTIPOINT
                    && chain.getThisObject().getClass().getName().equals("com.oplus.bluetooth.btservice.AdapterServiceEnhancer"))
                    return backend.onTransact(code, (android.os.Parcel)chain.getArg(1), (android.os.Parcel)chain.getArg(2), (int)chain.getArg(3));
                return chain.proceed();
            });
            info("OEM Bluetooth Binder bridge installed");
            hook(Reflect.method(Application.class, "attach", Context.class)).intercept(chain -> {
                Object result = chain.proceed();
                try { backend.attach((Context) chain.getArg(0)); }
                catch (Throwable e) { error("Backend attach failed", e); }
                return result;
            });
            // The OEM factory extends its ClassLoader and uses Class.forName(),
            // which can bypass Java ClassLoader.loadClass hooks. Observe the exact
            // factory boundary instead, before any AirPodsManager is constructed.
            Class<?> factory = Class.forName("com.oplus.bluetooth.common.OplusBTFactory", false, param.getClassLoader());
            hook(Reflect.method(factory, "newInstance", String.class, String.class)).intercept(chain -> {
                Object result = chain.proceed();
                if (!managerHooked && "/system_ext/framework/oplus_bluetooth_common_ext.jar".equals(chain.getArg(0))) {
                    try { installManager(Class.forName("com.oplus.bluetooth.feature.airpods.AirPodsManager", false, factory.getClassLoader())); }
                    catch (Throwable e) { error("AirPodsManager hook failed", e); }
                }
                return result;
            });
            try { installManager(Class.forName("com.oplus.bluetooth.feature.airpods.AirPodsManager", false, param.getClassLoader())); }
            catch (ClassNotFoundException ignored) { info("Waiting for Oplus Bluetooth extension classloader"); }
        } catch (Throwable e) { error("Bluetooth hooks unavailable", e); }
    }
    private synchronized void installManager(Class<?> cls) throws ReflectiveOperationException {
        if (managerHooked) return;
        managerHooked = true;
        try {
            for (var ctor : cls.getDeclaredConstructors()) {
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try { backend.manager(chain.getThisObject()); } catch (Throwable e) { error("Manager capture failed", e); }
                    return result;
                });
            }
            var parse = Reflect.method(cls, "parseAirPodsPacket", byte[].class, int.class, android.bluetooth.BluetoothDevice.class);
            hook(parse).intercept(chain -> {
                // Never consume the original InputStream or change the OEM's parser input.
                try { backend.packet((byte[]) chain.getArg(0), (int) chain.getArg(1), (android.bluetooth.BluetoothDevice) chain.getArg(2),readerSocket.get()); }
                catch (Throwable e) { error("Packet observer failed", e); }
                return chain.proceed();
            });
            hook(Reflect.method(cls,"handleAirPodsSocket",android.bluetooth.BluetoothSocket.class,android.bluetooth.BluetoothDevice.class)).intercept(chain->{
                var socket=(android.bluetooth.BluetoothSocket)chain.getArg(0);var device=(android.bluetooth.BluetoothDevice)chain.getArg(1);
                readerSocket.set(socket);backend.sessionOpened(device,socket);
                try{return chain.proceed();}finally{backend.sessionClosed(device,socket);readerSocket.remove();}
            });
            Class<?> media=Class.forName("com.oplus.bluetooth.feature.airpods.AirPodsMediaController",false,cls.getClassLoader());
            hook(Reflect.method(media,"sendPlay")).intercept(chain->{
                if(backend.suppressAutomaticPlay()){info("Deferred OEM ear-detection autoplay while another device owns audio");return null;}
                return chain.proceed();
            });
            // OEM AOT callers can contain inlined copies of a hooked method.
            for(var caller:cls.getDeclaredMethods())if(caller.isSynthetic()||caller.getName().contains("connectToAirPodsSocket"))deoptimize(caller);
            for(var caller:media.getDeclaredMethods())if(caller.isSynthetic()||caller.getName().equals("onPodStatusChanged"))deoptimize(caller);
            hook(Reflect.method(cls, "closeSocketForDevice", android.bluetooth.BluetoothDevice.class)).intercept(chain -> {
                Object result = chain.proceed();
                backend.disconnected((android.bluetooth.BluetoothDevice) chain.getArg(0));
                return result;
            });
            // Existing instance can survive a package-only lifecycle callback.
            var instance = cls.getDeclaredField("sInstance"); instance.setAccessible(true);
            Object existing = instance.get(null); if (existing != null) backend.manager(existing);
            info("OEM AirPodsManager hooks installed");
        } catch (ReflectiveOperationException | RuntimeException e) { managerHooked = false; throw e; }
    }
}
