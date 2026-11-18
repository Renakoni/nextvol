#include <jni.h>
#include "sovits.h"

namespace {
std::string path(JNIEnv* env, jbyteArray bytes) {
  std::string result(env->GetArrayLength(bytes), '\0');
  env->GetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<jbyte*>(result.data()));
  if (result.find('\0') != std::string::npos) throw std::invalid_argument("Invalid model path");
  return result;
}

std::vector<int64_t> longs(JNIEnv* env, jlongArray array) {
  std::vector<jlong> values(env->GetArrayLength(array));
  env->GetLongArrayRegion(array, 0, values.size(), values.data());
  return {values.begin(), values.end()};
}

std::vector<float> floats(JNIEnv* env, jfloatArray array) {
  std::vector<float> values(env->GetArrayLength(array));
  env->GetFloatArrayRegion(array, 0, values.size(), values.data());
  return values;
}

void fail(JNIEnv* env, const std::exception& error) {
  if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_indi_renakoni_nextvol_tts_sovits_SovitsNative_create(JNIEnv* env, jobject, jbyteArray model, jbyteArray runtime, jbyteArray speaker) {
  try { return reinterpret_cast<jlong>(new sovits::Voice(path(env, model), path(env, runtime), path(env, speaker))); }
  catch (const std::exception& e) { fail(env, e); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_sovits_SovitsNative_prepare(JNIEnv* env, jobject, jlong handle,
    jlongArray phones, jlongArray tokens, jlongArray repeats, jfloatArray pcm32, jfloatArray pcm16) {
  try {
    reinterpret_cast<sovits::Voice*>(handle)->prepare({longs(env, phones), longs(env, tokens), longs(env, repeats)},
                                                     floats(env, pcm32), floats(env, pcm16));
  } catch (const std::exception& e) { fail(env, e); }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_indi_renakoni_nextvol_tts_sovits_SovitsNative_generate(JNIEnv* env, jobject, jlong handle,
    jlongArray phones, jlongArray tokens, jlongArray repeats) {
  try {
    auto pcm = reinterpret_cast<sovits::Voice*>(handle)->generate({longs(env, phones), longs(env, tokens), longs(env, repeats)});
    auto bytes = static_cast<jsize>(pcm.size() * sizeof(int16_t));
    auto result = env->NewByteArray(bytes);
    if (result) env->SetByteArrayRegion(result, 0, bytes, reinterpret_cast<const jbyte*>(pcm.data()));
    return result;
  } catch (const std::exception& e) { fail(env, e); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_sovits_SovitsNative_cancel(JNIEnv* env, jobject, jlong handle) {
  try { reinterpret_cast<sovits::Voice*>(handle)->cancel(); }
  catch (const std::exception& e) { fail(env, e); }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_sovits_SovitsNative_close(JNIEnv*, jobject, jlong handle) {
  delete reinterpret_cast<sovits::Voice*>(handle);
}
