package com.vivo.speechsdk.module.opus;

import androidx.annotation.Keep;

/** Native signatures from Vivo Speech SDK 5.1.6.11. */
@Keep
public final class Opus {
    static { System.loadLibrary("vopus_v5"); }
    public static native byte[] decode(byte[] audio, int sampleRate, int type);
    public static native void destroyDecoder();
    public static native void initOpusDecoder(int sampleRate);
}
