package com.edm.downloader;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;

import com.edm.downloader.data.Settings;
import com.edm.downloader.engine.DownloadEngine;
import com.edm.downloader.ui.AddDownloadSheet;
import com.edm.downloader.ui.BrowserFragment;
import com.edm.downloader.ui.DownloadsFragment;
import com.edm.downloader.ui.SettingsFragment;
import com.edm.downloader.ui.SystemBars;
import com.edm.downloader.util.FileUtils;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Main screen: the download lists, the built-in browser and settings. */
public class MainActivity extends AppCompatActivity {

    private static final String TAG_DOWNLOADS = "tab_downloads";
    private static final String TAG_BROWSER = "tab_browser";
    private static final String TAG_SETTINGS = "tab_settings";
    private static final String TAG_SHEET = "add_download";
    private static final String STATE_TAB = "tab_index";

    private static final String[] TAGS = {TAG_DOWNLOADS, TAG_BROWSER, TAG_SETTINGS};
    private static final Pattern URL_PATTERN = Pattern.compile("https?://[^\\s]+",
            Pattern.CASE_INSENSITIVE);

    private MaterialToolbar toolbar;
    private BottomNavigationView bottomNav;
    private FloatingActionButton fab;
    private int currentTab;
    private boolean suppressNav;
    private boolean selectionMode;

    private ActivityResultLauncher<String> notificationPermission;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        bottomNav = findViewById(R.id.bottomNav);
        fab = findViewById(R.id.fab);
        SystemBars.apply(this);
        SystemBars.padToolbar(toolbar);
        SystemBars.padBottom(bottomNav);

        notificationPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                });

        fab.setOnClickListener(v -> showAddSheet(null));
        bottomNav.setOnItemSelectedListener(item -> {
            if (suppressNav) {
                return true;
            }
            int id = item.getItemId();
            if (id == R.id.nav_downloads) {
                selectTab(0);
            } else if (id == R.id.nav_browser) {
                selectTab(1);
            } else if (id == R.id.nav_settings) {
                selectTab(2);
            }
            return true;
        });

        int tab = savedInstanceState == null ? 0 : savedInstanceState.getInt(STATE_TAB, 0);
        if (tab < 0 || tab >= TAGS.length) {
            tab = 0;
        }
        selectTab(tab);
        DownloadEngine.get(this).setListener(this::refreshCurrent);
        requestNotificationPermissionIfNeeded();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_TAB, currentTab);
    }

    private void selectTab(int index) {
        currentTab = index;
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (String tag : TAGS) {
            Fragment existing = getSupportFragmentManager().findFragmentByTag(tag);
            if (existing != null) {
                transaction.hide(existing);
            }
        }
        Fragment target = getSupportFragmentManager().findFragmentByTag(TAGS[index]);
        if (target == null) {
            target = createFragment(index);
            transaction.add(R.id.container, target, TAGS[index]);
        }
        transaction.show(target);
        transaction.commit();

        suppressNav = true;
        bottomNav.getMenu().findItem(navId(index)).setChecked(true);
        suppressNav = false;
        updateFabVisibility();
        toolbar.setTitle(index == 0 ? R.string.app_name : navTitle(index));
    }

    /** Called by the task lists when multi-select starts or ends. */
    public void setSelectionMode(boolean active) {
        selectionMode = active;
        updateFabVisibility();
    }

    private void updateFabVisibility() {
        fab.setVisibility(!selectionMode && currentTab == 0
                ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    private Fragment createFragment(int index) {
        switch (index) {
            case 1:
                return new BrowserFragment();
            case 2:
                return new SettingsFragment();
            default:
                return new DownloadsFragment();
        }
    }

    private int navId(int index) {
        switch (index) {
            case 1:
                return R.id.nav_browser;
            case 2:
                return R.id.nav_settings;
            default:
                return R.id.nav_downloads;
        }
    }

    private int navTitle(int index) {
        switch (index) {
            case 1:
                return R.string.tab_browser;
            case 2:
                return R.string.tab_settings;
            default:
                return R.string.tab_downloads;
        }
    }

    private void refreshCurrent() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(TAGS[currentTab]);
        if (fragment instanceof DownloadsFragment) {
            ((DownloadsFragment) fragment).refresh();
        }
    }

    private void showAddSheet(String url) {
        if (getSupportFragmentManager().findFragmentByTag(TAG_SHEET) != null) {
            return;
        }
        AddDownloadSheet.newInstance(url, null, null, null, null, null)
                .show(getSupportFragmentManager(), TAG_SHEET);
    }

    /**
     * Handles the only intent EDM accepts from other apps: a browser handing over a download
     * as a typed http(s) link. Shares and local file opens are not registered in the manifest,
     * so nothing else reaches this method.
     */
    private void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())
                || intent.getData() == null) {
            return;
        }
        final String url = intent.getData().toString();
        if (!FileUtils.isHttp(url)) {
            return;
        }
        fab.post(() -> showAddSheet(url));
    }

    @Nullable
    private static String extractUrl(String text) {
        if (text == null) {
            return null;
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        if (matcher.find()) {
            String url = matcher.group();
            while (!url.isEmpty() && ".,);]}\"'".indexOf(url.charAt(url.length() - 1)) >= 0) {
                url = url.substring(0, url.length() - 1);
            }
            return url;
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        checkClipboard();
    }

    private void checkClipboard() {
        Settings settings = DownloadEngine.get(this).settings();
        if (!settings.clipboardWatch()
                || getSupportFragmentManager().findFragmentByTag(TAG_SHEET) != null) {
            return;
        }
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null || !clipboard.hasPrimaryClip()) {
            return;
        }
        ClipData clip = clipboard.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return;
        }
        CharSequence text = clip.getItemAt(0).coerceToText(this);
        String url = extractUrl(text == null ? null : text.toString());
        if (url == null || url.equals(settings.lastClip())) {
            return;
        }
        settings.setLastClip(url);
        Snackbar.make(findViewById(R.id.container), R.string.link_found, Snackbar.LENGTH_LONG)
                .setAction(R.string.link_found_action, v -> showAddSheet(url))
                .show();
    }

    private void requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && DownloadEngine.get(this).settings().notifications()
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        // Vector icons are authored black; the toolbar needs them white.
        int white = ContextCompat.getColor(this, R.color.edm_white);
        tintIcon(menu.findItem(R.id.action_search), white);
        tintIcon(menu.findItem(R.id.action_sort), white);
        return true;
    }

    private void tintIcon(android.view.MenuItem item, int color) {
        if (item == null || item.getIcon() == null) {
            return;
        }
        android.graphics.drawable.Drawable icon =
                DrawableCompat.wrap(item.getIcon()).mutate();
        DrawableCompat.setTint(icon, color);
        item.setIcon(icon);
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        int id = item.getItemId();
        DownloadEngine engine = DownloadEngine.get(this);
        if (id == R.id.action_search) {
            promptSearch();
            return true;
        }
        if (id == R.id.action_sort) {
            promptSort();
            return true;
        }
        if (id == R.id.action_pause_all) {
            engine.pauseAll();
            return true;
        }
        if (id == R.id.action_resume_all) {
            engine.resumeAll();
            return true;
        }
        if (id == R.id.action_clear_completed) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.clear_completed_title)
                    .setMessage(R.string.clear_completed_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_clear_completed, (d, w) -> {
                        engine.clearCompleted();
                        refreshCurrent();
                    })
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void promptSearch() {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.action_search);
        int pad = Math.round(20 * getResources().getDisplayMetrics().density);
        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setPadding(pad, pad / 2, pad, 0);
        wrapper.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_search)
                .setView(wrapper)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_search, (d, w) -> {
                    if (currentTab != 0) {
                        selectTab(0);
                        getSupportFragmentManager().executePendingTransactions();
                    }
                    Fragment fragment = getSupportFragmentManager().findFragmentByTag(TAGS[0]);
                    if (fragment instanceof DownloadsFragment) {
                        ((DownloadsFragment) fragment).showAll();
                        ((DownloadsFragment) fragment)
                                .setQuery(input.getText().toString().trim());
                    }
                })
                .show();
    }

    private void promptSort() {
        String[] labels = {
                getString(R.string.sort_time),
                getString(R.string.sort_name),
                getString(R.string.sort_size)
        };
        Settings settings = DownloadEngine.get(this).settings();
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.sort_title)
                .setSingleChoiceItems(labels, settings.sortMode(), (dialog, which) -> {
                    settings.setSortMode(which);
                    refreshCurrent();
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    @Override
    public void onBackPressed() {
        Fragment fragment = getSupportFragmentManager().findFragmentByTag(TAG_BROWSER);
        if (currentTab == 1 && fragment instanceof BrowserFragment
                && ((BrowserFragment) fragment).canGoBack()) {
            ((BrowserFragment) fragment).goBack();
            return;
        }
        super.onBackPressed();
    }
}