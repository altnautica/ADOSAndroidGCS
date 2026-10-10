# Native methods are bound by name with RegisterNatives, and the native side
# calls onPacket back by name.
-keep class com.altnautica.gcs.radio.rx.WfbReceiver {
    native <methods>;
    void onPacket(int, byte[]);
}
