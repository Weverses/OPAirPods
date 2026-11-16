// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

/** Original synthesized earcons: rounded directional pops and a clean rising
 * glass chime. Stereo PCM at 48 kHz; final loudness follows media volume. */
final class HeadFeedbackSound {
    static final int RATE=48000;
    enum Cue { LEFT, RIGHT, UP, DOWN, CONFIRM }
    private HeadFeedbackSound(){}

    static short[] pcm(Cue cue){
        return pcm(cue,RATE,false);
    }
    static short[] pcm(Cue cue,int rate,boolean monoOutput){
        if(rate!=RATE&&rate!=16000)throw new IllegalArgumentException("Unsupported feedback sample rate");
        boolean confirm=cue==Cue.CONFIRM;
        int frames=rate*(confirm?230:65)/1000;
        double[] mono=new double[frames];
        double energy=0;
        for(int i=0;i<frames;i++){
            double t=(double)i/rate;
            double value;
            if(confirm){
                value=glass(t,987.77)*0.95+glass(t-0.065,1318.51);
            }else{
                double frequency=cue==Cue.UP?620:cue==Cue.DOWN?440:520;
                value=pop(t,frequency);
            }
            mono[i]=value;energy+=value*value;
        }
        // Keep the accepted timbre, with 50% more main-channel RMS than
        // 0.6.22. Native SCO synthesis uses exactly the same target level.
        double target=confirm?0.10810659:
                cue==Cue.UP?0.11809480:cue==Cue.DOWN?0.11809435:0.11804330;
        double gain=energy==0?0:target*1.5/Math.sqrt(energy/frames);
        boolean left=cue==Cue.LEFT,right=cue==Cue.RIGHT;
        short[] out=new short[frames*(monoOutput?1:2)];
        int delay=rate/4000; // 0.25 ms lateral cue.
        for(int i=0;i<frames;i++){
            double main=mono[i]*gain;
            // A SCO stream is mono. Render its full main signal directly so
            // directional panning cannot halve the level during downmixing.
            if(monoOutput){out[i]=quantize(main);continue;}
            double opposite=i<delay?0:mono[i-delay]*gain*0.08;
            out[i*2]=quantize(right?opposite:main);
            out[i*2+1]=quantize(left?opposite:main);
        }
        return out;
    }
    private static double pop(double t,double f){
        // A very short downward pitch bend and quickly damped partials make
        // a rounded tap, with a soft attack instead of an electronic beep.
        double phase=2*Math.PI*f*(t+0.22*0.006*(1-Math.exp(-t/0.006)));
        double attack=0.5-0.5*Math.cos(Math.PI*Math.min(1,t/0.0018));
        double release=Math.min(1,(0.065-t)/0.008);
        double body=Math.sin(phase)+0.10*Math.exp(-t/0.006)*Math.sin(phase*2.42)
                +0.035*Math.exp(-t/0.004)*Math.sin(phase*3.95);
        return attack*Math.exp(-t/0.018)*release*body;
    }
    private static double glass(double t,double f){
        if(t<0||t>=0.165)return 0;
        double attack=0.5-0.5*Math.cos(Math.PI*Math.min(1,t/0.002));
        double release=Math.min(1,(0.165-t)/0.012);
        double body=Math.exp(-t/0.045)*Math.sin(2*Math.PI*f*t)
                +0.13*Math.exp(-t/0.012)*Math.sin(2*Math.PI*f*2.76*t)
                +0.035*Math.exp(-t/0.007)*Math.sin(2*Math.PI*f*4.07*t);
        return attack*release*body;
    }
    private static short quantize(double value){return (short)Math.round(Math.max(-1,Math.min(1,value))*32767);}
}
