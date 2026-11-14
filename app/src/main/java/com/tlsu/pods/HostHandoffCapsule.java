// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Adapt only module-owned cards; all other native battery cards stay native. */
final class HostHandoffCapsule {
    static final String ACTION="com.tlsu.pods.HANDOFF_NOTICE";
    static final String RECEIVER="com.oplus.mydevices.receiver.AccountReceiver";
    private static final String CORE_RECEIVER="com.heytap.mydevices.plugin.airpods.noisereduction.NoiseReductionCommand";
    private static final String PREFIX="colorpods.handoff/";
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->new Thread(r,"ColorPodsCapsule"));
    private static final ExecutorService ROUTES=Executors.newSingleThreadExecutor(r->new Thread(r,"ColorPodsCapsuleRoute"));
    private static final Handler MAIN=new Handler(Looper.getMainLooper());
    private static ClassLoader loader;
    private static ColorPodsModule module;

    static void install(ColorPodsModule owner,ClassLoader host)throws Exception{
        module=owner;loader=host;
        for(String component:new String[]{RECEIVER,CORE_RECEIVER}){
        Class<?> receiver=Class.forName(component,false,host);
        owner.hook(Reflect.method(receiver,"onReceive",Context.class,Intent.class)).intercept(chain->{
            Intent intent=(Intent)chain.getArg(1);
            if(intent==null||!ACTION.equals(intent.getAction()))return chain.proceed();
            Context app=((Context)chain.getArg(0)).getApplicationContext();
            if(component.equals(RECEIVER)){
                // Bluetooth has the existing AccountReceiver permission. The
                // same host UID forwards to its protected core-process receiver.
                app.sendBroadcast(new Intent(intent).setClassName(app.getPackageName(),CORE_RECEIVER));return null;
            }
            BroadcastReceiver.PendingResult pending=((BroadcastReceiver)chain.getThisObject()).goAsync();
            String address=intent.getStringExtra("address");long id=intent.getLongExtra("noticeId",0);
            IO.execute(()->{try{
                if(app.getPackageManager().getPackageInfo("com.heytap.mydevices",0).getLongVersionCode()!=1725000)return;
                if(!BluetoothAdapter.checkBluetoothAddress(address))return;
                Bundle request=BridgeClient.forDevice(address);request.putLong("noticeId",id);
                Bundle event=new BridgeClient(app).request(BridgeClient.HANDOFF_NOTICE,request);
                if(event.getBoolean("available"))MAIN.post(()->display(address,event));
            }catch(Exception e){module.error("Handoff capsule delivery failed",e);}finally{pending.finish();}});
            return null;
        });
        }
        Class<?> info=Class.forName("com.oplus.mydevices.domain.entities.linkage.CapsuleInfo",false,host);
        Class<?> producer=Class.forName("aa.Y80",false,host);
        for(String name:new String[]{"r","s"})owner.hook(Reflect.method(producer,name,info)).intercept(chain->{
            Object result=chain.proceed();Object card=chain.getArg(0);
            if(!owned((String)Reflect.call(card,"getDeviceId",new Class[0])))return result;
            JSONObject data=(JSONObject)result;
            if(name.equals("s")){JSONObject initial=new JSONObject(data.getString("initData"));decorate(initial,card);data.put("initData",initial.toString());}
            else decorate(data,card);
            return result;
        });
        Class<?> click=Class.forName("com.heytap.mydevices.core.seedling.SeedlingCardClickEvent",false,host);
        owner.hook(Reflect.method(click,"clickActiveButton",String.class)).intercept(chain->{
            String id=(String)chain.getArg(0);if(!owned(id))return chain.proceed();
            // A card click may arrive in another host process. Recover context
            // from the host Application instead of relying on receiver state.
            Context app=application();
            ROUTES.execute(()->{try{
                int slash=id.lastIndexOf('/');String address=id.substring(PREFIX.length(),slash);long notice=Long.parseLong(id.substring(slash+1));
                Bundle request=BridgeClient.forDevice(address);request.putLong("noticeId",notice);
                Bundle result=new BridgeClient(app).request(BridgeClient.ROUTE,request);
                if(result.getBoolean("routeConfirmed"))MAIN.post(()->hide(id));
            }catch(Exception e){module.error("Handoff capsule return failed",e);MAIN.post(()->Toast.makeText(app,"暂时无法切回，请重试",Toast.LENGTH_SHORT).show());}});
            return null;
        });
        owner.info("Native handoff capsule hooks installed");
    }
    private static boolean owned(String id){return id!=null&&id.startsWith(PREFIX);}
    private static void decorate(JSONObject data,Object card)throws Exception{
        String title=(String)Reflect.call(card,"getTitle",new Class[0]);
        String content=(String)Reflect.call(card,"getSubTitle",new Class[0]);
        data.put("primaryInfo",title);data.put("secondaryInfo",content);data.put("capsuleInfoPrimaryText",content);
        data.put("isShowBattery",false);data.put("isCardShowBattery",false);
        data.put("buttonText","");data.put("isShowButton",false);data.put("isReConnectable",true);
        data.put("progressColor","");data.put("progressBgColor","#33FFFFFF");data.put("themeColor","#FFFFFF");data.put("isLowPower",false);
    }
    private static Context application()throws Exception{
        Class<?> base=Class.forName("com.heytap.mydevices.common.BaseApplication",true,loader);
        return (Context)Reflect.call(base.getField("a").get(null),"d",new Class[0]);
    }
    private static Object helper()throws Exception{
        Class<?> manager=Class.forName("com.heytap.mydevices.plugin.airpods.core.AirpodsManager",true,loader);
        var instance=manager.getDeclaredField("instance");instance.setAccessible(true);Object airpods=instance.get(null);
        if(airpods!=null)return Reflect.field(airpods,"seedlingCardHelper");
        Object base=Class.forName("com.heytap.mydevices.common.BaseApplication",true,loader).getField("a").get(null);
        Object koin=Reflect.call(base,"getKoin",new Class[0]);
        Object registry=Reflect.call(koin,"l",new Class[0]),scope=Reflect.call(registry,"d",new Class[0]);
        Class<?> helper=Class.forName("com.heytap.mydevices.core.seedling.SeedlingCardHelper",true,loader);
        Object kotlinClass=Class.forName("aa.J50",true,loader).getMethod("b",Class.class).invoke(null,helper);
        for(var method:scope.getClass().getDeclaredMethods())if(method.getName().equals("e")&&method.getParameterCount()==3)
            return method.invoke(scope,kotlinClass,null,null);
        throw new NoSuchMethodException("Host SeedlingCardHelper dependency");
    }
    private static void display(String address,Bundle event){
        try{
            Object helper=helper();
            if(!event.getBoolean("remote")){hideCurrent(helper,address);return;}
            String id=PREFIX+address+"/"+event.getLong("noticeId");
            Object utils=Class.forName("aa.b2",true,loader).getField("a").get(null);
            Object deviceInfo=Reflect.call(utils,"g",new Class[]{String.class},address);
            if(deviceInfo==null)throw new IllegalStateException("Host AirPods model not available");
            String model=(String)Reflect.call(deviceInfo,"getDeviceModel",new Class[0]);
            String name=null;
            try{
                BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
                if(adapter!=null)name=adapter.getRemoteDevice(address).getAlias();
            }catch(SecurityException ignored){}
            if(name==null||name.isBlank())name=(String)Reflect.call(deviceInfo,"getDeviceName",new Class[0]);
            if(name==null||name.isBlank())name="AirPods";
            // The Bluetooth process may still be running the previous module
            // version while the host reloads its presentation adapter.
            String content=event.getString("title","").replaceFirst("^已经移到\\s*","已移至 ");
            boolean max=(Boolean)Reflect.call(utils,"s",new Class[]{String.class},model),pro=(Boolean)Reflect.call(utils,"o",new Class[]{String.class},model);
            Object icons=Class.forName("com.heytap.mydevices.plugin.airpods.core.AirpodsIconHelper",true,loader).getConstructor(Context.class).newInstance(application());
            String icon=(String)Reflect.call(icons,"getCapsuleIconUri",new Class[]{int.class,boolean.class},max?2000:1000,pro);
            Class<?> type=Class.forName("com.oplus.mydevices.domain.entities.linkage.CapsuleType",true,loader);
            Class<?> info=Class.forName("com.oplus.mydevices.domain.entities.linkage.CapsuleInfo",true,loader);
            Object card=info.getConstructor(String.class,String.class,String.class,String.class,String.class,String.class,type,String.class,List.class,Integer.class)
                .newInstance(id,model,address,icon,name,content,type.getField("SET_ACTIVE").get(null),"",List.of(),Integer.valueOf(1));
            Class<?> callback=Class.forName("aa.Py",false,loader);
            Reflect.call(helper,"l",new Class[]{info,callback},card,null);
            module.info("Native handoff capsule requested: "+content);
            MAIN.postDelayed(()->hide(id),8000);
        }catch(Exception e){module.error("Native handoff capsule failed",e);}
    }
    private static void hideCurrent(Object helper,String address)throws Exception{
        Object card=Reflect.call(helper,"d",new Class[0]);if(card==null)return;
        String id=(String)Reflect.call(card,"getDeviceId",new Class[0]);
        if(owned(id)&&address.equals(Reflect.call(card,"getMac",new Class[0])))Reflect.call(helper,"k",new Class[0]);
    }
    private static void hide(String id){
        try{Object helper=helper(),card=Reflect.call(helper,"d",new Class[0]);
            if(card!=null&&id.equals(Reflect.call(card,"getDeviceId",new Class[0])))Reflect.call(helper,"k",new Class[0]);
        }catch(Exception e){module.error("Handoff capsule cleanup failed",e);}
    }
}
