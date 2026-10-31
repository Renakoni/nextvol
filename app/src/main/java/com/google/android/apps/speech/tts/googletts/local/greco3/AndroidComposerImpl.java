package com.google.android.apps.speech.tts.googletts.local.greco3;

import android.content.Context;
import android.content.res.AssetManager;
import androidx.annotation.Keep;
import com.google.android.apps.speech.tts.googletts.audio.AndroidDecoder;
import java.nio.ByteBuffer;

/** These field and callback names are looked up by the embedded JNI runtime. */
@Keep
public final class AndroidComposerImpl implements AutoCloseable {
    static { System.loadLibrary("google_speech_sbg_tts_jni"); }
    private final AssetManager assetManager;
    private final ByteBuffer directAudioBuffer = ByteBuffer.allocateDirect(4096);
    private final AndroidDecoder decoder = new AndroidDecoder();
    private long handle;
    private int outputSampleRate;

    public AndroidComposerImpl(Context context) {
        assetManager = context.getAssets();
        directAudioBuffer.limit(0);
    }

    public void initialize(int pipelineFd, String directory, byte[] configuration) {
        try {
            handle = jniConstruct(decoder.initialize());
            if (handle == 0 || !jniInitWithOverride(handle, pipelineFd, directory, configuration)) {
                throw new IllegalStateException("Google voice pipeline initialization failed");
            }
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    public void start(byte[] markup, byte[] session) {
        discardAudio();
        decoder.reset();
        outputSampleRate = 0;
        if (handle == 0 || !jniSyncSetupBuffered(handle, markup, session)) {
            throw new IllegalStateException("Google synthesis initialization failed");
        }
    }

    public int read() {
        if (directAudioBuffer.hasRemaining()) throw new IllegalStateException("Unconsumed Google audio");
        int status = jniSyncReadBuffered(handle);
        if (decoder.hasUnsupportedAudio()) throw new IllegalStateException("Google offline text contains unsupported encoded audio");
        return status;
    }

    public byte[] consumeAudio() {
        byte[] result = new byte[directAudioBuffer.remaining()];
        directAudioBuffer.get(result);
        discardAudio();
        return result;
    }

    public void discardAudio() { directAudioBuffer.position(0); directAudioBuffer.limit(0); }

    public int getSampleRate() { return outputSampleRate; }

    private boolean audioAvailable(int bytes, int sampleRate) {
        if (directAudioBuffer.hasRemaining() || sampleRate <= 0 || bytes < 0 || bytes > directAudioBuffer.capacity() || (bytes & 1) != 0) return false;
        // A PCM file has one format; retain the native rate without mixing formats within an utterance.
        if (outputSampleRate != 0 && outputSampleRate != sampleRate) return false;
        outputSampleRate = sampleRate;
        directAudioBuffer.position(0);
        directAudioBuffer.limit(bytes);
        return true;
    }

    private boolean timepointAvailable(int first, int second, int third, int fourth, double time) { return true; }

    @Override public void close() {
        if (handle != 0) { jniDelete(handle); handle = 0; }
        decoder.close();
        discardAudio();
    }

    private native long jniConstruct(long decoderHandle);
    private native boolean jniInitWithOverride(long handle, int pipelineFd, String directory, byte[] configuration);
    private native boolean jniSyncSetupBuffered(long handle, byte[] markup, byte[] session);
    private native int jniSyncReadBuffered(long handle);
    private native void jniDelete(long handle);
}
