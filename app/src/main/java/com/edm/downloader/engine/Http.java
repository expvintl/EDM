package com.edm.downloader.engine;

import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.Settings;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.Locale;

/** Builds HTTP connections with the browser-ish headers captured when the task was created. */
final class Http {

    static final String MOBILE_UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36";

    static final String DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    static final String DEFAULT_UA = MOBILE_UA;

    private static final int MAX_REDIRECTS = 6;

    private Http() {
    }

    /** Resolves the User-Agent for a UA mode; custom falls back to the mobile UA when blank. */
    static String resolveUa(int mode, String custom) {
        if (mode == Settings.UA_DESKTOP) {
            return DESKTOP_UA;
        }
        if (mode == Settings.UA_CUSTOM) {
            String value = custom == null ? "" : custom.trim();
            return value.isEmpty() ? MOBILE_UA : value;
        }
        return MOBILE_UA;
    }

    private static Proxy proxyOf(DownloadTask task) {
        if (task == null || !task.proxy) {
            return null;
        }
        String host = task.proxyHost == null ? "" : task.proxyHost.trim();
        if (host.isEmpty() || task.proxyPort <= 0 || task.proxyPort > 65535) {
            return null;
        }
        Proxy.Type type = task.proxyType == Settings.PROXY_SOCKS5
                ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
        return new Proxy(type, InetSocketAddress.createUnresolved(host, task.proxyPort));
    }

    static HttpURLConnection open(String url, DownloadTask task, String range) throws IOException {
        return open(url, task, range, "GET");
    }

    static HttpURLConnection open(String url, DownloadTask task, String range, String method) throws IOException {
        return open(url, task, range, method, 20000, 30000);
    }

    /** Same as {@link #open(String, DownloadTask, String, String)} with explicit socket timeouts. */
    static HttpURLConnection open(String url, DownloadTask task, String range, String method,
                                  int connectTimeout, int readTimeout) throws IOException {
        String current = url;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            URL target = new URL(current);
            Proxy proxy = proxyOf(task);
            HttpURLConnection connection = (HttpURLConnection) (proxy == null
                    ? target.openConnection() : target.openConnection(proxy));
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeout);
            connection.setReadTimeout(readTimeout);
            connection.setRequestProperty("User-Agent", task.userAgent != null ? task.userAgent : DEFAULT_UA);
            connection.setRequestProperty("Accept", "*/*");
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (range != null) {
                connection.setRequestProperty("Range", range);
            }
            if (task.cookie != null && !task.cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", task.cookie);
            }
            if (task.referer != null && !task.referer.isEmpty()) {
                connection.setRequestProperty("Referer", task.referer);
            }
            connection.connect();
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("Redirect without Location");
                }
                current = new URL(new URL(current), location).toString();
                continue;
            }
            return connection;
        }
        throw new IOException("Too many redirects");
    }

    static boolean isRangeResponse(HttpURLConnection connection) throws IOException {
        return connection.getResponseCode() == HttpURLConnection.HTTP_PARTIAL;
    }

    static long parseContentRangeStart(String contentRange) {
        long[] parsed = parseContentRange(contentRange);
        return parsed == null ? -1 : parsed[0];
    }

    static long parseContentRangeTotal(String contentRange) {
        long[] parsed = parseContentRange(contentRange);
        return parsed == null ? -1 : parsed[1];
    }

    /** Returns {start, total} from "bytes 0-1023/4096"; total -1 when unknown. */
    private static long[] parseContentRange(String value) {
        if (value == null) {
            return null;
        }
        String lower = value.trim().toLowerCase(Locale.US);
        if (!lower.startsWith("bytes")) {
            return null;
        }
        int space = value.indexOf(' ');
        if (space < 0) {
            return null;
        }
        String range = value.substring(space + 1).trim();
        int dash = range.indexOf('-');
        int slash = range.indexOf('/');
        if (dash < 0 || slash < 0 || slash < dash) {
            return null;
        }
        try {
            long start = Long.parseLong(range.substring(0, dash).trim());
            long total = -1;
            String totalText = range.substring(slash + 1).trim();
            if (!totalText.equals("*")) {
                total = Long.parseLong(totalText);
            }
            return new long[]{start, total};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    static void closeQuietly(HttpURLConnection connection) {
        if (connection == null) {
            return;
        }
        try {
            InputStream stream = connection.getErrorStream();
            if (stream != null) {
                stream.close();
            }
        } catch (Exception ignored) {
        }
        connection.disconnect();
    }
}