package com.autonavi.jni.vcs;

public interface VCSCallback {
    int getAudioPowerLevel();

    double getCarMarkerOri();

    String getCity();

    String getConfirmConnectionParams();

    String getContextParams();

    long getCurrentScene();

    String getHttpdnsHostIp();

    int getNetworkStatus();

    String getPosition();

    String getStartParams();

    boolean handleVoiceCommand(int i, String str);

    boolean isVoiceSupportScene();

    void logToFile(String str);

    void onNuiAuioStateChanged(int i);

    void onVCSStatusChange(int i, String str);

    void onVoiceWakeup(String str);

    void playSound(String str);

    void playWakeupSound(String str);

    void receiveDialogExtensionInfo(String str);

    void text2actionCallback(boolean z5);

    void vcsActionLog(String str, String str2, String str3);
}
