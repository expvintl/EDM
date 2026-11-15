package com.edm.downloader.data;

import android.content.Context;
import android.content.SharedPreferences;

/** Typed access to the single preferences file. */
public class Settings {

    public static final int THEME_SYSTEM = 0;
    public static final int THEME_LIGHT = 1;
    public static final int THEME_DARK = 2;

    public static final int SORT_TIME = 0;
    public static final int SORT_NAME = 1;
    public static final int SORT_SIZE = 2;

    public static final int PROXY_HTTP = 0;
    public static final int PROXY_HTTPS = 1;
    public static final int PROXY_SOCKS5 = 2;

    public static final int UA_MOBILE = 0;
    public static final int UA_DESKTOP = 1;
    public static final int UA_CUSTOM = 2;

    private static final String FILE = "edm_prefs";
    private static final String KEY_THREADS = "threads";
    private static final String KEY_CONCURRENT = "concurrent";
    private static final String KEY_WIFI_ONLY = "wifi_only";
    private static final String KEY_PREALLOCATE = "preallocate";
    private static final String KEY_AUTO_RESUME = "auto_resume";
    private static final String KEY_NOTIFICATIONS = "notifications";
    private static final String KEY_CLIPBOARD = "clipboard_watch";
    private static final String KEY_DIR_URI = "dir_uri";
    private static final String KEY_THEME = "theme";
    private static final String KEY_LANGUAGE = "language";
    private static final String KEY_SORT = "sort";
    private static final String KEY_LAST_CLIP = "last_clip";
    private static final String KEY_PROXY_DEFAULT = "proxy_default";
    private static final String KEY_PROXY_HOST = "proxy_host";
    private static final String KEY_PROXY_PORT = "proxy_port";
    private static final String KEY_PROXY_TYPE = "proxy_type";
    private static final String KEY_UA_MODE = "ua_mode";
    private static final String KEY_UA_CUSTOM = "ua_custom";
    private static final String KEY_RETRY_INTERVAL = "retry_interval";
    private static final String KEY_RETRY_COUNT = "retry_count";

    private final SharedPreferences prefs;

    public Settings(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int threads() {
        return clamp(prefs.getInt(KEY_THREADS, 8), 1, 32);
    }

    public void setThreads(int value) {
        prefs.edit().putInt(KEY_THREADS, clamp(value, 1, 32)).apply();
    }

    public int concurrent() {
        return clamp(prefs.getInt(KEY_CONCURRENT, 3), 1, 5);
    }

    public void setConcurrent(int value) {
        prefs.edit().putInt(KEY_CONCURRENT, clamp(value, 1, 5)).apply();
    }

    public boolean wifiOnly() {
        return prefs.getBoolean(KEY_WIFI_ONLY, false);
    }

    public void setWifiOnly(boolean value) {
        prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply();
    }

    public boolean autoResume() {
        return prefs.getBoolean(KEY_AUTO_RESUME, true);
    }

    public void setAutoResume(boolean value) {
        prefs.edit().putBoolean(KEY_AUTO_RESUME, value).apply();
    }

    /** Creates the destination at full size before downloading and then fills it. */
    public boolean preallocate() {
        return prefs.getBoolean(KEY_PREALLOCATE, false);
    }

    public void setPreallocate(boolean value) {
        prefs.edit().putBoolean(KEY_PREALLOCATE, value).apply();
    }

    public boolean notifications() {
        return prefs.getBoolean(KEY_NOTIFICATIONS, true);
    }

    public void setNotifications(boolean value) {
        prefs.edit().putBoolean(KEY_NOTIFICATIONS, value).apply();
    }

    public boolean clipboardWatch() {
        return prefs.getBoolean(KEY_CLIPBOARD, true);
    }

    public void setClipboardWatch(boolean value) {
        prefs.edit().putBoolean(KEY_CLIPBOARD, value).apply();
    }

    public String dirUri() {
        return prefs.getString(KEY_DIR_URI, null);
    }

    public void setDirUri(String value) {
        prefs.edit().putString(KEY_DIR_URI, value).apply();
    }

    public int themeMode() {
        return clamp(prefs.getInt(KEY_THEME, THEME_SYSTEM), 0, 2);
    }

    public void setThemeMode(int value) {
        prefs.edit().putInt(KEY_THEME, clamp(value, 0, 2)).apply();
    }

    /**
     * BCP 47 language tag chosen in Settings, or an empty string to follow the system language.
     */
    public String language() {
        String value = prefs.getString(KEY_LANGUAGE, "");
        return value != null ? value : "";
    }

    public void setLanguage(String value) {
        prefs.edit().putString(KEY_LANGUAGE, value == null ? "" : value).apply();
    }

    public int sortMode() {
        return clamp(prefs.getInt(KEY_SORT, SORT_TIME), 0, 2);
    }

    public void setSortMode(int value) {
        prefs.edit().putInt(KEY_SORT, clamp(value, 0, 2)).apply();
    }

    public String lastClip() {
        String value = prefs.getString(KEY_LAST_CLIP, "");
        return value != null ? value : "";
    }

    public void setLastClip(String value) {
        prefs.edit().putString(KEY_LAST_CLIP, value).apply();
    }

    public boolean proxyDefault() {
        return prefs.getBoolean(KEY_PROXY_DEFAULT, false);
    }

    public void setProxyDefault(boolean value) {
        prefs.edit().putBoolean(KEY_PROXY_DEFAULT, value).apply();
    }

    public String proxyHost() {
        String value = prefs.getString(KEY_PROXY_HOST, "");
        return value != null ? value.trim() : "";
    }

    public void setProxyHost(String value) {
        prefs.edit().putString(KEY_PROXY_HOST, value == null ? "" : value.trim()).apply();
    }

    public int proxyPort() {
        return clamp(prefs.getInt(KEY_PROXY_PORT, 0), 0, 65535);
    }

    public void setProxyPort(int value) {
        prefs.edit().putInt(KEY_PROXY_PORT, clamp(value, 0, 65535)).apply();
    }

    public int proxyType() {
        return clamp(prefs.getInt(KEY_PROXY_TYPE, PROXY_HTTP), 0, 2);
    }

    public void setProxyType(int value) {
        prefs.edit().putInt(KEY_PROXY_TYPE, clamp(value, 0, 2)).apply();
    }

    /** True when host and port form a usable proxy. */
    public boolean hasProxy() {
        return !proxyHost().isEmpty() && proxyPort() > 0;
    }

    public String proxyAddress() {
        return proxyHost() + ":" + proxyPort();
    }

    public int uaMode() {
        return clamp(prefs.getInt(KEY_UA_MODE, UA_MOBILE), 0, 2);
    }

    public void setUaMode(int value) {
        prefs.edit().putInt(KEY_UA_MODE, clamp(value, 0, 2)).apply();
    }

    public String uaCustom() {
        String value = prefs.getString(KEY_UA_CUSTOM, "");
        return value != null ? value.trim() : "";
    }

    public void setUaCustom(String value) {
        prefs.edit().putString(KEY_UA_CUSTOM, value == null ? "" : value.trim()).apply();
    }

    /** Seconds to wait before a failed download is retried automatically. */
    public int retryInterval() {
        return clamp(prefs.getInt(KEY_RETRY_INTERVAL, 5), 1, 600);
    }

    public void setRetryInterval(int value) {
        prefs.edit().putInt(KEY_RETRY_INTERVAL, clamp(value, 1, 600)).apply();
    }

    /** How many times a failed download is retried; 0 turns automatic retries off. */
    public int retryCount() {
        return clamp(prefs.getInt(KEY_RETRY_COUNT, 3), 0, 20);
    }

    public void setRetryCount(int value) {
        prefs.edit().putInt(KEY_RETRY_COUNT, clamp(value, 0, 20)).apply();
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}