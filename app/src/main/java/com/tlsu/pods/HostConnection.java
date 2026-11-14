// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothA2dp;
import android.bluetooth.BluetoothHeadset;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.view.View;
import java.util.HashMap;
import java.util.Map;

/** Reconcile the host's cached connection state without connecting or claiming audio. */
final class HostConnection {
    private final Activity activity;
    private final ColorPodsModule module;
    private final Runnable changed;
    private final BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
    private final Map<Integer,BluetoothProfile> profiles=new HashMap<>();
    private boolean active,registered;
    private int generation;
    private BluetoothProfile.ServiceListener listener(int session){return new BluetoothProfile.ServiceListener(){
        @Override public void onServiceConnected(int id,BluetoothProfile proxy){
            if(!active||session!=generation){adapter.closeProfileProxy(id,proxy);return;}
            BluetoothProfile previous=profiles.put(id,proxy);
            if(previous!=null&&previous!=proxy)adapter.closeProfileProxy(id,previous);
            changed.run();
        }
        @Override public void onServiceDisconnected(int id){if(active&&session==generation){profiles.remove(id);changed.run();}}
    };}
    private final BroadcastReceiver receiver=new BroadcastReceiver(){
        @Override public void onReceive(Context context,Intent intent){
            if(!active)return;
            try{
                BluetoothDevice current=device();
                BluetoothDevice event=intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE,BluetoothDevice.class);
                if(!BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())&&(current==null||!current.equals(event)))return;
                changed.run();
            }catch(Exception e){module.error("Host connection event failed",e);}
        }
    };
    HostConnection(Activity activity,ColorPodsModule module,Runnable changed){this.activity=activity;this.module=module;this.changed=changed;}
    private Object viewModel()throws Exception{return Reflect.call(activity,"getViewModel",new Class[0]);}
    private BluetoothDevice device()throws Exception{return (BluetoothDevice)Reflect.call(viewModel(),"getBluetoothDevice",new Class[0]);}
    void start(){
        if(active||adapter==null)return;active=true;int session=++generation;
        IntentFilter filter=new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED);filter.addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED);
        try{activity.registerReceiver(receiver,filter,Context.RECEIVER_EXPORTED);registered=true;}
        catch(RuntimeException e){module.error("Host connection observer unavailable",e);}
        BluetoothProfile.ServiceListener listener=listener(session);
        for(int id:new int[]{BluetoothProfile.A2DP,BluetoothProfile.HEADSET})try{adapter.getProfileProxy(activity,listener,id);}
        catch(RuntimeException e){module.error("Host audio profile observer unavailable",e);}
        reconcile();
    }
    void stop(){
        if(!active)return;active=false;generation++;if(registered){activity.unregisterReceiver(receiver);registered=false;}
        for(var entry:profiles.entrySet())adapter.closeProfileProxy(entry.getKey(),entry.getValue());profiles.clear();
    }
    private Integer profileState(int id,BluetoothDevice device){
        BluetoothProfile proxy=profiles.get(id);if(proxy==null)return null;
        try{return proxy.getConnectionState(device);}catch(RuntimeException e){return null;}
    }
    private int state(BluetoothDevice device){
        Boolean acl=null;try{acl=(Boolean)Reflect.call(device,"isConnected",new Class[0]);}catch(ReflectiveOperationException ignored){}
        return ConnectionState.resolve(adapter.isEnabled(),acl,profileState(BluetoothProfile.A2DP,device),profileState(BluetoothProfile.HEADSET,device));
    }
    int state(){
        if(!active)return ConnectionState.UNKNOWN;
        try{BluetoothDevice device=device();return device==null?ConnectionState.UNKNOWN:state(device);}
        catch(Exception e){return ConnectionState.UNKNOWN;}
    }
    void reconcile(){
        if(!active)return;
        try{
            Object vm=viewModel();BluetoothDevice device=(BluetoothDevice)Reflect.call(vm,"getBluetoothDevice",new Class[0]);if(device==null)return;
            int observed=state(device);
            String original=(String)Reflect.call(vm,"getConnectState",new Class[0]),resolved=ConnectionState.reconcile(original,observed);
            if(!java.util.Objects.equals(original,resolved)){
                // Use the native transition to update preferences and battery
                // observation together, for this device only.
                Object manager=Reflect.field(vm,"bluetoothManager");
                Reflect.call(manager,"i0",new Class[]{String.class,BluetoothDevice.class},resolved,device);
                module.info("Reconciled host connection: "+original+" -> "+resolved);
            }
            // Initial profile callbacks can run before the activity registers
            // its native listener. An already-correct cache then emits nothing.
            View hint=(View)Reflect.call(activity,"getDeviceNotConnectedHint",new Class[0]);
            View strip=(View)Reflect.call(activity,"getFlConnectLayoutNew",new Class[0]);
            if(ConnectionState.needsRender(observed,resolved,hint.getVisibility()==View.VISIBLE,strip.getVisibility()==View.VISIBLE)){
                Reflect.call(activity,"updateBluetoothOperation",new Class[]{String.class},resolved);
                Reflect.call(activity,"updateConnectStateNew",new Class[0]);
                module.info("Refreshed stale host connection banner: "+resolved);
            }
        }catch(Exception e){module.error("Host connection reconciliation failed",e);}
    }
}
