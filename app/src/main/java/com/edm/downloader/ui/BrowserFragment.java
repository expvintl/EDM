package com.edm.downloader.ui;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.DownloadListener;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.edm.downloader.R;
import com.edm.downloader.engine.DownloadEngine;
import com.edm.downloader.data.Settings;
import com.edm.downloader.engine.HttpProbe;
import com.edm.downloader.util.FileUtils;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Built-in browser: web pages load normally, file links are captured by EDM. */
public class BrowserFragment extends Fragment {

    private static final String HOME_URL = "https://www.bing.com";

    private WebView webView;
    private EditText urlInput;
    private ProgressBar progress;
    private View home;
    private ImageButton back;
    private ImageButton forward;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_browser, container, false);
        webView = view.findViewById(R.id.webView);
        urlInput = view.findViewById(R.id.url);
        progress = view.findViewById(R.id.progress);
        home = view.findViewById(R.id.home);
        back = view.findViewById(R.id.back);
        forward = view.findViewById(R.id.forward);

        CookieManager.getInstance().setAcceptCookie(true);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        settings.setUserAgentString(settings.getUserAgentString() + " EDM/1.0");

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return intercept(request.getUrl());
            }

            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                home.setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(0);
                if (url != null) {
                    urlInput.setText(url);
                }
                updateNav();
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (url != null) {
                    urlInput.setText(url);
                }
                updateNav();
            }

            @Override
            public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                updateNav();
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler,
                                           android.net.http.SslError error) {
                handler.cancel();
                toast(R.string.browser_ssl_error);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    progress.setVisibility(View.GONE);
                    toast(R.string.browser_error);
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
                progress.setProgress(newProgress);
            }
        });

        webView.setDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(String url, String userAgent, String contentDisposition,
                                        String mimeType, long contentLength) {
                String name = HttpProbe.parseFileName(contentDisposition);
                if (name == null) {
                    name = HttpProbe.guessFileName(url);
                }
                String mime = HttpProbe.mimeOf(mimeType);
                startDownload(url, name, mime, webView.getUrl(),
                        CookieManager.getInstance().getCookie(url));
            }
        });

        webView.setOnLongClickListener(v -> showLinkOptions());
        back.setOnClickListener(v -> {
            if (webView.canGoBack()) {
                webView.goBack();
            }
        });
        forward.setOnClickListener(v -> {
            if (webView.canGoForward()) {
                webView.goForward();
            }
        });
        view.findViewById(R.id.refresh).setOnClickListener(v -> {
            String typed = urlInput.getText().toString().trim();
            if (!typed.isEmpty() && !typed.equals(webView.getUrl())) {
                loadFromBar();
            } else {
                webView.reload();
            }
        });
        urlInput.setOnEditorActionListener((v, actionId, event) -> {
            loadFromBar();
            return true;
        });
        updateNav();
        return view;
    }

    private boolean intercept(Uri uri) {
        if (uri == null) {
            return false;
        }
        String scheme = uri.getScheme();
        if (scheme == null
                || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        String url = uri.toString();
        if (FileUtils.isDownloadUrl(url)) {
            startDownload(url, HttpProbe.guessFileName(url), null, webView.getUrl(),
                    CookieManager.getInstance().getCookie(url));
            return true;
        }
        return false;
    }

    private void startDownload(String url, String name, String mime, String referer,
                               String cookie) {
        if (!FileUtils.isHttp(url)) {
            return;
        }
        DownloadEngine engine = DownloadEngine.get(requireContext());
        Settings settings = engine.settings();
        boolean useProxy = settings.proxyDefault() && settings.hasProxy();
        if (engine.add(url, name, mime, referer, cookie, settings.uaMode(), settings.uaCustom(),
                useProxy, settings.proxyType(), settings.proxyHost(), settings.proxyPort()) != null) {
            toast(R.string.browser_download_started);
        }
    }

    private void loadFromBar() {
        String text = urlInput.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        String url = text;
        if (!url.contains("://")) {
            boolean looksLikeHost = !url.contains(" ") && url.contains(".");
            url = looksLikeHost ? "http://" + url
                    : "https://www.bing.com/search?q=" + Uri.encode(url);
        }
        home.setVisibility(View.GONE);
        webView.loadUrl(url);
        hideKeyboard();
    }

    private boolean showLinkOptions() {
        WebView.HitTestResult result = webView.getHitTestResult();
        String url = result.getExtra();
        if (url == null) {
            return false;
        }
        int type = result.getType();
        boolean anchor = type == WebView.HitTestResult.ANCHOR_TYPE
                || type == WebView.HitTestResult.SRC_ANCHOR_TYPE
                || type == WebView.HitTestResult.IMAGE_TYPE
                || type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                || type == WebView.HitTestResult.IMAGE_ANCHOR_TYPE;
        if (!anchor) {
            return false;
        }
        String lower = url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
            return false;
        }

        final List<String> labels = new ArrayList<>();
        final List<Integer> actions = new ArrayList<>();
        labels.add(getString(R.string.browser_long_press_copy));
        actions.add(0);
        labels.add(getString(R.string.browser_long_press_open));
        actions.add(1);
        if (FileUtils.isDownloadUrl(url)) {
            labels.add(getString(R.string.browser_long_press_download));
            actions.add(2);
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(FileUtils.host(url))
                .setItems(labels.toArray(new String[0]), (dialog, which) -> {
                    int action = actions.get(which);
                    if (action == 0) {
                        copy(url);
                    } else if (action == 1) {
                        webView.loadUrl(url);
                    } else {
                        startDownload(url, HttpProbe.guessFileName(url), null, webView.getUrl(),
                                CookieManager.getInstance().getCookie(url));
                    }
                })
                .show();
        return true;
    }

    private void copy(String text) {
        ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard != null) {
            clipboard.setPrimaryClip(ClipData.newPlainText("url", text));
            toast(R.string.copied_to_clipboard);
        }
    }

    private void updateNav() {
        back.setEnabled(webView.canGoBack());
        back.setAlpha(webView.canGoBack() ? 1f : 0.4f);
        forward.setEnabled(webView.canGoForward());
        forward.setAlpha(webView.canGoForward() ? 1f : 0.4f);
    }

    private void hideKeyboard() {
        android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager)
                        requireContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) {
            imm.hideSoftInputFromWindow(urlInput.getWindowToken(), 0);
        }
        urlInput.clearFocus();
    }

    public boolean canGoBack() {
        return webView != null && webView.canGoBack();
    }

    public void goBack() {
        if (webView != null) {
            webView.goBack();
        }
    }

    public void loadUrl(String url) {
        if (webView != null && url != null) {
            home.setVisibility(View.GONE);
            webView.loadUrl(url);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
        }
    }

    @Override
    public void onPause() {
        if (webView != null) {
            webView.onPause();
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        if (webView != null) {
            webView.setWebViewClient(null);
            webView.setWebChromeClient(null);
            webView.destroy();
            webView = null;
        }
        super.onDestroyView();
    }

    private void toast(int res) {
        if (isAdded()) {
            Toast.makeText(requireContext(), res, Toast.LENGTH_SHORT).show();
        }
    }
}