package com.edm.downloader.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.edm.downloader.MainActivity;
import com.edm.downloader.R;
import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.Settings;
import com.edm.downloader.data.TaskState;
import com.edm.downloader.engine.DownloadEngine;
import com.edm.downloader.util.FileUtils;
import com.edm.downloader.util.Net;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One of the two task tabs: active downloads or completed ones. */
public class TaskListFragment extends Fragment implements TaskAdapter.Listener {

    private static final String ARG_MODE = "mode";
    private static final long TICK_MS = 1000L;

    public static final int MODE_ALL = 0;
    public static final int MODE_ACTIVE = 1;
    public static final int MODE_DONE = 2;
    private static final int LIST_PADDING_BOTTOM_DP = 88;
    private static final int LIST_PADDING_SELECTION_DP = 8;

    private long folderTaskId;

    /** Lets the user retarget an unfinished task at another folder before retrying it. */
    private final ActivityResultLauncher<Uri> folderLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null || folderTaskId == 0L) {
                    return;
                }
                try {
                    requireContext().getContentResolver().takePersistableUriPermission(uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                } catch (Exception ignored) {
                    // A provider may hand out transient access only; the write can still work.
                }
                boolean restarted = DownloadEngine.get(requireContext())
                        .changeFolder(folderTaskId, uri.toString());
                toast(restarted ? R.string.folder_changed_restart : R.string.folder_changed);
            });

    private int mode = MODE_ACTIVE;
    private TaskAdapter adapter;
    private RecyclerView list;
    private View empty;
    private TextView emptyTitle;
    private TextView emptyHint;
    private ImageView emptyIcon;
    private String query = "";

    private View selectionBar;
    private View selectionActions;
    private TextView selectionCount;
    private TextView selectionSelectAll;
    private boolean selectionMode;
    private final Set<Long> selected = new LinkedHashSet<>();
    private List<DownloadTask> visibleTasks = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, TICK_MS);
        }
    };

    public static TaskListFragment newInstance(int mode) {
        TaskListFragment fragment = new TaskListFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_MODE, mode);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mode = getArguments() == null ? MODE_ACTIVE : getArguments().getInt(ARG_MODE, MODE_ACTIVE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_task_list, container, false);
        list = view.findViewById(R.id.list);
        empty = view.findViewById(R.id.empty);
        emptyTitle = view.findViewById(R.id.emptyTitle);
        emptyHint = view.findViewById(R.id.emptyHint);
        emptyIcon = view.findViewById(R.id.emptyIcon);
        selectionBar = view.findViewById(R.id.selectionBar);
        selectionActions = view.findViewById(R.id.selectionActions);
        selectionCount = view.findViewById(R.id.selectionCount);
        selectionSelectAll = view.findViewById(R.id.selectionSelectAll);

        list.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new TaskAdapter(requireContext(), this);
        list.setAdapter(adapter);
        boolean doneMode = mode == MODE_DONE;
        emptyIcon.setImageResource(doneMode ? R.drawable.ic_check_circle : R.drawable.ic_cloud_download);
        emptyTitle.setText(doneMode ? R.string.empty_done_title : R.string.empty_active_title);
        emptyHint.setText(doneMode ? R.string.empty_done_hint : R.string.empty_active_hint);

        view.findViewById(R.id.selectionClose).setOnClickListener(v -> exitSelection());
        selectionSelectAll.setOnClickListener(v -> toggleSelectAll());
        view.findViewById(R.id.selectionRemove).setOnClickListener(v -> confirmRemoveRecords());
        view.findViewById(R.id.selectionDelete).setOnClickListener(v -> confirmDeleteFiles());

        refresh();
        return view;
    }

    private boolean isReallyVisible() {
        if (!isVisible()) {
            return false;
        }
        Fragment parent = getParentFragment();
        if (parent instanceof DownloadsFragment) {
            return parent.isVisible() && ((DownloadsFragment) parent).isActivePage(this);
        }
        return parent == null || (parent.isVisible() && !parent.isHidden());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().getOnBackPressedDispatcher().addCallback(getViewLifecycleOwner(),
                new OnBackPressedCallback(true) {
                    @Override
                    public void handleOnBackPressed() {
                        if (selectionMode && isReallyVisible()) {
                            exitSelection();
                            return;
                        }
                        setEnabled(false);
                        try {
                            requireActivity().getOnBackPressedDispatcher().onBackPressed();
                        } finally {
                            setEnabled(true);
                        }
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(tick);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(tick);
        super.onPause();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (hidden && selectionMode) {
            exitSelection();
        }
    }

    public void setQuery(String text) {
        query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        // The view (and therefore the adapter) may not exist yet when the parent
        // hands over a pending query; onCreateView refreshes with the stored value.
        if (adapter != null) {
            refresh();
        }
    }

    public void refresh() {
        if (!isAdded() || adapter == null) {
            return;
        }
        DownloadEngine engine = DownloadEngine.get(requireContext());
        Settings settings = engine.settings();
        List<DownloadTask> visible = new ArrayList<>();
        for (DownloadTask task : engine.tasks()) {
            boolean isDone = task.state == TaskState.DONE;
            if (mode == MODE_DONE && !isDone) {
                continue;
            }
            if (mode == MODE_ACTIVE && isDone) {
                continue;
            }
            if (!query.isEmpty()
                    && !task.fileName.toLowerCase(Locale.ROOT).contains(query)
                    && !task.url.toLowerCase(Locale.ROOT).contains(query)) {
                continue;
            }
            visible.add(task);
        }
        sort(visible, settings.sortMode());
        visibleTasks = visible;

        if (selectionMode) {
            Set<Long> ids = new LinkedHashSet<>();
            for (DownloadTask task : visible) {
                ids.add(task.id);
            }
            selected.retainAll(ids);
            if (selected.isEmpty()) {
                exitSelection();
            }
        }

        boolean waitingWifi = settings.wifiOnly() && !Net.isWifi(requireContext());
        adapter.setSelection(selectionMode, selected);
        adapter.submit(visible, waitingWifi);
        updateSelectionUi();

        boolean isEmpty = visible.isEmpty();
        empty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        list.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private void sort(List<DownloadTask> items, int sortMode) {
        switch (sortMode) {
            case Settings.SORT_NAME:
                Collections.sort(items, (a, b) ->
                        a.fileName.compareToIgnoreCase(b.fileName));
                break;
            case Settings.SORT_SIZE:
                Collections.sort(items, (a, b) -> Long.compare(b.total, a.total));
                break;
            default:
                Collections.sort(items, (a, b) -> Long.compare(b.created, a.created));
                break;
        }
    }

    private void enterSelection(DownloadTask task) {
        selectionMode = true;
        selected.clear();
        selected.add(task.id);
        if (getActivity() instanceof MainActivity) {
            ((MainActivity) getActivity()).setSelectionMode(true);
        }
        requireView().performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        applySelection();
    }

    private void toggleSelection(long id) {
        if (!selected.remove(id)) {
            selected.add(id);
        }
        if (selected.isEmpty()) {
            exitSelection();
            return;
        }
        applySelection();
    }

    private void toggleSelectAll() {
        if (!selectionMode) {
            return;
        }
        if (allVisibleSelected()) {
            selected.clear();
            exitSelection();
            return;
        }
        for (DownloadTask task : visibleTasks) {
            selected.add(task.id);
        }
        applySelection();
    }

    private boolean allVisibleSelected() {
        if (visibleTasks.isEmpty()) {
            return false;
        }
        for (DownloadTask task : visibleTasks) {
            if (!selected.contains(task.id)) {
                return false;
            }
        }
        return true;
    }

    /** Leaves multi-select, used when a parent fragment hides this list. */
    public void cancelSelection() {
        if (selectionMode) {
            exitSelection();
        }
    }

    private void exitSelection() {
        selectionMode = false;
        selected.clear();
        if (isAdded()) {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) getActivity()).setSelectionMode(false);
            }
            adapter.setSelection(false, selected);
            adapter.notifyDataSetChanged();
            updateSelectionUi();
        }
    }

    private void applySelection() {
        adapter.setSelection(selectionMode, selected);
        adapter.notifyDataSetChanged();
        updateSelectionUi();
    }

    private void updateSelectionUi() {
        selectionBar.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        selectionActions.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        int bottom = dp(selectionMode ? LIST_PADDING_SELECTION_DP : LIST_PADDING_BOTTOM_DP);
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), bottom);
        if (!selectionMode) {
            return;
        }
        selectionCount.setText(getString(R.string.selection_count, selected.size()));
        selectionSelectAll.setText(allVisibleSelected()
                ? R.string.action_select_none : R.string.action_select_all);
    }

    private int dp(int value) {
        return Math.round(getResources().getDisplayMetrics().density * value);
    }

    private List<DownloadTask> selectedTasks() {
        DownloadEngine engine = DownloadEngine.get(requireContext());
        List<DownloadTask> targets = new ArrayList<>();
        for (Long id : new ArrayList<>(selected)) {
            DownloadTask task = engine.find(id);
            if (task != null) {
                targets.add(task);
            }
        }
        return targets;
    }

    private void confirmDeleteFiles() {
        List<DownloadTask> targets = selectedTasks();
        if (targets.isEmpty()) {
            exitSelection();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.selection_delete_title, targets.size()))
                .setMessage(R.string.selection_delete_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_delete, (d, w) -> {
                    DownloadEngine engine = DownloadEngine.get(requireContext());
                    for (DownloadTask task : targets) {
                        engine.cancel(task.id);
                    }
                    Toast.makeText(requireContext(),
                            getString(R.string.selection_deleted, targets.size()),
                            Toast.LENGTH_SHORT).show();
                    exitSelection();
                    refresh();
                })
                .show();
    }

    private void confirmRemoveRecords() {
        List<DownloadTask> targets = selectedTasks();
        if (targets.isEmpty()) {
            exitSelection();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.selection_remove_title, targets.size()))
                .setMessage(R.string.selection_remove_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.remove_record_only, (d, w) -> {
                    DownloadEngine engine = DownloadEngine.get(requireContext());
                    for (DownloadTask task : targets) {
                        if (task.state == TaskState.DONE) {
                            engine.removeRecord(task.id, false);
                        } else {
                            // Unfinished downloads have no usable file; cancel them and drop temp data.
                            engine.cancel(task.id);
                        }
                    }
                    Toast.makeText(requireContext(),
                            getString(R.string.selection_removed, targets.size()),
                            Toast.LENGTH_SHORT).show();
                    exitSelection();
                    refresh();
                })
                .show();
    }

    @Override
    public void onTaskClick(DownloadTask task) {
        if (selectionMode) {
            toggleSelection(task.id);
            return;
        }
        if (task.state == TaskState.DONE) {
            // Finished items open straight away (APKs reach the installer); details stay in the menu.
            FileOpen.open(requireActivity(), task);
            return;
        }
        startActivity(DetailActivity.intent(requireContext(), task.id));
    }

    @Override
    public void onTaskLongClick(DownloadTask task) {
        if (!selectionMode) {
            enterSelection(task);
            return;
        }
        toggleSelection(task.id);
    }

    @Override
    public void onTaskMenu(DownloadTask task, View anchor) {
        DownloadEngine engine = DownloadEngine.get(requireContext());
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.inflate(R.menu.menu_task);
        Menu menu = popup.getMenu();
        boolean resumable = task.state == TaskState.PAUSED || task.state == TaskState.FAILED
                || task.state == TaskState.QUEUED;
        menu.findItem(R.id.task_toggle).setVisible(task.state != TaskState.DONE);
        menu.findItem(R.id.task_toggle).setTitle(
                resumable ? R.string.action_resume : R.string.action_pause);
        boolean finished = task.state == TaskState.DONE && FileUtils.exists(requireContext(), task);
        menu.findItem(R.id.task_open).setVisible(finished);
        menu.findItem(R.id.task_share).setVisible(finished);
        menu.findItem(R.id.task_redownload).setVisible(task.state == TaskState.DONE);
        menu.findItem(R.id.task_change_folder).setVisible(resumable);
        popup.setOnMenuItemClickListener(item -> {
            int id = item.getItemId();
            if (id == R.id.task_toggle) {
                if (resumable) {
                    engine.resume(task.id);
                } else {
                    engine.pause(task.id);
                }
                return true;
            }
            if (id == R.id.task_open) {
                openTask(task);
                return true;
            }
            if (id == R.id.task_share) {
                shareTask(task);
                return true;
            }
            if (id == R.id.task_copy) {
                copyLink(task);
                return true;
            }
            if (id == R.id.task_redownload) {
                redownload(task);
                return true;
            }
            if (id == R.id.task_change_folder) {
                folderTaskId = task.id;
                folderLauncher.launch(FileUtils.initialTreeUri());
                return true;
            }
            if (id == R.id.task_details) {
                startActivity(DetailActivity.intent(requireContext(), task.id));
                return true;
            }
            if (id == R.id.task_delete) {
                confirmDelete(task);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void redownload(DownloadTask task) {
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.redownload_dialog_title)
                .setMessage(R.string.redownload_dialog_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_redownload, (dialog, which) ->
                        DownloadEngine.get(requireContext()).restart(task.id))
                .show();
    }


    private void openTask(DownloadTask task) {
        FileOpen.open(requireActivity(), task);
    }

    private void shareTask(DownloadTask task) {
        Intent intent = FileUtils.shareIntent(requireContext(), task);
        if (intent == null) {
            toast(R.string.open_failed);
            return;
        }
        startActivity(Intent.createChooser(intent, getString(R.string.action_share)));
    }

    private void copyLink(DownloadTask task) {
        ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("url", task.url));
            toast(R.string.copied_to_clipboard);
        }
    }

    private void confirmDelete(DownloadTask task) {
        DownloadEngine engine = DownloadEngine.get(requireContext());
        if (task.state != TaskState.DONE) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.cancel_download_title)
                    .setMessage(R.string.cancel_download_message)
                    .setNegativeButton(R.string.action_cancel, null)
                    .setPositiveButton(R.string.action_delete, (d, w) -> engine.cancel(task.id))
                    .show();
            return;
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.delete_dialog_title)
                .setMessage(R.string.delete_dialog_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.action_remove_record,
                        (d, w) -> engine.removeRecord(task.id, false))
                .setPositiveButton(R.string.action_delete_file,
                        (d, w) -> engine.cancel(task.id))
                .show();
    }

    private void toast(int res) {
        Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show();
    }
}