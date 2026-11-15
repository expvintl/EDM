package com.edm.downloader.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.text.InputType;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.edm.downloader.EDMApp;
import com.edm.downloader.R;
import com.edm.downloader.data.Settings;
import com.edm.downloader.engine.DownloadEngine;
import com.edm.downloader.util.FileUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.slider.Slider;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.function.Consumer;

/** Preferences screen, styled as Material Design 2 setting rows. */
public class SettingsFragment extends Fragment {

    private Settings settings;
    private TextView threadsValue;
    private TextView concurrentValue;
    private TextView retryIntervalValue;
    private TextView retryCountValue;
    private SwitchMaterial switchWifi;
    private SwitchMaterial switchAutoResume;
    private SwitchMaterial switchPreallocate;
    private SwitchMaterial switchNotifications;
    private SwitchMaterial switchClipboard;
    private SwitchMaterial switchProxyDefault;
    private TextView proxyTypeValue;
    private TextView proxyHostValue;
    private TextView proxyPortValue;
    private TextView uaValue;
    private TextView uaCustomValue;
    private View rowUaCustom;
    private TextView folderValue;
    private TextView themeValue;
    private TextView languageValue;
    private TextView versionValue;
    private TextView batteryValue;

    private ActivityResultLauncher<Uri> folderPicker;
    private ActivityResultLauncher<String> notificationPermission;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        folderPicker = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri == null || !isAdded()) {
                        return;
                    }
                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        settings.setDirUri(uri.toString());
                        refreshValues();
                        toast(R.string.folder_changed);
                    } catch (Exception e) {
                        toast(R.string.folder_pick_failed);
                    }
                });
        notificationPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> refreshValues());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        settings = DownloadEngine.get(requireContext()).settings();
        threadsValue = view.findViewById(R.id.threadsValue);
        concurrentValue = view.findViewById(R.id.concurrentValue);
        retryIntervalValue = view.findViewById(R.id.retryIntervalValue);
        retryCountValue = view.findViewById(R.id.retryCountValue);
        switchWifi = view.findViewById(R.id.switchWifi);
        switchAutoResume = view.findViewById(R.id.switchAutoResume);
        switchPreallocate = view.findViewById(R.id.switchPreallocate);
        switchNotifications = view.findViewById(R.id.switchNotifications);
        switchClipboard = view.findViewById(R.id.switchClipboard);
        switchProxyDefault = view.findViewById(R.id.switchProxyDefault);
        proxyTypeValue = view.findViewById(R.id.proxyTypeValue);
        proxyHostValue = view.findViewById(R.id.proxyHostValue);
        proxyPortValue = view.findViewById(R.id.proxyPortValue);
        uaValue = view.findViewById(R.id.uaValue);
        uaCustomValue = view.findViewById(R.id.uaCustomValue);
        rowUaCustom = view.findViewById(R.id.rowUaCustom);
        folderValue = view.findViewById(R.id.folderValue);
        themeValue = view.findViewById(R.id.themeValue);
        languageValue = view.findViewById(R.id.languageValue);
        versionValue = view.findViewById(R.id.versionValue);
        batteryValue = view.findViewById(R.id.batteryValue);

        view.findViewById(R.id.rowThreads).setOnClickListener(v -> pickThreads());
        view.findViewById(R.id.rowConcurrent).setOnClickListener(v -> pickConcurrent());
        view.findViewById(R.id.rowRetryInterval).setOnClickListener(v -> pickRetryInterval());
        view.findViewById(R.id.rowRetryCount).setOnClickListener(v -> pickRetryCount());
        switchWifi.setOnCheckedChangeListener((b, checked) -> settings.setWifiOnly(checked));
        switchAutoResume.setOnCheckedChangeListener((b, checked) -> settings.setAutoResume(checked));
        switchPreallocate.setOnCheckedChangeListener((b, checked) -> settings.setPreallocate(checked));
        switchClipboard.setOnCheckedChangeListener((b, checked) -> settings.setClipboardWatch(checked));
        switchProxyDefault.setOnCheckedChangeListener((b, checked) -> settings.setProxyDefault(checked));
        view.findViewById(R.id.rowProxyType).setOnClickListener(v -> pickProxyType());
        view.findViewById(R.id.rowProxyHost).setOnClickListener(v -> editProxyHost());
        view.findViewById(R.id.rowProxyPort).setOnClickListener(v -> editProxyPort());
        view.findViewById(R.id.rowUa).setOnClickListener(v -> pickUa());
        rowUaCustom.setOnClickListener(v -> editUaCustom());
        switchNotifications.setOnCheckedChangeListener((b, checked) -> {
            settings.setNotifications(checked);
            if (checked) {
                requestNotificationPermissionIfNeeded();
            }
        });
        bindRowToggle(switchWifi);
        bindRowToggle(switchAutoResume);
        bindRowToggle(switchPreallocate);
        bindRowToggle(switchNotifications);
        bindRowToggle(switchClipboard);
        bindRowToggle(switchProxyDefault);

        view.findViewById(R.id.rowFolder).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.dialog_folder_title)
                        .setMessage(R.string.folder_picker_hint)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.settings_folder_choose, (d, w) ->
                                folderPicker.launch(FileUtils.initialTreeUri()))
                        .show());
        view.findViewById(R.id.rowResetFolder).setOnClickListener(v -> {
            settings.setDirUri(null);
            refreshValues();
            toast(R.string.folder_changed);
        });
        view.findViewById(R.id.rowBattery).setOnClickListener(v -> openBatterySettings());
        view.findViewById(R.id.rowTheme).setOnClickListener(v -> pickTheme());
        view.findViewById(R.id.rowLanguage).setOnClickListener(v -> pickLanguage());
        view.findViewById(R.id.rowClearCompleted).setOnClickListener(v ->
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.clear_completed_title)
                        .setMessage(R.string.clear_completed_message)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.action_clear_completed, (d, w) ->
                                DownloadEngine.get(requireContext()).clearCompleted())
                        .show());

        refreshValues();
        return view;
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshValues();
    }

    private void refreshValues() {
        if (!isAdded()) {
            return;
        }
        threadsValue.setText(getString(R.string.fmt_threads, settings.threads()));
        concurrentValue.setText(String.valueOf(settings.concurrent()));
        retryIntervalValue.setText(getString(R.string.fmt_seconds, settings.retryInterval()));
        retryCountValue.setText(settings.retryCount() == 0
                ? getString(R.string.settings_off) : String.valueOf(settings.retryCount()));
        switchWifi.setChecked(settings.wifiOnly());
        switchAutoResume.setChecked(settings.autoResume());
        switchPreallocate.setChecked(settings.preallocate());
        switchNotifications.setChecked(settings.notifications());
        switchClipboard.setChecked(settings.clipboardWatch());
        switchProxyDefault.setChecked(settings.proxyDefault());
        proxyTypeValue.setText(proxyTypeLabel(settings.proxyType()));
        String proxyHost = settings.proxyHost();
        proxyHostValue.setText(proxyHost.isEmpty()
                ? getString(R.string.settings_proxy_unset) : proxyHost);
        proxyPortValue.setText(settings.proxyPort() > 0
                ? String.valueOf(settings.proxyPort()) : getString(R.string.settings_proxy_unset));
        uaValue.setText(uaLabel(settings.uaMode()));
        String customUa = settings.uaCustom();
        uaCustomValue.setText(customUa.isEmpty()
                ? getString(R.string.settings_proxy_unset) : customUa);
        rowUaCustom.setVisibility(settings.uaMode() == Settings.UA_CUSTOM
                ? View.VISIBLE : View.GONE);
        String dirUri = settings.dirUri();
        folderValue.setText(dirUri == null
                ? getString(R.string.settings_folder_default)
                : FileUtils.folderLabel(requireContext(), dirUri));
        themeValue.setText(themeLabel(settings.themeMode()));
        languageValue.setText(languageLabel(settings.language()));
        versionValue.setText(getString(R.string.settings_version_summary, versionName()));
        batteryValue.setText(ignoringBattery() ? R.string.settings_battery_on
                : R.string.settings_battery_off);
    }

    private boolean ignoringBattery() {
        PowerManager power = (PowerManager) requireContext().getSystemService(android.content.Context.POWER_SERVICE);
        return power != null && power.isIgnoringBatteryOptimizations(requireContext().getPackageName());
    }

    private void openBatterySettings() {
        if (ignoringBattery()) {
            toast(R.string.settings_battery_on);
            return;
        }
        try {
            Intent request = new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + requireContext().getPackageName()));
            startActivity(request);
        } catch (Exception e) {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception ignored) {
                toast(R.string.open_failed);
            }
        }
    }

    private void pickThreads() {
        showSlider(getString(R.string.dialog_threads_title), 1, 32, settings.threads(),
                value -> getString(R.string.fmt_threads, value),
                value -> {
                    settings.setThreads(value);
                    refreshValues();
                });
    }

    private void pickRetryInterval() {
        showSlider(getString(R.string.dialog_retry_interval_title), 1, 120,
                Math.min(settings.retryInterval(), 120),
                value -> getString(R.string.fmt_seconds, value),
                value -> {
                    settings.setRetryInterval(value);
                    refreshValues();
                });
    }

    private void pickRetryCount() {
        showSlider(getString(R.string.dialog_retry_count_title), 0, 20, settings.retryCount(),
                value -> value == 0 ? getString(R.string.settings_off)
                        : String.valueOf(value),
                value -> {
                    settings.setRetryCount(value);
                    refreshValues();
                });
    }

    private void pickConcurrent() {
        showSlider(getString(R.string.dialog_concurrent_title), 1, 5, settings.concurrent(),
                value -> String.valueOf(value),
                value -> {
                    settings.setConcurrent(value);
                    refreshValues();
                });
    }

    private interface Labeler {
        String label(int value);
    }

    private interface Applier {
        void apply(int value);
    }

    private void showSlider(String title, int min, int max, int current,
                            Labeler labeler, Applier applier) {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_slider, null, false);
        TextView label = content.findViewById(R.id.value);
        Slider slider = content.findViewById(R.id.slider);
        slider.setValueFrom(min);
        slider.setValueTo(max);
        slider.setStepSize(1f);
        slider.setValue(Math.max(min, Math.min(max, current)));
        label.setText(labeler.label((int) slider.getValue()));
        slider.addOnChangeListener((s, value, fromUser) ->
                label.setText(labeler.label((int) value)));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setView(content)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (d, w) -> applier.apply((int) slider.getValue()))
                .show();
    }

    private void pickProxyType() {
        String[] labels = {"HTTP", "HTTPS", "SOCKS5"};
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_proxy_type_title)
                .setSingleChoiceItems(labels, settings.proxyType(), (dialog, which) -> {
                    settings.setProxyType(which);
                    refreshValues();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void editProxyHost() {
        showTextDialog(getString(R.string.dialog_proxy_host_title), settings.proxyHost(),
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, value -> {
                    settings.setProxyHost(value);
                    refreshValues();
                });
    }

    private void editProxyPort() {
        String initial = settings.proxyPort() > 0 ? String.valueOf(settings.proxyPort()) : "";
        showTextDialog(getString(R.string.dialog_proxy_port_title), initial,
                InputType.TYPE_CLASS_NUMBER, value -> {
                    int port;
                    try {
                        port = Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        port = -1;
                    }
                    if (port < 1 || port > 65535) {
                        toast(R.string.invalid_proxy_port);
                        return;
                    }
                    settings.setProxyPort(port);
                    refreshValues();
                });
    }

    private void pickUa() {
        String[] labels = {
                getString(R.string.settings_ua_mobile),
                getString(R.string.settings_ua_desktop),
                getString(R.string.settings_ua_custom)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_ua_title)
                .setSingleChoiceItems(labels, settings.uaMode(), (dialog, which) -> {
                    settings.setUaMode(which);
                    refreshValues();
                    dialog.dismiss();
                    if (which == Settings.UA_CUSTOM) {
                        editUaCustom();
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void editUaCustom() {
        showTextDialog(getString(R.string.dialog_ua_custom_title), settings.uaCustom(),
                InputType.TYPE_CLASS_TEXT, value -> {
                    settings.setUaCustom(value);
                    refreshValues();
                });
    }

    /**
     * Lets the whole setting row toggle its switch: the thumb is made non-clickable, so a tap
     * anywhere on the row (title, summary or switch) flips the value exactly once.
     */
    private void bindRowToggle(SwitchMaterial sw) {
        View row = (View) sw.getParent();
        if (row == null) {
            return;
        }
        sw.setClickable(false);
        sw.setFocusable(false);
        TypedValue ripple = new TypedValue();
        if (requireContext().getTheme().resolveAttribute(
                android.R.attr.selectableItemBackground, ripple, true)) {
            row.setBackgroundResource(ripple.resourceId);
        }
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> sw.setChecked(!sw.isChecked()));
    }

    private void showTextDialog(String title, String initial, int inputType, Consumer<String> onOk) {
        EditText edit = new EditText(requireContext());
        edit.setInputType(inputType);
        edit.setText(initial);
        edit.setSelection(edit.getText().length());
        edit.setSingleLine(true);
        FrameLayout container = new FrameLayout(requireContext());
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        container.setPadding(pad, pad / 2, pad, 0);
        container.addView(edit, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(title)
                .setView(container)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_ok, (dialog, which) ->
                        onOk.accept(edit.getText().toString().trim()))
                .show();
    }

    private String proxyTypeLabel(int type) {
        switch (type) {
            case Settings.PROXY_HTTPS:
                return "HTTPS";
            case Settings.PROXY_SOCKS5:
                return "SOCKS5";
            default:
                return "HTTP";
        }
    }

    private String uaLabel(int mode) {
        switch (mode) {
            case Settings.UA_DESKTOP:
                return getString(R.string.settings_ua_desktop);
            case Settings.UA_CUSTOM:
                return getString(R.string.settings_ua_custom);
            default:
                return getString(R.string.settings_ua_mobile);
        }
    }

    private void pickTheme() {
        String[] labels = {
                getString(R.string.settings_theme_system),
                getString(R.string.settings_theme_light),
                getString(R.string.settings_theme_dark)
        };
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_theme_title)
                .setSingleChoiceItems(labels, settings.themeMode(), (dialog, which) -> {
                    settings.setThemeMode(which);
                    EDMApp.applyTheme(which);
                    themeValue.setText(themeLabel(which));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private String themeLabel(int mode) {
        switch (mode) {
            case Settings.THEME_LIGHT:
                return getString(R.string.settings_theme_light);
            case Settings.THEME_DARK:
                return getString(R.string.settings_theme_dark);
            default:
                return getString(R.string.settings_theme_system);
        }
    }

    private void pickLanguage() {
        final String[] tags = {"", "en", "zh-CN"};
        String[] labels = {
                getString(R.string.settings_language_system),
                getString(R.string.settings_language_english),
                getString(R.string.settings_language_chinese)
        };
        String current = settings.language();
        int checked = 0;
        for (int i = 0; i < tags.length; i++) {
            if (matchesLanguage(tags[i], current)) {
                checked = i;
                break;
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.dialog_language_title)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    settings.setLanguage(tags[which]);
                    EDMApp.applyLanguage(tags[which]);
                    languageValue.setText(languageLabel(tags[which]));
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private String languageLabel(String tag) {
        if (matchesLanguage("en", tag)) {
            return getString(R.string.settings_language_english);
        }
        if (matchesLanguage("zh-CN", tag)) {
            return getString(R.string.settings_language_chinese);
        }
        return getString(R.string.settings_language_system);
    }

    /** Treats regional variants such as en-US or zh-Hans as their base language. */
    private static boolean matchesLanguage(String option, String tag) {
        if (option.equals(tag)) {
            return true;
        }
        if ("en".equals(option)) {
            return tag != null && tag.startsWith("en");
        }
        if ("zh-CN".equals(option)) {
            return tag != null && tag.startsWith("zh");
        }
        return option.isEmpty() && (tag == null || tag.isEmpty());
    }

    private String versionName() {
        try {
            return requireContext().getPackageManager()
                    .getPackageInfo(requireContext().getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "1.0.0";
        }
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    private void toast(int res) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show();
    }
}