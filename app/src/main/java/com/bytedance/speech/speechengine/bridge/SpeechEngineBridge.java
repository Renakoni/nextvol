package com.bytedance.speech.speechengine.bridge;

import android.content.res.AssetManager;

/** JNI descriptors registered by the embedded SpeechEngine SDK. */
public final class SpeechEngineBridge {
    public native long createEngineToNative(SpeechBridgeCallback callback);
    public native void destroyEngineToNative(long handle);
    public native int feedAudioToNative(long handle, byte[] data, int length);
    public native int fetchResultToNative(long handle, int type, byte[] data);
    public native String fetchStringResultToNative(long handle, int type);
    public native String getVersionToNative();
    public native int initEngineToNative(long handle, AssetManager assets);
    public native boolean isEngineSupportedToNative(String name);
    public native int processAudioToNative(long handle, byte[] data, int length, boolean end);
    public native int resetEngineToNative(long handle);
    public native int sendDirectiveToNative(long handle, int directive, String data);
    public native void setOptionBooleanToNative(long handle, String key, boolean value);
    public native void setOptionDoubleToNative(long handle, String key, double value);
    public native void setOptionIntToNative(long handle, String key, int value);
    public native void setOptionStringToNative(long handle, String key, String value);
}
