// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import java.io.IOException;

final class DefaultAssistant {
    private DefaultAssistant(){}
    static void launch(Context context)throws IOException{
        String assistant=Settings.Secure.getString(context.getContentResolver(),"assistant");
        String voice=Settings.Secure.getString(context.getContentResolver(),"voice_interaction_service");
        String[] packages=packages(assistant,voice);
        if(packages.length==0)throw new IOException("请先配置系统默认语音助手");
        for(String pkg:packages)for(String action:new String[]{Intent.ACTION_VOICE_COMMAND,Intent.ACTION_ASSIST})try{
            context.startActivity(new Intent(action).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));return;
        }catch(ActivityNotFoundException|SecurityException ignored){}
        throw new IOException("系统默认语音助手没有可用的启动入口");
    }
    static String[] packages(String assistant,String voice){
        java.util.Set<String> result=new java.util.LinkedHashSet<>();
        for(String value:new String[]{assistant,voice}){
            if(value==null||value.isBlank())continue;
            int slash=value.indexOf('/');
            if(slash<=0||slash==value.length()-1||value.indexOf('/',slash+1)>=0)continue;
            String pkg=value.substring(0,slash),cls=value.substring(slash+1);
            if(pkg.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*")&&cls.matches("\\.?[A-Za-z_$][A-Za-z0-9_.$]*"))result.add(pkg);
        }
        return result.toArray(new String[0]);
    }
}
