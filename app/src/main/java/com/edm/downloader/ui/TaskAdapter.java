package com.edm.downloader.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import com.edm.downloader.R;
import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.TaskState;
import com.edm.downloader.widget.CircularProgressView;
import com.edm.downloader.widget.SegmentBarView;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Binds download tasks to the shared list card layout. */
public class TaskAdapter extends RecyclerView.Adapter<TaskAdapter.Holder> {

    public interface Listener {
        void onTaskClick(DownloadTask task);

        void onTaskLongClick(DownloadTask task);

        void onTaskMenu(DownloadTask task, View anchor);
    }

    private final Context context;
    private final Listener listener;
    private final List<DownloadTask> tasks = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private boolean waitingWifi;
    private boolean selectionMode;

    public TaskAdapter(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void submit(List<DownloadTask> items, boolean waitingWifi) {
        this.waitingWifi = waitingWifi;
        tasks.clear();
        tasks.addAll(items);
        notifyDataSetChanged();
    }

    /** Updates selection state without notifying; call before {@link #submit}. */
    public void setSelection(boolean mode, Collection<Long> ids) {
        selectionMode = mode;
        selectedIds.clear();
        if (ids != null) {
            selectedIds.addAll(ids);
        }
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_task, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        DownloadTask task = tasks.get(position);
        boolean selected = selectionMode && selectedIds.contains(task.id);

        holder.title.setText(task.fileName);
        holder.status.setText(TaskUi.progressLine(context, task, waitingWifi && task.state == TaskState.QUEUED));
        holder.status.setTextColor(statusColor(task));

        String meta = TaskUi.metaLine(context, task);
        holder.meta.setText(meta);
        holder.meta.setVisibility(meta.isEmpty() ? View.GONE : View.VISIBLE);

        // Links whose server ignores Range always start over, so flag them on the card.
        boolean noResume = !task.resumable && (task.state == TaskState.DOWNLOADING
                || task.state == TaskState.PAUSED || task.state == TaskState.FAILED);
        holder.resumeChip.setVisibility(noResume ? View.VISIBLE : View.GONE);

        holder.icon.setImageResource(TaskUi.iconFor(task.fileName));
        holder.icon.setColorFilter(TaskUi.color(context, TaskUi.iconTint(task.fileName)));
        Drawable background = holder.iconFrame.getBackground();
        if (background != null) {
            background.mutate().setTint(TaskUi.color(context, TaskUi.iconBackground(task.fileName)));
        }

        bindRing(holder.ring, task);

        boolean showBar = task.total > 0 && !task.segments.isEmpty()
                && (task.state == TaskState.DOWNLOADING || task.state == TaskState.PAUSED
                || task.state == TaskState.FAILED);
        holder.bar.setVisibility(showBar ? View.VISIBLE : View.GONE);
        if (showBar) {
            holder.bar.setColors(TaskUi.color(context, R.color.edm_track),
                    TaskUi.color(context, TaskUi.stateColor(task.state)));
            holder.bar.setData(task.segments, task.total, task.state == TaskState.DONE);
        }

        holder.check.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        holder.check.setChecked(selected);
        holder.more.setVisibility(selectionMode ? View.GONE : View.VISIBLE);
        holder.card.setCardBackgroundColor(TaskUi.color(context,
                selected ? R.color.edm_primary_container : R.color.edm_surface));

        holder.itemView.setOnClickListener(v -> listener.onTaskClick(task));
        holder.itemView.setOnLongClickListener(v -> {
            listener.onTaskLongClick(task);
            return true;
        });
        holder.more.setOnClickListener(v -> listener.onTaskMenu(task, v));
    }

    private void bindRing(CircularProgressView ring, DownloadTask task) {
        int color = TaskUi.color(context, TaskUi.stateColor(task.state));
        ring.setColors(TaskUi.color(context, R.color.edm_track), color, color);
        if (task.state == TaskState.DONE) {
            ring.setProgress(100, false, true);
        } else if (task.state == TaskState.CONNECTING
                || (task.state == TaskState.DOWNLOADING && task.total <= 0)) {
            ring.setProgress(0, true, false);
        } else {
            ring.setProgress(task.percent(), false, false);
        }
    }

    private int statusColor(DownloadTask task) {
        switch (task.state) {
            case TaskState.PAUSED:
                return TaskUi.color(context, R.color.edm_warning);
            case TaskState.FAILED:
                return TaskUi.color(context, R.color.edm_error);
            case TaskState.DONE:
                return TaskUi.color(context, R.color.edm_success);
            default:
                return ContextCompat.getColor(context, R.color.edm_text_secondary);
        }
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    static class Holder extends RecyclerView.ViewHolder {

        final MaterialCardView card;
        final View iconFrame;
        final ImageView icon;
        final TextView title;
        final TextView status;
        final TextView meta;
        final TextView resumeChip;
        final CircularProgressView ring;
        final SegmentBarView bar;
        final MaterialCheckBox check;
        final ImageButton more;

        Holder(@NonNull View itemView) {
            super(itemView);
            card = (MaterialCardView) itemView;
            iconFrame = itemView.findViewById(R.id.iconFrame);
            icon = itemView.findViewById(R.id.typeIcon);
            title = itemView.findViewById(R.id.title);
            status = itemView.findViewById(R.id.status);
            meta = itemView.findViewById(R.id.meta);
            resumeChip = itemView.findViewById(R.id.resumeChip);
            ring = itemView.findViewById(R.id.ring);
            bar = itemView.findViewById(R.id.segmentBar);
            check = itemView.findViewById(R.id.check);
            more = itemView.findViewById(R.id.more);
        }
    }
}