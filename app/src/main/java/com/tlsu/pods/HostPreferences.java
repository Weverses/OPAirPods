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
    private final Map<Integer,Object> controls=new LinkedHashMap<>();
    private final Map<Integer,byte[]> sendingControls=new HashMap<>();
    private final Map<Object, Boolean> switchRows=new HashMap<>();
    private final Map<String,Object> attRows=new LinkedHashMap<>();
    private final Map<Integer,byte[]> attValues=new HashMap<>();
    private final Map<String,Object> pages=new LinkedHashMap<>();
    private final Map<String,String> pageTitles=new HashMap<>();
    private final Map<String,Object> pageEntries=new HashMap<>();
    private final HostPageNavigation navigation;
    private final HostConnection connection;
    private final Runnable refreshTask=this::refresh;
    private Object statusRow,vendorRow,routeRow,multiRow,autoMediaRow,routeStatus,reverseRow,reconnectRow,transRead,hearingRead,refreshControlsRow,root;
    private Bundle snapshot=Bundle.EMPTY;
    private boolean active,busy;
    private String lastError;
    private String connectionError;
    private final Class<?> prefClass;

    HostPreferences(Object fragment,ClassLoader loader,ColorPodsModule module) throws Exception {
        this.fragment=fragment;this.loader=loader;this.module=module;
        activity=(Activity)Reflect.call(fragment,"getActivity",new Class[0]);
        if(activity==null)throw new IllegalStateException("Host activity unavailable");
        bridge=new BridgeClient(activity);prefClass=Class.forName("androidx.preference.Preference",false,loader);
        navigation=new HostPageNavigation(this,activity,loader,module);
        connection=new HostConnection(activity,module,this::connectionChanged);
    }
    private Object create(String name) throws Exception {
        Class<?> cls=Class.forName("com.coui.appcompat.preference."+name,true,loader);
        try{return cls.getConstructor(Context.class).newInstance(activity);}
        catch(NoSuchMethodException e){return cls.getConstructor(Context.class,AttributeSet.class).newInstance(activity,null);}
    }
    private void call(Object row,String name,Class<?> type,Object arg) throws Exception {Reflect.call(row,name,new Class[]{type},arg);}
    private void setField(Object row,String name,Object value) throws Exception {
        for(Class<?> c=row.getClass();c!=null;c=c.getSuperclass())try{var f=c.getDeclaredField(name);f.setAccessible(true);f.set(row,value);return;}catch(NoSuchFieldException ignored){}
        throw new NoSuchFieldException(name);
    }
    private Object add(Object parent,String kind,String key,String title) throws Exception {
        Object row=create(kind);call(row,"D0",String.class,key);call(row,"O0",CharSequence.class,title);call(row,"J0",boolean.class,false);
        if(!Boolean.TRUE.equals(Reflect.call(parent,"W0",new Class[]{prefClass},row)))throw new IllegalStateException("Host rejected preference "+key);
        return row;
    }
    private Object category(Object parent,String key,String title,int order) throws Exception {
        Object row=add(parent,"COUIPreferenceCategory",key,title);call(row,"I0",int.class,order);return row;
    }
    private Object find(String key) throws Exception {return Reflect.call(root,"X0",new Class[]{CharSequence.class},key);}
    Object findPreference(CharSequence key) throws Exception {return Reflect.call(root,"X0",new Class[]{CharSequence.class},key);}
    private void move(Object row,Object parent,int order) throws Exception {
        if(row==null)return;
        Object oldParent=Reflect.call(row,"B",new Class[0]);
        Reflect.call(oldParent,"f1",new Class[]{prefClass},row);
        call(row,"I0",int.class,order);
        Reflect.call(parent,"W0",new Class[]{prefClass},row);
    }
    private Object page(Object parent,String key,String title,String description) throws Exception {
        Object entry=add(parent,"COUIJumpPreference","colorpods_open_"+key,title);summary(entry,description);
        Object screen=Class.forName("androidx.preference.PreferenceScreen",true,loader)
            .getConstructor(Context.class,AttributeSet.class).newInstance(activity,null);
        call(screen,"D0",String.class,"colorpods_page_"+key);call(screen,"J0",boolean.class,false);
        call(screen,"P0",boolean.class,false);
        Reflect.call(root,"W0",new Class[]{prefClass},screen);
        pages.put(key,screen);pageTitles.put(key,title);pageEntries.put(key,entry);
        click(entry,()->navigation.open(key));return screen;
    }
    boolean back(){return navigation.back();}
    HostPageNavigation navigation(){return navigation;}
    Object pageScreen(String key){return pages.get(key);}
    String pageTitle(String key){return pageTitles.get(key);}
    private void summary(Object row,String text) throws Exception {call(row,"L0",CharSequence.class,text);}
    private void enabled(Object row,boolean value) throws Exception {call(row,"x0",boolean.class,value);}
    private void checked(Object row,boolean value) throws Exception {call(row,"W0",boolean.class,value);}
    private Object listener(String name,java.util.function.Function<Object[],Object> action) throws Exception {
        Class<?> type=Class.forName("androidx.preference.Preference$"+name,false,loader);
        return Proxy.newProxyInstance(loader,new Class[]{type},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return switch(method.getName()){
                case "toString"->"ColorPodsCOUIListener";case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->null;};
            return action.apply(args);
        });
    }
    private void click(Object row,Runnable action) throws Exception {
        Object listener=listener("d",args->{action.run();return true;});
        call(row,"H0",Class.forName("androidx.preference.Preference$d",false,loader),listener);
    }
    private void change(Object row,java.util.function.Consumer<Object> action) throws Exception {
        Object listener=listener("c",args->{action.accept(args[1]);return false;});
        call(row,"G0",Class.forName("androidx.preference.Preference$c",false,loader),listener);
    }
    private void controlChange(Object row,int command,java.util.function.Function<Object,byte[]> value) throws Exception {
        // Accept queued selections so the native switch does not undo its
        // checked state after our listener returns. Failed writes refresh it.
        Object listener=listener("c",args->sendControl(command,value.apply(args[1])));
        call(row,"G0",Class.forName("androidx.preference.Preference$c",false,loader),listener);
    }
    private void entries(Object row,String[] names,String[] values) throws Exception {
        if(java.util.Arrays.equals((Object[])Reflect.field(row,"X"),names)&&java.util.Arrays.equals((Object[])Reflect.field(row,"Y"),values))return;
        setField(row,"X",names);setField(row,"Y",values);Reflect.call(row,"T",new Class[0]);
    }
    private Object list(Object parent,String key,String title,String[] names,String[] values) throws Exception {
        Object row=add(parent,"COUIListPreference",key,title);entries(row,names,values);
        setField(row,"R",title);setField(row,"V","取消");setField(row,"U","确定");
        return row;
    }
    void inject() throws Exception {
        long version=activity.getPackageManager().getPackageInfo("com.heytap.mydevices",0).getLongVersionCode();
        if(version!=1725000)throw new IllegalStateException("Unsupported MyDevices version "+version);
        root=Reflect.call(fragment,"getPreferenceScreen",new Class[0]);
        if(find("cat_colorpods_home")!=null)return;
        String[] existing={"pref_airpods_spacer_before_disconnect","cat_airpods_disconnect","pref_airpods_bottom_disconnect_gap"};
        int[] orders={10000,10001,10002};
        for(int i=0;i<existing.length;i++){Object row=Reflect.call(fragment,"findPreference",new Class[]{CharSequence.class},existing[i]);if(row!=null)call(row,"I0",int.class,orders[i]);}
        Object home=category(root,"cat_colorpods_home","耳机功能",0);
        Object listening=page(home,"listening","聆听体验","空间音频、降噪与通透、听力辅助");
        Object controlsPage=page(home,"controls","耳机操控","佩戴检测、按压与长按、音量滑动");
        Object routingPage=page(home,"routing","多设备连接","音频来源与设备切换");
        Object morePage=page(home,"more","更多设置","麦克风、自动连接与高级设置");
        Object sound=find("cat_airpods_sound_quality");move(sound,listening,0);
        Object settings=find("cat_airpods_settings");move(settings,controlsPage,0);
        if(settings!=null)call(settings,"O0",CharSequence.class,"佩戴检测");
        Object experience=category(listening,"cat_colorpods_listening","智能聆听",100);
        move(find("pref_airpods_conversation_aware"),experience,-1);
        controlSwitch(experience,0x26);controlSwitch(experience,0x35);
        Object noise=category(listening,"cat_colorpods_noise","降噪与通透",200);
        controlSwitch(noise,0x1b);controlSwitch(noise,0x34);
        String[] percent=new String[101],strength=new String[101];for(int i=0;i<=100;i++){percent[i]=i+"%";strength[i]=Integer.toString(100-i);}
        Object adaptive=list(noise,"colorpods_control_2e","自适应强度",percent,strength);controls.put(0x2e,adaptive);controlChange(adaptive,0x2e,v->new byte[]{(byte)Integer.parseInt(v.toString())});
        Object transPage=page(noise,"transparency","通透模式定制","自定义通透、声音放大与对话增强");
        Object hearingEntry=category(listening,"cat_colorpods_hearing_entry","听力健康",300);
        Object hearingPage=page(hearingEntry,"hearing","听力辅助","大音量降低与助听器");
        Object actions=category(controlsPage,"cat_colorpods_actions","按压与长按",100);
        controlList(actions,0x17,new String[]{"默认","较慢","最慢"},new int[]{0,1,2});
        controlList(actions,0x18,new String[]{"默认","较短","最短"},new int[]{0,1,2});
        Object volume=category(controlsPage,"cat_colorpods_volume","音量滑动",200);
        controlSwitch(volume,0x25);
        controlList(volume,0x23,new String[]{"默认","较长","最长"},new int[]{0,1,2});
        Object trans=category(transPage,"cat_colorpods_transparency","通透模式定制",0);
        transRead=add(trans,"COUIJumpPreference","colorpods_read_transparency","读取耳机设置");click(transRead,()->read(AttCodec.TRANSPARENCY));
        attSwitch(trans,AttCodec.TRANSPARENCY,"enabled","自定义通透");
        tuningRows(trans,AttCodec.TRANSPARENCY);
        Object hearing=category(hearingPage,"cat_colorpods_hearing","听力辅助",0);
        hearingRead=add(hearing,"COUIJumpPreference","colorpods_read_hearing","读取耳机设置");click(hearingRead,()->readHearing());
        attSwitch(hearing,AttCodec.LOUD_SOUND,"enabled","降低大音量");
        Object hearingToggle=add(hearing,"COUISwitchPreference","colorpods_control_2c","助听器");controls.put(0x2c,hearingToggle);switchRows.put(hearingToggle,true);summary(hearingToggle,"开启时停用自定义通透");
        controlChange(hearingToggle,0x2c,value->new byte[]{1,(byte)((Boolean)value?1:2)});
        tuningRows(hearing,AttCodec.HEARING_AID);
        Object routing=category(routingPage,"cat_colorpods_routing","设备切换",0);
        multiRow=add(routing,"COUISwitchPreference","colorpods_multipoint","多设备协同");
        change(multiRow,value->{Bundle b=request();b.putBoolean("enabled",(Boolean)value);operation(BridgeClient.MULTIPOINT,b,r->{snapshot=r;});});
        autoMediaRow=add(routing,"COUISwitchPreference","colorpods_auto_media","手机播放时自动切回");
        change(autoMediaRow,value->{Bundle b=request();b.putBoolean("autoMedia",(Boolean)value);operation(BridgeClient.MULTIPOINT,b,r->{snapshot=r;});});
        routeStatus=add(routing,"COUIPreference","colorpods_route_status","设备与音频来源");call(routeStatus,"K0",boolean.class,false);
        routeRow=list(routing,"colorpods_route","将音频切回此手机",new String[0],new String[0]);
        change(routeRow,value->route(value.toString(),false));
        reverseRow=add(routing,"COUIJumpPreference","colorpods_route_reverse","撤销其他设备的切换");click(reverseRow,()->route(snapshot.getString("audioSource"),true));
        Object connection=category(morePage,"cat_colorpods_connection","通话与连接",0);
        controlList(connection,0x01,new String[]{"自动","始终右侧","始终左侧"},new int[]{0,1,2});controlSwitch(connection,0x20);
        refreshControlsRow=add(connection,"COUIJumpPreference","colorpods_refresh_controls","刷新耳机状态");
        click(refreshControlsRow,()->{Bundle b=request();b.putBoolean("refreshControls",true);operation(BridgeClient.STATUS,b,r->{snapshot=r;Toast.makeText(activity,"已请求更新耳机状态",Toast.LENGTH_SHORT).show();});});
        Object advancedEntry=category(morePage,"cat_colorpods_advanced_entry","高级",100);
        Object advancedPage=page(advancedEntry,"advanced","兼容性与诊断","Apple 设备身份、连接状态与恢复");
        Object identity=category(advancedPage,"cat_colorpods_identity","设备兼容性",0);
        vendorRow=add(identity,"COUISwitchPreference","colorpods_vendor","以 Apple 设备身份连接");enabled(vendorRow,false);
        change(vendorRow,value->{Bundle b=request();b.putBoolean("enabled",(Boolean)value);operation(BridgeClient.VENDOR,b,r->{snapshot=r;lastError="身份设置已保存，请关闭再开启蓝牙后重新连接耳机";});});
        Object diagnostics=category(advancedPage,"cat_colorpods_diagnostics","连接诊断",100);
        statusRow=add(diagnostics,"COUIPreference","colorpods_status","连接与设置状态");call(statusRow,"K0",boolean.class,false);
        reconnectRow=add(diagnostics,"COUIJumpPreference","colorpods_route_reconnect","恢复耳机控制连接");click(reconnectRow,()->{Bundle b=request();b.putBoolean("connectOnly",true);operation(BridgeClient.ROUTE,b,r->{snapshot=r;});});
        update();module.info("All settings inserted as host COUI preferences");
    }
    private void controlList(Object parent,int cmd,String[] names,int[] values) throws Exception {
        String[] strings=new String[values.length];for(int i=0;i<values.length;i++)strings[i]=Integer.toString(values[i]);
        Object row=list(parent,"colorpods_control_"+Integer.toHexString(cmd),Protocol.COMMANDS.get(cmd),names,strings);controls.put(cmd,row);
        controlChange(row,cmd,v->new byte[]{(byte)Integer.parseInt(v.toString())});
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
        String[] names=new String[21],values=new String[21];for(int i=0;i<=20;i++){names[i]=i*5+"%";values[i]=Float.toString(i/20f);}
        for(String[] f:fields){Object row=list(parent,"colorpods_att_"+handle+"_"+f[0],f[1],names,values);attRows.put(handle+":"+f[0],row);change(row,v->patch(handle,f[0],Float.parseFloat(v.toString())));}
        attSwitch(parent,handle,"boost","对话增强");
    }
    private String address() throws Exception {
        Object vm=Reflect.call(activity,"getViewModel",new Class[0]);BluetoothDevice d=(BluetoothDevice)Reflect.call(vm,"getBluetoothDevice",new Class[0]);return d==null?null:d.getAddress();
    }
    private Bundle request(){try{return BridgeClient.forDevice(address());}catch(Exception e){throw new IllegalStateException(e);}}
    void start(){active=true;main.removeCallbacksAndMessages(null);connection.start();refresh();}
    void stop(){active=false;connection.stop();main.removeCallbacksAndMessages(null);}
    void resume(){if(!active)start();main.post(this::connectionChanged);}
    private void connectionChanged(){
        if(!alive())return;connection.reconcile();
        try{update();}catch(Exception e){module.error("COUI connection update failed",e);}
        main.removeCallbacks(refreshTask);main.postDelayed(refreshTask,100);
    }
    private boolean alive(){return active&&!activity.isDestroyed()&&!activity.isFinishing();}
    private void onConfirmationDeadline(){if(!alive())return;try{update();}catch(Exception e){module.error("COUI confirmation timeout update failed",e);}}
    private void refresh(){
        if(!alive())return;connection.reconcile();if(busy){main.postDelayed(refreshTask,2000);return;}
        Bundle b=request();IO.execute(()->{Bundle result=null;String error=null;try{result=bridge.request(BridgeClient.STATUS,b);}catch(Exception e){error=e.getMessage();}
            Bundle r=result;String message=error;main.post(()->{if(!alive())return;if(r!=null){snapshot=r;connectionError=null;}else connectionError=message;try{update();}catch(Exception e){module.error("COUI update failed",e);}main.removeCallbacks(refreshTask);main.postDelayed(refreshTask,2500);});});
    }
    private void update() throws Exception {
        int link=connection.state();
        boolean connected=link==ConnectionState.CONNECTED||(link==ConnectionState.UNKNOWN&&snapshot.getBoolean("connected"));
        boolean ready=link!=ConnectionState.DISCONNECTED&&snapshot.getBoolean("controlReady");long[] identity=snapshot.getLongArray("nativeStatus");
        String text=ready?"耳机已连接，原生控制通道就绪":connected?"耳机已连接，等待原生控制通道":"等待耳机连接";
        if(!ready&&snapshot.getBoolean("remoteAudioOwner"))text="音频已交给其他设备，控制连接待恢复";
        if(identity!=null)text+="；"+(identity[1]==0?"身份 Hook 未装载":identity[3]>0?"Apple 身份已提交":"身份 Hook 已装载，等待蓝牙重启");
        if(lastError!=null)text+="\n"+lastError;
        if(connectionError!=null)text+="\n"+connectionError;
        StringBuilder diagnosticText=new StringBuilder(text);
        enabled(vendorRow,identity!=null&&!busy);if(identity!=null)checked(vendorRow,identity[0]!=0);
        summary(vendorRow,"修改后需关闭再开启蓝牙");
        Bundle c=snapshot.getBundle("controls");if(c==null)c=Bundle.EMPTY;
        Bundle pending=snapshot.getBundle("pendingControls");if(pending==null)pending=Bundle.EMPTY;
        Bundle updates=snapshot.getBundle("controlUpdates");if(updates==null)updates=Bundle.EMPTY;
        long now=SystemClock.elapsedRealtime(),nextDeadline=Long.MAX_VALUE;
        main.removeCallbacks(confirmationTimeout);
        for(var entry:controls.entrySet()){
            int command=entry.getKey();Object row=entry.getValue();String key=Integer.toHexString(command);
            Bundle update=updates.getBundle(key);int state=update==null?0:ControlWrite.waitingState(update.getInt("state"),update.getLong("deadline"),now);
            byte[] reported=c.getByteArray(key),expected=update==null?null:update.getByteArray("expected");
            byte[] value=ControlWrite.displayedValue(reported,expected,state);
            // Compatibility for a host still running with the previous backend.
            if(update==null&&pending.containsKey(key))value=pending.getByteArray(key);
            if(sendingControls.containsKey(command))value=sendingControls.get(command);
            boolean known=value!=null&&value.length>0;
            enabled(row,ready&&!busy&&!sendingControls.containsKey(command)&&(!switchRows.containsKey(row)||known));
            if(switchRows.containsKey(row)){if(known)checked(row,command==0x2c?value.length>1&&value[0]==1&&value[1]==1:value[0]==1);if(command!=0x2c)summary(row,"");}
            else{call(row,"setValue",String.class,known?Integer.toString(value[0]&255):"");summary(row,known?controlLabel(row,command,value):"");}
            if(state==ControlWrite.WAITING)nextDeadline=Math.min(nextDeadline,update.getLong("deadline"));
            if(update!=null){
                String result=switch(state){case ControlWrite.CONFIRMED->"已确认";case ControlWrite.DIFFERENT->"回报不一致";case ControlWrite.UNCONFIRMED->"未收到确认";default->"等待回报";};
                diagnosticText.append('\n').append(Protocol.COMMANDS.get(command)).append("：").append(controlLabel(row,command,expected)).append("（").append(result).append('）');
                if(state==ControlWrite.DIFFERENT)diagnosticText.append("；耳机回报：").append(controlLabel(row,command,reported));
            }
        }
        summary(statusRow,diagnosticText.toString());
        if(nextDeadline!=Long.MAX_VALUE&&active)main.postDelayed(confirmationTimeout,Math.max(1,nextDeadline-SystemClock.elapsedRealtime()));
        enabled(refreshControlsRow,ready&&!busy);
        boolean attReady=ready&&identity!=null&&identity[0]!=0&&identity[1]!=0&&identity[3]>0;
        enabled(transRead,attReady&&!busy);enabled(hearingRead,attReady&&!busy);
        call(transRead,"O0",CharSequence.class,attValues.containsKey(AttCodec.TRANSPARENCY)&&attReady?"刷新耳机设置":"读取耳机设置");
        call(hearingRead,"O0",CharSequence.class,attValues.containsKey(AttCodec.HEARING_AID)&&attReady?"刷新耳机设置":"读取耳机设置");
        if(!ready)attValues.clear();
        for(var entry:attRows.entrySet()){
            String[] parts=entry.getKey().split(":");int handle=Integer.parseInt(parts[0]);String field=parts[1];Object row=entry.getValue();byte[] data=attValues.get(handle);
            boolean supported=data!=null&&(!field.equals("voice")||data.length>=104);enabled(row,attReady&&!busy&&supported);
            if(!supported){summary(row,"");continue;}
            float value=AttCodec.value(handle,data,field);
            if(switchRows.containsKey(row)){checked(row,value>0.5f);summary(row,"");}
            else{call(row,"setValue",String.class,Float.toString(value));summary(row,Math.round(value*100)+"%");}
        }
        boolean multi=snapshot.getBoolean("multipoint");checked(multiRow,multi);enabled(multiRow,identity!=null&&!busy);
        checked(autoMediaRow,snapshot.getBoolean("autoMedia",true));enabled(autoMediaRow,multi&&identity!=null&&!busy);
        enabled(reconnectRow,multi&&identity!=null&&identity[0]!=0&&identity[1]!=0&&identity[3]>0&&!ready&&!busy);
        String[] p=snapshot.getStringArray("peers");String self=snapshot.getString("selfAddress");ArrayList<String> targets=new ArrayList<>(),names=new ArrayList<>();Bundle types=snapshot.getBundle("peerTypes");if(types==null)types=Bundle.EMPTY;
        if(p!=null)for(String peer:p)if(!peer.equals(self)){targets.add(peer);names.add(types.getString(peer,"另一台设备"));}
        entries(routeRow,names.toArray(new String[0]),targets.toArray(new String[0]));enabled(routeRow,multi&&attReady&&!busy&&!targets.isEmpty());
        String source=snapshot.getString("audioSource");boolean otherSource=source!=null&&!source.equals(self)&&targets.contains(source)&&snapshot.getInt("audioSourceType")!=0;
        String routeText=!connected&&!ready?"耳机未连接":targets.isEmpty()?"已连接此手机":"已发现：此手机、"+String.join("、",names);
        routeText+="；音频："+(snapshot.getInt("audioSourceType")==0?"空闲":source!=null&&source.equals(self)?"此手机":types.getString(source,"另一台设备"));
        if(snapshot.getString("routeNotice")!=null)routeText+="\n"+snapshot.getString("routeNotice");summary(routeStatus,routeText);enabled(reverseRow,multi&&attReady&&!busy&&otherSource);
        summary(pageEntries.get("routing"),!multi?"未开启 · 在设备之间切换耳机音频":targets.isEmpty()?"已开启 · 尚未发现其他设备":snapshot.getInt("audioSourceType")==0?"已开启 · 当前无音频播放":"音频来源："+(source!=null&&source.equals(self)?"此手机":types.getString(source,"另一台设备")));
    }
    private String controlLabel(Object row,int command,byte[] value) throws Exception {
        if(value==null||value.length==0)return "未知";
        if(switchRows.containsKey(row))return (command==0x2c?value.length>1&&value[0]==1&&value[1]==1:value[0]==1)?"开启":"关闭";
        String selected=Integer.toString(value[0]&255);Object[] labels=(Object[])Reflect.field(row,"X"),values=(Object[])Reflect.field(row,"Y");
        for(int i=0;i<values.length;i++)if(selected.equals(values[i].toString()))return labels[i].toString();
        return selected;
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
    private void read(int handle){Bundle b=request();b.putInt("handle",handle);operation(BridgeClient.ATT_READ,b,r->{attValues.put(handle,r.getByteArray("value"));});}
    private void readHearing(){Bundle b=request();b.putInt("handle",AttCodec.HEARING_AID);operation(BridgeClient.ATT_READ,b,r->{attValues.put(AttCodec.HEARING_AID,r.getByteArray("value"));main.post(()->read(AttCodec.LOUD_SOUND));});}
    private void patch(int handle,String field,float value){Bundle b=request();b.putInt("handle",handle);b.putString("field",field);b.putFloat("value",value);operation(BridgeClient.ATT_PATCH,b,r->{attValues.put(handle,r.getByteArray("value"));});}
    private void operation(int operation,Bundle b,java.util.function.Consumer<Bundle> callback){
        if(busy)return;busy=true;lastError=null;try{update();}catch(Exception ignored){}
        IO.execute(()->{Bundle response=null;String error=null;try{response=bridge.request(operation,b);}catch(Exception e){error=e.getMessage();}
            Bundle result=response;String message=error;main.post(()->{busy=false;if(!alive())return;lastError=message;if(message!=null)Toast.makeText(activity,"设置失败，请重试",Toast.LENGTH_SHORT).show();if(result!=null)callback.accept(result);try{update();}catch(Exception e){module.error("COUI operation update failed",e);}});});
    }
}
