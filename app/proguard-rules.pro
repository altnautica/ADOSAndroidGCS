# ---- MAVLink (dronefleet) ----
-keep class io.dronefleet.mavlink.** { *; }
-keepclassmembers class io.dronefleet.mavlink.** { *; }
-dontwarn io.dronefleet.mavlink.**

# ---- Retrofit ----
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**

# ---- OkHttp ----
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ---- Gson ----
-keepattributes Signature
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# ---- WebRTC ----
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ---- Ktor ----
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ---- Mapbox ----
-keep class com.mapbox.** { *; }
-dontwarn com.mapbox.**

# ---- Hilt ----
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }

# ---- App data classes ----
-keep class com.altnautica.gcs.data.** { *; }

# ---- Coroutines ----
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
