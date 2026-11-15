package com.edm.downloader.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.edm.downloader.R;
import com.edm.downloader.data.DownloadTask;
import com.edm.downloader.data.Settings;
import com.edm.downloader.engine.DownloadEngine;
import com.edm.downloader.engine.HttpProbe;
import com.edm.downloader.util.FileUtils;
import com.edm.downloader.util.Fmt;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.net.ssl.SSLException;

/** Bottom sheet that creates a download from a pasted or captured link. */
public class AddDownloadSheet extends BottomSheetDialogFragment {

    private static final String ARG_URL = "url";
    private static final String ARG_NAME = "name";
    private static final String ARG_MIME = "mime";
    private static final String ARG_REFERER = "referer";
    private static final String ARG_UA = "ua";
    private static final String ARG_COOKIE = "cookie";

    private TextInputLayout urlLayout;
    private TextInputEditText urlInput;
    private TextInputEditText nameInput;
    private TextView folderView;
    private boolean nameTouched;
    private boolean updatingName;
    private MaterialButtonToggleGroup uaGroup;
    private TextInputLayout uaCustomLayout;
    private TextInputEditText uaCustomInput;
    private MaterialCheckBox proxyCheck;
    private TextView proxySummary;

    private static final long PROBE_DELAY_MS = 700L;

    private TextView probeStatus;
    private String probedMime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService probeExecutor = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "edm-probe");
        thread.setDaemon(true);
        return thread;
    });
    private int probeGeneration;
    private final Runnable probeTask = this::startProbe;

    private ActivityResultLauncher<Uri> folderPicker;

    public static AddDownloadSheet newInstance(String url, String name, String mime,
                                               String referer, String userAgent, String cookie) {
        AddDownloadSheet sheet = new AddDownloadSheet();
        Bundle args = new Bundle();
        args.putString(ARG_URL, url);
        args.putString(ARG_NAME, name);
        args.putString(ARG_MIME, mime);
        args.putString(ARG_REFERER, referer);
        args.putString(ARG_UA, userAgent);
        args.putString(ARG_COOKIE, cookie);
        sheet.setArguments(args);
        return sheet;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        folderPicker = registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(),
                uri -> {
                    if (uri == null || !isAdded()) {
                        return;
                    }
                    try {
                        requireContext().getContentResolver().takePersistableUriPermission(uri,
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                        DownloadEngine.get(requireContext()).settings().setDirUri(uri.toString());
                        updateFolderLabel();
                        Toast.makeText(requireContext(), R.string.folder_changed,
                                Toast.LENGTH_SHORT).show();
                    } catch (Exception e) {
                        Toast.makeText(requireContext(), R.string.folder_pick_failed,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.sheet_add_download, container, false);
        urlLayout = view.findViewById(R.id.urlLayout);
        urlInput = view.findViewById(R.id.url);
        nameInput = view.findViewById(R.id.name);
        folderView = view.findViewById(R.id.folder);
        probeStatus = view.findViewById(R.id.probeStatus);
        uaGroup = view.findViewById(R.id.uaGroup);
        uaCustomLayout = view.findViewById(R.id.uaCustomLayout);
        uaCustomInput = view.findViewById(R.id.uaCustomInput);
        proxyCheck = view.findViewById(R.id.proxyCheck);
        proxySummary = view.findViewById(R.id.proxySummary);
        bindNetworkOptions();

        Bundle args = getArguments();
        String initialUrl = args == null ? null : args.getString(ARG_URL);
        String initialName = args == null ? null : args.getString(ARG_NAME);
        if (initialUrl != null && !initialUrl.isEmpty()) {
            urlInput.setText(initialUrl);
        }
        if (initialName != null && !initialName.isEmpty()) {
            nameInput.setText(initialName);
            nameTouched = true;
        } else if (initialUrl != null && !initialUrl.isEmpty()) {
            nameInput.setText(HttpProbe.guessFileName(normalize(initialUrl)));
        }

        urlInput.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!nameTouched) {
                    String url = normalize(s.toString().trim());
                    setNameText(url.isEmpty() ? "" : HttpProbe.guessFileName(url));
                }
                scheduleProbe();
            }
        });
        if (initialUrl != null && !initialUrl.isEmpty()) {
            handler.postDelayed(probeTask, PROBE_DELAY_MS);
        }
        nameInput.addTextChangedListener(new SimpleWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (!updatingName) {
                    nameTouched = true;
                }
            }
        });
        view.findViewById(R.id.folderRow).setOnClickListener(v ->
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.dialog_folder_title)
                        .setMessage(R.string.folder_picker_hint)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.settings_folder_choose, (d, w) ->
                                folderPicker.launch(FileUtils.initialTreeUri()))
                        .show());
        view.findViewById(R.id.cancel).setOnClickListener(v -> dismiss());
        view.findViewById(R.id.start).setOnClickListener(v -> startDownload());

        updateFolderLabel();
        SystemBars.padBottom(view);
        urlInput.requestFocus();
        return view;
    }

    private void bindNetworkOptions() {
        Settings settings = DownloadEngine.get(requireContext()).settings();
        int uaMode = settings.uaMode();
        uaGroup.check(uaMode == Settings.UA_DESKTOP ? R.id.uaDesktop
                : uaMode == Settings.UA_CUSTOM ? R.id.uaCustom : R.id.uaMobile);
        uaCustomInput.setText(settings.uaCustom());
        uaCustomLayout.setVisibility(uaMode == Settings.UA_CUSTOM ? View.VISIBLE : View.GONE);
        uaGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (isChecked) {
                uaCustomLayout.setVisibility(checkedId == R.id.uaCustom ? View.VISIBLE : View.GONE);
                probeNow();
            }
        });

        // Proxy details live in Settings; the sheet only offers a per-download switch.
        boolean hasProxy = settings.hasProxy();
        proxyCheck.setChecked(settings.proxyDefault() && hasProxy);
        proxyCheck.setEnabled(hasProxy);
        proxySummary.setText(hasProxy
                ? getString(R.string.proxy_using, settings.proxyAddress())
                : getString(R.string.proxy_not_configured));
        proxyCheck.setOnCheckedChangeListener((button, checked) -> probeNow());
    }

    private int selectedUaMode() {
        int checked = uaGroup.getCheckedButtonId();
        if (checked == R.id.uaDesktop) {
            return Settings.UA_DESKTOP;
        }
        if (checked == R.id.uaCustom) {
            return Settings.UA_CUSTOM;
        }
        return Settings.UA_MOBILE;
    }


    private void updateFolderLabel() {
        Settings settings = DownloadEngine.get(requireContext()).settings();
        String dirUri = settings.dirUri();
        if (dirUri == null) {
            folderView.setText(R.string.settings_folder_default);
        } else {
            folderView.setText(FileUtils.folderLabel(requireContext(), dirUri));
        }
    }

    private void startDownload() {
        String url = normalize(text(urlInput).trim());
        if (!FileUtils.isHttp(url)) {
            urlLayout.setError(getString(R.string.invalid_url));
            return;
        }
        urlLayout.setError(null);
        int uaMode = selectedUaMode();
        String customUa = text(uaCustomInput).trim();
        if (uaMode == Settings.UA_CUSTOM && customUa.isEmpty()) {
            uaCustomLayout.setError(getString(R.string.ua_custom_required));
            return;
        }
        uaCustomLayout.setError(null);

        Settings settings = DownloadEngine.get(requireContext()).settings();
        boolean useProxy = proxyCheck.isChecked() && settings.hasProxy();
        String proxyHost = useProxy ? settings.proxyHost() : null;
        int proxyPort = useProxy ? settings.proxyPort() : 0;

        Bundle args = getArguments();
        String mime = args == null ? null : args.getString(ARG_MIME);
        if ((mime == null || mime.isEmpty()) && probedMime != null && !probedMime.isEmpty()) {
            mime = probedMime;
        }
        String referer = args == null ? null : args.getString(ARG_REFERER);
        String cookie = args == null ? null : args.getString(ARG_COOKIE);
        DownloadTask task = DownloadEngine.get(requireContext())
                .add(url, text(nameInput).trim(), mime, referer, cookie, uaMode, customUa,
                        useProxy, settings.proxyType(), proxyHost, proxyPort);
        if (task == null) {
            urlLayout.setError(getString(R.string.invalid_url));
            return;
        }
        Toast.makeText(requireContext(), R.string.download_queued, Toast.LENGTH_SHORT).show();
        dismiss();
    }

    private void scheduleProbe() {
        handler.removeCallbacks(probeTask);
        probedMime = null;
        handler.postDelayed(probeTask, PROBE_DELAY_MS);
    }

    /** Restarts the check right away, after a proxy or UA switch. */
    private void probeNow() {
        handler.removeCallbacks(probeTask);
        probedMime = null;
        handler.post(probeTask);
    }

    /** Checks the link in the background and fills in its real size and file name. */
    private void startProbe() {
        if (!isAdded() || urlInput == null) {
            return;
        }
        String url = normalize(text(urlInput).trim());
        if (!FileUtils.isHttp(url)) {
            showProbe(null, false);
            return;
        }
        final int generation = ++probeGeneration;
        HttpProbe.cancel();
        showProbe(getString(R.string.probe_checking), false);

        DownloadTask probe = new DownloadTask();
        probe.url = url;
        Bundle args = getArguments();
        if (args != null) {
            probe.referer = args.getString(ARG_REFERER);
            probe.cookie = args.getString(ARG_COOKIE);
        }
        int uaMode = selectedUaMode();
        probe.uaMode = uaMode;
        probe.userAgent = HttpProbe.userAgent(uaMode, text(uaCustomInput).trim());
        Settings settings = DownloadEngine.get(requireContext()).settings();
        if (proxyCheck.isChecked() && settings.hasProxy()) {
            probe.proxy = true;
            probe.proxyType = settings.proxyType();
            probe.proxyHost = settings.proxyHost();
            probe.proxyPort = settings.proxyPort();
        }

        probeExecutor.execute(() -> {
            try {
                HttpProbe.Info info = HttpProbe.inspect(probe);
                handler.post(() -> applyProbe(generation, info));
            } catch (IOException e) {
                handler.post(() -> {
                    if (generation != probeGeneration || !isAdded()) {
                        return;
                    }
                    showProbe(getString(R.string.probe_error, describeProbeError(e)), true);
                });
            }
        });
    }

    private void applyProbe(int generation, HttpProbe.Info info) {
        if (generation != probeGeneration || !isAdded()) {
            return;
        }
        String size = info.total > 0 ? Fmt.bytes(info.total)
                : getString(R.string.probe_size_unknown);
        String mode = info.ranges ? getString(R.string.probe_multithread)
                : getString(R.string.probe_single);
        showProbe(getString(R.string.probe_size, size) + " · " + mode, false);
        probedMime = info.mime;
        if (!nameTouched && info.fileName != null && !info.fileName.isEmpty()) {
            setNameText(info.fileName);
        }
    }

    private void setNameText(String name) {
        updatingName = true;
        nameInput.setText(name);
        updatingName = false;
    }

    private void showProbe(String text, boolean error) {
        if (probeStatus == null) {
            return;
        }
        if (text == null || text.isEmpty()) {
            probeStatus.setVisibility(View.GONE);
            return;
        }
        probeStatus.setVisibility(View.VISIBLE);
        probeStatus.setText(text);
        probeStatus.setTextColor(ContextCompat.getColor(requireContext(),
                error ? R.color.edm_error : R.color.edm_text_secondary));
    }

    private String describeProbeError(IOException e) {
        if (e instanceof UnknownHostException) {
            return getString(R.string.probe_err_host);
        }
        if (e instanceof SocketTimeoutException) {
            return getString(R.string.probe_err_timeout);
        }
        if (e instanceof SSLException) {
            return getString(R.string.probe_err_ssl);
        }
        String message = e.getMessage();
        return message == null || message.isEmpty() ? e.getClass().getSimpleName() : message;
    }

    @Override
    public void onDestroyView() {
        handler.removeCallbacks(probeTask);
        HttpProbe.cancel();
        super.onDestroyView();
    }

    private static String normalize(String url) {
        if (url.isEmpty() || url.contains("://")) {
            return url;
        }
        return "https://" + url;
    }

    private static String text(EditText editText) {
        Editable editable = editText.getText();
        return editable == null ? "" : editable.toString();
    }

    private abstract static class SimpleWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {
        }
    }
}