// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.os.Bundle;
import android.app.Activity;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

final class NativeUi {
    private static final Map<Object, HostPreferences> CONTROLLERS = new HashMap<>();
    private NativeUi() {}
    static void install(ColorPodsModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> fragment = Class.forName("com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingPreferencesFragment", false, loader);
        Class<?> activity = Class.forName("com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingActivity", false, loader);
        // Native observers keep their original preference objects even while
        // another host fragment displays one of the feature pages.
        module.hook(Reflect.method(fragment,"findPreference",CharSequence.class)).intercept(chain -> {
            HostPreferences p=CONTROLLERS.get(chain.getThisObject());
            return p==null?chain.proceed():p.findPreference((CharSequence)chain.getArg(0));
        });
        module.hook(Reflect.method(activity,"initToolbar$lambda$6$lambda$5",activity,View.class)).intercept(chain -> {
            return back((Activity)chain.getArg(0))?null:chain.proceed();
        });
        // Gesture back is handled by the host FragmentManager's native stack.
        module.hook(Reflect.method(activity,"onBackPressed")).intercept(chain -> {
            return back((Activity)chain.getThisObject())?null:chain.proceed();
        });
        Class<?> page=Class.forName("aa.rc",false,loader);
        module.hook(Reflect.method(page,"onCreatePreferences",Bundle.class,String.class)).intercept(chain -> {
            Object result=chain.proceed();HostPreferences p=pageOwner(chain.getThisObject());
            if(p!=null)p.navigation().prepare(chain.getThisObject());return result;
        });
        module.hook(Reflect.method(page,"onViewCreated",View.class,Bundle.class)).intercept(chain -> {
            Object result=chain.proceed();HostPreferences p=pageOwner(chain.getThisObject());
            if(p!=null)p.navigation().viewCreated(chain.getThisObject(),(View)chain.getArg(0));return result;
        });
        module.hook(Reflect.method(page,"onDestroyView")).intercept(chain -> {
            HostPreferences p=pageOwner(chain.getThisObject());Object result=chain.proceed();
            if(p!=null)p.navigation().viewDestroyed();return result;
        });
        module.hook(Reflect.method(fragment,"onCreatePreferences",Bundle.class,String.class)).intercept(chain -> {
            Object result=chain.proceed();
            try { HostPreferences old=CONTROLLERS.remove(chain.getThisObject());if(old!=null)old.stop();HostPreferences p=new HostPreferences(chain.getThisObject(),loader,module);p.inject();CONTROLLERS.put(chain.getThisObject(),p); }
            catch(Throwable e){module.error("COUI preference injection failed",e);}
            return result;
        });
        module.hook(Reflect.method(fragment,"onViewCreated",View.class,Bundle.class)).intercept(chain -> {
            Object result=chain.proceed();HostPreferences p=CONTROLLERS.get(chain.getThisObject());if(p!=null)p.start();return result;
        });
        module.hook(Reflect.method(fragment,"onDestroyView")).intercept(chain -> {
            HostPreferences p=CONTROLLERS.get(chain.getThisObject());if(p!=null)p.stop();return chain.proceed();
        });
        module.hook(Reflect.method(fragment,"onDestroy")).intercept(chain -> {
            HostPreferences p=CONTROLLERS.remove(chain.getThisObject());if(p!=null)p.stop();return chain.proceed();
        });
        for(var method:activity.getDeclaredMethods())if(method.getName().equals("initToolbar$lambda$6$lambda$5")||method.getName().equals("initToolbar"))module.deoptimize(method);
        module.info("Host COUI preference lifecycle hooks installed");
    }
    private static HostPreferences pageOwner(Object fragment)throws Exception{
        if(HostPageNavigation.pageKey(fragment)==null)return null;
        Activity activity=(Activity)Reflect.call(fragment,"getActivity",new Class[0]);
        if(activity==null||!activity.getClass().getName().equals("com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingActivity"))return null;
        return CONTROLLERS.get(Reflect.call(activity,"preferencesFragment",new Class[0]));
    }
    private static boolean back(Activity activity) throws Exception {
        if(!activity.getClass().getName().equals("com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingActivity"))return false;
        Object fragment=Reflect.call(activity,"preferencesFragment",new Class[0]);
        HostPreferences p=CONTROLLERS.get(fragment);return p!=null&&p.back();
    }
}
