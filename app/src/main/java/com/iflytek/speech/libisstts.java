package com.iflytek.speech;

import androidx.annotation.Keep;

@Keep
public final class libisstts {
    static { System.loadLibrary("issxtts40"); System.loadLibrary("issauth"); }
    private libisstts() {}
    public static native synchronized int setMachineCode(String configuration);
    public static native synchronized int initRes(String directory, int flags);
    public static native synchronized int unInitRes();
    public static native synchronized int create(NativeHandle handle, ITtsListener listener);
    public static native synchronized int destroy(NativeHandle handle);
    public static native synchronized int setParam(NativeHandle handle, int parameter, int value);
    public static native synchronized int start(NativeHandle handle, String text);
    public static native int getAudioData(NativeHandle handle, byte[] audio, int capacity, int[] length);
    public static native synchronized int stop(NativeHandle handle);
}
