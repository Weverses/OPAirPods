// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.SystemClock;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class BluetoothBackend extends Binder {
    private final ColorPodsModule module;
    private volatile Context context;
    private volatile Object manager;
    volatile boolean nativeLoaded;
    private final Object controlsLock = new Object(), commandLock = new Object();
    private record Control(byte[] value, long received) {}
    private final Map<String, Map<Integer, Control>> states = new ConcurrentHashMap<>();
    private final Map<String, Map<Integer, ControlWrite>> controlWrites = new ConcurrentHashMap<>();
    private final Map<String,ControlWrite> controlRefreshes=new ConcurrentHashMap<>();
    private final Map<String, AttChannel> channels = new ConcurrentHashMap<>();
    private final Map<String, java.util.List<String>> peers = new ConcurrentHashMap<>();
    private final Map<String, java.util.List<Routing.Peer>> peerRecords=new ConcurrentHashMap<>();
    private final Map<String, Long> sourceSequences=new ConcurrentHashMap<>();
    private final Map<String, java.util.List<android.media.session.MediaController>> pausedSessions=new java.util.HashMap<>();
    private final java.util.Set<BluetoothSocket> validated=ConcurrentHashMap.newKeySet();
    private final Map<String, String> audioSources = new ConcurrentHashMap<>();
    private final Map<String, Integer> sourceTypes = new ConcurrentHashMap<>();
    private final Map<String, Map<String,String>> peerTypes = new ConcurrentHashMap<>();
    private final Map<String,String> routeNotices = new ConcurrentHashMap<>();
    private final java.util.Set<String> announced = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ExecutorService routingWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ColorPodsRouting");t.setDaemon(true);return t;});
    private final java.util.Set<String> requested = ConcurrentHashMap.newKeySet();
    private final HandoffState handoff=new HandoffState();
    private final Map<String,BluetoothSocket> sessions=new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService recoveryWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ColorPodsRecovery");t.setDaemon(true);return t;});
    private final Map<String,Boolean> mediaReported=new ConcurrentHashMap<>();
    private final Map<String,HandoffState.Ticket> validationClaims=new ConcurrentHashMap<>();
    private final Map<String,Long> openedAt=new ConcurrentHashMap<>();
    private volatile BluetoothDevice lastDevice;
    private MediaStartMonitor playbackMonitor;
    private long sequence;

    BluetoothBackend(ColorPodsModule module) { this.module = module; attachInterface(null, BridgeClient.DESCRIPTOR); }
    synchronized void attach(Context c) {
        if (context != null) return;
        context = c.getApplicationContext() == null ? c : c.getApplicationContext();
        if (nativeLoaded) NativeBridge.setEnabled(context.getSharedPreferences("colorpods", 0).getBoolean("vendor", true));
        playbackMonitor=new MediaStartMonitor(context,module.getModuleApplicationInfo().uid,this::onLocalPlaybackStarted,this::publishMediaState,module::info);
    }
    private final java.util.Set<String> automaticPending=ConcurrentHashMap.newKeySet();
    private void publishMediaState(){
        BluetoothDevice d=lastDevice;if(d==null||!multiActive())return;
        routingWorker.execute(()->{
            try{
                if(!multiActive()||handoff.claiming(d.getAddress()))return;
                boolean playing=MediaPlayback.playing(context)&&!handoff.remoteOwns(d.getAddress());
                if(java.util.Objects.equals(mediaReported.get(d.getAddress()),playing))return;
                String self=localAddress();boolean sent=false;
                for(String peer:peers.getOrDefault(d.getAddress(),java.util.List.of()))if(!peer.equals(self)){sendFrames(d,Routing.streamingInfo(self,peer,playing));sent=true;}
                if(sent)mediaReported.put(d.getAddress(),playing);
            }catch(Exception e){routeFailure(d,e);}
        });
    }
    private boolean validating(String address){HandoffState.Ticket ticket=validationClaims.get(address);return ticket!=null&&handoff.claiming(ticket);}
    private boolean needsReclaim(BluetoothDevice d){
        if(handoff.claiming(d.getAddress()))return validating(d.getAddress());
        if(handoff.remoteOwns(d.getAddress()))return true;
        try{String self=localAddress();if(self.equals(audioSources.get(d.getAddress()))&&sourceTypes.getOrDefault(d.getAddress(),0)!=0){socket(d);return false;}
            return peers.getOrDefault(d.getAddress(),java.util.List.of()).stream().anyMatch(peer->!peer.equals(self));
        }catch(Exception e){return handoff.remoteOwns(d.getAddress());}
    }
    private void onLocalPlaybackStarted(){
        BluetoothDevice d=lastDevice;boolean enabled=multiActive()&&context.getSharedPreferences("colorpods",0).getBoolean("autoMedia",true);
        module.info("Playback intent: target="+(d!=null)+" enabled="+enabled+(d==null?"":" remote="+handoff.remoteOwns(d.getAddress())+" claiming="+handoff.claiming(d.getAddress())+" validating="+validating(d.getAddress())));
        if(d!=null&&enabled&&needsReclaim(d))automaticReclaim(d);
    }
    private void automaticReclaim(BluetoothDevice d){
        if(!automaticPending.add(d.getAddress()))return;
        routingWorker.execute(()->{
            try{
                if(!multiActive()||!context.getSharedPreferences("colorpods",0).getBoolean("autoMedia",true)||!needsReclaim(d))return;
                module.info("Automatic reclaim queued from playback intent");
                var paused=MediaPlayback.pause(context);if(!paused.isEmpty())pausedSessions.put(d.getAddress(),paused);
                MediaPlayback.awaitPaused(paused,800);
                module.info("Playback pause acknowledged: recorded="+paused.size()+" resumable="+MediaPlayback.resumable(paused));
                Bundle intent=BridgeClient.forDevice(d.getAddress());intent.putBoolean("automatic",true);
                reclaim(d,intent);module.info("Android playback start reclaimed AirPods");
            }catch(Exception e){routeFailure(d,e);}
            finally{automaticPending.remove(d.getAddress());}
        });
    }
    void manager(Object value) throws ReflectiveOperationException {
        manager = value;
        attach((Context) Reflect.field(value, "mContext"));
        module.info("Captured OEM AirPodsManager");
    }
    void packet(byte[] bytes, int length, BluetoothDevice device) {
        if (device == null || !Routing.header(bytes, length)) return;
        if(!sessions.containsKey(device.getAddress()))try{sessionOpened(device,socket(device));}catch(Exception ignored){}
        if (bytes[4] == 0x2e) {
            try { peers.put(device.getAddress(), Routing.peers(bytes, length));var records=Routing.peerRecords(bytes,length);var previous=peerRecords.put(device.getAddress(),records);
                if(!records.equals(previous))try{String self=localAddress();module.info("TiPi peers: "+records.stream().map(p->(self.equals(p.address())?"self":"peer")+"/role="+Integer.toHexString(p.role())+"/state="+Integer.toHexString(p.state())).collect(java.util.stream.Collectors.toList()));}catch(IOException ignored){}
                announce(device);
                BluetoothSocket active=sessions.get(device.getAddress());if(active!=null&&!validated.contains(active))routingWorker.execute(()->validateSession(device,active)); } catch (IllegalArgumentException ignored) {}
        }
        if (bytes[4] == 0x0e && length >= 13) {
            synchronized(controlsLock){String source=Routing.mac(bytes,6,true);String previous=audioSources.put(device.getAddress(),source);Integer type=sourceTypes.put(device.getAddress(),bytes[12]&255);sourceSequences.put(device.getAddress(),++sequence);
                if(!source.equals(previous)||!java.util.Objects.equals(type,bytes[12]&255))try{module.info("Audio source: local="+localAddress().equals(source)+" type="+(bytes[12]&255));}catch(IOException ignored){}}

            if(bytes[12]!=0&&multiActive())try{
                if(localAddress().equals(audioSources.get(device.getAddress()))){if(!handoff.claiming(device.getAddress()))handoff.local(device.getAddress());}
                else yieldAudio(device,handoff.remote(device.getAddress()),"音频来源已切换到另一台设备");
            }catch(IOException e){routeFailure(device,e);}
            synchronized (controlsLock) { controlsLock.notifyAll(); }
        }
        if(bytes[4]==0x11)try{
            Routing.Message message=Routing.response(bytes,length);
            if(message.kind()!=null)peerTypes.computeIfAbsent(device.getAddress(),k->new ConcurrentHashMap<>()).put(message.sender(),message.kind());
            if(multiActive()&&peers.getOrDefault(device.getAddress(),java.util.List.of()).contains(message.sender())&&message.relinquish())
                yieldAudio(device,handoff.remote(device.getAddress()),message.reversed()?"另一台设备已撤销切换，可手动切回手机":"音频已交给另一台设备，控制连接保留");
        }catch(IllegalArgumentException ignored){}
        if (!Protocol.isControl(bytes, length)) return;
        int command = bytes[6] & 255;
        if (!Protocol.COMMANDS.containsKey(command)&&command!=6) return;
        if(command==0x17)module.info("AACP press-speed state received: "+Protocol.hex(bytes,7,11));
        synchronized (controlsLock) {
            states.computeIfAbsent(device.getAddress(), k -> new ConcurrentHashMap<>())
                .put(command, new Control(Arrays.copyOfRange(bytes, 7, 11), ++sequence));
            controlsLock.notifyAll();
        }
        if(command==6){
            int value=bytes[7]&255;module.info("AACP ownership received: "+value);
            HandoffState.Ticket ticket=handoff.ownership(device.getAddress(),value);
            if(multiActive())yieldAudio(device,ticket,"耳机所有权已交给另一台设备");
        }
    }
    synchronized void sessionOpened(BluetoothDevice device,BluetoothSocket socket){
        if(sessions.put(device.getAddress(),socket)==socket)return;lastDevice=device;openedAt.put(device.getAddress(),SystemClock.elapsedRealtime());module.info("OEM AACP session opened");
        requestState(device,1000);
        new Handler(Looper.getMainLooper()).postDelayed(()->routingWorker.execute(()->validateSession(device,socket)),5000);
    }
    private boolean peerTakingOver(String address,String self){for(Routing.Peer peer:peerRecords.getOrDefault(address,java.util.List.of()))if(!peer.address().equals(self)&&peer.takingOver())return true;return false;}
    private void validateSession(BluetoothDevice device,BluetoothSocket expected){
        String address=device.getAddress();
        try{
            if(!multiActive()||sessions.get(address)!=expected||validated.contains(expected)||handoff.claiming(address)||SystemClock.elapsedRealtime()<openedAt.getOrDefault(address,0L)+5000)return;
            String self=localAddress();if(peerTakingOver(address,self)){module.info("Deferred initial validation: peer is taking over");return;}
            validated.add(expected);HandoffState.Ticket validation=handoff.beginClaim(address);validationClaims.put(address,validation);sendFrames(device,Routing.ownership(true));requestState(device,500);
            module.info("Initial AACP validation claim sent");
            new Handler(Looper.getMainLooper()).postDelayed(()->routingWorker.execute(()->{
                try{
                    if(sessions.get(address)!=expected||!multiActive()||!handoff.claiming(validation)){validationClaims.remove(address,validation);return;}
                    validationClaims.remove(address,validation);
                    boolean localAudio=self.equals(audioSources.get(address))&&sourceTypes.getOrDefault(address,0)!=0;
                    module.info("Validation settled: localSource="+localAudio+" localPlayback="+MediaPlayback.playing(context)+" peerCount="+peers.getOrDefault(address,java.util.List.of()).size());
                    if(!localAudio&&peers.getOrDefault(address,java.util.List.of()).stream().anyMatch(p->!p.equals(self))){
                        sendFrames(device,Routing.ownership(false));handoff.finishClaim(address,false);module.info("Validation complete; idle ownership returned to peer");
                        if(MediaPlayback.playing(context)&&context.getSharedPreferences("colorpods",0).getBoolean("autoMedia",true))automaticReclaim(device);
                    }else handoff.finishClaim(address,true);
                }catch(Exception e){handoff.finishClaim(address,false);routeFailure(device,e);}
            }),3000);
        }catch(Exception e){handoff.finishClaim(address,false);routeFailure(device,e);}
    }
    synchronized void sessionClosed(BluetoothDevice device,BluetoothSocket socket){
        if(!sessions.remove(device.getAddress(),socket))return;validated.remove(socket);
        module.info("OEM AACP reader ended; clearing stale device state");disconnected(device);recoverControl(device);
    }
    boolean suppressAutomaticPlay(){if(!multiActive())return false;for(String address:sessions.keySet())if(handoff.remoteOwns(address))return true;return false;}
    private void yieldAudio(BluetoothDevice device,HandoffState.Ticket ticket,String notice){
        if(ticket==null||!multiActive())return;
        routingWorker.execute(()->{
            try{
                if(!multiActive()||!handoff.current(ticket))return;
                java.util.List<android.media.session.MediaController> paused=MediaPlayback.pause(context);
                if(!paused.isEmpty())pausedSessions.put(device.getAddress(),paused);
                module.info("Remote handoff: dispatched Android media pause");
                sendFrames(device,Routing.ownership(false));
                String self=localAddress();for(String peer:peers.getOrDefault(device.getAddress(),java.util.List.of()))if(!peer.equals(self))sendFrames(device,Routing.streamingInfo(self,peer,false));mediaReported.put(device.getAddress(),false);
                if(!handoff.current(ticket))return;
                // Upstream disconnects A2DP/HFP here. In the native ColorOS
                // integration that makes Settings report the earbuds disconnected.
                // PAUSE suspends the media stream; keep profiles and AACP alive.
                routeNotices.put(device.getAddress(),notice);
                module.info("Remote handoff: ownership yielded; audio profiles and AACP retained");
            }catch(Exception e){routeFailure(device,e);}
        });
    }
    private void ensureControl(BluetoothDevice device)throws Exception{
        try{socket(device);return;}catch(Exception ignored){}
        if(context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IOException("系统蓝牙连接权限不可用");
        if(manager==null||!BluetoothAdapter.getDefaultAdapter().isEnabled()||device.getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("请先开启蓝牙并完成耳机配对");
        Reflect.call(manager,"checkAndConnectAirPods",new Class[]{BluetoothDevice.class},device);
        long until=SystemClock.elapsedRealtime()+5500;
        while(SystemClock.elapsedRealtime()<until){try{socket(device);return;}catch(Exception ignored){}SystemClock.sleep(100);}
        throw new IOException("原生控制通道重连超时，请检查耳机是否可用后重试");
    }
    private void recoverControl(BluetoothDevice device){
        if(context==null||context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED)return;
        if(!multiActive()||!BluetoothAdapter.getDefaultAdapter().isEnabled()||device.getBondState()!=BluetoothDevice.BOND_BONDED)return;
        int attempt=handoff.recovery(device.getAddress());if(attempt==0)return;
        new Handler(Looper.getMainLooper()).postDelayed(()->recoveryWorker.execute(()->{
            try{
                if(!multiActive()||!handoff.remoteOwns(device.getAddress()))return;
                ensureControl(device);routeNotices.put(device.getAddress(),"控制通道已恢复，音频仍由另一台设备使用");
                module.info("Recovered OEM AACP after remote handoff; audio not reconnected");
            }catch(Exception e){routeFailure(device,e);recoverControl(device);}
        }),attempt*1000L);
    }
    synchronized void disconnected(BluetoothDevice device) {
        if (device == null) return;
        BluetoothSocket ended=sessions.remove(device.getAddress());if(ended!=null)validated.remove(ended);handoff.disconnected(device.getAddress());
        synchronized(controlsLock){states.remove(device.getAddress());controlWrites.remove(device.getAddress());controlRefreshes.remove(device.getAddress());}
        requested.remove(device.getAddress());
        validationClaims.remove(device.getAddress());mediaReported.remove(device.getAddress());openedAt.remove(device.getAddress());peers.remove(device.getAddress());peerRecords.remove(device.getAddress());sourceSequences.remove(device.getAddress()); audioSources.remove(device.getAddress());
        sourceTypes.remove(device.getAddress());peerTypes.remove(device.getAddress());routeNotices.remove(device.getAddress());announced.removeIf(key->key.startsWith(device.getAddress()+"/"));
        AttChannel channel = channels.remove(device.getAddress()); if (channel != null) channel.close();
        synchronized (controlsLock) { controlsLock.notifyAll(); }
    }
    private BluetoothSocket socket(BluetoothDevice device) throws Exception {
        Object current = manager;
        if (current == null) throw new IOException("原生 AirPods 服务尚未初始化");
        BluetoothSocket socket = (BluetoothSocket) Reflect.call(current, "getDeviceSocket", new Class[]{BluetoothDevice.class}, device);
        if (socket == null || !socket.isConnected()) throw new IOException("请先连接此 AirPods，模块不会另建 AACP 连接");
        return socket;
    }
    private BluetoothDevice device(String address) {
        if (!BluetoothAdapter.checkBluetoothAddress(address)) throw new IllegalArgumentException("请从原生 AirPods 页面打开高级设置");
        return BluetoothAdapter.getDefaultAdapter().getRemoteDevice(address);
    }
    private boolean control(BluetoothDevice device, int command, byte[] value) throws Exception {
        byte[] packet = Protocol.control(command, value);
        module.info("AACP write command="+Integer.toHexString(command)+" value="+Protocol.hex(value,0,value.length));
        synchronized (commandLock) {
            BluetoothSocket socket = socket(device);
            Handler worker = (Handler) Reflect.field(manager, "mWorkerHandler");
            if (worker == null) throw new IOException("原生工作线程不可用");
            java.util.concurrent.CompletableFuture<ControlWrite> written = new java.util.concurrent.CompletableFuture<>();
            if (!worker.post(() -> {
                try {
                    if (written.isCancelled()) return;
                    if (socket(device)!=socket) throw new IOException("耳机连接已变化，请重试");
                    long before;Map<Integer,ControlWrite> writes;
                    synchronized(controlsLock){before=sequence;writes=controlWrites.computeIfAbsent(device.getAddress(),k->new ConcurrentHashMap<>());}
                    OutputStream output = socket.getOutputStream();
                    synchronized (output) { output.write(packet); output.flush(); }
                    ControlWrite update=new ControlWrite(value,before,SystemClock.elapsedRealtime());
                    synchronized(controlsLock){
                        // A late completion from a closed session must not enter
                        // the replacement session. A report arriving during flush
                        // is already in states and can still confirm this write.
                        if(controlWrites.get(device.getAddress())!=writes||socket(device)!=socket)
                            throw new IOException("耳机连接已变化，设置结果未确认");
                        writes.put(command,update);
                    }
                    written.complete(update);
                } catch (Exception e) { written.completeExceptionally(e); }
            })) throw new IOException("原生工作线程已停止");
            ControlWrite update;
            try { update=written.get(2, java.util.concurrent.TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException e) { written.cancel(false); throw new IOException("原生发送队列繁忙，已取消未发送的操作",e); }
            refreshAfterControl(device,socket,worker,update);
            synchronized (controlsLock) {
                Control observed=states.getOrDefault(device.getAddress(),Map.of()).get(command);
                return update.state(observed==null?null:observed.value,observed==null?0:observed.received,SystemClock.elapsedRealtime())==ControlWrite.CONFIRMED;
            }
        }
    }
    private void refreshAfterControl(BluetoothDevice device,BluetoothSocket expected,Handler worker,ControlWrite latest){
        String address=device.getAddress();controlRefreshes.put(address,latest);
        worker.postDelayed(()->{
            synchronized(controlsLock){
                // Coalesce a burst of setting changes into one refresh, scoped
                // to the same connection. Confirmation never blocks a writer.
                if(!controlRefreshes.remove(address,latest))return;
                Map<Integer,Control> observed=states.getOrDefault(address,Map.of());boolean waiting=false;
                for(var e:controlWrites.getOrDefault(address,Map.of()).entrySet()){
                    Control state=observed.get(e.getKey());
                    if(e.getValue().state(state==null?null:state.value,state==null?0:state.received,SystemClock.elapsedRealtime())==ControlWrite.WAITING){waiting=true;break;}
                }
                if(!waiting)return;
            }
            try{
                if(socket(device)!=expected)return;
                OutputStream output=expected.getOutputStream();synchronized(output){output.write(Protocol.requestState());output.flush();}
            }catch(Exception e){module.info("AACP background state refresh unavailable: "+e.getMessage());}
        },800);
    }
    private boolean multiActive(){
        if(context==null||!nativeLoaded||!context.getSharedPreferences("colorpods",0).getBoolean("multipoint",true))return false;
        long[] s=NativeBridge.status();return s[0]!=0&&s[1]!=0&&s[3]>0;
    }
    private void routeFailure(BluetoothDevice device,Exception e){routeNotices.put(device.getAddress(),"多设备协同："+e.getMessage());module.error("Smart Routing operation failed",e);}
    private void announce(BluetoothDevice device){
        if(!multiActive())return;
        routingWorker.execute(()->{
            try{
                if(!multiActive())return;String self=localAddress();java.util.List<String> current=peers.getOrDefault(device.getAddress(),java.util.List.of());
                announced.removeIf(key->key.startsWith(device.getAddress()+"/")&&!current.contains(key.substring(key.indexOf('/')+1)));
                for(String target:current)if(!self.equals(target)){
                    String key=device.getAddress()+"/"+target;if(!announced.add(key))continue;
                    try{sendFrames(device,Routing.streamingInfo(self,target,MediaPlayback.playing(context)&&!handoff.remoteOwns(device.getAddress())),Routing.introduce(self,target));module.info("TiPi peer introduction sent");}
                    catch(Exception e){announced.remove(key);throw e;}
                }
            }catch(Exception e){routeFailure(device,e);}
        });
    }
    private void sendFrames(BluetoothDevice device,byte[]...frames)throws Exception{
        synchronized(commandLock){
            BluetoothSocket socket=socket(device);Handler worker=(Handler)Reflect.field(manager,"mWorkerHandler");
            java.util.concurrent.CompletableFuture<Void> sent=new java.util.concurrent.CompletableFuture<>();
            if(worker==null||!worker.post(()->{
                try{if(sent.isCancelled())return;OutputStream out=socket.getOutputStream();synchronized(out){for(byte[] frame:frames)out.write(frame);out.flush();}sent.complete(null);}
                catch(Exception e){sent.completeExceptionally(e);}
            }))throw new IOException("原生工作线程已停止");
            try{sent.get(2,java.util.concurrent.TimeUnit.SECONDS);}
            catch(java.util.concurrent.TimeoutException e){sent.cancel(false);throw new IOException("原生发送队列繁忙，已取消未发送的操作",e);}
        }
    }
    private void requestState(BluetoothDevice device, long delay) {
        try {
            Handler worker=(Handler)Reflect.field(manager,"mWorkerHandler");BluetoothSocket expected=socket(device);
            if(worker!=null)worker.postDelayed(()->{
                try { if(socket(device)!=expected)return;OutputStream out=expected.getOutputStream();synchronized(out){out.write(Protocol.requestState());out.flush();} }
                catch(Exception ignored){}
            },delay);
        } catch(Exception ignored){}
    }
    private synchronized AttChannel channel(BluetoothDevice device) throws Exception {
        socket(device);
        long[] identity = nativeLoaded ? NativeBridge.status() : null;
        if (identity == null || identity[0] == 0 || identity[1] == 0 || identity[3] == 0) throw new IOException("尚未确认 Apple 身份已提交。启用后请重启蓝牙，再连接耳机");
        AttChannel existing = channels.get(device.getAddress());
        if (existing != null && existing.alive()) return existing;
        if (existing != null) existing.close();
        AttChannel created = new AttChannel(device); channels.put(device.getAddress(), created); return created;
    }
    private Bundle status(String address) {
        Bundle result = new Bundle(); result.putBoolean("manager", manager != null);
        result.putInt("controlConfirmationVersion",1);
        result.putBoolean("native", nativeLoaded);
        if (nativeLoaded) result.putLongArray("nativeStatus", NativeBridge.status());
        result.putBoolean("multipoint",context!=null&&context.getSharedPreferences("colorpods",0).getBoolean("multipoint",true));
        result.putBoolean("autoMedia",context!=null&&context.getSharedPreferences("colorpods",0).getBoolean("autoMedia",true));
        if (address != null && BluetoothAdapter.checkBluetoothAddress(address)) {
            BluetoothDevice device = device(address);
            try { result.putBoolean("connected", Boolean.TRUE.equals(Reflect.call(device, "isConnected", new Class[0]))); } catch (Exception ignored) {}
            try { socket(device); result.putBoolean("connected", true); result.putBoolean("controlReady", true); if(requested.add(address))requestState(device,100); }
            catch (Exception e) { result.putString("channelDetail", e.getMessage());BluetoothSocket previous=sessions.get(address);if(previous!=null)sessionClosed(device,previous); }
            synchronized(controlsLock){
                Map<Integer,Control> observed=states.getOrDefault(address,Map.of());
                Bundle controls=new Bundle(),pending=new Bundle(),updates=new Bundle();
                for(var e:observed.entrySet())controls.putByteArray(Integer.toHexString(e.getKey()),e.getValue().value.clone());
                long now=SystemClock.elapsedRealtime();
                for(var e:controlWrites.getOrDefault(address,Map.of()).entrySet()){
                    String key=Integer.toHexString(e.getKey());ControlWrite write=e.getValue();Control current=observed.get(e.getKey());
                    int state=write.state(current==null?null:current.value,current==null?0:current.received,now);
                    Bundle update=new Bundle();update.putInt("state",state);update.putLong("deadline",write.deadline);update.putByteArray("expected",write.expected());updates.putBundle(key,update);
                    if(state==ControlWrite.WAITING)pending.putByteArray(key,write.expected());
                }
                result.putBundle("controls",controls);result.putBundle("pendingControls",pending);result.putBundle("controlUpdates",updates);
            }
            result.putStringArray("peers", peers.getOrDefault(address, java.util.List.of()).toArray(new String[0]));
            result.putString("audioSource", audioSources.get(address));
            result.putInt("audioSourceType",sourceTypes.getOrDefault(address,0));
            Bundle types=new Bundle();for(var e:peerTypes.getOrDefault(address,Map.of()).entrySet())types.putString(e.getKey(),e.getValue());result.putBundle("peerTypes",types);
            result.putString("routeNotice",routeNotices.get(address));
            result.putBoolean("remoteAudioOwner",handoff.remoteOwns(address));
            try { result.putString("selfAddress", localAddress()); } catch (IOException ignored) {}
        }
        return result;
    }
    private String localAddress() throws IOException {
        // Bluetooth owns the controller address but does not request the public
        // LOCAL_MAC_ADDRESS permission. Read its initialized AdapterProperties,
        // inside that same process, instead of using the redacted app API.
        try {
            Class<?> serviceClass=Class.forName("com.android.bluetooth.btservice.AdapterService",false,context.getClassLoader());
            var field=serviceClass.getDeclaredField("sAdapterService");field.setAccessible(true);Object service=field.get(null);
            if(service==null)throw new IOException("系统蓝牙控制器尚未初始化");
            Object properties=Reflect.call(service,"getAdapterProperties",new Class[0]);
            byte[] bytes=(byte[])Reflect.field(properties,"mAddress");
            if(bytes==null||bytes.length!=6)throw new IOException("系统蓝牙控制器地址尚未就绪");
            String address=Routing.mac(bytes,0,false);
            if(address.equals("02:00:00:00:00:00")||address.equals("00:00:00:00:00:00"))throw new IOException("系统返回了无效的控制器地址");
            return address;
        }catch(ReflectiveOperationException e){throw new IOException("此系统的蓝牙控制器地址接口不受支持",e);}
    }
    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) { reply.writeString(BridgeClient.DESCRIPTOR); return true; }
        if (code < BridgeClient.STATUS || code > BridgeClient.MULTIPOINT) return false;
        data.enforceInterface(BridgeClient.DESCRIPTOR);
        Context c = context;
        if (c == null || !mayControl(c, Binder.getCallingUid())) throw new SecurityException("Untrusted Bluetooth bridge caller");
        if (data.dataAvail() > 8192) throw new IllegalArgumentException("Request too large");
        Bundle request = data.readBundle(getClass().getClassLoader()); data.enforceNoDataAvail();
        Bundle result;
        long identity = Binder.clearCallingIdentity();
        try {
            if (request == null) throw new IllegalArgumentException("Missing request");
            String address = request.getString("address");
            result = new Bundle();
            switch (code) {
                case BridgeClient.STATUS -> {
                    if(request.getBoolean("refreshControls"))sendFrames(device(address),Protocol.requestState());
                    result=status(address);if(request.getBoolean("audioState")&&BluetoothAdapter.checkBluetoothAddress(address))result.putBoolean("audioReady",AudioLinks.ready(c,device(address)));
                }
                case BridgeClient.VENDOR -> {
                    if (!nativeLoaded) throw new IOException("原生 VendorID Hook 未加载");
                    boolean enable = request.getBoolean("enabled");
                    if (!c.getSharedPreferences("colorpods", 0).edit().putBoolean("vendor", enable).commit()) throw new IOException("无法保存设置");
                    NativeBridge.setEnabled(enable);
                    for (AttChannel channel : channels.values()) channel.close(); channels.clear();
                    result = status(address); result.putBoolean("restartRequired", true);
                }
                case BridgeClient.CONTROL -> {
                    BluetoothDevice device = device(address); int command = request.getInt("command");
                    byte[] value = request.getByteArray("value");
                    boolean confirmed=control(device, command, value);result=status(address);result.putBoolean("controlConfirmed",confirmed);
                }
                case BridgeClient.ATT_READ -> {
                    int handle = request.getInt("handle"); AttCodec.validateHandle(handle);
                    result.putByteArray("value", channel(device(address)).read(handle));
                }
                case BridgeClient.ATT_PATCH -> {
                    int handle = request.getInt("handle"); AttCodec.validateHandle(handle);
                    AttChannel channel = channel(device(address));
                    synchronized (channel) {
                        byte[] current = channel.read(handle);
                        byte[] updated = AttCodec.patch(handle, current, request.getString("field", ""), request.getFloat("value", Float.NaN));
                        channel.write(handle, updated);
                        byte[] verified = channel.read(handle);
                        String field = request.getString("field", "");
                        float expected = request.getFloat("value", Float.NaN);
                        if (Math.abs(AttCodec.value(handle, verified, field) - expected) > 0.015f) throw new IOException("耳机未应用该调节值");
                        result.putByteArray("value", verified);
                    }
                }
                case BridgeClient.ROUTE -> {
                    BluetoothDevice d=device(address);Bundle intent=new Bundle(request);
                    java.util.concurrent.CompletableFuture<Bundle> action=new java.util.concurrent.CompletableFuture<>();
                    routingWorker.execute(()->{try{if(!action.isCancelled())action.complete(reclaim(d,intent));}catch(Exception e){action.completeExceptionally(e);}});
                    try{result=action.get(30,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.TimeoutException e){action.cancel(false);throw new IOException("音频切换超时，请重试",e);}
                }
                case BridgeClient.MULTIPOINT -> {
                    String key=request.containsKey("autoMedia")?"autoMedia":"multipoint";boolean value=request.getBoolean(key.equals("autoMedia")?"autoMedia":"enabled");
                    if(!c.getSharedPreferences("colorpods",0).edit().putBoolean(key,value).commit())throw new IOException("无法保存设置");
                    if(key.equals("multipoint")){announced.clear();if(value)announce(device(address));}result=status(address);
                }
            }
        } catch (Exception e) {
            result = new Bundle(); Throwable cause = e.getCause() == null ? e : e.getCause();
            result.putString("error", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            module.info("Bridge operation " + code + " failed: " + result.getString("error"));
        } finally { Binder.restoreCallingIdentity(identity); }
        reply.writeNoException(); reply.writeBundle(result); return true;
    }
    private Bundle reclaim(BluetoothDevice device,Bundle request)throws Exception{
        String address=device.getAddress();if(!multiActive())throw new IOException("请先启用 Apple 身份和多设备协同");
        if(request.getBoolean("connectOnly")){ensureControl(device);return status(address);}
        validationClaims.remove(address);HandoffState.Ticket claim=handoff.beginClaim(address);boolean success=false;
        try{
            ensureControl(device);String self=localAddress();
            long peerUntil=SystemClock.elapsedRealtime()+2000;while(peerTakingOver(address,self)&&SystemClock.elapsedRealtime()<peerUntil)SystemClock.sleep(100);
            if(peerTakingOver(address,self))throw new IOException("另一台设备正在切换音频，请待切换完成后重试");
            long before;synchronized(controlsLock){before=sequence;}
            // Claim and restore notifications before waiting for discovery. A
            // re-opened OEM socket may not report TiPi peers until this step.
            sendFrames(device,Routing.ownership(true));validated.add(socket(device));requestState(device,500);
            long wait=SystemClock.elapsedRealtime()+3000;
            while(peers.getOrDefault(address,java.util.List.of()).stream().noneMatch(p->!p.equals(self))&&SystemClock.elapsedRealtime()<wait)SystemClock.sleep(100);
            String target=request.getString("target");
            if(target==null&&request.getBoolean("automatic")){
                String source=audioSources.get(address);
                if(source!=null&&!source.equals(self)&&peers.getOrDefault(address,java.util.List.of()).contains(source))target=source;
                else for(String peer:peers.getOrDefault(address,java.util.List.of()))if(!peer.equals(self)){target=peer;break;}
            }
            if(target!=null&&(target.equals(self)||!peers.getOrDefault(address,java.util.List.of()).contains(target)))throw new IOException("所选设备已断开");
            if(target==null&&!request.getBoolean("automatic"))throw new IOException("第二台设备已断开或尚未发现");
            boolean playing=request.getBoolean("automatic")||MediaPlayback.playing(context)||MediaPlayback.resumable(pausedSessions.getOrDefault(address,java.util.List.of()));
            module.info("Reclaim discovery: peerCount="+peers.getOrDefault(address,java.util.List.of()).size()+" target="+(target!=null)+" playback="+playing);
            if(target!=null)sendFrames(device,Routing.streamingInfo(self,target,playing),Routing.showNearby(target),request.getBoolean("reverse")?Routing.reverse(target):Routing.takeOver(target));
            requestState(device,500);AudioLinks.connect(context,device);
            long audioUntil=SystemClock.elapsedRealtime()+3500;while(!AudioLinks.ready(context,device)&&SystemClock.elapsedRealtime()<audioUntil)SystemClock.sleep(150);
            if(!AudioLinks.ready(context,device))throw new IOException("手机音频通道未接通，已暂停播放");
            // Both profiles may stay connected throughout a handoff. Wait for
            // the earbuds to select this host before restarting its media; NONE
            // is expected here because we deliberately paused the player.
            if(playing){long selectedUntil=SystemClock.elapsedRealtime()+3500;
                synchronized(controlsLock){while(true){
                    if(!handoff.claiming(claim))throw new IOException("本次切换已因连接变化取消");
                    Control owner=states.getOrDefault(address,Map.of()).get(6);
                    if(HandoffConfirmation.selected(before,owner==null?0:owner.received,owner==null?-1:owner.value[0],sourceSequences.getOrDefault(address,0L),self.equals(audioSources.get(address))))break;
                    long remaining=selectedUntil-SystemClock.elapsedRealtime();if(remaining<=0)throw new IOException("耳机尚未完成手机音频路由，保持暂停");controlsLock.wait(remaining);
                }}
            }
            if(!handoff.claiming(claim))throw new IOException("本次切换已因连接变化取消");
            var paused=pausedSessions.getOrDefault(address,java.util.List.of());MediaPlayback.awaitPaused(paused,800);
            module.info("Resuming after local route selection: recorded="+paused.size()+" resumed="+MediaPlayback.resume(paused));
            long until=SystemClock.elapsedRealtime()+3500;
            synchronized(controlsLock){while(SystemClock.elapsedRealtime()<until){
                Control owner=states.getOrDefault(address,Map.of()).get(6);
                long sourceSeq=sourceSequences.getOrDefault(address,0L);
                if(!handoff.claiming(claim))throw new IOException("本次切换已因连接变化取消");
                if(HandoffConfirmation.confirmed(before,owner==null?0:owner.received,owner==null?-1:owner.value[0],sourceSeq,self.equals(audioSources.get(address)),sourceTypes.getOrDefault(address,0),playing))break;
                controlsLock.wait(Math.max(1,until-SystemClock.elapsedRealtime()));
            }}
            Control owner=states.getOrDefault(address,Map.of()).get(6);
            boolean confirmed=HandoffConfirmation.confirmed(before,owner==null?0:owner.received,owner==null?-1:owner.value[0],sourceSequences.getOrDefault(address,0L),self.equals(audioSources.get(address)),sourceTypes.getOrDefault(address,0),playing);
            if(!handoff.claiming(claim)||!confirmed)throw new IOException("耳机未确认本次音频切换，请待另一台设备空闲后重试");
            validated.add(socket(device));success=true;mediaReported.put(address,playing);pausedSessions.remove(address);routeNotices.remove(address);module.info("Local reclaim confirmed by fresh earbud state");
            Bundle result=status(address);result.putBoolean("routeConfirmed",true);result.putBoolean("audioReady",AudioLinks.ready(context,device));return result;
        }finally{HandoffState.Ticket ticket=handoff.finishClaim(claim,success);if(!success)yieldAudio(device,ticket,"切换未确认，已保留另一台设备的音频");}
    }
    private boolean mayControl(Context context, int uid) {
        if (uid == module.getModuleApplicationInfo().uid) return true;
        try {
            var app = context.getPackageManager().getApplicationInfo("com.heytap.mydevices", 0);
            return app.uid == uid && (app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) { return false; }
    }
}
