package com.tencent.btts.jni;

/** Native callback type and method signatures match the supplied Lark runtime. */
public abstract class a {
    public abstract int onCallback(byte[] pcm, boolean first, boolean last, String request);
    public abstract int onError(int code, String message, String request);
    public int onInfoCallback(int code, String message, String request) { return 0; }
    public int onLogCallback(int level, String message) { return 0; }
}
