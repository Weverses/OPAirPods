// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRouting;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Short, bounded feedback on the current earbud route. A warm track prevents
 * Bluetooth startup and immediate flushes from swallowing brief movement cues. */
final class HeadFeedbackAudio {
    private record Request(String address,boolean call,HeadFeedbackSound.Cue cue,long expires,BooleanSupplier valid,Runnable completed){}
    private record Target(AudioDeviceInfo device,HeadFeedbackRoute.Format format){}
    private final ColorPodsModule module;
    private volatile Context context;
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor(r->{Thread t=new Thread(r,"ColorPodsHeadAudio");t.setDaemon(true);return t;});
    private final AtomicReference<Request> movement=new AtomicReference<>(),confirmation=new AtomicReference<>(),preparation=new AtomicReference<>();
    private static final BooleanSupplier STOPPED=()->false;
    private final AtomicReference<BooleanSupplier> keepWarm=new AtomicReference<>(STOPPED);
    private final AtomicBoolean draining=new AtomicBoolean();
    private final Handler main=new Handler(Looper.getMainLooper());
    private Player player; // Audio worker only.
    HeadFeedbackAudio(ColorPodsModule module){this.module=module;}
    void attach(Context context){this.context=context;}

    void prepare(String address,boolean call,BooleanSupplier valid){
        keepWarm.set(valid);
        preparation.set(new Request(address,call,null,SystemClock.elapsedRealtime()+1500,valid,()->{}));wake();
    }

    void direction(String address,boolean call,HeadMovementFeedback.Direction direction,BooleanSupplier valid){
        if(direction==HeadMovementFeedback.Direction.NONE)return;
        movement.set(new Request(address,call,HeadFeedbackSound.Cue.valueOf(direction.name()),SystemClock.elapsedRealtime()+700,valid,()->{}));wake();
    }
    void confirm(String address,boolean call,BooleanSupplier valid,Runnable completed){
        movement.set(null);
        Request old=confirmation.getAndSet(new Request(address,call,HeadFeedbackSound.Cue.CONFIRM,SystemClock.elapsedRealtime()+1500,valid,completed));
        if(old!=null)old.completed.run();wake();
    }
    private void wake(){if(draining.compareAndSet(false,true))worker.execute(this::drain);}
    private void drain(){
        Request last=null;
        long idleUntil=0;
        try{
            while(true){
                Request r=confirmation.getAndSet(null);
                if(r==null)r=movement.getAndSet(null);
                if(r==null)r=preparation.getAndSet(null);
                if(r!=null){
                    try{play(r);}catch(Exception failure){closePlayer();module.info("Head feedback audio unavailable: "+failure.getClass().getSimpleName()+": "+failure.getMessage());}
                    finally{r.completed.run();}
                    last=r;idleUntil=SystemClock.elapsedRealtime()+1000;
                }else if(player!=null&&last!=null&&player.routed()
                        &&(keepWarm.get().getAsBoolean()||last.valid.getAsBoolean()&&SystemClock.elapsedRealtime()<idleUntil)){
                    // Keep the already selected output warm for the next turn.
                    if(!player.write(player.silence))closePlayer();
                }else break;
            }
        }finally{
            closePlayer();draining.set(false);
            BooleanSupplier scope=keepWarm.get();
            if(!scope.getAsBoolean())keepWarm.compareAndSet(scope,STOPPED);
            if(confirmation.get()!=null||movement.get()!=null||preparation.get()!=null)wake();
        }
    }
    private boolean valid(Request r){return SystemClock.elapsedRealtime()<r.expires&&r.valid.getAsBoolean();}
    private void play(Request r)throws Exception{
        Context c=context;if(c==null||!valid(r))return;
        AudioManager audio=c.getSystemService(AudioManager.class);if(audio==null)return;
        boolean confirm=r.cue==HeadFeedbackSound.Cue.CONFIRM;
        // Ringtone, SCO setup and hang-up can change both device and strategy.
        // Re-resolve while priming, before submitting any audible samples.
        while(valid(r)){
            if(r.cue==null&&(confirmation.get()!=null||movement.get()!=null))return;
            Target target=output(audio,r.address,r.call);
            if(target==null){
                closePlayer();if(!r.call&&!confirm)return;Thread.sleep(20);continue;
            }
            if(player!=null&&(player.device.getId()!=target.device.getId()
                    ||!player.format.equals(target.format)||player.lost.get()))closePlayer();
            if(player==null)player=new Player(target,r.valid);
            player.current=r.valid;
            if(player.routed()&&player.track.getPlaybackHeadPosition()>0)break;
            if(!player.write(player.silence)){closePlayer();if(!r.call)return;}
            Thread.sleep(5);
        }
        Player p=player;
        if(!valid(r)||p==null||!p.routed()){
            if(r.call&&r.valid.getAsBoolean())module.info("Head feedback skipped: call route not ready, cue="+r.cue+" mode="+audio.getMode());
            return;
        }
        p.tune();
        if(r.cue==null)return;
        long startDelay=SystemClock.elapsedRealtime()-(r.expires-(confirm?1500:700));
        p.audible.set(true);
        short[] pcm=HeadFeedbackSound.pcm(r.cue,p.format.rate(),p.format.channels()==1);
        for(int offset=0;offset<pcm.length;){
            if(!r.valid.getAsBoolean()||!p.routed())return;
            int count=Math.min(p.silence.length,pcm.length-offset);
            if(!p.write(pcm,offset,count))return;offset+=count;
        }
        long endFrame=p.queuedFrames;
        // AudioTrack.write only queues PCM. Keep feeding silence until the
        // mixer consumes the cue, then allow a tail for the Bluetooth pipeline.
        long deadline=SystemClock.elapsedRealtime()+800;
        while(r.valid.getAsBoolean()&&p.routed()&&SystemClock.elapsedRealtime()<deadline
                &&Integer.toUnsignedLong(p.track.getPlaybackHeadPosition())<endFrame){
            if(!p.write(p.silence))return;
        }
        if(!r.valid.getAsBoolean()||!p.routed()
                ||Integer.toUnsignedLong(p.track.getPlaybackHeadPosition())<endFrame){
            closePlayer();return;
        }
        for(int i=0;i<16&&r.valid.getAsBoolean()&&p.routed();i++)if(!p.write(p.silence))return;
        if(!p.logged||confirm){
            p.logged=true;
            module.info("Head feedback played: "+r.cue+" route="+p.device.getType()
                    +" scope="+(r.call?"call":"experience")
                    +" volume="+audio.getStreamVolume(p.format.communication()?AudioManager.STREAM_VOICE_CALL:AudioManager.STREAM_MUSIC)
                    +" startDelayMs="+startDelay);
        }
    }
    private void closePlayer(){if(player!=null){player.close();player=null;}}
    private final class Player {
        final AudioDeviceInfo device;
        final HeadFeedbackRoute.Format format;
        final AudioTrack track;
        final short[] silence;
        final AtomicBoolean audible=new AtomicBoolean(),alive=new AtomicBoolean(true),lost=new AtomicBoolean();
        final AudioRouting.OnRoutingChangedListener listener;
        long queuedFrames;
        boolean logged,tuned;
        BooleanSupplier current;
        Player(Target target,BooleanSupplier valid){
            device=target.device;format=target.format;current=valid;
            silence=new short[format.rate()/100*format.channels()];
            int channels=format.channels()==1?AudioFormat.CHANNEL_OUT_MONO:AudioFormat.CHANNEL_OUT_STEREO;
            int minimum=AudioTrack.getMinBufferSize(format.rate(),channels,AudioFormat.ENCODING_PCM_16BIT);
            if(minimum<=0)throw new IllegalStateException("No stereo PCM output");
            track=new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(format.communication()?AudioAttributes.USAGE_VOICE_COMMUNICATION:AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(format.rate())
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(channels).build())
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setBufferSizeInBytes(Math.max(minimum,format.rate()/50*format.channels()*2)).build();
            listener=router->{
                try{if(alive.get()&&audible.get()&&!routed()){
                    lost.set(true);track.setVolume(0);track.pause();
                }}catch(IllegalStateException ignored){}
            };
            try{
                if(track.getState()!=AudioTrack.STATE_INITIALIZED||!track.setPreferredDevice(device))
                    throw new IllegalStateException("Earbud output unavailable");
                track.addOnRoutingChangedListener(listener,main);
                // Default A2DP buffers can exceed 350 ms on ColorOS. Request a
                // 20 ms client buffer; the platform may clamp to its own floor.
                track.setBufferSizeInFrames(format.rate()/50);
                track.setStartThresholdInFrames(Math.min(format.rate()/100,track.getBufferSizeInFrames()));
                if(!write(silence))throw new IllegalStateException("Cannot prime feedback output");
                track.play();
            }catch(RuntimeException failure){close();throw failure;}
        }
        void tune(){
            // Routing can enlarge the buffer after construction; apply the
            // limit again on the actual Bluetooth sink.
            track.setBufferSizeInFrames(format.rate()/50);
            track.setStartThresholdInFrames(Math.min(format.rate()/100,track.getBufferSizeInFrames()));
            if(!tuned){tuned=true;module.info("Head feedback ready: bufferFrames="+track.getBufferSizeInFrames()
                    +" performanceMode="+track.getPerformanceMode()+" sampleRate="+format.rate()
                    +" channels="+format.channels()+" communication="+format.communication());}
        }
        boolean write(short[] samples){
            return write(samples,0,samples.length);
        }
        boolean write(short[] samples,int offset,int count){
            int end=offset+count;
            long until=SystemClock.elapsedRealtime()+500;
            while(offset<end){
                if(!alive.get()||lost.get()||!current.getAsBoolean()||SystemClock.elapsedRealtime()>=until)return false;
                int n=track.write(samples,offset,end-offset,AudioTrack.WRITE_NON_BLOCKING);
                if(n<0)return false;
                if(n==0){SystemClock.sleep(2);continue;}
                offset+=n;queuedFrames+=n/format.channels();
            }
            return true;
        }
        boolean routed(){return !lost.get()&&HeadFeedbackAudio.routed(track,device);}
        void close(){
            alive.set(false);audible.set(false);track.removeOnRoutingChangedListener(listener);
            try{track.pause();track.flush();}catch(IllegalStateException ignored){}track.release();
        }
    }
    private static Target output(AudioManager audio,String address,boolean call){
        var devices=java.util.Arrays.asList(audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS));
        int mode=audio.getMode();
        var state=mode==AudioManager.MODE_IN_CALL||mode==AudioManager.MODE_IN_COMMUNICATION?HeadFeedbackRoute.AudioState.VOICE:
                mode==AudioManager.MODE_RINGTONE?HeadFeedbackRoute.AudioState.RINGING:HeadFeedbackRoute.AudioState.IDLE;
        var selected=HeadFeedbackRoute.select(address,call,state,endpoint(audio.getCommunicationDevice()),
                devices.stream().map(HeadFeedbackAudio::endpoint).collect(java.util.stream.Collectors.toList()));
        if(selected==null)return null;
        for(AudioDeviceInfo device:devices)if(device.getId()==selected.id())
            return new Target(device,HeadFeedbackRoute.format(selected,call,state));
        return null;
    }
    private static HeadFeedbackRoute.Endpoint endpoint(AudioDeviceInfo device){
        if(device==null)return null;
        var kind=switch(device.getType()){
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP->HeadFeedbackRoute.Kind.A2DP;
            case AudioDeviceInfo.TYPE_BLE_HEADSET->HeadFeedbackRoute.Kind.BLE;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO->HeadFeedbackRoute.Kind.SCO;
            default->HeadFeedbackRoute.Kind.OTHER;
        };
        return new HeadFeedbackRoute.Endpoint(device.getId(),device.getAddress(),kind);
    }
    private static boolean matches(AudioDeviceInfo device,String address){
        if(device==null)return false;
        int type=device.getType();
        return targetAddress(address,device.getAddress(),type==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                ||type==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||type==AudioDeviceInfo.TYPE_BLE_HEADSET);
    }
    static boolean targetAddress(String expected,String actual,boolean bluetooth){
        return bluetooth&&expected!=null&&!expected.isEmpty()&&expected.equalsIgnoreCase(actual);
    }
    private static boolean routed(AudioTrack track,AudioDeviceInfo preferred){
        if(Build.VERSION.SDK_INT>=36){
            var devices=track.getRoutedDevices();
            return !devices.isEmpty()&&devices.stream().allMatch(d->d.getId()==preferred.getId()&&matches(d,preferred.getAddress()));
        }
        AudioDeviceInfo device=track.getRoutedDevice();
        return device!=null&&device.getId()==preferred.getId()&&matches(device,preferred.getAddress());
    }
}
