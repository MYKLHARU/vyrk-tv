# kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.vyrk.**$$serializer { *; }
-keepclassmembers class com.vyrk.** { *** Companion; }
-keepclasseswithmembers class com.vyrk.** { kotlinx.serialization.KSerializer serializer(...); }

# Jellyfin FFmpeg decoder is loaded reflectively by Media3.
-keep class androidx.media3.decoder.ffmpeg.** { *; }

# OkHttp
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
