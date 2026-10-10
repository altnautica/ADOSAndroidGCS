# kotlinx.serialization, OkHttp, Hilt, Room, MapLibre and the coroutines
# library ship their own consumer rules. Only libraries that reach classes
# through JNI or reflection without consumer rules are listed here.

# ---- WebRTC (classes are looked up from native code) ----
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
