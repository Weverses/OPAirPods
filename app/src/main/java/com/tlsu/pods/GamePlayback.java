// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.app.ActivityManager;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.os.PowerManager;
import java.util.HashSet;

/** Sample the focused app and its live audio players inside the Bluetooth UID. */
final class GamePlayback {
    private GamePlayback() {}
    @SuppressWarnings("deprecation")
    private static ApplicationInfo focused(Context context)throws Exception{
        PowerManager power=context.getSystemService(PowerManager.class);
        KeyguardManager keyguard=context.getSystemService(KeyguardManager.class);
        if(power==null||!power.isInteractive()||keyguard==null||keyguard.isDeviceLocked())return null;
        // Bluetooth has REAL_GET_TASKS on the supported ColorOS host. Do not
        // mistake a foreground media service for the focused game activity.
        if(context.checkSelfPermission("android.permission.REAL_GET_TASKS")!=android.content.pm.PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("Foreground task access unavailable");
        ActivityManager activities=context.getSystemService(ActivityManager.class);
        var tasks=activities==null?java.util.List.<ActivityManager.RunningTaskInfo>of():activities.getRunningTasks(1);
        if(tasks.isEmpty()||tasks.get(0).topActivity==null)return null;
        return context.getPackageManager().getApplicationInfo(tasks.get(0).topActivity.getPackageName(),0);
    }
    static GameReturnState.Target focusedIdentity(Context context)throws Exception{
        ApplicationInfo app=focused(context);return app==null?null:new GameReturnState.Target(app.uid,app.packageName,java.util.Set.of());
    }
    @SuppressWarnings("deprecation")
    static boolean gameSource(Context context,int uid,boolean gameUsage)throws Exception{
        if(gameUsage)return true;
        String[] packages=context.getPackageManager().getPackagesForUid(uid);if(packages==null)return false;
        for(String pkg:packages){ApplicationInfo app=context.getPackageManager().getApplicationInfo(pkg,0);
            if(app.category==ApplicationInfo.CATEGORY_GAME||(app.flags&ApplicationInfo.FLAG_IS_GAME)!=0)return true;}
        return false;
    }
    static GameReturnState.Target fromStart(Context context,MediaStartMonitor.Start start)throws Exception{
        if(start.players().isEmpty()){
            GameReturnState.Target current=capture(context);return current!=null&&current.uid()==start.uid()?current:null;
        }
        ApplicationInfo app=focused(context);
        return app!=null&&app.uid==start.uid()?new GameReturnState.Target(app.uid,app.packageName,start.players()):null;
    }
    @SuppressWarnings("deprecation")
    static GameReturnState.Target capture(Context context)throws Exception{
        ApplicationInfo app=focused(context);if(app==null)return null;
        boolean declared=app.category==ApplicationInfo.CATEGORY_GAME||(app.flags&ApplicationInfo.FLAG_IS_GAME)!=0;
        AudioManager audio=context.getSystemService(AudioManager.class);if(audio==null)return null;
        HashSet<Integer> players=new HashSet<>();boolean gameUsage=false;
        for(var config:audio.getActivePlaybackConfigurations()){
            int usage=config.getAudioAttributes().getUsage();
            if(usage!=AudioAttributes.USAGE_GAME&&usage!=AudioAttributes.USAGE_MEDIA)continue;
            if(!Boolean.TRUE.equals(Reflect.call(config,"isActive",new Class[0])))continue;
            int uid=(Integer)Reflect.call(config,"getClientUid",new Class[0]);if(uid!=app.uid)continue;
            int player=(Integer)Reflect.call(config,"getPlayerInterfaceId",new Class[0]);
            if(player<0)continue;
            players.add(player);gameUsage|=usage==AudioAttributes.USAGE_GAME;
        }
        return players.isEmpty()||!declared&&!gameUsage?null:new GameReturnState.Target(app.uid,app.packageName,players);
    }
    static boolean audible(Context context,int uid)throws Exception{
        AudioManager audio=context.getSystemService(AudioManager.class);if(audio==null||uid<0)return false;
        for(var config:audio.getActivePlaybackConfigurations()){
            int usage=config.getAudioAttributes().getUsage();
            if((usage==AudioAttributes.USAGE_GAME||usage==AudioAttributes.USAGE_MEDIA)
                    &&Boolean.TRUE.equals(Reflect.call(config,"isActive",new Class[0]))
                    &&uid==(Integer)Reflect.call(config,"getClientUid",new Class[0]))return true;
        }
        return false;
    }
}
