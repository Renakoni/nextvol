package com.iflytek.speech;

import androidx.annotation.Keep;

@Keep
public interface ITtsListener {
    void onProgress(int current, int total);
    void onTtsMsgProc(int message, int parameter, String detail);
}
