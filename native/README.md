# AxiomTV native playback libraries

AxiomTV's own builds of the parts of playback that need native (C/C++) code:

- **axiom-mpv** – libmpv (the mpv player engine with FFmpeg, libass, libplacebo, dav1d) for Android, plus the
  small JNI bridge `com.axiomtv.mpv.MPVLib`. The Kotlin `Player` built on top of it lives in the app
  (`com.axiomtv.player.mpv.MpvPlayer`).
- **media3** – Google's Media3 FFmpeg audio decoder (Dolby Digital/Plus, DTS, TrueHD...) and AV1 decoder for ExoPlayer.

They are built by the **Build native libraries** GitHub workflow (`.github/workflows/native.yml`) and published as assets
of the `native-libs` release. The app build downloads them from there, so the app itself builds in minutes.

Adapted from [wholphin-extensions](https://github.com/damontecres/wholphin-extensions) (GPL-2.0), whose mpv build scripts are in turn
adapted from [mpv-android](https://github.com/mpv-android/mpv-android) (MIT).
