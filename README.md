# Hedgehog

**Hedgehog** is a native Android app for turning e-books and text files into audiobooks using natural-sounding neural voices. It features a clean Material 3 interface with Material You (Monet) dynamic colors and works both online and completely offline.

---

## What the App Can Do

- **Convert Books to Audiobooks**:
  - Open books in **FB2**, **EPUB**, **TXT**, **CSV**, **INI**, or **ZIP** archives containing books.
  - Automatically detects book metadata (`Author - Book Title`) and text encodings (UTF-8 and Windows-1251).
  - Save audiobooks in **MP3** (`96 kbps` or compact `48 kbps`) or **Opus** (`32 kbps VoIP` for small file sizes).
- **Online & Offline Neural Voices**:
  - Choose from hundreds of **Online** Microsoft Edge neural voices across dozens of languages.
  - Download **Offline** voices directly inside the app to convert books without an internet connection.
  - Automatically falls back to your selected offline voice if your internet connection drops.
- **Smart Book & Audio Organization**:
  - **Cut by chapters**: Split audiobooks into separate chapter files named after each chapter.
  - **Inline footnotes**: Optionally embed FB2 and EPUB footnotes directly into the spoken text so notes are read naturally in context.
  - **Merge audio**: Combine parts into a single audiobook file or group them by a custom number of parts per file.
  - **Pronunciation dictionaries (`.lexx`)**: Apply custom word replacements, stress marks, and regex rules before synthesis.
- **Preview & Edit Before Converting**:
  - Browse and edit individual text parts, listen to a voice preview of any part, or customize the output book title before saving.
- **Background Conversion**:
  - Converts books in multiple parallel streams with live progress in the status grid, top bar, and system notification—even while your screen is off.

---

## How to Use

### 1. Choose a Voice (`Book` or `Settings` tab)
1. Select your preferred **Online voice** (or switch off *Use online voices* in **Settings → Voice settings** to use an **Offline voice**).
2. Adjust **Speed** and **Pitch** if needed.
3. To download offline voices from within the app, open **Settings → Voice settings** and tap **Get offline voices**.

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

### 2. Open a Book (`Book` tab)
- Tap a book directly from the **Load folder** list on the **Book** tab, or tap **Open books** at the bottom to pick one or more files from your device.
- Once loaded, you can edit the output book title right below the status grid.
- To inspect or edit the text of individual parts (or listen to a preview), switch to the **View text** tab.

### 3. Convert to Audio
- Tap **Save to MP3** (or **Save to Opus**) at the bottom of the screen to start conversion.
- Each square in the status grid represents a part of the book and changes color as it is processed and saved.
- You can **Pause**, **Resume**, or **Stop** conversion at any time. Finished audiobooks are saved to your **Save folder** (`Downloads` by default).

---

## How to Build

### Option 1: Download Prebuilt APK from GitHub Actions
Every push to the repository automatically builds a signed debug APK via GitHub Actions:
1. Open the **Actions** tab in this repository.
2. Select the latest **Build Debug APK** workflow run.
3. Download the **`hedgehog-debug-apk`** artifact and install `app-debug.apk` on your Android device (Android 7.0+).

### Option 2: Build Locally
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

## Credits

Hedgehog is a native Android (Kotlin & Jetpack Compose) reimplementation based on the original **EdgeTTS** application ([EdgeTTS topic on 4PDA](https://4pda.to/forum/index.php?showtopic=1089727)).
