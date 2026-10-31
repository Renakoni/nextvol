package com.autonavi.jni.tts;

public interface IAudioTaskEventCallback {
    void onEnd(long j2, int i, int i8);

    void onMiddle(long j2, int i);

    boolean onStart(long j2);
}
