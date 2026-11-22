// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.AudioDeviceCallback;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

/** Owns the active A2DP route's sensor independently of any settings activity. */
final class SpatialAudioController {
    static final int OFF=0, FIXED=1, TRACKING=2;
    private static final int UNSET=-1;
    private static final long MAX_POSE_AGE_NS = 40_000_000L;
    private static final String SET="OPLUS_AUDIO_SET_SPATIALIZER_AUDIO;";
    private static final String GET="OPLUS_AUDIO_GET_SPATIALIZER_AUDIO;";
    private static final AudioAttributes MEDIA=new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build();
    private final HeadMotion.Transport transport;
    private final HeadMotionStream stream;
    private final BooleanSupplier busy, nativeReady;
    private final ColorPodsModule module;
    private final HeadOrientation orientation = new HeadOrientation();
    private static final class MotionInput {
        final String address;
        final Object socket;
        final AtomicLong callbacks = new AtomicLong(), unbound = new AtomicLong(), payloads = new AtomicLong(), poses = new AtomicLong();
        volatile int flags = -1, length, starts, control;
        volatile boolean alternate;
        MotionInput(String address, Object socket) { this.address = address; this.socket = socket; }
    }
    private static final class Feed {
        final MotionInput input;
        final AtomicReference<Frame> latest = new AtomicReference<>();
        final AtomicBoolean framePosted = new AtomicBoolean();
        volatile long lastPayloadMs, lastPoseMs, sampleIntervalUs, bridgeUs;
        long previousClock;
        Feed(MotionInput input) { this.input = input; }
    }
    private record Frame(HeadOrientation.Pose pose, long arrival) {}
    private record View(String address,String detail,boolean sending,long reports, int yaw, int resets) {}
    private volatile Feed feed;
    private volatile MotionInput input;
    private volatile View view=new View("","等待耳机连接",false,0,0,0);
    private volatile Handler worker;
    private AudioManager audio;
    private SharedPreferences preferences;
    private String address;
    private Object socket;
    private int applied=UNSET;
    private long sensor, registeredAt, started, retryAt, reports, lastWriteNs, lastWriteClock;
    private int lastYaw, resets;
    private boolean requested, subscribed, alternate=true;
    private final Runnable tick=this::reconcile;

    SpatialAudioController(ColorPodsModule module,HeadMotion.Transport transport,HeadMotionStream stream,
                           BooleanSupplier busy,BooleanSupplier nativeReady) {
        this.module=module; this.transport=transport; this.stream=stream;
        this.busy=busy; this.nativeReady=nativeReady;
    }
    void attach(Context c) {
        audio=c.getSystemService(AudioManager.class);
        preferences=c.getSharedPreferences("colorpods",0);
        HandlerThread thread=new HandlerThread("ColorPodsSpatial"); thread.start();
        worker=new Handler(thread.getLooper());
        try {
            audio.registerAudioDeviceCallback(new AudioDeviceCallback() {
                @Override public void onAudioDevicesAdded(AudioDeviceInfo[] ignored) { changed(); }
                @Override public void onAudioDevicesRemoved(AudioDeviceInfo[] ignored) { changed(); }
            }, worker);
        } catch (RuntimeException | LinkageError e) {
            worker = null;
            thread.quitSafely();
            throw e;
        }
        changed();
    }
    void changed() {
        Handler h=worker;
        if (h!=null) { h.removeCallbacks(tick); h.post(tick); }
    }
    private String key(String addr) { return "spatialMode."+addr; }
    private String clearKey(String addr) { return "spatialClearTracker."+addr; }
    private String basisKey(String addr) { return "spatialHeadAxes." + addr; }
    private String route() {
        if (audio == null) return null;
        List<AudioDeviceInfo> devices=audio.getAudioDevicesForAttributes(MEDIA);
        if (devices.size()!=1 || devices.get(0).getType()!=AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return null;
        String addr=devices.get(0).getAddress().toUpperCase(java.util.Locale.ROOT);
        return BluetoothAdapter.checkBluetoothAddress(addr) ? addr : null;
    }
    String mediaAddress() { return route(); }
    private boolean current() { return address!=null && socket!=null && transport.connection(address)==socket && address.equals(route()); }
    private void set(String command) throws IOException {
        if (!current()) throw new IOException("媒体输出已切换");
        audio.setParameters(SET+command);
    }
    private boolean bound() {
        return current() && "true".equalsIgnoreCase(audio.getParameters(GET+"getHasHeadTracker;"+address).trim());
    }
    Bundle status(String addr) {
        Bundle out=new Bundle();
        if (worker==null) return out;
        int saved=preferences.getInt(key(addr),UNSET);
        out.putInt("version",1);
        out.putInt("mode", saved == UNSET ? (audio.getSpatializer().isEnabled() ? FIXED : OFF) : saved);
        out.putBoolean("calibrated", preferences.contains(basisKey(addr)));
        View v=view;
        if (addr.equals(v.address)) {
            out.putString("detail",v.detail); out.putBoolean("sending",v.sending);
            out.putLong("reports",v.reports); out.putInt("yaw",v.yaw); out.putInt("resets",v.resets);
            MotionInput i = input;
            if (i != null && addr.equals(i.address)) {
                out.putLong("callbacks", i.callbacks.get()); out.putLong("unbound", i.unbound.get());
                out.putLong("payloads", i.payloads.get()); out.putLong("poses", i.poses.get());
                out.putInt("flags", i.flags); out.putInt("length", i.length);
                out.putInt("starts", i.starts); out.putInt("control", i.control); out.putBoolean("alternate", i.alternate);
                Feed active = feed;
                if (active != null && active.input == i) {
                    out.putInt("requestedIntervalUs", HeadMotionCodec.REPORT_INTERVAL_US);
                    out.putLong("sampleIntervalUs", active.sampleIntervalUs);
                    out.putLong("bridgeUs", active.bridgeUs);
                }
            }
        } else out.putString("detail","等待这副耳机成为媒体输出");
        return out;
    }
    Bundle command(String addr,Bundle args) throws Exception {
        if (!BluetoothAdapter.checkBluetoothAddress(addr) || worker==null) throw new IOException("耳机尚未就绪");
        String action=args.getString("action","");
        if (action.equals("status")) return status(addr);
        return schedule(() -> {
            switch (action) {
                case "mode" -> selectMode(addr, args.getInt("mode", UNSET));
                case "recenter" -> { requireTracking(addr); orientation.recenter(); }
                case "sample", "calibrate" -> { return calibration(addr, action, args); }
                default -> throw new IllegalArgumentException("未知空间音频操作");
            }
            reconcile();
            return status(addr);
        });
    }
    void selectFromVolumePanel(String addr, int mode, BooleanSupplier valid) throws Exception {
        schedule(() -> {
            if (!valid.getAsBoolean()) throw new IOException("媒体输出已切换");
            selectMode(addr, mode);
            changed();
            return null;
        });
    }
    private Bundle schedule(java.util.concurrent.Callable<Bundle> action) throws Exception {
        FutureTask<Bundle> work = new FutureTask<>(action);
        worker.post(work);
        try { return work.get(5,TimeUnit.SECONDS); }
        catch (java.util.concurrent.ExecutionException e) { throw new IOException(e.getCause().getMessage(),e.getCause()); }
        catch (java.util.concurrent.TimeoutException e) { work.cancel(false); throw new IOException("空间音频操作超时",e); }
    }
    private void requireTracking(String addr) throws IOException {
        if (!addr.equals(address) || !current() || preferences.getInt(key(addr), UNSET) != TRACKING)
            throw new IOException("请先为当前媒体输出耳机开启头部追踪");
    }
    private void selectMode(String addr, int selection) throws IOException {
        if (selection < OFF || selection > TRACKING) throw new IllegalArgumentException("未知空间音频模式");
        if (selection == TRACKING && !nativeReady.getAsBoolean()) throw new IOException("头部传感器尚未就绪");
        if (!preferences.edit().putInt(key(addr), selection).commit()) throw new IOException("未能保存空间音频模式");
        if (addr.equals(address)) { applied = UNSET; retryAt = 0; }
    }
    private Bundle calibration(String addr, String action, Bundle args) throws IOException {
        requireTracking(addr);
        long epoch = orientation.epoch();
        if (args.containsKey("epoch") && args.getLong("epoch") != epoch) {
            Bundle result = status(addr);
            result.putBoolean("restart", true);
            return result;
        }
        if (!subscribed) throw new IOException("请先播放支持空间音频的媒体");
        HeadOrientation.Pose pose = orientation.stablePose(SystemClock.elapsedRealtimeNanos());
        if (pose == null) throw new IOException("请保持头部静止片刻，再记录方向");
        if (action.equals("sample")) {
            Bundle sample = status(addr);
            sample.putDoubleArray("pose", pose.components());
            sample.putLong("epoch", epoch);
            return sample;
        }
        HeadOrientation.Pose front = HeadOrientation.Pose.fromComponents(args.getDoubleArray("front"));
        if (!args.containsKey("epoch") || !HeadBasis.nearFront(front, pose))
            throw new IOException("请看向开始校准时的正前方，再点完成");
        HeadBasis basis = HeadBasis.calibrate(front,
                HeadOrientation.Pose.fromComponents(args.getDoubleArray("left")),
                HeadOrientation.Pose.fromComponents(args.getDoubleArray("up")));
        if (!preferences.edit().putString(basisKey(addr), basis.encode()).commit()) throw new IOException("未能保存头部方向");
        orientation.setBasis(basis);
        return status(addr);
    }
    private void publish(String detail,boolean sending) { view=new View(address,detail,sending,reports,lastYaw,resets); }
    private void stopStream() {
        feed=null; orientation.recenter();
        if (!subscribed) return;
        subscribed=false; lastWriteNs=0; lastWriteClock=0;
        release(address,socket,alternate,0);
    }
    private void release(String addr,Object connection,boolean variant,int attempt) {
        if (subscribed && connection==socket && addr.equals(address)) return;
        if (transport.connection(addr)!=connection) { stream.forget(addr,connection); return; }
        try { stream.change(HeadMotionStream.Consumer.SPATIAL,addr,connection,false,variant); }
        catch (Exception e) {
            if (attempt<2) worker.postDelayed(()->release(addr,connection,variant,attempt+1),500);
            else module.info("Spatial stream stop failed: "+e.getMessage());
        }
    }
    private void closeSensor() {
        stopStream();
        if (sensor!=0) { NativeBridge.closeHeadTracker(sensor); sensor=0; }
        requested=false; lastWriteNs=0; lastWriteClock=0;
    }
    private void changeRoute(String next,Object connection) {
        closeSensor();
        address=next; socket=connection; applied=UNSET; retryAt=0; reports=0; lastYaw=0; resets=0;
        input = connection == null ? null : new MotionInput(next, connection);
        subscribed=false; alternate=true;
        orientation.setBasis(next == null ? null : HeadBasis.decode(preferences.getString(basisKey(next), null)));
        publish("等待耳机控制连接", false);
    }
    private void legacy(int mode) throws Exception {
        stopStream();
        boolean pending = preferences.getBoolean(clearKey(address), false);
        boolean cleared = (applied != mode || pending) && bound();
        if (cleared) {
            // ColorOS can update its per-device flag only while the tracker identity is bound.
            set("setHeadTrackerEnabled;false;" + address);
            preferences.edit().remove(clearKey(address)).apply();
            pending = false;
        }
        if (applied != mode || cleared) {
            set("setDesiredHeadTrackingMode;-1");
            set("setEnabled;"+(mode==FIXED));
            applied = mode;
        }
        if (pending && mode == FIXED && audio.getSpatializer().isEnabled() && audio.isMusicActive()) {
            if (sensor == 0) register();
        } else closeSensor();
        publish(mode==OFF?"已关闭空间音频":"声场固定",false);
    }
    private void register() throws Exception {
        if (!nativeReady.getAsBoolean()) throw new IOException("头部传感器尚未就绪");
        byte[] mac=new byte[6]; String[] parts=address.split(":");
        for (int i=0;i<mac.length;i++) mac[i]=(byte)Integer.parseInt(parts[i],16);
        sensor=NativeBridge.openHeadTracker(mac);
        if (sensor==0) throw new IOException("无法注册头部传感器");
        registeredAt = SystemClock.elapsedRealtime();
        requested=false;
    }
    private void tracking(long now) throws Exception {
        if (applied!=TRACKING) {
            set("setEnabled;true"); applied=TRACKING;
        }
        String blocked=transport.blocked(address,socket);
        if (blocked!=null || busy.getAsBoolean() || audio.getMode()!=AudioManager.MODE_NORMAL) {
            stopStream(); publish(blocked!=null?blocked:"通话期间暂停追踪",false); return;
        }
        if (!audio.getSpatializer().isEnabled()) {
            closeSensor(); publish("系统空间音频已关闭",false); return;
        }
        if (now<retryAt) return;
        if (sensor==0) register();
        if (!requested) {
            if (!bound()) { publish("等待系统识别头部传感器",false); return; }
            if (!preferences.getBoolean(clearKey(address), false)
                    && !preferences.edit().putBoolean(clearKey(address), true).commit())
                throw new IOException("未能保存设备跟踪状态");
            set("setHeadTrackerEnabled;true;"+address); set("setDesiredHeadTrackingMode;1"); requested=true;
        }
        int control=NativeBridge.headTrackerControl(sensor);
        input.control = control;
        if (control<0) throw new IOException("头部传感器已断开");
        if ((control&3)!=3 || !audio.isMusicActive()) {
            stopStream(); alternate=true; publish("等待支持空间音频的媒体播放",false); return;
        }
        if (!subscribed) {
            orientation.recenter();
            // The reader can receive the first packet before the control write returns.
            feed = new Feed(input);
            stream.change(HeadMotionStream.Consumer.SPATIAL, address, socket, true, alternate);
            subscribed = true;
            started = SystemClock.elapsedRealtime();
            input.starts++;
            input.alternate = alternate;
            publish("等待耳机姿态",false); return;
        }
        long lastPayload = feed.lastPayloadMs;
        long lastPose = feed.lastPoseMs;
        // The earbuds can continue sending motion packets without a usable orientation.
        if (lastPose == 0 ? now - started > 3000 : now - lastPose > 1500) {
            stopStream(); alternate=!alternate; retryAt=now+(alternate?5000:300);
            publish(lastPayload == 0 ? "尚未收到耳机运动报告，正在重试" : "缺少有效头部姿态，正在重启追踪",false); return;
        }
        if (lastWriteNs!=0 && now-lastWriteNs/1_000_000L<750)
            publish(orientation.calibrated() ? "正在追踪三轴头部姿态" : "正在追踪水平转头",true);
        else publish(lastPayload == 0 ? "等待耳机运动报告" : "已收到运动报告，等待有效头部姿态",false);
    }
    private void reconcile() {
        Handler h=worker;
        h.removeCallbacks(tick);
        boolean monitor=false;
        try {
            for (String addr:transport.addresses()) if (preferences.contains(key(addr))) { monitor=true; break; }
            String target=monitor?route():null;
            Object connection=target==null?null:transport.connection(target);
            if (!java.util.Objects.equals(target,address) || connection!=socket) changeRoute(target,connection);
            if (connection==null) return;
            int mode=preferences.getInt(key(address),UNSET);
            if (mode==UNSET) return;
            long now=SystemClock.elapsedRealtime();
            if (now<retryAt) return;
            if (mode==TRACKING) tracking(now); else legacy(mode);
        } catch (Exception | LinkageError e) {
            closeSensor(); retryAt=SystemClock.elapsedRealtime()+5000;
            publish("暂时无法启用："+e.getMessage(),false); module.info("Spatial audio: "+e);
        } finally {
            if (monitor) {
                boolean binding = sensor != 0 && !requested && SystemClock.elapsedRealtime() - registeredAt < 3000;
                h.postDelayed(tick, binding ? 200 : subscribed ? 750 : 2500);
            }
        }
    }
    void packet(String addr,Object reader,byte[] bytes,int length,long arrival) {
        Feed target=feed;
        if (target == null || !target.input.address.equals(addr)) return;
        MotionInput source = target.input;
        source.callbacks.incrementAndGet();
        source.length = length;
        if (source.socket != reader) { source.unbound.incrementAndGet(); return; }
        int payload = HeadMotionCodec.payloadOffset(bytes, length);
        if (payload < 0) return;
        target.lastPayloadMs = arrival / 1_000_000L;
        source.flags = bytes[payload+11] & 255;
        source.payloads.incrementAndGet();
        HeadOrientation.Pose pose = HeadOrientation.decodePayload(bytes, payload);
        if (pose==null) return;
        source.poses.incrementAndGet();
        if (pose.clock() == target.previousClock) return;
        long elapsed = pose.clock() - target.previousClock;
        if (target.previousClock != 0 && elapsed > 0) target.sampleIntervalUs = elapsed / 1_000;
        target.previousClock = pose.clock();
        target.lastPoseMs = arrival / 1_000_000L;
        target.latest.set(new Frame(pose,arrival));
        if (target.framePosted.compareAndSet(false,true)) worker.post(() -> drain(target));
    }
    private void drain(Feed target) {
        target.framePosted.set(false);
        Frame f=target.latest.getAndSet(null);
        if (f==null || target!=feed || transport.connection(address)!=socket || sensor==0) return;
        long now=SystemClock.elapsedRealtimeNanos();
        if (now-f.arrival>MAX_POSE_AGE_NS) return;
        int control=NativeBridge.headTrackerControl(sensor);
        if (control<0 || (control&3)!=3) return;
        HeadOrientation.Rotation rotation=orientation.accept(f.pose,f.arrival);
        if (rotation==null) return;
        long interval = HeadMotionCodec.REPORT_INTERVAL_US * 1_000L + ((control >> 2) * 60_000_000L) / 63;
        // Pace by the earbud's clock so Bluetooth arrival jitter cannot halve the
        // update rate. Allow for HID interval quantization at nominal sample rates.
        if ((control >> 2) > 0 && rotation.reset() == resets && lastWriteClock != 0
                && f.pose.clock() - lastWriteClock < interval - 1_000_000L) return;
        if (NativeBridge.writeHeadPose(sensor,rotation.x(),rotation.y(),rotation.z(),rotation.reset())) {
            reports++; lastYaw=rotation.z(); resets=rotation.reset(); lastWriteNs=now;
            lastWriteClock=f.pose.clock();
            target.bridgeUs=(SystemClock.elapsedRealtimeNanos()-f.arrival)/1_000;
        }
    }
}
