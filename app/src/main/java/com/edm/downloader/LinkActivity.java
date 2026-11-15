package com.edm.downloader;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Invisible entry point for plain http(s) links that carry no MIME type.
 *
 * <p>Why this exists as a separate activity: Firefox builds its "third-party downloader" list by
 * querying every app that can open a plain {@code http://...} URL (no MIME type) and then
 * <em>removing</em> all of those apps from the downloader list, because it treats them as
 * browsers. If MainActivity itself advertised the untyped link filter, EDM would be classified
 * as a browser and filtered out of Firefox's downloader chooser. Keeping the untyped filter on
 * this forwarding activity leaves MainActivity's typed download filters eligible, while EDM can
 * still be chosen to open a link. 1DM does the same thing with a separate browser activity.</p>
 */
public class LinkActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent incoming = getIntent();
        if (incoming != null && incoming.getData() != null) {
            Intent forward = new Intent(incoming);
            forward.setClass(this, MainActivity.class);
            forward.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(forward);
        }
        finish();
    }
}
