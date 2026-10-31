package com.autonavi.jni.audio.listeners;

public interface IJNIAudioAssistant {
    boolean allowPlayTask(String str);

    boolean allowRecordTask(String str);

    void onFired(String str);
}
