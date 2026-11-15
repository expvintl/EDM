package com.edm.downloader.engine;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.edm.downloader.DownloadService;
import com.edm.downloader.R;
import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.TaskState;
import com.edm.downloader.ui.DetailActivity;
import com.edm.downloader.util.Fmt;
import com.edm.downloader.util.FileUtils;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/** Builds and posts download notifications. */
final class Notifier {

    static final int SUMMARY_ID = DownloadService.SUMMARY_ID;

    private static final String CHANNEL_PROGRESS = "download_progress";
    private static final String CHANNEL_DONE = "download_done";
    private static final String GROUP_DOWNLOADS = "com.edm.downloader.DOWNLOADS";
    private static final long PROGRESS_THROTTLE_MS = 900;

    private final Context context;
    private final NotificationManager manager;
    private final NotificationManagerCompat compat;
    private final Map<Long, Long> lastPost = new HashMap<>();
    private final Map<Long, Integer> lastState = new HashMap<>();

    Notifier(Context context) {
        this.context = context.getApplicationContext();
        manager = (NotificationManager) this.context.getSystemService(Context.NOTIFICATION_SERVICE);
        compat = NotificationManagerCompat.from(this.context);
        createChannels();
    }

    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel progress = new NotificationChannel(CHANNEL_PROGRESS,
                context.getString(R.string.notification_channel_progress), NotificationManager.IMPORTANCE_LOW);
        progress.setDescription(context.getString(R.string.notification_channel_progress_desc));
        progress.setShowBadge(false);
        progress.setSound(null, null);
        NotificationChannel done = new NotificationChannel(CHANNEL_DONE,
                context.getString(R.string.notification_channel_done), NotificationManager.IMPORTANCE_DEFAULT);
        done.setDescription(context.getString(R.string.notification_channel_done_desc));
        manager.createNotificationChannel(progress);
        manager.createNotificationChannel(done);
    }

    static int notificationId(long taskId) {
        return 1000 + (int) (taskId % 100000);
    }

    void update(DownloadTask task, boolean force) {
        if (!compat.areNotificationsEnabled()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        Long previous = lastPost.get(task.id);
        Integer previousState = lastState.get(task.id);
        boolean stateChanged = previousState == null || previousState != task.state;
        if (!force && !stateChanged && previous != null && now - previous < PROGRESS_THROTTLE_MS) {
            return;
        }
        lastPost.put(task.id, now);
        lastState.put(task.id, task.state);
        if (task.state == TaskState.DONE) {
            postDone(task);
        } else if (task.state == TaskState.FAILED) {
            postFailed(task);
        } else if (task.state == TaskState.PAUSED || task.state == TaskState.QUEUED
                || TaskState.isRunning(task.state) || task.state == TaskState.CONNECTING) {
            postProgress(task);
        }
    }

    private void postProgress(DownloadTask task) {
        NotificationCompat.Builder builder = base(task, CHANNEL_PROGRESS, icProgress(task))
                .setOngoing(TaskState.isRunning(task.state) || task.state == TaskState.QUEUED)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP_DOWNLOADS)
                .setContentText(progressLine(task));
        if (task.total > 0 && task.state != TaskState.QUEUED) {
            builder.setProgress(100, task.percent(), false);
            builder.setSubText(context.getString(R.string.fmt_percent, task.percent()));
        } else {
            builder.setProgress(0, 0, TaskState.isRunning(task.state));
        }
        boolean paused = task.state == TaskState.PAUSED;
        builder.addAction(0, context.getString(paused ? R.string.notification_action_resume
                : R.string.notification_action_pause), serviceAction(paused
                ? DownloadService.ACTION_RESUME : DownloadService.ACTION_PAUSE, task.id, 1));
        builder.addAction(0, context.getString(R.string.notification_action_cancel),
                serviceAction(DownloadService.ACTION_CANCEL, task.id, 2));
        post(task.id, builder.build());
    }

    private void postDone(DownloadTask task) {
        Intent open = FileUtils.openIntent(context, task);
        PendingIntent openIntent = open != null
                ? PendingIntent.getActivity(context, 6000 + (int) (task.id % 100000), open, pendingFlags())
                : contentIntent(task);
        NotificationCompat.Builder builder = base(task, CHANNEL_DONE, R.drawable.ic_stat_done)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentTitle(context.getString(R.string.notification_complete_title, task.fileName))
                .setContentText(context.getString(R.string.notification_complete_text,
                        Fmt.bytes(task.total > 0 ? task.total : task.downloaded)))
                .setContentIntent(openIntent);
        if (open != null) {
            builder.addAction(0, context.getString(R.string.action_open), openIntent);
        }
        post(task.id, builder.build());
    }

    private void postFailed(DownloadTask task) {
        String error = task.error != null && !task.error.isEmpty()
                ? task.error : context.getString(R.string.status_failed);
        NotificationCompat.Builder builder = base(task, CHANNEL_DONE, R.drawable.ic_stat_error)
                .setOngoing(false)
                .setAutoCancel(true)
                .setContentTitle(context.getString(R.string.notification_failed_title, task.fileName))
                .setContentText(context.getString(R.string.fmt_failed, error))
                .setContentIntent(contentIntent(task));
        builder.addAction(0, context.getString(R.string.notification_action_resume),
                serviceAction(DownloadService.ACTION_RESUME, task.id, 1));
        post(task.id, builder.build());
    }

    private String progressLine(DownloadTask task) {
        long done = task.doneBytes();
        String doneText = Fmt.bytes(done);
        String totalText = task.total > 0 ? Fmt.bytes(task.total) : context.getString(R.string.unknown_size);
        if (task.state == TaskState.PAUSED) {
            return context.getString(R.string.fmt_paused_at, doneText + " / " + totalText);
        }
        if (task.state == TaskState.QUEUED) {
            return context.getString(R.string.fmt_queued_size, totalText);
        }
        String speed = task.speed > 0 ? Fmt.speed(task.speed) : context.getString(R.string.speed_unknown);
        return context.getString(R.string.notification_downloading_line, doneText, totalText, speed);
    }

    private NotificationCompat.Builder base(DownloadTask task, String channel, int smallIcon) {
        return new NotificationCompat.Builder(context, channel)
                .setSmallIcon(smallIcon)
                .setContentTitle(task.fileName != null && !task.fileName.isEmpty()
                        ? task.fileName : context.getString(R.string.app_name))
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setContentIntent(contentIntent(task));
    }

    private PendingIntent contentIntent(DownloadTask task) {
        Intent intent = DetailActivity.intent(context, task.id);
        return PendingIntent.getActivity(context, contentRequest(task.id), intent, pendingFlags());
    }

    private PendingIntent serviceAction(String action, long taskId, int slot) {
        Intent intent = new Intent(context, DownloadService.class);
        intent.setAction(action);
        intent.putExtra(DownloadService.EXTRA_TASK_ID, taskId);
        return PendingIntent.getService(context,
                (int) (taskId % 100000) * 10 + slot, intent, pendingFlags());
    }

    private static int contentRequest(long taskId) {
        return 5000 + (int) (taskId % 100000);
    }

    private static int pendingFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private static int icProgress(DownloadTask task) {
        if (task.state == TaskState.PAUSED) {
            return R.drawable.ic_stat_download;
        }
        return R.drawable.ic_stat_download;
    }

    private void post(long taskId, Notification notification) {
        try {
            manager.notify(notificationId(taskId), notification);
        } catch (Exception ignored) {
            // Notification channel disabled or permission revoked mid-download.
        }
    }

    void cancel(long taskId) {
        lastPost.remove(taskId);
        lastState.remove(taskId);
        try {
            manager.cancel(notificationId(taskId));
        } catch (Exception ignored) {
        }
    }

    void cancelAll() {
        lastPost.clear();
        lastState.clear();
        try {
            manager.cancelAll();
        } catch (Exception ignored) {
        }
    }

    Notification foreground(Collection<DownloadTask> running, long speed, int queued) {
        long done = 0;
        long expected = 0;
        for (DownloadTask task : running) {
            done += task.doneBytes();
            if (task.total > 0) {
                expected += task.total;
            }
        }
        int count = running.size() + queued;
        String title = context.getString(R.string.notification_summary_title, count);
        int percent = expected > 0 ? (int) Math.min(100, done * 100 / expected) : -1;
        String text = percent >= 0
                ? context.getString(R.string.notification_summary_progress, percent,
                        Fmt.speed(speed), Fmt.bytes(done), Fmt.bytes(expected))
                : context.getString(R.string.notification_summary_text, Fmt.speed(speed));
        Intent intent = new Intent(context, com.edm.downloader.MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent content = PendingIntent.getActivity(context, 42, intent, pendingFlags());
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setNumber(count)
                .setGroup(GROUP_DOWNLOADS)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE);
        if (percent >= 0) {
            builder.setProgress(100, percent, false);
            builder.setSubText(context.getString(R.string.fmt_percent, percent));
        }
        return builder.build();
    }

    Notification idleForeground() {
        Intent intent = new Intent(context, com.edm.downloader.MainActivity.class);
        PendingIntent content = PendingIntent.getActivity(context, 43, intent, pendingFlags());
        return new NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(R.drawable.ic_stat_download)
                .setContentTitle(context.getString(R.string.app_name))
                .setContentText(context.getString(R.string.notification_summary_text,
                        context.getString(R.string.speed_unknown)))
                .setContentIntent(content)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setGroup(GROUP_DOWNLOADS)
                .setGroupSummary(true)
                .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
    }

    void notifyForeground(Notification notification) {
        try {
            manager.notify(SUMMARY_ID, notification);
        } catch (Exception ignored) {
        }
    }

    void cancelForeground() {
        try {
            manager.cancel(SUMMARY_ID);
        } catch (Exception ignored) {
        }
    }

    static Uri uriOf(DownloadTask task) {
        return task.fileUri != null ? Uri.parse(task.fileUri) : null;
    }
}