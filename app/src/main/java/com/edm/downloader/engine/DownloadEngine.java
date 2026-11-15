package com.edm.downloader.engine;

import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.edm.downloader.DownloadService;
import com.edm.downloader.R;
import com.edm.downloader.data.DownloadStore;
import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.Settings;
import com.edm.downloader.data.TaskState;
import com.edm.downloader.util.FileUtils;
import com.edm.downloader.util.Net;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** Central registry and scheduler for all downloads. */
public final class DownloadEngine {

    public interface Listener {
        void onDownloadsChanged();
    }

    private static volatile DownloadEngine instance;

    private final Context context;
    private final DownloadStore store;
    private final Settings settings;
    private final Notifier notifier;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService workerExecutor;
    /** File deletion never runs on the UI thread: slow storage would freeze the app. */
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "edm-io");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<Long, DownloadTask> tasks = new LinkedHashMap<>();
    private final Map<Long, TaskWorker> running = new LinkedHashMap<>();
    /** Resume taps that arrived while a paused worker was still winding down. */
    private final Set<Long> pendingResume = new HashSet<>();

    private volatile Listener listener;
    private PowerManager.WakeLock wakeLock;
    private long lastSummary;
    private boolean serviceActive;
    private final List<Long> interrupted = new ArrayList<>();
    private final Set<Long> deleting = new HashSet<>();
    /** Tasks whose rows are hidden while their files are being removed in the background. */
    private final Set<Long> pendingDelete = new HashSet<>();

    public static DownloadEngine get(Context context) {
        DownloadEngine local = instance;
        if (local == null) {
            synchronized (DownloadEngine.class) {
                local = instance;
                if (local == null) {
                    local = new DownloadEngine(context.getApplicationContext());
                    instance = local;
                }
            }
        }
        return local;
    }

    private DownloadEngine(Context context) {
        this.context = context;
        store = new DownloadStore(context);
        settings = new Settings(context);
        notifier = new Notifier(context);
        workerExecutor = Executors.newCachedThreadPool(new WorkerFactory());
        interrupted.addAll(store.resetRunning());
        for (DownloadTask task : store.loadAll()) {
            tasks.put(task.id, task);
        }
        registerNetworkCallback();
    }

    public Settings settings() {
        return settings;
    }

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    /** Resumes only the tasks that were interrupted by process death. */
    public void autoResume() {
        if (!settings.autoResume() || interrupted.isEmpty()) {
            return;
        }
        synchronized (tasks) {
            for (Long id : interrupted) {
                DownloadTask task = tasks.get(id);
                if (task != null && task.state == TaskState.PAUSED) {
                    task.state = TaskState.QUEUED;
                    store.updateProgress(task);
                }
            }
        }
        interrupted.clear();
        notifyChanged();
        main.post(this::schedule);
    }

    public List<DownloadTask> tasks() {
        synchronized (tasks) {
            List<DownloadTask> list = new ArrayList<>(tasks.size());
            synchronized (pendingDelete) {
                for (DownloadTask task : tasks.values()) {
                    // Rows being deleted disappear immediately; the IO happens in the background.
                    if (!pendingDelete.contains(task.id)) {
                        list.add(task);
                    }
                }
            }
            return list;
        }
    }

    public DownloadTask find(long id) {
        synchronized (tasks) {
            return tasks.get(id);
        }
    }

    public DownloadTask add(String url, String fileName, String mime, String referer,
                            String cookie, int uaMode, String customUa,
                            boolean useProxy, int proxyType, String proxyHost, int proxyPort) {
        if (url == null || !FileUtils.isHttp(url.trim())) {
            return null;
        }
        String cleanUrl = url.trim();
        DownloadTask task = new DownloadTask();
        task.url = cleanUrl;
        task.referer = referer;
        task.uaMode = uaMode;
        task.userAgent = Http.resolveUa(uaMode, customUa);
        task.cookie = cookie;
        String proxyServer = proxyHost == null ? "" : proxyHost.trim();
        task.proxy = useProxy && !proxyServer.isEmpty() && proxyPort > 0 && proxyPort <= 65535;
        task.proxyType = proxyType;
        task.proxyHost = proxyServer;
        task.proxyPort = proxyPort;
        String cleanName = FileUtils.sanitize(fileName);
        task.customName = !cleanName.isEmpty();
        task.fileName = cleanName.isEmpty() ? HttpProbe.guessFileName(cleanUrl) : cleanName;
        task.mime = mime != null && !mime.isEmpty() ? mime : FileUtils.mimeOf(task.fileName);
        task.dirUri = settings.dirUri();
        task.threads = settings.threads();
        task.state = TaskState.QUEUED;
        task.created = System.currentTimeMillis();
        synchronized (tasks) {
            store.insert(task);
            tasks.put(task.id, task);
        }
        notifier.update(task, true);
        notifyChanged();
        main.post(this::schedule);
        return task;
    }

    public void pause(long id) {
        DownloadTask task = find(id);
        if (task == null) {
            return;
        }
        int state = task.state;
        if (state == TaskState.PAUSED || state == TaskState.DONE || state == TaskState.FAILED) {
            return;
        }
        task.state = TaskState.PAUSED;
        task.speed = 0;
        task.retryAt = 0;
        pendingResume.remove(id);
        store.updateProgress(task);
        notifier.update(task, true);
        notifyChanged();
        TaskWorker worker = workerOf(id);
        if (worker != null) {
            worker.requestStop();
        }
    }

    public void resume(long id) {
        DownloadTask task = find(id);
        if (task == null) {
            return;
        }
        if (task.state == TaskState.DONE) {
            return;
        }
        if (workerOf(id) != null) {
            // A pause is still winding down; remember the tap and apply it when the worker exits.
            if (task.state == TaskState.PAUSED) {
                pendingResume.add(id);
            }
            return;
        }
        pendingResume.remove(id);
        task.state = TaskState.QUEUED;
        task.error = null;
        task.speed = 0;
        task.retries = 0;
        task.retryAt = 0;
        store.updateProgress(task);
        notifier.update(task, true);
        notifyChanged();
        main.post(this::schedule);
    }

    public void pauseAll() {
        for (DownloadTask task : tasks()) {
            if (task.state == TaskState.FAILED) {
                if (task.retryAt > 0) {
                    // A failed task can be waiting for its automatic retry. "Pause all"
                    // must stop that wait too, or the download would restart by itself.
                    task.state = TaskState.PAUSED;
                    task.retryAt = 0;
                    task.speed = 0;
                    store.updateProgress(task);
                    notifier.update(task, true);
                }
            } else if (task.state == TaskState.QUEUED || TaskState.isRunning(task.state)) {
                pause(task.id);
            }
        }
        notifyChanged();
    }

    public void resumeAll() {
        for (DownloadTask task : tasks()) {
            if (task.state == TaskState.PAUSED) {
                resume(task.id);
            }
        }
    }

    /** Stops the task and deletes both the record and any downloaded bytes. */
    public void cancel(long id) {
        DownloadTask task = find(id);
        if (task == null) {
            return;
        }
        TaskWorker worker = workerOf(id);
        task.retryAt = 0;
        if (worker != null) {
            task.state = TaskState.PAUSED;
            task.speed = 0;
            notifier.update(task, true);
            // Hide the row now; the worker removes the files off the UI thread.
            beginDelete(id);
            worker.requestCancel();
            return;
        }
        deleteTask(task);
    }

    public void removeRecord(long id, boolean deleteFile) {
        DownloadTask task = find(id);
        if (task == null) {
            return;
        }
        TaskWorker worker = workerOf(id);
        if (worker != null) {
            return;
        }
        if (deleteFile) {
            // Same background path as cancel(): file IO must not run on the UI thread.
            deleteTask(task);
            return;
        }
        synchronized (tasks) {
            tasks.remove(id);
            pendingResume.remove(id);
            store.delete(id);
        }
        notifier.cancel(id);
        notifyChanged();
        updateService();
    }

    /** Deletes the finished file and queues the same record again; no duplicate entry. */
    public void restart(long id) {
        DownloadTask task = find(id);
        if (task == null || task.state != TaskState.DONE || workerOf(id) != null) {
            return;
        }
        if (!beginDelete(id)) {
            return;
        }
        ioExecutor.execute(() -> {
            final boolean clean = deleteFiles(task);
            main.post(() -> {
                endDelete(id);
                clearPending(id);
                if (!clean) {
                    // Do not start a second copy on top of the file we could not remove.
                    Toast.makeText(context, R.string.file_delete_failed, Toast.LENGTH_LONG).show();
                    notifyChanged();
                    return;
                }
                resetForRedownload(task);
            });
        });
    }

    private void resetForRedownload(DownloadTask task) {
        task.fileUri = null;
        task.downloaded = 0;
        task.total = -1;
        task.speed = 0;
        task.error = null;
        task.completed = 0;
        task.resumable = true;
        task.etag = null;
        task.lastModified = null;
        task.segments.clear();
        task.state = TaskState.QUEUED;
        task.retries = 0;
        task.retryAt = 0;
        store.update(task);
        store.saveSegments(task);
        notifier.update(task, true);
        notifyChanged();
        updateService();
        main.post(this::schedule);
    }

    /**
     * Re-points an unfinished task at another SAF folder and starts it again. Cached segment
     * files are keyed by task id, so a failed merge can be retried without downloading again.
     *
     * @return true when the task was downloading in place and its progress had to be
     *         abandoned, so the transfer starts from zero in the new folder.
     */
    public boolean changeFolder(long id, String dirUri) {
        DownloadTask task = find(id);
        if (task == null || dirUri == null || workerOf(id) != null || task.state == TaskState.DONE) {
            return false;
        }
        String abandoned = task.fileUri;
        task.dirUri = dirUri;
        task.fileUri = null;
        boolean restarted = false;
        if (abandoned != null) {
            // In-place (preallocated) tasks keep their bytes in the old folder's document.
            // It cannot move to the new tree, so drop it; a new zero-filled document must
            // never be resumed from the old checkpoints.
            ioExecutor.execute(() -> FileUtils.deleteUriQuietly(context, abandoned));
            if (!hasCachedParts(task.id)) {
                task.segments.clear();
                task.downloaded = 0;
                task.retries = 0;
                task.retryAt = 0;
                store.saveSegments(task);
                restarted = true;
            }
        }
        store.update(task);
        notifier.update(task, true);
        notifyChanged();
        resume(id);
        return restarted;
    }

    /** True when the SAF segment cache still holds bytes for this task. */
    private boolean hasCachedParts(long id) {
        File[] parts = FileUtils.safPartsDir(context, id).listFiles();
        if (parts == null) {
            return false;
        }
        for (File part : parts) {
            if (part.isFile() && part.length() > 0) {
                return true;
            }
        }
        return false;
    }

    public void clearCompleted() {
        for (DownloadTask task : tasks()) {
            if (task.state == TaskState.DONE) {
                removeRecord(task.id, false);
            }
        }
    }

    public int queuedCount() {
        int count = 0;
        synchronized (tasks) {
            for (DownloadTask task : tasks.values()) {
                if (task.state == TaskState.QUEUED) {
                    count++;
                }
            }
        }
        return count;
    }

    public long totalSpeed() {
        long speed = 0;
        for (TaskWorker worker : running.values()) {
            speed += worker.task().speed;
        }
        return speed;
    }

    public boolean hasActiveWork() {
        return !running.isEmpty();
    }

    public Notification idleNotification() {
        return notifier.idleForeground();
    }

    /** Called by the service after startForeground so the summary is refreshed immediately. */
    public void onServiceReady() {
        main.post(() -> updateSummary(true));
    }

    /** The service was stopped by the system; keep the engine usable and drop its summary. */
    public void onServiceDestroyed() {
        serviceActive = false;
        notifier.cancelForeground();
    }

    // ---- worker callbacks ----

    void notifyState(DownloadTask task) {
        store.update(task);
        store.updateProgress(task);
        store.saveSegments(task);
        notifier.update(task, true);
        postChanged();
        main.post(() -> {
            updateSummary(true);
            updateService();
        });
    }

    void notifyProgress(DownloadTask task) {
        // Progress is rendered by the visible screen's own one-second tick; only the
        // notification shade is refreshed here. State changes still call postChanged().
        main.post(() -> updateSummary(false));
        notifier.update(task, false);
    }

    void workerFinished(TaskWorker worker) {
        DownloadTask task = worker.task();
        synchronized (tasks) {
            running.remove(task.id);
        }
        if (pendingResume.remove(task.id)) {
            // The user asked to resume while this worker was stopping: honour it now.
            task.state = TaskState.QUEUED;
            task.error = null;
            task.speed = 0;
            task.retries = 0;
            task.retryAt = 0;
            store.updateProgress(task);
        }
        notifier.update(task, true);
        postChanged();
        scheduleRetry(task);
        main.post(() -> {
            updateService();
            // A failed task may be waiting for its retry timer, but its slot is free now.
            schedule();
        });
    }

    /**
     * Arms an automatic retry for a failed task. Returns the delay in seconds when a retry was
     * queued, 0 when the task waits for connectivity, or -1 when nothing was scheduled.
     */
    private int scheduleRetry(DownloadTask task) {
        if (task.state == TaskState.DONE) {
            task.retries = 0;
            task.retryAt = 0;
            store.updateProgress(task);
            return -1;
        }
        if (task.state != TaskState.FAILED) {
            return -1;
        }
        int limit = settings.retryCount();
        if (limit <= 0 || task.retries >= limit) {
            task.retryAt = 0;
            postChanged();
            return -1;
        }
        if (!Net.isOnline(context)) {
            // Offline: hold the task instead of spending an attempt on a dead network.
            task.state = TaskState.QUEUED;
            task.error = null;
            task.retryAt = 0;
            store.updateProgress(task);
            notifier.update(task, true);
            postChanged();
            return 0;
        }
        int seconds = settings.retryInterval();
        task.retryAt = System.currentTimeMillis() + seconds * 1000L;
        postChanged();
        final long id = task.id;
        main.postDelayed(() -> runRetry(id), seconds * 1000L);
        return seconds;
    }

    private void runRetry(long id) {
        DownloadTask task = find(id);
        if (task == null || task.state != TaskState.FAILED || workerOf(id) != null) {
            // Paused, deleted or resumed by the user in the meantime.
            return;
        }
        task.retries++;
        task.retryAt = 0;
        task.error = null;
        task.state = TaskState.QUEUED;
        store.updateProgress(task);
        notifier.update(task, true);
        notifyChanged();
        schedule();
    }

    void workerCancelled(DownloadTask task) {
        synchronized (tasks) {
            running.remove(task.id);
            tasks.remove(task.id);
            pendingResume.remove(task.id);
            store.delete(task.id);
        }
        endDelete(task.id);
        clearPending(task.id);
        notifier.cancel(task.id);
        postChanged();
        main.post(() -> {
            updateService();
            schedule();
        });
        // The row is already gone: remove the partial files off the UI/worker thread.
        ioExecutor.execute(() -> FileUtils.deleteTaskFiles(context, task));
    }

    // ---- scheduling ----

    private synchronized void schedule() {
        if (running.size() >= settings.concurrent()) {
            return;
        }
        boolean wifiOnly = settings.wifiOnly();
        if (!Net.isOnline(context) || (wifiOnly && !Net.isWifi(context))) {
            notifyChanged();
            return;
        }
        int limit = settings.concurrent();
        while (running.size() < limit) {
            DownloadTask next = nextQueued();
            if (next == null) {
                break;
            }
            startWorker(next);
        }
    }

    private DownloadTask nextQueued() {
        DownloadTask pick = null;
        synchronized (tasks) {
            for (DownloadTask task : tasks.values()) {
                if (task.state != TaskState.QUEUED || running.containsKey(task.id)) {
                    continue;
                }
                if (pick == null || task.created < pick.created) {
                    pick = task;
                }
            }
        }
        return pick;
    }

    private void startWorker(DownloadTask task) {
        TaskWorker worker = new TaskWorker(this, context, store, settings, task);
        running.put(task.id, worker);
        acquireWakeLock();
        try {
            workerExecutor.execute(worker);
        } catch (RuntimeException e) {
            running.remove(task.id);
            releaseWakeLockIfIdle();
            throw e;
        }
        updateService();
    }

    private TaskWorker workerOf(long id) {
        synchronized (tasks) {
            return running.get(id);
        }
    }

    private void deleteTask(DownloadTask task) {
        if (!beginDelete(task.id)) {
            return;
        }
        ioExecutor.execute(() -> {
            final boolean clean = deleteFiles(task);
            main.post(() -> {
                endDelete(task.id);
                if (task.state == TaskState.DONE && !clean) {
                    // Keep the record: otherwise the file stays on disk with no way to find it.
                    clearPending(task.id);
                    Toast.makeText(context, R.string.file_delete_failed, Toast.LENGTH_LONG).show();
                    notifyChanged();
                    return;
                }
                synchronized (tasks) {
                    tasks.remove(task.id);
                    pendingResume.remove(task.id);
                    store.delete(task.id);
                }
                clearPending(task.id);
                notifier.cancel(task.id);
                notifyChanged();
                updateService();
                schedule();
            });
        });
    }

    private boolean deleteFiles(DownloadTask task) {
        try {
            return FileUtils.deleteTaskFiles(context, task);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean beginDelete(long id) {
        synchronized (deleting) {
            if (!deleting.add(id)) {
                return false;
            }
        }
        synchronized (pendingDelete) {
            pendingDelete.add(id);
        }
        // Let the list drop the row right away instead of waiting for slow storage.
        notifyChanged();
        return true;
    }

    private void clearPending(long id) {
        synchronized (pendingDelete) {
            pendingDelete.remove(id);
        }
    }

    private void endDelete(long id) {
        synchronized (deleting) {
            deleting.remove(id);
        }
    }

    // ---- foreground service and wake lock ----

    private void updateService() {
        boolean active = !running.isEmpty();
        if (active && !serviceActive) {
            serviceActive = true;
            Intent intent = new Intent(context, DownloadService.class);
            try {
                ContextCompat.startForegroundService(context, intent);
            } catch (RuntimeException e) {
                try {
                    context.startService(intent);
                } catch (RuntimeException ignored) {
                    serviceActive = false;
                }
            }
        } else if (!active && serviceActive) {
            serviceActive = false;
            try {
                context.stopService(new Intent(context, DownloadService.class));
            } catch (RuntimeException ignored) {
            }
            notifier.cancelForeground();
        }
        if (active) {
            updateSummary(true);
        }
        releaseWakeLockIfIdle();
    }

    private void updateSummary(boolean force) {
        if (!serviceActive) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (!force && now - lastSummary < 900) {
            return;
        }
        lastSummary = now;
        List<DownloadTask> active = new ArrayList<>();
        for (TaskWorker worker : running.values()) {
            active.add(worker.task());
        }
        notifier.notifyForeground(notifier.foreground(active, totalSpeed(), queuedCount()));
    }

    private void acquireWakeLock() {
        if (wakeLock == null) {
            PowerManager power = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "edm:downloads");
            wakeLock.setReferenceCounted(false);
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void releaseWakeLockIfIdle() {
        if (wakeLock != null && wakeLock.isHeld() && running.isEmpty()) {
            wakeLock.release();
        }
    }

    // ---- UI notifications ----

    private void notifyChanged() {
        postChanged();
    }

    private void postChanged() {
        main.post(() -> {
            Listener current = listener;
            if (current != null) {
                current.onDownloadsChanged();
            }
        });
    }

    private void registerNetworkCallback() {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            return;
        }
        try {
            manager.registerDefaultNetworkCallback(new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    main.post(DownloadEngine.this::schedule);
                }

                @Override
                public void onLost(Network network) {
                    main.post(() -> {
                        if (settings.wifiOnly() && !Net.isWifi(context)) {
                            pauseActive();
                        }
                    });
                }

                @Override
                public void onCapabilitiesChanged(Network network,
                                                  android.net.NetworkCapabilities capabilities) {
                    boolean wifi = capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
                    if (!wifi && settings.wifiOnly()) {
                        main.post(DownloadEngine.this::pauseActive);
                    } else {
                        main.post(DownloadEngine.this::schedule);
                    }
                }
            });
        } catch (RuntimeException ignored) {
            // Network callbacks unavailable on some devices.
        }
    }

    private void pauseActive() {
        for (DownloadTask task : tasks()) {
            if (TaskState.isRunning(task.state) || task.state == TaskState.CONNECTING) {
                pause(task.id);
            }
        }
    }

    private static final class WorkerFactory implements ThreadFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "edm-task-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}