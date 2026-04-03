package com.openipc.wfbngrtl8812;

import android.content.Context;

import androidx.annotation.Keep;

/**
 * JNI compatibility shim for WFB-ng native link.
 *
 * This class MUST live at com.openipc.wfbngrtl8812.WfbNgLink because the native
 * C++ code (WfbngLink.cpp) exports JNI functions with this fully-qualified name
 * baked into the symbol (e.g. Java_com_openipc_wfbngrtl8812_WfbNgLink_nativeInitialize).
 *
 * The only change from PixelPilot's original: we load "ados_wfb" instead of "WfbngRtl8812".
 */
@Keep
public class WfbNgLink implements WfbNGStatsChanged {

    static {
        System.loadLibrary("ados_wfb");
    }

    // Native method declarations matching C++ JNI exports exactly.
    public static native long nativeInitialize(Context context);
    public static native void nativeRun(long nativeInstance, Context context, int wifiChannel, int bandWidth, int fd);
    public static native void nativeStop(long nativeInstance, Context context, int fd);
    public static native void nativeRefreshKey(long nativeInstance);
    public static native <T extends WfbNGStatsChanged> void nativeCallBack(T t, long nativeInstance);
    public static native void nativeStartAdaptivelink(long nativeInstance);
    public static native void nativeSetAdaptiveLinkEnabled(long nativeInstance, boolean enabled);
    public static native void nativeSetTxPower(long nativeInstance, int power);
    public static native void nativeSetUseFec(long nativeInstance, int use);
    public static native void nativeSetUseLdpc(long nativeInstance, int use);
    public static native void nativeSetUseStbc(long nativeInstance, int use);
    public static native void nativeSetFecThresholds(long nativeInstance, int lostTo5, int recTo4, int recTo3, int recTo2, int recTo1);

    private final long nativeHandle;
    private WfbNGStatsChanged statsCallback;

    /**
     * Create a new WFB-ng link instance. Call from any context (does not require Activity).
     */
    public WfbNgLink(Context context) {
        nativeHandle = nativeInitialize(context);
    }

    public long getNativeHandle() {
        return nativeHandle;
    }

    public void run(Context context, int wifiChannel, int bandWidth, int fd) {
        nativeRun(nativeHandle, context, wifiChannel, bandWidth, fd);
    }

    public void stop(Context context, int fd) {
        nativeStop(nativeHandle, context, fd);
    }

    public void refreshKey() {
        nativeRefreshKey(nativeHandle);
    }

    public void pollStats() {
        nativeCallBack(this, nativeHandle);
    }

    public void setAdaptiveLinkEnabled(boolean enabled) {
        nativeSetAdaptiveLinkEnabled(nativeHandle, enabled);
    }

    public void setTxPower(int power) {
        nativeSetTxPower(nativeHandle, power);
    }

    public void setUseFec(int use) {
        nativeSetUseFec(nativeHandle, use);
    }

    public void setUseLdpc(int use) {
        nativeSetUseLdpc(nativeHandle, use);
    }

    public void setUseStbc(int use) {
        nativeSetUseStbc(nativeHandle, use);
    }

    public void setFecThresholds(int lostTo5, int recTo4, int recTo3, int recTo2, int recTo1) {
        nativeSetFecThresholds(nativeHandle, lostTo5, recTo4, recTo3, recTo2, recTo1);
    }

    public void setStatsCallback(WfbNGStatsChanged callback) {
        this.statsCallback = callback;
    }

    @Override
    public void onWfbNgStatsChanged(WfbNGStats stats) {
        if (statsCallback != null) {
            statsCallback.onWfbNgStatsChanged(stats);
        }
    }
}
