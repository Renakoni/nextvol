#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>

#include "sherpa-onnx/csrc/offline-tts.h"

namespace {
std::string Utf8(JNIEnv *env, jstring value) {
  if (!value) throw std::invalid_argument("Missing string");
  // Java's modified UTF-8 encodes supplementary characters as two surrogates.
  const jchar *chars = env->GetStringChars(value, nullptr);
  if (!chars) throw std::runtime_error("Cannot read text");
  std::string result;
  const auto length = env->GetStringLength(value);
  for (jsize i = 0; i < length; ++i) {
    uint32_t c = chars[i];
    if (c >= 0xd800 && c <= 0xdbff && i + 1 < length &&
        chars[i + 1] >= 0xdc00 && chars[i + 1] <= 0xdfff) {
      c = 0x10000 + ((c - 0xd800) << 10) + chars[++i] - 0xdc00;
    } else if (c >= 0xd800 && c <= 0xdfff) {
      c = 0xfffd;
    }
    if (c < 0x80) result += static_cast<char>(c);
    else if (c < 0x800) {
      result += static_cast<char>(0xc0 | (c >> 6));
      result += static_cast<char>(0x80 | (c & 63));
    } else if (c < 0x10000) {
      result += static_cast<char>(0xe0 | (c >> 12));
      result += static_cast<char>(0x80 | ((c >> 6) & 63));
      result += static_cast<char>(0x80 | (c & 63));
    } else {
      result += static_cast<char>(0xf0 | (c >> 18));
      result += static_cast<char>(0x80 | ((c >> 12) & 63));
      result += static_cast<char>(0x80 | ((c >> 6) & 63));
      result += static_cast<char>(0x80 | (c & 63));
    }
  }
  env->ReleaseStringChars(value, chars);
  if (result.find('\0') != std::string::npos) throw std::invalid_argument("NUL in string");
  return result;
}

void Fail(JNIEnv *env, const std::exception &error) {
  __android_log_print(ANDROID_LOG_ERROR, "NextVolMatcha", "%s", error.what());
  if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}

void WriteWav(const sherpa_onnx::GeneratedAudio &audio, const std::string &path) {
  // Same 8 MiB ceiling as the existing complete-file contract.
  if (audio.sample_rate != 22050 || audio.samples.empty() || audio.samples.size() > (8 * 1024 * 1024 - 44) / 2)
    throw std::runtime_error("Invalid or oversized Matcha audio");
  bool audible = false;
  for (float value : audio.samples) {
    if (!std::isfinite(value)) throw std::runtime_error("Non-finite Matcha audio");
    audible |= static_cast<int16_t>(std::clamp(value, -1.0f, 1.0f) * 32767) != 0;
  }
  if (!audible) throw std::runtime_error("Silent Matcha audio");
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  output.exceptions(std::ios::badbit | std::ios::failbit);
  auto u16 = [&](uint16_t value) { output.put(value & 255); output.put(value >> 8); };
  auto u32 = [&](uint32_t value) { u16(value & 65535); u16(value >> 16); };
  uint32_t bytes = audio.samples.size() * 2;
  output.write("RIFF", 4); u32(36 + bytes); output.write("WAVEfmt ", 8);
  u32(16); u16(1); u16(1); u32(22050); u32(44100); u16(2); u16(16);
  output.write("data", 4); u32(bytes);
  for (float value : audio.samples)
    u16(static_cast<int16_t>(std::clamp(value, -1.0f, 1.0f) * 32767));
  output.close();
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_indi_renakoni_nextvol_tts_matcha_MatchaNative_create(JNIEnv *env, jobject, jstring directory) {
  try {
    const auto root = Utf8(env, directory);
    sherpa_onnx::OfflineTtsConfig config;
    config.model.matcha.acoustic_model = root + "/model-steps-3.onnx";
    config.model.matcha.vocoder = root + "/hifigan_v2.onnx";
    config.model.matcha.tokens = root + "/tokens.txt";
    config.model.matcha.lexicon = root + "/lexicon.txt";
    config.model.matcha.dict_dir = root + "/dict";
    config.model.num_threads = 2;
    config.model.provider = "cpu";
    config.max_num_sentences = 1;
    config.rule_fsts = root + "/date.fst," + root + "/number.fst," + root + "/phone.fst";
    if (!config.Validate()) throw std::invalid_argument("Invalid Matcha resources");
    auto instance = std::make_unique<sherpa_onnx::OfflineTts>(config);
    if (instance->SampleRate() != 22050 || instance->NumSpeakers() != 1)
      throw std::invalid_argument("Unsupported Matcha model");
    return reinterpret_cast<jlong>(instance.release());
  } catch (const std::exception &error) { Fail(env, error); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_matcha_MatchaNative_generate(
    JNIEnv *env, jobject, jlong handle, jstring text, jfloat speed, jstring path, jobject cancellation) {
  try {
    if (!handle || !std::isfinite(speed) || speed < 0.5f || speed > 2 ||
        !text || env->GetStringLength(text) > 200 || !cancellation)
      throw std::invalid_argument("Invalid Matcha request");
    auto method = env->GetMethodID(env->GetObjectClass(cancellation), "isCancelled", "()Z");
    if (!method) throw std::runtime_error("Missing cancellation callback");
    auto cancelled = [&] { return env->CallBooleanMethod(cancellation, method) || env->ExceptionCheck(); };
    if (cancelled()) return;
    size_t samples = 0;
    auto audio = reinterpret_cast<sherpa_onnx::OfflineTts *>(handle)->Generate(
        Utf8(env, text), 0, speed, [&](const float *, int32_t count, float) {
          if (count < 0 || (samples += count) > (8 * 1024 * 1024 - 44) / 2)
            throw std::runtime_error("Oversized Matcha audio");
          return cancelled() ? 0 : 1;
        });
    if (!cancelled()) WriteWav(audio, Utf8(env, path));
  } catch (const std::exception &error) { Fail(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_matcha_MatchaNative_destroy(JNIEnv *, jobject, jlong handle) {
  delete reinterpret_cast<sherpa_onnx::OfflineTts *>(handle);
}
