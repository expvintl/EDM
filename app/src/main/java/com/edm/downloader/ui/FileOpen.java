package com.edm.downloader.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import com.edm.downloader.R;
import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.util.FileUtils;

import java.util.Locale;

/** Opens a finished download; APKs go through the "install unknown apps" permission first. */
public final class FileOpen {

    private FileOpen() {
    }

    public static boolean isApk(String name) {
        return name != null && name.toLowerCase(Locale.US).endsWith(".apk");
    }

    public static void open(Activity activity, DownloadTask task) {
        if (!FileUtils.exists(activity, task)) {
            toast(activity, R.string.detail_open_missing);
            return;
        }
        if (isApk(task.fileName) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            toast(activity, R.string.install_permission_needed);
            try {
                activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + activity.getPackageName())));
            } catch (Exception ignored) {
                toast(activity, R.string.open_failed);
            }
            return;
        }
        Intent intent = FileUtils.openIntent(activity, task);
        if (intent == null) {
            toast(activity, R.string.detail_open_missing);
            return;
        }
        try {
            activity.startActivity(intent);
        } catch (ActivityNotFoundException e) {
            toast(activity, R.string.detail_no_app);
        } catch (Exception e) {
            toast(activity, R.string.open_failed);
        }
    }

    private static void toast(Activity activity, int res) {
        Toast.makeText(activity, res, Toast.LENGTH_SHORT).show();
    }
}
