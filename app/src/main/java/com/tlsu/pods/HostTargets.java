// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.util.Log;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindClass;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.matchers.ClassMatcher;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.FieldData;
import org.luckypray.dexkit.result.MethodData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.BiFunction;
import java.util.function.UnaryOperator;

/**
 * Single source of truth for every host symbol ColorPods hooks.
 *
 * <p>Named host and framework symbols use direct lookup. Obfuscated symbols
 * use DexKit fingerprints, with cached
 * descriptors for successful matches. Ambiguous matches are rejected.</p>
 *
 * <p>Fingerprints were derived from the local JADX exports in
 * {@code ../analysis}: {@code bluetooth-ext/.../AirPodsManager.java},
 * {@code bluetooth/.../OplusBTFactory.java} and {@code jadx/sources/aa/Y80.java}.
 * Direct and fuzzy targets each have one lookup strategy, with no fallback.</p>
 */
final class HostTargets {
    private static final String TAG = "ColorPodsTargets";

    // ---- Bluetooth process -------------------------------------------------
    static final String BT_FACTORY_CLASS = "bt.factory.class";
    static final String BT_FACTORY_NEW_INSTANCE = "bt.factory.newInstance";
    static final String BT_STUB_ON_TRANSACT = "bt.stub.onTransact";
    static final String BT_HFP_AT_RESPONSE = "bt.hfp.atResponseStringNative";
    static final String BT_AVRCP_MEDIA_KEY = "bt.avrcp.sendMediaKeyEvent";
    static final String BT_ENHANCER_CLASS = "bt.enhancer.class";
    static final String BT_ADAPTER_SERVICE_CLASS = "bt.adapterService.class";
    static final String BT_ADAPTER_SERVICE_FIELD = "bt.adapterService.field";
    static final String APP_ATTACH = "framework.application.attach";

    static final String EXT_MANAGER_CLASS = "ext.manager.class";
    static final String EXT_MANAGER_PARSE = "ext.manager.parsePacket";
    static final String EXT_MANAGER_HANDLE = "ext.manager.handleSocket";
    static final String EXT_MANAGER_CLOSE = "ext.manager.closeSocket";
    static final String EXT_MANAGER_CONNECT = "ext.manager.connectSocket";
    static final String EXT_MANAGER_BATTERY_UPDATE = "ext.manager.updateBatteryInfo";
    static final String EXT_MANAGER_BATTERY_GET = "ext.manager.getBatteryInfo";
    static final String EXT_MANAGER_BATTERY_NOTIFY = "ext.manager.notifyBatteryLevelChanged";
    static final String EXT_MANAGER_POD_STATUS = "ext.manager.updatePodStatus";
    static final String EXT_MANAGER_INSTANCE = "ext.manager.instance";
    static final String EXT_MEDIA_CLASS = "ext.media.class";
    static final String EXT_MEDIA_SEND_PLAY = "ext.media.sendPlay";
    static final String EXT_MEDIA_POD_STATUS = "ext.media.onPodStatusChanged";

    // ---- MyDevices process -------------------------------------------------
    static final String UI_FRAGMENT_CLASS = "ui.fragment.class";
    static final String UI_FRAGMENT_FIND_PREFERENCE = "ui.fragment.findPreference";
    static final String UI_FRAGMENT_CREATE_PREFS = "ui.fragment.onCreatePreferences";
    static final String UI_FRAGMENT_VIEW_CREATED = "ui.fragment.onViewCreated";
    static final String UI_FRAGMENT_DESTROY_VIEW = "ui.fragment.onDestroyView";
    static final String UI_FRAGMENT_DESTROY = "ui.fragment.onDestroy";
    static final String UI_ACTIVITY_CLASS = "ui.activity.class";
    static final String UI_ACTIVITY_TOOLBAR_LAMBDA = "ui.activity.toolbarBackLambda";
    static final String UI_ACTIVITY_INIT_TOOLBAR = "ui.activity.initToolbar";
    static final String UI_ACTIVITY_BACK_PRESSED = "ui.activity.onBackPressed";
    static final String UI_ACTIVITY_RESUME = "ui.activity.onResume";
    static final String UI_ACTIVITY_BATTERY_UPDATE = "ui.activity.updateBatteryInfo";
    static final String UI_CASE_BATTERY_VISIBLE = "ui.battery.caseVisible";
    static final String UI_NOISE_SELECTION = "ui.noise.selection";
    static final String UI_NOISE_SELECTED = "ui.noise.selected";
    static final String UI_NOISE_ITEMS = "ui.noise.items";
    static final String UI_ACTIVITY_PREFERENCES_FRAGMENT = "ui.activity.preferencesFragment";
    static final String UI_SUBPAGE_CLASS = "ui.subpage.class";
    static final String UI_SUBPAGE_CREATE_PREFS = "ui.subpage.onCreatePreferences";
    static final String UI_SUBPAGE_VIEW_CREATED = "ui.subpage.onViewCreated";
    static final String UI_SUBPAGE_DESTROY_VIEW = "ui.subpage.onDestroyView";
    static final String UI_RECEIVER_CLASS = "ui.receiver.class";
    static final String UI_CORE_RECEIVER_CLASS = "ui.coreReceiver.class";
    static final String UI_RECEIVER_ON_RECEIVE = "ui.receiver.onReceive";
    static final String UI_CORE_RECEIVER_ON_RECEIVE = "ui.coreReceiver.onReceive";
    static final String UI_CAPSULE_PRODUCER_CLASS = "ui.capsule.producer.class";
    static final String UI_CAPSULE_PRODUCER_PLAIN = "ui.capsule.producer.plain";
    static final String UI_CAPSULE_PRODUCER_INIT = "ui.capsule.producer.init";
    static final String UI_CLICK_EVENT_CLASS = "ui.clickEvent.class";
    static final String UI_CLICK_ACTIVE_BUTTON = "ui.clickEvent.activeButton";
    static final String UI_DEVICE_INFO = "ui.deviceInfo.lookup";
    static final String UI_MODEL_MAX = "ui.model.isMax";
    static final String UI_MODEL_PRO = "ui.model.isPro";
    static final String UI_CAPSULE_START = "ui.capsule.start";
    static final String UI_CAPSULE_END = "ui.capsule.end";
    static final String UI_CONNECTION_UPDATE = "ui.connection.update";
    static final String UI_PREF_ADD = "ui.preference.add";
    static final String UI_PREF_FIND = "ui.preference.find";
    static final String UI_PREF_SUMMARY = "ui.preference.summary";
    static final String UI_PREF_KEY = "ui.preference.key";
    static final String UI_PREF_TITLE = "ui.preference.title";
    static final String UI_PREF_CHILD = "ui.preference.child";
    static final String UI_PREF_COUNT = "ui.preference.count";
    static final String UI_PREF_ORDER = "ui.preference.order";
    static final String UI_PREF_REMOVE = "ui.preference.remove";


    private static final String MANAGER = "com.oplus.bluetooth.feature.airpods.AirPodsManager";
    private static final String MEDIA = "com.oplus.bluetooth.feature.airpods.AirPodsMediaController";
    private static final String FACTORY = "com.oplus.bluetooth.common.OplusBTFactory";
    private static final String STUB = "android.bluetooth.IOplusBluetooth$Stub";
    private static final String HFP = "com.android.bluetooth.hfp.HeadsetNativeInterface";
    private static final String ENHANCER = "com.oplus.bluetooth.btservice.AdapterServiceEnhancer";
    private static final String ADAPTER_SERVICE = "com.android.bluetooth.btservice.AdapterService";
    private static final String FRAGMENT = "com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingPreferencesFragment";
    private static final String ACTIVITY = "com.heytap.mydevices.plugin.airpods.ui.AirpodsSettingActivity";
    private static final String RECEIVER = "com.oplus.mydevices.receiver.AccountReceiver";
    private static final String CORE_RECEIVER = "com.heytap.mydevices.plugin.airpods.noisereduction.NoiseReductionCommand";
    private static final String CAPSULE_INFO = "com.oplus.mydevices.domain.entities.linkage.CapsuleInfo";
    private static final String CLICK_EVENT = "com.heytap.mydevices.core.seedling.SeedlingCardClickEvent";

    private static final String[] NONE = new String[0];
    private static final Map<String, Spec> SPECS = new LinkedHashMap<>();

    private HostTargets() {}

    static {
        // Named OEM and Android symbols are resolved directly. These do not
        // need a dex scan; inherited lifecycle methods are found in superclasses.
        directClass(BT_FACTORY_CLASS, DexKitHost.BLUETOOTH, FACTORY);
        directMethod(BT_FACTORY_NEW_INSTANCE, DexKitHost.BLUETOOTH, FACTORY, "newInstance", new String[]{"java.lang.String", "java.lang.String"}, "java.lang.Object", 1);
        directMethod(BT_STUB_ON_TRANSACT, DexKitHost.BLUETOOTH, STUB, "onTransact", new String[]{"int", "android.os.Parcel", "android.os.Parcel", "int"}, "boolean", 0);
        directMethod(BT_HFP_AT_RESPONSE, DexKitHost.BLUETOOTH, HFP, "atResponseStringNative", new String[]{"java.lang.String", "[B"}, "boolean", 0);
        directMethod(BT_AVRCP_MEDIA_KEY, DexKitHost.BLUETOOTH, "com.android.bluetooth.avrcp.AvrcpNativeInterface", "sendMediaKeyEvent", new String[]{"java.lang.String", "int", "boolean"}, "void", 0);
        directClass(BT_ENHANCER_CLASS, DexKitHost.BLUETOOTH, ENHANCER);
        directClass(BT_ADAPTER_SERVICE_CLASS, DexKitHost.BLUETOOTH, ADAPTER_SERVICE);
        directField(BT_ADAPTER_SERVICE_FIELD, DexKitHost.BLUETOOTH, ADAPTER_SERVICE, "sAdapterService", ADAPTER_SERVICE);
        directMethod(APP_ATTACH, DexKitHost.BLUETOOTH, "android.app.Application", "attach", new String[]{"android.content.Context"}, "void", 0);

        directClass(EXT_MANAGER_CLASS, DexKitHost.BLUETOOTH_EXT, MANAGER);
        directMethod(EXT_MANAGER_PARSE, DexKitHost.BLUETOOTH_EXT, MANAGER, "parseAirPodsPacket", new String[]{"[B", "int", "android.bluetooth.BluetoothDevice"}, "void", 0);
        directMethod(EXT_MANAGER_HANDLE, DexKitHost.BLUETOOTH_EXT, MANAGER, "handleAirPodsSocket", new String[]{"android.bluetooth.BluetoothSocket", "android.bluetooth.BluetoothDevice"}, "void", 0);
        directMethod(EXT_MANAGER_CLOSE, DexKitHost.BLUETOOTH_EXT, MANAGER, "closeSocketForDevice", new String[]{"android.bluetooth.BluetoothDevice"}, "void", 0);
        directMethod(EXT_MANAGER_CONNECT, DexKitHost.BLUETOOTH_EXT, MANAGER, "connectToAirPodsSocket", new String[]{"android.bluetooth.BluetoothDevice"}, "void", 0);
        directMethod(EXT_MANAGER_BATTERY_UPDATE, DexKitHost.BLUETOOTH_EXT, MANAGER, "updateBatteryInfo", new String[]{"android.bluetooth.BluetoothDevice", "boolean", "int", "int", "int", "int", "int", "int"}, "void", 0);
        directMethod(EXT_MANAGER_BATTERY_GET, DexKitHost.BLUETOOTH_EXT, MANAGER, "getBatteryInfo", new String[]{"android.bluetooth.BluetoothDevice"}, "[I", 0);
        directMethod(EXT_MANAGER_BATTERY_NOTIFY, DexKitHost.BLUETOOTH_EXT, MANAGER, "notifyBatteryLevelChanged", new String[]{"android.bluetooth.BluetoothDevice", "[I"}, "void", 0);
        directMethod(EXT_MANAGER_POD_STATUS, DexKitHost.BLUETOOTH_EXT, MANAGER, "updatePodStatus", new String[]{"android.bluetooth.BluetoothDevice", "int", "int"}, "void", 0);
        directField(EXT_MANAGER_INSTANCE, DexKitHost.BLUETOOTH_EXT, MANAGER, "sInstance", MANAGER);
        directClass(EXT_MEDIA_CLASS, DexKitHost.BLUETOOTH_EXT, MEDIA);
        directMethod(EXT_MEDIA_SEND_PLAY, DexKitHost.BLUETOOTH_EXT, MEDIA, "sendPlay", NONE, "void", 0);
        directMethod(EXT_MEDIA_POD_STATUS, DexKitHost.BLUETOOTH_EXT, MEDIA, "onPodStatusChanged", new String[]{"boolean", "boolean"}, "void", 0);

        directClass(UI_FRAGMENT_CLASS, DexKitHost.MYDEVICES, FRAGMENT);
        directClass(UI_ACTIVITY_CLASS, DexKitHost.MYDEVICES, ACTIVITY);
        directMethod(UI_ACTIVITY_BATTERY_UPDATE, DexKitHost.MYDEVICES, ACTIVITY, "updateBatteryInfo", new String[]{"[I"}, "void", 0);
        fingerprintMethod(UI_CASE_BATTERY_VISIBLE, new String[]{"[I"}, "boolean", b -> {
            var update = DexQueries.one(DexQueries.methods(b, new MethodMatcher().declaredClass(ACTIVITY)
                    .name("updateBatteryInfo").paramTypes("int[]").returnType("void")));
            // The case guard reads level index 2 and status index 5, comparing
            // against DISCONNECTED (4). Resolve through the native caller graph.
            return DexQueries.filter(DexQueries.methods(b, new MethodMatcher().paramTypes("int[]")
                    .returnType("boolean").usingNumbers(2, 5, 4)), update.getInvokes()::contains);
        });
        String selector="com.oplus.mydevices.deviceui.devicecard.view.IconTextSelectorSettings";
        directMethod(UI_NOISE_SELECTION,DexKitHost.MYDEVICES,selector,"setSelectedPosition",new String[]{"int"},"void",0);
        directMethod(UI_NOISE_SELECTED,DexKitHost.MYDEVICES,selector,"getSelectedPosition",NONE,"int",0);
        directMethod(UI_NOISE_ITEMS,DexKitHost.MYDEVICES,ACTIVITY,"getNoiseSelectorItems",NONE,"java.util.ArrayList",0);
        directMethod(UI_ACTIVITY_TOOLBAR_LAMBDA, DexKitHost.MYDEVICES, ACTIVITY,
                "initToolbar$lambda$6$lambda$5", new String[]{ACTIVITY, "android.view.View"}, "void", 1);
        directMethod(UI_ACTIVITY_INIT_TOOLBAR, DexKitHost.MYDEVICES, ACTIVITY, "initToolbar", new String[]{"java.lang.String"}, "void", 0);
        directMethod(UI_ACTIVITY_BACK_PRESSED, DexKitHost.MYDEVICES, ACTIVITY, "onBackPressed", NONE, "void", 0);
        directMethod(UI_ACTIVITY_RESUME, DexKitHost.MYDEVICES, ACTIVITY, "onResume", NONE, "void", 0);
        directMethod(UI_ACTIVITY_PREFERENCES_FRAGMENT, DexKitHost.MYDEVICES, ACTIVITY, "preferencesFragment", NONE, FRAGMENT, 0);
        directMethod(UI_FRAGMENT_FIND_PREFERENCE, DexKitHost.MYDEVICES, FRAGMENT, "findPreference", new String[]{"java.lang.CharSequence"}, null, 0);
        directMethod(UI_FRAGMENT_CREATE_PREFS, DexKitHost.MYDEVICES, FRAGMENT, "onCreatePreferences", new String[]{"android.os.Bundle", "java.lang.String"}, "void", 0);
        directMethod(UI_FRAGMENT_VIEW_CREATED, DexKitHost.MYDEVICES, FRAGMENT, "onViewCreated", new String[]{"android.view.View", "android.os.Bundle"}, "void", 0);
        directMethod(UI_FRAGMENT_DESTROY_VIEW, DexKitHost.MYDEVICES, FRAGMENT, "onDestroyView", NONE, "void", 0);
        directMethod(UI_FRAGMENT_DESTROY, DexKitHost.MYDEVICES, FRAGMENT, "onDestroy", NONE, "void", 0);

        // Only the class name is obfuscated; its Android lifecycle method names
        // are stable and are looked up directly on the resolved class.
        clazz(UI_SUBPAGE_CLASS, DexKitHost.MYDEVICES, null, false,
                classQuery(m -> m.usingStrings("androidx.preference.PreferenceFragment.DIALOG")
                        .addMethod(new MethodMatcher().name("onCreatePreferences")
                                .paramTypes("android.os.Bundle", "java.lang.String").returnType("void"))
                        .addMethod(new MethodMatcher().name("setEnableCOUIPreferenceDivider")
                                .paramTypes("boolean").returnType("void"))));
        directMethod(UI_SUBPAGE_CREATE_PREFS, DexKitHost.MYDEVICES, "@" + UI_SUBPAGE_CLASS, "onCreatePreferences", new String[]{"android.os.Bundle", "java.lang.String"}, "void", 0);
        directMethod(UI_SUBPAGE_VIEW_CREATED, DexKitHost.MYDEVICES, "@" + UI_SUBPAGE_CLASS, "onViewCreated", new String[]{"android.view.View", "android.os.Bundle"}, "void", 0);
        directMethod(UI_SUBPAGE_DESTROY_VIEW, DexKitHost.MYDEVICES, "@" + UI_SUBPAGE_CLASS, "onDestroyView", NONE, "void", 0);

        directClass(UI_RECEIVER_CLASS, DexKitHost.MYDEVICES, RECEIVER);
        directClass(UI_CORE_RECEIVER_CLASS, DexKitHost.MYDEVICES, CORE_RECEIVER);
        directMethod(UI_RECEIVER_ON_RECEIVE, DexKitHost.MYDEVICES, RECEIVER, "onReceive", new String[]{"android.content.Context", "android.content.Intent"}, "void", 0);
        directMethod(UI_CORE_RECEIVER_ON_RECEIVE, DexKitHost.MYDEVICES, CORE_RECEIVER, "onReceive", new String[]{"android.content.Context", "android.content.Intent"}, "void", 0);

        // The producer class and its two JSON-producing methods are obfuscated.
        clazz(UI_CAPSULE_PRODUCER_CLASS, DexKitHost.MYDEVICES, null, false,
                classQuery(m -> m.usingStrings("capsuleInfoPrimaryText", "isReConnectable", "isShowButton", "initData")));
        method(UI_CAPSULE_PRODUCER_PLAIN, DexKitHost.MYDEVICES, null, null,
                new String[]{CAPSULE_INFO}, "org.json.JSONObject", 0, false,
                methodInResolvedClass(UI_CAPSULE_PRODUCER_CLASS, (m, owner) -> m.declaredClass(owner).paramTypes(CAPSULE_INFO).returnType("org.json.JSONObject")
                        .usingEqStrings("capsuleInfoPrimaryText", "isShowButton")));
        method(UI_CAPSULE_PRODUCER_INIT, DexKitHost.MYDEVICES, null, null,
                new String[]{CAPSULE_INFO}, "org.json.JSONObject", 0, false,
                methodInResolvedClass(UI_CAPSULE_PRODUCER_CLASS, (m, owner) -> m.declaredClass(owner).paramTypes(CAPSULE_INFO).returnType("org.json.JSONObject").usingStrings("initData")));
        directClass(UI_CLICK_EVENT_CLASS, DexKitHost.MYDEVICES, CLICK_EVENT);
        directMethod(UI_CLICK_ACTIVE_BUTTON, DexKitHost.MYDEVICES, CLICK_EVENT, "clickActiveButton", new String[]{"java.lang.String"}, "void", 0);
        method(UI_DEVICE_INFO, DexKitHost.MYDEVICES, null, null,
                new String[]{"java.lang.String"}, "com.oplus.mydevices.domain.entities.airpods.DeviceInfo", 0, false,
                bridge -> bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                        .paramTypes("java.lang.String").returnType("com.oplus.mydevices.domain.entities.airpods.DeviceInfo")
                        .usingEqStrings("SP_AIRPODS_DEVICE_INFO"))));
        method(UI_MODEL_MAX, DexKitHost.MYDEVICES, null, null, new String[]{"java.lang.String"}, "boolean", 0, false,
                bridge -> bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                        .paramTypes("java.lang.String").returnType("boolean").usingEqStrings("AirPods Max"))));
        method(UI_MODEL_PRO, DexKitHost.MYDEVICES, null, null, new String[]{"java.lang.String"}, "boolean", 0, false,
                bridge -> bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                        .paramTypes("java.lang.String").returnType("boolean").usingEqStrings("AirPods Pro"))));
        method(UI_CAPSULE_START, DexKitHost.MYDEVICES, null, null, new String[]{CAPSULE_INFO, null}, "void", 0, false,
                bridge -> bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                        .declaredClass("com.heytap.mydevices.core.seedling.SeedlingCardHelper")
                        .paramCount(2).returnType("void").usingEqStrings("key_capsule_info"))));
        method(UI_CAPSULE_END, DexKitHost.MYDEVICES, null, null, NONE, "void", 0, false,
                bridge -> bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                        .declaredClass("com.heytap.mydevices.core.seedling.SeedlingCardHelper")
                        .paramTypes().returnType("void").usingEqStrings("sendEndSeedling"))));
        method(UI_CONNECTION_UPDATE, DexKitHost.MYDEVICES, null, null,
                new String[]{"java.lang.String", "android.bluetooth.BluetoothDevice"}, "void", 0, false,
                bridge -> bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                        .declaredClass("com.heytap.mydevices.plugin.airpods.core.AirpodsBluetoothManager")
                        .paramTypes("java.lang.String", "android.bluetooth.BluetoothDevice").returnType("void")
                        .usingStrings("currentstate already is"))));
        method(UI_PREF_ADD, DexKitHost.MYDEVICES, null, null,
                new String[]{"androidx.preference.Preference"}, "boolean", 0, false,
                bridge -> bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                        .declaredClass("androidx.preference.PreferenceGroup")
                        .paramTypes("androidx.preference.Preference").returnType("boolean")
                        .usingStrings("Found duplicated key"))));
        method(UI_PREF_FIND, DexKitHost.MYDEVICES, null, null,
                new String[]{"java.lang.CharSequence"}, "androidx.preference.Preference", 0, false,
                bridge -> bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                        .declaredClass("androidx.preference.PreferenceGroup")
                        .paramTypes("java.lang.CharSequence").returnType("androidx.preference.Preference")
                        .usingEqStrings("Key cannot be null"))));
        method(UI_PREF_SUMMARY, DexKitHost.MYDEVICES, null, null,
                new String[]{"java.lang.CharSequence"}, "void", 0, false,
                bridge -> bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                        .declaredClass("androidx.preference.Preference")
                        .paramTypes("java.lang.CharSequence").returnType("void")
                        .usingEqStrings("Preference already has a SummaryProvider set."))));
        method(UI_PREF_KEY, DexKitHost.MYDEVICES, null, null,
                new String[]{"java.lang.String"}, "void", 0, false, HostTargets::findPreferenceKeySetter);
        method(UI_PREF_TITLE, DexKitHost.MYDEVICES, null, null,
                new String[]{"java.lang.CharSequence"}, "void", 0, false, HostTargets::findPreferenceTitleSetter);
        method(UI_PREF_CHILD, DexKitHost.MYDEVICES, null, null, new String[]{"int"},
                "androidx.preference.Preference", 0, false, bridge -> findPreferenceTraversal(bridge, true));
        method(UI_PREF_COUNT, DexKitHost.MYDEVICES, null, null, NONE,
                "int", 0, false, bridge -> findPreferenceTraversal(bridge, false));
        method(UI_PREF_ORDER, DexKitHost.MYDEVICES, null, null, new String[]{"int"}, "void", 0, false,
                HostTargets::findPreferenceOrderSetter);
        method(UI_PREF_REMOVE, DexKitHost.MYDEVICES, null, null,
                new String[]{"androidx.preference.Preference"}, "boolean", 0, false, HostTargets::findPreferenceRemover);
        PreferenceTargets.register();
        NavigationTargets.register();
        CapsuleTargets.register();
    }

    // ------------------------------------------------------------------ API

    static Class<?> typeOrNull(String key, ClassLoader loader) {
        Object value = resolve(key, loader);
        return value instanceof Class ? (Class<?>) value : null;
    }

    static Method methodOrNull(String key, ClassLoader loader) {
        Object value = resolve(key, loader);
        return value instanceof Method ? (Method) value : null;
    }

    static Field fieldOrNull(String key, ClassLoader loader) {
        Object value = resolve(key, loader);
        return value instanceof Field ? (Field) value : null;
    }

    /** Resolved member name, for call sites that still use runtime reflection. */
    static String memberName(String key, ClassLoader loader) {
        Method method = methodOrNull(key, loader);
        return method == null ? null : method.getName();
    }

    /** Read-only view for validating the rule table in JVM unit tests. */
    static java.util.Set<String> registeredKeys() { return SPECS.keySet(); }

    static boolean isExactFirst(String key) {
        Spec spec = SPECS.get(key);
        return spec != null && spec.exactFirst;
    }

    static boolean hasFingerprint(String key) {
        Spec spec = SPECS.get(key);
        return spec != null && spec.query != null;
    }

    private static String javaName(String name) {
        if (name == null) return "";
        if (name.startsWith("L") && name.endsWith(";")) name = name.substring(1, name.length() - 1);
        return name.replace('/', '.');
    }

    private static Object resolve(String key, ClassLoader loader) {
        Spec spec = SPECS.get(key);
        if (spec == null) throw new IllegalArgumentException("Unknown host target: " + key);
        String cacheKey = spec.scope + ":" + key;
        if (TargetCache.contains(cacheKey)) return TargetCache.get(cacheKey);

        // Named targets use direct lookup only; no cached fuzzy descriptor can
        // override their identity and no DexKit fallback is attempted.
        if (spec.exactFirst) {
            Object value = spec.exact(loader);
            if (spec.accepts(value)) { TargetCache.put(cacheKey, value); return value; }
            Log.w(TAG, "named host target unresolved: " + key);
            return null;
        }

        // A descriptor saved from an earlier successful fingerprint scan.
        TargetCache.Descriptor saved = TargetCache.Descriptor.parse(TargetCache.loadDescriptor(cacheKey));
        if (saved != null) {
            try {
                Object value = saved.resolve(loader);
                if (spec.accepts(value)) { accept(cacheKey, value, false); return value; }
            } catch (Throwable ignored) { /* stale index after an OTA */ }
        }

        // Structural / string fingerprint via DexKit.
        if (spec.query != null && DexKitHost.available()) {
            try {
                List<?> hits = DexKitHost.with(spec.scope, spec.query::find);
                List<Object> candidates = new ArrayList<>();
                for (Object hit : hits) {
                    Object value = materialize(hit, loader);
                    if (spec.accepts(value) && !containsSame(candidates, value)) candidates.add(value);
                }
                Object preferred = candidates.size() == 1 ? candidates.get(0) : null;
                if (preferred != null) {
                    Log.i(TAG, "resolved " + key + " by DexKit -> " + describe(preferred));
                    accept(cacheKey, preferred, true);
                    return preferred;
                }
                if (!candidates.isEmpty()) {
                    Log.w(TAG, "ambiguous DexKit result for " + key + ": " + candidates.size()
                            + " candidates");
                }
            } catch (Throwable t) {
                Log.w(TAG, "DexKit lookup failed for " + key + ": " + t);
            }
        }

        Log.w(TAG, "host target unresolved: " + key);
        return null;
    }

    private static void accept(String cacheKey, Object value, boolean persist) {
        TargetCache.put(cacheKey, value);
        if (!persist) return;
        TargetCache.Descriptor descriptor;
        if (value instanceof Method) descriptor = TargetCache.Descriptor.ofMethod((Method) value);
        else if (value instanceof Field) descriptor = TargetCache.Descriptor.ofField((Field) value);
        else if (value instanceof Class) descriptor = TargetCache.Descriptor.ofClass((Class<?>) value);
        else return;
        TargetCache.saveDescriptor(cacheKey, descriptor.encode());
    }

    private static boolean containsSame(List<Object> values, Object value) {
        for (Object current : values) if (current.equals(value)) return true;
        return false;
    }

    private static Object materialize(Object hit, ClassLoader loader) throws Exception {
        if (hit instanceof MethodData) {
            Method method = ((MethodData) hit).getMethodInstance(loader);
            method.setAccessible(true);
            return method;
        }
        if (hit instanceof ClassData) return ((ClassData) hit).getInstance(loader);
        if (hit instanceof FieldData) {
            Field field = ((FieldData) hit).getFieldInstance(loader);
            field.setAccessible(true);
            return field;
        }
        return null;
    }

    private static String describe(Object value) {
        if (value instanceof Method) {
            Method m = (Method) value;
            return m.getDeclaringClass().getName() + "." + m.getName()
                    + Arrays.toString(m.getParameterTypes());
        }
        if (value instanceof Field) {
            Field f = (Field) value;
            return f.getDeclaringClass().getName() + "." + f.getName();
        }
        return String.valueOf(value);
    }

    // -------------------------------------------------------------- builders

    private static void directClass(String key, String scope, String name) {
        clazz(key, scope, name, true, null);
    }

    private static void directMethod(String key, String scope, String owner, String name,
                                     String[] params, String ret, int staticMode) {
        method(key, scope, owner, name, params, ret, staticMode, true, null);
    }

    private static void directField(String key, String scope, String owner, String name, String type) {
        field(key, scope, owner, name, type, true, null);
    }

    private static void clazz(String key, String scope, String exact, boolean exactFirst, Query query) {
        clazz(key, scope, exact, exactFirst, query, null);
    }

    private static void clazz(String key, String scope, String exact, boolean exactFirst, Query query,
                              Predicate<Object> validator) {
        SPECS.put(key, new Spec(key, scope, Kind.CLASS, exact, exact, NONE, null, -1,
                exactFirst, query, validator));
    }

    private static void method(String key, String scope, String owner, String name, String[] params,
                               String ret, int staticMode, boolean exactFirst, Query query) {
        SPECS.put(key, new Spec(key, scope, Kind.METHOD, owner, name, params, ret,
                staticMode, exactFirst, query, null));
    }

    private static void field(String key, String scope, String owner, String name, String type,
                              boolean exactFirst, Query query) {
        SPECS.put(key, new Spec(key, scope, Kind.FIELD, owner, name, NONE, type, -1,
                exactFirst, query, null));
    }

    private static Query methodInResolvedClass(String classKey,
                                                BiFunction<MethodMatcher, String, MethodMatcher> matcher) {
        return bridge -> {
            List<?> classes = SPECS.get(classKey).query.find(bridge);
            if (classes.size() != 1 || !(classes.get(0) instanceof ClassData)) return List.of();
            String owner = javaName(((ClassData) classes.get(0)).getName());
            return bridge.findMethod(new FindMethod()
                    .matcher(matcher.apply(new MethodMatcher(), owner)));
        };
    }

    private static Query classQuery(UnaryOperator<ClassMatcher> matcher) {
        return bridge -> bridge.findClass(new FindClass()
                .matcher(matcher.apply(new ClassMatcher())));
    }

    /** Follow findPreference -> key getter -> backing field -> setter, without relying on R8 names. */
    private static List<?> findPreferenceKeySetter(DexKitBridge bridge) {
        var finders = bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                .declaredClass("androidx.preference.PreferenceGroup")
                .paramTypes("java.lang.CharSequence").returnType("androidx.preference.Preference")
                .usingEqStrings("Key cannot be null")));
        if (finders.size() != 1) return List.of();
        List<MethodData> candidates = new ArrayList<>();
        for (MethodData getter : finders.get(0).getInvokes()) {
            if (!"androidx.preference.Preference".equals(javaName(getter.getDeclaredClassName()))
                    || getter.getParamCount() != 0 || !"java.lang.String".equals(javaName(getter.getReturnTypeName()))) continue;
            for (var used : getter.getUsingFields()) {
                FieldData field = used.getField();
                if (!"androidx.preference.Preference".equals(javaName(field.getDeclaredClassName()))
                        || !"java.lang.String".equals(javaName(field.getTypeName()))) continue;
                for (MethodData writer : field.getWriters()) {
                    if ("androidx.preference.Preference".equals(javaName(writer.getDeclaredClassName()))
                            && "void".equals(javaName(writer.getReturnTypeName()))
                            && writer.getParamTypeNames().equals(List.of("java.lang.String"))
                            && !candidates.contains(writer)) candidates.add(writer);
                }
            }
        }
        return candidates;
    }

    /** The dependency error includes the preference title; trace its field to its writer. */
    private static List<?> findPreferenceTitleSetter(DexKitBridge bridge) {
        var matches = bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                .declaredClass("androidx.preference.Preference").paramTypes().returnType("void")
                .usingStrings("not found for preference")));
        if (matches.size() != 1) return List.of();
        List<MethodData> candidates = new ArrayList<>();
        for (var used : matches.get(0).getUsingFields()) {
            FieldData field = used.getField();
            if (!"androidx.preference.Preference".equals(javaName(field.getDeclaredClassName()))
                    || !"java.lang.CharSequence".equals(javaName(field.getTypeName()))) continue;
            for (MethodData writer : field.getWriters())
                if ("androidx.preference.Preference".equals(javaName(writer.getDeclaredClassName()))
                        && "void".equals(javaName(writer.getReturnTypeName()))
                        && writer.getParamTypeNames().equals(List.of("java.lang.CharSequence"))
                        && !candidates.contains(writer)) candidates.add(writer);
        }
        return candidates;
    }

    private static List<?> findPreferenceTraversal(DexKitBridge bridge, boolean child) {
        var finders = bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                .declaredClass("androidx.preference.PreferenceGroup")
                .paramTypes("java.lang.CharSequence").returnType("androidx.preference.Preference")
                .usingEqStrings("Key cannot be null")));
        if (finders.size() != 1) return List.of();
        List<MethodData> results = new ArrayList<>();
        for (MethodData method : finders.get(0).getInvokes()) {
            if (!"androidx.preference.PreferenceGroup".equals(javaName(method.getDeclaredClassName()))) continue;
            boolean match = child ? "androidx.preference.Preference".equals(javaName(method.getReturnTypeName()))
                    && method.getParamTypeNames().equals(List.of("int"))
                    : "int".equals(javaName(method.getReturnTypeName())) && method.getParamCount() == 0;
            if (match && !results.contains(method)) results.add(method);
        }
        return results;
    }

    private static List<?> findPreferenceOrderSetter(DexKitBridge bridge) {
        var adders = bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                .declaredClass("androidx.preference.PreferenceGroup")
                .paramTypes("androidx.preference.Preference").returnType("boolean")
                .usingStrings("Found duplicated key")));
        if (adders.size() != 1) return List.of();
        List<MethodData> results = new ArrayList<>();
        for (MethodData method : adders.get(0).getInvokes())
            if ("androidx.preference.Preference".equals(javaName(method.getDeclaredClassName()))
                    && "void".equals(javaName(method.getReturnTypeName()))
                    && method.getParamTypeNames().equals(List.of("int"))
                    && !results.contains(method)) results.add(method);
        return results;
    }

    private static List<?> findPreferenceRemover(DexKitBridge bridge) {
        var internals=bridge.findMethod(new FindMethod().matcher(new MethodMatcher()
                .declaredClass("androidx.preference.PreferenceGroup")
                .paramTypes("androidx.preference.Preference").returnType("boolean")
                .addInvoke(new MethodMatcher().declaredClass("java.util.List").name("remove")
                        .paramTypes("java.lang.Object").returnType("boolean"))));
        if(internals.size()!=1)return List.of();
        List<MethodData> results=new ArrayList<>();
        for(MethodData caller:internals.get(0).getCallers())
            if("androidx.preference.PreferenceGroup".equals(javaName(caller.getDeclaredClassName()))
                    && "boolean".equals(javaName(caller.getReturnTypeName()))
                    && caller.getParamTypeNames().equals(List.of("androidx.preference.Preference"))
                    && !results.contains(caller))results.add(caller);
        return results;
    }

    private enum Kind { CLASS, METHOD, FIELD }

    interface Query { List<?> find(DexKitBridge bridge) throws Exception; }

    static void fingerprintMethod(String key, String[] params, String result, Query query) {
        method(key, DexKitHost.MYDEVICES, null, null, params, result, 0, false, query);
    }

    static void fingerprintStaticMethod(String key, String[] params, String result, Query query) {
        method(key, DexKitHost.MYDEVICES, null, null, params, result, 1, false, query);
    }

    static void fingerprintField(String key, String type, Query query) {
        field(key, DexKitHost.MYDEVICES, null, null, type, false, query);
    }

    /** Runs the production query without loading or initializing any host class. */
    static List<?> fingerprintMatches(String key, DexKitBridge bridge) throws Exception {
        Spec spec = SPECS.get(key);
        return spec.query == null ? List.of() : spec.query.find(bridge);
    }

    private static final class Spec {
        final String key, scope, owner, name, ret;
        final String[] params;
        final Kind kind;
        final int staticMode; // -1 any, 0 instance, 1 static
        final boolean exactFirst;
        final Query query;
        final Predicate<Object> validator;

        Spec(String key, String scope, Kind kind, String owner, String name, String[] params,
             String ret, int staticMode, boolean exactFirst, Query query,
             Predicate<Object> validator) {
            this.key = key;
            this.scope = scope;
            this.kind = kind;
            this.owner = owner;
            this.name = name;
            this.params = params == null ? NONE : params;
            this.ret = ret;
            this.staticMode = staticMode;
            this.exactFirst = exactFirst;
            this.query = query;
            this.validator = validator;
        }

        boolean accepts(Object value) {
            return acceptsStructure(value) && (validator == null || validator.test(value));
        }

        private boolean acceptsStructure(Object value) {
            if (value == null) return false;
            switch (kind) {
                case CLASS:
                    return value instanceof Class;
                case FIELD:
                    if (!(value instanceof Field)) return false;
                    Field field = (Field) value;
                    return matchesType(field.getType().getName(), ret);
                case METHOD:
                default:
                    if (!(value instanceof Method)) return false;
                    Method method = (Method) value;
                    if (staticMode == 1 && !Modifier.isStatic(method.getModifiers())) return false;
                    if (staticMode == 0 && Modifier.isStatic(method.getModifiers())) return false;
                    if (params.length > 0 || requireExactSignature()) {
                        List<String> actual = TargetCache.Descriptor.typeNames(method.getParameterTypes());
                        if (actual.size() != params.length) return false;
                        for (int i = 0; i < params.length; i++)
                            if (params[i] != null && !params[i].equals(actual.get(i))) return false;
                    }
                    return ret == null || matchesType(method.getReturnType().getName(), ret);
            }
        }

        private boolean requireExactSignature() { return kind == Kind.METHOD; }

        private boolean matchesType(String actual, String expected) {
            return expected == null || expected.equals(actual);
        }

        Object exact(ClassLoader loader) {
            try {
                Class<?> cls = owner.startsWith("@") ? typeOrNull(owner.substring(1), loader)
                        : Class.forName(owner, false, loader);
                if (cls == null) return null;
                switch (kind) {
                    case CLASS: return cls;
                    case FIELD:
                        Field field = declaredField(cls, name);
                        field.setAccessible(true);
                        return field;
                    case METHOD:
                    default:
                        Method method = declaredMethod(cls, name, resolveTypes(loader));
                        method.setAccessible(true);
                        return method;
                }
            } catch (Throwable t) { return null; }
        }

        private Class<?>[] resolveTypes(ClassLoader loader) throws ClassNotFoundException {
            Class<?>[] types = new Class<?>[params.length];
            for (int i = 0; i < params.length; i++) {
                Class<?> primitive = primitiveOf(params[i]);
                types[i] = primitive != null ? primitive : Class.forName(params[i], false, loader);
            }
            return types;
        }

        private static Class<?> primitiveOf(String name) {
            switch (name) {
                case "boolean": return boolean.class;
                case "byte": return byte.class;
                case "char": return char.class;
                case "short": return short.class;
                case "int": return int.class;
                case "long": return long.class;
                case "float": return float.class;
                case "double": return double.class;
                case "void": return void.class;
                default: return null;
            }
        }

        private static Method declaredMethod(Class<?> owner, String name, Class<?>[] signature)
                throws NoSuchMethodException {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try { return c.getDeclaredMethod(name, signature); }
                catch (NoSuchMethodException ignored) { }
            }
            throw new NoSuchMethodException(owner.getName() + "." + name);
        }

        private static Field declaredField(Class<?> owner, String name) throws NoSuchFieldException {
            for (Class<?> c = owner; c != null; c = c.getSuperclass()) {
                try { return c.getDeclaredField(name); }
                catch (NoSuchFieldException ignored) { }
            }
            throw new NoSuchFieldException(owner.getName() + "." + name);
        }
    }
}
