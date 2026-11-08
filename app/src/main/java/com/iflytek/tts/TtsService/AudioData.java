package com.iflytek.tts.TtsService;

/** PCM is delivered to the reader's playback pipeline. */
public final class AudioData {
    public interface Listener { void onAudio(byte[] audio, int length); }
    private static volatile Listener listener;
    public static void setDataArriveListener(Listener value) { listener = value; }
    public static void onJniOutData(int length, byte[] audio) {
        Listener current = listener;
        if (current != null) current.onAudio(audio, length);
    }
    public static void onJniOutData(int rate, byte[] audio, Object unused, boolean first, boolean last) { }
    public static void onJniWatchCB(int event) { }
    public static void init() { }
    public static boolean isAudioInit() { return true; }
    public static void stop() { }
    public static void release() { }
}
