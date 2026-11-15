package com.edm.downloader.data;

/** One byte range of a multi-threaded download. End is inclusive; -1 means open ended. */
public class TaskSegment {

    public int index;
    public long start;
    public long end;
    public volatile long done;
    public volatile int state; // 0 idle, 1 running, 2 done, 3 failed

    public TaskSegment() {
    }

    public TaskSegment(int index, long start, long end) {
        this.index = index;
        this.start = start;
        this.end = end;
    }

    public long length() {
        return end >= start ? end - start + 1 : -1;
    }

    public boolean isComplete() {
        return state == 2 || (end >= start && done >= length());
    }

    public int percent() {
        long length = length();
        if (length <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, done * 100 / length));
    }
}