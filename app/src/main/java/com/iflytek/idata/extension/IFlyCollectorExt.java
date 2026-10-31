package com.iflytek.idata.extension;

import android.content.Context;
import androidx.annotation.Keep;

/** libmsc registers this signature at load time. The reader never invokes its collector. */
@Keep
public final class IFlyCollectorExt {
    private IFlyCollectorExt() {}
    private native Class<?> getClass(Context context, String path, String name);
}
