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
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

import io.github.libxposed.api.XposedInterface;

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

    static void install(ColorPodsModule owner, ClassLoader host) {
        module = owner; loader = host;
        hookReceiver(owner, host, HostTargets.UI_RECEIVER_ON_RECEIVE, true);
        hookReceiver(owner, host, HostTargets.UI_CORE_RECEIVER_ON_RECEIVE, false);
        hookProducer(owner, host, HostTargets.UI_CAPSULE_PRODUCER_PLAIN, false);
        hookProducer(owner, host, HostTargets.UI_CAPSULE_PRODUCER_INIT, true);
        hook(owner, host, HostTargets.UI_CLICK_ACTIVE_BUTTON, chain -> {
            String id = (String) chain.getArg(0);
            if (!owned(id)) return chain.proceed();
            // A card click may arrive in another host process. Recover context
            // from the host Application instead of relying on receiver state.
            Context app = application();
            ROUTES.execute(() -> {
                try {
                    int slash = id.lastIndexOf('/');
                    String address = id.substring(PREFIX.length(), slash);
                    long notice = Long.parseLong(id.substring(slash + 1));
                    Bundle request = BridgeClient.forDevice(address); request.putLong("noticeId", notice);
                    Bundle result = new BridgeClient(app).request(BridgeClient.ROUTE, request);
                    if (result.getBoolean("routeConfirmed")) MAIN.post(() -> hide(id));
                } catch (Exception e) {
                    module.error("Handoff capsule return failed", e);
                    MAIN.post(() -> Toast.makeText(app, "暂时无法切回，请重试", Toast.LENGTH_SHORT).show());
                }
            });
            return null;
        });
        IO.execute(() -> {
            for (String key : new String[]{HostTargets.UI_DEVICE_INFO, HostTargets.UI_MODEL_MAX,
                    HostTargets.UI_MODEL_PRO, HostTargets.UI_CAPSULE_START, HostTargets.UI_CAPSULE_END,
                    HostTargets.UI_CONNECTION_UPDATE}) {
                if (HostTargets.methodOrNull(key, host) == null)
                    owner.info("Handoff capability unavailable: " + key);
                else owner.info("Handoff capability resolved: " + key);
            }
        });
        owner.info("Native handoff capsule hook installation attempted");
    }

    private static void hook(ColorPodsModule owner, ClassLoader host, String key,
                             XposedInterface.Hooker hooker) {
        try {
            Method method = HostTargets.methodOrNull(key, host);
            if (method == null) {
                owner.error("Capsule hook target unresolved: " + key, new NoSuchMethodException(key));
                return;
            }
            owner.hook(method).intercept(hooker);
        } catch (Throwable e) { owner.error("Capsule hook " + key + " unavailable", e); }
    }

    private static void hookReceiver(ColorPodsModule owner, ClassLoader host, String key,
                                     boolean forward) {
        hook(owner, host, key, chain -> {
            Intent intent = (Intent) chain.getArg(1);
            if (intent == null || !ACTION.equals(intent.getAction())) return chain.proceed();
            Context app = ((Context) chain.getArg(0)).getApplicationContext();
            if (forward) {
                // Bluetooth has the existing AccountReceiver permission. The
                // same host UID forwards to its protected core-process receiver.
                app.sendBroadcast(new Intent(intent).setClassName(app.getPackageName(), CORE_RECEIVER));
                return null;
            }
            BroadcastReceiver.PendingResult pending = ((BroadcastReceiver) chain.getThisObject()).goAsync();
            String address = intent.getStringExtra("address");
            long id = intent.getLongExtra("noticeId", 0);
            IO.execute(() -> {
                try {
                    if (!BluetoothAdapter.checkBluetoothAddress(address)) return;
                    Bundle request = BridgeClient.forDevice(address); request.putLong("noticeId", id);
                    Bundle event = new BridgeClient(app).request(BridgeClient.HANDOFF_NOTICE, request);
                    if (event.getBoolean("available")) MAIN.post(() -> display(address, event));
                } catch (Exception e) { module.error("Handoff capsule delivery failed", e); }
                finally { pending.finish(); }
            });
            return null;
        });
    }

    private static void hookProducer(ColorPodsModule owner, ClassLoader host, String key,
                                     boolean initData) {
        hook(owner, host, key, chain -> {
            Object result = chain.proceed();
            Object card = chain.getArg(0);
            if (!owned((String) Reflect.call(card, "getDeviceId", new Class[0]))) return result;
            JSONObject data = (JSONObject) result;
            if (initData) {
                JSONObject initial = new JSONObject(data.getString("initData"));
                decorate(initial, card);
                data.put("initData", initial.toString());
            } else {
                decorate(data, card);
            }
            return result;
        });
    }

    private static boolean owned(String id) { return id != null && id.startsWith(PREFIX); }
    private static void decorate(JSONObject data,Object card)throws Exception{
        String title=(String)Reflect.call(card,"getTitle",new Class[0]);
        String content=(String)Reflect.call(card,"getSubTitle",new Class[0]);
        data.put("primaryInfo",title);data.put("secondaryInfo",content);data.put("capsuleInfoPrimaryText",content);
        data.put("isShowBattery",false);data.put("isCardShowBattery",false);
        data.put("buttonText","");data.put("isShowButton",false);data.put("isReConnectable",true);
        data.put("progressColor","");data.put("progressBgColor","#33FFFFFF");data.put("themeColor","#FFFFFF");data.put("isLowPower",false);
    }
    private static Object base()throws Exception{
        var field=HostTargets.fieldOrNull(CapsuleTargets.BASE,loader);
        if(field==null)throw new NoSuchFieldException("Host application singleton unresolved");
        return field.get(null);
    }
    private static Context application()throws Exception{
        return (Context)target(CapsuleTargets.APPLICATION).invoke(base());
    }
    private static Object helper()throws Exception{
        Object koin=Reflect.call(base(),"getKoin",new Class[0]);
        Object registry=target(CapsuleTargets.REGISTRY).invoke(koin);
        Object scope=target(CapsuleTargets.ROOT).invoke(registry);
        Class<?> helper=Class.forName("com.heytap.mydevices.core.seedling.SeedlingCardHelper",false,loader);
        Object kotlinClass=target(CapsuleTargets.KCLASS).invoke(null,helper);
        return target(CapsuleTargets.GET).invoke(scope,kotlinClass,null,null);
    }
    private static void display(String address,Bundle event){
        try{
            Object helper=helper();
            if(!event.getBoolean("remote")){hideCurrent(helper,address);return;}
            Object current=currentCard(helper);
            if(current!=null&&!owned((String)Reflect.call(current,"getDeviceId",new Class[0]))){
                module.info("Handoff capsule skipped while a native card is active");return;
            }
            String id=PREFIX+address+"/"+event.getLong("noticeId");
            Method lookup=target(HostTargets.UI_DEVICE_INFO);
            var utilsField=HostTargets.fieldOrNull(CapsuleTargets.UTILS,loader);
            if(utilsField==null)throw new NoSuchFieldException("AirPods utility singleton unresolved");
            Object utils=utilsField.get(null);
            if(utils==null)throw new IllegalStateException("AirPods utility instance unavailable");
            Object deviceInfo=lookup.invoke(utils,address);
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
            Method maxMethod=target(HostTargets.UI_MODEL_MAX),proMethod=target(HostTargets.UI_MODEL_PRO);
            if(!maxMethod.getDeclaringClass().isInstance(utils)||!proMethod.getDeclaringClass().isInstance(utils))
                throw new IllegalStateException("AirPods model targets belong to different utilities");
            boolean max=(Boolean)maxMethod.invoke(utils,model),pro=(Boolean)proMethod.invoke(utils,model);
            Object icons=Class.forName("com.heytap.mydevices.plugin.airpods.core.AirpodsIconHelper",true,loader).getConstructor(Context.class).newInstance(application());
            String icon=(String)Reflect.call(icons,"getCapsuleIconUri",new Class[]{int.class,boolean.class},max?2000:1000,pro);
            Class<?> type=Class.forName("com.oplus.mydevices.domain.entities.linkage.CapsuleType",true,loader);
            Class<?> info=Class.forName("com.oplus.mydevices.domain.entities.linkage.CapsuleInfo",true,loader);
            Object card=info.getConstructor(String.class,String.class,String.class,String.class,String.class,String.class,type,String.class,List.class,Integer.class)
                .newInstance(id,model,address,icon,name,content,type.getField("SET_ACTIVE").get(null),"",List.of(),Integer.valueOf(1));
            target(HostTargets.UI_CAPSULE_START).invoke(helper,card,null);
            module.info("Native handoff capsule requested: "+content);
            MAIN.postDelayed(()->hide(id),8000);
        }catch(Exception e){module.error("Native handoff capsule failed",e);}
    }
    private static void hideCurrent(Object helper,String address)throws Exception{
        Object card=currentCard(helper);if(card==null)return;
        String id=(String)Reflect.call(card,"getDeviceId",new Class[0]);
        if(owned(id)&&address.equals(Reflect.call(card,"getMac",new Class[0])))target(HostTargets.UI_CAPSULE_END).invoke(helper);
    }
    private static void hide(String id){
        try{Object helper=helper(),card=currentCard(helper);
            if(card!=null&&id.equals(Reflect.call(card,"getDeviceId",new Class[0])))target(HostTargets.UI_CAPSULE_END).invoke(helper);
        }catch(Exception e){module.error("Handoff capsule cleanup failed",e);}
    }
    private static Method target(String key)throws Exception{
        Method method=HostTargets.methodOrNull(key,loader);
        if(method==null)throw new NoSuchMethodException("Unresolved capsule target: "+key);
        return method;
    }
    private static Object currentCard(Object helper)throws Exception{
        return target(CapsuleTargets.CURRENT).invoke(helper);
    }
}
