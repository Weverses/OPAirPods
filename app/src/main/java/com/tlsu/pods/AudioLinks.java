// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Reconnect/select audio without modifying the user's connection policies.
 * Remote handoff never disconnects these profiles in the native integration. */
final class AudioLinks {
    private AudioLinks() {}
    /** One nonblocking media-route step. Recheck cancellation inside the profile
     * callback, where the actual side effect happens, not just at submission. */
    static CompletableFuture<Boolean> prepare(Context context,BluetoothDevice device,java.util.function.BooleanSupplier valid){
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        CompletableFuture<Boolean> result=new CompletableFuture<>();
        boolean accepted=adapter.getProfileProxy(context,new BluetoothProfile.ServiceListener(){
            @Override public void onServiceConnected(int id,BluetoothProfile proxy){
                try{
                    if(result.isDone()||!valid.getAsBoolean())throw new IOException("音频切换已取消");
                    int state=proxy.getConnectionState(device);
                    if(state==BluetoothProfile.STATE_DISCONNECTED){
                        if(!valid.getAsBoolean())throw new IOException("音频切换已取消");
                        if(Boolean.FALSE.equals(Reflect.call(proxy,"connect",new Class[]{BluetoothDevice.class},device)))throw new IOException("系统拒绝接通音频");
                    }
                    if(state==BluetoothProfile.STATE_CONNECTED){
                        if(!valid.getAsBoolean())throw new IOException("音频切换已取消");
                        if(!device.equals(Reflect.call(proxy,"getActiveDevice",new Class[0])))
                            Reflect.call(proxy,"setActiveDevice",new Class[]{BluetoothDevice.class},device);
                        result.complete(device.equals(Reflect.call(proxy,"getActiveDevice",new Class[0])));
                    }else result.complete(false);
                }catch(Exception e){result.completeExceptionally(e);}
                finally{adapter.closeProfileProxy(id,proxy);}
            }
            @Override public void onServiceDisconnected(int id){result.completeExceptionally(new IOException("系统音频服务已断开"));}
        },BluetoothProfile.A2DP);
        if(!accepted)result.completeExceptionally(new IOException("系统音频服务不可用"));
        return result.orTimeout(3,TimeUnit.SECONDS);
    }
    static void connect(Context context,BluetoothDevice device) throws Exception {
        access(context,device,BluetoothProfile.A2DP,true);
        access(context,device,BluetoothProfile.HEADSET,true);
    }
    static boolean ready(Context context,BluetoothDevice device) throws Exception {
        return access(context,device,BluetoothProfile.A2DP,null)==BluetoothProfile.STATE_CONNECTED;
    }
    private static int access(Context context,BluetoothDevice device,int profile,Boolean connect) throws Exception {
        BluetoothAdapter adapter=BluetoothAdapter.getDefaultAdapter();
        CompletableFuture<Integer> result=new CompletableFuture<>();
        boolean accepted=adapter.getProfileProxy(context,new BluetoothProfile.ServiceListener(){
            @Override public void onServiceConnected(int id,BluetoothProfile proxy){
                try {
                    if(result.isCancelled())return;
                    int state=proxy.getConnectionState(device);
                    if(connect!=null){
                        if(connect&&state==BluetoothProfile.STATE_DISCONNECTED){
                            if(Boolean.FALSE.equals(Reflect.call(proxy,"connect",new Class[]{BluetoothDevice.class},device)))throw new IOException("系统拒绝接通音频");
                        }
                        if(connect&&id==BluetoothProfile.A2DP&&state==BluetoothProfile.STATE_CONNECTED)
                            Reflect.call(proxy,"setActiveDevice",new Class[]{BluetoothDevice.class},device);
                    }
                    int current=proxy.getConnectionState(device);
                    if(connect==null&&id==BluetoothProfile.A2DP&&current==BluetoothProfile.STATE_CONNECTED&&!device.equals(Reflect.call(proxy,"getActiveDevice",new Class[0])))current=BluetoothProfile.STATE_CONNECTING;
                    result.complete(current);
                }catch(Exception e){result.completeExceptionally(e);}
                finally{adapter.closeProfileProxy(id,proxy);}
            }
            @Override public void onServiceDisconnected(int id){result.completeExceptionally(new IOException("系统音频服务已断开"));}
        },profile);
        if(!accepted)throw new IOException("系统音频服务不可用");
        try{return result.get(3,TimeUnit.SECONDS);}
        catch(java.util.concurrent.TimeoutException e){result.cancel(false);throw new IOException("系统音频服务响应超时",e);}
    }
}
