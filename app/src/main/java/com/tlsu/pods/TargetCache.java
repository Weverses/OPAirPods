// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-level cache for DexKit resolutions.
 *
 * <p>Level 1 is an in-process map (a host process lives for a long time, so a
 * resolution normally happens at most once). Level 2 stores compact member
 * descriptors in the module's remote preferences, so a restarted host process
 * can rebuild a {@link Method}/{@link Field} by name without scanning dex again.
 * Entries are namespaced by a token derived from the host APK, so an OTA that
 * changes the dex invalidates every cached symbol automatically.</p>
 */
final class TargetCache {
    private static final Object NULL_VALUE = new Object();
    private static final ConcurrentHashMap<String, Object> MEMORY = new ConcurrentHashMap<>();
    private static volatile SharedPreferences prefs;
    private static volatile String token = "";

    private TargetCache() {}

    static void init(ColorPodsModule module, ApplicationInfo appInfo, String packageName) {
        try { prefs = module.getRemotePreferences("colorpods_dexkit_index"); }
        catch (Throwable t) { prefs = null; }
        String nextToken = hostToken(appInfo, packageName);
        // Keys are already namespaced by token, so a new host simply makes the
        // old entries unreachable; clearing avoids retaining members forever.
        if (!nextToken.equals(token)) MEMORY.clear();
        token = nextToken;
    }

    static String hostToken(ApplicationInfo appInfo, String packageName) {
        if (appInfo == null || appInfo.sourceDir == null) return packageName == null ? "" : packageName;
        File apk = new File(appInfo.sourceDir);
        return packageName + ":" + apk.getName() + ":" + apk.length() + ":" + apk.lastModified();
    }

    static String token() { return token; }

    private static String scoped(String key) {
        File ext = new File("/system_ext/framework/oplus_bluetooth_common_ext.jar");
        String extVersion = key.startsWith(DexKitHost.BLUETOOTH_EXT + ":")
                ? ":" + ext.length() + ":" + ext.lastModified() : "";
        return "fingerprints-v4|" + token + extVersion + "|" + key;
    }

    static Object get(String key) {
        Object value = MEMORY.get(scoped(key));
        return value == NULL_VALUE ? null : value;
    }

    static boolean contains(String key) { return MEMORY.containsKey(scoped(key)); }

    static void put(String key, Object value) {
        MEMORY.put(scoped(key), value == null ? NULL_VALUE : value);
    }

    static boolean isMissing(String key) {
        return (contains(key) && get(key) == null) ||
                (prefs != null && safeString(loadKey(key, "missing")) != null);
    }

    static void putMissing(String key) {
        put(key, null);
        SharedPreferences store = prefs;
        if (store != null) try { store.edit().putString(loadKey(key, "missing"), "1").apply(); }
        catch (Throwable ignored) { }
    }

    static String loadDescriptor(String key) {
        SharedPreferences store = prefs;
        return store == null ? null : safeString(loadKey(key, "descriptor"));
    }

    static void saveDescriptor(String key, String descriptor) {
        SharedPreferences store = prefs;
        if (store == null || descriptor == null || descriptor.isEmpty()) return;
        try {
            store.edit()
                    .putString(loadKey(key, "descriptor"), descriptor)
                    .remove(loadKey(key, "missing"))
                    .apply();
        } catch (Throwable ignored) { }
    }

    private static String loadKey(String key, String suffix) { return scoped(key) + "." + suffix; }

    private static String safeString(String key) {
        try { return prefs.getString(key, null); } catch (Throwable t) { return null; }
    }

    /** Compact, human-readable descriptor of a resolved class/method/field. */
    static final class Descriptor {
        enum Kind { CLASS, METHOD, CONSTRUCTOR, FIELD }

        final Kind kind;
        final String className;
        final String memberName;
        final List<String> paramTypes;
        final String valueType;

        private Descriptor(Kind kind, String className, String memberName,
                           List<String> paramTypes, String valueType) {
            this.kind = kind;
            this.className = className;
            this.memberName = memberName;
            this.paramTypes = paramTypes;
            this.valueType = valueType;
        }

        static Descriptor ofClass(Class<?> cls) {
            return new Descriptor(Kind.CLASS, cls.getName(), "", List.of(), "");
        }

        static Descriptor ofMethod(Method method) {
            return new Descriptor(Kind.METHOD, method.getDeclaringClass().getName(), method.getName(),
                    typeNames(method.getParameterTypes()), typeName(method.getReturnType()));
        }

        static Descriptor ofConstructor(Constructor<?> ctor) {
            return new Descriptor(Kind.CONSTRUCTOR, ctor.getDeclaringClass().getName(), "",
                    typeNames(ctor.getParameterTypes()), "void");
        }

        static Descriptor ofField(Field field) {
            return new Descriptor(Kind.FIELD, field.getDeclaringClass().getName(), field.getName(),
                    List.of(), typeName(field.getType()));
        }

        /** Format: {@code kind|class|member|param1,param2|valueType}. */
        String encode() {
            return kind.name() + "|" + className + "|" + memberName + "|"
                    + String.join(",", paramTypes) + "|" + valueType;
        }

        static Descriptor parse(String text) {
            if (text == null) return null;
            String[] parts = text.split("\\|", -1);
            if (parts.length != 5 || parts[1].isEmpty()) return null;
            Kind kind;
            try { kind = Kind.valueOf(parts[0]); } catch (IllegalArgumentException e) { return null; }
            List<String> params = parts[3].isEmpty() ? List.of()
                    : new ArrayList<>(Arrays.asList(parts[3].split(",", -1)));
            return new Descriptor(kind, parts[1], parts[2], params, parts[4]);
        }

        /** Rebuilds the member without any dex scanning. */
        Object resolve(ClassLoader loader) throws ReflectiveOperationException {
            Class<?> owner = loadClass(className, loader);
            switch (kind) {
                case CLASS:
                    return owner;
                case FIELD:
                    Field field = findField(owner, memberName);
                    if (!typeName(field.getType()).equals(valueType)) {
                        throw new NoSuchFieldException("stale field type for " + className + "." + memberName);
                    }
                    field.setAccessible(true);
                    return field;
                case CONSTRUCTOR:
                    Constructor<?> ctor = owner.getDeclaredConstructor(resolveTypes(params(), loader));
                    ctor.setAccessible(true);
                    return ctor;
                case METHOD:
                default:
                    Method method = findMethod(owner, memberName, resolveTypes(params(), loader));
                    if (!typeName(method.getReturnType()).equals(valueType)) {
                        throw new NoSuchMethodException("stale signature for " + className + "." + memberName);
                    }
                    method.setAccessible(true);
                    return method;
            }
        }

        private List<String> params() { return paramTypes == null ? List.of() : paramTypes; }

        private static Field findField(Class<?> owner, String name) throws NoSuchFieldException {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) { }
            }
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }

        private static Method findMethod(Class<?> owner, String name, Class<?>[] signature)
                throws NoSuchMethodException {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try { return c.getDeclaredMethod(name, signature); } catch (NoSuchMethodException ignored) { }
            }
            throw new NoSuchMethodException(owner.getName() + "." + name);
        }

        private static Class<?>[] resolveTypes(List<String> names, ClassLoader loader)
                throws ClassNotFoundException {
            Class<?>[] types = new Class<?>[names.size()];
            for (int i = 0; i < names.size(); i++) types[i] = loadClass(names.get(i), loader);
            return types;
        }

        private static Class<?> loadClass(String name, ClassLoader loader) throws ClassNotFoundException {
            String normalized = normalize(name);
            Class<?> primitive = primitive(normalized);
            return primitive != null ? primitive : Class.forName(normalized, false, loader);
        }

        /** Accepts both dex style ("[B") and source style ("byte[]", "String[]"). */
        static String normalize(String name) {
            if (name == null || !name.endsWith("[]")) return name;
            String component = normalize(name.substring(0, name.length() - 2));
            Class<?> primitive = primitive(component);
            if (primitive == null) return "[L" + component + ";";
            if (primitive == boolean.class) return "[Z";
            if (primitive == byte.class) return "[B";
            if (primitive == char.class) return "[C";
            if (primitive == short.class) return "[S";
            if (primitive == int.class) return "[I";
            if (primitive == long.class) return "[J";
            if (primitive == float.class) return "[F";
            if (primitive == double.class) return "[D";
            return name;
        }

        private static Class<?> primitive(String name) {
            switch (name) {
                case "boolean": return boolean.class;
                case "byte": return byte.class;
                case "char": return char.class;
                case "short": return short.class;
                case "int": return int.class;
                case "long": return long.class;
                case "float": return float.class;
                case "double": return double.class;
                case "void": return void.class;
                default: return null;
            }
        }

        static String typeName(Class<?> type) {
            // Class.getName() also covers arrays ("[B", "[Ljava.lang.String;"),
            // which Class.forName() accepts unchanged.
            return type.getName();
        }

        static List<String> typeNames(Class<?>[] types) {
            List<String> names = new ArrayList<>(types.length);
            for (Class<?> type : types) names.add(typeName(type));
            return names;
        }

        @Override public boolean equals(Object other) {
            return other instanceof Descriptor && encode().equals(((Descriptor) other).encode());
        }

        @Override public int hashCode() { return Objects.hash(encode()); }

        @Override public String toString() { return encode(); }
    }
}
