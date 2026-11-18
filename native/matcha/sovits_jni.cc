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

extern "C" JNIEXPORT jlongArray JNICALL
Java_indi_renakoni_nextvol_tts_sovits_SovitsNative_semantics(JNIEnv* env, jobject, jlong handle,
    jlongArray phones, jlongArray tokens, jlongArray repeats) {
  try {
    auto ids = reinterpret_cast<sovits::Voice*>(handle)->semantics({longs(env, phones), longs(env, tokens), longs(env, repeats)});
    auto result = env->NewLongArray(ids.size());
    if (result) env->SetLongArrayRegion(result, 0, ids.size(), reinterpret_cast<const jlong*>(ids.data()));
    return result;
  } catch (const std::exception& e) { fail(env, e); return nullptr; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_indi_renakoni_nextvol_tts_sovits_SovitsNative_condition(JNIEnv* env, jobject, jlong handle, jboolean advanced) {
  try {
    auto values = reinterpret_cast<sovits::Voice*>(handle)->condition(advanced);
    auto result = env->NewFloatArray(values.size());
    if (result) env->SetFloatArrayRegion(result, 0, values.size(), values.data());
    return result;
  } catch (const std::exception& e) { fail(env, e); return nullptr; }
}

static jbyteArray bytes(JNIEnv* env, const std::vector<int16_t>& pcm) {
  auto size = static_cast<jsize>(pcm.size() * sizeof(int16_t));
  auto result = env->NewByteArray(size);
  if (result) env->SetByteArrayRegion(result, 0, size, reinterpret_cast<const jbyte*>(pcm.data()));
  return result;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_indi_renakoni_nextvol_tts_sovits_SovitsNative_vocode(JNIEnv* env, jobject, jlong handle, jlongArray phones, jlongArray tokens) {
  try { return bytes(env, reinterpret_cast<sovits::Voice*>(handle)->vocode(longs(env, phones), longs(env, tokens))); }
  catch (const std::exception& e) { fail(env, e); return nullptr; }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_indi_renakoni_nextvol_tts_sovits_SovitsNative_pcm(JNIEnv* env, jobject, jfloatArray audio) {
  try {
    auto samples = floats(env, audio);
    return bytes(env, sovits::Voice::pcm(samples.data(), samples.size()));
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
