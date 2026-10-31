#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <fstream>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <string>

#include "sherpa-onnx/csrc/offline-tts.h"
#include "sherpa-onnx/csrc/onnx-utils.h"
#include "sherpa-onnx/csrc/session.h"

namespace sherpa_onnx { void ReleaseEspeak(); }

namespace {
constexpr size_t kMaxSamples = (8 * 1024 * 1024 - 44) / 2;
struct Instance {
  std::unique_ptr<sherpa_onnx::OfflineTts> engine;
  int32_t speaker = 0;
  float speed = 1, pitch = 1, volume = 1;
  ~Instance() { engine.reset(); sherpa_onnx::ReleaseEspeak(); }
};

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

class Fields {
 public:
  Fields(JNIEnv *env, jobject value) : env_(env), value_(value), type_(env->GetObjectClass(value)) {}
  ~Fields() { env_->DeleteLocalRef(type_); }
  std::string String(const char *name) {
    auto value = static_cast<jstring>(env_->GetObjectField(value_, Field(name, "Ljava/lang/String;")));
    auto result = Utf8(env_, value);
    env_->DeleteLocalRef(value);
    return result;
  }
  int32_t Int(const char *name) { return env_->GetIntField(value_, Field(name, "I")); }
  float Float(const char *name) { return env_->GetFloatField(value_, Field(name, "F")); }
 private:
  jfieldID Field(const char *name, const char *signature) {
    auto field = env_->GetFieldID(type_, name, signature);
    if (!field) throw std::invalid_argument("Missing offline TTS configuration field");
    return field;
  }
  JNIEnv *env_;
  jobject value_;
  jclass type_;
};

std::string ModelType(const std::string &model, const std::string &vocoder,
                      const std::string &voices, const sherpa_onnx::OfflineTtsModelConfig &config) {
  // Read the model's own declaration, not a filename, digest or model allowlist.
  Ort::Env environment(ORT_LOGGING_LEVEL_ERROR);
  auto options = sherpa_onnx::GetSessionOptions(config);
  Ort::Session session(environment, model.c_str(), options);
  auto metadata = session.GetModelMetadata();
  Ort::AllocatorWithDefaultOptions allocator;
  const auto declared = sherpa_onnx::LookupCustomModelMetaData(metadata, "model_type", allocator);
  if (declared == "kitten-tts") return "kitten";
  if (declared == "kokoro" || declared == "kokoro-tts") return "kokoro";
  if (declared == "matcha" || declared == "matcha-tts") return "matcha";
  if (!vocoder.empty()) return "matcha";
  if (!voices.empty()) {
    // Earlier Kokoro exports declare their embedding shape without model_type.
    const auto dimensions = sherpa_onnx::LookupCustomModelMetaData(metadata, "style_dim", allocator);
    std::istringstream input(dimensions);
    int32_t count = 0, value;
    while (input >> value) { ++count; if (input.peek() == ',') input.get(); }
    if (count == 2) return "kitten";
    if (count == 3) return "kokoro";
    throw std::invalid_argument("Missing voice embedding model metadata");
  }
  return "vits";
}

void WriteWav(const sherpa_onnx::GeneratedAudio &audio, const std::string &path,
              float pitch, float volume) {
  // Same 8 MiB ceiling as the existing complete-file contract.
  if (audio.sample_rate <= 0 || audio.samples.empty() || audio.samples.size() > kMaxSamples)
    throw std::runtime_error("Invalid or oversized offline audio");
  const double count = std::ceil(audio.samples.size() / static_cast<double>(pitch));
  if (count < 1 || count > kMaxSamples) throw std::runtime_error("Oversized adjusted audio");
  bool audible = false;
  for (float value : audio.samples) {
    if (!std::isfinite(value)) throw std::runtime_error("Non-finite offline audio");
    audible |= static_cast<int16_t>(std::clamp(value, -1.0f, 1.0f) * 32767) != 0;
  }
  if (!audible) throw std::runtime_error("Silent offline audio");
  std::ofstream output(path, std::ios::binary | std::ios::trunc);
  output.exceptions(std::ios::badbit | std::ios::failbit);
  auto u16 = [&](uint16_t value) { output.put(value & 255); output.put(value >> 8); };
  auto u32 = [&](uint32_t value) { u16(value & 65535); u16(value >> 16); };
  uint32_t bytes = static_cast<uint32_t>(count) * 2;
  output.write("RIFF", 4); u32(36 + bytes); output.write("WAVEfmt ", 8);
  u32(16); u16(1); u16(1); u32(audio.sample_rate); u32(static_cast<uint32_t>(audio.sample_rate) * 2); u16(2); u16(16);
  output.write("data", 4); u32(bytes);
  for (size_t i = 0; i < static_cast<size_t>(count); ++i) {
    const double position = std::min(i * static_cast<double>(pitch), static_cast<double>(audio.samples.size() - 1));
    const auto first = static_cast<size_t>(position);
    const auto next = std::min(first + 1, audio.samples.size() - 1);
    const double value = (audio.samples[first] +
        (static_cast<double>(audio.samples[next]) - audio.samples[first]) * (position - first)) * volume;
    u16(static_cast<int16_t>(std::clamp(value, -1.0, 1.0) * 32767));
  }
  output.close();
}
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_indi_renakoni_nextvol_tts_matcha_MatchaNative_create(JNIEnv *env, jobject, jobject configuration) {
  try {
    if (!configuration) throw std::invalid_argument("Missing offline TTS configuration");
    Fields fields(env, configuration);
    auto instance = std::make_unique<Instance>();
    instance->speaker = fields.Int("speakerId");
    instance->speed = fields.Float("speed");
    instance->pitch = fields.Float("pitch");
    instance->volume = fields.Float("volume");
    if (!std::isfinite(instance->speed) || instance->speed <= 0 ||
        !std::isfinite(instance->pitch) || instance->pitch <= 0 ||
        !std::isfinite(instance->volume) || instance->volume < 0)
      throw std::invalid_argument("Invalid offline voice audio parameters");
    sherpa_onnx::OfflineTtsConfig config;
    config.model.num_threads = fields.Int("numThreads");
    config.model.provider = fields.String("provider");
    const auto model = fields.String("model"), vocoder = fields.String("vocoder"), voices = fields.String("voices");
    const auto tokens = fields.String("tokens"), lexicon = fields.String("lexicon");
    const auto data = fields.String("dataDir"), dict = fields.String("dictDir");
    const float noise = fields.Float("noiseScale"), noise_w = fields.Float("noiseScaleW"), length = fields.Float("lengthScale");
    auto type = fields.String("modelType");
    if (type == "auto") type = ModelType(model, vocoder, voices, config.model);
    if (type == "matcha") {
      config.model.matcha = {model, vocoder, lexicon, tokens, data, dict, noise, length};
    } else if (type == "vits") {
      config.model.vits = {model, lexicon, tokens, data, dict, noise, noise_w, length};
    } else if (type == "kokoro") {
      config.model.kokoro = {model, voices, tokens, lexicon, data, dict, length, fields.String("lang")};
    } else if (type == "kitten") {
      config.model.kitten = {model, voices, tokens, data, length};
    } else throw std::invalid_argument("Unknown Sherpa TTS model type");
    config.max_num_sentences = 1;
    config.rule_fsts = fields.String("ruleFsts");
    config.rule_fars = fields.String("ruleFars");
    if (!config.Validate()) throw std::invalid_argument("Invalid offline model resources");
    instance->engine = std::make_unique<sherpa_onnx::OfflineTts>(config);
    const int32_t speakers = instance->engine->NumSpeakers();
    if (instance->engine->SampleRate() <= 0 || speakers < 0 || instance->speaker < 0 ||
        instance->speaker >= std::max(1, speakers))
      throw std::invalid_argument("Invalid sample rate or selected speaker");
    return reinterpret_cast<jlong>(instance.release());
  } catch (const std::exception &error) { Fail(env, error); return 0; }
}

extern "C" JNIEXPORT jintArray JNICALL
Java_indi_renakoni_nextvol_tts_matcha_MatchaNative_describe(JNIEnv *env, jobject, jlong handle) {
  try {
    if (!handle) throw std::invalid_argument("Missing offline TTS handle");
    const auto &engine = reinterpret_cast<Instance *>(handle)->engine;
    const jint values[] = {engine->NumSpeakers(), engine->SampleRate()};
    auto result = env->NewIntArray(2);
    if (result) env->SetIntArrayRegion(result, 0, 2, values);
    return result;
  } catch (const std::exception &error) { Fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_matcha_MatchaNative_generate(
    JNIEnv *env, jobject, jlong handle, jstring text, jfloat speed, jstring path, jobject cancellation) {
  try {
    if (!handle || !std::isfinite(speed) || speed <= 0 ||
        !text || env->GetStringLength(text) > 200 || !cancellation)
      throw std::invalid_argument("Invalid Matcha request");
    auto method = env->GetMethodID(env->GetObjectClass(cancellation), "isCancelled", "()Z");
    if (!method) throw std::runtime_error("Missing cancellation callback");
    auto cancelled = [&] { return env->CallBooleanMethod(cancellation, method) || env->ExceptionCheck(); };
    if (cancelled()) return;
    size_t samples = 0;
    auto &instance = *reinterpret_cast<Instance *>(handle);
    // Native speaking-rate adjustment compensates the pitch resampling duration.
    const float synthesis_speed = speed * instance.speed / instance.pitch;
    if (!std::isfinite(synthesis_speed) || synthesis_speed <= 0)
      throw std::invalid_argument("Invalid effective speaking rate");
    auto audio = instance.engine->Generate(
        Utf8(env, text), instance.speaker, synthesis_speed, [&](const float *, int32_t count, float) {
          if (count < 0 || (samples += count) > kMaxSamples)
            throw std::runtime_error("Oversized Matcha audio");
          return cancelled() ? 0 : 1;
        });
    if (!cancelled()) WriteWav(audio, Utf8(env, path), instance.pitch, instance.volume);
  } catch (const std::exception &error) { Fail(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_matcha_MatchaNative_destroy(JNIEnv *, jobject, jlong handle) {
  delete reinterpret_cast<Instance *>(handle);
}
