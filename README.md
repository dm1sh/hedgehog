# Hedgehog

**Hedgehog** is a native Android app for turning e-books and text into audiobooks and spoken audio using natural-sounding neural voices. It features a clean Material 3 interface with Material You (Monet) dynamic colors, a built-in sentence-by-sentence player, and an Android system Text-To-Speech service—working both online and completely offline.

---

## What the App Can Do

- **Convert Books to Audiobooks**:
  - Open books in **FB2**, **EPUB**, **TXT**, **CSV**, **INI**, or **ZIP** archives containing books.
  - Automatically detects book metadata (`Author - Book Title`) and text encodings (UTF-8 and Windows-1251).
  - Save audiobooks in **MP3** (`96 kbps` or compact `48 kbps`) or **Opus** (`32 kbps VoIP` for small file sizes).
  - **Embedded Cover Art & Audio Tags**: Automatically extracts cover images and book metadata (title, author, series/album, genre, year, track number) from FB2, EPUB, and ZIP files and embeds them into output `.mp3` (`ID3v2.3` + `APIC`) and `.opus` (`OpusTags` + `METADATA_BLOCK_PICTURE`) files (can be toggled in **Settings → Book**).
- **Built-in Player (`Player` Tab)**:
  - Listen to loaded books or shared text right inside the app with per-sentence synthesis, background pre-caching of upcoming sentences, and seamless sequential playback.
  - Tap any sentence card to start playing immediately from that point, tap the sentence counter to jump to a specific sentence number, or drag the fast scrollbar (with live chapter title preview).
  - **Bookmarks & Progress Persistence**: Tap the bookmark button to bookmark the current sentence, or long-press it to view, jump to, or delete saved bookmarks. Playback position and bookmarks are automatically saved per book.
  - **Share Files or Plain Text**: Share book files or plain text from any app (browsers, messengers, readers) to Hedgehog. Shared text opens directly in the **Player** tab with character normalization, emoji filtering, and `.lexx` dictionary rules applied, and cached audio can be saved to MP3 or Opus.
  - **Background Media Notification**: Control playback (`Previous`, `Play/Pause`, `Next`) and track progress from the Android notification shade.
- **Android System Text-To-Speech (TTS) Provider**:
  - Use Hedgehog as a system-wide TTS engine across Android (**System Settings → Accessibility → Text-to-speech output**) so external book readers and accessibility tools can speak using your configured Hedgehog voices, speed/pitch, `.lexx` dictionary, and emoji filter.
- **Online & Offline Neural Voices**:
  - Choose from hundreds of **Online** Microsoft Edge neural voices across dozens of languages.
  - Download **Offline** voices directly inside the app to convert and listen without an internet connection.
  - Independent toggles in **Settings → Voice** to choose whether **Book conversion**, the **Built-in player**, and **System TTS** use online or offline voices, with automatic fallback to your selected offline voice (and a toast notification) if the network is unavailable.
- **Smart Book & Text Processing**:
  - **Cut by chapters**: Split audiobooks into separate chapter files named after each chapter, and optionally prepend a custom chapter sound (`glav.mp3`).
  - **Inline footnotes**: Optionally embed FB2 and EPUB footnotes directly into the spoken text so notes are read naturally in context.
  - **Merge audio**: Combine parts into a single audiobook file or group them by a custom number of parts per file.
  - **Pronunciation dictionaries (`.lexx`)**: Apply custom word replacements, stress marks, and regex rules before synthesis.
  - **Filter out emojis**: Automatically strip emojis during text normalization (configurable in **Settings → Book**).
- **Preview & Edit Before Converting (`View text` Tab)**:
  - Browse and edit individual text parts, edit chapter titles and skip between chapters, listen to a voice preview of any part, or customize the output book title before saving.
- **Background Conversion**:
  - Converts books in multiple parallel streams with live progress for synthesized parts and postprocessed audio chunks in the status grid, top bar, and system notification—even while your screen is off.

---

## How to Use

### 1. Choose a Voice (`Book` or `Settings` tab)
1. Select your preferred **Online voice** and/or **Offline voice** in **Settings → Voice**.
2. Toggle whether to use online voices for **Book conversion**, the **Built-in player**, and **System TTS**.
3. Adjust **Speed** and **Pitch** if needed.
4. To download offline voices from within the app, open **Settings → Voice** and tap **Get offline voices**.

#### Installing Offline Voices Manually (Completely Offline Setup)
If your device has no internet access at all, you can install offline voices manually:
1. Download the `.zip` archives for the voices you want (and optionally `Voices.json`) from the [**`offline-voices` release**](https://github.com/dm1sh/hedgehog/releases/tag/offline-voices).
2. Extract each voice archive on your device into:
   ```text
   Documents/EdgeTTS/Voices/<voice name>
   ```
   *(for example, `Documents/EdgeTTS/Voices/Dmitry`, `Documents/EdgeTTS/Voices/Svetlana`, etc.)*
3. Either place the `Voices.json` downloaded from the release directly into `Documents/EdgeTTS/Data/Voices.json`, or edit `Documents/EdgeTTS/Data/Voices.json` so that the `value_offline` array includes the folder names of the extracted voices:
   ```json
   {
       "value": [...],
       "value_offline": [
           "Dmitry",
           "Svetlana"
       ]
   }
   ```
   *(If you placed the release's `Voices.json` into `Documents/EdgeTTS/Data/Voices.json` and later tap **Get online voices**, the app will automatically convert it into the standard `Voices.json` format while keeping your offline voices.)*

### 2. Open a Book or Share Text
- Tap a book directly from the **Load folder** list on the **Book** tab, or tap **Open books** at the bottom to pick one or more files from your device.
- You can also share book files or plain text from any Android app via the system **Share** menu and select **Hedgehog**.
- Once loaded, you can edit the output book title right below the status grid, or switch to the **View text** tab to inspect/edit parts and chapter titles.

### 3. Listen in the Player or Convert to Audio Files
- **Listen immediately**: Switch to the **Player** tab and press **Play** (or tap any sentence card) to listen right away.
- **Convert full audiobook**: Tap **Save to MP3** (or **Save to Opus**) in the bottom dock to convert and save the book to your **Save folder** (`Downloads` by default). You can **Pause**, **Resume**, or **Stop** conversion at any time.

---

## Installation & Building

### Option 1: Download Release APK
Download the latest signed release APK (`hedgehog-<version>.apk`) from the [**Releases**](https://github.com/dm1sh/hedgehog/releases) page and install it on your Android device (Android 7.0+).

### Option 2: Download Debug APK from GitHub Actions
Every push to `main` automatically builds a signed debug APK (`dm1sh.hedgehog_debug`, which can be installed side by side with the release build):
1. Open the **Actions** tab in this repository.
2. Select the latest **Build Debug APK** workflow run.
3. Download the **`hedgehog-debug-apk`** artifact and install `app-debug.apk`.

### Option 3: Build Locally
1. Install **JDK 17**, the **Android SDK** (API 35), and **Android NDK** (`27.2.12479018`).
2. Build the native Opus/FFmpeg libraries once:
   ```bash
   ./scripts/build-opus-ffmpeg-android.sh
   ```
3. Build the debug APK with Gradle:
   ```bash
   ./gradlew assembleDebug
   ```
   The compiled APK will be generated at `app/build/outputs/apk/debug/app-debug.apk`.

---

## Linux CLI (`hedgehog-cli`)

Hedgehog includes a standalone command-line interface (**`:cli`**) for Linux PCs that shares the same online Edge TTS engine, offline Microsoft Embedded Speech engine, book parsers (`FB2`, `EPUB`, `TXT`, `HTML`, `ZIP`), `.lexx` dictionaries, metadata/cover tagger, and native `libmp3lame` + `libopus` + `FFmpeg` audio encoder.

### Building `hedgehog-cli`

```bash
./scripts/build-opus-ffmpeg-linux.sh
./gradlew :cli:executableCli
```

This produces a self-executing binary at `cli/build/libs/hedgehog-cli` (and `cli/build/libs/hedgehog-cli.jar`), which only requires a Java 11+ runtime (`java`) on the host machine.

### CLI Usage Examples

```bash
# Convert a book using an online voice
hedgehog-cli -i book.fb2 -o ./out --voice "ru-RU, DmitryNeural" --lexx rules.lexx --chapters --inline-notes --opus

# Convert text or a book completely offline
hedgehog-cli -i book.epub -o ./out --offline --offline-voice Dmitry --voices-dir ~/EdgeTTS/Voices

# Read from stdin / plain text to an audio file
echo "Привет, мир!" | hedgehog-cli -i - --offline --offline-voice Dmitry -o hello.mp3

# Download offline voices or list available voices on Linux
hedgehog-cli --download-offline-voices --voices-dir ~/EdgeTTS/Voices
hedgehog-cli --list-voices
```

---

## Credits

Hedgehog is a native Android (Kotlin & Jetpack Compose) reimplementation based on the original **EdgeTTS** application ([EdgeTTS topic on 4PDA](https://4pda.to/forum/index.php?showtopic=1089727)).
