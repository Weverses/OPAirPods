// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;
import android.view.WindowInsets;
import java.util.List;

/** Independent host COUI fragments, each with its own app bar and RecyclerView. */
final class HostPageNavigation {
    static final String ARG_PAGE="com.tlsu.pods.page";
    private final HostPreferences owner;
    private final Activity activity;
    private final ColorPodsModule module;
    private final ClassLoader loader;
    private final Class<?> screenClass;
    private final java.lang.reflect.Method attachManager;
    private final java.lang.reflect.Method fragmentsGetter;
    private boolean opening;
    private Integer homeAccessibility;

    HostPageNavigation(HostPreferences owner,Activity activity,ClassLoader loader,ColorPodsModule module)throws ReflectiveOperationException{
        this.owner=owner;this.activity=activity;this.loader=loader;this.module=module;
        screenClass=Class.forName("androidx.preference.PreferenceScreen",false,loader);
        Class<?> hostFragment=HostTargets.typeOrNull(HostTargets.UI_FRAGMENT_CLASS,loader);
        if(hostFragment==null)throw new ClassNotFoundException("Host preference fragment unavailable");
        attachManager=target(NavigationTargets.ATTACH);
        fragmentsGetter=target(NavigationTargets.FRAGMENTS);
        for(String key:new String[]{NavigationTargets.SAVED,NavigationTargets.BEGIN,NavigationTargets.ANIMATIONS,
                NavigationTargets.HIDE,NavigationTargets.ADD,NavigationTargets.BACKSTACK,NavigationTargets.COMMIT,NavigationTargets.POP})target(key);
    }
    private java.lang.reflect.Method target(String key)throws NoSuchMethodException{
        var method=HostTargets.methodOrNull(key,loader);
        if(method==null)throw new NoSuchMethodException("Unresolved navigation target: "+key);
        return method;
    }
    static String pageKey(Object fragment)throws ReflectiveOperationException{
        Bundle args=(Bundle)Reflect.call(fragment,"getArguments",new Class[0]);
        return args==null?null:args.getString(ARG_PAGE);
    }
    private Object manager()throws ReflectiveOperationException{return Reflect.call(activity,"getSupportFragmentManager",new Class[0]);}
    private Object top(Object manager)throws ReflectiveOperationException{
        List<?> fragments=(List<?>)fragmentsGetter.invoke(manager);
        for(int i=fragments.size()-1;i>=0;i--){Object fragment=fragments.get(i);
            if(pageKey(fragment)!=null&&!Boolean.TRUE.equals(Reflect.call(fragment,"isHidden",new Class[0])))return fragment;
        }
        return null;
    }
    private int resource(String type,String name){
        int id=activity.getResources().getIdentifier(name,type,activity.getPackageName());
        if(id==0)throw new IllegalStateException("Missing host resource "+type+"/"+name);return id;
    }
    void open(String key){
        if(opening||owner.pageScreen(key)==null)return;
        try{
            Object manager=manager(),previous=top(manager);
            if(Boolean.TRUE.equals(target(NavigationTargets.SAVED).invoke(manager)))return;
            if(previous!=null&&key.equals(pageKey(previous)))return;
            Class<?> pageClass = HostTargets.typeOrNull(HostTargets.UI_SUBPAGE_CLASS, loader);
            if (pageClass == null) throw new ClassNotFoundException("DexKit subpage target unresolved");
            Object page = pageClass.getConstructor().newInstance();
            Bundle args=new Bundle();args.putString(ARG_PAGE,key);
            Reflect.call(page,"setArguments",new Class[]{Bundle.class},args);
            Object transaction=target(NavigationTargets.BEGIN).invoke(manager);
            target(NavigationTargets.ANIMATIONS).invoke(transaction,
                resource("anim","coui_open_slide_enter"),resource("anim","coui_open_slide_exit_noalpha"),
                resource("anim","coui_close_slide_enter_noalpha"),resource("anim","coui_close_slide_exit"));
            if(previous!=null)target(NavigationTargets.HIDE).invoke(transaction,previous);
            target(NavigationTargets.ADD).invoke(transaction,android.R.id.content,page,"colorpods.page."+key);
            target(NavigationTargets.BACKSTACK).invoke(transaction,"colorpods.page."+key);
            opening=true;target(NavigationTargets.COMMIT).invoke(transaction);
        }catch(Exception e){opening=false;module.error("COUI page open failed",e);}
    }
    boolean back(){
        if(opening)return true;
        try{Object manager=manager();if(top(manager)==null)return false;target(NavigationTargets.POP).invoke(manager);return true;}
        catch(Exception e){module.error("COUI page back failed",e);return true;}
    }
    void prepare(Object page)throws Exception{
        String key=pageKey(page);Object screen=owner.pageScreen(key);
        if(screen==null)throw new IllegalArgumentException("Unknown ColorPods page "+key);
        if(Reflect.call(page,"getPreferenceScreen",new Class[0])==screen)return;
        Object preferences=Reflect.call(page,"getPreferenceManager",new Class[0]);
        attach(screen,preferences);
        Reflect.call(page,"setPreferenceScreen",new Class[]{screenClass},screen);
    }
    private void attach(Object preference,Object manager)throws Exception{
        attachManager.invoke(preference,manager);
        if(Class.forName("androidx.preference.PreferenceGroup",false,loader).isInstance(preference)){
            var countMethod=HostTargets.methodOrNull(HostTargets.UI_PREF_COUNT,loader);
            var childMethod=HostTargets.methodOrNull(HostTargets.UI_PREF_CHILD,loader);
            if(countMethod==null||childMethod==null)throw new NoSuchMethodException("Preference traversal unresolved");
            int count=(Integer)countMethod.invoke(preference);
            for(int i=0;i<count;i++)attach(childMethod.invoke(preference,i),manager);
        }
    }
    void viewCreated(Object page,View view)throws Exception{
        opening=false;prepare(page);
        View home=activity.findViewById(resource("id","activity_standard_layout_main"));
        if(home!=null){if(homeAccessibility==null)homeAccessibility=home.getImportantForAccessibility();home.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);}
        view.setBackgroundResource(resource("drawable","coui_window_bg_with_card"));view.setClickable(true);
        Object toolbar=view.findViewById(resource("id","toolbar"));
        if(toolbar==null)throw new IllegalStateException("Host preference page has no toolbar");
        Reflect.call(toolbar,"setTitle",new Class[]{CharSequence.class},owner.pageTitle(pageKey(page)));
        Object mainToolbar=Reflect.call(activity,"getToolBar",new Class[0]);
        Drawable icon=(Drawable)Reflect.call(mainToolbar,"getNavigationIcon",new Class[0]);
        if(icon!=null&&icon.getConstantState()!=null)icon=icon.getConstantState().newDrawable(activity.getResources()).mutate();
        Reflect.call(toolbar,"setNavigationIcon",new Class[]{Drawable.class},icon);
        Reflect.call(toolbar,"setNavigationContentDescription",new Class[]{CharSequence.class},"返回");
        Reflect.call(toolbar,"setNavigationOnClickListener",new Class[]{View.OnClickListener.class},(View.OnClickListener)v->back());
        view.setOnApplyWindowInsetsListener((v,insets)->{
            var bars=insets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;
        });
        view.requestApplyInsets();
    }
    void viewDestroyed()throws ReflectiveOperationException{
        if(top(manager())!=null)return;
        View home=activity.findViewById(resource("id","activity_standard_layout_main"));
        if(home!=null&&homeAccessibility!=null){home.setImportantForAccessibility(homeAccessibility);homeAccessibility=null;}
    }
}
