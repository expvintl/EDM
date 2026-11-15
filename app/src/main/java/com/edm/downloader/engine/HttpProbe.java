package com.edm.downloader.engine;

import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.util.FileUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves metadata (length, range support, name, type) before downloading. */
public final class HttpProbe {

    private static final Pattern FILENAME_STAR =
            Pattern.compile("filename\\*\\s*=\\s*([^;]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern FILENAME =
            Pattern.compile("filename\\s*=\\s*(\"[^\"]*\"|[^;]+)", Pattern.CASE_INSENSITIVE);

    /** Link checks must fail fast; a walled host otherwise blocks for a minute. */
    private static final int PROBE_CONNECT_TIMEOUT_MS = 8000;
    private static final int PROBE_READ_TIMEOUT_MS = 12000;

    /** The connection a probe is currently blocked on, so the UI can abort it. */
    private static final AtomicReference<HttpURLConnection> ACTIVE = new AtomicReference<>();

    static class Result {
        String finalUrl;
        long total = -1;
        boolean ranges;
        String fileName;
        String mime;
        String etag;
        String lastModified;
    }

    private HttpProbe() {
    }

    /** Snapshot of what a link advertises, used by the add-download sheet. */
    public static final class Info {
        public final String finalUrl;
        public final long total;
        public final boolean ranges;
        public final String fileName;
        public final String mime;

        Info(Result result) {
            finalUrl = result.finalUrl;
            total = result.total;
            ranges = result.ranges;
            fileName = result.fileName;
            mime = result.mime;
        }
    }

    /** Aborts the in-flight probe; its thread unwinds with an IOException shortly after. */
    public static void cancel() {
        HttpURLConnection connection = ACTIVE.getAndSet(null);
        if (connection != null) {
            try {
                connection.disconnect();
            } catch (RuntimeException ignored) {
                // Best effort: disconnecting an unconnected socket can throw.
            }
        }
    }

    /** Probes a link with the same UA, headers and proxy the real download would use. */
    public static Info inspect(DownloadTask task) throws IOException {
        return new Info(probe(task));
    }

    /** Resolves the User-Agent for a UA mode, for probe requests from the UI. */
    public static String userAgent(int mode, String custom) {
        return Http.resolveUa(mode, custom);
    }

    static Result probe(DownloadTask task) throws IOException {
        IOException headFailure = null;
        try {
            Result result = attempt(task, "HEAD", true);
            if (result != null) {
                return result;
            }
        } catch (IOException e) {
            headFailure = e;
        }
        // GET is what a browser would do, so its error is the honest one to report.
        Result result = attempt(task, "GET", false);
        if (result != null) {
            return result;
        }
        throw headFailure != null ? headFailure : new IOException("HTTP request failed");
    }

    /** Returns null when the response cannot be trusted and a GET range probe is required. */
    private static Result attempt(DownloadTask task, String method, boolean allowRangeTest) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = Http.open(task.url, task, "bytes=0-0", method,
                    PROBE_CONNECT_TIMEOUT_MS, PROBE_READ_TIMEOUT_MS);
            ACTIVE.set(connection);
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                String message = connection.getResponseMessage();
                throw new IOException("HTTP " + code
                        + (message == null || message.isEmpty() ? "" : " " + message));
            }
            String contentRange = connection.getHeaderField("Content-Range");
            long rangeTotal = Http.parseContentRangeTotal(contentRange);
            long length = connection.getContentLengthLong();
            String acceptRanges = connection.getHeaderField("Accept-Ranges");
            boolean ranges = code == HttpURLConnection.HTTP_PARTIAL
                    || (acceptRanges != null && acceptRanges.toLowerCase(Locale.US).contains("bytes"));

            Result result = new Result();
            result.finalUrl = connection.getURL().toString();
            result.mime = mimeOf(connection.getHeaderField("Content-Type"));
            result.etag = connection.getHeaderField("ETag");
            result.lastModified = connection.getHeaderField("Last-Modified");
            result.fileName = parseFileName(connection.getHeaderField("Content-Disposition"));

            if (code == HttpURLConnection.HTTP_PARTIAL && rangeTotal > 0) {
                result.total = rangeTotal;
                result.ranges = true;
            } else if (length > 0) {
                result.total = length;
                result.ranges = ranges;
            } else {
                result.total = -1;
                result.ranges = ranges;
            }

            if (result.fileName == null || result.fileName.isEmpty()) {
                String fromFinal = FileUtils.fileNameFromUrl(result.finalUrl);
                result.fileName = fromFinal.isEmpty() ? FileUtils.fileNameFromUrl(task.url) : fromFinal;
            }
            if (result.fileName == null || result.fileName.isEmpty()) {
                result.fileName = "download";
            }
            result.fileName = FileUtils.sanitize(FileUtils.withMimeExtension(result.fileName, result.mime));

            if (allowRangeTest && !result.ranges && result.total > 0) {
                // Accept-Ranges is often omitted; verify with a ranged GET before splitting.
                return null;
            }
            return result;
        } finally {
            ACTIVE.compareAndSet(connection, null);
            Http.closeQuietly(connection);
        }

    }

    /** Consumes no body; returns the final HTTP status for diagnostics. */
    static int status(String url, DownloadTask task) throws IOException {
        HttpURLConnection connection = null;
        try {
            connection = Http.open(url, task, "bytes=0-0");
            return connection.getResponseCode();
        } finally {
            Http.closeQuietly(connection);
        }
    }

    public static String parseFileName(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isEmpty()) {
            return null;
        }
        Matcher star = FILENAME_STAR.matcher(contentDisposition);
        if (star.find()) {
            String raw = star.group(1).trim();
            if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            String[] parts = raw.split("'", 3);
            String charset = parts.length == 3 && !parts[0].isEmpty() ? parts[0] : "UTF-8";
            String encoded = parts.length == 3 ? parts[2] : raw;
            try {
                return URLDecoder.decode(encoded, charset);
            } catch (Exception ignored) {
                // fall through to the plain filename parameter
            }
        }
        Matcher plain = FILENAME.matcher(contentDisposition);
        if (plain.find()) {
            String raw = plain.group(1).trim();
            if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) {
                raw = raw.substring(1, raw.length() - 1);
            }
            return raw;
        }
        return null;
    }

    public static String mimeOf(String contentType) {
        if (contentType == null || contentType.isEmpty()) {
            return null;
        }
        int semicolon = contentType.indexOf(';');
        String mime = (semicolon >= 0 ? contentType.substring(0, semicolon) : contentType).trim();
        return mime.isEmpty() ? null : mime;
    }

    /** Best-effort name for a URL, used by the add-download sheet before probing. */
    public static String guessFileName(String url) {
        String name = FileUtils.fileNameFromUrl(url);
        if (name.isEmpty()) {
            try {
                name = FileUtils.sanitize(new URL(url).getHost());
            } catch (Exception ignored) {
                name = "download";
            }
        }
        return name;
    }

    static long contentLength(HttpURLConnection connection) {
        return connection.getContentLengthLong();
    }

    static InputStream stream(HttpURLConnection connection) throws IOException {
        return connection.getInputStream();
    }
}