package com.cereproc.cerevoice_eng;

import androidx.annotation.Keep;

@Keep
public abstract class TtsEngineCallback {
    private native void clearcallback(long engine, int channel);
    private native void setcallback(long engine, int channel);
    public final void CallbackPrivate(long buffer) {
        onAudio(cerevoice_engJNI.CPRCEN_engine_cptr2abuf(buffer));
    }
    protected abstract void onAudio(long buffer);
    public final void setCallback(long engine, int channel) {
        setcallback(cerevoice_engJNI.CPRCEN_engine_cptr(engine), channel);
    }
    public final void clearCallback(long engine, int channel) {
        clearcallback(cerevoice_engJNI.CPRCEN_engine_cptr(engine), channel);
    }
}
