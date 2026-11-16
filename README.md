# YT Chat to XML

Small Android app. Paste a YouTube live or video link, and it saves **only the live chat**
(no video, audio or subtitles) as a comment `.xml` in your Downloads folder, in the same format
that JKCommentViewer writes.

- Accepts `youtube.com/live/ID`, `youtube.com/watch?v=ID`, `youtu.be/ID`, or just the 11-character ID.
- You can also use the YouTube app's **Share** button and pick this app.
- Uses [youtubedl-android](https://github.com/yausername/youtubedl-android) (bundled yt-dlp) to fetch
  `live_chat.json`, then converts it (Kotlin port of `convert_chat_to_xml.py`, extended to the JKCommentViewer format).
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

Same layout as a JKCommentViewer export: UTF-8 with BOM, CRLF line endings, one indented
`<chat>` per comment.

```xml
<?xml version="1.0" encoding="UTF-8"?>
<packet>
  <chat date="1730809943" user_id="UC..." id="" vpos="9673" mail="" premium="" user_name="Name" youtube_icon_url="https://..." youtube_sticker_url="" badge_image_url="" youtube_super_chat="false" new_sponsor="false" gift_send="false" gift_recv="false" sponsor="false" amount_text="" moderator="false" owner="false" verified="false" header_primary_text="" yt_text_0="Hello" yt_emojiId_0="" yt_emojiUrl_0="">Hello</chat>
</packet>
```

- `date` is the Unix time in seconds; `vpos` is 1/100 second from the start of the video
  (comments posted before the stream started get `0`).
- Emoji appear in the comment text as `:shortcut:` names; `yt_emojiId_N` / `yt_emojiUrl_N` keep the
  original emoji ID and image URL for each message run.
- Besides normal comments it converts Super Chats, Super Stickers, new members / membership milestones
  and membership gifts, with the matching flags set.
- Verified by rebuilding the YouTube data of a real JKCommentViewer export and converting it back:
  the result was byte-for-byte identical. Super Chat, sticker, owner and verified details were not in
  that export, so those follow the most likely layout rather than a confirmed one.
