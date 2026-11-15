package com.edm.downloader.data;

import java.util.ArrayList;
import java.util.List;

/** A download task and its live progress. Mutated by the engine, read by the UI on the main thread. */
public class DownloadTask {

    public long id;
    public String url = "";
    public String finalUrl;
    public String fileName = "";
    public String dirUri;
    public String fileUri;
    public String mime = "application/octet-stream";
    public String userAgent;
    public boolean proxy;
    public int proxyType;
    public String proxyHost;
    public int proxyPort;
    public int uaMode;
    public String cookie;
    public String referer;
    public volatile long total = -1;
    public volatile long downloaded;
    public volatile long speed;
    public volatile int state = TaskState.QUEUED;
    public int threads = 4;
    public boolean resumable = true;
    public boolean customName;
    public String etag;
    public String lastModified;
    public String error;
    /** Transient hint shown after a finished download (for example a folder fallback). */
    public String note;
    /** Automatic retry bookkeeping: attempts spent so far and the pending retry deadline. */
    public int retries;
    public volatile long retryAt;
    public long created = System.currentTimeMillis();
    public long completed;
    public final List<TaskSegment> segments = new ArrayList<>();

    public int percent() {
        if (total <= 0) {
            return state == TaskState.DONE ? 100 : 0;
        }
        return (int) Math.max(0, Math.min(100, downloaded * 100 / total));
    }

    public long remaining() {
        return total > 0 ? Math.max(0, total - downloaded) : -1;
    }

    public boolean isIndeterminate() {
        return total <= 0 && (state == TaskState.CONNECTING || state == TaskState.DOWNLOADING);
    }

    public String host() {
        String source = finalUrl != null ? finalUrl : url;
        try {
            String host = new java.net.URL(source).getHost();
            return host != null ? host : source;
        } catch (Exception ignored) {
            return source != null ? source : "";
        }
    }

    public long doneBytes() {
        if (!segments.isEmpty() && (state == TaskState.CONNECTING || state == TaskState.DOWNLOADING
                || state == TaskState.PAUSED || state == TaskState.FAILED)) {
            long sum = 0;
            for (TaskSegment segment : segments) {
                sum += segment.done;
            }
            return Math.max(downloaded, sum);
        }
        return downloaded;
    }

    public boolean hasProgress() {
        return doneBytes() > 0;
    }
}