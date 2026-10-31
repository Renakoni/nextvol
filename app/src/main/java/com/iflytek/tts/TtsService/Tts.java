package com.iflytek.tts.TtsService;

/** All nine methods are registered together by libAisound's JNI_OnLoad. */
public final class Tts {
    static { System.loadLibrary("Aisound"); }
    public static native int JniGetVersion(int[] version);
    public static native int JniCreate(String[] resources);
    public static native int JniStop();
    public static native int JniIsPlaying();
    public static native boolean JniIsCreated();
    public static native int JniSpeak(String text);
    public static native int JniDestroy();
    public static native int JniSetParam(int parameter, int value);
    public static native int JniGetParam(int parameter);
}
