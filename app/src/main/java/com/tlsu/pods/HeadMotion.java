// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.content.Context;
import android.os.Bundle;
import android.os.SystemClock;
import android.telecom.Call;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single short-lived motion subscription; never claims audio ownership.
 * The OEM reader only copies validated samples into a bounded queue. */
final class HeadMotion {
    interface Transport {
        Iterable<String> addresses();
        Object connection(String address);
        String blocked(String address, Object connection);
        void send(String address, Object connection, boolean start, boolean alternate) throws Exception;
    }
    private final ColorPodsModule module;
    private final Transport transport;
    final HeadMotionCalls calls;
    private final HeadFeedbackAudio audio;
    private volatile Context context;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ColorPodsHeadMotion"); t.setDaemon(true); return t;
    });
    private final AtomicBoolean scheduled = new AtomicBoolean();
    private final Map<String, Bundle> views = new ConcurrentHashMap<>();
    private volatile Session active;
    private Call attempted;

    private static final class Session {
        final HeadMotionLease lease;
        final HeadGestureDetector detector = new HeadGestureDetector();
        final HeadMovementFeedback movement = new HeadMovementFeedback();
        final ArrayBlockingQueue<HeadMotionCodec.Sample> samples = new ArrayBlockingQueue<>(128);
        volatile long heartbeat;
        boolean alternate = true, retry, tracking;
        volatile boolean actionPending;
        volatile boolean overrun;
        int diagnosticFrames;
        final ArrayBlockingQueue<byte[]> diagnostics = new ArrayBlockingQueue<>(3);
        long phaseAt, lastSample, count, gestureAt, rangeAt;
        int minH=Integer.MAX_VALUE,maxH=Integer.MIN_VALUE,minV=Integer.MAX_VALUE,maxV=Integer.MIN_VALUE;
        int gesture;
        String message = "正在等待耳机运动数据";
        Session(HeadMotionLease lease, long now) {
            this.lease = lease; phaseAt = heartbeat = now;
        }
    }

    HeadMotion(ColorPodsModule module, Transport transport) {
        this.module = module; this.transport = transport;
        calls = new HeadMotionCalls(module, this::changed);
        audio = new HeadFeedbackAudio(module);
    }
    void attach(Context context) { this.context = context; audio.attach(context); }
    private String key(String address, String setting) { return "headMotion." + address + "." + setting; }
    boolean enabled(String address) {
        return context != null && context.getSharedPreferences("colorpods", 0).getBoolean(key(address, "enabled"), false);
    }
    boolean reversed(String address) {
        return context != null && context.getSharedPreferences("colorpods", 0).getBoolean(key(address, "reversed"), false);
    }
    void configure(String address, String field, boolean value) throws Exception {
        if (!field.equals("enabled") && !field.equals("reversed")) throw new IllegalArgumentException("未知头部操控设置");
        if (!context.getSharedPreferences("colorpods", 0).edit().putBoolean(key(address, field), value).commit())
            throw new IOException("无法保存头部操控设置");
        Session current = active;
        boolean cancel = current != null && current.lease.address.equals(address);
        if (cancel) current.lease.cancel();
        worker.execute(() -> {
            if (cancel) finish(current, "设置已更改，请重新开始");
            changed();
        });
    }

    void test(String address, String owner, boolean start) throws Exception {
        if (owner == null || owner.length() < 16 || owner.length() > 80) throw new IllegalArgumentException("体验会话无效");
        AtomicBoolean cancelled = new AtomicBoolean();
        var task = worker.submit(() -> {
            if (cancelled.get()) return null;
            if (!start) {
                Session s = active;
                if (s != null && s.lease.call == null && s.lease.address.equals(address) && s.lease.owner.equals(owner))
                    finish(s, "体验已停止");
                return null;
            }
            if (calls.busy()) throw new IOException("通话期间无法开始体验");
            Session previous = active;
            if (previous != null) {
                if (previous.lease.call != null) throw new IOException("正在处理来电");
                if (!previous.lease.owner.equals(owner)) throw new IOException("另一个页面正在体验，请稍后重试");
                finish(previous, "重新开始体验");
            }
            Object connection = transport.connection(address);
            String blocked = transport.blocked(address, connection);
            if (blocked != null) throw new IOException(blocked);
            begin(address, connection, null, owner);
            return null;
        });
        try { task.get(5, TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) { throw new IOException(e.getCause().getMessage(), e.getCause()); }
        catch (java.util.concurrent.TimeoutException e) {
            cancelled.set(true); task.cancel(false);
            Session s = active;
            if (s != null && s.lease.owner.equals(owner)) { s.lease.cancel(); worker.execute(() -> finish(s, "体验启动超时")); }
            throw new IOException("体验启动超时，请重试", e);
        }
    }

    void heartbeat(String address, String owner) {
        Session s = active;
        if (s != null && s.lease.call == null && s.lease.address.equals(address) && s.lease.owner.equals(owner))
            s.heartbeat = SystemClock.elapsedRealtime();
    }
    void disconnected(String address) {
        Session s = active;
        if (s != null && s.lease.address.equals(address)) {
            s.lease.cancel(); worker.execute(() -> finish(s, "耳机连接已结束"));
        }
    }
    void packet(String address, Object connection, byte[] data, int length) {
        Session s = active;
        if (s == null || !s.lease.address.equals(address) || s.lease.connection != connection) return;
        if (s.lease.call == null && s.diagnosticFrames < 3 && length >= 70) {
            s.diagnosticFrames++;
            s.diagnostics.offer(java.util.Arrays.copyOf(data, Math.min(length, 128)));
        }
        HeadMotionCodec.Sample sample = HeadMotionCodec.sample(data, length, SystemClock.elapsedRealtime());
        if (sample != null && !s.samples.offer(sample)) {
            // A discontinuity is safer than recognising a gesture from a partial window.
            s.overrun = true; s.samples.clear(); s.samples.offer(sample);
        }
    }

    void changed() {
        if (scheduled.compareAndSet(false, true)) worker.execute(this::pulse);
    }
    private void pulse() {
        scheduled.set(false);
        try {
            Call ringing = calls.ringing();
            if (ringing == null && !calls.busy()) attempted = null;
            Session s = active;
            if (s != null) {
                long now = SystemClock.elapsedRealtime();
                String blocked = transport.blocked(s.lease.address, s.lease.connection);
                boolean valid = s.lease.current(transport.connection(s.lease.address), s.lease.call, now);
                if (blocked != null || !valid) finish(s, blocked == null ? "本次识别已结束" : blocked);
                else if (s.lease.call == null && (calls.busy() || now - s.heartbeat > 4000)) finish(s, "体验已停止");
                else if (s.lease.call != null && !s.actionPending && ringing != s.lease.call) finish(s, "来电已结束");
                else if (!s.actionPending) process(s, now);
            }
            if (active == null && calls.available() && ringing != null && ringing != attempted) {
                String selected = null; Object connection = null;
                for (String address : transport.addresses()) if (enabled(address)) {
                    Object candidate = transport.connection(address);
                    if (transport.blocked(address, candidate) == null) {
                        if (selected != null) { selected = null; break; }
                        selected = address; connection = candidate;
                    }
                }
                if (selected != null) {
                    attempted = ringing;
                    begin(selected, connection, ringing, "call");
                }
            }
        } catch (Exception failure) {
            Session s = active;
            if (s != null) finish(s, "无法开启运动数据，请重新体验");
            module.error("Head motion session failed", failure);
        } finally {
            if ((active != null || calls.ringing() != null) && scheduled.compareAndSet(false, true))
                worker.schedule(this::pulse, active == null ? 500 : 40, TimeUnit.MILLISECONDS);
        }
    }

    private void begin(String address, Object connection, Call call, String owner) throws Exception {
        long now = SystemClock.elapsedRealtime();
        Session s = new Session(new HeadMotionLease(address, connection, call, owner, now + (call == null ? 30000 : 60000)), now);
        active = s;
        // No takeOver(), routing frames, or audio preparation belongs here.
        try { s.tracking=true; transport.send(address, connection, true, s.alternate); }
        catch (Exception failure) { finish(s, "无法开启运动数据，请重新体验"); throw failure; }
        audio.prepare(address,call!=null,()->sessionValid(s)&&!s.actionPending);
        publish(s, true); changed();
        module.info("Head motion: started " + (call == null ? "experience" : "ringing-call detection"));
    }

    private void process(Session s, long now) throws Exception {
        byte[] diagnostic;
        while ((diagnostic = s.diagnostics.poll()) != null)
            module.info("Head motion frame: " + Protocol.hex(diagnostic, 0, diagnostic.length));
        if (s.overrun) { s.overrun = false; s.detector.reset(); s.movement.reset(); }
        if (s.lease.call == null && s.gesture != 0 && now - s.gestureAt >= 1500) s.gesture = 0;
        HeadMotionCodec.Sample sample;
        while ((sample = s.samples.poll()) != null) {
            if (now - sample.at() > 250) { s.detector.reset(); s.movement.reset(); continue; }
            s.lastSample = sample.at(); s.count++;
            if (s.lease.call == null) {
                s.minH=Math.min(s.minH,sample.horizontal());s.maxH=Math.max(s.maxH,sample.horizontal());
                s.minV=Math.min(s.minV,sample.vertical());s.maxV=Math.max(s.maxV,sample.vertical());
            }
            HeadMovementFeedback.Direction direction=s.movement.accept(sample);
            audio.direction(s.lease.address,s.lease.call!=null,direction,()->sessionValid(s)&&!s.actionPending);
            int gesture = s.detector.accept(sample);
            if (gesture == HeadGestureDetector.NONE) continue;
            s.gesture = gesture;
            s.gestureAt = now;
            s.message = gesture == HeadGestureDetector.NOD ? "识别到点头" : "识别到摇头";
            module.info("Head motion: " + (gesture == HeadGestureDetector.NOD ? "nod" : "shake"));
            if (s.lease.call != null) {
                s.actionPending = true;
                boolean answer = HeadMotionLease.answers(gesture, reversed(s.lease.address));
                calls.perform((Call) s.lease.call, answer,
                        () -> sessionValid(s) && enabled(s.lease.address)
                                && s.lease.consume(transport.connection(s.lease.address), callsCandidate(s), SystemClock.elapsedRealtime()),
                        ()->sessionValid(s)&&enabled(s.lease.address),
                        ()->worker.execute(()->{
                            if(active!=s)return;stopTracking(s);s.message=answer?"已请求接听，等待系统确认":"已请求拒接，等待系统确认";publish(s,true);
                        }),
                        result -> worker.execute(() -> {
                            if(active!=s)return;
                            if(result==HeadCallConfirmation.Result.SUCCESS){
                                s.message=answer?"已接听来电":"已拒接来电";publish(s,true);
                                audio.confirm(s.lease.address,true,()->sessionValid(s)&&enabled(s.lease.address),
                                        ()->worker.execute(()->finish(s,answer?"已接听来电":"已拒接来电")));
                            }else finish(s,result==HeadCallConfirmation.Result.TIMED_OUT?"尚未收到系统操作确认":"来电已变化或操作未成功");
                        }));
                break;
            }else audio.confirm(s.lease.address,false,()->sessionValid(s)&&!calls.busy(),()->{});
        }
        if (s.count == 0 && now - s.phaseAt >= 2500) {
            if (!s.retry) {
                transport.send(s.lease.address, s.lease.connection, false, s.alternate);
                s.retry = true; s.alternate = false; s.phaseAt = now;
                s.samples.clear(); s.detector.reset(); s.movement.reset();
                transport.send(s.lease.address, s.lease.connection, true, false);
            } else { finish(s, "未收到可用运动数据，请确认耳机支持并重试"); return; }
        } else if (s.lastSample > 0 && now - s.lastSample > 1500) {
            finish(s, "运动数据已中断，请重新开始"); return;
        }
        if (s.count > 0 && s.gesture == 0) s.message = "请自然地连续点头或摇头";
        if (s.lease.call == null && s.minH != Integer.MAX_VALUE && now - s.rangeAt >= 1000) {
            module.info("Head motion sample range: h="+s.minH+".."+s.maxH+" v="+s.minV+".."+s.maxV+" "+s.detector.diagnostic());
            s.rangeAt=now;s.minH=s.minV=Integer.MAX_VALUE;s.maxH=s.maxV=Integer.MIN_VALUE;
        }
        publish(s, true);
    }
    private Object callsCandidate(Session s) { return calls.ringing() == s.lease.call ? s.lease.call : null; }
    private boolean sessionValid(Session s){
        return active==s&&s.lease.current(transport.connection(s.lease.address),s.lease.call,SystemClock.elapsedRealtime())
                &&transport.blocked(s.lease.address,s.lease.connection)==null;
    }
    private void stopTracking(Session s){
        if(!s.tracking)return;s.tracking=false;
        try{if(transport.connection(s.lease.address)==s.lease.connection)
            transport.send(s.lease.address,s.lease.connection,false,s.alternate);
        }catch(Exception failure){module.info("Head motion stop unavailable: "+failure.getMessage());worker.schedule(()->retryStop(s,1),1,TimeUnit.SECONDS);}
    }

    private void finish(Session s, String message) {
        s.lease.cancel();
        if (active != s) return;
        // Keep the session slot until the matching stop has been sent. A stale
        // stop can never disable a replacement session or replacement socket.
        stopTracking(s);
        active = null; s.samples.clear(); s.message = message; publish(s, false);
        module.info("Head motion: subscription ended");
    }
    private void retryStop(Session ended, int attempt) {
        Session current = active;
        if (transport.connection(ended.lease.address) != ended.lease.connection
                || current != null && current != ended && current.lease.address.equals(ended.lease.address)) return;
        try { transport.send(ended.lease.address, ended.lease.connection, false, ended.alternate); }
        catch (Exception failure) {
            if (attempt < 2) worker.schedule(() -> retryStop(ended, attempt + 1), 1, TimeUnit.SECONDS);
            else module.info("Head motion stop retry exhausted; connection must be refreshed");
        }
    }

    private void publish(Session s, boolean running) {
        Bundle view = new Bundle();
        view.putBoolean("active", running); view.putBoolean("testing", s.lease.call == null);
        view.putString("owner", s.lease.owner); view.putString("message", s.message);
        view.putLong("samples", s.count); view.putInt("gesture", s.gesture); view.putLong("expires", s.lease.expires);
        views.put(s.lease.address, view);
    }
    Bundle status(String address) {
        Bundle result = views.containsKey(address) ? new Bundle(views.get(address)) : new Bundle();
        result.putBoolean("enabled", enabled(address)); result.putBoolean("reversed", reversed(address));
        result.putBoolean("callsAvailable", calls.available());
        String blocked = transport.blocked(address, transport.connection(address));
        result.putBoolean("available", blocked == null && !calls.busy());
        if (!result.getBoolean("active") && blocked != null) result.putString("message", blocked);
        return result;
    }
}
