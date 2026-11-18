package com.baidu.speechsynthesizer.utility;

import androidx.annotation.Keep;
import indi.renakoni.nextvol.tts.runtime.SpeechLibraries;

/** JNI names and callback match the bundled Baidu decoder. */
@Keep
public final class SpeechDecoder {
    public interface Listener { void onAudio(byte[] pcm); }
    private final Listener listener;

    static {
        SpeechLibraries.loadVendor("c++_shared");
        SpeechLibraries.loadVendor("BDSpeechDecoder_V1");
    }

    public SpeechDecoder(Listener listener) { this.listener = listener; }
    public native int decodeWithCallback(String instanceId, byte[] audio, Object callback, int index);
    public native int release(String instanceId);
    public void decodeAudioCallback(byte[] pcm) { listener.onAudio(pcm); }
}
