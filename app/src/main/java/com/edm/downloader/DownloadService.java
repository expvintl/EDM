package com.edm.downloader;

import android.app.Notification;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.core.app.ServiceCompat;

import com.edm.downloader.engine.DownloadEngine;

/** Keeps downloads alive in the background and handles notification actions. */
public class DownloadService extends Service {

    public static final int SUMMARY_ID = 1;

    public static final String ACTION_PAUSE = "com.edm.downloader.action.PAUSE";
    public static final String ACTION_RESUME = "com.edm.downloader.action.RESUME";
    public static final String ACTION_CANCEL = "com.edm.downloader.action.CANCEL";
    public static final String ACTION_PAUSE_ALL = "com.edm.downloader.action.PAUSE_ALL";
    public static final String ACTION_RESUME_ALL = "com.edm.downloader.action.RESUME_ALL";
    public static final String EXTRA_TASK_ID = "task_id";

    private DownloadEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();
        engine = DownloadEngine.get(this);
        Notification notification = engine.idleNotification();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC : 0;
        ServiceCompat.startForeground(this, SUMMARY_ID, notification, type);
        engine.onServiceReady();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (engine == null) {
            engine = DownloadEngine.get(this);
        }
        if (intent != null && intent.getAction() != null) {
            long taskId = intent.getLongExtra(EXTRA_TASK_ID, -1);
            switch (intent.getAction()) {
                case ACTION_PAUSE:
                    if (taskId >= 0) {
                        engine.pause(taskId);
                    }
                    break;
                case ACTION_RESUME:
                    if (taskId >= 0) {
                        engine.resume(taskId);
                    }
                    break;
                case ACTION_CANCEL:
                    if (taskId >= 0) {
                        engine.cancel(taskId);
                    }
                    break;
                case ACTION_PAUSE_ALL:
                    engine.pauseAll();
                    break;
                case ACTION_RESUME_ALL:
                    engine.resumeAll();
                    break;
                default:
                    break;
            }
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (engine != null) {
            engine.onServiceDestroyed();
        }
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}