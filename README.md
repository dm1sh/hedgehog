# Hedgehog (EdgeTTS Native Android Reimplementation)

Native Android reimplementation of **EdgeTTS** built with **Kotlin** and **Jetpack Compose** (`dm1sh.hedgehog`), faithfully porting the original Godot 4.6 application and its custom Android plugins into a clean, single-process native Android codebase.

## Features

- **Online Microsoft Edge Neural TTS**:
  - Direct WebSocket synthesis (`wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1`) with bit-exact `Sec-MS-GEC` (`2-143.0.3650.75`) security token generation and automatic server clock-skew synchronization.
  - Multi-threaded concurrent part synthesis (`1..100` streams) with configurable retry delay (`5s..300s` or `∞`) and `48 kbps` / `96 kbps` 24kHz mono MP3 output.
  - Online voice list updater (`edge.microsoft.com` endpoint).
- **Offline Embedded Neural TTS**:
  - Full integration with `com.microsoft.cognitiveservices.speech:client-sdk-embedded:1.40.0` and `de.sciss:jump3r:1.0.5` LAME MP3 encoder (`24 kHz`, `48/96 kbps` mono).
  - One-click downloader & installer for offline voice models (`offline_voices_3.json` from `edgetts.github.io/assets/offline/`) into `EdgeTTS/offline/models/`.
- **Book & Document Processing**:
  - Native parsers for `.txt`, `.ini`, `.fb2`, `.epub`, `.csv`, and `.zip` archives containing books, with automatic UTF-8 and Windows-1251 (CP1251) encoding detection.
  - `.lexx` pronunciation & regex dictionary support (`regex"=replacement`, case-sensitive `#` rules, whole-word vs. substring replacements).
  - Chapter splitting by customizable regular expression, optional chapter intro audio (`glav.mp3`), chapter titles in output filenames, and configurable MP3 chunk merging (`1..99` parts or `All`).
- **Original Dark UI in Jetpack Compose**:
  - Faithful recreation of the Godot dark UI (`CenterMenu`, `Group1`–`Group8`, bottom icon toolbar, `DopMenu` modals, full-screen/split-screen Part Editor with live preview, and interactive `StatusGrid` Canvas with per-part status colors and tooltips).
  - 12-language localization (`localization/translations.csv`: Auto, English, Russian, Ukrainian, Belarusian, Kazakh, German, French, Spanish, Italian, Portuguese, Chinese, Turkish).
  - Built-in UI Screen Reader (`TextToSpeech`) for accessibility.
- **Background Execution & Storage**:
  - `ForegroundWorkService` with partial `WakeLock` and ongoing notification (`dataSync`) so book synthesis continues reliably while the screen is off.
  - `EdgeTTS/Trash` recycle bin support and `MANAGE_EXTERNAL_STORAGE` + Android Storage Access Framework (SAF) file picker integration.

## Building Debug APKs

A GitHub Actions workflow (`.github/workflows/build-debug.yml`) automatically builds `app-debug.apk` on every push to `main`/`master` or via manual `workflow_dispatch` and uploads it as the `hedgehog-debug-apk` artifact.

To build locally with Android SDK and JDK 17:

```bash
./gradlew assembleDebug
```
