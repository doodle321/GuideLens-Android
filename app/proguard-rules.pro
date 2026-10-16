# GuideLens R8 rules — validated for the bundled stack.
# TFLite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.** { *; }
-dontwarn org.tensorflow.**
# ML Kit
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_text.** { *; }
-dontwarn com.google.mlkit.**
-dontwarn com.google.android.gms.internal.mlkit_vision_text.**
# CameraX
-keep class androidx.camera.** { *; }
-dontwarn androidx.camera.**
# kotlinx.coroutines + org.json
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keep class org.json.** { *; }
# Keep BuildConfig fields (endpoints) from being inlined/obfuscated away
-keepclassmembers class com.guidelens.app.BuildConfig { *; }
# Keep utterance-id callbacks
-keepclassmembers class * extends android.speech.tts.UtteranceProgressListener { *; }
