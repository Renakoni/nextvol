#include <jni.h>
#include <mutex>
#include <string>
#include <vector>
#include "mecab.h"
#include "njd.h"
#include "jpcommon.h"
#include "text2mecab.h"
#include "mecab2njd.h"
#include "njd_set_pronunciation.h"
#include "njd_set_digit.h"
#include "njd_set_accent_phrase.h"
#include "njd_set_accent_type.h"
#include "njd_set_unvoiced_vowel.h"
#include "njd_set_long_vowel.h"
#include "njd2jpcommon.h"

namespace {
// Same frontend stages as pyopenjtalk.run_frontend + make_label. The dictionary
// stays memory-mapped; per-utterance nodes are released after copying the labels.
struct Frontend {
    Mecab mecab{};
    NJD njd{};
    JPCommon jpcommon{};
    bool loaded = false;
    std::mutex mutex;
    Frontend() { Mecab_initialize(&mecab); NJD_initialize(&njd); JPCommon_initialize(&jpcommon); }
    ~Frontend() { Mecab_clear(&mecab); NJD_clear(&njd); JPCommon_clear(&jpcommon); }
};
Frontend frontend;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_indi_renakoni_nextvol_tts_sovits_OpenJtalk_labelsNative(
    JNIEnv* env, jobject, jstring dictionary, jbyteArray text) {
    std::lock_guard<std::mutex> lock(frontend.mutex);
    if (!frontend.loaded) {
        const char* path = env->GetStringUTFChars(dictionary, nullptr);
        frontend.loaded = Mecab_load(&frontend.mecab, path) == 1;
        env->ReleaseStringUTFChars(dictionary, path);
        if (!frontend.loaded) {
            env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Cannot load Japanese dictionary");
            return nullptr;
        }
    }
    const auto size = env->GetArrayLength(text);
    std::vector<char> input(size + 1, 0);
    env->GetByteArrayRegion(text, 0, size, reinterpret_cast<jbyte*>(input.data()));
    // text2mecab expands ASCII to full-width UTF-8 (at most three bytes per byte).
    std::vector<char> normalized(size * 3 + 1, 0);
    text2mecab(normalized.data(), input.data());
    Mecab_analysis(&frontend.mecab, normalized.data());
    mecab2njd(&frontend.njd, Mecab_get_feature(&frontend.mecab), Mecab_get_size(&frontend.mecab));
    njd_set_pronunciation(&frontend.njd);
    njd_set_digit(&frontend.njd);
    njd_set_accent_phrase(&frontend.njd);
    njd_set_accent_type(&frontend.njd);
    njd_set_unvoiced_vowel(&frontend.njd);
    njd_set_long_vowel(&frontend.njd);
    njd2jpcommon(&frontend.jpcommon, &frontend.njd);
    JPCommon_make_label(&frontend.jpcommon);
    const int count = JPCommon_get_label_size(&frontend.jpcommon);
    char** labels = JPCommon_get_label_feature(&frontend.jpcommon);
    auto result = env->NewObjectArray(count, env->FindClass("java/lang/String"), nullptr);
    for (int i = 0; i < count; ++i) {
        auto label = env->NewStringUTF(labels[i]);
        env->SetObjectArrayElement(result, i, label);
        env->DeleteLocalRef(label);
    }
    JPCommon_refresh(&frontend.jpcommon);
    NJD_refresh(&frontend.njd);
    Mecab_refresh(&frontend.mecab);
    return result;
}
