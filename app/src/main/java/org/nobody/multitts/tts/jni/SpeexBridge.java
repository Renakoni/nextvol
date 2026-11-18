package org.nobody.multitts.tts.jni;

import indi.renakoni.nextvol.tts.runtime.SpeechLibraries;

/** Companion-runtime entry point; returned authorization stays inside SDK initialization. */
public final class SpeexBridge {
    static { SpeechLibraries.loadVendor("speexdsp"); }
    public static native int code2id(String code);
    public static native String getLicense(int index);
}
