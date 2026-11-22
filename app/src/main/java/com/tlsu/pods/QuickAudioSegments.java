// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntConsumer;

/** Inline choices rendered by the ColorOS segmented button component. */
final class QuickAudioSegments {
    static final int BACK_WIDTH_DP = 48;
    static final int STRIP_HEIGHT_DP = 60;
    private static final int SELECTED_TEXT = 0xff075bda;
    record Mode(int value, String label, String icon) {}

    private final Constructor<?> constructor;
    private final Method selectSegment;
    private final Method segmentHeight;
    private final Method segmentSize;
    private final Method selectedTextColor;
    private final Method unselectedTextColor;
    private final Method materialEffect;
    private final Field selectedPosition;
    private final Field trackBounds;
    private final Field indicatorBounds;
    private final Map<Object, Strip> strips = new IdentityHashMap<>();
    private final Map<RectF, Integer> cornerRadii = new IdentityHashMap<>();

    QuickAudioSegments(ColorPodsModule module, ClassLoader loader) throws ReflectiveOperationException {
        Class<?> type = loader.loadClass("com.coui.appcompat.segmentbutton.COUISegmentButtonLayout");
        constructor = type.getConstructor(Context.class);
        selectedPosition = type.getField("mSelectedPosition");
        trackBounds = type.getField("mTmpRectF");
        indicatorBounds = type.getField("mIndicatorRectF");
        selectSegment = Reflect.method(type, "selectSegmentAt", int.class);
        segmentHeight = Reflect.method(type, "setSegmentHeight", float.class);
        segmentSize = Reflect.method(type, "setSegmentTextSize", float.class);
        selectedTextColor = Reflect.method(type, "setSegmentSelectedTextColor", int.class);
        unselectedTextColor = Reflect.method(type, "setSegmentUnselectedTextColor", int.class);
        materialEffect = Reflect.method(type, "setMaterialEffectEnabled", boolean.class);
        Class<?> corners = loader.loadClass(type.getName() + "$SmoothRoundCornerHelper");
        for (Method draw : List.of(Reflect.method(type, "onDraw", Canvas.class),
                Reflect.method(type, "drawIndicator", Canvas.class, Boolean.class, Paint.class),
                Reflect.method(type, "updatePath", corners, RectF.class),
                Reflect.method(type, "onLayout", boolean.class, int.class, int.class, int.class, int.class),
                Reflect.method(type, "updateIndicatorRect"))) {
            module.deoptimize(draw);
        }
        module.hook(Reflect.method(type, "getDrawableRadius$1", RectF.class)).intercept(chain -> {
            RectF bounds = (RectF) chain.getArg(0);
            Integer radius = cornerRadii.get(bounds);
            if (radius == null) return chain.proceed();
            return Math.max(0f, Math.min(radius, Math.min(bounds.width(), bounds.height()) * 0.5f));
        });
        module.hook(Reflect.method(type, "dispatchTouchEvent", MotionEvent.class)).intercept(chain -> {
            Object original = chain.proceed();
            Strip strip = strips.get(chain.getThisObject());
            if (strip != null) {
                int action = ((MotionEvent) chain.getArg(0)).getActionMasked();
                if (action == MotionEvent.ACTION_UP) strip.choose(selectedPosition.getInt(strip.segments));
                if (action == MotionEvent.ACTION_CANCEL) strip.update(strip.selected);
            }
            return original;
        });
        module.hook(Reflect.method(type, "dispatchKeyEvent", KeyEvent.class)).intercept(chain -> {
            Object original = chain.proceed();
            Strip strip = strips.get(chain.getThisObject());
            KeyEvent event = (KeyEvent) chain.getArg(0);
            if (strip != null && event.getAction() == KeyEvent.ACTION_UP
                    && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER)) {
                strip.choose(selectedPosition.getInt(strip.segments));
            }
            return original;
        });
    }

    final class Strip {
        final FrameLayout view;
        final ViewGroup segments;
        private final TextView backButton;
        private final List<Mode> options;
        private final IntConsumer onSelected;
        private final RectF track;
        private final RectF indicator;
        private int selected;

        Strip(Context context, List<Mode> options, int selected, int width, Runnable back, IntConsumer onSelected)
                throws ReflectiveOperationException {
            this.options = options;
            this.selected = selected;
            this.onSelected = onSelected;
            int theme = context.getResources().getIdentifier("Theme.COUI.Main.Dark", "style", context.getPackageName());
            Context themed = theme == 0 ? context : new ContextThemeWrapper(context, theme);
            view = new FrameLayout(themed);
            view.setClipChildren(false);
            backButton = new TextView(themed);
            backButton.setText("‹");
            backButton.setTextSize(28);
            backButton.setGravity(Gravity.CENTER);
            backButton.setTextColor(Color.WHITE);
            backButton.setContentDescription("返回音频控制");
            backButton.setFocusable(true);
            backButton.setOnClickListener(v -> back.run());
            view.addView(backButton, new FrameLayout.LayoutParams(dp(context, BACK_WIDTH_DP), dp(context, 56),
                    Gravity.START | Gravity.CENTER_VERTICAL));

            segments = (ViewGroup) constructor.newInstance(themed);
            track = (RectF) trackBounds.get(segments);
            indicator = (RectF) indicatorBounds.get(segments);
            materialEffect.invoke(segments, false);
            segmentHeight.invoke(segments, (float) STRIP_HEIGHT_DP);
            segmentSize.invoke(segments, 12f);
            selectedPosition.setInt(segments, Math.max(0, indexOf(selected)));
            for (int i = 0; i < options.size(); i++) {
                int index = i;
                Mode mode = options.get(i);
                var label = new SegmentLabel(themed);
                label.setText(mode.label());
                int id = context.getResources().getIdentifier(mode.icon(), "drawable", context.getPackageName());
                if (id != 0) {
                    var drawable = context.getDrawable(id).mutate();
                    drawable.setTintList(new ColorStateList(
                            new int[][]{{android.R.attr.state_selected}, {}},
                            new int[]{SELECTED_TEXT, Color.WHITE}));
                    int size = dp(context, 21);
                    drawable.setBounds(0, 0, size, size);
                    label.setCompoundDrawablePadding(dp(context, 5));
                    label.setCompoundDrawables(null, drawable, null, null);
                }
                segments.addView(label);
                label.setOnClickListener(v -> choose(index));
            }
            selectedTextColor.invoke(segments, SELECTED_TEXT);
            unselectedTextColor.invoke(segments, Color.WHITE);
            view.addView(segments, new FrameLayout.LayoutParams(width, dp(context, STRIP_HEIGHT_DP), Gravity.CENTER));
            cornerRadii.put(track, dp(context, 17));
            cornerRadii.put(indicator, dp(context, 14));
            strips.put(segments, this);
        }

        private int indexOf(int value) {
            for (int i = 0; i < options.size(); i++) if (options.get(i).value() == value) return i;
            return -1;
        }

        void update(int value) throws ReflectiveOperationException {
            selected = value;
            int index = indexOf(value);
            if (index >= 0 && index != selectedPosition.getInt(segments)) selectSegment.invoke(segments, index);
        }

        private void choose(int index) {
            if (index < 0 || index >= options.size()) return;
            int value = options.get(index).value();
            if (value != selected) onSelected.accept(value);
        }

        void fadeContents(boolean show) {
            for (int index = 0; index < segments.getChildCount(); index++) {
                fade(segments.getChildAt(index), show, 55);
            }
            fade(backButton, show, 90);
        }

        private static void fade(View child, boolean show, int enterDelay) {
            child.animate().cancel();
            if (show) child.setAlpha(0f);
            child.animate().alpha(show ? 1f : 0f).setStartDelay(show ? enterDelay : 0)
                    .setDuration(show ? 145 : 85).start();
        }

        void close() {
            backButton.animate().cancel();
            for (int index = 0; index < segments.getChildCount(); index++) {
                segments.getChildAt(index).animate().cancel();
            }
            strips.remove(segments);
            cornerRadii.remove(track);
            cornerRadii.remove(indicator);
        }
    }

    private static final class SegmentLabel extends TextView {
        SegmentLabel(Context context) { super(context); }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            var icon = getCompoundDrawables()[1];
            if (icon == null) return;
            int groupHeight = icon.getBounds().height() + getCompoundDrawablePadding() + getLineHeight();
            int spare = Math.max(0, height - groupHeight);
            int top = Math.min(spare, spare / 2 + dp(getContext(), 1));
            setPaddingRelative(getPaddingStart(), top, getPaddingEnd(), spare - top);
        }
    }

    static int dp(Context context, int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }
}
