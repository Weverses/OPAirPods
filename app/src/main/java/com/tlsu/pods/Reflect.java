// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

final class Reflect {
    private Reflect() {}
    static Object call(Object instance, String name, Class<?>[] signature, Object... args) throws ReflectiveOperationException {
        return method(instance.getClass(), name, signature).invoke(instance, args);
    }
    static Method method(Class<?> cls, String name, Class<?>... signature) throws NoSuchMethodException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod(name, signature); m.setAccessible(true); return m; }
            catch (NoSuchMethodException ignored) {}
        }
        throw new NoSuchMethodException(cls.getName() + "." + name);
    }
    static Object field(Object instance, String name) throws ReflectiveOperationException {
        for (Class<?> c = instance.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(instance); }
            catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(name);
    }
}
