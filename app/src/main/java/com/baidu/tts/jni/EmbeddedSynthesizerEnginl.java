package com.baidu.tts.jni;

/** JNI entry points retain the vendor class and method names. */
public final class EmbeddedSynthesizerEnginl implements IEmbeddedSynthesizerEngine {
    private volatile OnNewDataListener listener;

    static {
        System.loadLibrary("c++_shared");
        System.loadLibrary("BDSpeechDecoder_V1");
        System.loadLibrary("bd_ettl");
        bdTTSSetNativeLogLevel(3);
    }

    public static native int bdTTSSetNativeLogLevel(int level);
    public static synchronized native LicenseInfo bdTTSVerifyLicense(String packageName, int mode, String appId, String device, String extra, String path, byte[] output);
    public static native String bdTTSGetDatParam(String path);
    public static native long bdTTSGetSpeechSampleRate(byte[] path);
    public native ETtsError bdTTSEngineInit(String packageName, byte[] text, byte[] speech, byte[] extension, byte[] extra, String authorization, long[] handle);
    public native int bdTTSEngineUninit(long handle);
    public native int bdTTSDomainDataInit(byte[] path, long handle);
    public native int bdTTSDomainDataUninit(long handle);
    public native int bdTTSSetParam(long handle, int parameter, long value);
    public native int bdTTSSetParamFloat(long handle, int parameter, float value);
    public native int bdTTSSetParamString(long handle, String parameter, String value);
    public native ETtsError bdTTSSynthesis(long handle, byte[] text, int length, int flags);

    @Override public LicenseInfo verifyLicense(String packageName, int mode, String appId, String device, String extra, String path, byte[] output) {
        return bdTTSVerifyLicense(packageName, mode, appId, device, extra, path, output);
    }
    @Override public String modelParameters(String path) { return bdTTSGetDatParam(path); }
    @Override public long modelSampleRate(byte[] path) { return bdTTSGetSpeechSampleRate(path); }
    @Override public void setOnNewDataListener(OnNewDataListener value) { listener = value; }

    public int newAudioDataCallback(byte[] audio, int length) {
        OnNewDataListener current = listener;
        return current == null ? -1 : current.onNewData(audio, length);
    }
}
