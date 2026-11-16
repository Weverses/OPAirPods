// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

final class NativeUi {
    private static final Map<Object, HostPreferences> CONTROLLERS = new HashMap<>();
    private static String activityName;
    private static String preferencesFragmentName;

    private NativeUi() {}

    static void install(ColorPodsModule module, ClassLoader loader) {
        Class<?> fragment = HostTargets.typeOrNull(HostTargets.UI_FRAGMENT_CLASS, loader);
        Class<?> activity = HostTargets.typeOrNull(HostTargets.UI_ACTIVITY_CLASS, loader);
        if (fragment == null || activity == null) {
            throw new IllegalStateException("Host AirPods UI classes not resolved");
        }
        activityName = activity.getName();
        preferencesFragmentName = HostTargets.memberName(HostTargets.UI_ACTIVITY_PREFERENCES_FRAGMENT, loader);
        if (preferencesFragmentName == null) throw new IllegalStateException("Host preferences fragment accessor unresolved");

        hook(module,loader,HostTargets.UI_NOISE_SELECTION,chain->{
            Object result=chain.proceed();
            for(HostPreferences p:CONTROLLERS.values())p.listeningSelectorChanged((View)chain.getThisObject());
            return result;
        });
        // Native selector rebuilds and clicks can inline its private selection
        // setter. Deoptimize this small widget's callers so expansion is immediate.
        Method selection=HostTargets.methodOrNull(HostTargets.UI_NOISE_SELECTION,loader);
        if(selection!=null)for(Method caller:selection.getDeclaringClass().getDeclaredMethods()){
            if(caller.equals(selection))continue;
            try{module.deoptimize(caller);}catch(Throwable e){module.error("Selector caller deoptimization failed",e);}
        }

        hook(module,loader,PreferenceTargets.BIND,chain->{
            Object result=chain.proceed();
            try{SystemSettingsSlider.bind(chain.getThisObject(),chain.getArg(0));}
            catch(Exception e){module.error("System COUI slider binding failed",e);}
            return result;
        });
        // Native observers keep their original preference objects even while
        // another host fragment displays one of the feature pages.
        hook(module, loader, HostTargets.UI_FRAGMENT_FIND_PREFERENCE, chain -> {
            HostPreferences p = CONTROLLERS.get(chain.getThisObject());
            return p == null ? chain.proceed() : p.findPreference((CharSequence) chain.getArg(0));
        });
        hook(module, loader, HostTargets.UI_ACTIVITY_TOOLBAR_LAMBDA, chain ->
                back((Activity) chain.getArg(0)) ? null : chain.proceed());
        // Gesture back is handled by the host FragmentManager's native stack.
        hook(module, loader, HostTargets.UI_ACTIVITY_BACK_PRESSED, chain ->
                back((Activity) chain.getThisObject()) ? null : chain.proceed());
        hook(module, loader, HostTargets.UI_ACTIVITY_RESUME, chain -> {
            Object result = chain.proceed();
            Object home = preferencesFragment(chain.getThisObject());
            HostPreferences p = CONTROLLERS.get(home);
            if (p != null) p.resume();
            return result;
        });
        hook(module, loader, HostTargets.UI_ACTIVITY_PAUSE, chain -> {
            // onPause may be inherited from Activity; the hook then observes
            // other activities in this process as well.
            if(!activityName.equals(chain.getThisObject().getClass().getName()))return chain.proceed();
            Object home=preferencesFragment(chain.getThisObject());
            HostPreferences p=CONTROLLERS.get(home);if(p!=null)p.pauseHeadMotion();
            return chain.proceed();
        });
        hook(module, loader, HostTargets.UI_SUBPAGE_CREATE_PREFS, chain -> {
            Object result = chain.proceed();
            HostPreferences p = pageOwner(chain.getThisObject());
            if (p != null) p.navigation().prepare(chain.getThisObject());
            return result;
        });
        hook(module, loader, HostTargets.UI_SUBPAGE_VIEW_CREATED, chain -> {
            Object result = chain.proceed();
            HostPreferences p = pageOwner(chain.getThisObject());
            if (p != null) p.navigation().viewCreated(chain.getThisObject(), (View) chain.getArg(0));
            return result;
        });
        hook(module, loader, HostTargets.UI_SUBPAGE_DESTROY_VIEW, chain -> {
            HostPreferences p = pageOwner(chain.getThisObject());
            Object result = chain.proceed();
            if (p != null) p.navigation().viewDestroyed();
            return result;
        });
        hook(module, loader, HostTargets.UI_FRAGMENT_CREATE_PREFS, chain -> {
            Object result = chain.proceed();
            try {
                HostPreferences old = CONTROLLERS.remove(chain.getThisObject());
                if (old != null) old.stop();
                HostPreferences p = new HostPreferences(chain.getThisObject(), loader, module);
                p.inject();
                CONTROLLERS.put(chain.getThisObject(), p);
            } catch (Throwable e) { module.error("COUI preference injection failed", e); }
            return result;
        });
        hook(module, loader, HostTargets.UI_FRAGMENT_VIEW_CREATED, chain -> {
            Object result = chain.proceed();
            HostPreferences p = CONTROLLERS.get(chain.getThisObject());
            if (p != null) p.start();
            return result;
        });
        hook(module, loader, HostTargets.UI_FRAGMENT_DESTROY_VIEW, chain -> {
            HostPreferences p = CONTROLLERS.get(chain.getThisObject());
            if (p != null) p.stop();
            return chain.proceed();
        });
        hook(module, loader, HostTargets.UI_FRAGMENT_DESTROY, chain -> {
            HostPreferences p = CONTROLLERS.remove(chain.getThisObject());
            if (p != null) p.stop();
            return chain.proceed();
        });

        String toolbarLambda = HostTargets.memberName(HostTargets.UI_ACTIVITY_TOOLBAR_LAMBDA, loader);
        String initToolbar = HostTargets.memberName(HostTargets.UI_ACTIVITY_INIT_TOOLBAR, loader);
        for (Method method : activity.getDeclaredMethods()) {
            if (method.getName().equals(toolbarLambda) || method.getName().equals(initToolbar)) {
                module.deoptimize(method);
            }
        }
        module.info("Host COUI preference hook installation attempted");
    }

    private static void hook(ColorPodsModule module, ClassLoader loader, String key,
                             XposedInterface.Hooker hooker) {
        try {
            Method method = HostTargets.methodOrNull(key, loader);
            if (method == null) {
                module.error("UI hook target unresolved: " + key, new NoSuchMethodException(key));
                return;
            }
            module.hook(method).intercept(hooker);
        } catch (Throwable e) { module.error("UI hook " + key + " unavailable", e); }
    }

    private static Object preferencesFragment(Object activity) throws Exception {
        return Reflect.call(activity, preferencesFragmentName, new Class[0]);
    }

    private static HostPreferences pageOwner(Object fragment) throws Exception {
        if (HostPageNavigation.pageKey(fragment) == null) return null;
        Activity activity = (Activity) Reflect.call(fragment, "getActivity", new Class[0]);
        if (activity == null || !activity.getClass().getName().equals(activityName)) return null;
        return CONTROLLERS.get(preferencesFragment(activity));
    }

    private static boolean back(Activity activity) throws Exception {
        if (!activity.getClass().getName().equals(activityName)) return false;
        HostPreferences p = CONTROLLERS.get(preferencesFragment(activity));
        return p != null && p.back();
    }
}
