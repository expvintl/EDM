package com.edm.downloader.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

/** SQLite persistence for tasks and their segments. */
public class DownloadStore extends SQLiteOpenHelper {

    private static final String DB_NAME = "edm.db";
    private static final int DB_VERSION = 3;

    private static final String TABLE_TASKS = "tasks";
    private static final String TABLE_SEGMENTS = "segments";

    public DownloadStore(Context context) {
        super(context.getApplicationContext(), DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE " + TABLE_TASKS + " ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,"
                + "url TEXT NOT NULL,"
                + "final_url TEXT,"
                + "file_name TEXT,"
                + "dir_uri TEXT,"
                + "file_uri TEXT,"
                + "mime TEXT,"
                + "user_agent TEXT,"
                + "proxy INTEGER DEFAULT 0,"
                + "proxy_type INTEGER DEFAULT 0,"
                + "proxy_host TEXT,"
                + "proxy_port INTEGER DEFAULT 0,"
                + "ua_mode INTEGER DEFAULT 0,"
                + "cookie TEXT,"
                + "referer TEXT,"
                + "total INTEGER DEFAULT -1,"
                + "downloaded INTEGER DEFAULT 0,"
                + "state INTEGER DEFAULT 0,"
                + "threads INTEGER DEFAULT 4,"
                + "resumable INTEGER DEFAULT 1,"
                + "custom_name INTEGER DEFAULT 0,"
                + "etag TEXT,"
                + "last_modified TEXT,"
                + "error TEXT,"
                + "retries INTEGER DEFAULT 0,"
                + "created INTEGER DEFAULT 0,"
                + "completed INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE " + TABLE_SEGMENTS + " ("
                + "task_id INTEGER NOT NULL,"
                + "idx INTEGER NOT NULL,"
                + "start INTEGER NOT NULL,"
                + "end INTEGER NOT NULL,"
                + "done INTEGER DEFAULT 0,"
                + "state INTEGER DEFAULT 0,"
                + "PRIMARY KEY (task_id, idx))");
        db.execSQL("CREATE INDEX idx_tasks_state ON " + TABLE_TASKS + "(state)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN proxy INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN proxy_type INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN proxy_host TEXT");
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN proxy_port INTEGER DEFAULT 0");
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN ua_mode INTEGER DEFAULT 0");
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE " + TABLE_TASKS + " ADD COLUMN retries INTEGER DEFAULT 0");
        }
    }

    public long insert(DownloadTask task) {
        ContentValues values = taskValues(task);
        long id = getWritableDatabase().insert(TABLE_TASKS, null, values);
        task.id = id;
        saveSegments(task);
        return id;
    }

    public void update(DownloadTask task) {
        getWritableDatabase().update(TABLE_TASKS, taskValues(task), "id=?",
                new String[]{String.valueOf(task.id)});
    }

    public void updateProgress(DownloadTask task) {
        ContentValues values = new ContentValues();
        values.put("downloaded", task.downloaded);
        values.put("total", task.total);
        values.put("state", task.state);
        values.put("error", task.error);
        values.put("completed", task.completed);
        values.put("etag", task.etag);
        values.put("last_modified", task.lastModified);
        values.put("retries", task.retries);
        getWritableDatabase().update(TABLE_TASKS, values, "id=?",
                new String[]{String.valueOf(task.id)});
    }

    public void delete(long taskId) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete(TABLE_SEGMENTS, "task_id=?", new String[]{String.valueOf(taskId)});
        db.delete(TABLE_TASKS, "id=?", new String[]{String.valueOf(taskId)});
    }

    public void saveSegments(DownloadTask task) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete(TABLE_SEGMENTS, "task_id=?", new String[]{String.valueOf(task.id)});
            for (TaskSegment segment : task.segments) {
                ContentValues values = new ContentValues();
                values.put("task_id", task.id);
                values.put("idx", segment.index);
                values.put("start", segment.start);
                values.put("end", segment.end);
                values.put("done", segment.done);
                values.put("state", segment.state);
                db.insert(TABLE_SEGMENTS, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public List<DownloadTask> loadAll() {
        List<DownloadTask> tasks = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().query(TABLE_TASKS, null, null, null, null, null, "created DESC")) {
            while (cursor.moveToNext()) {
                tasks.add(readTask(cursor));
            }
        }
        for (DownloadTask task : tasks) {
            loadSegments(task);
        }
        return tasks;
    }

    public DownloadTask load(long taskId) {
        try (Cursor cursor = getReadableDatabase().query(TABLE_TASKS, null, "id=?",
                new String[]{String.valueOf(taskId)}, null, null, null)) {
            if (!cursor.moveToFirst()) {
                return null;
            }
            DownloadTask task = readTask(cursor);
            loadSegments(task);
            return task;
        }
    }

    /** Tasks interrupted by process death are restored as paused; their ids are returned. */
    public List<Long> resetRunning() {
        List<Long> ids = new ArrayList<>();
        String[] runningStates = {String.valueOf(TaskState.CONNECTING), String.valueOf(TaskState.DOWNLOADING)};
        try (Cursor cursor = getReadableDatabase().query(TABLE_TASKS, new String[]{"id"},
                "state IN (?,?)", runningStates, null, null, null)) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        }
        ContentValues values = new ContentValues();
        values.put("state", TaskState.PAUSED);
        getWritableDatabase().update(TABLE_TASKS, values, "state IN (?,?)", runningStates);
        ContentValues segmentValues = new ContentValues();
        segmentValues.put("state", 0);
        getWritableDatabase().update(TABLE_SEGMENTS, segmentValues, "state=?", new String[]{"1"});
        return ids;
    }

    private void loadSegments(DownloadTask task) {
        task.segments.clear();
        try (Cursor cursor = getReadableDatabase().query(TABLE_SEGMENTS, null, "task_id=?",
                new String[]{String.valueOf(task.id)}, null, null, "idx ASC")) {
            while (cursor.moveToNext()) {
                TaskSegment segment = new TaskSegment();
                segment.index = cursor.getInt(cursor.getColumnIndexOrThrow("idx"));
                segment.start = cursor.getLong(cursor.getColumnIndexOrThrow("start"));
                segment.end = cursor.getLong(cursor.getColumnIndexOrThrow("end"));
                segment.done = cursor.getLong(cursor.getColumnIndexOrThrow("done"));
                segment.state = cursor.getInt(cursor.getColumnIndexOrThrow("state"));
                task.segments.add(segment);
            }
        }
    }

    private DownloadTask readTask(Cursor cursor) {
        DownloadTask task = new DownloadTask();
        task.id = cursor.getLong(cursor.getColumnIndexOrThrow("id"));
        task.url = string(cursor, "url", "");
        task.finalUrl = string(cursor, "final_url", null);
        task.fileName = string(cursor, "file_name", "");
        task.dirUri = string(cursor, "dir_uri", null);
        task.fileUri = string(cursor, "file_uri", null);
        task.mime = string(cursor, "mime", "application/octet-stream");
        task.userAgent = string(cursor, "user_agent", null);
        task.proxy = cursor.getInt(cursor.getColumnIndexOrThrow("proxy")) != 0;
        task.proxyType = cursor.getInt(cursor.getColumnIndexOrThrow("proxy_type"));
        task.proxyHost = string(cursor, "proxy_host", null);
        task.proxyPort = cursor.getInt(cursor.getColumnIndexOrThrow("proxy_port"));
        task.uaMode = cursor.getInt(cursor.getColumnIndexOrThrow("ua_mode"));
        task.cookie = string(cursor, "cookie", null);
        task.referer = string(cursor, "referer", null);
        task.total = cursor.getLong(cursor.getColumnIndexOrThrow("total"));
        task.downloaded = cursor.getLong(cursor.getColumnIndexOrThrow("downloaded"));
        task.state = cursor.getInt(cursor.getColumnIndexOrThrow("state"));
        task.threads = cursor.getInt(cursor.getColumnIndexOrThrow("threads"));
        task.resumable = cursor.getInt(cursor.getColumnIndexOrThrow("resumable")) != 0;
        task.customName = cursor.getInt(cursor.getColumnIndexOrThrow("custom_name")) != 0;
        task.etag = string(cursor, "etag", null);
        task.lastModified = string(cursor, "last_modified", null);
        task.error = string(cursor, "error", null);
        task.retries = cursor.getInt(cursor.getColumnIndexOrThrow("retries"));
        task.created = cursor.getLong(cursor.getColumnIndexOrThrow("created"));
        task.completed = cursor.getLong(cursor.getColumnIndexOrThrow("completed"));
        return task;
    }

    private static String string(Cursor cursor, String column, String fallback) {
        int index = cursor.getColumnIndexOrThrow(column);
        return cursor.isNull(index) ? fallback : cursor.getString(index);
    }

    private static ContentValues taskValues(DownloadTask task) {
        ContentValues values = new ContentValues();
        values.put("url", task.url);
        values.put("final_url", task.finalUrl);
        values.put("file_name", task.fileName);
        values.put("dir_uri", task.dirUri);
        values.put("file_uri", task.fileUri);
        values.put("mime", task.mime);
        values.put("user_agent", task.userAgent);
        values.put("proxy", task.proxy ? 1 : 0);
        values.put("proxy_type", task.proxyType);
        values.put("proxy_host", task.proxyHost);
        values.put("proxy_port", task.proxyPort);
        values.put("ua_mode", task.uaMode);
        values.put("cookie", task.cookie);
        values.put("referer", task.referer);
        values.put("total", task.total);
        values.put("downloaded", task.downloaded);
        values.put("state", task.state);
        values.put("threads", task.threads);
        values.put("resumable", task.resumable ? 1 : 0);
        values.put("custom_name", task.customName ? 1 : 0);
        values.put("etag", task.etag);
        values.put("last_modified", task.lastModified);
        values.put("error", task.error);
        values.put("retries", task.retries);
        values.put("created", task.created);
        values.put("completed", task.completed);
        return values;
    }
}