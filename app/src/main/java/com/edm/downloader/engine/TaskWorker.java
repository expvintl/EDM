package com.edm.downloader.engine;

import android.content.Context;

import com.edm.downloader.data.DownloadStore;
import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.Settings;
import com.edm.downloader.data.TaskSegment;
import com.edm.downloader.data.TaskState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Runs one download: probe, split, start segment threads, monitor and publish. */
final class TaskWorker implements Runnable, SegmentRunner.Listener {

    private static final long MIN_SEGMENT_BYTES = 512 * 1024;
    private static final int MAX_SEGMENTS = 32;
    private static final long TICK_MS = 300;
    private static final long PERSIST_MS = 2000;
    private static final int SPEED_WINDOW = 8;

    private final DownloadEngine engine;
    private final Context context;
    private final DownloadStore store;
    private final Settings settings;
    private final DownloadTask task;

    private final Object monitorLock = new Object();
    private final List<SegmentRunner> runners = new ArrayList<>();

    private volatile boolean stopRequested;
    private volatile boolean cancelRequested;
    private volatile String failure;
    private Sink sink;
    private ExecutorService executor;

    TaskWorker(DownloadEngine engine, Context context, DownloadStore store, Settings settings, DownloadTask task) {
        this.engine = engine;
        this.context = context.getApplicationContext();
        this.store = store;
        this.settings = settings;
        this.task = task;
    }

    void requestStop() {
        stopRequested = true;
        synchronized (monitorLock) {
            monitorLock.notifyAll();
        }
        stopRunners();
    }

    void requestCancel() {
        cancelRequested = true;
        requestStop();
    }

    boolean isCancelRequested() {
        return cancelRequested;
    }

    DownloadTask task() {
        return task;
    }

    @Override
    public void run() {
        try {
            boolean ready = prepare();
            if (!ready) {
                return;
            }
            if (stopRequested) {
                finishPaused();
                return;
            }
            task.state = TaskState.DOWNLOADING;
            engine.notifyState(task);
            startRunners();
            monitor();
        } catch (IOException e) {
            fail(messageOf(e));
        } catch (RuntimeException e) {
            fail(messageOf(e));
        } finally {
            shutdownExecutor();
            if (cancelRequested) {
                engine.workerCancelled(task);
            } else {
                engine.workerFinished(this);
            }
        }
    }

    private boolean prepare() throws IOException {
        task.state = TaskState.CONNECTING;
        task.speed = 0;
        task.error = null;
        task.note = null;
        engine.notifyState(task);

        // If every range is already on disk (typically a task that failed only while
        // moving the finished file into the target folder), finish it without touching
        // the network: the original URL may already have expired.
        if (allRangesOnDisk()) {
            task.downloaded = sumDone();
            finish();
            return false;
        }

        long previousTotal = task.total;
        String previousEtag = task.etag;
        HttpProbe.Result probe = HttpProbe.probe(task);
        if (stopRequested) {
            return false;
        }
        boolean mismatch = previousTotal > 0 && probe.total > 0 && previousTotal != probe.total;
        if (!mismatch && previousEtag != null && probe.etag != null && !previousEtag.equals(probe.etag)) {
            mismatch = true;
        }
        if (probe.total > 0) {
            task.total = probe.total;
        }
        task.finalUrl = probe.finalUrl;
        if (probe.mime != null && !probe.mime.isEmpty()) {
            task.mime = probe.mime;
        }
        if (probe.etag != null) {
            task.etag = probe.etag;
        }
        if (probe.lastModified != null) {
            task.lastModified = probe.lastModified;
        }
        if (!task.customName && probe.fileName != null && !probe.fileName.isEmpty()) {
            task.fileName = probe.fileName;
        }
        if (task.fileName == null || task.fileName.isEmpty()) {
            task.fileName = "download";
        }
        task.resumable = probe.ranges;

        if (!task.resumable) {
            // Without range support every attempt starts from byte zero.
            mismatch = true;
        }
        boolean grewFromUnknown = task.segments.size() == 1 && task.segments.get(0).end < 0
                && task.total > 0 && task.resumable;
        if (mismatch || grewFromUnknown) {
            resetSegments();
        }
        if (task.segments.isEmpty()) {
            buildSegments();
        }
        task.threads = Math.max(1, task.segments.size());
        if (task.segments.isEmpty()) {
            throw new IOException("Cannot split download");
        }

        sink = task.dirUri != null ? new SafSink(context, task) : new FileSink(context, task);
        sink.prepare(task);
        task.downloaded = sumDone();
        store.update(task);
        store.saveSegments(task);
        engine.notifyState(task);

        boolean allDone = true;
        for (TaskSegment segment : task.segments) {
            if (!segment.isComplete()) {
                allDone = false;
                break;
            }
        }
        if (allDone) {
            finish();
            return false;
        }
        return true;
    }

    private void buildSegments() {
        task.segments.clear();
        long total = task.total;
        int wanted = settings.threads();
        if (total <= 0 || !task.resumable) {
            task.segments.add(new TaskSegment(0, 0, total > 0 ? total - 1 : -1));
            return;
        }
        int count = (int) Math.min(Math.min(wanted, MAX_SEGMENTS), Math.max(1, total / MIN_SEGMENT_BYTES));
        if (count <= 1) {
            task.segments.add(new TaskSegment(0, 0, total - 1));
            return;
        }
        long part = total / count;
        long start = 0;
        for (int i = 0; i < count; i++) {
            long end = i == count - 1 ? total - 1 : start + part - 1;
            task.segments.add(new TaskSegment(i, start, end));
            start = end + 1;
        }
    }

    private void resetSegments() {
        task.segments.clear();
        task.downloaded = 0;
        if (sink != null) {
            sink.discard();
        }
    }

    private void startRunners() {
        int count = 0;
        for (TaskSegment segment : task.segments) {
            if (!segment.isComplete()) {
                count++;
            }
        }
        executor = Executors.newFixedThreadPool(Math.max(1, count), new SegmentFactory(task.id));
        for (TaskSegment segment : task.segments) {
            if (segment.isComplete()) {
                continue;
            }
            SegmentRunner runner = new SegmentRunner(task, segment, sink, this);
            runners.add(runner);
            executor.execute(runner);
        }
    }

    private void monitor() throws IOException {
        long lastSum = sumDone();
        long lastTime = System.currentTimeMillis();
        long lastPersist = 0;
        long[] windowBytes = new long[SPEED_WINDOW];
        long[] windowMs = new long[SPEED_WINDOW];
        int windowCursor = 0;
        int windowFilled = 0;
        while (true) {
            if (stopRequested) {
                break;
            }
            String currentFailure = failure;
            if (currentFailure != null) {
                fail(currentFailure);
                return;
            }
            if (allComplete()) {
                break;
            }
            synchronized (monitorLock) {
                try {
                    monitorLock.wait(TICK_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            long now = System.currentTimeMillis();
            long sum = sumDone();
            task.downloaded = sum;
            long dt = now - lastTime;
            if (dt > 0) {
                // Average over the last few ticks so the displayed speed does not flicker.
                windowBytes[windowCursor] = Math.max(0, sum - lastSum);
                windowMs[windowCursor] = dt;
                windowCursor = (windowCursor + 1) % SPEED_WINDOW;
                if (windowFilled < SPEED_WINDOW) {
                    windowFilled++;
                }
                long windowBytesTotal = 0;
                long windowTime = 0;
                for (int i = 0; i < windowFilled; i++) {
                    windowBytesTotal += windowBytes[i];
                    windowTime += windowMs[i];
                }
                task.speed = windowTime > 0 ? windowBytesTotal * 1000 / windowTime : 0;
            }
            lastSum = sum;
            lastTime = now;
            if (now - lastPersist >= PERSIST_MS) {
                lastPersist = now;
                // Flush first: a persisted "done" must never run past the bytes that
                // reached the sink, or an in-place resume would skip a zero-filled hole.
                for (SegmentRunner runner : runners) {
                    runner.flushBuffered();
                }
                store.updateProgress(task);
                store.saveSegments(task);
            }
            engine.notifyProgress(task);
        }
        if (stopRequested) {
            if (cancelRequested) {
                return;
            }
            finishPaused();
            return;
        }
        if (failure != null) {
            fail(failure);
            return;
        }
        finish();
    }

    private void finish() throws IOException {
        stopRunners();
        if (stopRequested && cancelRequested) {
            return;
        }
        sink.finish(task);
        if (task.total <= 0) {
            task.total = Math.max(task.downloaded, sumDone());
        }
        task.downloaded = task.total;
        task.speed = 0;
        task.error = null;
        task.state = TaskState.DONE;
        task.completed = System.currentTimeMillis();
        store.update(task);
        store.updateProgress(task);
        store.saveSegments(task);
        engine.notifyState(task);
    }

    private void finishPaused() {
        stopRunners();
        task.speed = 0;
        task.state = TaskState.PAUSED;
        task.downloaded = sumDone();
        store.updateProgress(task);
        store.saveSegments(task);
        engine.notifyState(task);
    }

    private void fail(String message) {
        stopRunners();
        task.speed = 0;
        task.state = TaskState.FAILED;
        task.error = message != null && !message.isEmpty() ? message : "Download failed";
        task.downloaded = sumDone();
        store.updateProgress(task);
        store.saveSegments(task);
        engine.notifyState(task);
    }

    private void stopRunners() {
        for (SegmentRunner runner : new ArrayList<>(runners)) {
            runner.stop();
        }
        synchronized (monitorLock) {
            monitorLock.notifyAll();
        }
    }

    private boolean allComplete() {
        if (task.segments.isEmpty()) {
            return false;
        }
        for (TaskSegment segment : task.segments) {
            if (!segment.isComplete()) {
                return false;
            }
        }
        return true;
    }

    /**
     * True when the persisted segments describe a complete download whose bytes are still
     * present in the sink. The sink's prepare() trims {@code done} to the real file lengths,
     * so truncated or cleared temporary files correctly fall through to a fresh probe.
     */
    private boolean allRangesOnDisk() throws IOException {
        if (task.segments.isEmpty()) {
            return false;
        }
        sink = task.dirUri != null ? new SafSink(context, task) : new FileSink(context, task);
        sink.prepare(task);
        return allComplete();
    }

    private long sumDone() {
        long sum = 0;
        for (TaskSegment segment : task.segments) {
            sum += segment.done;
        }
        return sum;
    }

    private void shutdownExecutor() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    private static String messageOf(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isEmpty()) {
            return "Network error";
        }
        return message;
    }

    @Override
    public void onSegmentBytes(TaskSegment segment, int bytes) {
        // Intentionally does not wake the monitor: waking on every 64 KB read turned
        // the 300 ms speed window into a few milliseconds of bursts, inflating speed.
    }

    @Override
    public void onSegmentFinished(TaskSegment segment) {
        synchronized (monitorLock) {
            monitorLock.notifyAll();
        }
    }

    @Override
    public void onSegmentError(TaskSegment segment, String message) {
        if (failure == null) {
            failure = message;
        }
        stopRunners();
    }

    private static final class SegmentFactory implements ThreadFactory {

        private final long taskId;
        private final AtomicInteger counter = new AtomicInteger();

        SegmentFactory(long taskId) {
            this.taskId = taskId;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "edm-seg-" + taskId + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }

}