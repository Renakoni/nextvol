#include <jni.h>
#include <espeak-ng/speak_lib.h>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
// Synchronous eSpeak callbacks run on the calling thread. The app's offline
// lifecycle permit owns this library from initialization through termination.
thread_local JNIEnv *callback_env = nullptr;
thread_local jobject callback_object = nullptr;
thread_local jmethodID callback_method = nullptr;

std::string String(JNIEnv *env, jstring value) {
  const char *chars = env->GetStringUTFChars(value, nullptr);
  if (!chars) throw std::runtime_error("Cannot read eSpeak setting");
  std::string result(chars);
  env->ReleaseStringUTFChars(value, chars);
  return result;
}

void Fail(JNIEnv *env, const std::exception &error) {
  if (!env->ExceptionCheck())
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

int Audio(short *samples, int count, espeak_EVENT *) {
  auto env = callback_env;
  if (env->ExceptionCheck()) return 1;
  auto bytes = env->NewByteArray(count * 2);
  if (!bytes) return 1;
  if (count) env->SetByteArrayRegion(bytes, 0, count * 2, reinterpret_cast<jbyte *>(samples));
  if (!env->ExceptionCheck()) env->CallVoidMethod(callback_object, callback_method, bytes);
  env->DeleteLocalRef(bytes);
  return env->ExceptionCheck() ? 1 : 0;
}
}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_indi_renakoni_nextvol_tts_offline_legacy_EspeakNative_create(
    JNIEnv *env, jobject, jstring data, jstring language, jstring identifier,
    jstring variant, jint gender, jint age) {
  try {
    const auto path = String(env, data);
    const auto code = String(env, language);
    const auto id = String(env, identifier);
    const auto selected_variant = String(env, variant);
    const int rate = espeak_Initialize(AUDIO_OUTPUT_SYNCHRONOUS, 0, path.c_str(), espeakINITIALIZE_DONT_EXIT);
    if (rate <= 0) throw std::runtime_error("eSpeak could not initialize data: " + path);
    const espeak_VOICE *selected = nullptr;
    const auto voices = espeak_ListVoices(nullptr);
    for (int i = 0; voices && voices[i]; ++i)
      if (!id.empty() && id == voices[i]->identifier) { selected = voices[i]; break; }
    if (!selected) {
      for (int i = 0; voices && voices[i]; ++i)
        if (code == voices[i]->languages + 1) { selected = voices[i]; break; }
    }
    if (!selected) throw std::runtime_error("eSpeak voice not found: " + code + " / " + id);
    espeak_ERROR result;
    if (selected_variant.empty()) {
      espeak_VOICE properties{};
      properties.languages = selected->languages + 1;
      properties.gender = gender;
      properties.age = age;
      result = espeak_SetVoiceByProperties(&properties);
    } else {
      const auto name = std::string(selected->identifier) + "+" + selected_variant;
      result = espeak_SetVoiceByName(name.c_str());
    }
    if (result != EE_OK) throw std::runtime_error("eSpeak could not select voice/variant: " + code + " / " + selected_variant);
    espeak_SetParameter(espeakPUNCTUATION, 2, 0);
    espeak_SetSynthCallback(Audio);
    return rate;
  } catch (const std::exception &error) {
    espeak_Terminate();
    Fail(env, error);
    return 0;
  }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_offline_legacy_EspeakNative_synthesize(
    JNIEnv *env, jobject, jbyteArray text, jint rate, jint volume, jobject callback) {
  try {
    const auto length = env->GetArrayLength(text);
    std::vector<char> input(length + 1, 0);
    env->GetByteArrayRegion(text, 0, length, reinterpret_cast<jbyte *>(input.data()));
    if (env->ExceptionCheck()) return;
    auto type = env->GetObjectClass(callback);
    auto method = env->GetMethodID(type, "audio", "([B)V");
    env->DeleteLocalRef(type);
    if (!method) return;
    espeak_SetParameter(espeakRATE, rate, 0);
    espeak_SetParameter(espeakVOLUME, volume, 0);
    callback_env = env;
    callback_object = callback;
    callback_method = method;
    const auto result = espeak_Synth(input.data(), input.size(), 0, POS_CHARACTER, 0, espeakCHARS_UTF8, nullptr, nullptr);
    callback_object = nullptr;
    callback_env = nullptr;
    if (result != EE_OK) throw std::runtime_error("eSpeak synthesis failed: " + std::to_string(result));
  } catch (const std::exception &error) { Fail(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_offline_legacy_EspeakNative_destroy(JNIEnv *, jobject) {
  espeak_SetSynthCallback(nullptr);
  espeak_Terminate();
}
