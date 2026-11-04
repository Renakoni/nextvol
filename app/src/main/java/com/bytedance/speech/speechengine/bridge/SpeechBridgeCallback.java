package com.bytedance.speech.speechengine.bridge;

public interface SpeechBridgeCallback {
    void onSpeechMessage(int type, byte[] data, int length);
}
