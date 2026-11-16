// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.app.Application;
import android.content.Context;
import android.util.Log;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

public final class ColorPodsModule extends XposedModule {
    private BluetoothBackend backend;
    private volatile boolean managerHooked;
    private final ThreadLocal<android.bluetooth.BluetoothSocket> readerSocket = new ThreadLocal<>();

    void info(String message) { log(Log.INFO, "ColorPods", message); }
    void error(String message, Throwable t) { log(Log.ERROR, "ColorPods", message, t); }

    boolean currentBatteryReader(android.bluetooth.BluetoothDevice device) {
        return backend.currentReader(device, readerSocket.get());
    }

    @Override public void onModuleLoaded(ModuleLoadedParam param) {
        info("API " + getApiVersion() + " loaded in " + param.getProcessName());
    }

    @Override public void onPackageReady(PackageReadyParam param) {
        String pkg = param.getPackageName();
        ClassLoader loader = param.getClassLoader();
        String sourceDir = param.getApplicationInfo() == null ? null : param.getApplicationInfo().sourceDir;
        TargetCache.init(this, param.getApplicationInfo(), pkg);

        if (pkg.equals("com.heytap.mydevices")) {
            DexKitHost.init(this, DexKitHost.MYDEVICES, loader, sourceDir);
            try { NativeUi.install(this, loader); }
            catch (Throwable e) { error("MyDevices UI adapter unavailable", e); }
            try { CaseBatteryHooks.installUi(this, loader); }
            catch (Throwable e) { error("Case battery UI adapter unavailable", e); }
            try { HostHandoffCapsule.install(this, loader); }
            catch (Throwable e) { error("Handoff capsule adapter unavailable", e); }
        }
        if (!pkg.equals("com.android.bluetooth")) return;

        backend = new BluetoothBackend(this);
        try { backend.headMotion.calls.install(loader); }
        catch (Throwable e) { error("Head motion call adapter unavailable", e); }
        try {
            String library = getModuleApplicationInfo().nativeLibraryDir + "/libcolorpods.so";
            System.load(library);
            backend.nativeLoaded = true;
            info("Native module loaded from " + library);
        } catch (Throwable e) { error("Native VendorID module unavailable", e); }

        hookTarget(HostTargets.BT_HFP_AT_RESPONSE, loader, chain -> {
            String original = (String) chain.getArg(0);
            if (!"+XAPL=iPhone,2".equals(original)) return chain.proceed();
            var device = android.bluetooth.BluetoothAdapter.getDefaultAdapter()
                    .getRemoteDevice((byte[]) chain.getArg(1));
            String response = HfpCompatibility.response(backend.isAirPods(device), original);
            if (!java.util.Objects.equals(original, response)) {
                info("AirPods HFP: disabled legacy battery AT extension; AACP battery retained");
                return chain.proceed(new Object[]{response, chain.getArg(1)});
            }
            return chain.proceed();
        });

        // JNI callback: catch route-generated PLAY before the OEM AVRCP path
        // can dispatch it to an unrelated addressed/background media session.
        hookTarget(HostTargets.BT_AVRCP_MEDIA_KEY, loader, chain -> {
            if(backend.suppressGamePlay((String)chain.getArg(0),(int)chain.getArg(1),(boolean)chain.getArg(2)))return null;
            return chain.proceed();
        });

        hookTarget(HostTargets.BT_STUB_ON_TRANSACT, loader, chain -> {
            int code = (int) chain.getArg(0) - BridgeClient.TRANSACTION_BASE;
            if (code < BridgeClient.STATUS || code > BridgeClient.HEAD_MOTION)
                return chain.proceed();
            Object receiver = chain.getThisObject();
            // The enhancer belongs to the OEM extension jar, which is added
            // after onPackageReady. Resolve it only when its Binder is in use;
            // capturing an early null here would reject every bridge request.
            Class<?> enhancer = HostTargets.typeOrNull(HostTargets.BT_ENHANCER_CLASS,
                    receiver.getClass().getClassLoader());
            boolean isEnhancer = enhancer != null && enhancer.isInstance(receiver);
            if (isEnhancer)
                return backend.onTransact(code, (android.os.Parcel) chain.getArg(1),
                        (android.os.Parcel) chain.getArg(2), (int) chain.getArg(3));
            return chain.proceed();
        });

        hookTarget(HostTargets.APP_ATTACH, loader, chain -> {
            Object result = chain.proceed();
            try { backend.attach((Context) chain.getArg(0)); }
            catch (Throwable e) { error("Backend attach failed", e); }
            return result;
        });

        // The OEM factory adds the extension jar to its ClassLoader at runtime.
        // Observe that boundary, then install the manager hooks before any
        // AirPodsManager instance can be constructed.
        hookTarget(HostTargets.BT_FACTORY_NEW_INSTANCE, loader, chain -> {
            Object result = chain.proceed();
            Object jar = chain.getArg(0);
            if (!managerHooked && jar instanceof String && ((String) jar).contains("oplus_bluetooth")) {
                Class<?> factory = HostTargets.typeOrNull(HostTargets.BT_FACTORY_CLASS, loader);
                if (factory == null) {
                    error("Bluetooth factory class unresolved", new ClassNotFoundException("Bluetooth factory"));
                    return result;
                }
                ClassLoader hostLoader = factory.getClassLoader();
                try { installManager(HostTargets.typeOrNull(HostTargets.EXT_MANAGER_CLASS, hostLoader)); }
                catch (Throwable e) { error("AirPodsManager hook failed", e); }
            }
            return result;
        });
        try { installManager(HostTargets.typeOrNull(HostTargets.EXT_MANAGER_CLASS, loader)); }
        catch (Throwable e) { error("AirPodsManager hook failed", e); }
        info("Bluetooth hook installation attempted");
    }

    private void hookTarget(String key, ClassLoader loader, XposedInterface.Hooker hooker) {
        try {
            Method method = HostTargets.methodOrNull(key, loader);
            if (method == null) { error("Hook target unresolved: " + key, new NoSuchMethodException(key)); return; }
            hook(method).intercept(hooker);
        } catch (Throwable e) { error("Hook " + key + " unavailable", e); }
    }

    private synchronized void installManager(Class<?> cls) {
        if (managerHooked || cls == null) return;
        managerHooked = true;
        ClassLoader host = cls.getClassLoader();
        try { CaseBatteryHooks.installBluetooth(this, host, backend.caseBattery); }
        catch (Throwable e) { error("Case battery adapter unavailable", e); }
        for (var ctor : cls.getDeclaredConstructors()) {
            try {
                hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    try { backend.manager(chain.getThisObject()); }
                    catch (Throwable e) { error("Manager capture failed", e); }
                    return result;
                });
            } catch (Throwable e) { error("Manager constructor hook failed", e); }
        }

        hookTarget(HostTargets.EXT_MANAGER_PARSE, host, chain -> {
            // Never consume the original InputStream or change the OEM parser input.
            try {
                byte[] bytes=(byte[])chain.getArg(0);int length=(int)chain.getArg(1);
                var device=(android.bluetooth.BluetoothDevice)chain.getArg(2);
                // Only an opted-in, locally handled stem event bypasses OEM
                // dispatch, so the same press cannot perform two actions.
                if(backend.stemPacket(bytes,length,device,readerSocket.get()))return null;
                backend.packet(bytes,length,device,readerSocket.get());
            }
            catch (Throwable e) { error("Packet observer failed", e); }
            return chain.proceed();
        });
        hookTarget(HostTargets.EXT_MANAGER_HANDLE, host, chain -> {
            var socket = (android.bluetooth.BluetoothSocket) chain.getArg(0);
            var device = (android.bluetooth.BluetoothDevice) chain.getArg(1);
            readerSocket.set(socket);
            backend.sessionOpened(device, socket);
            try { return chain.proceed(); }
            finally { backend.sessionClosed(device, socket); readerSocket.remove(); }
        });
        hookTarget(HostTargets.EXT_MEDIA_SEND_PLAY, host, chain -> {
            if (backend.suppressAutomaticPlay()) {
                info("Deferred OEM ear-detection autoplay during remote audio or game handoff");
                return null;
            }
            return chain.proceed();
        });
        hookTarget(HostTargets.EXT_MANAGER_CLOSE, host, chain -> {
            Object result = chain.proceed();
            backend.disconnected((android.bluetooth.BluetoothDevice) chain.getArg(0));
            return result;
        });

        // OEM AOT callers can contain inlined copies of a hooked method.
        String connectName = HostTargets.memberName(HostTargets.EXT_MANAGER_CONNECT, host);
        String podStatusName = HostTargets.memberName(HostTargets.EXT_MEDIA_POD_STATUS, host);
        Class<?> media = HostTargets.typeOrNull(HostTargets.EXT_MEDIA_CLASS, host);
        for (var caller : cls.getDeclaredMethods()) {
            if (caller.isSynthetic() || (connectName != null && caller.getName().contains(connectName))) deoptimize(caller);
        }
        if (media != null) {
            for (var caller : media.getDeclaredMethods()) {
                if (caller.isSynthetic() || caller.getName().equals(podStatusName)) deoptimize(caller);
            }
        }

        try {
            Field instance = HostTargets.fieldOrNull(HostTargets.EXT_MANAGER_INSTANCE, host);
            if (instance == null) throw new NoSuchFieldException("DexKit manager instance target unresolved");
            Object existing = instance.get(null);
            if (existing != null) backend.manager(existing);
        } catch (Throwable e) { error("Existing manager instance capture failed", e); }
        info("OEM AirPodsManager hooks installed");
    }
}
