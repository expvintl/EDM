package com.edm.downloader.util;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.webkit.MimeTypeMap;

import androidx.core.content.FileProvider;

import com.edm.downloader.data.DownloadTask;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** File naming, destination and intent helpers. */
public final class FileUtils {

    public static final String AUTHORITY_SUFFIX = ".fileprovider";

    private static final Set<String> DOWNLOAD_EXTENSIONS = new HashSet<>(Arrays.asList(
            "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "dmg", "cab", "msi",
            "apk", "xapk", "apks", "exe", "deb", "rpm", "jar",
            "mp4", "mkv", "avi", "mov", "webm", "flv", "wmv", "m4v", "ts", "3gp",
            "mp3", "flac", "wav", "m4a", "aac", "ogg", "opus", "wma",
            "pdf", "epub", "mobi", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "bin", "img", "torrent", "dat", "pak", "obb", "zipx", "lz", "zst"
    ));

    private FileUtils() {
    }

    public static File defaultDir(Context context) {
        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            dir = new File(context.getFilesDir(), "Download");
        }
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        return dir;
    }

    public static File fileOf(Context context, DownloadTask task) {
        if (task.dirUri != null || task.fileName == null || task.fileName.isEmpty()) {
            return null;
        }
        return new File(defaultDir(context), task.fileName);
    }

    public static File partFileOf(Context context, DownloadTask task) {
        File file = fileOf(context, task);
        return file == null ? null : new File(file.getParentFile(), file.getName() + ".edm-part");
    }

    public static String sanitize(String name) {
        if (name == null || name.trim().isEmpty()) {
            return "";
        }
        String cleaned = name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_").trim();
        cleaned = cleaned.replaceAll("^[. ]+", "").replaceAll("[. ]+$", "");
        if (cleaned.length() > 120) {
            int dot = cleaned.lastIndexOf('.');
            String ext = dot > 0 && cleaned.length() - dot <= 8 ? cleaned.substring(dot) : "";
            cleaned = cleaned.substring(0, Math.min(110, cleaned.length() - ext.length())) + ext;
        }
        return cleaned;
    }

    public static String withMimeExtension(String name, String mime) {
        if (name == null || name.isEmpty() || !name.contains(".")) {
            String ext = extensionForMime(mime);
            if (ext != null && !name.isEmpty()) {
                return name + "." + ext;
            }
        }
        return name;
    }

    public static String extensionForMime(String mime) {
        if (mime == null) {
            return null;
        }
        int semicolon = mime.indexOf(';');
        String clean = semicolon >= 0 ? mime.substring(0, semicolon) : mime;
        return MimeTypeMap.getSingleton().getExtensionFromMimeType(clean.trim());
    }

    public static String mimeOf(String name) {
        if (name != null) {
            int dot = name.lastIndexOf('.');
            if (dot >= 0 && dot < name.length() - 1) {
                String ext = name.substring(dot + 1).toLowerCase(Locale.US);
                String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                if (mime != null) {
                    return mime;
                }
            }
        }
        return "application/octet-stream";
    }

    public static String fileNameFromUrl(String url) {
        try {
            URL parsed = new URL(url);
            String path = parsed.getPath();
            if (path == null || path.isEmpty() || path.endsWith("/")) {
                return "";
            }
            int slash = path.lastIndexOf('/');
            String name = slash >= 0 ? path.substring(slash + 1) : path;
            return sanitize(URLDecoder.decode(name, "UTF-8"));
        } catch (Exception ignored) {
            return "";
        }
    }

    public static String splitBase(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    public static String splitExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot) : "";
    }

    public static File uniqueFile(File dir, String name) {
        File file = new File(dir, name);
        if (!file.exists()) {
            return file;
        }
        String base = splitBase(name);
        String ext = splitExtension(name);
        for (int i = 1; i < 1000; i++) {
            File candidate = new File(dir, base + " (" + i + ")" + ext);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(dir, base + "-" + System.currentTimeMillis() + ext);
    }

    public static String host(String url) {
        try {
            return new URL(url).getHost();
        } catch (Exception ignored) {
            return url;
        }
    }

    public static boolean isHttp(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase(Locale.US);
        return lower.startsWith("http://") || lower.startsWith("https://");
    }

    public static boolean isDownloadUrl(String url) {
        if (!isHttp(url)) {
            return false;
        }
        try {
            String path = new URL(url).getPath();
            if (path == null) {
                return false;
            }
            int dot = path.lastIndexOf('.');
            if (dot < 0 || dot == path.length() - 1) {
                return false;
            }
            String ext = path.substring(dot + 1).toLowerCase(Locale.US);
            return DOWNLOAD_EXTENSIONS.contains(ext);
        } catch (Exception ignored) {
            return false;
        }
    }

    public static String folderLabel(Context context, String dirUri) {
        if (dirUri == null) {
            return defaultDir(context).getAbsolutePath();
        }
        try {
            String treeId = DocumentsContract.getTreeDocumentId(Uri.parse(dirUri));
            return Uri.decode(treeId);
        } catch (Exception ignored) {
            return Uri.decode(Uri.parse(dirUri).getLastPathSegment());
        }
    }

    /** Starting point for ACTION_OPEN_DOCUMENT_TREE: internal storage / Download. */
    public static Uri initialTreeUri() {
        try {
            return DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents", "primary:Download");
        } catch (Exception e) {
            return null;
        }
    }

    public static Uri fileProviderUri(Context context, File file) {
        return FileProvider.getUriForFile(context, context.getPackageName() + AUTHORITY_SUFFIX, file);
    }

    public static Uri contentUri(Context context, DownloadTask task) {
        if (task.fileUri != null) {
            return Uri.parse(task.fileUri);
        }
        File file = fileOf(context, task);
        return file != null && file.exists() ? fileProviderUri(context, file) : null;
    }

    public static boolean exists(Context context, DownloadTask task) {
        if (task.fileUri != null) {
            try {
                ContentResolver resolver = context.getContentResolver();
                try (android.content.res.AssetFileDescriptor fd = resolver.openAssetFileDescriptor(Uri.parse(task.fileUri), "r")) {
                    return fd != null;
                }
            } catch (Exception ignored) {
                return false;
            }
        }
        File file = fileOf(context, task);
        return file != null && file.exists();
    }

    public static Intent openIntent(Context context, DownloadTask task) {
        Uri uri = contentUri(context, task);
        if (uri == null) {
            return null;
        }
        String mime = task.mime != null && !task.mime.isEmpty() ? task.mime : mimeOf(task.fileName);
        if (task.fileName != null && task.fileName.toLowerCase(Locale.US).endsWith(".apk")) {
            // Some servers send octet-stream for APKs; the package installer needs the real type.
            mime = "application/vnd.android.package-archive";
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

    public static Intent shareIntent(Context context, DownloadTask task) {
        Uri uri = contentUri(context, task);
        if (uri == null) {
            return null;
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType(task.mime != null && !task.mime.isEmpty() ? task.mime : mimeOf(task.fileName));
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    /**
     * Deletes every copy of a task's payload: the SAF document, the file inside the granted
     * tree, the copy in the app folder and the cached segment parts.
     *
     * @return false when a file was found but could not be deleted, so callers can keep the
     *         record and let the user retry instead of leaving an orphan behind.
     */
    public static boolean deleteTaskFiles(Context context, DownloadTask task) {
        boolean failed = false;
        boolean removed = false;
        if (task.fileUri != null) {
            removed = deleteDocument(context, Uri.parse(task.fileUri));
        }
        String name = task.fileName;
        if (!removed && task.dirUri != null && name != null && !name.isEmpty()) {
            // Listing the whole tree is slow on big folders, so it only runs as a fallback
            // when the stored document URI could not be deleted directly.
            Uri tree = Uri.parse(task.dirUri);
            Uri child = findChildByName(context, tree, name);
            if (child != null) {
                if (deleteDocument(context, child)) {
                    removed = true;
                } else {
                    failed = true;
                }
            }
        }
        if (name != null && !name.isEmpty()) {
            // A completed file can also sit in the app folder: older builds fell back there
            // when the SAF folder refused the write, and the record kept pointing at the tree.
            File file = new File(defaultDir(context), name);
            if (file.exists() && !file.delete()) {
                failed = true;
            }
            //noinspection ResultOfMethodCallIgnored
            new File(file.getParentFile(), file.getName() + ".edm-part").delete();
        }
        deleteTree(safPartsDir(context, task.id));
        return !failed;
    }

    /**
     * Document URIs handed back by {@code DocumentsContract.createDocument} live under
     * {@code /document/...}, which some providers do not accept for a plain
     * {@code ContentResolver.delete()} even though the tree grant covers them. Calling the
     * provider method directly works for every shape of the URI.
     */
    private static boolean deleteDocument(Context context, Uri uri) {
        try {
            if (DocumentsContract.deleteDocument(context.getContentResolver(), uri)) {
                return true;
            }
        } catch (Exception ignored) {
        }
        try {
            return context.getContentResolver().delete(uri, null, null) > 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * Best-effort removal of one stored document URI. Used when a task abandons an
     * in-place download (folder change, failed reservation) and the file must not be
     * left behind; never throws.
     */
    public static void deleteUriQuietly(Context context, String uri) {
        if (uri == null || uri.isEmpty()) {
            return;
        }
        try {
            deleteDocument(context, Uri.parse(uri));
        } catch (Exception ignored) {
        }
    }

    /** Finds a file created by EDM inside a granted tree, matching by display name. */
    private static Uri findChildByName(Context context, Uri tree, String name) {
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree,
                DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cursor = context.getContentResolver().query(children,
                new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (cursor == null) {
                return null;
            }
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(1))) {
                    return DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Directory holding the per-segment cache of a download into a SAF folder. */
    public static File safPartsDir(Context context, long taskId) {
        return new File(context.getCacheDir(), "saf-parts/" + taskId);
    }

    public static void deleteTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}