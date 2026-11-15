package com.edm.downloader.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Human readable formatting helpers shared by the UI and notifications. */
public final class Fmt {

    private static final String[] UNITS = {"KB", "MB", "GB", "TB"};

    private Fmt() {
    }

    public static String bytes(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = -1;
        do {
            value /= 1024d;
            unit++;
        } while (value >= 1024d && unit < UNITS.length - 1);
        String pattern = value >= 100 ? "%.0f" : value >= 10 ? "%.1f" : "%.2f";
        return String.format(Locale.US, pattern, value) + " " + UNITS[unit];
    }

    public static String speed(long bytesPerSecond) {
        if (bytesPerSecond <= 0) {
            return "—";
        }
        if (bytesPerSecond < 1024) {
            return bytesPerSecond + " B/s";
        }
        double value = bytesPerSecond;
        int unit = -1;
        do {
            value /= 1024d;
            unit++;
        } while (value >= 1024d && unit < UNITS.length - 1);
        // One decimal keeps the readout steady while it updates every few hundred ms.
        String pattern = value >= 100 ? "%.0f" : "%.1f";
        return String.format(Locale.US, pattern, value) + " " + UNITS[unit] + "/s";
    }

    public static String eta(long remainingBytes, long bytesPerSecond) {
        if (remainingBytes < 0 || bytesPerSecond <= 0) {
            return "—";
        }
        long seconds = (remainingBytes + bytesPerSecond - 1) / bytesPerSecond;
        if (seconds <= 0) {
            return "0:00";
        }
        if (seconds > 99L * 3600L) {
            return "—";
        }
        // Round longer estimates to coarse steps so the readout stops flickering.
        long step;
        if (seconds <= 30) {
            step = 1;
        } else if (seconds <= 120) {
            step = 5;
        } else if (seconds <= 600) {
            step = 15;
        } else if (seconds <= 3600) {
            step = 30;
        } else {
            step = 60;
        }
        if (step > 1) {
            seconds = ((seconds + step - 1) / step) * step;
        }
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest);
        }
        return String.format(Locale.US, "%d:%02d", minutes, rest);
    }

    public static int percent(long done, long total) {
        if (total <= 0) {
            return 0;
        }
        long value = done * 100L / total;
        return (int) Math.max(0, Math.min(100, value));
    }

    public static String date(long millis) {
        if (millis <= 0) {
            return "—";
        }
        Date date = new Date(millis);
        Date now = new Date();
        boolean sameDay = android.text.format.DateUtils.isToday(millis);
        SimpleDateFormat format = sameDay
                ? new SimpleDateFormat("HH:mm", Locale.getDefault())
                : new SimpleDateFormat("MM-dd HH:mm", Locale.getDefault());
        return format.format(date);
    }

    public static String duration(long millis) {
        long seconds = millis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest);
        }
        return String.format(Locale.US, "%d:%02d", minutes, rest);
    }

    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}