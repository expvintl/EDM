# EDM

**Material Design 2 风格的 Android 多线程下载器。**

[English](README.md) · [下载](#下载) · [功能](#功能) · [构建](#从源码构建)

![Android 8.0+](https://img.shields.io/badge/Android-8.0%2B-3DDC84?logo=android&logoColor=white)
![Version 1.0.1](https://img.shields.io/badge/version-1.0.1-blue)
![License MIT](https://img.shields.io/badge/license-MIT-green)

EDM 是一款轻量、无广告的 Android 下载管理器。它会把一个文件拆分为最多 32 个并行 HTTP
Range 请求，支持断点续传，并能接管浏览器交过来的下载链接，让下载不再受限于浏览器自带的下载器。
界面遵循 Material Design 2，提供浅色、深色和跟随系统三种主题。

> EDM 只下载**直接的 HTTP/HTTPS 链接**，不解析流媒体平台，也不绕过 DRM 保护。

| | |
|---|---|
| **版本** | 1.0.1 |
| **系统要求** | Android 8.0（API 26）及以上 |
| **包名** | `com.edm.downloader` |
| **APK 大小** | 约 4.3 MB |
| **许可证** | MIT |

## 功能

- **多线程下载** —— 单个文件可拆分为 1–32 个 Range 请求，最多同时下载 5 个任务。
  服务器不支持 `Range` 时自动退化为单连接下载。
- **断点续传与重试** —— 从上次检查点继续传输；失败任务可按设定的间隔和次数自动重试。
- **浏览器接管** —— 无论浏览器交来的是带 MIME 类型的下载还是只有链接，都可以把 EDM
  列为下载器。EDM 不处理分享、本地文件、附件和普通网页链接，不会出现在分享面板或
  本地文件的“打开方式”里。
- **灵活的保存位置** —— 默认保存到应用目录，也可以通过存储访问框架（SAF）选择任意
  文件夹；未完成的任务可以单独更换保存目录。
- **预分配** —— 可选择按最终大小预先创建目标文件并原地写入分段。换目录后无法迁移的
  预分配任务会明确提示并重新下载，不会留下损坏的文件。
- **链接探测** —— 下载前解析最终文件名、大小和断点续传能力，并尽早暴露 HTTP 错误。
- **网络控制** —— 仅 Wi-Fi 下载、可选的 HTTP/HTTPS CONNECT/SOCKS5 代理，以及移动端、
  桌面端或自定义 User-Agent。
- **任务管理** —— 搜索、排序、全部/进行中/已完成分页、多选清理、原地重新下载、
  打开和分享。
- **Material Design 2** —— 浅色/深色/跟随系统主题、底部导航、进度通知，以及多任务
  同时运行时的汇总进度。
- **中英双语** —— 简体中文和英文；默认跟随系统语言，也可以在设置中手动切换。
- **无广告、无统计、无遥测。**

## 下载

请从 [Releases](https://github.com/expvintl/EDM/releases/latest) 页面获取最新 APK，
然后在 Android 8.0 或更高版本的设备上安装。首次安装时，可能需要在打开 APK 的应用中
允许“安装未知应用”。

Release APK 已签名，且签名证书在各版本之间保持一致，因此新版本可以直接覆盖旧版本安装。
如果 Android 因 `versionCode` 更低而拒绝安装，请先卸载旧版本，或使用
`adb install -r -d EDM-*.apk`。

## 使用

1. 打开 EDM，点击 **+** 按钮。
2. 粘贴直接下载链接，或用内置的 EDM 浏览器打开网页。
3. 等待链接探测解析出文件名和大小，必要时修改文件名、线程数或保存目录。
4. 点击**下载**。进度会显示在任务列表和通知栏中。
5. 下载完成后，可以在任务菜单中打开或分享文件。

设置中可以调整下载目录、线程数、并发任务数、仅 Wi-Fi、预分配、自动重试、代理、
User-Agent、主题和语言。

## 浏览器接管

EDM 可以充当浏览器的下载器，接收两种交接方式：

| 交接方式 | 意图 | 命中条件 |
|---|---|---|
| 浏览器知道文件类型 | 带 MIME 类型的 `http(s)` `VIEW` 意图 | `*/*` 过滤器，与文件名无关 |
| 浏览器只传链接 | 不带类型的 `http(s)` `VIEW` 意图 | `pathPattern` 扩展名清单 |

只要浏览器给出了类型（包括它无法识别文件时使用的 `application/octet-stream`），文件名和
扩展名都不重要。扩展名清单只服务于第二种情况：意图里除了 URL 什么都没有。这也是
`.mp4`、`.pdf`、`.apk`、`.zip`、`.iso` 这类链接会被认领、而普通网页不会的原因。

有两条边界来自 Android 的过滤机制，而不是应用本身的取舍：

- 既没有 MIME 类型、路径也没有已知扩展名的 URL（例如 `/download?id=123`）无法被认领；
- 清单在编译期固定，冷门或自定义扩展名只有在浏览器报告了类型时才会被认领。

EDM 刻意**不**注册以下入口：

- `SEND text/plain`：不会出现在任何分享面板里；
- `content://`、`blob:`、`data:`、`file://` 以及媒体 MIME 过滤器：打开本地文件、
  附件（例如日历邀请）或图片时不会列出 EDM；
- 处理所有链接的通用入口：从其它应用打开链接时不会列出 EDM，除非该 URL 指向可下载的文件。

如果某个浏览器只允许自带下载器，请使用内置 EDM 浏览器，或把链接粘贴到 EDM。

## 从源码构建

环境要求：

- JDK 17 或更高版本
- Android SDK Platform 35 和 Build Tools 35.0.0
- Gradle 8.11.1（仓库未包含 Gradle Wrapper）

```bash
git clone https://github.com/expvintl/EDM.git
cd EDM
echo "sdk.dir=/path/to/Android/Sdk" > local.properties
gradle assembleDebug
```

Debug APK 输出到 `app/build/outputs/apk/debug/app-debug.apk`。

### Release 签名

签名密钥和密码不在本仓库中。要在本地构建已签名的 Release APK，请把密钥库放到
`keystore/edm-release.jks`，并创建 `keystore/signing.properties`：

```properties
storePassword=你的密钥库密码
keyPassword=你的密钥密码
keyAlias=你的密钥别名
```

也可以通过 `EDM_STORE_PASSWORD`、`EDM_KEY_PASSWORD` 和 `EDM_KEY_ALIAS` 环境变量提供
这些值。没有密钥库时，`assembleRelease` 仍可构建，但产物是未签名的 APK。

## 实现概要

- `DownloadService` 是前台服务，持有下载引擎，保证应用退到后台后传输继续进行。
- `DownloadEngine` 负责任务调度、并发限制，并每两秒持久化一次检查点。
- `HttpProbe` 在任务开始前解析元数据（最终 URL、文件名、大小、ETag、Range 支持）。
- `TaskWorker` 与 `SegmentRunner` 并行下载字节区间；每个分段先写入独立的临时文件，
  任务完成后再合并。
- `SafSink` 负责写入用户选择的文件夹。预分配模式下直接写入已预留的文档，否则把临时
  分段文件合并到目标文件。
- `DownloadStore` 使用 SQLite 保存任务元数据和分段检查点。

## 权限

| 权限 | 用途 |
|---|---|
| `INTERNET` | 下载文件、解析链接。 |
| `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE` | 仅 Wi-Fi 模式和网络状态判断。 |
| `FOREGROUND_SERVICE`、`FOREGROUND_SERVICE_DATA_SYNC`、`WAKE_LOCK` | 应用在后台时保持下载运行。 |
| `POST_NOTIFICATIONS` | 显示进度通知以及暂停/继续/取消操作。 |
| `REQUEST_INSTALL_PACKAGES` | 用系统安装器打开已下载的 APK。 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 可选的电池优化豁免，避免长任务被系统中断。 |

EDM 不申请存储权限；文件通过存储访问框架或应用专属目录读写。

## 隐私

EDM 不收集统计信息、崩溃报告或广告标识符。下载历史、设置和未完成的分段文件都保存在
本机。下载 URL 只会发送给该 URL 指向的服务器，以及你配置的代理。卸载应用会删除应用
专属目录中的文件；保存在用户选择目录中的文件不会被删除。

## 常见问题

**EDM 支持 YouTube 或其他流媒体网站吗？**
不支持。EDM 只下载直接的 HTTP/HTTPS 链接，不绕过 DRM，也不从网页播放器中提取流。

**为什么更换保存目录后下载重新开始了？**
预分配下载的字节保存在旧目录的文档中，该文档无法移动到另一个目录。EDM 会删除旧文档并
重新下载，同时给出明确提示。使用临时文件合并路径的下载可以保留进度。

**服务器不支持 Range 请求怎么办？**
EDM 会以单连接方式下载，并把任务标记为不可续传。暂停后再继续会从头开始。

**使用移动数据时下载暂停了。**
请检查设置中的**仅 Wi-Fi**选项。

**Android 13 及以上如何修改应用语言？**
可以在 EDM 内使用**设置 → 语言**，也可以使用系统的应用语言页面
（**设置 → 应用 → EDM → 语言**）。默认跟随系统语言。

## 参与贡献

欢迎提交问题和 Pull Request。请说明 Android 版本、链接类型（不要粘贴包含隐私的完整
URL）以及复现步骤。新增翻译可以放到
`app/src/main/res/values-<locale>/strings.xml`，并保持现有键名不变。

## 许可证

MIT 许可证，详见 [LICENSE](LICENSE)。

Copyright (c) 2026 expvintl.

## 致谢

使用 [AndroidX](https://developer.android.com/jetpack/androidx) 和
[Material Components for Android](https://github.com/material-components/material-components-android)
构建。EDM 是独立项目，与 ADM、1DM、IDM 或任何浏览器厂商无关。
