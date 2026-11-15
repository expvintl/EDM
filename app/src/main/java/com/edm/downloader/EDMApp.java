package com.edm.downloader;

import android.app.Application;
import android.os.Build;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.edm.downloader.data.Settings;
import com.edm.downloader.engine.DownloadEngine;

/** Application entry point: boots the engine, applies the saved language and theme. */
public class EDMApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        DownloadEngine engine = DownloadEngine.get(this);
        applySavedLanguage(engine.settings());
        applyTheme(engine.settings().themeMode());
        engine.autoResume();
    }

    /**
     * Applies the language chosen in Settings. An empty tag means "follow the system
     * language", which is also the default for a fresh install.
     */
    public static void applyLanguage(String tag) {
        LocaleListCompat locales = tag == null || tag.isEmpty()
                ? LocaleListCompat.getEmptyLocaleList()
                : LocaleListCompat.forLanguageTags(tag);
        if (!locales.equals(AppCompatDelegate.getApplicationLocales())) {
            AppCompatDelegate.setApplicationLocales(locales);
        }
    }

    private static void applySavedLanguage(Settings settings) {
        if (Build.VERSION.SDK_INT >= 33) {
            // Android 13+ stores per-app locales in the framework, including choices made
            // from the system settings page, so it is the source of truth there.
            LocaleListCompat current = AppCompatDelegate.getApplicationLocales();
            settings.setLanguage(current.isEmpty() ? "" : current.toLanguageTags());
            return;
        }
        applyLanguage(settings.language());
    }

    public static void applyTheme(int mode) {
        int night = mode == Settings.THEME_LIGHT ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == Settings.THEME_DARK ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (AppCompatDelegate.getDefaultNightMode() != night) {
            AppCompatDelegate.setDefaultNightMode(night);
        }
    }
}
