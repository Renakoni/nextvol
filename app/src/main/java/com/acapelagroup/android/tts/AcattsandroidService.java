package com.acapelagroup.android.tts;

import android.speech.tts.SynthesisCallback;
import android.os.Build;
import androidx.annotation.Keep;

/** JNI class name required by the SDK. This is not an Android service. */
@Keep
public final class AcattsandroidService {
    private final String identity;
    private volatile SynthesisCallback callback;
    static { System.loadLibrary("ttsacattsandroidextended"); }
    public AcattsandroidService(String identity) { this.identity = identity; }
    public void setCallback(SynthesisCallback callback) { this.callback = callback; }
    public String idfunction() { return identity; }
    public void reloadpref() { }
    private void exceptionCallback() {
        SynthesisCallback current = callback;
        if (current != null) current.error();
    }
    private long eventsCallback(long event, long a, long b, long c) {
        SynthesisCallback current = callback;
        if (current != null) {
            if (event == 1) current.done();
            if (event == 2 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                current.rangeStart((int) a, (int) b, (int) c);
            }
        }
        return 0;
    }
    private void samplesCallback(byte[] samples, long count) {
        SynthesisCallback current = callback;
        if (current != null && samples != null && count > 0 && count <= samples.length) {
            current.audioAvailable(samples, 0, (int) count);
        }
    }
    public native int nInit(String directory);
    public native int nSetVoiceMode(String mode);
    public native int nSetProperty(String key, String value);
    public native int nSetLanguage(String language, String country, String variant);
    public native int nSetCurrentVoice(String voice);
    public native int nSpeak(String text, SynthesisCallback callback);
    public native int nStop();
    public native int nShutdown();
}
