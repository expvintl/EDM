# EDM

**A multi-threaded download manager for Android, built with Material Design 2.**

[简体中文](README.zh-CN.md) · [Download](#download) · [Features](#features) · [Build](#building-from-source)

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Version 1.0.0](https://img.shields.io/badge/version-1.0.0-blue)
![License MIT](https://img.shields.io/badge/license-MIT-green)

EDM is a lightweight, ad-free Android download manager. It splits a file into up to 32
parallel HTTP range requests, resumes interrupted transfers, and accepts links handed over
from browsers, so downloads no longer have to go through a browser's own downloader. The
interface follows Material Design 2 and supports light, dark, and system themes.

> EDM downloads **direct HTTP/HTTPS links**. It does not extract media from streaming
> platforms or DRM-protected services.

| | |
|---|---|
| **Version** | 1.0.0 |
| **Requires** | Android 8.0 (API 26) or newer |
| **Package** | `com.edm.downloader` |
| **APK size** | ~4.3 MB |
| **License** | MIT |

## Features

- **Parallel downloads** — split a file into 1–32 range requests; run up to 5 downloads at
  the same time. Servers without `Range` support are downloaded as a single stream.
- **Resume and retry** — continue interrupted transfers from the last checkpoint. Failed
  downloads can be retried automatically with a configurable delay and attempt count.
- **Browser handoff** — share a link to EDM, or open it with EDM from a browser's
  "Open with" menu. Typed and untyped HTTP(S) links, as well as `content://`, `blob:`,
  `data:`, and `file://` handoffs, are supported.
- **Flexible storage** — save to the app folder by default, or pick any folder through the
  Storage Access Framework. The destination can be changed per unfinished task.
- **Preallocation** — optionally create the destination at its final size and write
  segments in place. Downloads that must be restarted after a folder change are reported
  explicitly instead of leaving a corrupt file.
- **Link inspector** — resolve the final filename, size, and range support before starting,
  and surface HTTP errors early.
- **Network controls** — Wi-Fi-only mode, optional HTTP/HTTPS CONNECT/SOCKS5 proxy, and
  mobile, desktop, or custom User-Agent strings.
- **Task management** — search, sort, All/Active/Done tabs, multi-select cleanup,
  redownload in place, open, and share.
- **Material Design 2** — light/dark/system theme, bottom navigation, progress
  notifications, and aggregate progress while several tasks are running.
- **Bilingual UI** — English and Simplified Chinese. The app follows the system language by
  default; the language can also be overridden in Settings.
- **No ads, no analytics, no telemetry.**

## Download

Get the latest APK from the [Releases](https://github.com/expvintl/EDM/releases/latest)
page, then install it on a device running Android 8.0 or newer. You may need to allow
"Install unknown apps" for the app you use to open the APK.

The release APK is signed. The signing certificate is stable across versions, so newer
builds can be installed over older ones; Android may refuse a build with a lower
`versionCode`, in which case uninstall the old app first or use
`adb install -r -d EDM-*.apk`.

## Getting started

1. Open EDM and tap the **+** button.
2. Paste a direct link, or share one from a browser.
3. Wait for the link inspector to resolve the filename and size, then adjust the filename,
   thread count, or folder if needed.
4. Tap **Download**. Progress is shown in the task list and in the notification.
5. Finished files can be opened or shared from the task menu.

Settings contains the download folder, thread count, concurrency, Wi-Fi-only mode,
preallocation, automatic retries, proxy, User-Agent, theme, and language.

## Browser integration

EDM registers both typed and untyped HTTP(S) handlers:

- The **typed** `*/*` filter and file-extension patterns let browsers that resolve a
  download's MIME type or filename offer EDM as a downloader.
- The **untyped** handler covers browsers that hand over a link without a MIME type, such
  as Firefox's download chooser.
- A separate handler accepts `content://`, `blob:`, `data:`, and `file://` URIs from apps
  that forward an already-opened download.

If a browser does not list EDM, use its **Share** or **Open with** action instead. Some
browsers only expose their own downloader and cannot hand links to third-party apps.

## Building from source

Requirements:

- JDK 17 or newer
- Android SDK Platform 35 and Build Tools 35.0.0
- Gradle 8.11.1 (the repository does not include a Gradle wrapper)

```bash
git clone https://github.com/expvintl/EDM.git
cd EDM
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
gradle assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

### Release signing

The signing key and its passwords are not part of this repository. To build a signed
release APK locally, put the keystore at `keystore/edm-release.jks` and create
`keystore/signing.properties`:

```properties
storePassword=your-store-password
keyPassword=your-key-password
keyAlias=your-key-alias
```

The same values can be supplied through the `EDM_STORE_PASSWORD`, `EDM_KEY_PASSWORD`, and
`EDM_KEY_ALIAS` environment variables. Without a keystore, `assembleRelease` still builds,
but the APK is unsigned.

## How it works

- `DownloadService` is a foreground service that owns the download engine and keeps
  transfers alive while the app is in the background.
- `DownloadEngine` schedules tasks, enforces the concurrency limit, and persists
  checkpoints every two seconds.
- `HttpProbe` resolves metadata (final URL, filename, size, ETag, range support) before a
  task starts.
- `TaskWorker` and `SegmentRunner` download byte ranges in parallel. Each segment is
  written to its own temporary file and merged when the task finishes.
- `SafSink` stores downloads in a user-selected folder. In preallocation mode it writes
  directly into a reserved document; otherwise it merges temporary segment files into the
  destination.
- `DownloadStore` keeps task metadata and segment checkpoints in SQLite.

## Permissions

| Permission | Purpose |
|---|---|
| `INTERNET` | Download files and resolve links. |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Wi-Fi-only mode and connectivity checks. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WAKE_LOCK` | Keep downloads running while the app is in the background. |
| `POST_NOTIFICATIONS` | Show progress notifications and pause/resume/cancel actions. |
| `REQUEST_INSTALL_PACKAGES` | Open downloaded APK files with the system installer. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Offer the optional battery-optimization exemption for long downloads. |

EDM does not request storage permissions; files are written through the Storage Access
Framework or to the app-specific folder.

## Privacy

EDM collects no analytics, crash reports, or advertising identifiers. Download history,
settings, and partial files stay on the device. A download URL is sent only to the server
it points to, and to the proxy you configure. Uninstalling the app removes files stored in
its app-specific folder; files saved in a user-selected folder are left untouched.

## FAQ

**Does EDM support YouTube or other streaming sites?**
No. EDM downloads direct HTTP/HTTPS links and does not bypass DRM or extract streams from
web players.

**Why did a download restart after I changed the folder?**
A preallocated download keeps its bytes inside a document in the old folder. That document
cannot be moved to another folder, so EDM deletes it and starts the transfer again, with a
message explaining why. Downloads using the temporary-file merge path can keep their
progress.

**A server ignores range requests. What happens?**
EDM downloads the file as a single stream and marks the task as non-resumable. Pausing and
resuming such a task starts it over.

**Downloads pause on mobile data.**
Check **Wi-Fi only** in Settings.

**How do I change the app language on Android 13+?**
Use **Settings → Language** inside EDM, or the system's per-app language page
(**Settings → Apps → EDM → Language**). The default is to follow the system language.

## Contributing

Bug reports and pull requests are welcome. Please describe the Android version, the URL
scheme (not the full private URL), and the steps to reproduce. New translations can be
added as `app/src/main/res/values-<locale>/strings.xml`; keep the existing keys unchanged.

## License

MIT License. See [LICENSE](LICENSE).

Copyright (c) 2026 expvintl.

## Acknowledgements

Built with [AndroidX](https://developer.android.com/jetpack/androidx) and
[Material Components for Android](https://github.com/material-components/material-components-android).
EDM is an independent project and is not affiliated with ADM, 1DM, IDM, or any browser
vendor.
