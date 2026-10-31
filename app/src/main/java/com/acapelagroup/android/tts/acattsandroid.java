package com.acapelagroup.android.tts;

import androidx.annotation.Keep;

@Keep
public final class acattsandroid {
    public interface Listener { void onSamples(short[] samples, long count); }
    private volatile Listener listener;
    static { System.loadLibrary("acattsandroid"); }
    public void setListener(Listener listener) { this.listener = listener; }
    private long eventsCallback(long event, long a, long b, long c, long d) { return 0; }
    private void samplesCallback(short[] samples, long count) {
        Listener current = listener;
        if (current != null) current.onSamples(samples, count);
    }
    public native int nInitCallbacks();
    public native void nSetLog(boolean enabled);
    public native int nSetLicense(long user, long password, String license);
    public native int nGetVoiceList(String[] directories);
    public native String nGetVoiceInfo(String ini, String key);
    public native int nLoadVoice(String voice);
    public native int nSetShape(float shape);
    public native int nSetSpeechRate(float rate);
    public native int nSpeak(String text);
    public native int nStop();
    public native int nShutdown();
}
