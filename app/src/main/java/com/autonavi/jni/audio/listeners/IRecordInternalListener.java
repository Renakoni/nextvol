package com.autonavi.jni.audio.listeners;

public interface IRecordInternalListener {
    public static final int EVENT_CANCEL = 4;
    public static final int EVENT_ERROR = 3;
    public static final int EVENT_FINISH = 2;
    public static final int EVENT_PROGRESS = 9;
    public static final int EVENT_START = 1;

    void dataFrame(byte[] bArr, int i);

    void onRecordEventCallBack(int i, int i8, String str);
}
