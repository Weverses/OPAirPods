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
    private final Map<String, java.util.List<android.media.session.MediaController>> pausedSessions=new ConcurrentHashMap<>();
    private final Map<String,Map<String,Routing.Message>> peerReports=new ConcurrentHashMap<>();
    private final Map<String,Claim> claims=new ConcurrentHashMap<>();
    private final java.util.Set<BluetoothSocket> validated=ConcurrentHashMap.newKeySet();
    private final Map<String, String> audioSources = new ConcurrentHashMap<>();
    private final Map<String, Integer> sourceTypes = new ConcurrentHashMap<>();
    private final Map<String, Map<String,String>> peerTypes = new ConcurrentHashMap<>();
    private final Map<String,String> routeNotices = new ConcurrentHashMap<>();
    private final Map<String,HandoffNotice> handoffNotices=new ConcurrentHashMap<>();
    private final java.util.Set<String> announced = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.ScheduledExecutorService routingWorker=java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"ColorPodsRouting");t.setDaemon(true);return t;});
    private final java.util.Set<String> requested = ConcurrentHashMap.newKeySet();
    private final HandoffState handoff=new HandoffState();
    private final Map<String,BluetoothSocket> sessions=new ConcurrentHashMap<>();
    private final Map<String,Integer> sessionCounts=new ConcurrentHashMap<>(),sessionDrops=new ConcurrentHashMap<>();
    private final java.util.concurrent.ExecutorService recoveryWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ColorPodsRecovery");t.setDaemon(true);return t;});
    private final java.util.concurrent.ExecutorService mediaRecoveryWorker=java.util.concurrent.Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ColorPodsMediaRecovery");t.setDaemon(true);return t;});
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
    private boolean autoMedia(){return multiActive()&&context.getSharedPreferences("colorpods",0).getBoolean("autoMedia",true);}
    private boolean remoteCall(BluetoothDevice device){
        String address=device.getAddress();
        try{if(sourceTypes.getOrDefault(address,0)==1&&!localAddress().equals(audioSources.get(address)))return true;}catch(IOException ignored){}
        return peerReports.getOrDefault(address,Map.of()).values().stream().anyMatch(Routing.Message::call);
    }
    private boolean needsReclaim(BluetoothDevice d){
        if(handoff.claiming(d.getAddress())){HandoffState.Ticket validation=validationClaims.get(d.getAddress());return validation!=null&&handoff.claiming(validation);}
        try{
            socket(d);String self=localAddress();
            if(self.equals(audioSources.get(d.getAddress()))&&sourceTypes.getOrDefault(d.getAddress(),0)==2)return false;
            return handoff.remoteOwns(d.getAddress())||peers.getOrDefault(d.getAddress(),java.util.List.of()).stream().anyMatch(peer->!peer.equals(self));
        }catch(Exception e){return true;}
    }
    private void onLocalPlaybackStarted(android.media.session.MediaController player){
        BluetoothDevice d=lastDevice;
        module.info("Playback intent: target="+(d!=null)+" enabled="+autoMedia());
        if(d==null||!autoMedia()||!needsReclaim(d))return;
        if(remoteCall(d)){module.info("Media takeover deferred: peer call is active");return;}
        Bundle intent=BridgeClient.forDevice(d.getAddress());intent.putBoolean("automatic",true);
        queueRoute(d,intent,player).whenComplete((result,error)->{if(error!=null)module.info("Automatic takeover ended: "+error.getMessage());});
    }
    void manager(Object value) throws ReflectiveOperationException {
        manager = value;
        attach((Context) Reflect.field(value, "mContext"));
        module.info("Captured OEM AirPodsManager");
    }
    boolean isAirPods(BluetoothDevice device){
        if(device==null||manager==null)return false;
        if(sessions.containsKey(device.getAddress()))return true;
        try{return Boolean.TRUE.equals(Reflect.call(manager,"checkIsAirpodsDevice",new Class[]{BluetoothDevice.class},device));}
        catch(Exception ignored){return false;}
    }
    void packet(byte[] bytes, int length, BluetoothDevice device,BluetoothSocket reader) {
        if (device == null || !Routing.header(bytes, length)) return;
        if(reader!=null&&sessions.get(device.getAddress())!=reader)return;
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

            if(multiActive())try{
                yieldAudio(device,handoff.source(device.getAddress(),localAddress().equals(audioSources.get(device.getAddress())),bytes[12]&255),"音频来源已切换到另一台设备");
                HandoffNotice.Event notice=handoffNotices.computeIfAbsent(device.getAddress(),k->new HandoffNotice())
                    .observe(localAddress(),Routing.mac(bytes,6,true),bytes[12]&255,SystemClock.elapsedRealtime());
                if(notice!=null)routingWorker.schedule(()->publishHandoffNotice(device,notice),notice.remote()?600:0,java.util.concurrent.TimeUnit.MILLISECONDS);
            }catch(IOException e){routeFailure(device,e);}
            synchronized (controlsLock) { controlsLock.notifyAll(); }
        }
        if(bytes[4]==0x11)try{
            Routing.Message message=Routing.response(bytes,length);
            if(message.decisive())peerReports.computeIfAbsent(device.getAddress(),k->new ConcurrentHashMap<>()).put(message.sender(),message);
            if(message.kind()!=null)peerTypes.computeIfAbsent(device.getAddress(),k->new ConcurrentHashMap<>()).put(message.sender(),message.kind());
            if(multiActive()&&peers.getOrDefault(device.getAddress(),java.util.List.of()).contains(message.sender())&&message.relinquish())
                yieldAudio(device,handoff.requestRemote(device.getAddress()),message.reversed()?"另一台设备已撤销切换，可手动切回手机":"音频已交给另一台设备，控制连接保留");
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
    private void publishHandoffNotice(BluetoothDevice device,HandoffNotice.Event event){
        HandoffNotice state=handoffNotices.get(device.getAddress());
        if(!multiActive()||state==null||state.current(event.id(),SystemClock.elapsedRealtime())==null)return;
        try{
            // Existing protected host receiver wakes the cards process even
            // when no settings page is open. Payload is read back over Binder.
            android.content.Intent intent=new android.content.Intent(HostHandoffCapsule.ACTION)
                .setClassName("com.heytap.mydevices",HostHandoffCapsule.RECEIVER)
                .putExtra("address",device.getAddress()).putExtra("noticeId",event.id());
            context.sendBroadcast(intent);
            module.info("Handoff capsule event dispatched: remote="+event.remote());
        }catch(Exception e){module.error("Handoff capsule dispatch failed",e);}
    }
    synchronized void sessionOpened(BluetoothDevice device,BluetoothSocket socket){
        BluetoothSocket previous=sessions.get(device.getAddress());if(previous==socket)return;
        if(previous!=null)disconnected(device);sessions.put(device.getAddress(),socket);lastDevice=device;openedAt.put(device.getAddress(),SystemClock.elapsedRealtime());sessionCounts.merge(device.getAddress(),1,Integer::sum);module.info("OEM AACP session opened");
        Claim pending=claims.get(device.getAddress());if(pending!=null&&handoff.preparing(pending.ticket))pending.socket=socket;
        requestState(device,1000);
        new Handler(Looper.getMainLooper()).postDelayed(()->routingWorker.execute(()->validateSession(device,socket)),5000);
    }
    private boolean peerTakingOver(String address,String self){for(Routing.Peer peer:peerRecords.getOrDefault(address,java.util.List.of()))if(!peer.address().equals(self)&&peer.takingOver())return true;return false;}
    private void validateSession(BluetoothDevice device,BluetoothSocket expected){
        String address=device.getAddress();
        try{
            if(!multiActive()||sessions.get(address)!=expected||validated.contains(expected)||handoff.claiming(address)||SystemClock.elapsedRealtime()<openedAt.getOrDefault(address,0L)+5000)return;
            String self=localAddress();
            if(handoff.remoteOwns(address)||sourceTypes.getOrDefault(address,0)!=0&&!self.equals(audioSources.get(address))){validated.add(expected);module.info("Passive AACP restore: remote route retained");return;}
            if(peerTakingOver(address,self)){module.info("Deferred initial validation: peer is taking over");return;}
            validated.add(expected);HandoffState.Ticket validation=handoff.beginClaim(address);validationClaims.put(address,validation);sendFrames(device,()->sessions.get(address)==expected&&multiActive()&&handoff.claiming(validation),()->handoff.arm(validation),Routing.ownership(true));requestState(device,500);
            module.info("Initial AACP validation claim sent");
            new Handler(Looper.getMainLooper()).postDelayed(()->routingWorker.execute(()->{
                try{
                    if(sessions.get(address)!=expected||!multiActive()||!handoff.claiming(validation)){validationClaims.remove(address,validation);return;}
                    validationClaims.remove(address,validation);
                    boolean localAudio=self.equals(audioSources.get(address))&&sourceTypes.getOrDefault(address,0)!=0;
                    module.info("Validation settled: localSource="+localAudio+" localPlayback="+MediaPlayback.playing(context)+" peerCount="+peers.getOrDefault(address,java.util.List.of()).size());
                    if(!localAudio&&peers.getOrDefault(address,java.util.List.of()).stream().anyMatch(p->!p.equals(self))){
                        sendFrames(device,()->sessions.get(address)==expected&&multiActive()&&handoff.claiming(validation),Routing.ownership(false));handoff.finishClaim(validation,false);module.info("Validation complete; idle ownership returned to peer");
                        if(MediaPlayback.playing(context)&&autoMedia())onLocalPlaybackStarted(null);
                    }else handoff.finishClaim(validation,true);
                }catch(Exception e){handoff.finishClaim(validation,false);routeFailure(device,e);}
            }),3000);
        }catch(Exception e){HandoffState.Ticket validation=validationClaims.remove(address);if(validation!=null)handoff.finishClaim(validation,false);routeFailure(device,e);}
    }
    synchronized void sessionClosed(BluetoothDevice device,BluetoothSocket socket){
        if(!sessions.remove(device.getAddress(),socket))return;validated.remove(socket);
        sessionDrops.merge(device.getAddress(),1,Integer::sum);
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
                sendFrames(device,()->multiActive()&&handoff.current(ticket),Routing.ownership(false));
                String self=localAddress();for(String peer:peers.getOrDefault(device.getAddress(),java.util.List.of()))if(!peer.equals(self))sendFrames(device,()->multiActive()&&handoff.current(ticket),Routing.streamingInfo(self,peer,false));mediaReported.put(device.getAddress(),false);
                if(!handoff.current(ticket))return;
                // Upstream disconnects A2DP/HFP here. In the native ColorOS
                // integration that makes Settings report the earbuds disconnected.
                // PAUSE suspends the media stream; keep profiles and AACP alive.
                routeNotices.put(device.getAddress(),notice);
                module.info("Remote handoff: ownership yielded; audio profiles and AACP retained");
            }catch(Exception e){routeFailure(device,e);}
        });
    }
    private void ensureControl(BluetoothDevice device)throws Exception{ensureControl(device,()->multiActive());}
    private void ensureControl(BluetoothDevice device,java.util.function.BooleanSupplier valid)throws Exception{
        ensureControl(device,valid,5500);
    }
    private void ensureControl(BluetoothDevice device,java.util.function.BooleanSupplier valid,long timeout)throws Exception{
        if(!valid.getAsBoolean())throw new IOException("控制恢复已取消");
        try{socket(device);return;}catch(Exception ignored){}
        if(context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT)!=android.content.pm.PackageManager.PERMISSION_GRANTED)throw new IOException("系统蓝牙连接权限不可用");
        if(manager==null||!BluetoothAdapter.getDefaultAdapter().isEnabled()||device.getBondState()!=BluetoothDevice.BOND_BONDED)throw new IOException("请先开启蓝牙并完成耳机配对");
        if(!valid.getAsBoolean())throw new IOException("控制恢复已取消");
        Reflect.call(manager,"checkAndConnectAirPods",new Class[]{BluetoothDevice.class},device);
        long until=SystemClock.elapsedRealtime()+timeout;
        while(SystemClock.elapsedRealtime()<until){if(!valid.getAsBoolean())throw new IOException("控制恢复已取消");try{socket(device);return;}catch(Exception ignored){}SystemClock.sleep(100);}
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
        BluetoothSocket ended=sessions.remove(device.getAddress());if(ended!=null){validated.remove(ended);sessionDrops.merge(device.getAddress(),1,Integer::sum);}handoff.disconnected(device.getAddress());
        synchronized(controlsLock){states.remove(device.getAddress());controlWrites.remove(device.getAddress());controlRefreshes.remove(device.getAddress());}
        requested.remove(device.getAddress());
        validationClaims.remove(device.getAddress());peerReports.remove(device.getAddress());mediaReported.remove(device.getAddress());openedAt.remove(device.getAddress());peers.remove(device.getAddress());peerRecords.remove(device.getAddress());sourceSequences.remove(device.getAddress()); audioSources.remove(device.getAddress());
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
    private void sendFrames(BluetoothDevice device,byte[]...frames)throws Exception{sendFrames(device,()->true,frames);}
    private void sendFrames(BluetoothDevice device,java.util.function.BooleanSupplier valid,byte[]...frames)throws Exception{
        sendFrames(device,valid,()->{},frames);
    }
    private void sendFrames(BluetoothDevice device,java.util.function.BooleanSupplier valid,Runnable beforeWrite,byte[]...frames)throws Exception{
        synchronized(commandLock){
            BluetoothSocket socket=socket(device);Handler worker=(Handler)Reflect.field(manager,"mWorkerHandler");
            java.util.concurrent.CompletableFuture<Void> sent=new java.util.concurrent.CompletableFuture<>();
            if(worker==null||!worker.post(()->{
                try{if(sent.isCancelled())return;
                    if(socket(device)!=socket||sessions.get(device.getAddress())!=socket||!valid.getAsBoolean())throw new IOException("控制连接或切换任务已变化");
                    OutputStream out=socket.getOutputStream();synchronized(out){beforeWrite.run();for(byte[] frame:frames){if(!valid.getAsBoolean())throw new IOException("音频切换已取消");out.write(frame);}out.flush();}sent.complete(null);}
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
        result.putInt("controlConfirmationVersion",1);result.putInt("routingVersion",2);
        result.putBoolean("native", nativeLoaded);
        if (nativeLoaded) result.putLongArray("nativeStatus", NativeBridge.status());
        result.putBoolean("multipoint",context!=null&&context.getSharedPreferences("colorpods",0).getBoolean("multipoint",true));
        result.putBoolean("autoMedia",context!=null&&context.getSharedPreferences("colorpods",0).getBoolean("autoMedia",true));
        if (address != null && BluetoothAdapter.checkBluetoothAddress(address)) {
            result.putInt("controlSessions",sessionCounts.getOrDefault(address,0));result.putInt("controlDrops",sessionDrops.getOrDefault(address,0));
            result.putLong("controlOpenedAt",openedAt.getOrDefault(address,0L));Claim claim=claims.get(address);result.putInt("routePhase",claim==null?0:claim.phase);
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
            ClassLoader loader = context.getClassLoader();
            Class<?> serviceClass = HostTargets.typeOrNull(HostTargets.BT_ADAPTER_SERVICE_CLASS, loader);
            if (serviceClass == null) throw new IOException("DexKit AdapterService target unresolved");
            java.lang.reflect.Field field = HostTargets.fieldOrNull(HostTargets.BT_ADAPTER_SERVICE_FIELD, loader);
            if (field == null) throw new IOException("DexKit AdapterService field unresolved");
            Object service = field.get(null);
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
        if (code < BridgeClient.STATUS || code > BridgeClient.HANDOFF_NOTICE) return false;
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
                    java.util.concurrent.CompletableFuture<Bundle> action=queueRoute(device(address),new Bundle(request),null);
                    try{result=action.get(25,java.util.concurrent.TimeUnit.SECONDS);}catch(java.util.concurrent.TimeoutException e){action.cancel(false);throw new IOException("音频切换超时，操作已取消",e);}
                }
                case BridgeClient.MULTIPOINT -> {
                    String key=request.containsKey("autoMedia")?"autoMedia":"multipoint";boolean value=request.getBoolean(key.equals("autoMedia")?"autoMedia":"enabled");
                    if(!c.getSharedPreferences("colorpods",0).edit().putBoolean(key,value).commit())throw new IOException("无法保存设置");
                    if(!value){for(Claim claim:claims.values())if(key.equals("multipoint")||claim.automatic){claim.result.cancel(false);handoff.cancel(claim.device.getAddress());}if(key.equals("multipoint")){for(String target:sessions.keySet())handoff.cancel(target);validationClaims.clear();}}
                    if(key.equals("multipoint")){announced.clear();if(value)announce(device(address));}result=status(address);
                }
                case BridgeClient.HANDOFF_NOTICE -> {
                    HandoffNotice state=handoffNotices.get(address);
                    HandoffNotice.Event event=state==null||!multiActive()?null:state.take(request.getLong("noticeId"),SystemClock.elapsedRealtime());
                    if(event!=null){result.putBoolean("available",true);result.putBoolean("remote",event.remote());result.putLong("noticeId",event.id());
                        result.putString("source",event.source());result.putString("title",HandoffNotice.title(peerTypes.getOrDefault(address,Map.of()).get(event.source())));}
                }
            }
        } catch (Exception e) {
            result = new Bundle(); Throwable cause = e.getCause() == null ? e : e.getCause();
            result.putString("error", cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
            module.info("Bridge operation " + code + " failed: " + result.getString("error"));
        } finally { Binder.restoreCallingIdentity(identity); }
        reply.writeNoException(); reply.writeBundle(result); return true;
    }
    /** All phases advance on routingWorker. Waiting for players, discovery,
     * profiles or sources never blocks the queue that must process a remote yield. */
    private static final class Claim {
        final BluetoothDevice device;
        final Bundle request;
        final android.media.session.MediaController player;
        final java.util.concurrent.CompletableFuture<Bundle> result;
        final boolean automatic;
        final HandoffState.Ticket ticket;
        volatile BluetoothSocket socket;
        java.util.List<android.media.session.MediaController> paused=java.util.List.of();
        long before,deadline;
        int phase;
        boolean playing;
        PlaybackPauseGate pauseGate;
        Boolean audioPrepared;
        Claim(BluetoothDevice device,Bundle request,android.media.session.MediaController player,
              java.util.concurrent.CompletableFuture<Bundle> result,HandoffState.Ticket ticket){
            this.device=device;this.request=request;this.player=player;this.result=result;this.ticket=ticket;automatic=request.getBoolean("automatic");
        }
    }
    private java.util.concurrent.CompletableFuture<Bundle> queueRoute(BluetoothDevice device,Bundle request,android.media.session.MediaController player){
        var result=new java.util.concurrent.CompletableFuture<Bundle>();
        routingWorker.execute(()->{
            if(result.isCancelled())return;
            try{
                if(!multiActive())throw new IOException("请先启用 Apple 身份和多设备协同");
                if(request.containsKey("noticeId")){
                    HandoffNotice state=handoffNotices.get(device.getAddress());
                    HandoffNotice.Event event=state==null?null:state.current(request.getLong("noticeId"),SystemClock.elapsedRealtime());
                    if(event==null||!event.remote()||!event.source().equals(audioSources.get(device.getAddress()))||sourceTypes.getOrDefault(device.getAddress(),0)==0)
                        throw new IOException("音频来源已变化，请重新选择设备");
                    request.putString("target",event.source());request.putBoolean("reverse",true);
                }
                if(request.getBoolean("connectOnly")){
                    recoveryWorker.execute(()->{try{ensureControl(device,()->multiActive()&&!result.isCancelled());result.complete(status(device.getAddress()));}catch(Exception e){result.completeExceptionally(e);}});return;
                }
                if(request.getBoolean("automatic")&&(!autoMedia()||remoteCall(device)))throw new IOException("另一台设备正在通话，暂不自动切换媒体");
                Claim old=claims.get(device.getAddress());
                if(old!=null){if(request.getBoolean("automatic")){result.completeExceptionally(new IOException("音频切换正在进行"));return;}old.result.cancel(false);}
                validationClaims.remove(device.getAddress());
                Claim claim=new Claim(device,request,player,result,handoff.beginClaim(device.getAddress()));claims.put(device.getAddress(),claim);
                claim.playing=claim.automatic||MediaPlayback.playing(context)||MediaPlayback.resumable(pausedSessions.getOrDefault(device.getAddress(),java.util.List.of()));
                if(claim.automatic){claim.paused=MediaPlayback.pause(context,player);if(!claim.paused.isEmpty())pausedSessions.put(device.getAddress(),claim.paused);}
                else claim.paused=pausedSessions.getOrDefault(device.getAddress(),java.util.List.of());
                module.info("Route transaction started: generation="+claim.ticket.generation()+" automatic="+claim.automatic);
                routingWorker.schedule(()->{if(claims.get(device.getAddress())==claim)finish(claim,new IOException("音频切换未在期限内完成"));},25,java.util.concurrent.TimeUnit.SECONDS);
                mediaRecoveryWorker.execute(()->{
                    try{ensureControl(device,()->valid(claim),12000);BluetoothSocket expected=socket(device);
                        routingWorker.execute(()->{if(!valid(claim)){finish(claim,new IOException("切换已因连接变化取消"));return;}claim.socket=expected;claim.phase=1;claim.pauseGate=new PlaybackPauseGate(SystemClock.elapsedRealtime());step(claim);});
                    }catch(Exception e){routingWorker.execute(()->finish(claim,e));}
                });
            }catch(Exception e){result.completeExceptionally(e);}
        });
        return result;
    }
    private boolean valid(Claim c){
        return !c.result.isDone()&&claims.get(c.device.getAddress())==c&&handoff.claiming(c.ticket)&&multiActive()&&(!c.automatic||autoMedia())
            &&(c.socket==null||sessions.get(c.device.getAddress())==c.socket&&c.socket.isConnected());
    }
    private void later(Claim c){routingWorker.schedule(()->step(c),100,java.util.concurrent.TimeUnit.MILLISECONDS);}
    private void step(Claim c){
        try{
            if(!valid(c))throw new IOException("本次切换已取消或控制连接已变化");
            if(c.automatic&&remoteCall(c.device))throw new IOException("另一台设备正在通话，暂不自动切换媒体");
            long now=SystemClock.elapsedRealtime();String address=c.device.getAddress(),self=localAddress();
            if(c.phase==1){
                PlaybackPauseGate.Action action=c.pauseGate.check(MediaPlayback.paused(c.paused),now);
                if(action==PlaybackPauseGate.Action.TIMEOUT)throw new IOException("播放器尚未暂停，已取消接管");
                if(action==PlaybackPauseGate.Action.PAUSE){MediaPlayback.pauseAgain(context,c.paused);module.info("Reasserted player pause after control recovery");}
                if(action!=PlaybackPauseGate.Action.READY){later(c);return;}
                c.phase=2;c.deadline=now+3000;
            }
            if(c.phase==2){
                if(peerTakingOver(address,self)){if(now>=c.deadline)throw new IOException("另一台设备正在切换音频，请稍后重试");later(c);return;}
                String source=audioSources.get(address);
                var candidates=peerRecords.getOrDefault(address,java.util.List.of()).stream().filter(p->!p.address().equals(self)&&p.role()!=0).map(Routing.Peer::address).collect(java.util.stream.Collectors.toList());
                String target=Routing.target(self,c.request.getString("target"),source,peerRecords.getOrDefault(address,java.util.List.of()));
                if(target==null&&now<c.deadline){later(c);return;}
                if(target!=null&&!candidates.contains(target))throw new IOException("所选设备已断开");
                if(target==null&&!candidates.isEmpty())throw new IOException("多个设备在线，尚未确认当前音频来源");
                if(target==null&&!c.automatic)throw new IOException("第二台设备尚未发现");
                sendFrames(c.device,()->valid(c)&&!peerTakingOver(address,self),()->{synchronized(controlsLock){c.before=sequence;handoff.arm(c.ticket);}},Routing.ownership(true));validated.add(c.socket);
                if(target!=null)sendFrames(c.device,()->valid(c),Routing.streamingInfo(self,target,c.playing),Routing.showNearby(target),c.request.getBoolean("reverse")?Routing.reverse(target):Routing.takeOver(target));
                requestState(c.device,500);c.phase=3;c.deadline=now+5000;
                module.info("Route claimed: generation="+c.ticket.generation()+" peer="+(target!=null));
            }
            if(c.phase==3){
                if(now>=c.deadline)throw new IOException("手机音频通道未准备就绪");
                AudioLinks.prepare(context,c.device,()->valid(c)).whenComplete((ready,error)->routingWorker.execute(()->{
                    if(error!=null||!java.util.Objects.equals(c.audioPrepared,ready))module.info("Route audio prepared: generation="+c.ticket.generation()+" ready="+ready+" error="+(error==null?"none":error.getClass().getSimpleName()));
                    c.audioPrepared=ready;
                    if(error!=null){finish(c,new IOException("系统音频路由失败",error));return;}
                    if(!valid(c)){finish(c,new IOException("音频切换已取消"));return;}
                    if(Boolean.TRUE.equals(ready)){c.phase=4;c.deadline=SystemClock.elapsedRealtime()+4000;}
                    later(c);
                }));return;
            }
            Control owner=states.getOrDefault(address,Map.of()).get(6);
            long ownerSequence=owner==null?0:owner.received,sourceSequence=sourceSequences.getOrDefault(address,0L);
            int owns=owner==null?-1:owner.value[0];boolean local=self.equals(audioSources.get(address));
            if(c.phase==4){
                if(!HandoffConfirmation.selected(c.before,ownerSequence,owns,sourceSequence,local)){
                    if(now>=c.deadline)throw new IOException("耳机尚未选中手机音频，保持暂停");later(c);return;
                }
                if(c.playing){
                    if(!valid(c))throw new IOException("音频切换已取消");
                    int resumed=MediaPlayback.resume(context,c.paused,c.automatic&&c.paused.isEmpty());
                    module.info("Resumed selected local player: count="+resumed+" generation="+c.ticket.generation());
                }
                c.phase=5;c.deadline=now+4000;
            }
            if(c.phase==5){
                if(HandoffConfirmation.confirmed(c.before,ownerSequence,owns,sourceSequence,local,sourceTypes.getOrDefault(address,0),c.playing)){finish(c,null);return;}
                if(now>=c.deadline)throw new IOException("耳机未确认手机媒体来源，保持暂停");
                later(c);
            }
        }catch(Exception e){module.error("Route phase failed: phase="+c.phase,e);finish(c,e);}
        catch(LinkageError e){module.error("Route runtime API unavailable: phase="+c.phase,e);finish(c,new IOException("系统音频接口不可用",e));}
    }
    private void finish(Claim c,Exception error){
        if(!claims.remove(c.device.getAddress(),c))return;
        module.info("Route completion: generation="+c.ticket.generation()+" phase="+c.phase+" playback="+c.playing+" error="+(error==null?"none":error.getMessage()));
        String address=c.device.getAddress();boolean success=error==null&&!c.result.isDone()&&handoff.claiming(c.ticket)&&multiActive();
        HandoffState.Ticket remote=handoff.finishClaim(c.ticket,success);
        if(success){mediaReported.put(address,c.playing);pausedSessions.remove(address,c.paused);routeNotices.remove(address);
            HandoffNotice notice=handoffNotices.get(address);
            if(notice!=null){HandoffNotice.Event event=notice.selectedLocal(audioSources.get(address),SystemClock.elapsedRealtime());if(event!=null)publishHandoffNotice(c.device,event);}
            module.info("Local MEDIA reclaim confirmed: generation="+c.ticket.generation());Bundle result=status(address);result.putBoolean("routeConfirmed",true);result.putBoolean("audioReady",true);c.result.complete(result);
        }else{
            if(error==null)error=new IOException("音频切换已取消");
            c.result.completeExceptionally(error);
            if(remote!=null)yieldAudio(c.device,remote,"切换未完成，音频已让回另一台设备");
            module.info("Route transaction ended: generation="+c.ticket.generation()+" reason="+error.getMessage());
        }
    }
    private boolean mayControl(Context context, int uid) {
        if (uid == module.getModuleApplicationInfo().uid) return true;
        try {
            var app = context.getPackageManager().getApplicationInfo("com.heytap.mydevices", 0);
            return app.uid == uid && (app.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (Exception e) { return false; }
    }
}
