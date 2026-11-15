// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.os.Process;
import android.os.SystemClock;

import org.luckypray.dexkit.DexKitBridge;

import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Owns the native DexKit bridges used to resolve host targets.
 *
 * <p>A bridge holds a full dex index in native memory, so it is created lazily,
 * shared per host scope and released after an idle period. Only MyDevices
 * requires a dex index for its obfuscated symbols.</p>
 *
 * <p>The module runs inside a host process, not as a normal installed app.
 * {@link System#loadLibrary(String)} may therefore miss the module's own
 * nativeLibraryDir; the absolute-path fallback mirrors the vendor hook's
 * loading strategy.</p>
 */
final class DexKitHost {
    interface Work<T> { T run(DexKitBridge bridge) throws Exception; }

    static final String MYDEVICES = "mydevices";
    static final String BLUETOOTH = "bluetooth";
    static final String BLUETOOTH_EXT = "bluetooth-ext";

    private static final String TAG = "ColorPodsDexKit";
    private static final long IDLE_CLOSE_DELAY_MS = 5_000L;
    private static final int BRIDGE_THREADS = 2;
    private static final Object LOCK = new Object();
    private static final Map<String, Scope> SCOPES = new HashMap<>();

    private static String nativeLibraryDir;
    private static String moduleApkPath;
    private static boolean nativeLoaded;
    private static ScheduledThreadPoolExecutor closer;

    private DexKitHost() {}

    /** Registers a host scope. Safe to call repeatedly; the loader can be refreshed. */
    static void init(ColorPodsModule module, String scope, ClassLoader loader, String sourceDir) {
        synchronized (LOCK) {
            if (nativeLibraryDir == null) {
                try { nativeLibraryDir = module.getModuleApplicationInfo().nativeLibraryDir; }
                catch (Throwable ignored) { }
                try { moduleApkPath = module.getModuleApplicationInfo().sourceDir; }
                catch (Throwable ignored) { }
            }
            Scope current = SCOPES.get(scope);
            if (current == null) {
                SCOPES.put(scope, new Scope(loader, sourceDir));
            } else if (loader != null && current.loader != loader) {
                // A different extension ClassLoader appeared: its dex index must
                // be rebuilt, otherwise queries would search the previous jar.
                current.invalidate();
                current.loader = loader;
                current.sourceDir = sourceDir;
            } else if (sourceDir != null) {
                current.sourceDir = sourceDir;
            }
        }
    }

    /** Drops the cached dex index, e.g. after OplusBTFactory.addDexPath(). */
    static void invalidate(String scope, ClassLoader loader, String sourceDir) {
        synchronized (LOCK) {
            Scope current = SCOPES.get(scope);
            if (current == null) {
                SCOPES.put(scope, new Scope(loader, sourceDir));
            } else {
                current.invalidate();
                current.loader = loader;
                current.sourceDir = sourceDir;
            }
        }
    }

    static boolean available() {
        synchronized (LOCK) {
            try { loadNativeLocked(); return true; }
            catch (Throwable t) { return false; }
        }
    }

    static <T> T with(String scope, Work<T> work) throws Exception {
        Scope target;
        synchronized (LOCK) {
            target = SCOPES.get(scope);
            if (target == null) throw new IllegalStateException("DexKit scope not initialized: " + scope);
            target.leases++;
            if (target.pendingClose != null) { target.pendingClose.cancel(false); target.pendingClose = null; }
        }
        try {
            DexKitBridge bridge = bridge(target);
            return work.run(bridge);
        } finally {
            synchronized (LOCK) {
                target.leases--;
                target.lastAccess = SystemClock.uptimeMillis();
                if (target.leases == 0) scheduleIdleCloseLocked(target);
            }
        }
    }

    private static DexKitBridge bridge(Scope scope) throws Exception {
        synchronized (LOCK) {
            loadNativeLocked();
            if (scope.bridge != null && scope.bridge.isValid()) return scope.bridge;
            scope.bridge = null;
            Throwable failure = null;
            if (scope.loader != null) {
                try {
                    DexKitBridge created = DexKitBridge.create(scope.loader, false);
                    if (created.getDexNum() > 0) {
                        scope.bridge = configure(created);
                        return scope.bridge;
                    }
                    created.close();
                } catch (Throwable t) { failure = t; }
            }
            if (scope.sourceDir != null) {
                try {
                    scope.bridge = configure(DexKitBridge.create(scope.sourceDir));
                    return scope.bridge;
                } catch (Throwable t) { if (failure == null) failure = t; else failure.addSuppressed(t); }
            }
            throw new IllegalStateException("DexKit bridge unavailable for " + scope, failure);
        }
    }

    private static DexKitBridge configure(DexKitBridge bridge) {
        try { bridge.setThreadNum(BRIDGE_THREADS); } catch (Throwable ignored) { }
        return bridge;
    }

    private static void scheduleIdleCloseLocked(Scope scope) {
        if (scope.bridge == null) return;
        if (closer == null) {
            closer = new ScheduledThreadPoolExecutor(1, runnable -> {
                Thread t = new Thread(runnable, "ColorPods-DexKitCloser");
                t.setDaemon(true);
                return t;
            });
            closer.setRemoveOnCancelPolicy(true);
        }
        if (scope.pendingClose != null) scope.pendingClose.cancel(false);
        scope.pendingClose = closer.schedule(() -> closeIfIdle(scope),
                IDLE_CLOSE_DELAY_MS, TimeUnit.MILLISECONDS);
    }

    private static void closeIfIdle(Scope scope) {
        synchronized (LOCK) {
            scope.pendingClose = null;
            if (scope.leases != 0 || scope.bridge == null) return;
            long idle = SystemClock.uptimeMillis() - scope.lastAccess;
            if (idle < IDLE_CLOSE_DELAY_MS) { scheduleIdleCloseLocked(scope); return; }
            scope.invalidate();
        }
    }

    private static void loadNativeLocked() {
        if (nativeLoaded) return;
        try {
            System.loadLibrary("dexkit");
            nativeLoaded = true;
            return;
        } catch (UnsatisfiedLinkError ignored) { }
        String abi = Process.is64Bit() ? "arm64-v8a" : "armeabi-v7a";
        String[] candidates = {
                nativeLibraryDir == null ? null : nativeLibraryDir + "/libdexkit.so",
                moduleApkPath == null ? null
                        : new File(moduleApkPath).getParentFile() + "/lib/" + abi + "/libdexkit.so",
        };
        for (String path : candidates) {
            if (path == null || !new File(path).isFile()) continue;
            try { System.load(path); nativeLoaded = true; return; }
            catch (UnsatisfiedLinkError ignored) { }
        }
        throw new UnsatisfiedLinkError("libdexkit.so not found in " + nativeLibraryDir + " / " + moduleApkPath);
    }

    private static final class Scope {
        ClassLoader loader;
        String sourceDir;
        DexKitBridge bridge;
        ScheduledFuture<?> pendingClose;
        int leases;
        long lastAccess = SystemClock.uptimeMillis();

        Scope(ClassLoader loader, String sourceDir) { this.loader = loader; this.sourceDir = sourceDir; }

        void invalidate() {
            DexKitBridge current = bridge;
            bridge = null;
            if (pendingClose != null) { pendingClose.cancel(false); pendingClose = null; }
            if (current != null) try { current.close(); } catch (Throwable ignored) { }
        }

        @Override public String toString() { return "scope(" + sourceDir + ")"; }
    }
}
