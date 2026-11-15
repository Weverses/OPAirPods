// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.FieldUsingType;
import org.luckypray.dexkit.result.MethodData;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

/** Small graph operations. No obfuscated names, ordinal selection or candidate preference. */
final class DexQueries {
    private DexQueries() {}
    static List<MethodData> methods(DexKitBridge b, MethodMatcher matcher) {
        return b.findMethod(new FindMethod().matcher(matcher));
    }
    static MethodData one(List<MethodData> methods) {
        if (methods.size() != 1) throw new IllegalStateException("Expected one method, found " + methods);
        return methods.get(0);
    }
    static List<MethodData> filter(Iterable<MethodData> source, Predicate<MethodData> predicate) {
        List<MethodData> result = new ArrayList<>();
        for (MethodData m : source) if (predicate.test(m) && !result.contains(m)) result.add(m);
        return result;
    }
    static boolean shape(MethodData m, String owner, String ret, String... params) {
        return (owner == null || owner.equals(m.getDeclaredClassName()))
                && (ret == null || ret.equals(m.getReturnTypeName()))
                && m.getParamTypeNames().equals(Arrays.asList(params));
    }
    static List<FieldData> fields(MethodData method, String owner, String type, boolean read) {
        List<FieldData> result = new ArrayList<>();
        for (var use : method.getUsingFields()) {
            FieldData f = use.getField();
            if ((owner == null || owner.equals(f.getDeclaredClassName()))
                    && (type == null || type.equals(f.getTypeName()))
                    && use.getUsingType() == (read ? FieldUsingType.Read : FieldUsingType.Write)
                    && !result.contains(f)) result.add(f);
        }
        return result;
    }
    static FieldData oneField(List<FieldData> fields) {
        if (fields.size() != 1) throw new IllegalStateException("Expected one field, found " + fields);
        return fields.get(0);
    }
    static List<MethodData> writers(FieldData field, String owner, String... params) {
        return filter(field.getWriters(), m -> shape(m, owner, "void", params));
    }
    static MethodMatcher invoke(String owner, String name, String ret, String... args) {
        return new MethodMatcher().declaredClass(owner).name(name).returnType(ret).paramTypes(args);
    }
}
