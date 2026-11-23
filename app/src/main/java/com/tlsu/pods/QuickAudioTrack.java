// SPDX-License-Identifier: GPL-3.0-or-later
package com.tlsu.pods;

import android.graphics.drawable.Drawable;
import android.view.View;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Creates the same background as the inactive SystemUI volume detail tiles. */
final class QuickAudioTrack {
    private static final String RES = "com.oplus.systemui.qs.base.res.";
    private static final String OUTLINE = "com.oplusos.systemui.common.outline.";

    final int[] inactiveState;
    private final Class<?> splitTile;
    private final Field standardFlow;
    private final Field splitPool;
    private final Method splitFlow;
    private final Method currentValue;
    private final Method build;
    private final Method setPathProvider;
    private final Constructor<?> outline;

    QuickAudioTrack(ClassLoader loader) throws ReflectiveOperationException {
        splitTile = loader.loadClass("com.oplus.systemui.qs.slider.detail.widget.SepQsDetailTileView");
        standardFlow = loader.loadClass(RES + "StdQSResPool").getField("detailTileViewDrawable");
        Class<?> split = loader.loadClass(RES + "SepQSResPool");
        splitPool = split.getField("INSTANCE");
        splitFlow = split.getMethod("getDetailTileViewDrawable");
        currentValue = loader.loadClass("kotlinx.coroutines.flow.StateFlow").getMethod("getValue");
        build = loader.loadClass(RES + "model.QSDrawableBuilder")
                .getMethod("build", View.class, boolean.class, Integer.class);
        setPathProvider = loader.loadClass(RES + "drawable.TileDrawableWrapper")
                .getMethod("setPathProvider", loader.loadClass(OUTLINE + "CornerOutlineProvider"));
        outline = loader.loadClass(OUTLINE + "RoundRectOutlineProvider").getConstructor(float.class, Float.class);
        Class<?> flags = loader.loadClass(RES + "model.TileViewFlag");
        inactiveState = (int[]) flags.getMethod("getFlag").invoke(flags.getField("Inactive").get(null));
    }

    Object currentBuilder(View stockTile) throws ReflectiveOperationException {
        Object flow = splitTile.isInstance(stockTile) ? splitFlow.invoke(splitPool.get(null)) : standardFlow.get(null);
        return currentValue.invoke(flow);
    }

    Drawable createBackground(Object builder, View track, float radius) throws ReflectiveOperationException {
        // The blur proxy belongs to its target view, so build a fresh background for this rail.
        Drawable background = (Drawable) build.invoke(builder, track, true, null);
        if (background != null && background != track.getBackground()) {
            setPathProvider.invoke(background, outline.newInstance(radius, null));
        }
        return background;
    }
}
