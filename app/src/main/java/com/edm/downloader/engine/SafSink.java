package com.edm.downloader.engine;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;

import com.edm.downloader.R;
import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.Settings;
import com.edm.downloader.data.TaskSegment;
import com.edm.downloader.util.FileUtils;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.channels.FileChannel;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** Downloads into per-segment cache files, then merges them into the user's SAF folder. */
final class SafSink implements Sink {

    private static final int BUFFER = 64 * 1024;

    private final Context context;
    private final DownloadTask task;
    private final File partsDir;
    /** True when the destination document was created up front and is written in place. */
    private boolean direct;

    SafSink(Context context, DownloadTask task) {
        this.context = context.getApplicationContext();
        this.task = task;
        this.partsDir = FileUtils.safPartsDir(context, task.id);
    }

    private File partFile(TaskSegment segment) {
        return new File(partsDir, "s" + segment.index + ".part");
    }

    /** True when the cache already holds bytes that a new document could not contain. */
    private boolean hasCachedProgress() {
        File[] parts = partsDir.listFiles();
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

    @Override
    public void prepare(DownloadTask task) throws IOException {
        direct = false;
        boolean cachedProgress = hasCachedProgress();
        if (task.total > 0 && task.dirUri != null && new Settings(context).preallocate()) {
            if (task.fileUri != null && cachedProgress) {
                // Preallocation was switched off and on again: the cache holds newer
                // bytes than the document, so the document cannot be resumed from.
                FileUtils.deleteUriQuietly(context, task.fileUri);
                task.fileUri = null;
            } else if (!cachedProgress) {
                try {
                    openDirect();
                    direct = true;
                    FileUtils.deleteTree(partsDir);
                    return;
                } catch (Exception e) {
                    // The provider refused to reserve space: keep the safe cache + merge
                    // path. Nothing was written to the document in this attempt, and the
                    // cache starts empty, so drop it instead of leaving a zero-filled file.
                    direct = false;
                    FileUtils.deleteUriQuietly(context, task.fileUri);
                    task.fileUri = null;
                }
            }
        }
        if (!partsDir.exists() && !partsDir.mkdirs()) {
            throw new IOException("Cannot create temporary folder");
        }
        for (TaskSegment segment : task.segments) {
            File part = partFile(segment);
            long length = part.exists() ? part.length() : 0;
            if (segment.done > length) {
                segment.done = Math.max(0, length);
            }
            if (segment.done < 0) {
                segment.done = 0;
            }
        }
    }

    @Override
    public OutputStream openSegment(TaskSegment segment) throws IOException {
        if (direct) {
            return openDirectSegment(segment);
        }
        File part = partFile(segment);
        RandomAccessFile raf = new RandomAccessFile(part, "rw");
        try {
            if (raf.length() > segment.done) {
                // Trailing bytes were never checkpointed; they will be re-fetched.
                raf.setLength(segment.done);
            }
            raf.seek(segment.done);
        } catch (IOException e) {
            raf.close();
            throw e;
        }
        return new BufferedOutputStream(new RafStream(raf), BUFFER);
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

    @Override
    public void finish(DownloadTask task) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        if (direct) {
            long size = documentSize();
            if (size >= 0 && size < task.total) {
                throw new IOException("The file in the selected folder is incomplete ("
                        + size + " of " + task.total + " bytes)");
            }
            // The document was named when it was created; a later probe may have changed
            // task.fileName, so take the real display name for the list and details.
            String actualName = displayName(resolver, documentUri());
            if (actualName != null && !actualName.isEmpty()) {
                task.fileName = actualName;
            }
            FileUtils.deleteTree(partsDir);
            return;
        }
        Uri tree = Uri.parse(task.dirUri);
        String mime = task.mime != null ? task.mime : "application/octet-stream";
        String displayName = uniqueChildName(resolver, tree, task.fileName);

        // DocumentsContract.createDocument needs a *document* URI. Passing the bare tree
        // URI (content://.../tree/primary%3AEMD) makes DocumentsContract.getDocumentId()
        // inside the provider throw IllegalArgumentException("Invalid URI"), which is why
        // every SAF folder failed with the same message.
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));

        Uri document;
        try {
            document = DocumentsContract.createDocument(resolver, parent, mime, displayName);
        } catch (Exception e) {
            throw new IOException("Cannot create file in the selected folder ("
                    + describe(e) + ")", e);
        }
        if (document == null) {
            throw new IOException("Cannot create file in the selected folder"
                    + " (the provider returned no document)");
        }
        try {
            writeMerged(resolver, document);
        } catch (Exception e) {
            try {
                resolver.delete(document, null, null);
            } catch (Exception ignored) {
            }
            throw new IOException("Cannot write file in the selected folder ("
                    + describe(e) + ")", e);
        }
        task.fileUri = document.toString();
        String actualName = displayName(resolver, document);
        if (actualName != null && !actualName.isEmpty()) {
            task.fileName = actualName;
        }
        FileUtils.deleteTree(partsDir);
    }

    private void writeMerged(ContentResolver resolver, Uri document) throws IOException {
        try (OutputStream out = resolver.openOutputStream(document, "w")) {
            if (out == null) {
                throw new IOException("Cannot open document for writing");
            }
            BufferedOutputStream buffer = new BufferedOutputStream(out, BUFFER);
            for (TaskSegment segment : task.segments) {
                File part = partFile(segment);
                if (!part.exists()) {
                    continue;
                }
                try (InputStream in = new FileInputStream(part)) {
                    byte[] chunk = new byte[BUFFER];
                    int read;
                    while ((read = in.read(chunk)) > 0) {
                        buffer.write(chunk, 0, read);
                    }
                }
            }
            buffer.flush();
        }
    }

    private static String describe(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        String message = error.getMessage();
        return message == null || message.isEmpty()
                ? error.getClass().getSimpleName()
                : error.getClass().getSimpleName() + ": " + message;
    }

    private void openDirect() throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri tree = Uri.parse(task.dirUri);
        String mime = task.mime != null ? task.mime : "application/octet-stream";
        if (task.fileUri == null) {
            String displayName = uniqueChildName(resolver, tree, task.fileName);
            Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getTreeDocumentId(tree));
            Uri document = DocumentsContract.createDocument(resolver, parent, mime, displayName);
            if (document == null) {
                throw new IOException("Cannot create file in the selected folder");
            }
            // Store the tree-shaped document URI: plain /document/ URIs are not always
            // covered by the tree grant for the later open/delete calls.
            task.fileUri = DocumentsContract.buildDocumentUriUsingTree(tree,
                    DocumentsContract.getDocumentId(document)).toString();
            task.fileName = displayName;
            // A brand-new document holds no payload bytes. Any checkpointed progress
            // belongs to another location (typically the folder the task was moved from);
            // writing after it would leave a zero-filled hole, so restart from zero.
            long carried = 0;
            for (TaskSegment segment : task.segments) {
                carried += segment.done;
            }
            if (carried > 0) {
                for (TaskSegment segment : task.segments) {
                    segment.done = 0;
                }
                task.downloaded = 0;
            }
        }
        reserve(documentUri(), task.total);
    }

    private long documentSize() {
        try (ParcelFileDescriptor pfd = context.getContentResolver()
                .openFileDescriptor(documentUri(), "r")) {
            return pfd == null ? -1 : pfd.getStatSize();
        } catch (Exception e) {
            return -1;
        }
    }

    private Uri documentUri() {
        Uri uri = Uri.parse(task.fileUri);
        if (task.dirUri == null) {
            return uri;
        }
        try {
            return DocumentsContract.buildDocumentUriUsingTree(Uri.parse(task.dirUri),
                    DocumentsContract.getDocumentId(uri));
        } catch (Exception e) {
            return uri;
        }
    }

    /** Extends the document to its final size so it exists (and shows its size) up front. */
    private void reserve(Uri document, long size) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        try (ParcelFileDescriptor pfd = resolver.openFileDescriptor(document, "rw")) {
            if (pfd == null) {
                throw new IOException("Cannot open the target file");
            }
            try (FileOutputStream out = new FileOutputStream(pfd.getFileDescriptor())) {
                FileChannel channel = out.getChannel();
                if (channel.size() < size) {
                    channel.position(size - 1);
                    out.write(0);
                } else if (channel.size() > size) {
                    channel.truncate(size);
                }
            }
        }
    }

    private OutputStream openDirectSegment(TaskSegment segment) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri document = documentUri();
        ParcelFileDescriptor pfd = resolver.openFileDescriptor(document, "rw");
        if (pfd == null) {
            throw new IOException("Cannot open the target file");
        }
        FileOutputStream out;
        try {
            out = new FileOutputStream(pfd.getFileDescriptor());
            out.getChannel().position(segment.start + segment.done);
        } catch (IOException e) {
            try {
                pfd.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
        return new BufferedOutputStream(new DirectStream(out, pfd), BUFFER);
    }

    private static final class DirectStream extends OutputStream {

        private final FileOutputStream out;
        private final ParcelFileDescriptor pfd;

        DirectStream(FileOutputStream out, ParcelFileDescriptor pfd) {
            this.out = out;
            this.pfd = pfd;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
        }

        @Override
        public void close() throws IOException {
            try {
                out.close();
            } finally {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public void discard() {
        FileUtils.deleteTree(partsDir);
    }

    private String uniqueChildName(ContentResolver resolver, Uri tree, String fileName) {
        String name = FileUtils.sanitize(fileName);
        if (name.isEmpty()) {
            name = "download";
        }
        Set<String> used = new HashSet<>();
        String treeId = DocumentsContract.getTreeDocumentId(tree);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId);
        String[] projection = {DocumentsContract.Document.COLUMN_DISPLAY_NAME};
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String child = cursor.getString(0);
                    if (child != null) {
                        used.add(child.toLowerCase(Locale.US));
                    }
                }
            }
        } catch (Exception ignored) {
            // Some providers refuse child queries; creation may still succeed.
        }
        if (!used.contains(name.toLowerCase(Locale.US))) {
            return name;
        }
        String base = FileUtils.splitBase(name);
        String ext = FileUtils.splitExtension(name);
        for (int i = 1; i < 1000; i++) {
            String candidate = base + " (" + i + ")" + ext;
            if (!used.contains(candidate.toLowerCase(Locale.US))) {
                return candidate;
            }
        }
        return base + "-" + System.currentTimeMillis() + ext;
    }

    private static String displayName(ContentResolver resolver, Uri document) {
        try (Cursor cursor = resolver.query(document,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        } catch (Exception ignored) {
        }
        return null;
    }
}