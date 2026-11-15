// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.app.Activity;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.TextView;
import java.util.List;
import java.util.function.IntPredicate;

/** Adds a track and drag gesture to the host's existing adaptive mode icon. */
final class AdaptiveStrengthSlider {
    private final Activity activity;
    private final ClassLoader loader;
    private final IntPredicate commit;
    private final int slop;
    private View item,thumb;
    private TextView label;
    private Drawable background;
    private View.AccessibilityDelegate accessibility;
    private CharSequence description,stateDescription,originalLabel;
    private final Track track=new Track();
    private final View.OnLayoutChangeListener layout=(v,l,t,r,b,ol,ot,or,ob)->render();
    private int reported=-1,displayed,start,pointer=-1;
    private float downX,downY;
    private boolean selected,ready,touching,dragging,abandoned;
    private String confirmation="";

    AdaptiveStrengthSlider(Activity activity,ClassLoader loader,IntPredicate commit){
        this.activity=activity;this.loader=loader;this.commit=commit;
        slop=ViewConfiguration.get(activity).getScaledTouchSlop();
    }
    private int id(String name){return activity.getResources().getIdentifier(name,"id",activity.getPackageName());}
    private Object call(Object target,String key,Object... args)throws Exception{
        var method=HostTargets.methodOrNull(key,loader);
        if(method==null)throw new NoSuchMethodException(key);
        return method.invoke(target,args);
    }
    void bind()throws Exception{
        View view=activity.findViewById(id("ll_operation_noise"));
        if(!(view instanceof ViewGroup group)){detach();return;}
        List<?> items=(List<?>)call(activity,HostTargets.UI_NOISE_ITEMS);
        int position=-1;
        for(int i=0;i<items.size();i++){
            if((Integer)Reflect.call(items.get(i),"getId",new Class[0])==4){position=i;break;}
        }
        bind(group,position,(Integer)call(group,HostTargets.UI_NOISE_SELECTED));
    }
    void bind(ViewGroup group,int position,int selectedPosition){
        if(position<0||position>=group.getChildCount()){detach();return;}
        View next=group.getChildAt(position);
        if(item!=next){
            detach();
            View nextThumb=next.findViewById(id("layout"));
            View nextLabel=next.findViewById(id("text"));
            if(nextThumb==null||!(nextLabel instanceof TextView text))return;
            item=next;thumb=nextThumb;label=text;originalLabel=label.getText();
            background=item.getBackground();accessibility=item.getAccessibilityDelegate();
            description=item.getContentDescription();stateDescription=item.getStateDescription();
            item.addOnLayoutChangeListener(layout);thumb.addOnLayoutChangeListener(layout);
            item.setOnTouchListener(this::touch);
        }
        boolean nextSelected=selectedPosition==position;
        if(selected!=nextSelected){cancel();selected=nextSelected;}
        render();
    }
    void update(int value,boolean enabled,String confirmation){
        reported=value;ready=enabled;this.confirmation=confirmation;
        if(!adjustable()&&touching)cancel();
        if(!touching&&value>=0)displayed=value;
        render();
    }
    private boolean adjustable(){return selected&&ready&&reported>=0;}
    private float inset(){return 4*activity.getResources().getDisplayMetrics().density;}
    private float travel(){return item==null||thumb==null?0:Math.max(0,item.getWidth()-2*inset()-thumb.getWidth());}
    private void render(){
        if(item==null)return;
        boolean show=selected&&reported>=0;
        item.setBackground(show?track:background);
        thumb.setTranslationX(show?inset()+travel()*displayed/100f-thumb.getLeft():0);
        CharSequence text=show?originalLabel+" · "+displayed+"%":originalLabel;
        if(!TextUtils.equals(label.getText(),text))label.setText(text);
        item.setContentDescription(show?"自适应强度":description);
        item.setStateDescription(show?displayed+"%"+confirmation:stateDescription);
        item.setAccessibilityDelegate(show?delegate:accessibility);
        track.invalidateSelf();
    }
    private boolean touch(View view,MotionEvent event){
        if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
            if(!adjustable()||travel()<=0)return false;
            touching=true;dragging=false;abandoned=false;pointer=event.getPointerId(0);
            downX=event.getRawX();downY=event.getRawY();start=displayed;
            return true;
        }
        if(!touching)return false;
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_MOVE->{
                if(event.getPointerId(0)!=pointer){cancel();return true;}
                float dx=event.getRawX()-downX,dy=event.getRawY()-downY;
                if(!dragging&&!abandoned){
                    if(Math.abs(dy)>slop&&Math.abs(dy)>Math.abs(dx)){abandoned=true;return true;}
                    if(Math.abs(dx)>slop&&Math.abs(dx)>Math.abs(dy)){
                        dragging=true;view.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if(dragging){displayed=AdaptiveStrength.drag(start,dx,travel());render();}
            }
            case MotionEvent.ACTION_UP->{
                boolean tap=!dragging&&!abandoned;
                if(dragging)displayed=AdaptiveStrength.drag(start,event.getRawX()-downX,travel());
                boolean changed=dragging&&displayed!=start;
                int value=displayed;
                finishTouch();
                render();
                if(changed){if(!commit.test(value)){displayed=reported;render();}}
                else {displayed=reported;render();if(tap)view.performClick();}
            }
            case MotionEvent.ACTION_CANCEL,MotionEvent.ACTION_POINTER_DOWN,MotionEvent.ACTION_POINTER_UP->cancel();
        }
        return true;
    }
    private void finishTouch(){
        touching=false;dragging=false;abandoned=false;pointer=-1;
        if(item!=null&&item.getParent()!=null)item.getParent().requestDisallowInterceptTouchEvent(false);
    }
    private void cancel(){finishTouch();if(reported>=0)displayed=reported;render();}
    void detach(){
        cancel();
        if(item!=null){
            item.removeOnLayoutChangeListener(layout);thumb.removeOnLayoutChangeListener(layout);
            item.setOnTouchListener(null);item.setBackground(background);thumb.setTranslationX(0);
            label.setText(originalLabel);item.setContentDescription(description);item.setStateDescription(stateDescription);
            item.setAccessibilityDelegate(accessibility);
        }
        item=null;thumb=null;label=null;selected=false;
    }
    private final View.AccessibilityDelegate delegate=new View.AccessibilityDelegate(){
        @Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
            if(accessibility!=null)accessibility.onInitializeAccessibilityNodeInfo(host,info);
            else super.onInitializeAccessibilityNodeInfo(host,info);
            info.setClassName("android.widget.SeekBar");info.setEnabled(adjustable());
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_INT,0,100,displayed));
            if(adjustable()){
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
            }
        }
        @Override public boolean performAccessibilityAction(View host,int action,Bundle args){
            Integer value=null;
            if(action==AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()&&args!=null&&args.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE))
                value=AdaptiveStrength.clamp(Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));
            else if(action==AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)value=AdaptiveStrength.clamp(displayed+5);
            else if(action==AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)value=AdaptiveStrength.clamp(displayed-5);
            if(value!=null)return adjustable()&&(value==displayed||commit.test(value));
            return accessibility!=null?accessibility.performAccessibilityAction(host,action,args):super.performAccessibilityAction(host,action,args);
        }
    };
    private final class Track extends Drawable{
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        @Override public void draw(Canvas canvas){
            if(item==null||thumb==null)return;
            boolean dark=(activity.getResources().getConfiguration().uiMode&Configuration.UI_MODE_NIGHT_MASK)==Configuration.UI_MODE_NIGHT_YES;
            float left=inset(),top=thumb.getTop(),height=thumb.getHeight(),right=item.getWidth()-inset();
            paint.setColor(dark?0xff38383a:0xffe3e5e8);paint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(left,top,right,top+height,height/2,height/2,paint);
            float radius=height*.17f,cy=top+height/2;
            paint.setColor(dark?0xff929296:0xff92969c);paint.setStrokeWidth(Math.max(1,height*.025f));paint.setStyle(Paint.Style.STROKE);
            canvas.drawCircle(left+height/2,cy,radius,paint);canvas.drawCircle(right-height/2,cy,radius,paint);
        }
        @Override public void setAlpha(int alpha){paint.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter filter){paint.setColorFilter(filter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
