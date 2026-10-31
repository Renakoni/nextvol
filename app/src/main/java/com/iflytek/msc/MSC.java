package com.iflytek.msc;

import androidx.annotation.Keep;

/** Exact JNI entry points used by the embedded offline synthesizer. */
@Keep
public final class MSC {
    static { System.loadLibrary("msc"); }
    private MSC() {}

    public static native int QMSPLogin(byte[] user, byte[] password, byte[] parameters);
    public static native int QMSPLogOut();
    public static native char[] QTTSSessionBegin(byte[] parameters, MSCSessionInfo status);
    public static native int QTTSTextPut(char[] session, byte[] text);
    public static native byte[] QTTSAudioGet(char[] session, MSCSessionInfo status);
    public static native int QTTSSessionEnd(char[] session, byte[] reason);
}
