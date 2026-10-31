package com.iflytek.msc;

import androidx.annotation.Keep;

/** The runtime writes these fields by their JNI names. */
@Keep
public final class MSCSessionInfo {
    public int epstatues;
    public int errorcode;
    public int buflen = -1;
    public int sesstatus = -1;
    public int rsltstatus = 2;
    public byte[] buffer;
}
