// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.animation.ValueAnimator;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;
import android.view.animation.PathInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class QuickAudioPanel implements View.OnAttachStateChangeListener {
    private static final String SLIDER = "com.oplus.systemui.qs.slider.";
    private static final PathInterpolator ENTER = new PathInterpolator(0.16f, 1f, 0.3f, 1f);
    private static final PathInterpolator EXPAND = new PathInterpolator(0.18f, 1.06f, 0.34f, 1f);
    private static final PathInterpolator EXIT = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final int FIRST_ROW_TIMEOUT_MS = 480;
    private static final int MODE_ROW_OFFSET_DP = 12;
    private static final List<QuickAudioSegments.Mode> LISTENING_MODES = List.of(
            new QuickAudioSegments.Mode(3, "通透", "qs_detail_tile_noise_reduction_transparent"),
            new QuickAudioSegments.Mode(4, "自适应", "qs_detail_tile_noise_reduction_adaptive"),
            new QuickAudioSegments.Mode(2, "降噪", "qs_detail_tile_noise_reduction_open"),
            new QuickAudioSegments.Mode(1, "关闭", "qs_detail_tile_earphne"));
    private static final List<QuickAudioSegments.Mode> SPATIAL_MODES = List.of(
            new QuickAudioSegments.Mode(0, "关闭", "ic_spatial_audio_off"),
            new QuickAudioSegments.Mode(1, "固定", "ic_spatial_speaker"),
            new QuickAudioSegments.Mode(2, "头部追踪", "qs_detail_tile_spatial_audio_header"));
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> new Thread(r, "ColorPodsQuickAudio"));
    private static final Map<Object, QuickAudioPanel> PANELS = new IdentityHashMap<>();

    static void install(ColorPodsModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> root = loader.loadClass(SLIDER + "OplusQsDetailVolumeRootViewController");
        Class<?> tiles = loader.loadClass(SLIDER + "OplusQsDetailTilesContainerController");
        Class<?> volumeTiles = loader.loadClass(SLIDER + "OplusQsDetailVolumeTilesContainerController");
        Class<?> tileView = loader.loadClass(SLIDER + "detail.widget.QsDetailTileBaseView");
        Class<?> tileType = loader.loadClass(SLIDER + "detail.tile.QsDetailTile");
        Class<?> state = loader.loadClass("com.android.systemui.plugins.qs.QSTile$State");
        Class<?> app = loader.loadClass("com.android.systemui.application.impl.SystemUIApplicationImpl");
        Class<?> volumeDialog = loader.loadClass(SLIDER + "OplusQSVolumeDialogController");
        Class<?> backAction = loader.loadClass("com.oplus.systemui.viewdialog.ui.viewmodel."
                + "ViewDialogContentViewModel$backActionHandler$1");
        Object unit = loader.loadClass("kotlin.Unit").getField("INSTANCE").get(null);
        Method secondaryView = Reflect.method(volumeDialog, "getSecondaryView");
        Method getContainer = Reflect.method(root, "getTilesContainer");
        Method getRepository = Reflect.method(root, "getSliderDetailTileRepository");
        Method init = Reflect.method(root, "onInit");
        Method render = Reflect.method(root, "showDetailData", boolean.class);
        Method destroy = Reflect.method(root, "destroyTilesContainerController");
        Method detach = Reflect.method(root, "onViewDetached");
        Method setTiles = Reflect.method(tiles, "setTiles");
        Method setTilesChanged = Reflect.method(tiles, "setTilesChanged", int.class);
        QuickAudioSegments segments = new QuickAudioSegments(module, loader);
        QuickAudioNative nativeTiles = new QuickAudioNative(module, loader);
        QuickAudioSnapshots snapshots = new QuickAudioSnapshots(IO);
        module.hook(Reflect.method(app, "onCreate")).intercept(chain -> {
            Object result = chain.proceed();
            snapshots.start((Context) chain.getThisObject());
            return result;
        });
        module.deoptimize(Reflect.method(backAction, "invoke", Object.class, Object.class));
        module.hook(Reflect.method(backAction, "invokeSuspend", Object.class)).intercept(chain -> {
            try {
                Object viewModel = Reflect.field(chain.getThisObject(), "this$0");
                Object adapter = Reflect.field(viewModel, "adapter");
                if (volumeDialog.isInstance(adapter)) {
                    Object view = secondaryView.invoke(adapter);
                    for (QuickAudioPanel panel : PANELS.values()) {
                        if (panel.dialogView == view && panel.returnFromModes()) return unit;
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                module.error("Quick Settings AirPods nested back action unavailable", e);
            }
            return chain.proceed();
        });
        module.hook(init).intercept(chain -> {
            Object owner = chain.getThisObject();
            if (!root.isInstance(owner)) return chain.proceed();
            try {
                View view = (View) Reflect.field(owner, "mView");
                snapshots.start(view.getContext());
                Bundle cached = snapshots.current();
                if (cached != null) {
                    Object volume = Reflect.field(owner, "volumeDetailTileController");
                    nativeTiles.prepare(owner, Reflect.field(volume, "earphoneController"), cached);
                }
            } catch (ReflectiveOperationException | RuntimeException e) {
                module.error("Quick Settings AirPods preload unavailable", e);
            }
            try {
                return chain.proceed();
            } finally {
                if (!PANELS.containsKey(owner)) nativeTiles.clear(owner);
            }
        });
        module.hook(destroy).intercept(chain -> {
            remove(chain.getThisObject());
            return chain.proceed();
        });
        module.hook(detach).intercept(chain -> {
            remove(chain.getThisObject());
            return chain.proceed();
        });
        for (Method changed : List.of(setTiles, setTilesChanged)) {
            module.hook(changed).intercept(chain -> {
                Object owner = chain.getThisObject();
                QuickAudioPanel panel = null;
                if (volumeTiles.isInstance(owner)) {
                    for (QuickAudioPanel candidate : PANELS.values()) {
                        if (candidate.tilesController == owner) {
                            panel = candidate;
                            break;
                        }
                    }
                }
                if (panel != null && panel.waitingForRow && changed.equals(setTilesChanged)
                        && (int) chain.getArg(0) > 0) {
                    // The row is still hidden. Bind all initial tiles together using the host's non-animated path.
                    try {
                        setTiles.invoke(owner);
                        return null;
                    } catch (ReflectiveOperationException e) {
                        module.error("Quick Settings AirPods initial tile layout unavailable", e);
                    }
                }
                Object original = chain.proceed();
                if (panel != null) panel.main.post(panel::bindTiles);
                return original;
            });
        }
        module.hook(render).intercept(chain -> {
            Object original = chain.proceed();
            Object owner = chain.getThisObject();
            try {
                remove(owner);
                FrameLayout container = (FrameLayout) getContainer.invoke(owner);
                Object tilesController = Reflect.field(owner, "tilesContainerController");
                Object volumeController = Reflect.field(owner, "volumeDetailTileController");
                if (container != null && tilesController != null && tileView.isInstance(container.getChildAt(0))) {
                    var panel = new QuickAudioPanel(module, owner, (View) Reflect.field(owner, "mView"),
                            container, nativeTiles, snapshots,
                            Reflect.field(volumeController, "earphoneController"), getRepository.invoke(owner),
                            tilesController, state, tileType, segments);
                    PANELS.put(owner, panel);
                    panel.open();
                }
            } catch (Exception | LinkageError e) {
                remove(owner);
                module.error("Quick Settings volume panel setup failed", e);
            }
            return original;
        });
        module.info("Quick Settings native AirPods tile hooks installed");
    }

    private static void remove(Object owner) {
        QuickAudioPanel panel = PANELS.remove(owner);
        if (panel != null) panel.close();
    }

    private final ColorPodsModule module;
    private final Object owner;
    private final View dialogView;
    private final FrameLayout container;
    private final Context context;
    private final BridgeClient bridge;
    private final QuickAudioNative nativeTiles;
    private final QuickAudioSnapshots snapshots;
    private final Object earphoneController;
    private final Object repository;
    private final Object tilesController;
    private final Class<?> stateType;
    private final Class<?> tileType;
    private final QuickAudioSegments segments;
    private final int stockCount;
    private final Map<View, Object> modeClicks = new IdentityHashMap<>();
    private final List<View> hiddenTiles = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable refresh = () -> request(new Bundle(), false);
    private final Runnable revealTimeout = this::revealRow;
    private final Runnable checkRow = this::revealIfReady;
    private final Runnable finishEnter = this::finishOpenModes;
    private final View.OnLayoutChangeListener layout = (v, l, t, r, b, ol, ot, or, ob) -> placeConversation();
    private volatile boolean active;
    private Bundle status = new Bundle();
    private boolean busy;
    private boolean reportedError;
    private boolean waitingForRow;
    private boolean adapted;
    private int expectedTiles;
    private int requestId;
    private View conversation;
    private Object conversationIcon;
    private int conversationMode = -1;
    private QuickAudioSegments.Strip modeStrip;
    private ModeOrigin modeOrigin;
    private String expandedControl;
    private boolean enteringModes;
    private boolean exitingModes;

    private record ModeOrigin(float x, float y, float scaleX, float scaleY) {
        static ModeOrigin fromTile(View tile, View container, int modeWidth, int modeHeight, int rowOffset) {
            int[] tilePosition = new int[2];
            int[] rowPosition = new int[2];
            tile.getLocationInWindow(tilePosition);
            container.getLocationInWindow(rowPosition);
            float centerX = tilePosition[0] - rowPosition[0] + tile.getWidth() / 2f;
            float centerY = tilePosition[1] - rowPosition[1] + tile.getHeight() / 2f;
            return new ModeOrigin(centerX - container.getWidth() / 2f, centerY - rowOffset - modeHeight / 2f,
                    Math.min(1f, tile.getWidth() / (float) modeWidth),
                    Math.min(1f, tile.getHeight() / (float) modeHeight));
        }
    }

    private QuickAudioPanel(ColorPodsModule module, Object owner, View dialogView, FrameLayout container,
                            QuickAudioNative nativeTiles, QuickAudioSnapshots snapshots,
                            Object earphoneController, Object repository, Object tilesController, Class<?> stateType,
                            Class<?> tileType, QuickAudioSegments segments) {
        this.module = module;
        this.owner = owner;
        this.dialogView = dialogView;
        this.container = container;
        this.context = container.getContext();
        this.bridge = new BridgeClient(context);
        this.nativeTiles = nativeTiles;
        this.snapshots = snapshots;
        this.earphoneController = earphoneController;
        this.repository = repository;
        this.tilesController = tilesController;
        this.stateType = stateType;
        this.tileType = tileType;
        this.segments = segments;
        this.stockCount = container.getChildCount();
    }

    private void open() {
        container.addOnAttachStateChangeListener(this);
        container.addOnLayoutChangeListener(layout);
        start();
    }

    private void close() {
        onViewDetachedFromWindow(container);
        container.removeOnAttachStateChangeListener(this);
        container.removeOnLayoutChangeListener(layout);
        hideConversation();
    }

    @Override public void onViewAttachedToWindow(View view) {
        if (!active) start();
    }

    private void start() {
        active = true;
        waitingForRow = true;
        expectedTiles = 0;
        container.setVisibility(View.INVISIBLE);
        main.postDelayed(revealTimeout, FIRST_ROW_TIMEOUT_MS);
        Bundle cached = snapshots.current();
        if (cached != null) {
            status = cached;
            renderStatus();
        }
        refresh.run();
    }

    @Override public void onViewDetachedFromWindow(View view) {
        active = false;
        requestId++;
        busy = false;
        adapted = false;
        modeClicks.clear();
        status = new Bundle();
        nativeTiles.clear(owner);
        main.removeCallbacksAndMessages(null);
        revealRow();
        closeModes();
    }

    private void request(Bundle args, boolean selection) {
        if (!active) return;
        int id = ++requestId;
        IO.execute(() -> {
            if (!active) return;
            try {
                Bundle result = bridge.request(BridgeClient.QUICK_AUDIO, args);
                main.post(() -> receive(id, result, selection, null));
            } catch (Exception e) {
                main.post(() -> receive(id, new Bundle(), selection, e));
            }
        });
    }

    private void receive(int id, Bundle result, boolean selection, Exception error) {
        if (!active || id != requestId) return;
        busy = false;
        if (error != null && !reportedError) {
            reportedError = true;
            module.error("Quick Settings AirPods bridge unavailable", error);
        }
        if (selection && error != null) {
            message(error.getMessage());
        } else {
            String previous = status.getString("address");
            if (previous != null && !previous.equals(result.getString("address"))) closeModes();
            status = result;
            if (error == null) snapshots.remember(result);
        }
        renderStatus();
        if (active) main.postDelayed(refresh, 1050);
    }

    private void renderStatus() {
        try {
            adapted = nativeTiles.update(owner, earphoneController, repository, status);
            if (waitingForRow) bindFirstRow();
            int mode = adapted ? status.getInt("conversation", -1) : -1;
            if (mode >= 0) showConversation(mode);
            else hideConversation();
            bindTiles();
            if (modeStrip != null) {
                if (!adapted) closeModes();
                else modeStrip.update(status.getInt(expandedControl, -1));
            }
            if (waitingForRow) {
                expectedTiles = 1;
                if (adapted && Integer.bitCount(status.getInt("listeningModes")) > 1) expectedTiles++;
                if (adapted && status.containsKey("spatial")) expectedTiles++;
                revealIfReady();
            }
        } catch (Exception | LinkageError e) {
            nativeTiles.clear(owner);
            closeModes();
            hideConversation();
            revealRow();
            active = false;
            module.error("Quick Settings AirPods tiles disabled for this panel", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void bindFirstRow() throws ReflectiveOperationException {
        List<Object> displayed = (List<Object>) Reflect.call(tilesController, "getDetailTiles", new Class<?>[0]);
        List<?> available = (List<?>) Reflect.call(tilesController, "getTiles", new Class<?>[0]);
        if (displayed.equals(available)) return;
        displayed.clear();
        displayed.addAll(available);
        for (int index = available.size(); index < stockCount; index++) {
            container.getChildAt(index).setVisibility(View.GONE);
        }
        Reflect.call(tilesController, "setTiles", new Class<?>[0]);
    }

    private void revealIfReady() {
        if (!waitingForRow || expectedTiles == 0) return;
        try {
            List<?> displayed = (List<?>) Reflect.call(tilesController, "getDetailTiles", new Class<?>[0]);
            int ready = 0;
            for (int index = 0; index < displayed.size(); index++) {
                View view = container.getChildAt(index);
                Object state = Reflect.call(view, "getState", new Class<?>[0]);
                Object spec = Reflect.call(displayed.get(index), "getTileSpec", new Class<?>[0]);
                if (view.getVisibility() == View.VISIBLE && state != null
                        && spec.equals(stateType.getField("spec").get(state))) ready++;
            }
            if (ready >= expectedTiles && (conversation == null
                    || Reflect.call(conversation, "getState", new Class<?>[0]) != null)) {
                placeConversation();
                revealRow();
                return;
            }
        } catch (ReflectiveOperationException e) {
            revealRow();
            return;
        }
        main.postDelayed(checkRow, 16);
    }

    private void revealRow() {
        if (!waitingForRow) return;
        waitingForRow = false;
        main.removeCallbacks(checkRow);
        main.removeCallbacks(revealTimeout);
        container.setVisibility(View.VISIBLE);
    }

    private void bindTiles() {
        if (!active) return;
        try {
            List<?> tiles = (List<?>) Reflect.call(tilesController, "getDetailTiles", new Class<?>[0]);
            for (int index = 0; index < tiles.size(); index++) {
                View view = container.getChildAt(index);
                Object tile = tiles.get(index);
                if (!adapted) {
                    if (modeClicks.remove(view) == tile) Reflect.call(view, "init", new Class<?>[]{tileType}, tile);
                    continue;
                }
                String spec = (String) Reflect.call(tile, "getTileSpec", new Class<?>[0]);
                boolean listening = spec.equals("noisereduction");
                if (!listening && !spec.equals("spatialaudio")) continue;
                View icon = (View) Reflect.call(view, "getBgContainer", new Class<?>[0]);
                View.OnClickListener click = anchor -> showModes(status.getString("address"), listening, icon);
                icon.setOnClickListener(click);
                for (String target : List.of("getLabelHostView", "getSecondLabelHostView")) {
                    ((View) Reflect.call(view, target, new Class<?>[0])).setOnClickListener(click);
                }
                modeClicks.put(view, tile);
            }
            placeConversation();
            if (modeStrip != null) hideTilesForModes();
        } catch (Exception | LinkageError e) {
            module.error("Quick Settings AirPods tile click binding unavailable", e);
        }
    }

    private void showModes(String address, boolean listening, View tile) {
        if (!active || !adapted || busy || modeStrip != null || address == null
                || !container.isAttachedToWindow()) return;
        try {
            var options = new ArrayList<QuickAudioSegments.Mode>();
            if (listening) {
                int modes = status.getInt("listeningModes");
                for (var option : LISTENING_MODES) {
                    if (QuickAudioState.hasMode(modes, option.value())) options.add(option);
                }
            } else {
                int count = status.getBoolean("trackingAvailable") ? SPATIAL_MODES.size() : 2;
                options.addAll(SPATIAL_MODES.subList(0, count));
            }
            if (options.isEmpty()) return;
            String action = listening ? "listening" : "spatial";
            int reserved = QuickAudioSegments.dp(context, 2 * QuickAudioSegments.BACK_WIDTH_DP);
            int width = Math.min(QuickAudioSegments.dp(context, 336), container.getWidth() - reserved);
            if (width <= 0) return;
            int offset = QuickAudioSegments.dp(context, MODE_ROW_OFFSET_DP);
            int height = QuickAudioSegments.dp(context, QuickAudioSegments.STRIP_HEIGHT_DP);
            modeOrigin = ModeOrigin.fromTile(tile, container, width, height, offset);
            modeStrip = segments.new Strip(context, options, status.getInt(action, -1), width,
                    this::animateCloseModes, value -> select(address, action, value));
            expandedControl = action;
            var layout = new FrameLayout.LayoutParams(width + reserved, height, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            layout.topMargin = offset;
            container.addView(modeStrip.view, layout);
            animateOpenModes();
        } catch (Exception | LinkageError e) {
            closeModes();
            module.error("Quick Settings ColorOS inline mode control unavailable", e);
        }
    }

    private void hideTilesForModes() {
        if (exitingModes) return;
        for (int index = 0; index < container.getChildCount(); index++) {
            View child = container.getChildAt(index);
            if (child != modeStrip.view && child.getVisibility() == View.VISIBLE) {
                if (!hiddenTiles.contains(child)) hiddenTiles.add(child);
                if (!enteringModes) child.setVisibility(View.INVISIBLE);
            }
        }
    }

    private void animateOpenModes() {
        if (!ValueAnimator.areAnimatorsEnabled()) {
            hideTilesForModes();
            return;
        }
        enteringModes = true;
        hideTilesForModes();
        for (View tile : hiddenTiles) {
            tile.animate().cancel();
            tile.animate().alpha(0f).scaleX(0.94f).scaleY(0.94f)
                    .setDuration(105).setInterpolator(EXIT).start();
        }
        main.postDelayed(finishEnter, 105);
        View strip = modeStrip.segments;
        strip.setAlpha(0.26f);
        strip.setScaleX(modeOrigin.scaleX());
        strip.setScaleY(modeOrigin.scaleY());
        strip.setTranslationX(modeOrigin.x());
        strip.setTranslationY(modeOrigin.y());
        strip.animate().alpha(1f).scaleX(1f).scaleY(1f).translationX(0f).translationY(0f)
                .setDuration(285).setInterpolator(EXPAND).start();
        modeStrip.fadeContents(true);
    }

    private void animateCloseModes() {
        if (modeStrip == null || exitingModes) return;
        if (!ValueAnimator.areAnimatorsEnabled()) {
            closeModes();
            return;
        }
        exitingModes = true;
        enteringModes = false;
        main.removeCallbacks(finishEnter);
        for (View tile : hiddenTiles) {
            if (tile.getParent() != container) continue;
            tile.animate().cancel();
            tile.setVisibility(View.VISIBLE);
            tile.animate().alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(220).setInterpolator(ENTER).start();
        }
        View strip = modeStrip.segments;
        strip.animate().cancel();
        modeStrip.fadeContents(false);
        strip.animate().alpha(0f).scaleX(modeOrigin.scaleX()).scaleY(modeOrigin.scaleY())
                .translationX(modeOrigin.x()).translationY(modeOrigin.y())
                .setDuration(225).setInterpolator(EXIT).withEndAction(this::closeModes).start();
    }

    private boolean returnFromModes() {
        if (modeStrip == null || !container.isShown()) return false;
        animateCloseModes();
        return true;
    }

    private void finishOpenModes() {
        enteringModes = false;
        if (modeStrip != null) hideTilesForModes();
    }

    private void closeModes() {
        QuickAudioSegments.Strip closing = modeStrip;
        if (closing == null) return;
        modeStrip = null;
        enteringModes = false;
        exitingModes = false;
        main.removeCallbacks(finishEnter);
        closing.segments.animate().cancel();
        container.removeView(closing.view);
        closing.close();
        expandedControl = null;
        modeOrigin = null;
        for (View tile : hiddenTiles) {
            if (tile.getParent() != container) continue;
            tile.animate().cancel();
            tile.setAlpha(1f);
            tile.setScaleX(1f);
            tile.setScaleY(1f);
            tile.setVisibility(View.VISIBLE);
        }
        hiddenTiles.clear();
    }

    private void select(String address, String action, int selection) {
        if (!active || busy || exitingModes || address == null || !address.equals(status.getString("address"))) return;
        busy = true;
        main.removeCallbacks(refresh);
        Bundle args = BridgeClient.forDevice(address);
        args.putString("action", action);
        args.putInt("selection", selection);
        request(args, true);
    }

    private void showConversation(int mode) throws ReflectiveOperationException {
        if (conversation == null) {
            View tile = (View) container.getChildAt(0).getClass().getConstructor(Context.class, AttributeSet.class)
                    .newInstance(context, null);
            int width = tile.getLayoutParams().width;
            int id = context.getResources().getIdentifier("ic_volume_voice_chat", "drawable", context.getPackageName());
            if (id == 0) return;
            Class<?> icon = stateType.getClassLoader().loadClass("com.android.systemui.qs.tileimpl.QSTileImpl$ResourceIcon");
            conversationIcon = Reflect.method(icon, "get", int.class).invoke(null, id);
            View.OnClickListener toggle = v -> {
                int current = status.getInt("conversation", -1);
                if (current >= 0) select(status.getString("address"), "conversation", 1 - current);
            };
            for (String getter : List.of("getBgContainer", "getLabelHostView", "getSecondLabelHostView")) {
                ((View) Reflect.call(tile, getter, new Class<?>[0])).setOnClickListener(toggle);
            }
            tile.setOnClickListener(toggle);
            conversation = tile;
            container.addView(tile, new FrameLayout.LayoutParams(width, FrameLayout.LayoutParams.WRAP_CONTENT));
        }
        if (conversationMode != mode) {
            Object state = stateType.getConstructor().newInstance();
            String label = mode == 1 ? "开启" : "关闭";
            stateType.getField("spec").set(state, "colorpods_conversation");
            stateType.getField("label").set(state, "对话感知");
            stateType.getField("secondaryLabel").set(state, label);
            stateType.getField("contentDescription").set(state, "对话感知，" + label);
            stateType.getField("icon").set(state, conversationIcon);
            stateType.getField("state").setInt(state, mode == 1 ? 2 : 1);
            stateType.getField("expandedAccessibilityClassName").set(state, Button.class.getName());
            Reflect.call(conversation, "onStateChanged", new Class<?>[]{stateType}, state);
            conversationMode = mode;
        }
        placeConversation();
    }

    private void placeConversation() {
        if (conversation == null || container.getWidth() == 0) return;
        try {
            int count = ((List<?>) Reflect.call(tilesController, "getDetailTiles", new Class<?>[0])).size();
            if (count < 1 || count > stockCount) return;
            Object helper = Reflect.call(container, "getLocationHelper", new Class<?>[0]);
            boolean vertical = Boolean.TRUE.equals(Reflect.field(helper, "isLandscapeLayout"));
            boolean rtl = container.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
            List<?> positions = (List<?>) Reflect.call(container, "locationLayout", new Class<?>[]{int.class}, count + 1);
            for (int index = 0; index < count; index++) {
                View view = container.getChildAt(index);
                int position = ((Number) positions.get(index)).intValue();
                view.setTranslationX(vertical ? 0 : rtl
                        ? container.getWidth() - position - view.getRight() : position - view.getLeft());
                view.setTranslationY(vertical ? position - view.getTop() : 0);
            }
            for (int index = count; index < stockCount; index++) resetTranslation(container.getChildAt(index));
            int position = ((Number) positions.get(count)).intValue();
            var params = (FrameLayout.LayoutParams) conversation.getLayoutParams();
            int start = vertical ? 0 : position;
            int top = vertical ? position : 0;
            if (params.getMarginStart() != start || params.topMargin != top) {
                params.setMarginStart(start);
                params.topMargin = top;
                conversation.setLayoutParams(params);
            }
        } catch (Exception | LinkageError e) {
            hideConversation();
            module.error("Quick Settings conversation tile position unavailable", e);
        }
    }

    private void hideConversation() {
        if (conversation == null) return;
        container.removeView(conversation);
        conversation = null;
        conversationMode = -1;
        for (int index = 0; index < stockCount; index++) resetTranslation(container.getChildAt(index));
    }

    private static void resetTranslation(View view) {
        if (view == null) return;
        view.setTranslationX(0);
        view.setTranslationY(0);
    }

    private void message(String value) {
        Toast.makeText(context, value == null ? "耳机设置未完成" : value, Toast.LENGTH_SHORT).show();
    }
}
