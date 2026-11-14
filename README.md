<p align="center">
  <img src="docs/logo.png" alt="AxiomTV logo" width="200">
</p>

<h1 align="center">AXIOM<span>TV</span></h1>

<p align="center">
  <b>A fast, good-looking IPTV player for Android TV boxes and phones.</b><br>
  TiviMate-style live TV guide · Plex-style films &amp; series · hybrid ExoPlayer + mpv playback
</p>

<p align="center">
  <a href="https://github.com/FiTH-g1gGLe/AxiomTV/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/FiTH-g1gGLe/AxiomTV?label=latest&color=E5A00D&style=for-the-badge"></a>
  <a href="https://github.com/FiTH-g1gGLe/AxiomTV/releases"><img alt="Total downloads" src="https://img.shields.io/github/downloads/FiTH-g1gGLe/AxiomTV/total?label=downloads&color=E5A00D&style=for-the-badge"></a>
  <a href="https://github.com/FiTH-g1gGLe/AxiomTV/releases/latest"><img alt="Downloads of the latest release" src="https://img.shields.io/github/downloads/FiTH-g1gGLe/AxiomTV/latest/total?label=latest%20downloads&color=1E3A6E&style=for-the-badge"></a>
  <br>
  <a href="https://github.com/FiTH-g1gGLe/AxiomTV/actions/workflows/build.yml"><img alt="Build" src="https://img.shields.io/github/actions/workflow/status/FiTH-g1gGLe/AxiomTV/build.yml?branch=main&label=build&style=flat-square"></a>
  <img alt="Android 7.0+" src="https://img.shields.io/badge/Android-7.0%2B-3DDC84?logo=android&logoColor=white&style=flat-square">
  <img alt="Android TV" src="https://img.shields.io/badge/Android%20TV-ready-0B1A33?logo=androidtv&logoColor=white&style=flat-square">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white&style=flat-square">
  <a href="LICENSE"><img alt="Licence GPL-2.0" src="https://img.shields.io/badge/licence-GPL--2.0-blue?style=flat-square"></a>
  <img alt="Last commit" src="https://img.shields.io/github/last-commit/FiTH-g1gGLe/AxiomTV?style=flat-square">
</p>

<p align="center">
  <a href="https://github.com/FiTH-g1gGLe/AxiomTV/releases/latest/download/AxiomTV.apk"><b>⬇ Download AxiomTV.apk</b></a>
</p>

---

## What is AxiomTV?

AxiomTV is a personal IPTV player built for the living room. Sign in with your provider's **Xtream Codes** login and you get:

- a **lightning-fast TV guide** in the style of TiviMate,
- a **Plex / Apple TV-style library** for films and series, with artwork, ratings, cast and trailers,
- and a **hybrid playback engine** that picks the best player for every stream and switches automatically if one struggles.

It is designed for the TV remote first (every screen works with just the arrows, OK and Back), and it works on phones too.

> AxiomTV doesn't come with any channels or content. You need your own subscription from an IPTV provider.

---

## Features

### 📺 Live TV
- **TiviMate-style guide** with a live preview window, and a collapsible menu: channel groups first, then the main menu further left.
- **Now & next info bar** in a floating frosted-glass card: channel logo, gold channel number, red LIVE dot, programme progress and what's on next.
- **Channel strip** (press OK) showing now and next on nearby channels.
- **Fast zapping**: channels start after about one second of video, and pressing Up/Down quickly only tunes the channel you stop on.
- **Favourites**: hold OK on a channel to add it to Favourites.
- **Channel groups**: hide the groups you never watch and put your favourite ones at the top.
- **Start-up group**: hold OK on a group and Live TV always opens on it, marked with a pin.
- Provider TV guide by default, or add your own **XMLTV link**.

### ⏪ Catch-up
- A dedicated **Catch-up** section: day tabs along the top, channels on the left, programmes on the right.
- Watch anything from the **last 3 days** on channels that support it. Programmes still on air can be watched **from the start**.
- Past programmes in the TV guide show a rewind icon and play straight away.

### 🎬 Films & Series
- **Five layouts** for Movies and Series: poster wall (the default), posters, large posters, wide cards and list.
- **Five episode layouts**: list (the default), cards, large cards, grid and compact.
- Rich detail pages using **TMDB**: backdrop, rating, age rating, runtime, cast and director.
- **Trailers**: opens the trailer in the YouTube app.
- **My Films / My Series** favourites.
- **Series tracking**: watched ticks, a "next episode" button, and hold OK on an episode to mark it watched.
- Duplicate episodes from the provider are cleaned up automatically.

### 🏠 Home
- A **moving banner** of new releases, with slow zoom artwork and dots showing which one you're on.
- **Continue Watching**, which resumes exactly where you stopped and queues up the next episode.
- **Recently Watched Channels** and **Favourite Channels**.
- Hold OK on a Home tile to remove it or mark it watched.

### ▶️ Playback
- **Hybrid engine**: ExoPlayer for live TV, mpv for films and episodes.
- **Auto-switch**: if a stream doesn't start within a few seconds (4, 8 or 15 s, your choice) the app tries the other player and remembers what worked.
- Choose audio tracks and subtitles, pin an engine per stream, or forget all engine choices.
- **Sleep timer**: off, 30, 60, 90 or 120 minutes.
- **Up next** countdown between episodes.
- The film player matches the Live TV design: frosted card, glowing gold progress bar, clock in the corner.

### ✨ Look & feel
- **Navy frosted-glass menu bar**: icons only, a gold edge marker and a label under the current item. The same bar is used in the TV guide.
- **Artwork backgrounds**: a soft blurred copy of whatever you've selected sets the mood behind every screen.
- **Glow focus**: the selected poster lifts with a gold ring and glow, and film pages glow in each film's own colour.
- AxiomTV logo and **Michroma** wordmark.

### 🔐 Sign-in & setup
- **Sign in with your phone**: the TV shows a QR code and a 4-digit code, and you type your login on your phone. Details go straight from your phone to the box over your home Wi-Fi, with no accounts and no cloud.
- On the TV keyboard, fields only open the keyboard when you press OK.
- Show/hide password button.

### 🧰 Everything else
- **Backup & restore** your favourites, watch progress, channel groups and settings to a file (never your login).
- **Automatic updates**: AxiomTV tells you when a new version is out and installs it for you.
- Works with the remote, a touch screen, or both.

---

## Install

### Android TV box / Google TV / Fire TV
1. Install the **Downloader** app from your box's app store.
2. Enter this address:
   ```
   https://github.com/FiTH-g1gGLe/AxiomTV/releases/latest/download/AxiomTV.apk
   ```
3. Install it, allowing installs from unknown sources if asked.

### Android phone or tablet
Open the [latest release](https://github.com/FiTH-g1gGLe/AxiomTV/releases/latest) on your phone, download **AxiomTV.apk** and open it.

After that, AxiomTV updates itself: when a new version is ready it shows an update card on start-up.

---

## Remote cheat sheet

| Where | Button | What it does |
|---|---|---|
| Live TV (full screen) | **OK** | Channel strip with now & next |
| | **Up / Down** | Change channel |
| | **Left** or **Back** | Open the TV guide |
| | **Right** | Engine menu & sleep timer |
| | **Menu** | Audio & subtitles |
| TV guide | **Hold OK** on a channel | Add/remove favourite |
| | **Left** | Channel groups, then the main menu |
| | **Hold OK** on a group | Make Live TV open on that group |
| Films & series | **Hold OK** on an episode | Mark watched / unwatched |
| Home | **Hold OK** on a tile | Remove from row / mark watched |
| Home | **Back** twice | Exit the app |

---

## Privacy

- Your provider login is stored **only on your device**. It is never sent anywhere except to your provider.
- Phone sign-in happens **directly over your home Wi-Fi**: nothing goes through a server.
- Backups never contain your login.

---

## Built with

Kotlin · Jetpack Compose (Material 3) · Media3 ExoPlayer · libmpv · FFmpeg · Room · Hilt · Coil · TMDB API · ZXing

AxiomTV compiles its **own native playback libraries** (libmpv with FFmpeg, libass, libplacebo and dav1d, plus the Media3
FFmpeg and AV1 decoders) in the [native](native/) folder, using the *Build native libraries* workflow. New app versions are
built and published automatically by GitHub Actions on every change.

---

## Credits

AxiomTV started as a fork of **[Wholphin](https://github.com/damontecres/Wholphin)** by damontecres, an open-source Android TV client for Jellyfin. Its mpv integration and native build scripts ([wholphin-extensions](https://github.com/damontecres/wholphin-extensions)), themselves adapted from [mpv-android](https://github.com/mpv-android/mpv-android), made the hybrid engine possible. Many thanks.

Film and series information and artwork come from [TMDB](https://www.themoviedb.org/). AxiomTV uses the TMDB API but is not endorsed or certified by TMDB.

The Michroma font is used under the SIL Open Font License.

## Licence

AxiomTV is released under the **GNU General Public License v2.0**, the same as Wholphin. See [LICENSE](LICENSE).
