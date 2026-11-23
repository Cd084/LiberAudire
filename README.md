# LiberAudīre

An Android audiobook player for a Plex server, built for the car and for places with no signal. *Liber* is Latin for "book" (and "free"), *audīre* "to hear".

Requires **Android 13 or newer**. Download the APK from the [Releases](https://github.com/Cd084/LiberAud-re/releases) page and open it on the phone; install updates the same way.

## Features

**Plex**
- Sign in with your Plex account. LiberAudīre opens Plex's own sign-in page and never sees your password.
- Works for the server owner and for friends the server is shared with. Plex Home members can switch profiles, with PINs.
- Uses the best address for where you are: the home address on Wi-Fi, the remote one on mobile data. It switches automatically, even mid-book.
- **Progress syncs with Plex**, so other Plex apps resume where you left off. Progress made offline is sent when you're back online.
  - Plex itself doesn't store positions within the first minute of a file. LiberAudīre keeps those on the phone.
- Plays show up in the Plex Dashboard and Tautulli as "LiberAudire".

**Library**
- Tabs for Continue listening, Recently added, Authors, Downloaded and All books, plus search.
- Each book page shows the length, size, progress, chapters (files) and bookmarks.
- Browsing works offline for books you've opened before; downloaded books are always available.

**Player**
- **−30 / +30** on a tap. **Hold either one to skip a custom amount**: quick choices from 1 to 30 minutes, or type minutes and seconds. The last custom amount is remembered.
  - Skipping crosses chapter (file) boundaries.
- Previous and next chapter, and a chapter scrubber.
- Speed from 0.5× to 3×, remembered for each book.
- Bookmarks.
- **Smart rewind:** after a pause it goes back 3 s (after a few minutes), 10 s (up to an hour) or 30 s (longer).
- Lock screen, notification, Bluetooth and steering-wheel controls.

**Offline listening**
- **Streaming keeps about 10 minutes ahead on the phone**, so short dead zones don't interrupt you.
- **After 10 minutes of a book, the whole book downloads** automatically. The threshold can be changed or switched off.
  - "Only download whole books on Wi-Fi" (on by default) makes those downloads wait for Wi-Fi.
- **Download any book in advance** for planes and beaches. Books you download yourself never wait for Wi-Fi and are never removed to make room.
- A storage limit for automatic downloads (oldest removed first), optional deletion of finished books, and a button to clear the streaming cache.

**Android Auto**
- Browse Continue listening, Downloaded, Recently added and Authors on the car screen, with your skip amounts as buttons.
- Android Auto doesn't allow typing while driving, so custom skips are phone-only.
- **Sideloaded apps are hidden in Android Auto by default.** To show LiberAudīre:
  1. Open Android Auto's settings on the phone.
  2. Tap **Version** 10 times to enable developer mode.
  3. In the ⋮ menu, open **Developer settings** and turn on **Unknown sources**.

## Releases

Pushing a tag like `v0.2.0` makes GitHub build the signed APK and attach it to the release. Bump `versionName`/`versionCode` in `app/build.gradle.kts` and add a `CHANGELOG.md` entry first.

## Building

You need JDK 17+ and the Android SDK with platform 37.1:

```powershell
.\gradlew.bat assembleDebug      # app\build\outputs\apk\debug\app-debug.apk
.\gradlew.bat assembleRelease    # signed with the key in key.properties (see app\build.gradle.kts)
```

## Project layout

| Path | Purpose |
|---|---|
| `plex/Plex.kt` | plex.tv sign-in, servers, Plex Home; library, books, progress reports |
| `App.kt` | Shared pieces: audio cache, downloads, network watching, progress sync, resume logic |
| `playback/PlaybackService.kt` | Background player (Media3), Android Auto browse tree, progress saving, the 10-minute rule |
| `playback/BookPlayer.kt` | Skip amounts, skipping across files, smart rewind |
| `playback/Downloads.kt` | Book downloads, waiting for Wi-Fi, storage limit, clean-up |
| `data/` | Settings, progress, bookmarks, download list, saved book details |
| `ui/` | Screens (Jetpack Compose) |
