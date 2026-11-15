package com.edm.downloader;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.edm.downloader.util.FileUtils;
import com.edm.downloader.util.Fmt;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Catches browser hand-offs that are not plain http(s) links.
 *
 * <p>Firefox's media viewer hands images and videos to downloaders with a temporary
 * {@code blob:}, {@code data:} or empty URI. Those addresses only exist inside the browser
 * process, so they cannot be downloaded here; this screen explains that instead of
 * silently dropping the request. Real http(s) hand-offs are forwarded to the main screen.
 */
public class HandoffActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        Uri data = intent.getData();
        String scheme = data == null ? null : data.getScheme();

        if (data != null && FileUtils.isHttp(data.toString())) {
            Intent forward = new Intent(this, MainActivity.class)
                    .setAction(Intent.ACTION_VIEW)
                    .setData(data)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            if (intent.getType() != null) {
                forward.setType(intent.getType());
            }
            startActivity(forward);
            finish();
            return;
        }

        String type = intent.getType();
        String raw = data == null ? "" : data.toString();
        String shown = raw.isEmpty() ? "—" : Fmt.truncate(raw, 200);
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.handoff_title)
                .setMessage(getString(R.string.handoff_message,
                        scheme == null ? "—" : scheme,
                        type == null ? "—" : type,
                        shown))
                .setPositiveButton(R.string.action_ok, (dialog, which) -> finish())
                .setOnDismissListener(dialog -> finish());
        if (!raw.isEmpty()) {
            builder.setNeutralButton(R.string.action_copy_link, (dialog, which) -> {
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                if (clipboard != null) {
                    clipboard.setPrimaryClip(ClipData.newPlainText("link", raw));
                    Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
                }
                finish();
            });
        }
        builder.show();
    }
}
