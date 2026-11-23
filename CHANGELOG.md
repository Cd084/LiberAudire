# Changelog

LiberAudīre's version is `versionName` / `versionCode` in `app/build.gradle.kts`. Each release gets a git tag `vX.Y.Z`; GitHub then builds the signed APK and attaches it to the release.

## 0.1.0 — 2026-11-23

The first version.

- **Plex:**
  - Sign-in through Plex's own page; servers you own or that are shared with you; Plex Home profiles with PINs.
  - Picks the best address automatically, switching between home Wi-Fi and mobile data even mid-book.
- **Library:** Continue listening (including from other Plex apps), Recently added, Authors, Downloaded, All books and search. Book pages with chapters and bookmarks, which can be browsed offline.
- **Player:**
  - −30 / +30 (amounts set in Settings) that cross chapter boundaries; hold for a custom skip.
  - Chapters, speed per book, bookmarks, smart rewind; lock-screen, Bluetooth and steering-wheel controls.
- **Offline listening:**
  - About 10 minutes cached ahead while streaming. The whole book downloads after 10 minutes of listening (Wi-Fi-only option), and any book can be downloaded in advance.
  - A storage limit, optional deletion of finished books, and clearing the streaming cache.
- **Progress syncs with Plex** and is queued while offline.
- **Android Auto:** browse and play, with skip buttons.
