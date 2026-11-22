// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import java.util.List;

/** Remember only the sessions paused by this handoff, for a later explicit reclaim. */
final class MediaPlayback {
    private MediaPlayback(){}
    static List<MediaController> pause(Context context,MediaController preferred){
        try{
            MediaSessionManager manager=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if(manager!=null)for(MediaController controller:manager.getActiveSessions(null)){
                if(!local(controller)||(preferred!=null&&!preferred.getSessionToken().equals(controller.getSessionToken())))continue;
                PlaybackState state=controller.getPlaybackState();
                if(state!=null&&(state.getState()==PlaybackState.STATE_PLAYING||state.getState()==PlaybackState.STATE_BUFFERING)){
                    controller.getTransportControls().pause();return List.of(controller);
                }
            }
        }catch(SecurityException ignored){}
        return List.of();
    }
    static void awaitPaused(List<MediaController> paused,long timeout){
        long until=android.os.SystemClock.elapsedRealtime()+timeout;
        while(android.os.SystemClock.elapsedRealtime()<until){
            boolean pending=false;for(MediaController controller:paused){PlaybackState state=controller.getPlaybackState();if(state!=null&&(state.getState()==PlaybackState.STATE_PLAYING||state.getState()==PlaybackState.STATE_BUFFERING))pending=true;}
            if(!pending)return;android.os.SystemClock.sleep(25);
        }
    }
    static int resume(List<MediaController> paused){
        int resumed=0;for(MediaController controller:paused){PlaybackState state=controller.getPlaybackState();if(state!=null&&state.getState()==PlaybackState.STATE_PAUSED){controller.getTransportControls().play();resumed++;}}return resumed;
    }
    static boolean paused(List<MediaController> controllers){
        for(MediaController c:controllers){PlaybackState s=c.getPlaybackState();if(s!=null&&(s.getState()==PlaybackState.STATE_PLAYING||s.getState()==PlaybackState.STATE_BUFFERING))return false;}return true;
    }
    static void pauseAgain(List<MediaController> controllers){
        for(MediaController c:controllers)if(local(c))c.getTransportControls().pause();
    }
    static boolean local(MediaController controller){
        MediaController.PlaybackInfo info=controller.getPlaybackInfo();return info!=null&&info.getPlaybackType()==MediaController.PlaybackInfo.PLAYBACK_TYPE_LOCAL;
    }
    static boolean resumable(List<MediaController> paused){for(MediaController controller:paused){PlaybackState state=controller.getPlaybackState();if(state!=null&&state.getState()==PlaybackState.STATE_PAUSED)return true;}return false;}
    static boolean playing(Context context){
        try{
            MediaSessionManager manager=(MediaSessionManager)context.getSystemService(Context.MEDIA_SESSION_SERVICE);
            if(manager!=null)for(MediaController c:manager.getActiveSessions(null)){PlaybackState s=c.getPlaybackState();if(local(c)&&s!=null&&s.getState()==PlaybackState.STATE_PLAYING)return true;}
        }catch(SecurityException ignored){}return false;
    }
}
