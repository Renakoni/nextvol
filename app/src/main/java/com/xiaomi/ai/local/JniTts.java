package com.xiaomi.ai.local;

import androidx.annotation.Keep;

@Keep
public final class JniTts {
    @Keep
    public interface SynthesisCallback {
        int onData(byte[] data, boolean finished);
    }

    static { System.loadLibrary("tts-offline"); }

    public static native boolean createEngine(long instance, TtsOptions options);
    public static native long createTtsInstance();
    public static native void deleteTtsInstance(long instance);
    public static native int getMaxAcceptableLength();
    public static native String getVersion();
    public static native int init(String directory);
    public static native boolean openLog(String path);
    public static native void release();
    public static native void resetEngine(long instance);
    public static native boolean synthesis(long instance, String text, SynthesisCallback callback);
}
