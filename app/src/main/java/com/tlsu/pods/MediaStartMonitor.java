// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;
import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.AudioPlaybackConfiguration;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import java.util.*;

/** Observe actual player state transitions, including players that keep a silent
 * AudioTrack running while paused. Audio callbacks cover players without sessions. */
final class MediaStartMonitor {
    record Start(MediaController player,int uid,Set<Integer> players,boolean gameUsage){
        Start {players=Set.copyOf(players);}
    }
    private record AudioPlayers(Set<Integer> players,boolean gameUsage){}
    private record Watch(MediaController controller,MediaController.Callback callback){}
    private final Map<MediaSession.Token,Watch> watches=new HashMap<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final java.util.function.Consumer<Start> started;
    private final Context context;
    private final Runnable changed;
    private Set<Integer> activeUids=Set.of();
    private boolean initialized,sessionsReady,configWarning;
    private final java.util.function.Consumer<String> log;
    MediaStartMonitor(Context context,int moduleUid,java.util.function.Consumer<Start> started,Runnable changed,java.util.function.Consumer<String> log){
        this.context=context;this.started=started;this.changed=changed;this.log=log;
        MediaSessionManager sessions=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
        try{if(sessions!=null){sessions.addOnActiveSessionsChangedListener(this::sessions,null,handler);sessions(sessions.getActiveSessions(null));sessionsReady=true;log.accept("Media session monitor ready: "+watches.size()+" sessions");}}catch(SecurityException e){log.accept("Media session monitor unavailable: "+e.getMessage());}
        AudioManager audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);
        if(audio!=null){try{activeUids=Set.copyOf(mediaPlayers(audio.getActivePlaybackConfigurations()).keySet());}catch(SecurityException e){log.accept("Audio baseline unavailable: "+e.getMessage());}initialized=true;}
        if(audio!=null)audio.registerAudioPlaybackCallback(new AudioManager.AudioPlaybackCallback(){
            @Override public void onPlaybackConfigChanged(List<AudioPlaybackConfiguration> configs){
                Map<Integer,AudioPlayers> now=mediaPlayers(configs);Set<Integer> previous=activeUids;
                boolean ready=initialized;activeUids=Set.copyOf(now.keySet());initialized=true;
                if(ready)for(var entry:now.entrySet()){
                    int uid=entry.getKey();if(previous.contains(uid)||uid==moduleUid||uid==android.os.Process.myUid())continue;
                    AudioPlayers audio=entry.getValue();
                    log.accept("Local media start: audio uid="+uid+" gameUsage="+audio.gameUsage()+" players="+audio.players().size());
                    started.accept(new Start(null,uid,audio.players(),audio.gameUsage()));
                }
                changed.run();
            }
        },handler);
    }
    private void sessions(List<MediaController> controllers){
        if(controllers==null)controllers=List.of();Set<MediaSession.Token> present=new HashSet<>();for(var c:controllers)present.add(c.getSessionToken());
        for(var iterator=watches.entrySet().iterator();iterator.hasNext();){var entry=iterator.next();if(!present.contains(entry.getKey())){entry.getValue().controller().unregisterCallback(entry.getValue().callback());iterator.remove();}}
        for(MediaController controller:controllers){
            if(watches.containsKey(controller.getSessionToken())||controller.getPackageName().equals("com.tlsu.pods")||!MediaPlayback.local(controller))continue;
            MediaController.Callback callback=new MediaController.Callback(){
                private boolean playing=isPlaying(controller.getPlaybackState());
                @Override public void onPlaybackStateChanged(PlaybackState state){boolean now=isPlaying(state);boolean fresh=now&&!playing;if(playing&&!now)log.accept("Local media stopped: session "+controller.getPackageName()+" state="+(state==null?-1:state.getState()));playing=now;if(fresh){log.accept("Local media start: session "+controller.getPackageName());sessionStarted(controller);}changed.run();}
            };
            watches.put(controller.getSessionToken(),new Watch(controller,callback));controller.registerCallback(callback,handler);
            if(sessionsReady&&isPlaying(controller.getPlaybackState())){log.accept("Local media start: new playing session "+controller.getPackageName());sessionStarted(controller);changed.run();}
        }
    }
    void recheckPlaying(){handler.post(()->{for(Watch watch:watches.values())if(isPlaying(watch.controller().getPlaybackState()))sessionStarted(watch.controller());});}
    private void sessionStarted(MediaController player){
        int uid=-1;try{uid=context.getPackageManager().getApplicationInfo(player.getPackageName(),0).uid;}catch(android.content.pm.PackageManager.NameNotFoundException ignored){}
        started.accept(new Start(player,uid,Set.of(),false));
    }
    private Map<Integer,AudioPlayers> mediaPlayers(List<AudioPlaybackConfiguration> configs){
        Map<Integer,AudioPlayers> result=new HashMap<>();for(var config:configs)try{
            int usage=config.getAudioAttributes().getUsage();if(usage!=AudioAttributes.USAGE_MEDIA&&usage!=AudioAttributes.USAGE_GAME)continue;
            boolean active;try{active=Boolean.TRUE.equals(Reflect.call(config,"isActive",new Class[0]));}catch(NoSuchMethodException e){active=((Integer)Reflect.call(config,"getPlayerState",new Class[0]))==2;}
            if(active){
                int uid=(Integer)Reflect.call(config,"getClientUid",new Class[0]);if(uid<0)continue;
                int id=(Integer)Reflect.call(config,"getPlayerInterfaceId",new Class[0]);if(id<0)continue;
                AudioPlayers previous=result.get(uid);Set<Integer> players=new HashSet<>(previous==null?Set.of():previous.players());players.add(id);
                result.put(uid,new AudioPlayers(players,usage==AudioAttributes.USAGE_GAME||previous!=null&&previous.gameUsage()));
            }
        }catch(Exception e){if(!configWarning){configWarning=true;log.accept("Audio configuration monitor unavailable: "+e);}}
        return result;
    }
    private static boolean isPlaying(PlaybackState state){return state!=null&&state.getState()==PlaybackState.STATE_PLAYING;}
}
