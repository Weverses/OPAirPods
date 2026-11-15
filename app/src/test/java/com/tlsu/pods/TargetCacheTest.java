// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public class TargetCacheTest {
    static class Sample {
        static String label;
        Sample() { }
        Sample(String value) { }
        boolean work(String name, int count, byte[] payload) { return false; }
    }

    @Test public void methodDescriptorRoundTripsAndResolves() throws Exception {
        Method method = Sample.class.getDeclaredMethod("work", String.class, int.class, byte[].class);
        TargetCache.Descriptor descriptor = TargetCache.Descriptor.ofMethod(method);
        TargetCache.Descriptor parsed = TargetCache.Descriptor.parse(descriptor.encode());
        assertNotNull(parsed);
        assertEquals(descriptor, parsed);
        assertEquals("METHOD", parsed.kind.name());
        assertEquals("[B", parsed.paramTypes.get(2));
        assertEquals(method, parsed.resolve(getClass().getClassLoader()));
    }

    @Test public void classFieldAndConstructorDescriptorsRoundTrip() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        TargetCache.Descriptor cls = TargetCache.Descriptor.parse(
                TargetCache.Descriptor.ofClass(Sample.class).encode());
        assertNotNull(cls);
        assertSame(Sample.class, cls.resolve(loader));

        Field field = Sample.class.getDeclaredField("label");
        TargetCache.Descriptor fieldDescriptor = TargetCache.Descriptor.parse(
                TargetCache.Descriptor.ofField(field).encode());
        assertNotNull(fieldDescriptor);
        assertEquals("FIELD", fieldDescriptor.kind.name());
        assertEquals(field, fieldDescriptor.resolve(loader));

        Constructor<?> ctor = Sample.class.getDeclaredConstructor(String.class);
        TargetCache.Descriptor ctorDescriptor = TargetCache.Descriptor.parse(
                TargetCache.Descriptor.ofConstructor(ctor).encode());
        assertNotNull(ctorDescriptor);
        assertEquals("CONSTRUCTOR", ctorDescriptor.kind.name());
        assertEquals(ctor, ctorDescriptor.resolve(loader));
    }

    @Test public void arrayTypeNamesAreNormalized() {
        assertEquals("[B", TargetCache.Descriptor.normalize("byte[]"));
        assertEquals("[I", TargetCache.Descriptor.normalize("int[]"));
        assertEquals("[Ljava.lang.String;", TargetCache.Descriptor.normalize("java.lang.String[]"));
        assertEquals("[B", TargetCache.Descriptor.normalize("[B"));
        assertEquals("boolean", TargetCache.Descriptor.normalize("boolean"));
        assertEquals("[Lfoo.Bar;", TargetCache.Descriptor.normalize("foo.Bar[]"));
    }

    @Test public void malformedDescriptorsAreRejected() {
        assertNull(TargetCache.Descriptor.parse(null));
        assertNull(TargetCache.Descriptor.parse(""));
        assertNull(TargetCache.Descriptor.parse("not-a-descriptor"));
        assertNull(TargetCache.Descriptor.parse("UNKNOWN|foo.Bar|x||void"));
        assertNull(TargetCache.Descriptor.parse("METHOD||x||void"));
        assertNull(TargetCache.Descriptor.parse("METHOD|foo.Bar|x"));
    }

    @Test public void hostTokenIsStableAndNamespaced() {
        assertEquals("com.example", TargetCache.hostToken(null, "com.example"));
        assertEquals("", TargetCache.hostToken(null, null));
    }
}
