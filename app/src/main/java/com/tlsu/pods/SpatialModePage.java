// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The three choices share the stock phone/tablet layout. The host still owns its video and audition. */
final class SpatialModePage {
    private static final String ACTIVITY="com.heytap.mydevices.plugin.airpods.ui.PersonalizedSpatialAudioActivity";
    private static final Map<Activity,SpatialModePage> pages=new IdentityHashMap<>();
    private static final ExecutorService io=Executors.newSingleThreadExecutor(r->new Thread(r,"ColorPodsSpatialUI"));
    private final Activity activity;
    private final String address;
    private final BridgeClient bridge;
    private final ColorPodsModule module;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final View[] choices=new View[3];
    private final ImageView[] icons=new ImageView[3];
    private LinearLayout original, column;
    private View originalClose,originalFixed;
    private ImageView originalCloseIcon, originalFixedIcon;
    private TextView detail, center, calibrate;
    private LinearLayout actions;
    private HeadCalibrationDialog calibration;
    private int normalBg,selectedBg,shown=-1;
    private boolean ready, pending, destroyed, fallback, temporaryStock, trackingActive;

    static void install(ColorPodsModule module,ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type=Class.forName(ACTIVITY,false,loader);
        // The OEM coroutine replays its cached two-way choice on every open.
        // The Bluetooth backend is the writer for the three-way page.
        for (String setter:new String[]{"setSpatializerClose","setSpatializerFixed"}) {
            module.hook(type.getDeclaredMethod(setter)).intercept(chain->{
                SpatialModePage p=pages.get((Activity)chain.getThisObject());
                return p!=null && !p.fallback && !p.temporaryStock ? null : chain.proceed();
            });
        }
        for (var m:type.getDeclaredMethods()) {
            if (java.lang.reflect.Modifier.isStatic(m.getModifiers()) && m.getParameterCount()==2
                    && m.getParameterTypes()[0]==type && m.getParameterTypes()[1]==View.class) module.deoptimize(m);
        }
        try { module.deoptimize(Class.forName(ACTIVITY+"$initAudioType$1",false,loader)
                .getDeclaredMethod("invokeSuspend",Object.class)); }
        catch (ReflectiveOperationException ignored) {}
        module.hook(type.getDeclaredMethod("onCreate",Bundle.class)).intercept(chain->{
            Activity a=(Activity)chain.getThisObject();
            String addr=a.getIntent().getStringExtra("mac_address");
            if (addr!=null) addr=addr.toUpperCase(Locale.ROOT);
            if (!BluetoothAdapter.checkBluetoothAddress(addr)) return chain.proceed();
            SpatialModePage p=new SpatialModePage(a,addr,module); pages.put(a,p);
            Object result=chain.proceed();
            try { p.bind(); }
            catch (Exception e) { p.restoreStock(); module.error("Spatial mode page unavailable",e); }
            return result;
        });
        module.hook(type.getDeclaredMethod("onDestroy")).intercept(chain->{
            SpatialModePage p=pages.remove((Activity)chain.getThisObject());
            if (p!=null) {
                p.destroyed=true;
                if (p.calibration != null) p.calibration.dismiss();
                p.main.removeCallbacksAndMessages(null);
            }
            return chain.proceed();
        });
    }
    private SpatialModePage(Activity a,String addr,ColorPodsModule module) {
        activity=a; address=addr; bridge=new BridgeClient(a); this.module=module;
    }
    private int res(String type,String name) {
        int id=activity.getResources().getIdentifier(name,type,activity.getPackageName());
        if (id==0) throw new IllegalStateException("Missing "+name);
        return id;
    }
    private int dp(int n) { return Math.round(activity.getResources().getDisplayMetrics().density*n); }
    private static void clearIds(View v) {
        v.setId(View.NO_ID);
        if (v instanceof ViewGroup g) for (int i=0;i<g.getChildCount();i++) clearIds(g.getChildAt(i));
    }
    private void bind() throws Exception {
        original=activity.findViewById(res("id","ll_spatial_layout"));
        originalClose=original.findViewById(res("id","spatial_audio_close"));
        originalFixed=original.findViewById(res("id","fixed_button"));
        originalCloseIcon=original.findViewById(res("id","img_spatial_audio_close"));
        originalFixedIcon=original.findViewById(res("id","img_spatial_audio_fixed"));
        ViewGroup parent=(ViewGroup)original.getParent();
        int layout=res("layout","spatial_audio_operation");
        LinearLayout row=(LinearLayout)activity.getLayoutInflater().inflate(layout,parent,false);
        LinearLayout copy=(LinearLayout)activity.getLayoutInflater().inflate(layout,parent,false);
        choices[0]=row.findViewById(res("id","spatial_audio_close"));
        choices[1]=row.findViewById(res("id","fixed_button"));
        choices[2]=copy.findViewById(res("id","fixed_button"));
        icons[0]=row.findViewById(res("id","img_spatial_audio_close"));
        icons[1]=row.findViewById(res("id","img_spatial_audio_fixed"));
        icons[2]=copy.findViewById(res("id","img_spatial_audio_fixed"));
        TextView label=copy.findViewById(res("id","tv_fixed")); label.setText("头部追踪");
        try {
            var resources = activity.getPackageManager().getResourcesForApplication(module.getModuleApplicationInfo());
            icons[2].setImageDrawable(resources.getDrawable(R.drawable.ic_head_tracking, null));
        } catch (android.content.pm.PackageManager.NameNotFoundException | android.content.res.Resources.NotFoundException e) {
            module.info("Using host spatial audio icon: " + e);
        }
        normalBg=res("drawable","noise_button_normal_bg"); selectedBg=res("drawable","noise_button_selected_bg");
        copy.removeView(choices[2]); row.addView(choices[2]); clearIds(row);
        int gap = activity.getResources().getDimensionPixelSize(res("dimen", "spatial_mode_item_gap"));
        if (choices[1].getBackground() instanceof android.graphics.drawable.GradientDrawable middle) {
            ((android.graphics.drawable.GradientDrawable) middle.mutate()).setCornerRadius(
                    activity.getResources().getDimension(res("dimen", "coui_round_corner_xs")));
        }
        for (int i=0;i<choices.length;i++) {
            int mode=i;
            var margins = (LinearLayout.LayoutParams) choices[i].getLayoutParams();
            margins.setMarginStart(i == 0 ? 0 : gap);
            margins.setMarginEnd(i == choices.length - 1 ? 0 : gap);
            choices[i].setLayoutParams(margins);
            choices[i].setContentDescription(new String[]{"关闭","固定","头部追踪"}[i]);
            choices[i].setOnClickListener(v->send("mode",mode));
        }
        column=new LinearLayout(activity) {
            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(visibility);
                if (visibility == View.VISIBLE) main.post(refresh);
                else {
                    main.removeCallbacks(refresh);
                    if (calibration != null) calibration.dismiss();
                }
            }
        };
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(row,new LinearLayout.LayoutParams(-1,-2));
        detail=new TextView(activity); detail.setTextColor(label.getTextColors()); detail.setTextSize(14);
        detail.setGravity(Gravity.CENTER); detail.setPadding(dp(8),dp(10),dp(8),0);
        column.addView(detail);
        actions = new LinearLayout(activity);
        actions.setGravity(Gravity.CENTER);
        center = action("以当前方向回正", label.getTextColors(), () -> send("recenter", -1));
        calibrate = action("校准头部方向", label.getTextColors(), this::startCalibration);
        column.addView(actions);
        column.setVisibility(View.GONE);
        parent.addView(column,parent.indexOfChild(original)+1,original.getLayoutParams());
        main.removeCallbacks(refresh);
        main.post(refresh);
    }
    private TextView action(String text, ColorStateList color, Runnable click) {
        TextView button = new TextView(activity);
        button.setText(text);
        button.setTextSize(15);
        button.setTextColor(color);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setMinHeight(dp(48));
        button.setFocusable(true);
        button.setOnClickListener(v -> click.run());
        actions.addView(button);
        return button;
    }
    private void startCalibration() {
        if (calibration != null) return;
        if (!trackingActive) {
            Toast.makeText(activity, "请先播放支持空间音频的媒体，等耳机开始追踪", Toast.LENGTH_SHORT).show();
            return;
        }
        calibration = new HeadCalibrationDialog(activity, address, bridge, io, () -> calibration = null);
        calibration.show();
    }
    private void restoreStock() {
        fallback=true;
        if (column!=null) column.setVisibility(View.GONE);
        if (original!=null) original.setVisibility(View.VISIBLE);
    }
    private final Runnable refresh=()->send("status",-1);
    private void refreshLater(long delay) {
        main.removeCallbacks(refresh);
        if (!destroyed && !fallback && column.getWindowVisibility() == View.VISIBLE)
            main.postDelayed(refresh, delay);
    }
    private void send(String action,int mode) {
        if (destroyed || fallback || pending || column.getWindowVisibility() != View.VISIBLE) return;
        main.removeCallbacks(refresh);
        if (!action.equals("status")) pending=true;
        Bundle request=BridgeClient.forDevice(address); request.putString("action",action); request.putInt("mode",mode);
        io.execute(()->{
            try {
                Bundle result=bridge.request(BridgeClient.SPATIAL,request);
                main.post(()->{
                    if (destroyed || fallback) return;
                    if (!action.equals("status")) pending=false;
                    if (result.getInt("version")!=1) { temporaryStock=true; refreshLater(1500); return; }
                    temporaryStock=false;
                    if (!pending) render(result);
                    if (action.equals("recenter")) Toast.makeText(activity,"将在下一帧姿态回正",Toast.LENGTH_SHORT).show();
                    refreshLater(1000);
                });
            } catch (Exception e) {
                main.post(()->{
                    if (destroyed) return;
                    pending=false;
                    if (!ready && !temporaryStock) {
                        temporaryStock=true;
                        module.info("Spatial UI backend unavailable: "+e);
                    }
                    if (!action.equals("status")) Toast.makeText(activity,e.getMessage(),Toast.LENGTH_SHORT).show();
                    detail.setText("等待蓝牙服务"); refreshLater(1500);
                });
            }
        });
    }
    private void render(Bundle state) {
        int mode=state.getInt("mode");
        if (mode<0 || mode>=choices.length) return;
        if (!ready) { ready=true; original.setVisibility(View.GONE); column.setVisibility(View.VISIBLE); }
        boolean off = mode == SpatialAudioController.OFF;
        if (originalFixed.isEnabled() && (mode!=shown || originalCloseIcon.isSelected() != off || originalFixedIcon.isSelected() == off)) {
            // The host can finish loading its cached two-way choice after the Binder response.
            // Use its click handler to keep both the original preview and its audio focus in sync.
            (off ? originalClose : originalFixed).performClick();
            shown=mode;
        }
        for (int i=0;i<choices.length;i++) {
            boolean selected=i==mode;
            if (icons[i].isSelected()!=selected || choices[i].getTag()==null) {
                choices[i].setSelected(selected); icons[i].setSelected(selected);
                icons[i].setBackgroundResource(selected ? selectedBg : normalBg);
                choices[i].setTag(Boolean.TRUE);
            }
            choices[i].setEnabled(originalFixed.isEnabled()); choices[i].setAlpha(originalFixed.isEnabled()?1f:.6f);
        }
        boolean tracking=mode==SpatialAudioController.TRACKING;
        trackingActive = tracking && state.getBoolean("sending");
        if (!trackingActive && calibration != null) calibration.dismiss();
        detail.setVisibility(tracking?View.VISIBLE:View.GONE); actions.setVisibility(tracking?View.VISIBLE:View.GONE);
        detail.setText(state.getString("detail","等待耳机姿态"));
        center.setEnabled(originalFixed.isEnabled());
        calibrate.setText(state.getBoolean("calibrated") ? "重新校准头部方向" : "校准头部方向");
        calibrate.setEnabled(originalFixed.isEnabled());
    }
}
