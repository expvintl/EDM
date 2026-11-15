package com.edm.downloader.data;

/** Download state constants shared by persistence, engine and UI. */
public final class TaskState {

    public static final int QUEUED = 0;
    public static final int CONNECTING = 1;
    public static final int DOWNLOADING = 2;
    public static final int PAUSED = 3;
    public static final int DONE = 4;
    public static final int FAILED = 5;

    private TaskState() {
    }

    public static boolean isRunning(int state) {
        return state == CONNECTING || state == DOWNLOADING;
    }

    public static boolean isFinished(int state) {
        return state == DONE;
    }

    public static boolean canPause(int state) {
        return state == QUEUED || isRunning(state);
    }

    public static boolean canResume(int state) {
        return state == PAUSED || state == FAILED || state == QUEUED;
    }
}