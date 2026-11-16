// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Exports the production PCM as a stereo WAV: left, right, confirmation. */
public final class HeadFeedbackPreview {
    public static void main(String[] args)throws Exception{
        ByteArrayOutputStream data=new ByteArrayOutputStream();
        for(var cue:new HeadFeedbackSound.Cue[]{HeadFeedbackSound.Cue.LEFT,HeadFeedbackSound.Cue.RIGHT,HeadFeedbackSound.Cue.CONFIRM}){
            data.write(new byte[HeadFeedbackSound.RATE*4/3]);
            for(short sample:HeadFeedbackSound.pcm(cue)){data.write(sample&255);data.write((sample>>>8)&255);}
        }
        byte[] pcm=data.toByteArray();
        ByteBuffer header=ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R','I','F','F'}).putInt(36+pcm.length).put(new byte[]{'W','A','V','E','f','m','t',' '})
                .putInt(16).putShort((short)1).putShort((short)2).putInt(HeadFeedbackSound.RATE).putInt(HeadFeedbackSound.RATE*4)
                .putShort((short)4).putShort((short)16).put(new byte[]{'d','a','t','a'}).putInt(pcm.length);
        ByteArrayOutputStream wav=new ByteArrayOutputStream();wav.write(header.array());wav.write(pcm);
        Files.write(Path.of(args[0]),wav.toByteArray());
    }
}
