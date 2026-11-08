package es.codefactory.vocalizertts;

import android.content.Context;
import androidx.annotation.Keep;

/** The SDK obtains its embedding application context through this JNI field. */
@Keep
public final class VocalizerApplication {
    public static Context mContext;
    private VocalizerApplication() {}
}
