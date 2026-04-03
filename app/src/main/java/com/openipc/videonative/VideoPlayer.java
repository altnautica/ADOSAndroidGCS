package com.openipc.videonative;

import android.content.Context;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

/**
 * JNI compatibility shim for the native video decoder/player.
 *
 * This class MUST live at com.openipc.videonative.VideoPlayer because the native
 * C++ code (VideoPlayer.cpp) exports JNI functions with symbol names like
 * Java_com_openipc_videonative_VideoPlayer_nativeInitialize.
 *
 * Loads "ados_wfb" (our combined native library) instead of PixelPilot's "VideoNative".
 */
@Keep
public class VideoPlayer implements IVideoParamsChanged {

    static {
        System.loadLibrary("ados_wfb");
    }

    // Native method declarations matching C++ JNI exports exactly.
    public static native long nativeInitialize(Context context);
    public static native void nativeFinalize(long nativeInstance);
    public static native void nativeStart(long nativeInstance, Context context);
    public static native void nativeStop(long nativeInstance, Context context);
    public static native void nativeSetVideoSurface(long nativeInstance, Surface surface, int index);
    public static native void nativeStartDvr(long nativeInstance, int fd, int fmp4_enabled);
    public static native void nativeStopDvr(long nativeInstance);
    public static native boolean nativeIsRecording(long nativeInstance);
    public static native void nativeStartAudio(long nativeInstance);
    public static native void nativeStopAudio(long nativeInstance);
    public static native String getVideoInfoString(long nativeInstance);
    public static native boolean anyVideoDataReceived(long nativeInstance);
    public static native boolean anyVideoBytesParsedSinceLastCall(long nativeInstance);
    public static native boolean receivingVideoButCannotParse(long nativeInstance);
    public static native <T extends IVideoParamsChanged> void nativeCallBack(T t, long nativeInstance);

    private final long nativeHandle;
    private final Context context;
    private IVideoParamsChanged videoParamsCallback;

    public VideoPlayer(Context context) {
        this.context = context;
        nativeHandle = nativeInitialize(context);
    }

    public long getNativeHandle() {
        return nativeHandle;
    }

    public void setVideoParamsCallback(IVideoParamsChanged callback) {
        this.videoParamsCallback = callback;
    }

    public void setVideoSurface(@Nullable Surface surface, int index) {
        nativeSetVideoSurface(nativeHandle, surface, index);
    }

    public void start() {
        nativeStart(nativeHandle, context);
    }

    public void stop() {
        nativeStop(nativeHandle, context);
    }

    public void pollCallbacks() {
        nativeCallBack(this, nativeHandle);
    }

    public boolean hasVideoData() {
        return anyVideoDataReceived(nativeHandle);
    }

    public String getInfoString() {
        return getVideoInfoString(nativeHandle);
    }

    public void startDvr(int fd, boolean fmp4Enabled) {
        nativeStartDvr(nativeHandle, fd, fmp4Enabled ? 1 : 0);
    }

    public void stopDvr() {
        nativeStopDvr(nativeHandle);
    }

    public boolean isRecording() {
        return nativeIsRecording(nativeHandle);
    }

    public void release() {
        nativeFinalize(nativeHandle);
    }

    /**
     * Create a SurfaceHolder.Callback that manages the video player lifecycle
     * for a given surface index.
     */
    public SurfaceHolder.Callback createSurfaceCallback(int index) {
        return new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(SurfaceHolder holder) {
                setVideoSurface(holder.getSurface(), index);
            }

            @Override
            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            }

            @Override
            public void surfaceDestroyed(SurfaceHolder holder) {
                stop();
                setVideoSurface(null, index);
            }
        };
    }

    @Override
    public void onVideoRatioChanged(int videoW, int videoH) {
        if (videoParamsCallback != null) {
            videoParamsCallback.onVideoRatioChanged(videoW, videoH);
        }
    }

    @Override
    public void onDecodingInfoChanged(DecodingInfo decodingInfo) {
        if (videoParamsCallback != null) {
            videoParamsCallback.onDecodingInfoChanged(decodingInfo);
        }
    }
}
