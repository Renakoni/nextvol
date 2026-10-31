package com.autonavi.jni.tts;

public abstract class JNIRecorder {
    public native void nativeNotifyRecordData(byte[] bArr, int i, long j2);

    public native void nativeNotifyRecordStatus(int i, long j2);

    public abstract int setConfig(int i, int i8, int i9, int i10, int i11);

    public abstract int start();

    public abstract int stop();
}
