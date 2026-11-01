# ==============================================================================
# Sona ProGuard / R8 Configuration
# ==============================================================================

# Keep line numbers and source file names for readable crash stack traces
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Preserve runtime annotations and generic type signatures
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,Exceptions

# ------------------------------------------------------------------------------
# Kotlin Coroutines
# ------------------------------------------------------------------------------
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.**

# ------------------------------------------------------------------------------
# Jetpack Compose & Material 3
# ------------------------------------------------------------------------------
-dontwarn androidx.compose.**

# ------------------------------------------------------------------------------
# AndroidX Media3 & ExoPlayer
# ------------------------------------------------------------------------------
-keep class com.sona.player.playback.PlaybackService { *; }
-keep class androidx.media3.session.** { *; }
-keep class androidx.media3.exoplayer.** { *; }
-keep class androidx.media3.common.** { *; }
-dontwarn androidx.media3.**

# Keep custom AudioProcessor reflection / instantiation
-keep class com.sona.player.automix.AutomixEqAudioProcessor { *; }

# ------------------------------------------------------------------------------
# Coil (Image Loading)
# ------------------------------------------------------------------------------
-keep class coil.** { *; }
-dontwarn coil.**
-dontwarn okhttp3.**
-dontwarn okio.**

# ------------------------------------------------------------------------------
# App Models & Enums
# ------------------------------------------------------------------------------
-keep class com.sona.player.data.model.** { *; }
-keepclassmembers enum com.sona.player.automix.** { *; }
-keep class com.sona.player.automix.TrackAnalysis { *; }
