package com.autonavi.jni.vcs;

public final class VCSInterfaceAlc {
    private static native void nativeSetAlcKitPtrForVCS(long j2);

    public static void setAlcKitPtr(long j2) {
        nativeSetAlcKitPtrForVCS(j2);
    }
}
