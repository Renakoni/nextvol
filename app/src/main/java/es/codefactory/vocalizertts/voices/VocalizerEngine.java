package es.codefactory.vocalizertts.voices;

import androidx.annotation.Keep;

/** Retains the exported JNI names; Android services are not part of this boundary. */
@Keep
public final class VocalizerEngine implements AutoCloseable {
    private long mNativeEnginePtr;
    private final AudioListener listener;

    public interface AudioListener { boolean onAudio(short[] samples); }

    public VocalizerEngine(AudioListener listener) {
        this.listener = listener;
        System.loadLibrary("NuanceVocalizer");
        g();
        if (mNativeEnginePtr == 0) throw new IllegalStateException("Vocalizer returned no engine handle");
    }

    public VocalizerVoice initialize(String headers, String directory) {
        if (!r(headers)) throw new IllegalStateException("Vocalizer could not initialize voice headers");
        VocalizerVoice[] voices = a();
        if (voices == null || voices.length == 0) throw new IllegalStateException("Vocalizer found no voices");
        VocalizerVoice voice = voices[0];
        voice.setInstallationPath(directory);
        if (!j(voice)) throw new IllegalStateException("Vocalizer could not load voice resources");
        pr(0);
        s(1);
        c(1);
        return voice;
    }

    public void configure(int rate, int pitch, int volume) { h(rate); f(pitch); l(volume); }
    public void speak(String text) {
        if (!o(4096)) throw new IllegalStateException("Vocalizer could not allocate its audio buffer");
        t(text);
    }
    public void stop() { if (mNativeEnginePtr != 0) b(); }
    private boolean audioSamplesReceived(short[] samples) { return listener.onAudio(samples); }

    @Override public void close() {
        if (mNativeEnginePtr != 0) {
            try { b(); n(); } finally { destroyNativeEngine(); }
        }
    }

    private native void g();
    private native boolean r(String headers);
    private native VocalizerVoice[] a();
    private native boolean j(VocalizerVoice voice);
    private native void h(int rate);
    private native void f(int pitch);
    private native void l(int volume);
    private native void pr(int gain);
    private native void s(int textMode);
    private native void c(int readMode);
    private native boolean o(int bytes);
    private native void t(String text);
    private native void b();
    private native void n();
    private native void destroyNativeEngine();
}
