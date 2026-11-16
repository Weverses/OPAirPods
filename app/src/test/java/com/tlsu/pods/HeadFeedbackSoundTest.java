// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import org.junit.Test;
import static org.junit.Assert.*;

public class HeadFeedbackSoundTest {
    private double energy(short[] pcm,int channel){double total=0;for(int i=channel;i<pcm.length;i+=2)total+=(double)pcm[i]*pcm[i];return total;}
    @Test public void leftAndRightAreMirroredStereoSignals(){
        short[] left=HeadFeedbackSound.pcm(HeadFeedbackSound.Cue.LEFT),right=HeadFeedbackSound.pcm(HeadFeedbackSound.Cue.RIGHT);
        assertTrue(energy(left,0)>energy(left,1)*100);assertTrue(energy(right,1)>energy(right,0)*100);
        for(int i=0;i<left.length;i+=2){assertEquals(left[i],right[i+1]);assertEquals(left[i+1],right[i]);}
    }
    @Test public void upDownAndConfirmationAreCentered(){
        for(var cue:new HeadFeedbackSound.Cue[]{HeadFeedbackSound.Cue.UP,HeadFeedbackSound.Cue.DOWN,HeadFeedbackSound.Cue.CONFIRM}){
            short[] pcm=HeadFeedbackSound.pcm(cue);for(int i=0;i<pcm.length;i+=2)assertEquals(pcm[i],pcm[i+1]);
        }
    }
    @Test public void successIsLongerAndDifferentFromMovement(){
        short[] motion=HeadFeedbackSound.pcm(HeadFeedbackSound.Cue.UP),success=HeadFeedbackSound.pcm(HeadFeedbackSound.Cue.CONFIRM);
        assertTrue(success.length>motion.length*3);assertFalse(java.util.Arrays.equals(motion,success));
        assertTrue(energy(success,0)>0);
    }
    @Test public void envelopesStartAtZeroAndNeverClip(){
        for(var cue:HeadFeedbackSound.Cue.values()){
            short[] pcm=HeadFeedbackSound.pcm(cue);assertEquals(0,pcm[0]);assertEquals(0,pcm[1]);
            for(short sample:pcm)assertTrue(Math.abs((int)sample)<24000);
        }
    }
    @Test public void phoneAndOtherEarbudsCannotBeAudioFallback(){
        String target="AA:BB:CC:DD:EE:FF";
        assertTrue(HeadFeedbackAudio.targetAddress(target,target.toLowerCase(java.util.Locale.ROOT),true));
        assertFalse(HeadFeedbackAudio.targetAddress(target,target,false));
        assertFalse(HeadFeedbackAudio.targetAddress(target,"11:22:33:44:55:66",true));
        assertFalse(HeadFeedbackAudio.targetAddress(target,null,true));
        assertFalse(HeadFeedbackAudio.targetAddress("","",true));
    }
    @Test public void mainChannelsHaveEnoughEnergyForShortFeedback(){
        for(var cue:HeadFeedbackSound.Cue.values()){
            short[] pcm=HeadFeedbackSound.pcm(cue);
            int main=cue==HeadFeedbackSound.Cue.RIGHT?1:0;
            double rms=Math.sqrt(energy(pcm,main)/(pcm.length/2));
            assertTrue("Short cue must not become inaudibly faint: "+cue,rms>=1800);
        }
    }
    @Test public void allCuesAreFiftyPercentAbove0622(){
        double[] previous={0.11804330,0.11804330,0.11809480,0.11809435,0.10810659};
        for(var cue:HeadFeedbackSound.Cue.values()){
            short[] pcm=HeadFeedbackSound.pcm(cue);
            int channel=cue==HeadFeedbackSound.Cue.RIGHT?1:0;
            double rms=Math.sqrt(energy(pcm,channel)/(pcm.length/2))/32767;
            assertEquals(previous[cue.ordinal()]*1.5,rms,0.00001);
        }
    }
    @Test public void scoUsesNativeMonoWithoutLosingThePannedChannelsLevel(){
        for(var cue:HeadFeedbackSound.Cue.values()){
            short[] stereo=HeadFeedbackSound.pcm(cue),mono=HeadFeedbackSound.pcm(cue,16000,true);
            assertEquals(16000*(cue==HeadFeedbackSound.Cue.CONFIRM?230:65)/1000,mono.length);
            double expected=Math.sqrt(energy(stereo,cue==HeadFeedbackSound.Cue.RIGHT?1:0)/(stereo.length/2));
            double total=0;for(short value:mono){total+=(double)value*value;assertTrue(Math.abs((int)value)<24000);}
            assertEquals(expected,Math.sqrt(total/mono.length),1.0);
        }
    }
}
