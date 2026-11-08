package com.sogou.speech.proxy;

/** JNI names and signatures required by the Sogou embedded runtime. */
public final class NativeProxy {
    public native long native_searchService(String library, String configuration);
    public native int native_connect(long handle, int type, String configuration);
    public native int native_write(long handle, String request);
    public native byte[] native_read(long handle, int[] status);
    public native int native_destroyService(long handle, String library);
}
