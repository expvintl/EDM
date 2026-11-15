package com.edm.downloader.engine;

import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.TaskSegment;

import java.io.IOException;
import java.io.OutputStream;

/** Destination abstraction: plain files in the app folder or a SAF tree chosen by the user. */
interface Sink {

    /** Aligns persisted segment progress with whatever bytes already exist on disk. */
    void prepare(DownloadTask task) throws IOException;

    /** Opens an append point at the current position of the segment. */
    OutputStream openSegment(TaskSegment segment) throws IOException;

    /** Publishes the finished file and updates the task (name and/or content URI). */
    void finish(DownloadTask task) throws IOException;

    /** Removes temporary data; called when the task is cancelled. */
    void discard();
}