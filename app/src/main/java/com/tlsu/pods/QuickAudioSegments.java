// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.animation.LinearInterpolator;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import java.util.List;
import java.util.function.IntPredicate;

final class QuickAudioSegments {
    static final int TRACK_HEIGHT_DP = 56;
    private static final int TRACK_INSET_DP = 4;
    private static final int MAX_HIGHLIGHT_WIDTH_DP = 68;
    private static final int MIN_HIGHLIGHT_GAP_DP = 4;
    private static final PathInterpolator SNAP = new PathInterpolator(0.18f, 1.2f, 0.38f, 1f);
    private static final LinearInterpolator LINEAR = new LinearInterpolator();
    private static final ColorStateList LABEL_COLORS = new ColorStateList(
            new int[][]{{android.R.attr.state_selected}, {}}, new int[]{Color.WHITE, 0xffcfd6e2});

    record Mode(int value, String label, String icon) {}

    @SuppressLint("ViewConstructor") // Built with the capabilities of the connected device.
    static final class Strip extends FrameLayout {
        private final View track;
        private final QuickAudioTrack trackBackground;
        private final FrameLayout contents;
        private final View indicator;
        private final LinearLayout choices;
        private final int trackHeight;
        private final int touchSlop;
        private Object trackBuilder;
        private ValueAnimator settling;
        private int shownIndex = -1;
        private float firstCenter;
        private float lastCenter;
        private float downX;
        private float downY;
        private boolean dragging;

        Strip(Context context, List<Mode> options, int selected, int width, QuickAudioTrack trackBackground,
              IntPredicate onSelected) {
            super(context);
            this.trackBackground = trackBackground;
            trackHeight = dp(context, TRACK_HEIGHT_DP);
            touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            setPivotY(trackHeight / 2f);

            track = new View(context) {
                @Override protected int[] onCreateDrawableState(int extraSpace) {
                    if (trackBackground == null) return super.onCreateDrawableState(extraSpace);
                    int[] inactive = trackBackground.inactiveState;
                    return mergeDrawableStates(super.onCreateDrawableState(extraSpace + inactive.length), inactive);
                }
            };
            addView(track, new LayoutParams(LayoutParams.MATCH_PARENT, trackHeight));

            int count = options.size();
            int inset = Math.min(dp(context, TRACK_INSET_DP), width / 2);
            int gap = dp(context, MIN_HIGHLIGHT_GAP_DP);
            int available = Math.max(0, width - 2 * inset - (count - 1) * gap);
            int highlightWidth = Math.min(dp(context, MAX_HIGHLIGHT_WIDTH_DP), available / count);
            int verticalInset = dp(context, TRACK_INSET_DP);
            int highlightHeight = trackHeight - 2 * verticalInset;
            int diameter = Math.min(highlightWidth, highlightHeight);

            contents = new FrameLayout(context);
            addView(contents, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
            indicator = new View(context);
            indicator.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            indicator.setBackground(pill(context, diameter, 0xa67cd2ff, 0xff1f96fc, 0xff1b90f6));
            indicator.setVisibility(INVISIBLE);
            var highlightParams = new LayoutParams(highlightWidth, highlightHeight, Gravity.TOP | Gravity.START);
            highlightParams.topMargin = verticalInset;
            contents.addView(indicator, highlightParams);

            choices = new LinearLayout(context);
            choices.setOrientation(LinearLayout.HORIZONTAL);
            choices.setBaselineAligned(false);
            contents.addView(choices, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

            float firstCenter = count == 1 ? width / 2f : inset + highlightWidth / 2f;
            float spacing = count == 1 ? 0 : (width - 2 * firstCenter) / (count - 1);
            int start = 0;
            for (int index = 0; index < count; index++) {
                Mode mode = options.get(index);
                int center = Math.round(firstCenter + index * spacing);
                int end = index == count - 1 ? width : Math.round(firstCenter + (index + 0.5f) * spacing);
                int halfContentWidth = Math.min(center - start, end - center);
                ModeButton button = new ModeButton(context, mode, highlightWidth);
                // Keep the full touch area while centering the contents on the icon.
                button.setPaddingRelative(center - start - halfContentWidth, 0, end - center - halfContentWidth, 0);
                int option = index;
                button.setOnClickListener(v -> {
                    if (option != shownIndex && onSelected.test(mode.value())) showChoice(option);
                });
                choices.addView(button, new LinearLayout.LayoutParams(end - start, LayoutParams.MATCH_PARENT));
                start = end;
            }
            update(selected);
        }

        void updateTrack(View stockTile) throws ReflectiveOperationException {
            if (trackBackground == null) return;
            Object builder = trackBackground.currentBuilder(stockTile);
            if (builder == trackBuilder) return;
            track.setBackground(trackBackground.createBackground(builder, track, trackHeight / 2f));
            track.jumpDrawablesToCurrentState();
            trackBuilder = builder;
        }

        void update(int value) {
            for (int i = 0; i < choices.getChildCount(); i++) {
                if (buttonAt(i).value == value) {
                    showChoice(i);
                    return;
                }
            }
            showChoice(-1);
        }

        private void showChoice(int index) {
            if (shownIndex == index) return;
            if (shownIndex >= 0) buttonAt(shownIndex).setSelected(false);
            shownIndex = index;
            if (index >= 0) buttonAt(index).setSelected(true);
            if (!dragging && isLaidOut()) snapToChoice(true);
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            float first = centerOf(0);
            float last = centerOf(choices.getChildCount() - 1);
            if (changed || firstCenter != first || lastCenter != last) {
                firstCenter = first;
                lastCenter = last;
                if (!dragging) snapToChoice(false);
            }
        }

        private ModeButton buttonAt(int index) {
            return (ModeButton) choices.getChildAt(index);
        }

        private float centerOf(int index) {
            ModeButton button = buttonAt(index);
            return choices.getLeft() + button.getLeft() + button.iconWell.getLeft() + button.iconWell.getWidth() / 2f;
        }

        private int nearestChoice(float x) {
            int nearest = 0;
            float distance = Math.abs(centerOf(0) - x);
            for (int i = 1; i < choices.getChildCount(); i++) {
                float candidate = Math.abs(centerOf(i) - x);
                if (candidate < distance) {
                    nearest = i;
                    distance = candidate;
                }
            }
            return nearest;
        }

        private void positionIndicator(float center, float scaleX, float scaleY) {
            float left = Math.min(firstCenter, lastCenter);
            float right = Math.max(firstCenter, lastCenter);
            float clamped = Math.max(left, Math.min(right, center));
            indicator.setTranslationX(clamped - indicator.getLeft() - indicator.getWidth() / 2f);
            indicator.setScaleX(scaleX);
            indicator.setScaleY(scaleY);
        }

        private void cancelSnap() {
            if (settling == null) return;
            settling.cancel();
            settling = null;
        }

        private void snapToChoice(boolean animate) {
            cancelSnap();
            if (shownIndex < 0) {
                indicator.setVisibility(INVISIBLE);
                return;
            }
            float target = centerOf(shownIndex);
            boolean visible = indicator.getVisibility() == VISIBLE;
            indicator.setVisibility(VISIBLE);
            if (!animate || !visible || !ValueAnimator.areAnimatorsEnabled()) {
                positionIndicator(target, 1f, 1f);
                return;
            }

            float start = indicator.getX() + indicator.getWidth() / 2f;
            float startScaleX = indicator.getScaleX();
            float startScaleY = indicator.getScaleY();
            settling = ValueAnimator.ofFloat(0f, 1f);
            settling.setDuration(265);
            settling.setInterpolator(LINEAR);
            settling.addUpdateListener(animator -> {
                float progress = animator.getAnimatedFraction();
                float eased = SNAP.getInterpolation(progress);
                float flex = 0.06f * (float) Math.sin(Math.PI * progress);
                positionIndicator(start + (target - start) * eased,
                        startScaleX + (1f - startScaleX) * eased + flex,
                        startScaleY + (1f - startScaleY) * eased - flex / 2f);
            });
            settling.start();
        }

        private void dragTo(float x) {
            boolean animate = ValueAnimator.areAnimatorsEnabled();
            indicator.setVisibility(VISIBLE);
            positionIndicator(x, animate ? 0.95f : 1f, animate ? 1.035f : 1f);
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                dragging = false;
                downX = event.getX();
                downY = event.getY();
            } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE && event.getPointerCount() == 1
                    && choices.getChildCount() > 1 && downY < trackHeight) {
                float dx = Math.abs(event.getX() - downX);
                if (dx > touchSlop && dx > Math.abs(event.getY() - downY)) {
                    dragging = true;
                    cancelSnap();
                    dragTo(event.getX());
                    getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                }
            }
            return super.onInterceptTouchEvent(event);
        }

        @SuppressLint("ClickableViewAccessibility") // The mode button handles the click after a drag.
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (!dragging) return super.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_MOVE -> dragTo(event.getX());
                case MotionEvent.ACTION_UP -> {
                    if (event.getY() >= -touchSlop && event.getY() < trackHeight + touchSlop) {
                        buttonAt(nearestChoice(event.getX())).performClick();
                    }
                    dragging = false;
                    snapToChoice(true);
                }
                case MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN -> {
                    dragging = false;
                    snapToChoice(true);
                }
            }
            return true;
        }

        void fadeContents(boolean show) {
            contents.animate().cancel();
            if (show) contents.setAlpha(0f);
            contents.animate().alpha(show ? 1f : 0f).setStartDelay(show ? 55 : 0)
                    .setDuration(show ? 145 : 85).start();
        }

        void close() {
            animate().cancel();
            contents.animate().cancel();
            cancelSnap();
        }
    }

    private static final class ModeButton extends LinearLayout {
        private final int value;
        private final FrameLayout iconWell;

        @SuppressLint("DiscouragedApi") // Drawables are provided by the host SystemUI package.
        ModeButton(Context context, Mode mode, int highlightWidth) {
            super(context);
            value = mode.value();
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            setContentDescription(mode.label());
            setFocusable(true);

            iconWell = new FrameLayout(context);
            iconWell.setDuplicateParentStateEnabled(true);
            int verticalInset = dp(context, TRACK_INSET_DP);
            int pillHeight = dp(context, TRACK_HEIGHT_DP) - 2 * verticalInset;
            int pillDiameter = Math.min(highlightWidth, pillHeight);
            iconWell.setForeground(new RippleDrawable(ColorStateList.valueOf(0x36ffffff), null,
                    pill(context, pillDiameter, 0, Color.WHITE, Color.WHITE)));
            var wellParams = new LayoutParams(highlightWidth, pillHeight);
            wellParams.topMargin = verticalInset;
            wellParams.bottomMargin = verticalInset;
            addView(iconWell, wellParams);

            int iconId = getResources().getIdentifier(mode.icon(), "drawable", context.getPackageName());
            if (iconId != 0) {
                ImageView icon = new ImageView(context);
                icon.setImageResource(iconId);
                icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
                icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
                int size = Math.min(dp(context, 27), highlightWidth);
                iconWell.addView(icon, new FrameLayout.LayoutParams(size, size, Gravity.CENTER));
            }

            TextView label = new TextView(context);
            label.setText(mode.label());
            label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            label.setDuplicateParentStateEnabled(true);
            label.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
            label.setIncludeFontPadding(false);
            label.setMaxLines(2);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setTextSize(13);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setTextColor(LABEL_COLORS);
            var labelParams = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            labelParams.topMargin = dp(context, 8);
            addView(label, labelParams);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName(RadioButton.class.getName());
            info.setCheckable(true);
            info.setChecked(isSelected());
        }
    }

    private static GradientDrawable pill(Context context, int diameter, int stroke, int top, int bottom) {
        var drawable = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{top, bottom});
        drawable.setCornerRadius(diameter / 2f);
        if (stroke != 0) drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    static int dp(Context context, int size) {
        return Math.round(size * context.getResources().getDisplayMetrics().density);
    }
}
