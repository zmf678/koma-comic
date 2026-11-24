# 页光 Koma

<p>
  <img src="app/src/main/res/drawable-nodpi/ic_koma_logo.png" alt="KomaComic Icon" width="96" />
</p>

[中文](README.md) | **English**

**Koma** is an open-source online-source, local, and WebDAV comic/manga reader built for Windows and Android tablets and phones. Named after the comic panel (*Koma*), it features a built-in Venera-compatible JavaScript comic source engine, adaptive portrait/landscape double-page reading on large screens, and an edge-to-edge distraction-free interface—no ads, no expiration popups, and no third-party tracking SDKs.

> Package ID: `com.arkreader.comic` · Minimum SDK: Android 9.0 (API 28) · Target SDK: Android 14 (API 34)

---

## Features

### 1. Built-in Venera-Compatible Comic Sources & Collections (`作品集`)
- **Built-in JS Comic Source Runtime & Repository Subscription**:
  - Zero-dependency Android `WebView` / Windows `WebView2` + native HTTP/Cookie/Crypto/DOM bridge compatible with standard Venera / Venera-Next `ComicSource` JS scripts and `index.json` repositories.
  - Includes 65 sources. Komiic chapter reading requires a source account. Source management and sign-in are available in Settings.
  - Home remembers the last source, shows channels as horizontal tabs, and loads the next page as you scroll. Online favorites retain cached covers and summaries.
- **Collections & Privacy Lock**:
  - Group imported comics into local Collections (`作品集`), optionally hiding private collections from the Local gallery.
  - Protect any collection with a **Numeric PIN** or system **`BiometricPrompt` fingerprint lock** (with optional 2-second quick relock).

### 2. Multi-Source Local / WebDAV Libraries & Formats
- **Local Directories (SAF)**: Persistent Storage Access Framework (`OpenDocumentTree`) permissions for internal storage and SD cards, with optional `.nomedia` creation and custom cover filenames.
- **WebDAV Libraries**: Built-in lightweight WebDAV client (Basic Auth and connection testing) to scan and stream remote comic folders, archives, and PDFs.
- **Supported Formats**:
  - Online comic source streams (`ComicFormat.ONLINE`)
  - Local / remote image folders (`jpg` / `jpeg` / `png` / `webp` / `bmp` / `gif`)
  - Archive & e-book comics (`ZIP` / `CBZ` / `EPUB`)
  - Native PDF rendering via Android `PdfRenderer` with adjustable render scale

### 3. Immersive Reader & Floating Capsule Menu
- **Edge-to-Edge Fullscreen with Transparent Status Bar Reveal**: Hides system status and navigation bars completely during reading, revealing a clean transparent status bar over a subtle top gradient only when tapping the center of the screen to open the menu.
- **Floating Rounded Capsule Menu & Spread Pairing Pill**: Minimalist dark floating bottom capsule (progress seek bar, previous/next chapter, hexagon reader settings, bookmarks, chapters directory) topped by the **Change Spread Pairing (`更改跨页匹配`)** floating pill.
- **Portrait Single Page / Landscape Double Spread (`AUTO_LANDSCAPE`)**: Automatically switches between single-page portrait and side-by-side double-page landscape spreads while preserving reading position on rotation.
- **Customizable Per-Comic Reader Profiles**: Vertical continuous scroll, Horizontal LTR, Horizontal RTL, pinch/double-tap zoom, customizable tap zones, volume-key flipping, smart white-margin trimming, blank-page detection, and bookmarks.

### 4. Translation and Appearance

Choose a local wallpaper and optionally enable the translucent glass material in Settings.

- **Direct API.** On Windows or Android, enter the provider's OpenAI-compatible API address and API key, then fetch the model list or enter a model ID. Enable image input to translate pages with a vision model and render the result on the device. With image input disabled, paste or type the source text in the reader. Reasoning effort defaults to the provider's setting.
- **Existing translation server.** The server-default option still accepts [manga-image-translator](https://github.com/zyddnys/manga-image-translator) services. Custom text and vision modes still support [manga-translator-ui](https://github.com/hgmzhn/manga-translator-ui) services with per-request model settings.

Test the configuration before saving. The reader's Translate button opens a floating translation control with a seven-day token chart and pretranslation settings. Pretranslation is off by default and can cover the next 1–5 pages in the current chapter. The chart uses token counts reported by the API and includes reasoning tokens within output tokens.

The profile shows the last seven days of reading. Select a date to see the works read that day, or switch to all-time totals. Feedback creates a GitHub issue in the app and shows its number. It requires a GitHub token with permission to create issues for this repository, which can be saved locally in encrypted form. Browsing state is restored when returning to a source in the same session.


---

## Project Structure

```text
koma-comic/
├── app/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/                             # Clean-room Venera JS host shim & built-in comic sources
│       │   ├── java/com/arkreader/comic/
│       │   │   ├── ArkReaderApp.kt                 # Application entry point & singletons (DB / Prefs / Cache / VeneraRuntime)
│       │   │   ├── data/                           # Models, SQLite DatabaseHelper & SharedPreferences
│       │   │   ├── engine/                         # Venera JS runtime, repo client, WebDAV/PDF/ZIP & spread engine
│       │   │   └── ui/                             # Home feeds, source manager, detail view, reader, collections & settings UIs
│       │   └── res/                                # Tablet/phone responsive layouts, themes & icon assets
│       └── test/java/com/arkreader/comic/
│           └── CoreEngineTest.kt                   # Core engine & script parser unit tests
├── windows/                                        # Native Windows desktop host (WPF DirectX + Per-Monitor V2 High-DPI + background JS engine)
│   ├── Program.cs
│   ├── app.manifest
│   └── build-windows.ps1
├── build.gradle.kts
├── gradle.properties
├── settings.gradle.kts
└── LICENSE
```

---

## Build & Installation

### Windows Desktop (Portable x64)

#### Prerequisites
- Windows 10 / 11 (x64) with built-in .NET Framework 4.x and Microsoft Edge WebView2 Runtime

#### Build Steps
```powershell
powershell -ExecutionPolicy Bypass -File windows/build-windows.ps1
# Or via Gradle:
gradle assembleWindows
```
Portable output: `build/windows/KomaComic-windows-x64/KomaComic.exe`  
Release archive: `build/release/KomaComic-v1.5.7-windows-x64.zip`

---

### Android Prerequisites
- **JDK**: Java 17 or Java 21
- **Android SDK**: Compile/Target SDK 34 (`platforms;android-34`, `build-tools;34.0.0`)
- **Gradle**: 8.7+

### Steps

1. Create `local.properties` in the project root pointing to your Android SDK (Windows example):
   ```properties
   sdk.dir=D\:\\Android
   ```
2. Run unit tests and build the Debug APK:
   ```bash
   gradle testDebugUnitTest assembleDebug --no-daemon
   ```
   Output APK: `app/build/outputs/apk/debug/app-debug.apk`
3. Install onto a connected tablet or phone via ADB:
   ```bash
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

---

## License

Released under the [MIT License](LICENSE).
