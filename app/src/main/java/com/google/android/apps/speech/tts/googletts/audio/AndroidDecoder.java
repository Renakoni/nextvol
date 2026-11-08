package com.google.android.apps.speech.tts.googletts.audio;

import androidx.annotation.Keep;
import java.nio.ByteBuffer;

/** JNI callback boundary; imported book text does not enable remote audio decoding. */
@Keep
public final class AndroidDecoder implements AutoCloseable {
    public final ByteBuffer directAudioBuffer = ByteBuffer.allocateDirect(1024);
    public final int capacity = directAudioBuffer.capacity();
    private long handle;
    private boolean unsupportedAudio;

    public long initialize() {
        handle = jniConstruct();
        if (handle == 0 || !jniInit(handle)) throw new IllegalStateException("Google audio decoder initialization failed");
        return handle;
    }

    public void reset() { unsupportedAudio = false; }
    public boolean hasUnsupportedAudio() { return unsupportedAudio; }
    private boolean isReady() { return true; }
    private void startDecodeByteArray(byte[] encoded) { unsupportedAudio = true; }
    private void startDecodeHttpUrl(String url, long offset) { unsupportedAudio = true; }

    @Override public void close() {
        if (handle != 0) { jniDelete(handle); handle = 0; }
    }

    private native long jniConstruct();
    private native boolean jniInit(long handle);
    private native void jniDelete(long handle);
}
