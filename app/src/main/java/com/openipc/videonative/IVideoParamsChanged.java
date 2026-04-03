package com.openipc.videonative;

import androidx.annotation.Keep;

/**
 * JNI compatibility shim. Native code calls these methods via NDK on the
 * VideoPlayer instance passed to nativeCallBack().
 */
@Keep
public interface IVideoParamsChanged {
    void onVideoRatioChanged(int videoW, int videoH);
    void onDecodingInfoChanged(final DecodingInfo decodingInfo);
}
