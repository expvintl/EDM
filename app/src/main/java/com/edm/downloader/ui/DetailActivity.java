package com.edm.downloader.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.edm.downloader.R;
import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.TaskSegment;
import com.edm.downloader.data.TaskState;
import com.edm.downloader.engine.DownloadEngine;
import com.edm.downloader.util.FileUtils;
import com.edm.downloader.util.Fmt;
import com.edm.downloader.widget.SegmentBarView;
import com.edm.downloader.widget.SpeedChartView;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/** Full task view: big progress, segmented bar, speed chart, stats and actions. */
public class DetailActivity extends AppCompatActivity {

    private static final String EXTRA_ID = "task_id";
    private static final long TICK_MS = 1000L;

    private long taskId;
    private DownloadTask task;

    private MaterialToolbar toolbar;
    private TextView percentView;
    private TextView stateChip;
    private SegmentBarView segmentBar;
    private TextView progressView;
    private TextView speedView;
    private TextView etaView;
    private SpeedChartView chart;
    private LinearLayout statsContainer;
    private TextView errorView;
    private LinearLayout threadsContainer;
    private MaterialButton actionButton;
    private MaterialButton openButton;

    private TextView sizeValue;
    private TextView downloadedValue;
    private TextView speedValue;
    private TextView remainingValue;
    private TextView threadsValue;
    private TextView resumeValue;
    private TextView serverValue;
    private TextView savedValue;
    private TextView addressValue;

    private final java.util.List<TextView> threadRows = new java.util.ArrayList<>();
    private int threadRowCount = -1;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, TICK_MS);
        }
    };

    public static Intent intent(Context context, long taskId) {
        Intent intent = new Intent(context, DetailActivity.class);
        intent.putExtra(EXTRA_ID, taskId);
        return intent;
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_detail);
        taskId = getIntent().getLongExtra(EXTRA_ID, -1);
        task = DownloadEngine.get(this).find(taskId);
        if (task == null) {
            Toast.makeText(this, R.string.detail_task_missing, Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        toolbar = findViewById(R.id.toolbar);
        SystemBars.apply(this);
        SystemBars.padToolbar(toolbar);
        SystemBars.padBottom(findViewById(R.id.scroll));
        toolbar.setNavigationOnClickListener(v -> finish());
        toolbar.setTitle(task.fileName);
        toolbar.setSubtitle(task.host());

        percentView = findViewById(R.id.percent);
        stateChip = findViewById(R.id.stateChip);
        segmentBar = findViewById(R.id.segmentBar);
        progressView = findViewById(R.id.progress);
        speedView = findViewById(R.id.speed);
        etaView = findViewById(R.id.eta);
        chart = findViewById(R.id.speedChart);
        statsContainer = findViewById(R.id.stats);
        threadsContainer = findViewById(R.id.threads);
        actionButton = findViewById(R.id.action);
        openButton = findViewById(R.id.open);

        segmentBar.setColors(ContextCompat.getColor(this, R.color.edm_track),
                ContextCompat.getColor(this, R.color.edm_primary));
        int primary = ContextCompat.getColor(this, R.color.edm_primary);
        chart.setColors(primary, ColorUtils.setAlphaComponent(primary, 48),
                ContextCompat.getColor(this, R.color.edm_divider));

        buildStats();
        actionButton.setOnClickListener(v -> onAction());
        openButton.setOnClickListener(v -> openFile());
        findViewById(R.id.delete).setOnClickListener(v -> confirmDelete());
        update();
    }

    private void buildStats() {
        sizeValue = addStatRow(R.string.detail_size);
        downloadedValue = addStatRow(R.string.detail_downloaded);
        speedValue = addStatRow(R.string.detail_speed);
        remainingValue = addStatRow(R.string.detail_remaining);
        threadsValue = addStatRow(R.string.detail_threads);
        resumeValue = addStatRow(R.string.detail_resume);
        serverValue = addStatRow(R.string.detail_server);
        savedValue = addStatRow(R.string.detail_saved_to);
        addressValue = addStatRow(R.string.detail_address);
        addressValue.setOnLongClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("url", task.url));
                Toast.makeText(this, R.string.detail_copy_url_done, Toast.LENGTH_SHORT).show();
            }
            return true;
        });

        // Full, selectable failure reason: the list row truncates it, and the exact
        // provider exception is what tells us why a folder refused the write.
        int pad = Math.round(8 * getResources().getDisplayMetrics().density);
        errorView = new TextView(this);
        errorView.setPadding(0, pad, 0, pad);
        errorView.setTextSize(12.5f);
        errorView.setTextColor(ContextCompat.getColor(this, R.color.edm_error));
        errorView.setTextIsSelectable(true);
        errorView.setVisibility(View.GONE);
        statsContainer.addView(errorView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TextView addStatRow(int labelRes) {
        int pad = Math.round(6 * getResources().getDisplayMetrics().density);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, pad, 0, pad);

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextColor(ContextCompat.getColor(this, R.color.edm_text_secondary));
        label.setTextSize(12.5f);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView value = new TextView(this);
        value.setTextColor(ContextCompat.getColor(this, R.color.edm_text_primary));
        value.setTextSize(13f);
        value.setGravity(Gravity.END);
        value.setMaxLines(2);
        value.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        row.addView(value, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        statsContainer.addView(row);
        return value;
    }

    private void update() {
        if (isFinishing()) {
            return;
        }
        DownloadTask current = DownloadEngine.get(this).find(taskId);
        if (current == null) {
            finish();
            return;
        }
        task = current;
        int stateColor = ContextCompat.getColor(this, TaskUi.stateColor(task.state));

        if (task.state == TaskState.DONE) {
            percentView.setText(getString(R.string.fmt_percent, 100));
        } else if (task.isIndeterminate()) {
            percentView.setText(R.string.value_unknown);
        } else {
            percentView.setText(getString(R.string.fmt_percent, task.percent()));
        }
        percentView.setTextColor(stateColor);

        stateChip.setText(TaskUi.stateLabel(task.state));
        stateChip.setTextColor(stateColor);
        if (stateChip.getBackground() != null) {
            stateChip.getBackground().mutate()
                    .setTint(ColorUtils.setAlphaComponent(stateColor, 32));
        }

        long total = task.total;
        long done = task.doneBytes();
        progressView.setText(total > 0
                ? getString(R.string.fmt_progress, Fmt.bytes(done), Fmt.bytes(total))
                : Fmt.bytes(done));

        boolean running = task.state == TaskState.DOWNLOADING;
        speedView.setText(running || task.state == TaskState.PAUSED
                ? (task.speed > 0 ? Fmt.speed(task.speed) : "—") : "—");
        etaView.setText(task.isIndeterminate() || task.state == TaskState.DONE
                ? "" : getString(R.string.fmt_eta, Fmt.eta(task.remaining(), task.speed)));

        if (running) {
            chart.addSample(task.speed);
        }

        segmentBar.setVisibility(task.total > 0 ? View.VISIBLE : View.GONE);
        if (task.total > 0) {
            segmentBar.setColors(ContextCompat.getColor(this, R.color.edm_track), stateColor);
            segmentBar.setData(task.segments, task.total, task.state == TaskState.DONE);
        }

        sizeValue.setText(task.total > 0 ? Fmt.bytes(task.total) : getString(R.string.unknown_size));
        downloadedValue.setText(Fmt.bytes(done));
        speedValue.setText(running ? Fmt.speed(task.speed) : "—");
        remainingValue.setText(task.state == TaskState.DONE ? "—"
                : Fmt.eta(task.remaining(), task.speed));
        threadsValue.setText(getString(R.string.fmt_threads, task.threads));
        boolean resumeKnown = task.total > 0 && task.state != TaskState.QUEUED
                && task.state != TaskState.CONNECTING;
        resumeValue.setText(resumeKnown
                ? getString(task.resumable ? R.string.detail_resume_yes : R.string.detail_resume_no)
                : getString(R.string.value_unknown));
        resumeValue.setTextColor(ContextCompat.getColor(this, !resumeKnown || task.resumable
                ? R.color.edm_text_primary : R.color.edm_warning));
        serverValue.setText(task.host());
        savedValue.setText(savedPath());
        addressValue.setText(task.url);
        if (task.error != null && !task.error.isEmpty()) {
            errorView.setText(getString(R.string.detail_error, task.error));
            errorView.setTextColor(ContextCompat.getColor(this, R.color.edm_error));
            errorView.setVisibility(View.VISIBLE);
        } else if (task.note != null && !task.note.isEmpty()) {
            errorView.setText(getString(R.string.detail_note, task.note));
            errorView.setTextColor(ContextCompat.getColor(this, R.color.edm_warning));
            errorView.setVisibility(View.VISIBLE);
        } else {
            errorView.setVisibility(View.GONE);
        }

        updateThreads();

        boolean doneState = task.state == TaskState.DONE;
        boolean openable = doneState && FileUtils.exists(this, task);
        openButton.setVisibility(openable ? View.VISIBLE : View.GONE);
        if (doneState) {
            actionButton.setText(R.string.action_share);
        } else if (task.state == TaskState.FAILED) {
            actionButton.setText(R.string.action_retry);
        } else if (task.state == TaskState.PAUSED || task.state == TaskState.QUEUED) {
            actionButton.setText(R.string.action_resume);
        } else {
            actionButton.setText(R.string.action_pause);
        }
    }

    private String savedPath() {
        if (task.dirUri != null) {
            return FileUtils.folderLabel(this, task.dirUri);
        }
        return FileUtils.defaultDir(this).getAbsolutePath();
    }

    private void updateThreads() {
        java.util.List<TaskSegment> segments = task.segments;
        if (segments.isEmpty() && threadRowCount == 0) {
            return;
        }
        if (segments.size() != threadRowCount) {
            threadRowCount = segments.size();
            threadsContainer.removeAllViews();
            threadRows.clear();
            for (int i = 0; i < segments.size(); i++) {
                int pad = Math.round(4 * getResources().getDisplayMetrics().density);
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setPadding(pad, pad, pad, pad);

                TextView name = new TextView(this);
                name.setText(getString(R.string.thread_name, i + 1));
                name.setTextColor(ContextCompat.getColor(this, R.color.edm_text_primary));
                name.setTextSize(12.5f);
                row.addView(name, new LinearLayout.LayoutParams(0,
                        ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                TextView value = new TextView(this);
                value.setTextColor(ContextCompat.getColor(this, R.color.edm_text_secondary));
                value.setTextSize(12.5f);
                row.addView(value, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

                threadsContainer.addView(row);
                threadRows.add(value);
            }
            if (segments.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText(R.string.value_unknown);
                empty.setTextColor(ContextCompat.getColor(this, R.color.edm_text_secondary));
                empty.setTextSize(12.5f);
                threadsContainer.addView(empty);
            }
        }
        for (int i = 0; i < segments.size() && i < threadRows.size(); i++) {
            TaskSegment segment = segments.get(i);
            threadRows.get(i).setText(threadText(segment));
        }
    }

    private String threadText(TaskSegment segment) {
        long length = segment.length();
        if (segment.isComplete()) {
            return getString(R.string.thread_complete) + " · " + Fmt.bytes(segment.done);
        }
        String done = Fmt.bytes(segment.done);
        if (length > 0) {
            return done + " / " + Fmt.bytes(length);
        }
        if (task.total > 0) {
            return done + " / " + Fmt.bytes(task.total);
        }
        return done;
    }

    private void onAction() {
        DownloadEngine engine = DownloadEngine.get(this);
        DownloadTask current = engine.find(taskId);
        if (current == null) {
            return;
        }
        switch (current.state) {
            case TaskState.DOWNLOADING:
            case TaskState.CONNECTING:
                engine.pause(taskId);
                break;
            case TaskState.DONE:
                shareFile();
                break;
            default:
                engine.resume(taskId);
                break;
        }
    }

    private void openFile() {
        FileOpen.open(this, task);
    }

    private void shareFile() {
        Intent intent = FileUtils.shareIntent(this, task);
        if (intent == null) {
            Toast.makeText(this, R.string.detail_open_missing, Toast.LENGTH_SHORT).show();
            return;
        }
        startActivity(Intent.createChooser(intent, getString(R.string.detail_share)));
    }

    private void confirmDelete() {
        DownloadEngine engine = DownloadEngine.get(this);
        boolean done = task != null && task.state == TaskState.DONE;
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(done ? R.string.delete_dialog_title : R.string.cancel_download_title)
                .setMessage(done ? R.string.delete_dialog_message : R.string.cancel_download_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(done ? R.string.action_delete_file : R.string.action_delete,
                        (d, w) -> {
                            engine.cancel(taskId);
                            finish();
                        });
        if (done) {
            builder.setNeutralButton(R.string.action_remove_record, (d, w) -> {
                engine.removeRecord(taskId, false);
                finish();
            });
        }
        builder.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }
}