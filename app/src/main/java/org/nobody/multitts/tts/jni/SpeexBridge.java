package org.nobody.multitts.tts.jni;

/** Companion-runtime entry point; returned authorization stays inside SDK initialization. */
public final class SpeexBridge {
    static { System.loadLibrary("speexdsp"); }
    public static native int code2id(String code);
    public static native String getLicense(int index);
}
