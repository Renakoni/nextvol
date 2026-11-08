package com.baidu.tts.jni;

public interface IEmbeddedSynthesizerEngine {
    LicenseInfo verifyLicense(String packageName, int mode, String appId, String device, String extra, String path, byte[] output);
    String modelParameters(String path);
    long modelSampleRate(byte[] path);
    ETtsError bdTTSEngineInit(String packageName, byte[] text, byte[] speech, byte[] extension, byte[] extra, String authorization, long[] handle);
    int bdTTSEngineUninit(long handle);
    int bdTTSDomainDataInit(byte[] path, long handle);
    int bdTTSDomainDataUninit(long handle);
    int bdTTSSetParam(long handle, int parameter, long value);
    int bdTTSSetParamFloat(long handle, int parameter, float value);
    int bdTTSSetParamString(long handle, String parameter, String value);
    ETtsError bdTTSSynthesis(long handle, byte[] text, int length, int flags);
    void setOnNewDataListener(OnNewDataListener listener);
}
