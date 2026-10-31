package com.sogou.speech.tts.util;

import android.util.Log;

/** Called by the native Sogou bridge. */
public final class LogUtil {
    public static boolean enable;
    public static boolean isEnable() { return enable; }
    public static void setEnable(boolean value) { enable = value; }
    // Looked up by libsgtts_lib even when native logging is disabled.
    private static void sendLog(int level, String tag, String message) { }
    public static void d(String tag, String message) { if (enable) Log.d(tag, message); }
    public static void i(String tag, String message) { if (enable) Log.i(tag, message); }
    public static void w(String tag, String message) { if (enable) Log.w(tag, message); }
    public static void e(String tag, String message) { if (enable) Log.e(tag, message); }
}
