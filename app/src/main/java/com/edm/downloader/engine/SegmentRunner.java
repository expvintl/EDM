package com.edm.downloader.engine;

import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.TaskSegment;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;

/** Downloads one byte range with retries and exposes a cancellation hook. */
final class SegmentRunner implements Runnable {

    interface Listener {
        void onSegmentBytes(TaskSegment segment, int bytes);

        void onSegmentFinished(TaskSegment segment);

        void onSegmentError(TaskSegment segment, String message);
    }

    private static final int MAX_ATTEMPTS = 4;
    private static final int BUFFER = 64 * 1024;

    private final DownloadTask task;
    private final TaskSegment segment;
    private final Sink sink;
    private final Listener listener;
    private final Object sleepLock = new Object();

    private volatile boolean stopped;
    private volatile HttpURLConnection connection;
    private volatile InputStream input;
    private volatile OutputStream output;

    SegmentRunner(DownloadTask task, TaskSegment segment, Sink sink, Listener listener) {
        this.task = task;
        this.segment = segment;
        this.sink = sink;
        this.listener = listener;
    }

    void stop() {
        stopped = true;
        closeQuietly(input);
        OutputStream out = output;
        if (out != null) {
            try {
                out.close();
            } catch (Exception ignored) {
            }
        }
        HttpURLConnection current = connection;
        if (current != null) {
            try {
                current.disconnect();
            } catch (Exception ignored) {
            }
        }
        synchronized (sleepLock) {
            sleepLock.notifyAll();
        }
    }

    /**
     * Pushes buffered bytes to the sink. Called before a progress checkpoint is persisted,
     * so a process kill cannot leave {@code done} ahead of the bytes really on disk.
     */
    void flushBuffered() {
        OutputStream out = output;
        if (out != null) {
            try {
                out.flush();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void run() {
        segment.state = 1;
        int attempt = 0;
        while (!stopped) {
            try {
                downloadOnce();
                return;
            } catch (FatalException e) {
                segment.state = 3;
                if (!stopped) {
                    listener.onSegmentError(segment, e.getMessage());
                }
                return;
            } catch (IOException e) {
                if (stopped) {
                    return;
                }
                attempt++;
                if (attempt >= MAX_ATTEMPTS) {
                    segment.state = 3;
                    listener.onSegmentError(segment, messageOf(e));
                    return;
                }
                if (!sleep(1000L << (attempt - 1))) {
                    return;
                }
            } finally {
                closeQuietly(input);
                closeQuietly(output);
                HttpURLConnection current = connection;
                connection = null;
                if (current != null) {
                    try {
                        current.disconnect();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
    }

    private void downloadOnce() throws IOException {
        if (segment.isComplete()) {
            segment.state = 2;
            listener.onSegmentFinished(segment);
            return;
        }
        long from = segment.start + segment.done;
        long to = segment.end;
        if (to >= 0 && from > to) {
            segment.state = 2;
            listener.onSegmentFinished(segment);
            return;
        }
        String range = "bytes=" + from + "-" + (to >= 0 ? String.valueOf(to) : "");
        HttpURLConnection current = Http.open(task.url, task, range);
        connection = current;
        if (stopped) {
            return;
        }
        int code = current.getResponseCode();
        if (code == HttpURLConnection.HTTP_PARTIAL) {
            long actualStart = Http.parseContentRangeStart(current.getHeaderField("Content-Range"));
            if (actualStart >= 0 && actualStart != from) {
                throw new IOException("Server returned an unexpected range");
            }
        } else if (code == HttpURLConnection.HTTP_OK) {
            if (from != 0) {
                throw new FatalException("Server does not support resuming downloads");
            }
            if (task.segments.size() > 1 && !(segment.start == 0 && segment.end == task.total - 1)) {
                throw new FatalException("Server does not support range requests");
            }
        } else if (code == 416) {
            // Range beyond the end: the file likely changed on the server.
            if (from == 0) {
                throw new FatalException("HTTP 416");
            }
            throw new IOException("HTTP 416");
        } else if (code >= 400 && code != 408 && code != 429) {
            throw new FatalException("HTTP " + code);
        } else {
            throw new IOException("HTTP " + code);
        }

        InputStream in = current.getInputStream();
        input = in;
        if (stopped) {
            return;
        }
        OutputStream out = sink.openSegment(segment);
        output = out;
        byte[] buffer = new byte[BUFFER];
        try {
            int read;
            while (!stopped && (read = in.read(buffer)) > 0) {
                if (to >= 0) {
                    long left = segment.length() - segment.done;
                    if (left <= 0) {
                        break;
                    }
                    if (read > left) {
                        read = (int) left;
                    }
                }
                out.write(buffer, 0, read);
                segment.done += read;
                listener.onSegmentBytes(segment, read);
            }
            out.flush();
        } finally {
            closeQuietly(out);
            output = null;
        }
        if (stopped) {
            return;
        }
        if (to < 0 || segment.done >= segment.length()) {
            segment.state = 2;
            listener.onSegmentFinished(segment);
        } else {
            throw new IOException("Connection closed before the range was complete");
        }
    }

    private boolean sleep(long millis) {
        long end = System.currentTimeMillis() + millis;
        synchronized (sleepLock) {
            while (!stopped) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return true;
                }
                try {
                    sleepLock.wait(Math.min(left, 500));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return false;
    }

    private static String messageOf(IOException e) {
        String message = e.getMessage();
        return message != null && !message.isEmpty() ? message : "Network error";
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }

    static final class FatalException extends IOException {
        FatalException(String message) {
            super(message);
        }
    }
}