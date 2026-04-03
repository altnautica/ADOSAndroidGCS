package com.openipc.videonative;

import androidx.annotation.Keep;

/**
 * JNI compatibility shim. Native code (VideoPlayer.cpp) constructs this class via
 * env->FindClass("com/openipc/videonative/DecodingInfo") and calls the 9-arg constructor.
 */
@Keep
public class DecodingInfo {
    public final float currentFPS;
    public final float currentKiloBitsPerSecond;
    public final float avgParsingTime_ms;
    public final float avgWaitForInputBTime_ms;
    public final float avgHWDecodingTime_ms;
    public final float avgTotalDecodingTime_ms;
    public final int nNALU;
    public final int nNALUSFeeded;
    public final int nDecodedFrames;
    public final int nCodec;

    public DecodingInfo() {
        currentFPS = 0;
        currentKiloBitsPerSecond = 0;
        avgParsingTime_ms = 0;
        avgWaitForInputBTime_ms = 0;
        avgHWDecodingTime_ms = 0;
        avgTotalDecodingTime_ms = 0;
        nNALU = 0;
        nNALUSFeeded = 0;
        nDecodedFrames = 0;
        nCodec = 0;
    }

    public DecodingInfo(float currentFPS, float currentKiloBitsPerSecond, float avgParsingTime_ms,
                        float avgWaitForInputBTime_ms, float avgHWDecodingTime_ms,
                        int nNALU, int nNALUSFeeded, int nDecodedFrames, int nCodec) {
        this.currentFPS = currentFPS;
        this.currentKiloBitsPerSecond = currentKiloBitsPerSecond;
        this.avgParsingTime_ms = avgParsingTime_ms;
        this.avgWaitForInputBTime_ms = avgWaitForInputBTime_ms;
        this.avgHWDecodingTime_ms = avgHWDecodingTime_ms;
        this.avgTotalDecodingTime_ms = avgParsingTime_ms + avgWaitForInputBTime_ms + avgHWDecodingTime_ms;
        this.nNALU = nNALU;
        this.nNALUSFeeded = nNALUSFeeded;
        this.nDecodedFrames = nDecodedFrames;
        this.nCodec = nCodec;
    }
}
