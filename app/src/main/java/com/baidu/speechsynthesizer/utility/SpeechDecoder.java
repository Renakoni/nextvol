package com.baidu.speechsynthesizer.utility;

import androidx.annotation.Keep;

/** JNI names and callback match the bundled Baidu decoder. */
@Keep
public final class SpeechDecoder {
    public interface Listener { void onAudio(byte[] pcm); }
    private final Listener listener;

    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("BDSpeechDecoder_V1");
    }

    public SpeechDecoder(Listener listener) { this.listener = listener; }
    public native int decodeWithCallback(String instanceId, byte[] audio, Object callback, int index);
    public native int release(String instanceId);
    public void decodeAudioCallback(byte[] pcm) { listener.onAudio(pcm); }
}
