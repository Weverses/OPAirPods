// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.app.Activity;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.View;
import android.widget.Toast;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

// All settings and selection dialogs belong to the host's preference framework.
// No module-owned views, AlertDialogs, styles, layouts, AndroidX or COUI copies.
final class HostPreferences {
    private static final ExecutorService IO=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ColorPodsCOUI");t.setDaemon(true);return t;});
    private final Object fragment;
    private final ClassLoader loader;
    private final ColorPodsModule module;
    private final Activity activity;
    private final BridgeClient bridge;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Runnable confirmationTimeout=this::onConfirmationDeadline;
    private final Map<Object,SystemSettingsSlider> sliders=new HashMap<>();
    private final Map<Integer,Object> controls=new LinkedHashMap<>();
    private final Map<Integer,Object> listeningModeRows=new LinkedHashMap<>();
    private final Map<Integer,Object> stemRows=new LinkedHashMap<>();
    private final Map<String,Object> eqRows=new LinkedHashMap<>();
    private Object eqToggle,eqReset;
    private CustomEq eqSelection;
    private StemConfig stemSelection;
    private Object headToggle,headMapping,headStatus,headStart,headStop;
    private String headOwner;
    private boolean headForeground=true;
    private final Map<Integer,byte[]> sendingControls=new HashMap<>();
    private final Map<Object, Boolean> switchRows=new HashMap<>();
    private final Map<String,Object> attRows=new LinkedHashMap<>();
    private final Map<Integer,byte[]> attValues=new HashMap<>();
    private final Map<String,Object> pages=new LinkedHashMap<>();
    private final Map<String,String> pageTitles=new HashMap<>();
    private final Map<String,Object> pageEntries=new HashMap<>();
    private final HostPageNavigation navigation;
    private final HostConnection connection;
    private final AdaptiveStrengthSlider adaptiveStrength;
    private final Runnable refreshTask=this::refresh;
    private Object vendorRow,routeRow,multiRow,autoMediaRow,autoGameReturnRow,routeStatus,reverseRow,root;
    private Bundle snapshot=Bundle.EMPTY;
    private boolean active,busy,attLoading,attWasReady;
    private long nextAttLoad;
    private int attGeneration;
    private int listeningMask=-1;
    private boolean listeningOffAllowed,listeningAdaptiveSupported;
    private String lastError;
    private String connectionError;

    HostPreferences(Object fragment,ClassLoader loader,ColorPodsModule module) throws Exception {
        this.fragment=fragment;this.loader=loader;this.module=module;
        activity=(Activity)Reflect.call(fragment,"getActivity",new Class[0]);
        if(activity==null)throw new IllegalStateException("Host activity unavailable");
        bridge=new BridgeClient(activity);
        navigation=new HostPageNavigation(this,activity,loader,module);
        connection=new HostConnection(activity,module,this::connectionChanged);
        adaptiveStrength=new AdaptiveStrengthSlider(activity,loader,value->sendControl(0x2e,AdaptiveStrength.command(value)));
    }
    private Object create(String name) throws Exception {
        Class<?> cls=Class.forName("com.coui.appcompat.preference."+name,true,loader);
        try{return cls.getConstructor(Context.class).newInstance(activity);}
        catch(NoSuchMethodException e){return cls.getConstructor(Context.class,AttributeSet.class).newInstance(activity,null);}
    }
    private void call(Object row,String name,Class<?> type,Object arg) throws Exception {Reflect.call(row,name,new Class[]{type},arg);}
    private Object targetCall(Object instance,String key,Object... args)throws Exception{
        var method=HostTargets.methodOrNull(key,loader);
        if(method==null)throw new NoSuchMethodException("Unresolved host preference target: "+key);
        return method.invoke(instance,args);
    }
    private java.lang.reflect.Field targetField(String key)throws NoSuchFieldException{
        var field=HostTargets.fieldOrNull(key,loader);
        if(field==null)throw new NoSuchFieldException("Unresolved preference field: "+key);
        return field;
    }
    private Object add(Object parent,String kind,String key,String title) throws Exception {
        Object row=create(kind);targetCall(row,HostTargets.UI_PREF_KEY,key);targetCall(row,HostTargets.UI_PREF_TITLE,title);targetCall(row,PreferenceTargets.PERSISTENT,false);
        if(!Boolean.TRUE.equals(targetCall(parent,HostTargets.UI_PREF_ADD,row)))throw new IllegalStateException("Host rejected preference "+key);
        return row;
    }
    private Object category(Object parent,String key,String title,int order) throws Exception {
        Object row=add(parent,"COUIPreferenceCategory",key,title);targetCall(row,HostTargets.UI_PREF_ORDER,order);return row;
    }
    private Object find(String key) throws Exception {return targetCall(root,HostTargets.UI_PREF_FIND,key);}
    Object findPreference(CharSequence key) throws Exception {return targetCall(root,HostTargets.UI_PREF_FIND,key);}
    private java.lang.reflect.Method parentGetter()throws NoSuchMethodException{
        var method=HostTargets.methodOrNull(PreferenceTargets.PARENT,loader);
        if(method==null)throw new NoSuchMethodException("Preference parent getter unresolved");
        return method;
    }
    private void move(Object row,Object parent,int order) throws Exception {
        if(row==null)return;
        Object oldParent=parentGetter().invoke(row);
        if(!Boolean.TRUE.equals(targetCall(oldParent,HostTargets.UI_PREF_REMOVE,row)))
            throw new IllegalStateException("Host refused to remove preference");
        targetCall(row,HostTargets.UI_PREF_ORDER,order);
        targetCall(parent,HostTargets.UI_PREF_ADD,row);
    }
    private Object page(Object parent,String key,String title,String description) throws Exception {
        Object entry=add(parent,"COUIJumpPreference","colorpods_open_"+key,title);summary(entry,description);
        Object screen=Class.forName("androidx.preference.PreferenceScreen",true,loader)
            .getConstructor(Context.class,AttributeSet.class).newInstance(activity,null);
        targetCall(screen,HostTargets.UI_PREF_KEY,"colorpods_page_"+key);targetCall(screen,PreferenceTargets.PERSISTENT,false);
        targetCall(screen,PreferenceTargets.VISIBLE,false);
        targetCall(root,HostTargets.UI_PREF_ADD,screen);
        pages.put(key,screen);pageTitles.put(key,title);pageEntries.put(key,entry);
        click(entry,()->navigation.open(key));return screen;
    }
    boolean back(){headPageLeaving();return navigation.back();}
    HostPageNavigation navigation(){return navigation;}
    Object pageScreen(String key){return pages.get(key);}
    String pageTitle(String key){return pageTitles.get(key);}
    private void summary(Object row,String text) throws Exception {targetCall(row,HostTargets.UI_PREF_SUMMARY,text);}
    private void enabled(Object row,boolean value) throws Exception {targetCall(row,PreferenceTargets.ENABLED,value);if(sliders.containsKey(row))sliders.get(row).enabled(value);}
    private void checked(Object row,boolean value) throws Exception {targetCall(row,PreferenceTargets.CHECKED,value);}
    private java.lang.reflect.Method listenerSetter(boolean change)throws NoSuchMethodException{
        var method=HostTargets.methodOrNull(change?PreferenceTargets.CHANGE:PreferenceTargets.CLICK,loader);
        if(method==null)throw new NoSuchMethodException("Preference listener setter unresolved");
        return method;
    }
    private Object listener(Class<?> type,java.util.function.Function<Object[],Object> action) throws Exception {
        return Proxy.newProxyInstance(loader,new Class[]{type},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return switch(method.getName()){
                case "toString"->"ColorPodsCOUIListener";case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->null;};
            return action.apply(args);
        });
    }
    private void click(Object row,Runnable action) throws Exception {
        var setter=listenerSetter(false);Class<?> type=setter.getParameterTypes()[0];Object listener=listener(type,args->{action.run();return true;});
        setter.invoke(row,listener);
    }
    private void change(Object row,java.util.function.Consumer<Object> action) throws Exception {
        if(sliders.containsKey(row)){sliders.get(row).onChange(value->action.accept(value));return;}
        var setter=listenerSetter(true);Class<?> type=setter.getParameterTypes()[0];Object listener=listener(type,args->{action.accept(args[1]);return false;});
        setter.invoke(row,listener);
    }
    private void controlChange(Object row,int command,java.util.function.Function<Object,byte[]> value) throws Exception {
        if(sliders.containsKey(row)){sliders.get(row).onChange(progress->sendControl(command,value.apply(progress)));return;}
        // Accept queued selections so the native switch does not undo its
        // checked state after our listener returns. Failed writes refresh it.
        var setter=listenerSetter(true);Class<?> type=setter.getParameterTypes()[0];Object listener=listener(type,args->sendControl(command,value.apply(args[1])));
        setter.invoke(row,listener);
    }
    private void featureChange(Object row,java.util.function.Function<Object,Boolean> action)throws Exception{
        if(sliders.containsKey(row)){sliders.get(row).onChange(value->action.apply(value));return;}
        var setter=listenerSetter(true);setter.invoke(row,listener(setter.getParameterTypes()[0],args->action.apply(args[1])));
    }
    private void entries(Object row,String[] names,String[] values) throws Exception {
        if(java.util.Arrays.equals((Object[])targetField(PreferenceTargets.ENTRIES).get(row),names)&&java.util.Arrays.equals((Object[])targetField(PreferenceTargets.VALUES).get(row),values))return;
        targetField(PreferenceTargets.ENTRIES).set(row,names);targetField(PreferenceTargets.VALUES).set(row,values);targetCall(row,PreferenceTargets.NOTIFY);
    }
    private Object list(Object parent,String key,String title,String[] names,String[] values) throws Exception {
        Object row=add(parent,"COUIListPreference",key,title);entries(row,names,values);
        targetField(PreferenceTargets.DIALOG_TITLE).set(row,title);
        return row;
    }
    private Object slider(Object parent,String key,String title) throws Exception {
        Object row=add(parent,"COUIPreference",key,title);
        targetCall(row,PreferenceTargets.SELECTABLE,false);
        sliders.put(row,new SystemSettingsSlider(activity,row));
        return row;
    }
    void inject() throws Exception {
        HostUiCompatibility.verify(loader);
        listenerSetter(false);listenerSetter(true);parentGetter();
        root=Reflect.call(fragment,"getPreferenceScreen",new Class[0]);
        if(find("cat_colorpods_home")!=null)return;
        String[] existing={"pref_airpods_spacer_before_disconnect","cat_airpods_disconnect","pref_airpods_bottom_disconnect_gap"};
        int[] orders={10000,10001,10002};
        for(int i=0;i<existing.length;i++){Object row=Reflect.call(fragment,"findPreference",new Class[]{CharSequence.class},existing[i]);if(row!=null)targetCall(row,HostTargets.UI_PREF_ORDER,orders[i]);}
        Object home=category(root,"cat_colorpods_home","耳机功能",0);
        Object listening=page(home,"listening","聆听体验","空间音频、降噪与通透、听力辅助");
        Object controlsPage=page(home,"controls","耳机操控","佩戴检测、头部操控、按压与长按");
        Object routingPage=page(home,"routing","多设备连接","音频来源与设备切换");
        Object morePage=page(home,"more","更多设置","通话按键、优化充电与连接设置");
        Object sound=find("cat_airpods_sound_quality");move(sound,listening,0);
        Object settings=find("cat_airpods_settings");move(settings,controlsPage,0);
        if(settings!=null)targetCall(settings,HostTargets.UI_PREF_TITLE,"佩戴检测");
        Object headEntry=category(controlsPage,"cat_colorpods_head_entry","头部操控",50);
        Object headPage=page(headEntry,"head_motion","头部操控","点头接听、摇头拒接");
        Object head=category(headPage,"cat_colorpods_head_motion","来电操控",0);
        headToggle=add(head,"COUISwitchPreference","colorpods_head_enabled","头部操控");
        summary(headToggle,"仅在来电响铃时识别头部动作");
        featureChange(headToggle,v->changeHead("enabled",(Boolean)v));
        headMapping=list(head,"colorpods_head_mapping","动作设置",new String[]{"点头接听，摇头拒接","摇头接听，点头拒接"},new String[]{"0","1"});
        featureChange(headMapping,v->changeHead("reversed",v.toString().equals("1")));
        Object headTestPage=page(head,"head_motion_test","试一试","识别点头和摇头，不执行通话操作");
        Object headTest=category(headTestPage,"cat_colorpods_head_test","佩戴耳机，保持自然姿势",0);
        headStatus=add(headTest,"COUIPreference","colorpods_head_status","准备体验");
        targetCall(headStatus,PreferenceTargets.SELECTABLE,false);
        summary(headStatus,"开始后连续点头或摇头；体验最长持续 30 秒");
        Object headSoundHint=add(headTest,"COUIPreference","colorpods_head_sound_hint","声音反馈");
        targetCall(headSoundHint,PreferenceTargets.SELECTABLE,false);
        summary(headSoundHint,"轻响提示转动方向，清脆提示表示体验完成；体验使用媒体音量，来电使用通话音量，接听或拒接成功后再确认");
        headStart=add(headTest,"COUIJumpPreference","colorpods_head_start","开始体验");click(headStart,this::startHeadTest);
        headStop=add(headTest,"COUIJumpPreference","colorpods_head_stop","停止体验");click(headStop,this::stopHeadTest);
        Object effects=category(listening,"cat_colorpods_effects","音效",50);
        Object eqPage=page(effects,"equalizer","三段均衡器","低音、中音与高音");
        Object equalizer=category(eqPage,"cat_colorpods_eq","三段均衡器",0);
        eqToggle=add(equalizer,"COUISwitchPreference","colorpods_eq_enabled","自定义均衡器");
        featureChange(eqToggle,v->changeEq("enabled",(Boolean)v?1:0));
        String[] bands={"low","mid","high"},bandNames={"低音","中音","高音"};
        for(int i=0;i<bands.length;i++){
            String band=bands[i];Object row=slider(equalizer,"colorpods_eq_"+band,bandNames[i]);eqRows.put(band,row);
            featureChange(row,v->changeEq(band,(Integer)v));
        }
        eqReset=add(equalizer,"COUIJumpPreference","colorpods_eq_reset","恢复标准音效");click(eqReset,()->changeEq("reset",0));
        Object experience=category(listening,"cat_colorpods_listening","智能聆听",100);
        move(find("pref_airpods_conversation_aware"),experience,-1);
        controlSwitch(experience,0x26);controlSwitch(experience,0x35);
        Object noise=category(listening,"cat_colorpods_noise","降噪与通透",200);
        controlSwitch(noise,0x1b);controlSwitch(noise,0x34);
        Object hearingEntry=category(listening,"cat_colorpods_hearing_entry","听力健康",300);
        Object hearingPage=page(hearingEntry,"hearing","听力辅助","大音量降低与助听器");
        Object actions=category(controlsPage,"cat_colorpods_actions","按压与长按",100);
        controlList(actions,0x17,new String[]{"默认","较慢","最慢"},new int[]{0,1,2});
        controlList(actions,0x18,new String[]{"默认","较短","最短"},new int[]{0,1,2});
        Object modesPage=page(actions,"listening_modes","长按切换模式","至少选择两种聆听模式");
        controls.put(0x1a,pageEntries.get("listening_modes"));
        Object modes=category(modesPage,"cat_colorpods_listening_modes","至少选择两种聆听模式",0);
        for(int bit:new int[]{1,2,4,8}){
            Object row=add(modes,"COUISwitchPreference","colorpods_listening_mode_"+bit,ListeningModes.name(bit));listeningModeRows.put(bit,row);
            var setter=listenerSetter(true);
            setter.invoke(row,listener(setter.getParameterTypes()[0],args->selectListeningMode(bit,(Boolean)args[1])));
        }
        Object chime=slider(actions,"colorpods_control_1f","提示声音量");controls.put(0x1f,chime);
        controlChange(chime,0x1f,v->new byte[]{((Integer)v).byteValue(),0x50});
        String[] pressNames={"按一下","按两下","按三下","长按"};
        String[] defaultNames={"播放/暂停","下一首","上一首","切换聆听模式"};
        for(int side=1;side<=2;side++){
            int bud=side;
            Object stemPage=page(actions,side==1?"stem_left":"stem_right",side==1?"左耳柄":"右耳柄","单按、双按、三按与长按");
            Object stem=category(stemPage,"cat_colorpods_stem_"+side,"按压动作",0);
            for(int type=0;type<4;type++){
                int press=type;
                Object row=list(stem,"colorpods_stem_"+side+"_"+type,pressNames[type],
                    new String[]{"默认（"+defaultNames[type]+"）","播放/暂停","下一首","上一首","语音助手","切换聆听模式","不执行动作"},new String[]{"0","1","2","3","4","5","6"});
                stemRows.put(StemConfig.index(side,type),row);featureChange(row,v->changeStem(bud,press,Integer.parseInt(v.toString())));
            }
        }
        Object assistant=add(actions,"COUIJumpPreference","colorpods_assistant_test","测试默认语音助手");
        click(assistant,()->{try{DefaultAssistant.launch(activity);}catch(Exception e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_SHORT).show();}});
        Object volume=category(controlsPage,"cat_colorpods_volume","音量滑动",200);
        controlSwitch(volume,0x25);
        controlList(volume,0x23,new String[]{"默认","较长","最长"},new int[]{0,1,2});
        Object hearing=category(hearingPage,"cat_colorpods_hearing","听力辅助",0);
        attSwitch(hearing,AttCodec.LOUD_SOUND,"enabled","降低大音量");
        Object hearingToggle=add(hearing,"COUISwitchPreference","colorpods_control_2c","助听器");controls.put(0x2c,hearingToggle);switchRows.put(hearingToggle,true);
        controlChange(hearingToggle,0x2c,value->new byte[]{1,(byte)((Boolean)value?1:2)});
        tuningRows(hearing,AttCodec.HEARING_AID);
        Object routing=category(routingPage,"cat_colorpods_routing","设备切换",0);
        multiRow=add(routing,"COUISwitchPreference","colorpods_multipoint","多设备协同");
        change(multiRow,value->{Bundle b=request();b.putBoolean("enabled",(Boolean)value);operation(BridgeClient.MULTIPOINT,b,r->{snapshot=r;});});
        autoMediaRow=add(routing,"COUISwitchPreference","colorpods_auto_media","手机播放时自动切回");
        change(autoMediaRow,value->{Bundle b=request();b.putBoolean("autoMedia",(Boolean)value);operation(BridgeClient.MULTIPOINT,b,r->{snapshot=r;});});
        autoGameReturnRow=add(routing,"COUISwitchPreference","colorpods_auto_game_return","远端暂停后返回游戏");
        summary(autoGameReturnRow,"其他设备暂停后，自动切回仍在前台发声的游戏");
        change(autoGameReturnRow,value->{Bundle b=request();b.putBoolean("autoGameReturn",(Boolean)value);operation(BridgeClient.MULTIPOINT,b,r->{snapshot=r;});});
        routeStatus=add(routing,"COUIPreference","colorpods_route_status","音频流转状态");targetCall(routeStatus,PreferenceTargets.SELECTABLE,false);
        routeRow=list(routing,"colorpods_route","将音频切回此手机",new String[0],new String[0]);
        change(routeRow,value->route(value.toString(),false));
        reverseRow=add(routing,"COUIJumpPreference","colorpods_route_reverse","撤销其他设备的切换");click(reverseRow,()->route(snapshot.getString("audioSource"),true));
        Object connection=category(morePage,"cat_colorpods_connection","通话与连接",0);
        controlList(connection,0x01,new String[]{"自动","始终右侧","始终左侧"},new int[]{0,1,2});controlSwitch(connection,0x20);
        Object callControl=list(connection,"colorpods_control_24","通话按键",new String[]{"按一下静音，按两下挂断","按两下静音，按一下挂断"},new String[]{"3","2"});
        controls.put(0x24,callControl);controlChange(callControl,0x24,v->new byte[]{0,(byte)Integer.parseInt(v.toString())});
        Object battery=category(morePage,"cat_colorpods_battery","电池",50);controlSwitch(battery,0x3b);
        Object advancedEntry=category(morePage,"cat_colorpods_advanced_entry","高级",100);
        Object advancedPage=page(advancedEntry,"advanced","设备兼容性","Apple 设备身份");
        Object identity=category(advancedPage,"cat_colorpods_identity","设备兼容性",0);
        vendorRow=add(identity,"COUISwitchPreference","colorpods_vendor","以 Apple 设备身份连接");enabled(vendorRow,false);
        change(vendorRow,value->{Bundle b=request();b.putBoolean("enabled",(Boolean)value);operation(BridgeClient.VENDOR,b,r->{snapshot=r;lastError="身份设置已保存，请关闭再开启蓝牙后重新连接耳机";});});
        update();module.info("All settings inserted as host COUI preferences");
    }
    private void controlList(Object parent,int cmd,String[] names,int[] values) throws Exception {
        String[] strings=new String[values.length];for(int i=0;i<values.length;i++)strings[i]=Integer.toString(values[i]);
        Object row=list(parent,"colorpods_control_"+Integer.toHexString(cmd),Protocol.COMMANDS.get(cmd),names,strings);controls.put(cmd,row);
        controlChange(row,cmd,v->new byte[]{(byte)Integer.parseInt(v.toString())});
    }
    private boolean selectListeningMode(int bit,boolean selected){
        if(!ListeningModes.available(listeningMask,bit,listeningOffAllowed,listeningAdaptiveSupported))return false;
        try{
            int next=ListeningModes.select(listeningMask,bit,selected);
            return next!=listeningMask&&sendControl(0x1a,new byte[]{(byte)next});
        }catch(IllegalArgumentException e){Toast.makeText(activity,e.getMessage(),Toast.LENGTH_SHORT).show();return false;}
    }
    private void controlSwitch(Object parent,int cmd) throws Exception {
        Object row=add(parent,"COUISwitchPreference","colorpods_control_"+Integer.toHexString(cmd),Protocol.COMMANDS.get(cmd));controls.put(cmd,row);switchRows.put(row,true);
        controlChange(row,cmd,v->new byte[]{(byte)((Boolean)v?1:2)});
    }
    private void attSwitch(Object parent,int handle,String field,String title) throws Exception {
        Object row=add(parent,"COUISwitchPreference","colorpods_att_"+handle+"_"+field,title);attRows.put(handle+":"+field,row);switchRows.put(row,true);
        change(row,v->patch(handle,field,(Boolean)v?1:0));
    }
    private void tuningRows(Object parent,int handle) throws Exception {
        String[][] fields={{"amplification","放大量"},{"tone","音调"},{"noise","环境噪声降低"},{"voice","本声放大"}};
        for(String[] f:fields){Object row=slider(parent,"colorpods_att_"+handle+"_"+f[0],f[1]);attRows.put(handle+":"+f[0],row);change(row,v->patch(handle,f[0],((Integer)v)/100f));}
        attSwitch(parent,handle,"boost","对话增强");
    }
    private String address() throws Exception {
        Object vm=Reflect.call(activity,"getViewModel",new Class[0]);BluetoothDevice d=(BluetoothDevice)Reflect.call(vm,"getBluetoothDevice",new Class[0]);return d==null?null:d.getAddress();
    }
    private Bundle request(){try{return BridgeClient.forDevice(address());}catch(Exception e){throw new IllegalStateException(e);}}
    void start(){active=true;main.removeCallbacksAndMessages(null);bindAdaptiveStrength();connection.start();refresh();}
    void stop(){stopHeadTest();active=false;adaptiveStrength.detach();attGeneration++;attLoading=false;attWasReady=false;attValues.clear();connection.stop();main.removeCallbacksAndMessages(null);}
    private void bindAdaptiveStrength(){try{adaptiveStrength.bind();}catch(Exception e){module.error("Adaptive strength binding failed",e);}}
    void listeningSelectorChanged(View selector){
        if(!alive())return;
        int id=activity.getResources().getIdentifier("ll_operation_noise","id",activity.getPackageName());
        if(activity.findViewById(id)==selector)bindAdaptiveStrength();
    }
    void resume(){headForeground=true;if(!active)start();main.post(this::connectionChanged);}
    void pauseHeadMotion(){headForeground=false;stopHeadTest();}
    void headPageLeaving(){stopHeadTest();}
    void headPageChanged(){
        main.post(()->{if(headOwner!=null&&!headTestVisible())stopHeadTest();});
    }
    private boolean headTestVisible(){
        try{return headForeground&&"head_motion_test".equals(navigation.visiblePage());}
        catch(Exception e){return false;}
    }
    private void connectionChanged(){
        if(!alive())return;connection.reconcile();
        try{update();}catch(Exception e){module.error("COUI connection update failed",e);}
        main.removeCallbacks(refreshTask);main.postDelayed(refreshTask,100);
    }
    private boolean alive(){return active&&!activity.isDestroyed()&&!activity.isFinishing();}
    private void onConfirmationDeadline(){if(!alive())return;try{update();}catch(Exception e){module.error("COUI confirmation timeout update failed",e);}}
    private void refresh(){
        if(!alive())return;connection.reconcile();if(busy){main.postDelayed(refreshTask,2000);return;}
        if(headOwner!=null&&!headTestVisible())stopHeadTest();
        Bundle b=request();if(headOwner!=null)b.putString("headMotionOwner",headOwner);
        IO.execute(()->{Bundle result=null;String error=null;try{result=bridge.request(BridgeClient.STATUS,b);}catch(Exception e){error=e.getMessage();}
            Bundle r=result;String message=error;main.post(()->{if(!alive())return;if(r!=null){snapshot=r;connectionError=null;}else connectionError=message;try{update();}catch(Exception e){module.error("COUI update failed",e);}main.removeCallbacks(refreshTask);main.postDelayed(refreshTask,headOwner==null?2500:300);});});
    }
    private void update() throws Exception {
        bindAdaptiveStrength();
        int link=connection.state();
        boolean connected=link==ConnectionState.CONNECTED||(link==ConnectionState.UNKNOWN&&snapshot.getBoolean("connected"));
        boolean ready=link!=ConnectionState.DISCONNECTED&&snapshot.getBoolean("controlReady");long[] identity=snapshot.getLongArray("nativeStatus");
        enabled(vendorRow,identity!=null&&!busy);if(identity!=null)checked(vendorRow,identity[0]!=0);
        summary(vendorRow,"修改后需关闭再开启蓝牙");
        updateAudioSettings(ready);
        updateHeadMotion(ready);
        Bundle c=snapshot.getBundle("controls");if(c==null)c=Bundle.EMPTY;
        Bundle pending=snapshot.getBundle("pendingControls");if(pending==null)pending=Bundle.EMPTY;
        Bundle updates=snapshot.getBundle("controlUpdates");if(updates==null)updates=Bundle.EMPTY;
        long now=SystemClock.elapsedRealtime(),nextDeadline=updateAdaptiveStrength(ready,c,pending,updates,now);
        main.removeCallbacks(confirmationTimeout);
        for(var entry:controls.entrySet()){
            int command=entry.getKey();Object row=entry.getValue();String key=Integer.toHexString(command);
            Bundle update=updates.getBundle(key);int state=update==null?0:ControlWrite.waitingState(update.getInt("state"),update.getLong("deadline"),now);
            byte[] reported=c.getByteArray(key),expected=update==null?null:update.getByteArray("expected");
            byte[] value=ControlWrite.displayedValue(reported,expected,state);
            // Compatibility for a host still running with the previous backend.
            if(update==null&&pending.containsKey(key))value=pending.getByteArray(key);
            if(sendingControls.containsKey(command))value=sendingControls.get(command);
            int summaryState=sendingControls.containsKey(command)?ControlWrite.WAITING:state;
            boolean requiresReport=command==0x1a||command==0x1f||command==0x24||command==0x3b;
            if(requiresReport)value=Protocol.reportedValue(command,value);
            boolean known=value!=null&&value.length>0;
            boolean editable=ready&&!busy&&!sendingControls.containsKey(command)&&(!(switchRows.containsKey(row)||requiresReport)||known);
            enabled(row,editable);
            if(command==0x1a){
                listeningMask=known?value[0]&255:-1;
                byte[] allowOff=Protocol.reportedValue(0x34,c.getByteArray("34"));
                listeningOffAllowed=allowOff==null||allowOff[0]==1;
                listeningAdaptiveSupported=(known&&(listeningMask&8)!=0)||Protocol.reportedValue(0x2e,c.getByteArray("2e"))!=null;
                for(var mode:listeningModeRows.entrySet()){
                    int bit=mode.getKey();Object toggle=mode.getValue();
                    boolean available=ListeningModes.available(listeningMask,bit,listeningOffAllowed,listeningAdaptiveSupported);
                    checked(toggle,known&&(listeningMask&bit)!=0);enabled(toggle,editable&&available);
                    summary(toggle,!known?"等待耳机回报设置":available?"":bit==1?"请先允许关闭聆听模式":"尚未确认此耳机支持自适应模式");
                }
                summary(row,controlSummary(known?ListeningModes.label(listeningMask):"等待耳机回报设置",summaryState));
            }
            else if(command==0x1f){if(known)sliders.get(row).value(value[0]&255);summary(row,controlSummary(known?(value[0]&255)+"%":"等待耳机回报设置",summaryState));}
            else if(command==0x3b){checked(row,known&&value[0]==1);summary(row,controlSummary(known?"根据日常使用习惯优化充电，延缓电池老化":"等待耳机回报设置",summaryState));}
            else if(command==0x24){call(row,"setValue",String.class,known?Integer.toString(value[1]&255):"");summary(row,controlSummary(known?controlLabel(row,command,value):"等待耳机回报设置",summaryState));}
            else if(switchRows.containsKey(row)){if(known)checked(row,command==0x2c?value.length>1&&value[0]==1&&value[1]==1:value[0]==1);if(command!=0x2c)summary(row,"");}
            else{call(row,"setValue",String.class,known?Integer.toString(value[0]&255):"");summary(row,known?controlLabel(row,command,value):"");}
            if(state==ControlWrite.WAITING)nextDeadline=Math.min(nextDeadline,update.getLong("deadline"));
        }
        if(nextDeadline!=Long.MAX_VALUE&&active)main.postDelayed(confirmationTimeout,Math.max(1,nextDeadline-SystemClock.elapsedRealtime()));
        boolean attReady=ready&&identity!=null&&identity[0]!=0&&identity[1]!=0&&identity[3]>0;
        if(!attReady){if(attWasReady){attGeneration++;attLoading=false;attValues.clear();}attWasReady=false;}
        else {if(!attWasReady){attWasReady=true;nextAttLoad=0;}loadHearing();}
        for(var entry:attRows.entrySet()){
            String[] parts=entry.getKey().split(":");int handle=Integer.parseInt(parts[0]);String field=parts[1];Object row=entry.getValue();byte[] data=attValues.get(handle);
            boolean supported=data!=null&&(!field.equals("voice")||data.length>=104);enabled(row,attReady&&!busy&&supported);
            if(!supported){summary(row,"");continue;}
            float value=AttCodec.value(handle,data,field);
            if(switchRows.containsKey(row)){checked(row,value>0.5f);summary(row,"");}
            else{sliders.get(row).value(Math.round(value*100));summary(row,Math.round(value*100)+"%");}
        }
        boolean multi=snapshot.getBoolean("multipoint");checked(multiRow,multi);enabled(multiRow,identity!=null&&!busy);
        checked(autoMediaRow,snapshot.getBoolean("autoMedia",true));enabled(autoMediaRow,multi&&identity!=null&&!busy);
        checked(autoGameReturnRow,snapshot.getBoolean("autoGameReturn",false));enabled(autoGameReturnRow,multi&&identity!=null&&!busy);
        String[] p=snapshot.getStringArray("peers");String self=snapshot.getString("selfAddress");ArrayList<String> targets=new ArrayList<>(),names=new ArrayList<>();Bundle types=snapshot.getBundle("peerTypes");if(types==null)types=Bundle.EMPTY;
        if(p!=null)for(String peer:p)if(!peer.equals(self)){targets.add(peer);names.add(types.getString(peer,"另一台设备"));}
        entries(routeRow,names.toArray(new String[0]),targets.toArray(new String[0]));enabled(routeRow,multi&&attReady&&!busy&&!targets.isEmpty());
        String source=snapshot.getString("audioSource");boolean otherSource=source!=null&&!source.equals(self)&&targets.contains(source)&&snapshot.getInt("audioSourceType")!=0;
        String routeText=!connected&&!ready?"耳机未连接":targets.isEmpty()?"已连接此手机":"已发现：此手机、"+String.join("、",names);
        routeText+="；音频："+(snapshot.getInt("audioSourceType")==0?"空闲":source!=null&&source.equals(self)?"此手机":types.getString(source,"另一台设备"));
        if(snapshot.getString("routeNotice")!=null)routeText+="\n"+snapshot.getString("routeNotice");summary(routeStatus,routeText);enabled(reverseRow,multi&&attReady&&!busy&&otherSource);
        summary(pageEntries.get("routing"),!multi?"未开启 · 在设备之间切换耳机音频":targets.isEmpty()?"已开启 · 尚未发现其他设备":snapshot.getInt("audioSourceType")==0?"已开启 · 当前无音频播放":"音频来源："+(source!=null&&source.equals(self)?"此手机":types.getString(source,"另一台设备")));
    }
    private long updateAdaptiveStrength(boolean ready,Bundle controls,Bundle pending,Bundle updates,long now){
        Bundle update=updates.getBundle("2e");
        int state=update==null?0:ControlWrite.waitingState(update.getInt("state"),update.getLong("deadline"),now);
        byte[] value=ControlWrite.displayedValue(controls.getByteArray("2e"),update==null?null:update.getByteArray("expected"),state);
        if(update==null&&pending.containsKey("2e"))value=pending.getByteArray("2e");
        boolean sending=sendingControls.containsKey(0x2e);
        if(sending)value=sendingControls.get(0x2e);
        int strength=AdaptiveStrength.fromReport(value);
        adaptiveStrength.update(strength,ready&&!busy&&!sending&&strength>=0,controlSummary("",sending?ControlWrite.WAITING:state));
        return state==ControlWrite.WAITING?update.getLong("deadline"):Long.MAX_VALUE;
    }
    private String controlLabel(Object row,int command,byte[] value) throws Exception {
        if(value==null||value.length==0)return "未知";
        if(switchRows.containsKey(row))return (command==0x2c?value.length>1&&value[0]==1&&value[1]==1:value[0]==1)?"开启":"关闭";
        String selected=Integer.toString(value[command==0x24?1:0]&255);Object[] labels=(Object[])targetField(PreferenceTargets.ENTRIES).get(row),values=(Object[])targetField(PreferenceTargets.VALUES).get(row);
        for(int i=0;i<values.length;i++)if(selected.equals(values[i].toString()))return labels[i].toString();
        return selected;
    }
    private static String controlSummary(String label,int state){
        return label+switch(state){case ControlWrite.WAITING->" · 等待耳机确认";case ControlWrite.UNCONFIRMED->" · 尚未收到耳机确认";case ControlWrite.DIFFERENT->" · 已显示耳机实际设置";default->"";};
    }
    private CustomEq currentEq(){
        if(eqSelection!=null)return eqSelection;
        int[] eq=snapshot.getIntArray("customEq");
        return eq!=null&&eq.length==4?new CustomEq(eq[0],eq[1],eq[2],eq[3]):CustomEq.flat();
    }
    private StemConfig currentStem(){
        if(stemSelection!=null)return stemSelection;
        int[] actions=snapshot.getIntArray("stemActions");return actions==null?StemConfig.defaults():new StemConfig(actions);
    }
    private void updateAudioSettings(boolean ready)throws Exception{
        boolean editable=ready&&!busy&&snapshot.getInt("advancedControlsVersion")>=2;
        CustomEq eq=currentEq();checked(eqToggle,eq.enabled());enabled(eqToggle,editable);
        summary(eqToggle,snapshot.getBoolean("eqKnown")?"":"首次使用从标准音效开始");
        int[] values={eq.low(),eq.mid(),eq.high()};int index=0;
        for(Object row:eqRows.values()){int value=values[index++];sliders.get(row).value(value);enabled(row,editable&&eq.enabled());summary(row,value+"%");}
        enabled(eqReset,editable&&eq.enabled());
        StemConfig config=currentStem();
        for(var entry:stemRows.entrySet()){
            Object row=entry.getValue();int action=config.values()[entry.getKey()];
            call(row,"setValue",String.class,Integer.toString(action));enabled(row,editable);
            Object[] names=(Object[])targetField(PreferenceTargets.ENTRIES).get(row);summary(row,names[action].toString());
        }
    }
    private boolean changeEq(String field,int value){
        if(busy)return false;
        eqSelection=currentEq().patch(field,value);Bundle b=request();b.putString("field",field);b.putInt("value",value);
        operation(BridgeClient.EQ_PATCH,b,r->{snapshot=r;},()->eqSelection=null);return true;
    }
    private boolean changeHead(String field,boolean value){
        if(busy)return false;
        stopHeadTest();Bundle b=request();b.putString("action","configure");b.putString("field",field);b.putBoolean("value",value);
        operation(BridgeClient.HEAD_MOTION,b,r->snapshot=r);return true;
    }
    private void startHeadTest(){
        if(busy||headOwner!=null||!headTestVisible())return;
        String owner=java.util.UUID.randomUUID().toString();headOwner=owner;
        Bundle b=request();b.putString("action","start");b.putString("owner",owner);
        operation(BridgeClient.HEAD_MOTION,b,r->{
            snapshot=r;
            if(owner.equals(headOwner)&&!headTestVisible())stopHeadTest();
            main.removeCallbacks(refreshTask);main.post(refreshTask);
        },()->main.post(()->{if(lastError!=null&&owner.equals(headOwner)){headOwner=null;main.post(refreshTask);}}));
    }
    private void stopHeadTest(){
        String owner=headOwner;if(owner==null)return;headOwner=null;
        Bundle b=request();b.putString("action","stop");b.putString("owner",owner);
        IO.execute(()->{try{bridge.request(BridgeClient.HEAD_MOTION,b);}catch(Exception e){module.info("Head motion experience stop unavailable: "+e.getMessage());}});
        if(alive()){main.removeCallbacks(refreshTask);main.post(refreshTask);}
    }
    private void updateHeadMotion(boolean ready)throws Exception{
        boolean supported=snapshot.getInt("headMotionVersion")>=1;
        Bundle head=snapshot.getBundle("headMotion");if(head==null)head=Bundle.EMPTY;
        checked(headToggle,head.getBoolean("enabled"));enabled(headToggle,supported&&!busy);
        summary(headToggle,head.getBoolean("callsAvailable")?"仅在来电响铃时识别头部动作":"当前系统通话适配尚未就绪，可使用试一试");
        boolean reversed=head.getBoolean("reversed");call(headMapping,"setValue",String.class,reversed?"1":"0");
        enabled(headMapping,supported&&!busy);summary(headMapping,reversed?"摇头接听，点头拒接":"点头接听，摇头拒接");
        summary(pageEntries.get("head_motion"),head.getBoolean("enabled")?reversed?"已开启 · 摇头接听，点头拒接":"已开启 · 点头接听，摇头拒接":"未开启 · 点头或摇头处理来电");
        boolean testing=head.getBoolean("active")&&head.getBoolean("testing")&&headOwner!=null&&headOwner.equals(head.getString("owner"));
        if(headOwner!=null&&!head.getBoolean("active")&&headOwner.equals(head.getString("owner")))headOwner=null;
        enabled(headStart,supported&&ready&&!busy&&headOwner==null&&head.getBoolean("available"));
        enabled(headStop,headOwner!=null);
        String text=!supported?"请更新模块并重启蓝牙后重试":head.getString("message","开始后连续点头或摇头；体验最长持续 30 秒");
        targetCall(headStatus,HostTargets.UI_PREF_TITLE,testing?head.getInt("gesture")==1?"识别到点头":head.getInt("gesture")==2?"识别到摇头":"正在体验":"准备体验");
        summary(headStatus,text);
        summary(headStart,headOwner!=null&&!testing?"正在开始体验":testing?"体验中":"最长 30 秒，离开页面后自动停止");
    }
    private boolean changeStem(int side,int press,int action){
        if(busy)return false;
        stemSelection=currentStem().with(side,press,action);Bundle b=request();b.putInt("side",side);b.putInt("press",press);b.putInt("action",action);
        operation(BridgeClient.STEM_CONFIG,b,r->{snapshot=r;},()->stemSelection=null);return true;
    }
    private boolean sendControl(int command,byte[] value){
        if(busy||sendingControls.containsKey(command))return false;
        Bundle b=request();b.putInt("command",command);b.putByteArray("value",value);
        sendingControls.put(command,value.clone());lastError=null;try{update();}catch(Exception e){module.error("COUI selection update failed",e);}
        IO.execute(()->{
            Bundle response=null;String error=null;
            try{
                response=bridge.request(BridgeClient.CONTROL,b);
                if(command==0x2c){Bundle follow=new Bundle(b);follow.putInt("command",0x33);follow.putByteArray("value",new byte[]{value[1]});response=bridge.request(BridgeClient.CONTROL,follow);}
            }catch(Exception e){error=e.getMessage();}
            Bundle result=response;String message=error;
            main.post(()->{sendingControls.remove(command);if(!alive())return;
                if(result!=null)snapshot=result;lastError=message;
                if(message!=null)Toast.makeText(activity,"设置失败，请重试",Toast.LENGTH_SHORT).show();
                try{update();}catch(Exception e){module.error("COUI control update failed",e);}
            });
        });
        return true;
    }
    private void route(String target,boolean reverse){Bundle b=request();b.putString("target",target);b.putBoolean("reverse",reverse);operation(BridgeClient.ROUTE,b,r->{snapshot=r;lastError=r.getBoolean("routeConfirmed")?"耳机已确认切换":r.getBoolean("audioReady")?"手机音频通道已接通，等待耳机更新来源":"切换请求已发送，等待耳机确认";});}
    private void loadHearing(){
        if(attLoading||busy||SystemClock.elapsedRealtime()<nextAttLoad||
                (attValues.containsKey(AttCodec.HEARING_AID)&&attValues.containsKey(AttCodec.LOUD_SOUND)))return;
        attLoading=true;nextAttLoad=SystemClock.elapsedRealtime()+10000;int generation=attGeneration;
        Bundle request=request();
        IO.execute(()->{
            Map<Integer,byte[]> loaded=new HashMap<>();
            for(int handle:new int[]{AttCodec.HEARING_AID,AttCodec.LOUD_SOUND}){
                try{Bundle b=new Bundle(request);b.putInt("handle",handle);byte[] value=bridge.request(BridgeClient.ATT_READ,b).getByteArray("value");AttCodec.validateValue(handle,value);loaded.put(handle,value);}
                catch(Exception e){module.info("Automatic hearing settings read failed: "+e.getMessage());}
            }
            main.post(()->{if(generation!=attGeneration)return;attLoading=false;if(!alive()||!attWasReady)return;loaded.forEach(attValues::putIfAbsent);try{update();}catch(Exception e){module.error("COUI hearing update failed",e);}});
        });
    }
    private void patch(int handle,String field,float value){Bundle b=request();b.putInt("handle",handle);b.putString("field",field);b.putFloat("value",value);operation(BridgeClient.ATT_PATCH,b,r->{attValues.put(handle,r.getByteArray("value"));});}
    private void operation(int operation,Bundle b,java.util.function.Consumer<Bundle> callback){
        operation(operation,b,callback,()->{});
    }
    private void operation(int operation,Bundle b,java.util.function.Consumer<Bundle> callback,Runnable completed){
        if(busy)return;busy=true;lastError=null;try{update();}catch(Exception ignored){}
        IO.execute(()->{Bundle response=null;String error=null;try{response=bridge.request(operation,b);}catch(Exception e){error=e.getMessage();}
            Bundle result=response;String message=error;main.post(()->{busy=false;completed.run();if(!alive())return;lastError=message;if(message!=null)Toast.makeText(activity,"设置失败，请重试",Toast.LENGTH_SHORT).show();if(result!=null)callback.accept(result);try{update();}catch(Exception e){module.error("COUI operation update failed",e);}});});
    }
}
