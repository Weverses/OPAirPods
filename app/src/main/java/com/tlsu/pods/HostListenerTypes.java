// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

/** Resolve preference listeners by their callback shape, rejecting ambiguous candidates. */
final class HostListenerTypes {
    private HostListenerTypes() {}
    static Class<?> type(Class<?> preference, boolean change) throws ClassNotFoundException {
        Class<?> found=null;
        for(Class<?> type:preference.getDeclaredClasses()){
            if(!type.isInterface())continue;
            Method[] methods=type.getDeclaredMethods();if(methods.length!=1)continue;
            Method method=methods[0];Class<?>[] args=method.getParameterTypes();
            if(method.getReturnType()!=boolean.class||args.length!=(change?2:1)||args[0]!=preference
                    ||(change&&args[1]!=Object.class))continue;
            if(found!=null)throw new IllegalStateException("Ambiguous preference listener type");found=type;
        }
        if(found==null)throw new ClassNotFoundException("Preference listener unavailable");return found;
    }
    static Method setter(Class<?> preference,Class<?> listener) throws NoSuchMethodException {
        Method found=null;
        for(Method method:preference.getDeclaredMethods()){
            if(Modifier.isStatic(method.getModifiers())||method.getReturnType()!=void.class
                    ||!Arrays.equals(method.getParameterTypes(),new Class[]{listener}))continue;
            if(found!=null)throw new IllegalStateException("Ambiguous preference listener setter");found=method;
        }
        if(found==null)throw new NoSuchMethodException("Preference listener setter");
        found.setAccessible(true);return found;
    }
}
