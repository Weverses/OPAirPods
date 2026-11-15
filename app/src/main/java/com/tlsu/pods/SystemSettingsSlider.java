// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.content.Context;
import android.content.res.XmlResourceParser;
import android.util.Xml;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;
import java.util.function.IntConsumer;

/** Reuse the installed Settings COUI implementation and its brightness XML attributes. */
final class SystemSettingsSlider {
    private static final String PACKAGE="com.android.settings", TYPE="com.coui.appcompat.seekbar.COUISeekBar";
    private static final WeakHashMap<Object,SystemSettingsSlider> ROWS=new WeakHashMap<>();
    private static final String TAG="colorpods.system.slider";
    private final Context context;
    private final Class<?> type;
    private View view;
    private TextView summary;
    private IntConsumer commit;
    private int value;
    private boolean enabled,tracking;

    SystemSettingsSlider(Context host,Object row)throws Exception {
        Context pkg=host.createPackageContext(PACKAGE,Context.CONTEXT_INCLUDE_CODE|Context.CONTEXT_IGNORE_SECURITY);
        Context application=host.getApplicationContext();
        if(application==null)throw new IllegalStateException("Host application unavailable");
        // A foreign package Context has resources/code but no running Application.
        // COUI registers its haptic observer when attached, using this accessor.
        context=new ContextThemeWrapper(pkg,resource(pkg,"style","AppBaseTheme.NoActionBar")){
            @Override public Context getApplicationContext(){return application;}
        };
        type=Class.forName(TYPE,true,pkg.getClassLoader());
        // Construct once before registering, so missing firmware resources fail at injection.
        view=create();ROWS.put(row,this);
    }
    private static int resource(Context context,String kind,String name){
        int id=context.getResources().getIdentifier(name,kind,PACKAGE);
        if(id==0)throw new IllegalStateException("Missing Settings resource "+kind+"/"+name);return id;
    }
    private View create()throws Exception {
        View result;
        try(XmlResourceParser xml=context.getResources().getLayout(resource(context,"layout","status_bar_toggle_slider"))){
            while(xml.next()!=XmlResourceParser.END_DOCUMENT && !(xml.getEventType()==XmlResourceParser.START_TAG&&TYPE.equals(xml.getName()))){}
            if(xml.getEventType()==XmlResourceParser.END_DOCUMENT)throw new IllegalStateException("Settings brightness slider missing");
            result=(View)type.getConstructor(Context.class,android.util.AttributeSet.class).newInstance(context,Xml.asAttributeSet(xml));
        }
        type.getMethod("setMax",int.class).invoke(result,100);
        Class<?> listener=Class.forName(TYPE+"$OnSeekBarChangeListener",true,type.getClassLoader());
        Object callback=Proxy.newProxyInstance(type.getClassLoader(),new Class[]{listener},(proxy,method,args)->{
            if(method.getDeclaringClass()==Object.class)return switch(method.getName()){
                case "hashCode"->System.identityHashCode(proxy);case "equals"->proxy==args[0];default->"ColorPodsSliderListener";};
            switch(method.getName()){
                case "onStartTrackingTouch"->tracking=true;
                case "onProgressChanged"->{if(Boolean.TRUE.equals(args[2])&&summary!=null)summary.setText(args[1]+"%");}
                case "onStopTrackingTouch"->{tracking=false;int progress=(Integer)type.getMethod("getProgress").invoke(args[0]);if(progress!=value&&commit!=null)commit.accept(progress);}
            }
            return null;
        });
        type.getMethod("setOnSeekBarChangeListener",listener).invoke(result,callback);
        result.setTag(TAG);return result;
    }
    void onChange(IntConsumer action){commit=action;}
    void value(int value)throws Exception{this.value=Math.max(0,Math.min(100,value));if(!tracking)type.getMethod("setProgress",int.class).invoke(view,this.value);}
    void enabled(boolean enabled){this.enabled=enabled;if(!tracking)view.setEnabled(enabled);}
    static void bind(Object row,Object holder)throws Exception {
        View root=(View)holder.getClass().getField("itemView").get(holder);
        View previous=root.findViewWithTag(TAG);
        SystemSettingsSlider slider=ROWS.get(row);
        if(slider!=null&&previous==slider.view){
            slider.summary=root.findViewById(android.R.id.summary);
            slider.enabled(slider.enabled);slider.value(slider.value);return;
        }
        if(previous!=null)((ViewGroup)previous.getParent()).removeView(previous);
        if(slider==null)return;
        slider.tracking=false;
        TextView title=root.findViewById(android.R.id.title);
        if(title==null||!(title.getParent() instanceof RelativeLayout content))throw new IllegalStateException("Unsupported COUI preference content");
        slider.summary=root.findViewById(android.R.id.summary);
        if(slider.view.getParent() instanceof ViewGroup old)old.removeView(slider.view);
        RelativeLayout.LayoutParams params=new RelativeLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,Math.round(52*root.getResources().getDisplayMetrics().density));
        params.addRule(RelativeLayout.BELOW,android.R.id.summary);
        slider.view.setContentDescription(title.getText());slider.view.setEnabled(slider.enabled);
        content.addView(slider.view,params);slider.value(slider.value);
    }
}
