# YT Chat to XML

Small Android app. Paste a YouTube live or video link, and it saves **only the live chat**
(no video, audio or subtitles) as a Niconico-style comment `.xml` in your Downloads folder.

- Accepts `youtube.com/live/ID`, `youtube.com/watch?v=ID`, `youtu.be/ID`, or just the 11-character ID.
- You can also use the YouTube app's **Share** button and pick this app.
- Uses [youtubedl-android](https://github.com/yausername/youtubedl-android) (bundled yt-dlp) to fetch
  `live_chat.json`, then converts it with a Kotlin port of `convert_chat_to_xml.py`.
- Output: `Downloads/<videoId>.xml`

## Get the APK

### Option A: GitHub (no Android Studio needed)
1. Create a new GitHub repository and push this whole folder to it, including the hidden `.github` folder
   (use `git push` or GitHub Desktop; dragging a folder into the web uploader may skip hidden folders).
2. Open the repository's **Actions** tab. The *Build APK* workflow runs on every push
   (or press **Run workflow**).
3. When it finishes, open the run and download the artifact **YtChat2Xml-debug-apk**.
   Unzip it and install `app-debug.apk` on your phone (allow "install unknown apps").

### Option B: Android Studio
Open this folder, let Gradle sync, then **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
Or from a terminal with the Android SDK installed: `./gradlew assembleDebug`
(APK: `app/build/outputs/apk/debug/app-debug.apk`).

## Notes
- Requires Android 10 (API 29) or newer. Built for arm64-v8a and armeabi-v7a phones
  (add `"x86_64"` to `abiFilters` in `app/build.gradle.kts` for a PC emulator).
- A video only has chat to download if YouTube offers a chat replay for it. Private/members-only
  videos and streams with replay disabled will report "No live chat found".
- If downloads start failing, tap **Update yt-dlp** (YouTube changes often).
- The library version is one line in `app/build.gradle.kts` (`youtubedlAndroidVersion`).

## XML format

```xml
<packet>
<chat vpos="372300" no="1" user_id="Alice">comment text</chat>
</packet>
```

`vpos` is in 1/100 second from the start of the video.

### Differences from `convert_chat_to_xml.py`
- Handles every action in a chat line, not just the first one.
- Emoji no longer crash the conversion (standard emoji are kept, custom emoji become `:shortcut:`).
- `&` is escaped first, so `<` no longer ends up as `&amp;lt;`.
- No extra spaces around the comment text.
- `vpos` comes from the exact millisecond offset (falls back to the `H:MM:SS` text); negative offsets become 0.
- Double-quoted attributes, matching the official Niconico format.
- Like the original, only normal text messages are converted (Super Chats and other event types are skipped).
