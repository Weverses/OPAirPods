// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import static org.junit.Assert.*;

public class PlaybackSessionsTest {
    @Test public void reconnectDoesNotInheritThePreviousPlayers(){
        PlaybackSessions<String> playback=new PlaybackSessions<>();
        var old=playback.capture("buds");playback.pause(old,()->List.of("music"));
        playback.disconnected("buds");var next=playback.capture("buds");
        assertFalse(playback.current(old));assertTrue(playback.current(next));
        assertTrue(playback.paused(old).isEmpty());assertTrue(playback.paused(next).isEmpty());
    }
    @Test public void anOldQueuedPauseCannotRunOrRepopulateTheNewSession(){
        PlaybackSessions<String> playback=new PlaybackSessions<>();
        var old=playback.capture("buds");
        playback.disconnected("buds");var next=playback.capture("buds");
        AtomicInteger pauses=new AtomicInteger();
        assertTrue(playback.pause(old,()->{pauses.incrementAndGet();return List.of("old music");}).isEmpty());
        assertEquals(0,pauses.get());assertTrue(playback.paused(next).isEmpty());
    }
    @Test public void aClaimHoldingItsOwnPlayerListCannotResumeAfterDisconnect(){
        PlaybackSessions<String> playback=new PlaybackSessions<>();
        var old=playback.capture("buds");
        List<String> captured=playback.pause(old,()->List.of("music"));
        playback.disconnected("buds");playback.capture("buds");
        AtomicInteger plays=new AtomicInteger();
        assertFalse(playback.runIfCurrent(old,()->true,()->plays.addAndGet(captured.size())));
        assertEquals(0,plays.get());
    }
    @Test public void aCancelledClaimCannotSendMediaCommandsInTheSameConnection(){
        PlaybackSessions<String> playback=new PlaybackSessions<>();
        var session=playback.capture("buds");AtomicInteger commands=new AtomicInteger();
        assertFalse(playback.runIfCurrent(session,()->false,commands::incrementAndGet));
        assertEquals(0,commands.get());
        assertTrue(playback.runIfCurrent(session,()->true,commands::incrementAndGet));
        assertEquals(1,commands.get());
    }
    @Test public void oldCompletionCannotEraseNewSessionCredentials(){
        PlaybackSessions<String> playback=new PlaybackSessions<>();
        var old=playback.capture("buds");playback.pause(old,()->List.of("old music"));
        playback.disconnected("buds");var next=playback.capture("buds");
        playback.pause(next,()->List.of("new music"));playback.clear(old);
        assertEquals(List.of("new music"),playback.paused(next));
    }
    @Test public void disconnectingOneDeviceKeepsTheOtherDevicesPlayers(){
        PlaybackSessions<String> playback=new PlaybackSessions<>();
        var first=playback.capture("first");var second=playback.capture("second");
        playback.pause(first,()->List.of("first music"));playback.pause(second,()->List.of("second music"));
        playback.disconnected("first");
        assertEquals(List.of("second music"),playback.paused(second));assertTrue(playback.current(second));
    }
    @Test public void repeatedPauseWithoutNewPlayersKeepsTheCurrentCredentials(){
        PlaybackSessions<String> playback=new PlaybackSessions<>();
        var session=playback.capture("buds");playback.pause(session,()->List.of("music"));
        assertTrue(playback.pause(session,List::of).isEmpty());
        assertEquals(List.of("music"),playback.paused(session));
        playback.clear(session);assertTrue(playback.paused(session).isEmpty());
    }
    @Test public void disconnectWaitsForAnInFlightPauseThenInvalidatesItsResult()throws Exception{
        PlaybackSessions<String> playback=new PlaybackSessions<>();
        var session=playback.capture("buds");
        var workers=Executors.newFixedThreadPool(2);
        CountDownLatch pausing=new CountDownLatch(1),release=new CountDownLatch(1),disconnecting=new CountDownLatch(1);
        try{
            var pause=workers.submit(()->playback.pause(session,()->{
                pausing.countDown();
                try{if(!release.await(3,TimeUnit.SECONDS))throw new AssertionError("pause was not released");}
                catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}
                return List.of("music");
            }));
            assertTrue(pausing.await(3,TimeUnit.SECONDS));
            var disconnect=workers.submit(()->{disconnecting.countDown();playback.disconnected("buds");});
            assertTrue(disconnecting.await(3,TimeUnit.SECONDS));
            try{disconnect.get(100,TimeUnit.MILLISECONDS);fail("disconnect passed an in-flight media command");}
            catch(TimeoutException expected){/* The media command must finish before invalidation returns. */}
            release.countDown();pause.get(3,TimeUnit.SECONDS);disconnect.get(3,TimeUnit.SECONDS);
            assertFalse(playback.current(session));
            assertTrue(playback.paused(playback.capture("buds")).isEmpty());
        }finally{release.countDown();workers.shutdownNow();}
    }
}
