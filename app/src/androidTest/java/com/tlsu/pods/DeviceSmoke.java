package com.tlsu.pods;
import android.app.Activity;
import android.app.Instrumentation;
import android.bluetooth.BluetoothAdapter;
import android.os.Bundle;
import android.os.SystemClock;

// Run while the device is awake; ColorOS may freeze background instrumentation.
public final class DeviceSmoke extends Instrumentation {
    private boolean attOnly,doRouting,handoffTest,confirmationOnly,automatic,statusOnly;
    private String probe,gameReturnSetting,statusAddress;
    private int watch;
    private int expectedNoticeVersion;
    private final StringBuilder report=new StringBuilder();
    private boolean failed;
    private boolean gameRoute;
    private boolean headMotionTest;
    private boolean spatialStatus;
    @Override public void onCreate(Bundle arguments){super.onCreate(arguments);spatialStatus=arguments!=null && "true".equals(arguments.getString("spatialStatus"));if(arguments!=null){statusAddress=arguments.getString("address");headMotionTest="true".equals(arguments.getString("headMotion"));statusOnly="true".equals(arguments.getString("statusOnly"));attOnly="true".equals(arguments.getString("attOnly"));doRouting="true".equals(arguments.getString("routing"));automatic="true".equals(arguments.getString("automatic"));probe=arguments.getString("probe");gameReturnSetting=arguments.getString("gameReturnSetting");handoffTest="true".equals(arguments.getString("handoff"));confirmationOnly="true".equals(arguments.getString("confirmation"));expectedNoticeVersion=Integer.parseInt(arguments.getString("noticeVersion","0"));watch=Integer.parseInt(arguments.getString("watch","0"));gameRoute="true".equals(arguments.getString("gameRoute"));}start();}
    private void emit(String value){report.append(value).append('\n');Bundle b=new Bundle();b.putString("stream",value+"\n");sendStatus(0,b);}
    @Override public void onStart(){
        try{
            BridgeClient bridge=new BridgeClient(getTargetContext());String address=null;Bundle status=null;String error="";long until=SystemClock.elapsedRealtime()+30_000;
            if(spatialStatus){
                Bundle request=BridgeClient.forDevice(statusAddress);request.putString("action","status");
                Bundle state=bridge.request(BridgeClient.SPATIAL,request);
                emit("spatial version="+state.getInt("version")+" mode="+state.getInt("mode")
                        +" sending="+state.getBoolean("sending")+" reports="+state.getLong("reports")
                        +" yaw="+state.getInt("yaw")+" resets="+state.getInt("resets")+" detail="+state.getString("detail",""));
                if (state.containsKey("callbacks"))
                    emit("input callbacks="+state.getLong("callbacks")+" unbound="+state.getLong("unbound")
                            +" payloads="+state.getLong("payloads")+" poses="+state.getLong("poses")
                            +" lastFlags="+state.getInt("flags")+" lastLength="+state.getInt("length")
                            +" starts="+state.getInt("starts")+" alternate="+state.getBoolean("alternate")
                            +" halControl="+state.getInt("control")
                            +" requestedIntervalUs="+state.getInt("requestedIntervalUs")
                            +" sampleIntervalUs="+state.getLong("sampleIntervalUs")
                            +" bridgeUs="+state.getLong("bridgeUs"));
                if(state.getInt("version")!=1)throw new AssertionError("Spatial backend unavailable");
                return;
            }
            if(statusOnly){
                Bundle diagnostic=new Bundle();diagnostic.putBoolean("gameState",true);
                Bundle base=bridge.request(BridgeClient.STATUS,diagnostic);
                emit("PASS: Binder status response: manager="+base.getBoolean("manager")+" native="+base.getBoolean("native")+" noticeVersion="+base.getInt("handoffNoticeVersion")+" modeNoticeVersion="+base.getInt("listeningModeNoticeVersion"));
                emit("Advanced controls backend: version="+base.getInt("advancedControlsVersion"));
                emit("Head motion backend: version="+base.getInt("headMotionVersion"));
                emit("Game return: version="+base.getInt("gameReturnVersion")+" enabled="+base.getBoolean("autoGameReturn")
                        +" foreground="+base.getBoolean("foregroundGame")+" players="+base.getInt("gamePlayers")
                        +" package="+base.getString("gamePackage","")+" detectionError="+base.getBoolean("gameDetectionError"));
                if(gameReturnSetting!=null){
                    if(base.getInt("gameReturnVersion")<1)throw new AssertionError("Game return backend is not loaded");
                    if(!gameReturnSetting.equals("true")&&!gameReturnSetting.equals("false"))throw new IllegalArgumentException("Expected boolean gameReturnSetting");
                    boolean enabled=Boolean.parseBoolean(gameReturnSetting);Bundle setting=new Bundle();setting.putBoolean("autoGameReturn",enabled);
                    Bundle saved=bridge.request(BridgeClient.MULTIPOINT,setting);
                    if(saved.getBoolean("autoGameReturn")!=enabled||saved.getBoolean("autoMedia")!=base.getBoolean("autoMedia"))throw new AssertionError("Game return setting did not persist independently");
                    emit("PASS: game return setting persisted independently: "+enabled);
                }
                if(expectedNoticeVersion>0&&base.getInt("handoffNoticeVersion")!=expectedNoticeVersion)throw new AssertionError("Expected handoff notice backend version "+expectedNoticeVersion);
                boolean ready=false;
                for(var device:statusAddress==null?BluetoothAdapter.getDefaultAdapter().getBondedDevices():java.util.List.of(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(statusAddress))){
                    Bundle state=bridge.request(BridgeClient.STATUS,BridgeClient.forDevice(device.getAddress()));
                    Bundle values=state.getBundle("controls");
                    emit("Device status: controlReady="+state.getBoolean("controlReady")+" connected="+state.getBoolean("connected")
                            +" acl="+(statusAddress==null?Reflect.call(device,"isConnected",new Class[0]):"not queried")+" controls="+(values==null?0:values.size())
                            +" listeningMode="+state.getInt("listeningMode")+" detail="+state.getString("channelDetail","none"));
                    emit("Game return candidate: pending="+state.getBoolean("gameReturnPending")+" returning="+state.getBoolean("gameReturning"));
                    emit("Advanced controls: EQ known="+state.getBoolean("eqKnown")+" stem active="+state.getBoolean("stemReady")
                            +" EQ confirmation="+state.getInt("eqConfirmation"));
                    Bundle head=state.getBundle("headMotion");
                    if(head!=null)emit("Head availability: available="+head.getBoolean("available")+" enabled="+head.getBoolean("enabled")
                            +" active="+head.getBoolean("active")+" reason="+head.getString("message","")
                            +" remoteOwner="+state.getBoolean("remoteAudioOwner")+" routePhase="+state.getInt("routePhase")
                            +" localSource="+java.util.Objects.equals(state.getString("selfAddress"),state.getString("audioSource"))
                            +" sourceType="+state.getInt("audioSourceType"));
                    ready|=state.getBoolean("controlReady");
                    if(state.getBoolean("controlReady"))emit("Ready device: "+device.getAddress());
                }
                if(!ready)throw new AssertionError("No connected OEM control channel");
                emit(gameReturnSetting==null?"PASS: native control channel available; no settings or routing writes":"PASS: native control channel available; game return setting verified; no routing writes");return;
            }
            emit("Waiting for OEM AirPods channel and initial settings");
            while(address==null&&SystemClock.elapsedRealtime()<until){
                for(var device:statusAddress==null?BluetoothAdapter.getDefaultAdapter().getBondedDevices():java.util.List.of(BluetoothAdapter.getDefaultAdapter().getRemoteDevice(statusAddress)))try{
                    Bundle s=bridge.request(BridgeClient.STATUS,BridgeClient.forDevice(device.getAddress()));Bundle c=s.getBundle("controls");
                    if(confirmationOnly&&s.getInt("controlConfirmationVersion")!=1)throw new AssertionError("New confirmation backend is not loaded");
                    boolean airpods=false;if(automatic&&device.getUuids()!=null)for(var uuid:device.getUuids())if(uuid.toString().equalsIgnoreCase("74ec2172-0bad-4d01-8f77-997b2be0722a"))airpods=true;
                    if(airpods||s.getBoolean("controlReady")&&(headMotionTest||c!=null&&c.getByteArray("17")!=null)){address=device.getAddress();status=s;break;}
                }catch(Exception e){error=e.toString();}
                if(address==null)SystemClock.sleep(250);
            }
            if(address==null)throw new AssertionError("No OEM channel: "+error);
            if(headMotionTest){testHeadMotion(bridge,address,status);return;}
            long[] identity=status.getLongArray("nativeStatus");
            if(identity==null||identity[0]!=1||identity[1]!=1||identity[3]<1)throw new AssertionError("Apple identity not applied");
            emit("PASS: OEM Binder + native AACP channel + Apple SDP identity");
            if(gameRoute){
                Bundle diagnostic=new Bundle();diagnostic.putBoolean("gameState",true);Bundle base=bridge.request(BridgeClient.STATUS,diagnostic);
                if(base.getInt("gameReturnVersion")<5||!base.getBoolean("foregroundGame"))throw new AssertionError("Streaming-aware backend and audible foreground game required");
                Bundle result=bridge.request(BridgeClient.ROUTE,BridgeClient.forDevice(address));
                if(!result.getBoolean("routeConfirmed")||!result.getBoolean("routeOnly")||!result.getBoolean("audioStreaming"))throw new AssertionError("Game route did not confirm actual A2DP streaming");
                emit("PASS: foreground game route restored actual A2DP streaming without player commands");return;
            }
            if(confirmationOnly){testConfirmation(bridge,address,status);return;}
            if(watch>0){
                Bundle b=BridgeClient.forDevice(address);b.putBoolean("audioState",true);Bundle first=bridge.request(BridgeClient.STATUS,b);
                emit("Before watch: AACP="+first.getBoolean("controlReady")+" A2DP="+first.getBoolean("audioReady")+" remoteOwner="+first.getBoolean("remoteAudioOwner")+" sessions="+first.getInt("controlSessions")+" drops="+first.getInt("controlDrops"));
                boolean dropped=false;long untilWatch=SystemClock.elapsedRealtime()+Math.min(600,watch)*1000L;int elapsed=0;
                while(SystemClock.elapsedRealtime()<untilWatch){
                    Bundle observed=bridge.request(BridgeClient.STATUS,BridgeClient.forDevice(address));
                    if(!observed.getBoolean("controlReady")||observed.getInt("controlDrops")!=first.getInt("controlDrops")||observed.getInt("controlSessions")!=first.getInt("controlSessions"))dropped=true;
                    if(elapsed++%10==0)emit("Watch: AACP="+observed.getBoolean("controlReady")+" source="+(observed.getString("selfAddress","").equals(observed.getString("audioSource"))?"local":"peer")+" type="+observed.getInt("audioSourceType")+" phase="+observed.getInt("routePhase")+" sessions="+observed.getInt("controlSessions")+" drops="+observed.getInt("controlDrops"));
                    SystemClock.sleep(1000);
                }
                Bundle end=bridge.request(BridgeClient.STATUS,b);
                if(end.getInt("controlDrops")!=first.getInt("controlDrops")||end.getInt("controlSessions")!=first.getInt("controlSessions"))dropped=true;
                emit("After watch: AACP="+end.getBoolean("controlReady")+" A2DP="+end.getBoolean("audioReady")+" remoteOwner="+end.getBoolean("remoteAudioOwner")+" sessions="+end.getInt("controlSessions")+" drops="+end.getInt("controlDrops")+" sawControlDrop="+dropped);
                if(!end.getBoolean("controlReady")||dropped)throw new AssertionError("Control channel did not remain continuously connected");
                emit("PASS: observed actual control channel over "+watch+" seconds");return;
            }
            if(handoffTest){testHandoff(bridge,address,status);return;}
            if(automatic){
                emit("Automatic route baseline: control="+status.getBoolean("controlReady")+" sessions="+status.getInt("controlSessions")+" drops="+status.getInt("controlDrops"));
                Bundle request=BridgeClient.forDevice(address);request.putBoolean("automatic",true);
                Bundle result=bridge.request(BridgeClient.ROUTE,request);
                if(!result.getBoolean("routeConfirmed")||!result.getBoolean("audioReady")||result.getInt("audioSourceType")!=2||!result.getString("selfAddress","").equals(result.getString("audioSource")))throw new AssertionError("Automatic route did not resume local MEDIA");
                emit("PASS: one automatic route request restored local MEDIA on connected A2DP");return;
            }
            if(probe!=null){probe(bridge,address,status);return;}
            if(!attOnly){
                byte original=status.getBundle("controls").getByteArray("17")[0],temporary=(byte)(original==0?1:0);
                try{Bundle sent=control(bridge,address,temporary);assertDelivery(sent,temporary);emit("PASS: AACP delivery is explicitly distinguished from earbud confirmation");}
                catch(Exception e){failed=true;emit("FAIL: AACP temporary write: "+e);}
                finally{try{control(bridge,address,original);emit("PASS: original press-speed restore sent");}catch(Exception e){failed=true;emit("FAIL: restore: "+e);}}
            }
            for(int handle:new int[]{AttCodec.LOUD_SOUND,AttCodec.TRANSPARENCY,AttCodec.HEARING_AID}){
                try{
                    byte[] value=read(bridge,address,handle);emit("PASS: ATT read handle="+Integer.toHexString(handle)+" bytes="+value.length+" header="+Protocol.hex(value,0,Math.min(4,value.length)));
                    if(handle==AttCodec.LOUD_SOUND){
                        Bundle b=BridgeClient.forDevice(address);b.putInt("handle",handle);b.putString("field","enabled");b.putFloat("value",value[0]);
                        byte[] verified=bridge.request(BridgeClient.ATT_PATCH,b).getByteArray("value");if(verified[0]!=value[0])throw new AssertionError("Same-value write changed original setting");emit("PASS: ATT same-value write and readback; original state retained");
                    }
                }catch(Exception e){failed=true;emit("FAIL: ATT handle="+Integer.toHexString(handle)+": "+e);}
            }
            if(doRouting){
                status=bridge.request(BridgeClient.STATUS,BridgeClient.forDevice(address));String self=status.getString("selfAddress"),target=null;String[] peers=status.getStringArray("peers");
                if(peers!=null)for(String peer:peers)if(!peer.equals(self)){target=peer;break;}
                if(target==null)throw new AssertionError("No second TiPi peer");
                emit("PASS: second device discovered through TiPi");Bundle b=BridgeClient.forDevice(address);b.putString("target",target);
                Bundle result=bridge.request(BridgeClient.ROUTE,b);if(!result.getBoolean("controlReady"))throw new AssertionError("AACP lost during routing");
                emit("Routing: sourceConfirmed="+result.getBoolean("routeConfirmed")+" audioReady="+result.getBoolean("audioReady"));
                if(!result.getBoolean("audioReady"))throw new AssertionError("A2DP not connected");
                emit("PASS: phone audio connected while OEM AACP remains connected");
            }
        }catch(Throwable e){failed=true;emit("FAIL: "+e);}
        finally{Bundle result=new Bundle();result.putString("stream",report.toString());finish(failed?Activity.RESULT_CANCELED:Activity.RESULT_OK,result);}
    }
    private void testHeadMotion(BridgeClient bridge,String address,Bundle baseline)throws Exception{
        if(baseline.getInt("headMotionVersion")<1)throw new AssertionError("Head motion backend is not loaded");
        Bundle head=baseline.getBundle("headMotion");
        if(head==null||!head.getBoolean("available"))throw new AssertionError("Cannot sample: "+(head==null?"missing state":head.getString("message")));
        String owner=java.util.UUID.randomUUID().toString();
        Bundle start=BridgeClient.forDevice(address);start.putString("action","start");start.putString("owner",owner);
        long samples=0;boolean stopped=false;
        try{
            bridge.request(BridgeClient.HEAD_MOTION,start);
            long until=SystemClock.elapsedRealtime()+8000;
            while(SystemClock.elapsedRealtime()<until){
                Bundle request=BridgeClient.forDevice(address);request.putString("headMotionOwner",owner);
                Bundle status=bridge.request(BridgeClient.STATUS,request);head=status.getBundle("headMotion");
                if(head==null)throw new AssertionError("Missing motion state");
                if(!head.getBoolean("testing")||!owner.equals(head.getString("owner")))throw new AssertionError("Wrong motion session");
                samples=head.getLong("samples");
                if(!head.getBoolean("active"))throw new AssertionError("Motion ended: "+head.getString("message"));
                SystemClock.sleep(250);
            }
            if(samples<10)throw new AssertionError("Too few real motion samples: "+samples);
            emit("PASS: received "+samples+" real motion samples in experience mode (no phone actions)");
            // A stale page must not cancel the current page's subscription.
            Bundle stale=new Bundle(start);stale.putString("action","stop");stale.putString("owner",java.util.UUID.randomUUID().toString());
            Bundle retained=bridge.request(BridgeClient.HEAD_MOTION,stale).getBundle("headMotion");
            if(retained==null||!retained.getBoolean("active"))throw new AssertionError("Stale owner stopped the current session");
            emit("PASS: stale experience owner cannot stop current subscription");
        }finally{
            Bundle stop=new Bundle(start);stop.putString("action","stop");
            Bundle after=bridge.request(BridgeClient.HEAD_MOTION,stop);head=after.getBundle("headMotion");
            stopped=head!=null&&!head.getBoolean("active");
            if(!stopped)throw new AssertionError("Experience subscription did not stop");
            if(after.getInt("controlSessions")!=baseline.getInt("controlSessions")
                    ||after.getInt("controlDrops")!=baseline.getInt("controlDrops"))throw new AssertionError("AACP connection changed during experience");
            if(after.getInt("audioSourceType")!=0&&!java.util.Objects.equals(after.getString("selfAddress"),after.getString("audioSource")))
                throw new AssertionError("Feedback left the local headset route");
            if(head.getBoolean("enabled")!=baseline.getBundle("headMotion").getBoolean("enabled")
                    ||head.getBoolean("reversed")!=baseline.getBundle("headMotion").getBoolean("reversed"))throw new AssertionError("Experience changed saved settings");
            emit("PASS: stopped subscription; AACP session, local headset route and saved configuration retained");
        }
    }
    private void testConfirmation(BridgeClient bridge,String address,Bundle status)throws Exception{
        // A same-value write exercises real delivery and confirmation without
        // changing the user's selected press speed or needing restoration.
        byte original=status.getBundle("controls").getByteArray("17")[0];
        Bundle result=control(bridge,address,original);assertDelivery(result,original);
        Bundle update=result.getBundle("controlUpdates").getBundle("17");
        if(update==null)throw new AssertionError("Missing confirmation metadata");
        emit("After write: state="+update.getInt("state"));
        long until=update.getLong("deadline")+2000;
        while(update.getInt("state")==ControlWrite.WAITING&&SystemClock.elapsedRealtime()<until){
            SystemClock.sleep(250);result=bridge.request(BridgeClient.STATUS,BridgeClient.forDevice(address));
            Bundle updates=result.getBundle("controlUpdates");update=updates==null?null:updates.getBundle("17");
            if(update==null)throw new AssertionError("Connection ended during confirmation check");
        }
        int state=update.getInt("state");
        if(state==ControlWrite.WAITING)throw new AssertionError("Confirmation did not leave waiting by its deadline");
        if(result.getBundle("pendingControls").containsKey("17"))throw new AssertionError("Terminal result still marked pending");
        if(state==ControlWrite.CONFIRMED&&!Protocol.matches(result.getBundle("controls").getByteArray("17"),new byte[]{original}))throw new AssertionError("Confirmation differs from actual report");
        emit("PASS: same-value write reached bounded result state="+state+"; pending marker cleared");
        Bundle refresh=BridgeClient.forDevice(address);refresh.putBoolean("refreshControls",true);
        bridge.request(BridgeClient.STATUS,refresh);emit("PASS: explicit state refresh accepted on native AACP");
    }
    private void testHandoff(BridgeClient bridge,String address,Bundle status)throws Exception{
        String self=status.getString("selfAddress"),target=null;String[] peers=status.getStringArray("peers");
        boolean selfListed=false;if(peers!=null)for(String peer:peers){if(peer.equals(self))selfListed=true;else if(target==null)target=peer;}
        emit("TiPi: records="+(peers==null?0:peers.length)+" selfListed="+selfListed);
        if(target==null)throw new AssertionError("No second peer for handoff test");
        Bundle route=BridgeClient.forDevice(address);route.putString("target",target);Bundle routed=bridge.request(BridgeClient.ROUTE,route);
        if(!routed.getBoolean("audioReady"))throw new AssertionError("Phone audio unavailable before handoff");
        java.util.concurrent.CountDownLatch paused=new java.util.concurrent.CountDownLatch(1);
        android.media.AudioTrack track=new android.media.AudioTrack.Builder()
            .setAudioAttributes(new android.media.AudioAttributes.Builder().setUsage(android.media.AudioAttributes.USAGE_MEDIA).setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(new android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_STEREO).build())
            .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(48000*4).build();
        android.media.session.MediaSession session=new android.media.session.MediaSession(getTargetContext(),"ColorPodsHandoffSmoke");
        try{
            session.setFlags(android.media.session.MediaSession.FLAG_HANDLES_MEDIA_BUTTONS|android.media.session.MediaSession.FLAG_HANDLES_TRANSPORT_CONTROLS);
            session.setCallback(new android.media.session.MediaSession.Callback(){@Override public void onPause(){track.pause();session.setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(android.media.session.PlaybackState.ACTION_PLAY).setState(android.media.session.PlaybackState.STATE_PAUSED,0,0).build());paused.countDown();}},new android.os.Handler(android.os.Looper.getMainLooper()));
            session.setPlaybackState(new android.media.session.PlaybackState.Builder().setActions(android.media.session.PlaybackState.ACTION_PAUSE|android.media.session.PlaybackState.ACTION_PLAY).setState(android.media.session.PlaybackState.STATE_PLAYING,0,1).build());session.setActive(true);
            track.write(new byte[48000*4],0,48000*4);track.setLoopPoints(0,48000,-1);track.play();
            emit("READY_FOR_MAC: silent Android media session is playing");
            if(!paused.await(30,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Android media session did not receive pause during remote handoff");
            emit("PASS: Android media session received PAUSE after remote handoff");
            boolean dropped=false,recovered=false;long until=SystemClock.elapsedRealtime()+70_000;
            while(SystemClock.elapsedRealtime()<until){
                Bundle state=bridge.request(BridgeClient.STATUS,BridgeClient.forDevice(address));boolean ready=state.getBoolean("controlReady");
                if(!ready&&!dropped){dropped=true;emit("OBSERVED: control channel dropped after handoff");}
                if(dropped&&ready&&!recovered){recovered=true;emit("OBSERVED: native control channel recovered");}
                SystemClock.sleep(1000);
            }
            Bundle end=bridge.request(BridgeClient.STATUS,BridgeClient.forDevice(address));
            if(!end.getBoolean("controlReady"))throw new AssertionError("Control channel unavailable after 70s");
            emit(dropped?"PASS: control available after 70s with recovery":"PASS: control remained connected throughout 70s after handoff");
        }finally{session.release();try{track.stop();}catch(IllegalStateException ignored){}track.release();}
    }
    private void probe(BridgeClient bridge,String address,Bundle status)throws Exception{
        var saved=getTargetContext().getSharedPreferences("device_smoke_probe",0);int current=status.getBundle("controls").getByteArray("17")[0]&255;
        if(probe.equals("set")){
            if(saved.contains("original"))throw new IllegalStateException("Previous probe needs restoring");
            int temporary=current==0?1:0;saved.edit().putInt("original",current).putInt("temporary",temporary).commit();
            Bundle sent=control(bridge,address,(byte)temporary);assertDelivery(sent,(byte)temporary);emit("PASS: probe sent; reconnect required for independent readback");
        }else if(probe.equals("verify")){
            if(!saved.contains("original"))throw new IllegalStateException("No saved original");
            try{if(current!=saved.getInt("temporary",-1))throw new AssertionError("Fresh readback differs from temporary setting: "+current);emit("PASS: AACP change independently verified after Bluetooth restart");}
            finally{control(bridge,address,(byte)saved.getInt("original",0));emit("Original press speed restore sent");}
        }else if(probe.equals("restored")){
            if(current!=saved.getInt("original",-1))throw new AssertionError("Original setting not restored: "+current);
            saved.edit().clear().commit();emit("PASS: original press speed independently verified after restart");
        }else throw new IllegalArgumentException("Unknown probe phase");
    }
    private void assertDelivery(Bundle result,byte expected){
        if(result.getBoolean("controlConfirmed"))return;
        Bundle updates=result.getBundle("controlUpdates");Bundle update=updates==null?null:updates.getBundle("17");
        byte[] sent=update==null?null:update.getByteArray("expected");
        if(sent==null||sent[0]!=expected)throw new AssertionError("Unconfirmed request must be separate from reported controls");
    }
    private Bundle control(BridgeClient client,String address,byte value)throws Exception{Bundle b=BridgeClient.forDevice(address);b.putInt("command",0x17);b.putByteArray("value",new byte[]{value});return client.request(BridgeClient.CONTROL,b);}
    private byte[] read(BridgeClient client,String address,int handle)throws Exception{Bundle b=BridgeClient.forDevice(address);b.putInt("handle",handle);return client.request(BridgeClient.ATT_READ,b).getByteArray("value");}
}
