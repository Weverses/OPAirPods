// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindField;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.FieldMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.lang.reflect.Modifier;
import java.util.List;
import static com.tlsu.pods.DexQueries.*;

/** Koin and Kotlin reflection are followed by their graph, never by R8 identifiers. */
final class CapsuleTargets {
    static final String APPLICATION="ui.capsule.application", BASE="ui.capsule.baseSingleton";
    static final String REGISTRY="ui.capsule.scopeRegistry", ROOT="ui.capsule.rootScope", GET="ui.capsule.resolveDependency";
    static final String KCLASS="ui.capsule.kotlinClass", CURRENT="ui.capsule.currentCard", SHOWING="ui.capsule.isShowing", UTILS="ui.capsule.utils";
    private static final String BASE_CLASS="com.heytap.mydevices.common.BaseApplication";
    private static final String HELPER="com.heytap.mydevices.core.seedling.SeedlingCardHelper";
    private static final String INFO="com.oplus.mydevices.domain.entities.linkage.CapsuleInfo", SCOPE="org.koin.core.scope.Scope";
    private CapsuleTargets() {}
    static void register() {
        HostTargets.fingerprintField(BASE,BASE_CLASS,b -> b.findField(new FindField().matcher(new FieldMatcher()
                .declaredClass(BASE_CLASS).type(BASE_CLASS).modifiers(Modifier.STATIC))));
        HostTargets.fingerprintMethod(APPLICATION,new String[]{},"android.content.Context",b -> methods(b,new MethodMatcher()
                .declaredClass(BASE_CLASS).paramTypes().returnType("android.content.Context").modifiers(Modifier.PUBLIC)));
        HostTargets.fingerprintMethod(ROOT,new String[]{},SCOPE,b -> {
            var classes=b.findClass(new FindClass().matcher(new ClassMatcher().usingEqStrings("_root_")
                    .addMethod(new MethodMatcher().paramTypes().returnType(SCOPE))));
            if(classes.size()!=1)throw new IllegalStateException("Ambiguous Koin root registry: "+classes);
            return methods(b,new MethodMatcher().declaredClass(classes.get(0).getName()).paramTypes().returnType(SCOPE));
        });
        HostTargets.fingerprintMethod(REGISTRY,new String[]{},null,b -> {
            MethodData koin=one(methods(b,new MethodMatcher().declaredClass(BASE_CLASS).name("getKoin").paramTypes()));
            MethodData root=(MethodData)HostTargets.fingerprintMatches(ROOT,b).get(0);
            return methods(b,new MethodMatcher().declaredClass(koin.getReturnTypeName()).paramTypes().returnType(root.getDeclaredClassName()));
        });
        HostTargets.fingerprintMethod(GET,new String[]{null,null,null},"java.lang.Object",b -> List.of(resolveDependency(b)));
        // Reflection's Class -> KClass wrapper lives in the same class as the
        // optional JVM ReflectionFactory loader. Its output is Koin's clazz type.
        HostTargets.fingerprintStaticMethod(KCLASS,new String[]{"java.lang.Class"},null,b -> {
            var classes=b.findClass(new FindClass().matcher(new ClassMatcher().usingEqStrings("kotlin.reflect.jvm.internal.ReflectionFactoryImpl")));
            if(classes.size()!=1)throw new IllegalStateException("Ambiguous Kotlin reflection facade: "+classes);
            return methods(b,new MethodMatcher().declaredClass(classes.get(0).getName()).paramTypes("java.lang.Class")
                    .returnType(resolveDependency(b).getParamTypeNames().get(0)));
        });
        HostTargets.fingerprintMethod(CURRENT,new String[]{},INFO,b -> {
            MethodData end=one(methods(b,new MethodMatcher().declaredClass(HELPER).paramTypes().returnType("void").usingEqStrings("sendEndSeedling")));
            var field=oneField(fields(end,HELPER,INFO,false));
            return filter(field.getReaders(),m -> shape(m,HELPER,INFO));
        });
        HostTargets.fingerprintMethod(SHOWING,new String[]{},"boolean",b -> {
            MethodData end=one(methods(b,new MethodMatcher().declaredClass(HELPER).paramTypes().returnType("void").usingEqStrings("sendEndSeedling")));
            var field=oneField(fields(end,HELPER,"boolean",false));
            return filter(field.getReaders(),m -> shape(m,HELPER,"boolean"));
        });
        HostTargets.fingerprintField(UTILS,null,b -> {
            var hits=HostTargets.fingerprintMatches(HostTargets.UI_DEVICE_INFO,b);
            if(hits.size()!=1)throw new IllegalStateException("AirPods utilities unavailable");
            String companion=((MethodData)hits.get(0)).getDeclaredClassName();
            int nested=companion.lastIndexOf('$');
            if(nested<0)throw new IllegalStateException("AirPods companion has no enclosing class");
            return b.findField(new FindField().matcher(new FieldMatcher().declaredClass(companion.substring(0,nested))
                    .type(companion).modifiers(Modifier.STATIC)));
        });
    }
    private static MethodData resolveDependency(DexKitBridge b) {
        return one(methods(b,new MethodMatcher().declaredClass(SCOPE).paramCount(3).returnType("java.lang.Object")
                .usingEqStrings("clazz","|- '","' in ")));
    }
}
