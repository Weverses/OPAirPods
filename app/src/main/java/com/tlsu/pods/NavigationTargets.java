// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import java.util.List;
import static com.tlsu.pods.DexQueries.*;

/** Fragment operations resolved from transaction diagnostics and operation codes. */
final class NavigationTargets {
    static final String ATTACH="ui.navigation.attachPreferences", FRAGMENTS="ui.navigation.fragments";
    static final String SAVED="ui.navigation.stateSaved", BEGIN="ui.navigation.begin", ANIMATIONS="ui.navigation.animations";
    static final String HIDE="ui.navigation.hide", ADD="ui.navigation.add", BACKSTACK="ui.navigation.backStack";
    static final String COMMIT="ui.navigation.commit", POP="ui.navigation.pop";
    private static final String FM="androidx.fragment.app.FragmentManager", F="androidx.fragment.app.Fragment";
    private static final String[] NONE={};
    private NavigationTargets() {}
    static void register() {
        HostTargets.fingerprintMethod(ATTACH,new String[]{null},"void",b -> {
            // PreferenceManager owns the '_preferences' SharedPreferences name.
            MethodData preferences=one(methods(b,new MethodMatcher().paramTypes("android.content.Context").returnType("java.lang.String")
                    .usingStrings("_preferences")));
            return methods(b,new MethodMatcher().declaredClass("androidx.preference.Preference")
                    .paramTypes(preferences.getDeclaredClassName()).returnType("void"));
        });
        HostTargets.fingerprintMethod(FRAGMENTS,NONE,"java.util.List",b -> methods(b,new MethodMatcher().declaredClass(FM).paramTypes().returnType("java.util.List")));
        HostTargets.fingerprintMethod(SAVED,NONE,"boolean",b -> {
            MethodData check=one(methods(b,new MethodMatcher().declaredClass(FM).paramTypes().returnType("void")
                    .usingStrings("Can not perform this action after onSaveInstanceState")));
            return filter(check.getInvokes(),m -> shape(m,FM,"boolean"));
        });
        HostTargets.fingerprintMethod(BEGIN,NONE,null,b -> methods(b,new MethodMatcher().declaredClass(FM).paramTypes().returnType(transaction(b))));
        HostTargets.fingerprintMethod(ANIMATIONS,new String[]{"int","int","int","int"},null,b -> methods(b,new MethodMatcher()
                .declaredClass(transaction(b)).paramTypes("int","int","int","int").returnType(transaction(b))));
        HostTargets.fingerprintMethod(HIDE,new String[]{F},null,b -> methods(b,new MethodMatcher().paramTypes(F)
                .usingStrings("Cannot hide Fragment attached to a different FragmentManager")));
        HostTargets.fingerprintMethod(ADD,new String[]{"int",F,"java.lang.String"},null,b -> methods(b,new MethodMatcher()
                .declaredClass(transaction(b)).paramTypes("int",F,"java.lang.String").returnType(transaction(b)).usingNumbers(1)
                .addInvoke(new MethodMatcher().declaredClass(transaction(b)).paramTypes("int",F,"java.lang.String","int").returnType("void"))));
        HostTargets.fingerprintMethod(BACKSTACK,new String[]{"java.lang.String"},null,b -> List.of(backStack(b)));
        HostTargets.fingerprintMethod(COMMIT,NONE,"int",b -> {
            MethodData commit=one(methods(b,new MethodMatcher().paramTypes("boolean").returnType("int").usingStrings("commit already called")));
            return methods(b,new MethodMatcher().declaredClass(commit.getDeclaredClassName()).paramTypes().returnType("int")
                    .usingNumbers(0).addInvoke(new MethodMatcher(commit.getDescriptor())));
        });
        HostTargets.fingerprintMethod(POP,NONE,"boolean",b -> methods(b,new MethodMatcher().declaredClass(FM).paramTypes().returnType("boolean")
                .usingNumbers(-1,0).addInvoke(new MethodMatcher().declaredClass(FM).paramTypes("java.lang.String","int","int").returnType("boolean"))));
    }
    private static MethodData backStack(DexKitBridge b) {
        return one(methods(b,new MethodMatcher().paramTypes("java.lang.String")
                .usingStrings("This FragmentTransaction is not allowed to be added to the back stack.")));
    }
    private static String transaction(DexKitBridge b) { return backStack(b).getDeclaredClassName(); }
}
