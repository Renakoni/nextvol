package com.tencent.btts.jni;

/** The Lark runtime exports these exact JNI class and method names. */
public final class SynthesizerNative {
    static { System.loadLibrary("synthesizer"); }
    public native int initializeForFiles(String[] paths, a callback);
    public native int appendSpeakers(String directory);
    public native int appendSpeakersForFiles(String[] paths);
    public native String[] getAvailableSpeaker();
    public native int synthesize(String text, int language, int speaker, int speed, int volume, String request);
    public native int synthesizeByName(String text, int language, String speaker, int speed, int volume, String request);
    public native int cancel(String request);
    public native int destroy();
    public native int setDebugLevel(int level);
}
