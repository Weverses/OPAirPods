// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.AbsSeekBar;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.WeakHashMap;
import java.util.function.BiPredicate;

/** Three hardware EQ controls and a visual interpolation of their settings. */
@SuppressLint("ViewConstructor") // Constructed by the host preference binder, not inflated from XML.
final class EqualizerPanel extends LinearLayout {
    private static final String TAG = "colorpods.equalizer.panel";
    private static final String VERTICAL_SLIDER = "com.coui.appcompat.seekbar.COUIVerticalSeekBar";
    private static final String[] FIELDS = {"low", "mid", "high"};
    private static final String[] LABELS = {"低音", "中音", "高音"};
    private static final WeakHashMap<Object, WeakReference<EqualizerPanel>> ROWS = new WeakHashMap<>();

    private final BandSlider[] sliders = new BandSlider[FIELDS.length];
    private final TextView[] values = new TextView[FIELDS.length];
    private final ResponseCurve curve;
    private final BiPredicate<String, Integer> write;
    private final int accentColor, dividerColor;
    private CustomEq selection = CustomEq.flat();
    private BandSlider activeSlider;
    private boolean editable;

    EqualizerPanel(Context context, Object row, BiPredicate<String, Integer> write) throws Exception {
        super(context);
        this.write = write;
        accentColor = themeColor("couiColorPrimary", android.R.attr.colorAccent);
        dividerColor = themeColor("couiColorDivider", android.R.attr.colorControlNormal);
        setOrientation(VERTICAL);
        setLayoutDirection(LAYOUT_DIRECTION_LTR);
        setForceDarkAllowed(false);
        setPadding(0, dp(12), 0, dp(12));
        setTag(TAG);

        Context settings = SystemSettingsSlider.themedContext(context);
        Class<? extends AbsSeekBar> sliderType = Class.forName(VERTICAL_SLIDER, true, settings.getClassLoader())
                .asSubclass(AbsSeekBar.class);
        LinearLayout bands = new LinearLayout(context);
        addView(bands, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        for (int i = 0; i < FIELDS.length; i++) {
            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            column.setGravity(Gravity.CENTER_HORIZONTAL);
            bands.addView(column, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));

            values[i] = label(16, true);
            values[i].setFontFeatureSettings("tnum");
            column.addView(values[i]);

            sliders[i] = new BandSlider(context, i, sliderType.getConstructor(Context.class).newInstance(settings));
            column.addView(sliders[i], new LayoutParams(LayoutParams.MATCH_PARENT, dp(132)));

            TextView name = label(14, false);
            name.setText(LABELS[i]);
            column.addView(name);
        }

        TextView caption = label(14, false);
        caption.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        caption.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        caption.setText("频响示意");
        LayoutParams captionParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        captionParams.topMargin = dp(16);
        addView(caption, captionParams);

        curve = new ResponseCurve(context);
        addView(curve, new LayoutParams(LayoutParams.MATCH_PARENT, dp(100)));
        update(selection, false, false);
        ROWS.put(row, new WeakReference<>(this));
    }

    private int themeColor(String name, int fallback) {
        int attr = getResources().getIdentifier(name, "attr", getContext().getPackageName());
        var colors = getContext().obtainStyledAttributes(new int[]{fallback, attr == 0 ? fallback : attr});
        try {
            return colors.getColor(1, colors.getColor(0, 0));
        } finally {
            colors.recycle();
        }
    }

    private TextView label(int sp, boolean primary) {
        TextView view = new TextView(getContext());
        String style = primary ? "COUIPreferenceTitleStyle" : "COUIPreferenceSummaryStyle";
        int appearance = getResources().getIdentifier(style, "style", getContext().getPackageName());
        if (appearance != 0) view.setTextAppearance(appearance);
        view.setTextSize(sp);
        view.setGravity(Gravity.CENTER);
        view.setTextAlignment(TEXT_ALIGNMENT_GRAVITY);
        view.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    static void bind(Object row, View root) {
        WeakReference<EqualizerPanel> ref = ROWS.get(row);
        EqualizerPanel panel = ref == null ? null : ref.get();
        View previous = root.findViewWithTag(TAG);
        if (previous == panel) return;
        if (previous != null) ((ViewGroup) previous.getParent()).removeView(previous);
        if (panel == null) return;
        View title = root.findViewById(android.R.id.title);
        if (title == null || !(title.getParent() instanceof RelativeLayout content)) {
            throw new IllegalStateException("Unsupported COUI equalizer preference content");
        }
        if (panel.getParent() instanceof ViewGroup old) old.removeView(panel);
        RelativeLayout.LayoutParams params = new RelativeLayout.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        params.addRule(RelativeLayout.BELOW, android.R.id.summary);
        content.addView(panel, params);
    }

    void update(CustomEq eq, boolean available, boolean busy) {
        selection = eq;
        boolean enabled = available && eq.enabled();
        editable = enabled && !busy;
        if (!editable) cancelDrag();
        for (BandSlider slider : sliders) slider.view.setEnabled(editable);
        setAlpha(enabled ? 1f : 0.55f);
        syncValues();
    }

    private void syncValues() {
        sync(sliders[0], selection.low());
        sync(sliders[1], selection.mid());
        sync(sliders[2], selection.high());
        showCurve();
    }

    private void sync(BandSlider slider, int value) {
        if (slider == activeSlider) return;
        if (slider.progress() != value) slider.view.setProgress(value);
        showValue(slider);
    }

    private void showValue(BandSlider slider) {
        String value = slider.progress() + "%";
        TextView label = values[slider.band];
        if (!value.contentEquals(label.getText())) label.setText(value);
    }

    private void showCurve() {
        curve.target(sliders[0].progress(), sliders[1].progress(), sliders[2].progress());
    }

    private void commit(BandSlider slider) {
        String field = FIELDS[slider.band];
        int value = slider.progress();
        if (editable && !selection.patch(field, value).equals(selection) && !write.test(field, value)) {
            syncValues();
        }
    }

    private void cancelDrag() {
        if (activeSlider == null) return;
        BandSlider slider = activeSlider;
        activeSlider = null;
        slider.cancelTouch();
    }

    @Override protected void onDetachedFromWindow() {
        if (activeSlider != null) {
            cancelDrag();
            syncValues();
        }
        curve.snap();
        super.onDetachedFromWindow();
    }

    private final class BandSlider extends FrameLayout {
        final int band;
        final AbsSeekBar view;
        private final Method userProgress;
        private boolean tracking, released;

        BandSlider(Context context, int band, AbsSeekBar view) throws ReflectiveOperationException {
            super(context);
            this.band = band;
            this.view = view;
            Class<?> type = view.getClass();
            userProgress = type.getMethod("setProgress", int.class, boolean.class, boolean.class);
            // The native press animation and haptics remain active. EQ values stop at release.
            type.getMethod("setPhysicalEnabled", boolean.class).invoke(view, false);
            type.getMethod("setSupportDeformation", boolean.class).invoke(view, false);
            view.setMin(0);
            view.setMax(100);
            view.setContentDescription(LABELS[band]);
            view.setFocusable(true);
            bindNativeListener(type);
            bindRangeActions();
            addView(view, new FrameLayout.LayoutParams(dp(56), LayoutParams.MATCH_PARENT, Gravity.CENTER));
        }

        int progress() { return view.getProgress(); }

        private void bindNativeListener(Class<?> type) throws ReflectiveOperationException {
            Class<?> listener = Class.forName(VERTICAL_SLIDER + "$OnSeekBarChangeListener", true, type.getClassLoader());
            Object callback = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{listener}, (proxy, method, args) -> {
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "ColorPodsEqListener";
                    case "onStartTrackingTouch" -> {
                        tracking = true;
                        yield null;
                    }
                    case "onProgressChanged" -> {
                        if (Boolean.TRUE.equals(args[2]) && editable
                                && (activeSlider == this || (activeSlider == null && !tracking))) {
                            showValue(this);
                            showCurve();
                            if (activeSlider == null) commit(this);
                        }
                        yield null;
                    }
                    case "onStopTrackingTouch" -> {
                        tracking = false;
                        if (activeSlider == this && released) finishRelease();
                        if (activeSlider != this) syncValues();
                        yield null;
                    }
                    default -> null;
                };
            });
            type.getMethod("setOnSeekBarChangeListener", listener).invoke(view, callback);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                if (!editable || activeSlider != null || tracking) return false;
                activeSlider = this;
                released = false;
                if (!super.dispatchTouchEvent(event)) {
                    activeSlider = null;
                    return false;
                }
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            } else if (activeSlider != this) {
                return false;
            }
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
                cancelDrag();
                syncValues();
                return true;
            }
            boolean handled = super.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_UP) {
                released = true;
                // A native tap animates to its target and reports stop after the animation.
                if (!tracking) finishRelease();
            }
            return handled;
        }

        private void finishRelease() {
            activeSlider = null;
            released = false;
            showValue(this);
            showCurve();
            commit(this);
        }

        void cancelTouch() {
            if (released) {
                released = false;
                return;
            }
            long now = SystemClock.uptimeMillis();
            // The native widget also runs its tap path on cancel unless the point is outside the track.
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, -1, -1, 0);
            try {
                super.dispatchTouchEvent(cancel);
            } finally {
                cancel.recycle();
            }
        }

        private void bindRangeActions() {
            view.setOnKeyListener((v, key, event) -> event.getAction() == KeyEvent.ACTION_DOWN && switch (key) {
                case KeyEvent.KEYCODE_DPAD_UP -> setUserProgress(progress() + 5);
                case KeyEvent.KEYCODE_DPAD_DOWN -> setUserProgress(progress() - 5);
                default -> false;
            });
            View.AccessibilityDelegate nativeDelegate = view.getAccessibilityDelegate();
            view.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                    if (nativeDelegate != null) nativeDelegate.onInitializeAccessibilityNodeInfo(host, info);
                    else super.onInitializeAccessibilityNodeInfo(host, info);
                }

                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.getId()
                            && args != null && args.containsKey(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)) {
                        return setUserProgress(Math.round(args.getFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE)));
                    }
                    return nativeDelegate == null ? super.performAccessibilityAction(host, action, args)
                            : nativeDelegate.performAccessibilityAction(host, action, args);
                }
            });
        }

        private boolean setUserProgress(int value) {
            if (!editable || activeSlider != null || tracking) return false;
            if (value == progress()) return true;
            try {
                userProgress.invoke(view, value, false, true);
                return true;
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to adjust the ColorOS EQ slider", e);
            }
        }
    }

    private final class ResponseCurve extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line = new Path(), fill = new Path();
        private final float[] position = {50, 50, 50};
        private final int[] goal = {50, 50, 50};
        private long lastFrame;
        private LinearGradient wash;

        ResponseCurve(Context context) {
            super(context);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        }

        void target(int low, int mid, int high) {
            if (goal[0] == low && goal[1] == mid && goal[2] == high) return;
            goal[0] = low;
            goal[1] = mid;
            goal[2] = high;
            if (!isShown() || !ValueAnimator.areAnimatorsEnabled()) {
                snap();
            } else {
                if (lastFrame == 0) lastFrame = System.nanoTime();
                postInvalidateOnAnimation();
            }
        }

        void snap() {
            for (int i = 0; i < position.length; i++) position[i] = goal[i];
            lastFrame = 0;
            invalidate();
        }

        @Override protected void onSizeChanged(int w, int h, int oldWidth, int oldHeight) {
            if (w == 0 || h == 0) return;
            int tint = accentColor & 0x00ffffff;
            wash = new LinearGradient(0, dp(8), 0, h - dp(8), tint | 0x29000000, tint,
                    Shader.TileMode.CLAMP);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (lastFrame != 0) advance();
            float width = getWidth(), left = dp(2), right = width - left;
            float top = dp(10), bottom = getHeight() - dp(8);
            float lowX = width / 6f, midX = width / 2f, highX = width * 5f / 6f;
            float lowY = y(position[0], top, bottom), midY = y(position[1], top, bottom);
            float highY = y(position[2], top, bottom);

            paint.setColor(dividerColor);
            paint.setStrokeWidth(dp(1));
            canvas.drawLine(lowX, top, lowX, bottom, paint);
            canvas.drawLine(midX, top, midX, bottom, paint);
            canvas.drawLine(highX, top, highX, bottom, paint);
            float center = (top + bottom) / 2f;
            for (float x = left; x <= right; x += dp(5)) canvas.drawCircle(x, center, dp(1), paint);

            float transition = width / 8f;
            line.rewind();
            line.moveTo(left, lowY);
            line.lineTo(lowX, lowY);
            line.cubicTo(lowX + transition, lowY, midX - transition, midY, midX, midY);
            line.cubicTo(midX + transition, midY, highX - transition, highY, highX, highY);
            line.lineTo(right, highY);
            fill.set(line);
            fill.lineTo(right, bottom);
            fill.lineTo(left, bottom);
            fill.close();
            paint.setAlpha(255);
            paint.setShader(wash);
            canvas.drawPath(fill, paint);
            paint.setShader(null);
            paint.setColor(accentColor);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeWidth(dp(2.5f));
            canvas.drawPath(line, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(lowX, lowY, dp(4), paint);
            canvas.drawCircle(midX, midY, dp(4), paint);
            canvas.drawCircle(highX, highY, dp(4), paint);
        }

        private float y(float progress, float top, float bottom) {
            return bottom - (bottom - top) * progress / 100f;
        }

        private void advance() {
            long now = System.nanoTime();
            // Time-based easing follows a moving target without restarting an animator on each tick.
            float step = 1f - (float) Math.exp((lastFrame - now) / 34_000_000d);
            boolean moving = false;
            for (int i = 0; i < position.length; i++) {
                float distance = goal[i] - position[i];
                if (Math.abs(distance) < 0.2f) {
                    position[i] = goal[i];
                } else {
                    position[i] += distance * step;
                    moving = true;
                }
            }
            lastFrame = moving ? now : 0;
            if (moving) postInvalidateOnAnimation();
        }
    }
}
