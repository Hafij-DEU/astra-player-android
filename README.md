# Astra Player for Android

A free media player for Android with no ads, no account and no subscription.
It is the Android companion of [Astra Player for Linux](https://github.com/Hafij-DEU/video-player),
with an amber accent and compact see-through controls at the bottom.

## Features

- **Plays almost any file**: MKV, MP4, AVI (DivX/Xvid), WMV, FLV, MOV, WebM, TS, MP3, FLAC, Opus and more, through libVLC.
- **Colourful light or dark theme**: follows the system by default, or pick Light or Dark in Settings. Folders, playlists and tabs get their own colours. The player itself is always dark.
- **Settings tab** in the bottom bar with every option in one place.
- **One-hand mode** (on by default): search bars, toolbars and dialogs sit at the bottom of the screen, within thumb reach on phones and tablets.
- **Use as music player**: turn on the Music library to get a Videos | Music switch in Files. Music has its own views (music folders, all songs, or browse), album art, and its own file-type list, so you can untick formats you don't want.
- **Files tab with three views** (switch with the list button or "Files view" in the ⋮ menu):
  - **Video folders** (default): only the folders that hold videos, each with its full path and video count. Open one to see just its videos and play the whole folder.
  - **All videos**: every video on the device in one list, with its folder path.
  - **Browse storage**: classic folder browsing from internal storage and SD cards, including audio files.
  The video list is cached, so it appears at once and refreshes in the background (or with the refresh button). Tapping a video queues its whole folder from that video. You can also pick files with the system picker.
- **Modern file manager**: video thumbnails with duration, search as you type, and sorting by name, date or size (ascending or descending).
- **Hide** folders or single files from Astra (undo in Files settings › Hidden items, or tick "Show hidden items"), and **exclude extensions** you never want listed (e.g. `3gp, ts`).
- **File actions** from ⋮ or a long-press: rename (subtitle files are renamed with the video), share, details (resolution, duration, bitrate, subtitles), hide, and **delete from device** with confirmation, optionally together with matching subtitle files.
- **Playlists**: a play queue with play once / repeat file / repeat all and shuffle, plus saved playlists that persist and can be renamed or deleted.
- **Streams**: HTTP files, HLS (`.m3u8`), DASH (`.mpd`), RTSP/RTMP, and "Open with" or "Share" from other apps.
- **YouTube**: search and watch without an API key or account (NewPipeExtractor). Live streams play too.
- **Subtitles**: embedded tracks, `.srt`/`.ass`/`.ssa`/`.vtt`/`.sub` files picked up automatically from the same folder (or a `Subs` folder), loading a file by hand, sync adjustment and styling (size, colour, background, bold).
- **Audio**: switch audio tracks, adjust audio sync, playback speed 0.25×–4× with pitch correction.
- **Seek preview**: drag the seek bar or swipe sideways to see a thumbnail of the target time (local videos), with the time shown for every source.
- **A-B repeat**, **resume where you left off**, **screenshots** (Pictures/Astra Player).
- **Picture-in-picture**, **background audio** with a media notification and lock-screen controls.
- **Gestures**: swipe the left side for brightness, the right side for volume, sideways to seek; double-tap the sides to skip 10 s.
- **Save a stream or record a portion** of what is playing (Movies/Astra Player), or download a direct HTTP file (Download/Astra Player). Saving is turned off for YouTube, whose terms do not allow it. Only save media you own or are allowed to keep.
- **"Unsupported file"** message for anything that cannot be played.
- **PotPlayer-style keyboard shortcuts** for keyboards, Chromebooks and TV remotes (Space, arrows, C/X/Z speed, `,`/`.` subtitle sync, `[`/`]`/`\` A-B repeat, Ctrl+E screenshot).

## Install

Download the APK for your phone from the [latest release](https://github.com/Hafij-DEU/astra-player-android/releases/latest):

| File | For |
|---|---|
| `AstraPlayer-<version>-arm64-v8a.apk` | Almost all phones from the last 8 years |
| `AstraPlayer-<version>-armeabi-v7a.apk` | Older 32-bit phones |
| `AstraPlayer-<version>-x86_64.apk` / `x86` | Emulators, some tablets and Chromebooks |
| `AstraPlayer-<version>-universal.apk` | Any device (larger download) |

Android 8.0 or newer is required. To browse folders, allow "All files access" when asked; nothing leaves your device.
The first video with subtitles after installing can take a little longer to start while subtitle fonts are prepared once.

## Build

The project is plain Java with the Android Gradle Plugin 8.7.3 (no Kotlin, no AndroidX UI).

```bash
gradle assembleDebug
```

Signed release APKs read the keystore from environment variables
(`ASTRA_KEYSTORE`, `ASTRA_KEYSTORE_PASS`, `ASTRA_KEY_ALIAS`, `ASTRA_KEY_PASS`):

```bash
gradle assembleRelease
```

### Why libVLC and not Media3/ExoPlayer

Media3 only plays exotic audio formats with its FFmpeg extension, and that extension must be compiled with the Android NDK. libVLC ships prebuilt for every ABI on Maven Central and decodes video and audio codecs that phone hardware does not (MS-MPEG4, WMV, RealVideo and more), so it builds with a plain Gradle setup and covers more files.

## Author

Made by Md Hafijur Rahman ([Hafij-DEU](https://github.com/Hafij-DEU)).

## Licence

Astra Player for Android is free software under the GNU General Public License 3.0 or later (see `LICENSE`).
It uses [libVLC](https://www.videolan.org/vlc/libvlc.html) (LGPL 2.1+) and
[NewPipeExtractor](https://github.com/TeamNewPipe/NewPipeExtractor) (GPL 3.0+).
Astra Player is not affiliated with PotPlayer, VideoLAN, NewPipe or YouTube.
