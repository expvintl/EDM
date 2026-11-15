package com.edm.downloader.ui;

import android.content.Context;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.edm.downloader.R;
import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.TaskState;
import com.edm.downloader.engine.DownloadEngine;
import com.edm.downloader.util.Fmt;

import java.util.Locale;

/** Maps task data onto string and drawable resources; keeps the data layer UI-free. */
public final class TaskUi {

    private TaskUi() {
    }

    @StringRes
    public static int stateLabel(int state) {
        switch (state) {
            case TaskState.QUEUED:
                return R.string.status_queued;
            case TaskState.CONNECTING:
                return R.string.status_connecting;
            case TaskState.DOWNLOADING:
                return R.string.status_downloading;
            case TaskState.PAUSED:
                return R.string.status_paused;
            case TaskState.DONE:
                return R.string.status_done;
            case TaskState.FAILED:
                return R.string.status_failed;
            default:
                return R.string.status_queued;
        }
    }

    @ColorRes
    public static int stateColor(int state) {
        switch (state) {
            case TaskState.DOWNLOADING:
            case TaskState.CONNECTING:
                return R.color.edm_primary;
            case TaskState.PAUSED:
                return R.color.edm_warning;
            case TaskState.DONE:
                return R.color.edm_success;
            case TaskState.FAILED:
                return R.color.edm_error;
            default:
                return R.color.edm_text_secondary;
        }
    }

    public static int color(Context context, @ColorRes int res) {
        return ContextCompat.getColor(context, res);
    }

    @DrawableRes
    public static int iconFor(String fileName) {
        switch (typeOf(fileName)) {
            case "video":
                return R.drawable.ic_file_video;
            case "audio":
                return R.drawable.ic_file_audio;
            case "image":
                return R.drawable.ic_file_image;
            case "apk":
                return R.drawable.ic_file_apk;
            case "archive":
                return R.drawable.ic_file_archive;
            case "doc":
                return R.drawable.ic_file_doc;
            default:
                return R.drawable.ic_file;
        }
    }

    @ColorRes
    public static int iconTint(String fileName) {
        switch (typeOf(fileName)) {
            case "video":
                return R.color.edm_icon_video;
            case "audio":
                return R.color.edm_icon_audio;
            case "image":
                return R.color.edm_icon_image;
            case "apk":
                return R.color.edm_icon_apk;
            case "archive":
                return R.color.edm_icon_archive;
            case "doc":
                return R.color.edm_icon_doc;
            default:
                return R.color.edm_icon_other;
        }
    }

    @ColorRes
    public static int iconBackground(String fileName) {
        switch (typeOf(fileName)) {
            case "video":
                return R.color.edm_icon_video_bg;
            case "audio":
                return R.color.edm_icon_audio_bg;
            case "image":
                return R.color.edm_icon_image_bg;
            case "apk":
                return R.color.edm_icon_apk_bg;
            case "archive":
                return R.color.edm_icon_archive_bg;
            case "doc":
                return R.color.edm_icon_doc_bg;
            default:
                return R.color.edm_icon_other_bg;
        }
    }

    private static String typeOf(String fileName) {
        if (fileName == null) {
            return "other";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "other";
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.US);
        switch (ext) {
            case "mp4":
            case "mkv":
            case "avi":
            case "mov":
            case "webm":
            case "flv":
            case "wmv":
            case "m4v":
            case "ts":
            case "3gp":
                return "video";
            case "mp3":
            case "flac":
            case "wav":
            case "m4a":
            case "aac":
            case "ogg":
            case "opus":
            case "wma":
                return "audio";
            case "jpg":
            case "jpeg":
            case "png":
            case "gif":
            case "webp":
            case "bmp":
            case "svg":
            case "heic":
                return "image";
            case "apk":
            case "xapk":
            case "apks":
            case "aab":
                return "apk";
            case "zip":
            case "rar":
            case "7z":
            case "tar":
            case "gz":
            case "tgz":
            case "bz2":
            case "xz":
            case "iso":
            case "img":
            case "bin":
                return "archive";
            case "pdf":
            case "doc":
            case "docx":
            case "xls":
            case "xlsx":
            case "ppt":
            case "pptx":
            case "txt":
            case "epub":
            case "mobi":
            case "rtf":
            case "csv":
                return "doc";
            default:
                return "other";
        }
    }

    public static String sizeWithTotal(DownloadTask task) {
        if (task.total > 0) {
            return Fmt.bytes(task.total);
        }
        return null;
    }

    public static String progressLine(Context context, DownloadTask task, boolean waitingWifi) {
        switch (task.state) {
            case TaskState.QUEUED:
                if (waitingWifi) {
                    return context.getString(R.string.status_waiting_wifi);
                }
                return task.total > 0
                        ? context.getString(R.string.fmt_queued_size, Fmt.bytes(task.total))
                        : context.getString(R.string.status_queued);
            case TaskState.CONNECTING:
                return context.getString(R.string.status_connecting);
            case TaskState.DOWNLOADING: {
                String done = Fmt.bytes(task.doneBytes());
                String total = task.total > 0 ? Fmt.bytes(task.total) : context.getString(R.string.unknown_size);
                String speed = task.speed > 0 ? Fmt.speed(task.speed) : context.getString(R.string.speed_unknown);
                return context.getString(R.string.fmt_progress, done, total) + " · " + speed;
            }
            case TaskState.PAUSED:
                return context.getString(R.string.fmt_paused_at,
                        task.total > 0 ? Fmt.percent(task.doneBytes(), task.total) : Fmt.bytes(task.doneBytes()));
            case TaskState.DONE: {
                if (task.completed > 0) {
                    return context.getString(R.string.fmt_completed_on,
                            task.total > 0 ? Fmt.bytes(task.total) : Fmt.bytes(task.downloaded),
                            Fmt.date(task.completed));
                }
                return context.getString(R.string.status_done);
            }
            case TaskState.FAILED: {
                String error = task.error != null && !task.error.isEmpty()
                        ? task.error : context.getString(R.string.status_failed);
                String line = context.getString(R.string.fmt_failed, error);
                if (task.retryAt > 0) {
                    long left = Math.max(0L, (task.retryAt - System.currentTimeMillis()) / 1000L);
                    int limit = Math.max(1, DownloadEngine.get(context).settings().retryCount());
                    line += " · " + context.getString(R.string.retry_waiting, left,
                            Math.min(task.retries + 1, limit), limit);
                }
                return line;
            }
            default:
                return "";
        }
    }

    public static String metaLine(Context context, DownloadTask task) {
        switch (task.state) {
            case TaskState.DOWNLOADING: {
                StringBuilder builder = new StringBuilder();
                builder.append(task.speed > 0
                        ? Fmt.eta(task.remaining(), task.speed)
                        : context.getString(R.string.speed_unknown));
                if (task.segments.size() > 1) {
                    if (builder.length() > 0) {
                        builder.append(" · ");
                    }
                    builder.append(context.getString(R.string.fmt_threads, task.segments.size()));
                }
                return builder.toString();
            }
            case TaskState.DONE:
                return task.fileName;
            default:
                return task.host();
        }
    }
}