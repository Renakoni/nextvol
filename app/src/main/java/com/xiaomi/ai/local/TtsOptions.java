package com.xiaomi.ai.local;

import androidx.annotation.Keep;

/** Field names are part of the native ABI. */
@Keep
public final class TtsOptions {
    public int sample_rate;
    public int speaker;

    public TtsOptions(int speaker) {
        this.speaker = speaker;
        this.sample_rate = 0;
    }
}
