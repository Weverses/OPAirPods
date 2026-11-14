// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.os.Bundle;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

final class NativeUi {
    private static final Map<Object, HostPreferences> CONTROLLERS = new HashMap<>();
    private NativeUi() {}
    static void install(ColorPodsModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> fragment = Class.forName("com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingPreferencesFragment", false, loader);
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
        module.info("Host COUI preference lifecycle hooks installed");
    }
}
