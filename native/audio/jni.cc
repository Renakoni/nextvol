#include <jni.h>
#include <algorithm>
#include <array>
#include <exception>
#include <memory>
#include "SoundTouch.h"

namespace {
using soundtouch::SoundTouch;
constexpr int kChunk = 4096;

void Fail(JNIEnv *env, const std::exception &failure) {
  env->ThrowNew(env->FindClass("indi/renakoni/nextvol/tts/audio/AudioProcessingException"), failure.what());
}

jshortArray Drain(JNIEnv *env, SoundTouch &processor) {
  const auto count = processor.numSamples();
  auto output = env->NewShortArray(static_cast<jsize>(count));
  if (!output) return nullptr;
  std::array<float, kChunk> samples;
  std::array<jshort, kChunk> pcm;
  for (unsigned offset = 0; offset < count;) {
    const auto read = processor.receiveSamples(samples.data(), std::min<unsigned>(kChunk, count - offset));
    for (unsigned index = 0; index < read; ++index) {
      // Match SoundTouchDLL's PCM16 conversion before MultiTTS's volume stage.
      pcm[index] = static_cast<jshort>(std::clamp(samples[index], -32768.f, 32767.f));
    }
    env->SetShortArrayRegion(output, static_cast<jsize>(offset), static_cast<jsize>(read), pcm.data());
    if (env->ExceptionCheck()) return nullptr;
    offset += read;
  }
  return output;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_indi_renakoni_nextvol_tts_audio_SoundTouchNative_create(
    JNIEnv *env, jobject, jint inputRate, jint outputRate, jfloat speed, jfloat pitch) {
  try {
    auto processor = std::make_unique<SoundTouch>();
    processor->setSampleRate(inputRate);
    processor->setChannels(1);
    processor->setRate(static_cast<float>(inputRate) / outputRate);
    processor->setTempo(speed);
    processor->setPitch(pitch);
    return reinterpret_cast<jlong>(processor.release());
  } catch (const std::exception &failure) { Fail(env, failure); return 0; }
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_indi_renakoni_nextvol_tts_audio_SoundTouchNative_process(
    JNIEnv *env, jobject, jlong handle, jshortArray input, jint count) {
  try {
    auto &processor = *reinterpret_cast<SoundTouch *>(handle);
    std::array<jshort, kChunk> pcm;
    std::array<float, kChunk> samples;
    for (int offset = 0; offset < count; offset += kChunk) {
      const auto size = std::min(kChunk, count - offset);
      env->GetShortArrayRegion(input, offset, size, pcm.data());
      if (env->ExceptionCheck()) return nullptr;
      for (int index = 0; index < size; ++index) samples[index] = pcm[index];
      processor.putSamples(samples.data(), size);
    }
    return Drain(env, processor);
  } catch (const std::exception &failure) { Fail(env, failure); return nullptr; }
}

extern "C" JNIEXPORT jshortArray JNICALL
Java_indi_renakoni_nextvol_tts_audio_SoundTouchNative_finish(JNIEnv *env, jobject, jlong handle) {
  try {
    auto &processor = *reinterpret_cast<SoundTouch *>(handle);
    processor.flush();
    auto output = Drain(env, processor);
    processor.clear();
    return output;
  } catch (const std::exception &failure) { Fail(env, failure); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_audio_SoundTouchNative_destroy(JNIEnv *, jobject, jlong handle) {
  delete reinterpret_cast<SoundTouch *>(handle);
}
