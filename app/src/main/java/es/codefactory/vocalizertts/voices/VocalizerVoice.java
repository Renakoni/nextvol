package es.codefactory.vocalizertts.voices;

import androidx.annotation.Keep;

/** Model metadata populated by the vendor JNI runtime. */
@Keep
public final class VocalizerVoice {
    private String mVoiceName = "";
    private String mLanguage = "";
    private String mVoiceModel = "";
    private int mSampleRate;
    private String mLanguageVersion = "";
    private String mLanguageTLW = "";
    private int mLangId;
    private String mVoiceAge = "";
    private String mVoiceType = "";
    private String mVoiceVersion = "";
    private String mInstallationPath = "";

    public int getSampleRate() { return mSampleRate; }
    public String getVoiceName() { return mVoiceName; }
    public void setInstallationPath(String path) { mInstallationPath = path; }
}
