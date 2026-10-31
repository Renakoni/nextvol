package com.autonavi.jni.tts;

public abstract class JNIPlayer {
    private native void nativeSetTaskDuration(long j2, int i, long j8);

    private native void playEventCallback(long j2, int i, int i8, long j8);

    public abstract int getCurrentPosition();

    public void onMediaEvent(long j2, int i, int i8, long j8) {
        playEventCallback(j2, i, i8, j8);
    }

    public abstract void pause(long j2);

    public abstract void playAudioFile(long j2, short s8, String str, int i, int i8);

    public abstract void playAudioFileStart(AudioPlayerTask audioPlayerTask);

    public abstract void playPCMData(long j2, byte[] bArr, int i);

    public abstract void playPCMDataFinished(long j2, int i);

    public abstract void playPCMStart(AudioPlayerTask audioPlayerTask, int i);

    public abstract int preStartCheck(AudioPlayerTask audioPlayerTask);

    public abstract void releasePlayer();

    public abstract void resume(long j2);

    public abstract void seekTo(int i);

    public abstract void setFocusStyle(int i);

    public void setTaskDuration(long j2, int i, long j8) {
        nativeSetTaskDuration(j2, i, j8);
    }

    public abstract void setVolume(float f3);

    public abstract void stopAudioFilePlay(int i);

    public abstract void stopTTSPlay(int i);

    public abstract void taskEnd(int i, long j2, int i8);
}
