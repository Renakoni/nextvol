package com.samsung.SMT.engine;

import androidx.annotation.Keep;
import com.samsung.SMT.ref.VoiceDataInfo;

/** Native ABI and callback only; application lifecycle stays in the internal adapter. */
@Keep
public final class SmtTTS {
    public interface AudioListener { void onAudio(byte[] data); }
    private volatile AudioListener listener;

    static { System.loadLibrary("smtloader"); }

    public void setListener(AudioListener listener) { this.listener = listener; }

    private void AudioCallback(int id, byte[] data, int start, int end, String text, int result) {
        AudioListener current = listener;
        if (current != null && data != null && data.length > 0) current.onAudio(data);
    }

    public native synchronized int changeSpeaker(long handle, String language, String country, String voiceType, int voiceId);
    public native int enablePowerOptimization(long handle, boolean enabled, int level);
    public native String getProperty(long handle, String key);
    public native int getSamplingRate(long handle);
    public native int getVersion(long handle);
    public native long initialize(int id);
    public native boolean isSupportMultiInstance(long handle);
    public native int loadEngine(long handle, String library);
    public native int setEndingSilence(long handle, int duration);
    public native synchronized int setLanguage(long handle, String directory, String language, String country, String voiceType, int voiceId, int type, boolean fp16);
    public native synchronized int setLanguageWithFD(long handle, VoiceDataInfo[] data, String language, String country, String voiceType, int voiceId, int type, boolean fp16);
    public native void setMaxBufferSize(long handle, int size);
    public native int setParameters(long handle, int speed, int volume, float pitch, int reserved);
    public native int speak(long handle, String text);
    public native void stopSpeak(long handle);
    public native boolean supervisor_GetUtterance(long handle, String[] utterance, boolean flush);
    public native void supervisor_InputText(long handle, String text, String language, String country);
    public native int terminate(long handle);
    public native int unloadEngine(long handle);
}
