package com.cereproc.cerevoice_eng;

import androidx.annotation.Keep;

/** The SDK's JNI ABI; pointer wrappers are unnecessary for the internal adapter. */
@Keep
public final class cerevoice_engJNI {
    static { System.loadLibrary("cerevoice_eng"); }
    public static native long CPRCEN_engine_new();
    public static native void CPRCEN_engine_delete(long engine);
    public static native int CPRCEN_engine_load_voice(long engine, String voice, String license, int type, String account, String password, String server, String proxy);
    public static native int CPRCEN_engine_load_voice_licensestr(long engine, String header, String signature, String root, String voice, int type);
    public static native int CPRCEN_engine_get_voice_count(long engine);
    public static native String CPRCEN_engine_get_voice_info(long engine, int index, String key);
    public static native int CPRCEN_engine_open_channel_with_voice(long engine, int index);
    public static native int CPRCEN_engine_open_default_channel(long engine);
    public static native int CPRCEN_engine_channel_close(long engine, int channel);
    public static native int CPRCEN_engine_channel_reset(long engine, int channel);
    public static native long CPRCEN_engine_channel_speak(long engine, int channel, String text, int length, int flush);
    public static native int CPRCEN_channel_set_phone_min_max(long engine, int channel, int minimum, int maximum);
    public static native long CPRCEN_engine_cptr(long engine);
    public static native long CPRCEN_engine_cptr2abuf(long buffer);
    public static native int CPRC_abuf_wav_mk(long buffer);
    public static native int CPRC_abuf_wav_done(long buffer);
    public static native int CPRC_abuf_wav_srate(long buffer);
    public static native short CPRC_abuf_wav(long buffer, int index);
}
