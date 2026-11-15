package com.edm.downloader.ui;

import android.app.Activity;
import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/** Insets helpers. Android 15 (targetSdk 35) draws every window edge-to-edge. */
public final class SystemBars {

    private SystemBars() {
    }

    /** White status bar icons on the colored toolbar; nav bar icons follow the app theme. */
    public static void apply(Activity activity) {
        Window window = activity.getWindow();
        boolean night = (activity.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(window, window.getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(!night);
    }

    /** Grows the toolbar by the status bar inset so its background reaches the screen top. */
    public static void padToolbar(View toolbar) {
        final int baseHeight = toolbar.getLayoutParams().height;
        final int baseTop = toolbar.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(toolbar, (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (baseHeight >= 0) {
                int height = baseHeight + bars.top;
                if (lp.height != height) {
                    lp.height = height;
                    view.setLayoutParams(lp);
                }
            }
            int top = baseTop + bars.top;
            if (view.getPaddingTop() != top) {
                view.setPadding(view.getPaddingLeft(), top, view.getPaddingRight(),
                        view.getPaddingBottom());
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(toolbar);
    }

    /** Adds the navigation bar inset below a view that sits at the bottom of the screen. */
    public static void padBottom(View view) {
        final int baseBottom = view.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            int bottom = baseBottom + bars.bottom;
            if (v.getPaddingBottom() != bottom) {
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), bottom);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}
