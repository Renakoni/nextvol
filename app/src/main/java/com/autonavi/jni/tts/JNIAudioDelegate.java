package com.autonavi.jni.tts;

public abstract class JNIAudioDelegate {
    public static final int EVENT_CALL_END = 2;
    public static final int EVENT_CALL_START = 1;
    public static final int EVENT_FOCUS_GAIN = 11;
    public static final int EVENT_FOCUS_LOSS = 12;

    private native void nativePlatformEventCallback(long j2, int i);

    public abstract void bindDelegate(long j2);

    public abstract JNIPlayer createPlayer(long j2);

    public abstract JNIRecorder createRecorder(long j2);

    public abstract String getExtendFontName(int i);

    public abstract String getInitParam();

    public abstract String getLibPath();

    public abstract boolean getLogSwitch();

    public abstract String getTranslateParam();

    public abstract boolean isBackgroundMusicPlaying();

    public abstract void onInited(boolean z5);

    public void platformEventCallback(long j2, int i) {
        nativePlatformEventCallback(j2, i);
    }

    public abstract void playbackLog(int i, String str);

    public abstract void utLog(String str, String str2);
}
