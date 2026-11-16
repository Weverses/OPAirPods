// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.telecom.Call;
import android.telecom.InCallService;
import dalvik.system.PathClassLoader;

/** Run through app_process; inspects signatures without instantiating the service. */
public final class HeadMotionHostProbe {
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected current Bluetooth APK path");
        ClassLoader loader=new PathClassLoader(args[0],ClassLoader.getSystemClassLoader());
        Class<?> service=Class.forName("com.android.bluetooth.telephony.BluetoothInCallService",false,loader);
        if(!InCallService.class.isAssignableFrom(service))throw new AssertionError("Unexpected service superclass");
        for(String name:new String[]{"onCreate","onDestroy"})
            System.out.println("PASS: "+service.getDeclaredMethod(name));
        for(String name:new String[]{"onCallAdded","onCallRemoved"})
            System.out.println("PASS: "+service.getDeclaredMethod(name,Call.class));
        System.out.println("PASS: "+Call.class.getMethod("answer",int.class));
        System.out.println("PASS: "+Call.class.getMethod("reject",boolean.class,String.class));
        System.out.println("Signature inspection only; no call actions or service creation");
    }
}
