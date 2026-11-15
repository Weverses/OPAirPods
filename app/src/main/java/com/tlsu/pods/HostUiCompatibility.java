// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.content.Context;
import android.util.AttributeSet;

/** Validate the currently supported host preference ABI before changing its screen. */
final class HostUiCompatibility {
    private HostUiCompatibility() {}

    static void verify(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> screen = Class.forName("androidx.preference.PreferenceScreen", false, loader);
        for(String key:new String[]{PreferenceTargets.PERSISTENT,PreferenceTargets.VISIBLE,
                PreferenceTargets.SELECTABLE,PreferenceTargets.ENABLED,PreferenceTargets.CHECKED,
                PreferenceTargets.NOTIFY,PreferenceTargets.PARENT,PreferenceTargets.CLICK,PreferenceTargets.CHANGE,PreferenceTargets.BIND})
            if(HostTargets.methodOrNull(key,loader)==null)throw new NoSuchMethodException("Unresolved host target "+key);
        for(String key:new String[]{HostTargets.UI_PREF_ADD,HostTargets.UI_PREF_FIND,HostTargets.UI_PREF_SUMMARY,HostTargets.UI_PREF_KEY,HostTargets.UI_PREF_TITLE,HostTargets.UI_PREF_CHILD,HostTargets.UI_PREF_COUNT,HostTargets.UI_PREF_ORDER,HostTargets.UI_PREF_REMOVE})
            if(HostTargets.methodOrNull(key,loader)==null)throw new NoSuchMethodException("Unresolved host target "+key);
        screen.getConstructor(Context.class, AttributeSet.class);
        for (String name : new String[]{"COUIPreferenceCategory", "COUIJumpPreference",
                "COUIPreference", "COUISwitchPreference", "COUIListPreference"}) {
            Class<?> type = Class.forName("com.coui.appcompat.preference." + name, false, loader);
            try { type.getConstructor(Context.class); }
            catch (NoSuchMethodException e) { type.getConstructor(Context.class, AttributeSet.class); }
        }
        for(String key:new String[]{PreferenceTargets.ENTRIES,PreferenceTargets.VALUES,PreferenceTargets.DIALOG_TITLE})
            if(HostTargets.fieldOrNull(key,loader)==null)throw new NoSuchFieldException("Unresolved host target "+key);
    }

}
