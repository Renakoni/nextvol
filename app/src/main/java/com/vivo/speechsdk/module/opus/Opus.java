package com.vivo.speechsdk.module.opus;

import androidx.annotation.Keep;

/** Native signatures from Vivo Speech SDK 5.1.6.11. */
@Keep
public final class Opus {
    static { System.loadLibrary("vopus_v5"); }
    public static native byte[] decode(byte[] audio, int sampleRate, int type);
    public static native void destroy();
    public static native void destroyDecoder();
    public static native void destroyEncoder();
    public static native byte[] encode(short[] audio, int sampleRate, int type);
    public static native void init(int encoderRate, int decoderRate);
    public static native void initOpusDecoder(int sampleRate);
    public static native void initOpusEncoder(int sampleRate);
    public static native byte[] ogg(byte[] audio, int sampleRate, int index, int type);
    public static native byte[] oggHead(int sampleRate);
}
