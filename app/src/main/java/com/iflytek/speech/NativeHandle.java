package com.iflytek.speech;

import androidx.annotation.Keep;

/** Accessors are called directly from the vendor JNI library. */
@Keep
public final class NativeHandle {
    private int err_ret;
    private long native_point;
    public int getErr_ret() { return err_ret; }
    public void setErr_ret(int value) { err_ret = value; }
    public long getNative_point() { return native_point; }
    public void setNative_point(long value) { native_point = value; }
}
