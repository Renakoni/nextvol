package com.qq.wx.offlinevoice.synthesizer;

import androidx.annotation.Keep;

/** JNI declarations required by the embedded WeRead runtime. */
@Keep
public final class SynthesizerNative {
    public native void destroy();
    public native int init(byte[] directory);
    public native int prepare(byte[] text);
    public native int prepareUTF8(byte[] text);
    public native void reset();
    public native void setSpeed(float speed);
    public native void setVoiceName(String voice);
    public native void setVolume(float volume);
    public native int synthesize(short[] samples, int capacity, int[] length, int mode);
}
