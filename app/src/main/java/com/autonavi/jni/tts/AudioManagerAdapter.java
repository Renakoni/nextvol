package com.autonavi.jni.tts;

import com.autonavi.jni.audio.listeners.IJNIAudioAssistant;
import com.autonavi.jni.audio.listeners.IRecordInternalListener;

/** libbl_vcs registers all of these declarations during JNI_OnLoad. */
public final class AudioManagerAdapter {
    static { System.loadLibrary("bl_vcs"); }
    // Called from the SDK's audio thread before it populates task fields.
    public AudioPlayerTask generateTask() { return new AudioPlayerTask(); }
    public native long nativeAddSpeakerChangeListener(long j2, ICommonInfoCallback iCommonInfoCallback);
    public native boolean nativeApplyAssistant(int i, IJNIAudioAssistant iJNIAudioAssistant);
    public native void nativeCancelRecord(long j2);
    public native boolean nativeDestroy();
    public native boolean nativeFireAssistant(int i, IJNIAudioAssistant iJNIAudioAssistant);
    public native int nativeGenerateId();
    public native long nativeGetAudioServicePtr();
    public native void nativeGetCurrentSpeaker(ICommonInfoCallback iCommonInfoCallback);
    public native void nativeGetCurrentTask(IAudioTaskInfoCallback iAudioTaskInfoCallback);
    public native String nativeGetMITModelVersion();
    public native String nativeGetParam(String str);
    public native void nativeGetPlayPosition(long j2, IPlayerPositionCallback iPlayerPositionCallback);
    public native String nativeGetSpeakerName();
    public native double nativeGetTTSSpeed();
    public native double nativeGetTTSVolume();
    public native void nativeInit();
    public native boolean nativeIsPlaying();
    public native boolean nativeIsRecording();
    public native void nativePause(long j2);
    public native long nativePlay(String str, IAudioTaskEventCallback iAudioTaskEventCallback, Object obj);
    public native long nativePlayGroup(short[] sArr, String[] strArr, short s8, short s9, double d2, double d8, short s10, int i, int i8, int i9, IAudioTaskEventCallback iAudioTaskEventCallback, Object obj);
    public native long nativePlaySingle(short s8, String str, short s9, short s10, double d2, double d8, short s11, int i, int i8, int i9, int i10, IAudioTaskEventCallback iAudioTaskEventCallback, Object obj);
    public native void nativeRegisterRecordDataBlankState(long j2, IRecordDataBlankStateCallback iRecordDataBlankStateCallback);
    public native void nativeRemoveSpeakerChangeListener(long j2);
    public native void nativeResetSpeaker(int i, IUserActionResultCallback iUserActionResultCallback);
    public native void nativeResume(long j2);
    public native boolean nativeSetDelegate(JNIAudioDelegate jNIAudioDelegate, IAudioEventBroadcast iAudioEventBroadcast);
    public native boolean nativeSetParam(String str, String str2);
    public native boolean nativeSetTTSSpeed(double d2);
    public native boolean nativeSetTTSVolume(double d2);
    public native void nativeSetVoiceIpServicePtr(long j2);
    public native void nativeStartMonopolize(int i, short s8);
    public native long nativeStartRecord(long j2, short s8, int i, long j8, long j9, long j10, String str, int i8, IRecordInternalListener iRecordInternalListener, Object obj);
    public native void nativeStartVoiceless(int i);
    public native void nativeStopAll(int i);
    public native void nativeStopAllExceptOwner(short s8, int i);
    public native void nativeStopCurrentTask(int i);
    public native void nativeStopMonopolize(short s8);
    public native void nativeStopPlay(String str);
    public native void nativeStopRecord(long j2, int i);
    public native void nativeStopVoiceless();
    public native void nativeStopWithId(long j2, int i);
    public native void nativeStopWithOwner(short s8, int i);
    public native void nativeStopWithTag(int i, int i8);
    public native void nativeUnregisterRecordDataBlankState(long j2);
}
