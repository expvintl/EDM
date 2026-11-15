package com.edm.downloader.engine;

import android.content.Context;

import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.Settings;
import com.edm.downloader.data.TaskSegment;
import com.edm.downloader.util.FileUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;

/** Writes into a single ".edm-part" file next to the final destination name. */
final class FileSink implements Sink {

    private static final int BUFFER = 64 * 1024;

    private final Context context;
    private final DownloadTask task;

    FileSink(Context context, DownloadTask task) {
        this.context = context.getApplicationContext();
        this.task = task;
    }

    @Override
    public void prepare(DownloadTask task) throws IOException {
        File dir = FileUtils.defaultDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create download folder");
        }
        File part = FileUtils.partFileOf(context, task);
        if (part == null) {
            throw new IOException("Invalid file name");
        }
        if (!part.exists()) {
            for (TaskSegment segment : task.segments) {
                segment.done = 0;
            }
            preallocate(part, task);
            return;
        }
        boolean fresh = true;
        for (TaskSegment segment : task.segments) {
            if (segment.done > 0) {
                fresh = false;
                break;
            }
        }
        if (fresh) {
            // Nothing was checkpointed; drop any stale bytes from an abandoned attempt.
            try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
                raf.setLength(0);
            }
            preallocate(part, task);
            return;
        }
        long length = part.length();
        for (TaskSegment segment : task.segments) {
            long available = Math.max(0, length - segment.start);
            long max = segment.length() > 0 ? Math.min(available, segment.length()) : available;
            if (segment.done > max) {
                segment.done = max;
            }
            if (segment.done < 0) {
                segment.done = 0;
            }
        }
    }

    /**
     * Pre-creates the file at its final size so the space is claimed up front and the
     * destination shows the full size while it is still being filled.
     */
    private void preallocate(File part, DownloadTask task) throws IOException {
        if (task.total <= 0 || !new Settings(context).preallocate()) {
            return;
        }
        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            if (raf.length() < task.total) {
                raf.setLength(task.total);
            }
        }
    }

    @Override
    public OutputStream openSegment(TaskSegment segment) throws IOException {
        File file = FileUtils.partFileOf(context, task);
        if (file == null) {
            throw new IOException("Invalid file name");
        }
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        try {
            raf.seek(segment.start + segment.done);
        } catch (IOException e) {
            raf.close();
            throw e;
        }
        return new BufferedOutputStream(new RafStream(raf), BUFFER);
    }

    @Override
    public void finish(DownloadTask task) throws IOException {
        File part = FileUtils.partFileOf(context, task);
        if (part == null) {
            throw new IOException("Invalid file name");
        }
        File dir = FileUtils.defaultDir(context);
        File target = new File(dir, task.fileName);
        if (target.exists()) {
            target = FileUtils.uniqueFile(dir, task.fileName);
        }
        if (part.exists()) {
            if (!part.renameTo(target)) {
                throw new IOException("Cannot rename temporary file");
            }
        } else if (!target.createNewFile()) {
            // Zero-byte result: still publish an empty file.
            throw new IOException("Cannot create file");
        }
        task.fileName = target.getName();
        task.fileUri = null;
    }

    @Override
    public void discard() {
        File part = FileUtils.partFileOf(context, task);
        if (part != null) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();
        }
    }

    private static final class RafStream extends OutputStream {

        private final RandomAccessFile raf;

        RafStream(RandomAccessFile raf) {
            this.raf = raf;
        }

        @Override
        public void write(int b) throws IOException {
            raf.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            raf.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }
}