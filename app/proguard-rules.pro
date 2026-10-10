# kotlinx.serialization, OkHttp, Hilt, Room, MapLibre and the coroutines
# library ship their own consumer rules. Only libraries that reach classes
# through JNI or reflection without consumer rules are listed here.

# ---- WebRTC (classes are looked up from native code) ----
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**

# ---- JNA (used by the Convex client; ships no consumer rules) ----
# Native code resolves JNA classes, fields and callback methods by name.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
-dontwarn java.awt.**
-dontwarn com.sun.jna.**

# ---- Convex mobile client (UniFFI bindings; ships no consumer rules) ----
# The generated bindings bind Rust symbols, structures and callback
# interfaces through JNA by name.
-keep class dev.convex.android.** { *; }
-keep interface dev.convex.android.** { *; }

# ---- HiveMQ MQTT client and its Netty/JCTools runtime (plain jars, no consumer rules) ----
# Netty and JCTools look up fields by name (Unsafe offsets, atomic updaters).
-keepclassmembernames class io.netty.** { *; }
-keepclassmembers class org.jctools.** { *; }
# Optional Netty/HiveMQ integrations that are never on the Android classpath.
-dontwarn io.netty.channel.epoll.**
-dontwarn io.netty.handler.proxy.**
-dontwarn io.netty.internal.tcnative.**
-dontwarn org.apache.log4j.**
-dontwarn org.apache.logging.log4j.**
-dontwarn org.eclipse.jetty.alpn.**
-dontwarn org.eclipse.jetty.npn.**
-dontwarn org.slf4j.**
