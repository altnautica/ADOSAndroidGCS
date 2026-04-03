package com.openipc.wfbngrtl8812;

/**
 * JNI compatibility shim. Native code calls onWfbNgStatsChanged via NDK callback.
 */
public interface WfbNGStatsChanged {
    void onWfbNgStatsChanged(final WfbNGStats data);
}
