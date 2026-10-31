package com.baidu.tts.jni;

public interface OnNewDataListener {
    int onNewData(byte[] audio, int length);
}
