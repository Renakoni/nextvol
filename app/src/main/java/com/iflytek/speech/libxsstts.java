package com.iflytek.speech;

import androidx.annotation.Keep;

@Keep
public final class libxsstts {
    static { System.loadLibrary("issxtts30"); System.loadLibrary("issavth"); }
    private libxsstts() {}
    public static native synchronized int setMachineCode(String configuration);
    public static native synchronized int initRes(String directory, int flags);
    public static native synchronized int unInitRes();
    public static native synchronized void create(NativeHandle handle, ITtsListener listener);
    public static native synchronized void destroy(NativeHandle handle);
    public static native synchronized void setParam(NativeHandle handle, int parameter, int value);
    public static native synchronized void start(NativeHandle handle, String text);
    public static native void getAudioData(NativeHandle handle, byte[] audio, int capacity, int[] length);
    public static native synchronized void stop(NativeHandle handle);
}
