-keepattributes SourceFile,LineNumberTable

-keep class com.axiomtv.mpv.MPVLib { *; }


# TODO investigate using smaller scope

# Media3SubtitleOverride uses reflection to access/modify these fields
-keep class androidx.media3.ui.SubtitleView {
  private androidx.media3.ui.SubtitleView$Output output;
}
-keep class androidx.media3.ui.CanvasSubtitleOutput {
  private final java.util.List painters;
}
-keep class androidx.media3.ui.SubtitlePainter {
  private final float outlineWidth;
}

# Keep external/native functions
