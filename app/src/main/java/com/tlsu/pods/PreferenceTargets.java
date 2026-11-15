// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;
import java.util.ArrayList;
import java.util.List;
import static com.tlsu.pods.DexQueries.*;

/** Preference semantics traced through persistence, click dispatch and list selection. */
final class PreferenceTargets {
    static final String PERSISTENT="ui.preference.persistent", VISIBLE="ui.preference.visible";
    static final String ENABLED="ui.preference.enabled", SELECTABLE="ui.preference.selectable";
    static final String CHECKED="ui.preference.checked", NOTIFY="ui.preference.notify";
    static final String PARENT="ui.preference.parent", CLICK="ui.preference.clickListener", CHANGE="ui.preference.changeListener";
    static final String ENTRIES="ui.preference.entries", VALUES="ui.preference.entryValues", DIALOG_TITLE="ui.preference.dialogTitle";
    private static final String PREF="androidx.preference.Preference", LIST="androidx.preference.ListPreference";
    private static final String[] BOOL={"boolean"}, NONE={};
    private PreferenceTargets() {}
    static void register() {
        HostTargets.fingerprintMethod(PERSISTENT, BOOL, "void", b -> {
            MethodData persist=one(methods(b, new MethodMatcher().declaredClass(PREF).paramTypes("boolean").returnType("boolean")
                    .addInvoke(invoke("android.content.SharedPreferences$Editor","putBoolean","android.content.SharedPreferences$Editor","java.lang.String","boolean"))));
            List<MethodData> result=new ArrayList<>();
            for(MethodData guard: persist.getInvokes()) if(shape(guard,PREF,"boolean"))
                for(MethodData getter:guard.getInvokes()) if(shape(getter,PREF,"boolean")) {
                    var fs=fields(getter,PREF,"boolean",true);
                    if(fs.size()==1) for(MethodData writer:writers(fs.get(0),PREF,"boolean"))
                        if(!result.contains(writer))result.add(writer);
                }
            return result;
        });
        HostTargets.fingerprintMethod(SELECTABLE, BOOL, "void", b -> {
            MethodData click=one(methods(b,new MethodMatcher().declaredClass(PREF).paramTypes().returnType("void")
                    .addInvoke(invoke("android.content.Context","startActivity","void","android.content.Intent"))));
            List<MethodData> result=new ArrayList<>();
            for(MethodData getter:click.getInvokes()) if(shape(getter,PREF,"boolean")) {
                var fs=fields(getter,PREF,"boolean",true);
                if(fs.size()==1)for(MethodData writer:writers(fs.get(0),PREF,"boolean"))
                    if(!result.contains(writer))result.add(writer);
            }
            return result;
        });
        HostTargets.fingerprintMethod(ENABLED, BOOL, "void", b -> methods(b,new MethodMatcher().declaredClass(PREF)
                .paramTypes("boolean").returnType("void")
                .addInvoke(new MethodMatcher().declaredClass(PREF).paramTypes("boolean").returnType("void"))));
        HostTargets.fingerprintMethod(VISIBLE, BOOL, "void", b -> {
            return filter(methods(b,new MethodMatcher().declaredClass(PREF).paramTypes("boolean").returnType("void")), m ->
                    m.getInvokes().stream().anyMatch(i -> i.getDeclaredClassName().startsWith(PREF+"$") && shape(i,null,"void",PREF)));
        });
        HostTargets.fingerprintMethod(CHECKED, BOOL, "void", b -> methods(b,new MethodMatcher().declaredClass("androidx.preference.TwoStatePreference")
                .paramTypes("boolean").returnType("void")
                .addInvoke(new MethodMatcher().declaredClass(PREF).paramTypes("boolean").returnType("boolean"))));
        HostTargets.fingerprintMethod(NOTIFY, NONE, "void", b -> {
            MethodData summary=one(methods(b,new MethodMatcher().declaredClass(PREF).paramTypes("java.lang.CharSequence").returnType("void")
                    .usingStrings("Preference already has a SummaryProvider set.")));
            return filter(summary.getInvokes(),m -> shape(m,PREF,"void"));
        });
        HostTargets.fingerprintMethod(PARENT,NONE,"androidx.preference.PreferenceGroup",b -> methods(b,new MethodMatcher()
                .declaredClass(PREF).paramTypes().returnType("androidx.preference.PreferenceGroup")));
        listener(CLICK,false);listener(CHANGE,true);
        HostTargets.fingerprintField(VALUES,"[Ljava.lang.CharSequence;",PreferenceTargets::valueFields);
        HostTargets.fingerprintField(ENTRIES,"[Ljava.lang.CharSequence;",b -> {
            List<FieldData> result=new ArrayList<>();
            for(MethodData m:methods(b,new MethodMatcher().declaredClass(LIST).paramTypes().returnType("java.lang.CharSequence")))
                for(FieldData f:fields(m,LIST,"java.lang.CharSequence[]",true))if(!result.contains(f))result.add(f);
            return result;
        });
        // The title is the sole CharSequence field written twice: once from
        // dialogTitle, then from the preference title when that value is null.
        HostTargets.fingerprintField(DIALOG_TITLE,"java.lang.CharSequence",b -> {
            MethodData ctor=one(methods(b,new MethodMatcher().declaredClass("androidx.preference.DialogPreference").name("<init>")
                    .paramTypes("android.content.Context","android.util.AttributeSet","int","int")));
            java.util.Map<FieldData,Integer> writes=new java.util.LinkedHashMap<>();
            for(var use:ctor.getUsingFields())if(use.getUsingType().isWrite()
                    && use.getField().getDeclaredClassName().equals("androidx.preference.DialogPreference")
                    && use.getField().getTypeName().equals("java.lang.CharSequence"))
                writes.merge(use.getField(),1,Integer::sum);
            List<FieldData> result=new ArrayList<>();
            for(var entry:writes.entrySet())if(entry.getValue()==2)result.add(entry.getKey());
            return result;
        });
    }
    private static List<?> valueFields(DexKitBridge b) {
        MethodData index=one(methods(b,new MethodMatcher().declaredClass(LIST).paramTypes("java.lang.String").returnType("int")));
        return fields(index,LIST,"java.lang.CharSequence[]",true);
    }
    private static void listener(String key,boolean change) {
        HostTargets.fingerprintMethod(key,new String[]{null},"void",b -> {
            String[] args=change?new String[]{PREF,"java.lang.Object"}:new String[]{PREF};
            var callbacks=filter(methods(b,new MethodMatcher().paramTypes(args).returnType("boolean")),m ->
                    m.getDeclaredClassName().startsWith(PREF+"$") && java.lang.reflect.Modifier.isAbstract(m.getModifiers()));
            MethodData callback=one(callbacks);
            return methods(b,new MethodMatcher().declaredClass(PREF).paramTypes(callback.getDeclaredClassName()).returnType("void"));
        });
    }
}
