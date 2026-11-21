#include "sovits.h"

#include <algorithm>
#include <android/log.h>
#include <array>
#include <chrono>
#include <cmath>
#include <limits>
#include <numeric>
#include <sched.h>
#include <stdexcept>

namespace sovits {
namespace {
using Tensor = Ort::Value;
using Inputs = std::vector<std::pair<std::string, const Tensor*>>;

template <typename T>
Tensor tensor(const T* data, size_t count, std::initializer_list<int64_t> shape) {
  Ort::AllocatorWithDefaultOptions allocator;
  Tensor value = Tensor::CreateTensor<T>(allocator, shape.begin(), shape.size());
  if (value.GetTensorTypeAndShapeInfo().GetElementCount() != count)
    throw std::invalid_argument("Invalid GPT-SoVITS tensor shape");
  std::copy_n(data, count, value.GetTensorMutableData<T>());
  return value;
}

template <typename T>
Tensor tensor(const std::vector<T>& data, std::initializer_list<int64_t> shape) {
  return tensor(data.data(), data.size(), shape);
}

std::vector<Tensor> evaluate(Ort::Session& graph, const Ort::RunOptions& run,
                             const Inputs& inputs, const std::vector<std::string>& outputs) {
  Ort::IoBinding binding(graph);
  for (const auto& [name, value] : inputs) binding.BindInput(name.c_str(), *value);
  auto cpu = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
  for (const auto& output : outputs) binding.BindOutput(output.c_str(), cpu);
  graph.Run(run, binding);
  return binding.GetOutputValues();
}

constexpr size_t layers = 24, kv_count = layers * 2;
constexpr int64_t heads = 16, head_width = 32;
constexpr size_t max_steps = 1500;

struct DecoderPorts {
  std::vector<std::string> prefill{"y", "y_emb"}, inputs{"iy", "iy_emb"}, outputs{"y", "y_emb", "stop_condition_tensor"};
  std::vector<const char*> input_names, output_names;

  DecoderPorts() {
    for (size_t layer = 0; layer < layers; ++layer) {
      for (const char* kind : {"k_layer_", "v_layer_"}) {
        auto suffix = kind + std::to_string(layer);
        inputs.push_back("past_" + suffix);
        outputs.push_back("present_" + suffix);
        prefill.push_back(outputs.back());
      }
    }
    inputs.insert(inputs.end(), {"cache_length", "sampling"});
    for (const auto& name : inputs) input_names.push_back(name.c_str());
    for (const auto& name : outputs) output_names.push_back(name.c_str());
  }
};
const DecoderPorts ports;

// Prefill returns [head, length, width]. The decoder reads/writes [1, head, capacity, width]
// in place. Copy history only when reserving the next block of up to 256 tokens.
void reserve_kv(Tensor& value, int64_t old_stride, int64_t used, int64_t capacity) {
  Ort::AllocatorWithDefaultOptions allocator;
  const int64_t shape[]{1, heads, capacity, head_width};
  auto next = Tensor::CreateTensor<float>(allocator, shape, 4);
  const auto* source = value.GetTensorData<float>();
  auto* destination = next.GetTensorMutableData<float>();
  for (int64_t head = 0; head < heads; ++head) {
    auto dst = destination + head * capacity * head_width;
    std::copy_n(source + head * old_stride * head_width, used * head_width, dst);
    std::fill(dst + used * head_width, dst + capacity * head_width, 0.f);
  }
  value = std::move(next);
}
}  // namespace

Voice::Voice(std::filesystem::path model, std::filesystem::path runtime)
    : model_(std::move(model)), runtime_(std::move(runtime)) {}

Ort::Session Voice::load(const std::filesystem::path& path, int thread_limit, bool vocoder) {
  static Ort::Env environment(ORT_LOGGING_LEVEL_WARNING, "NextVolSoVITS");
  Ort::SessionOptions options;
  cpu_set_t allowed;
  if (sched_getaffinity(0, sizeof(allowed), &allowed) == 0)
    thread_limit = std::clamp(CPU_COUNT(&allowed), 1, thread_limit);
  options.SetIntraOpNumThreads(thread_limit);
  options.SetInterOpNumThreads(1);
  options.SetGraphOptimizationLevel(GraphOptimizationLevel::ORT_ENABLE_ALL);
  if (vocoder) options.DisableMemPattern();
  if (path.filename() == "mobile-decoder-shared.onnx")
    options.AddConfigEntry("session.genie_fp16_weight_storage", "1");
  return Ort::Session(environment, path.c_str(), options);
}

Tensor Voice::bert(const Text& text) {
  const size_t phones = text.phones.size();
  if (text.bert_runs.empty()) return mandarin_bert(text.tokens, text.repeats, phones);
  Ort::AllocatorWithDefaultOptions allocator;
  const int64_t shape[] = {static_cast<int64_t>(phones), 1024};
  auto output = Tensor::CreateTensor<float>(allocator, shape, 2);
  auto dst = output.GetTensorMutableData<float>();
  size_t phone = 0, token = 0, repeat = 0;
  for (int64_t length : text.bert_runs) {
    if (length < 0 && length >= -static_cast<int64_t>(phones - phone)) {
      // English and Japanese use the same phones-by-1024 shape, filled with zeros.
      size_t count = static_cast<size_t>(-length);
      std::fill_n(dst + phone * 1024, count * 1024, 0.f);
      phone += count;
    } else {
      if (length < 3 || length > 512 || static_cast<size_t>(length) > text.tokens.size() - token ||
          static_cast<size_t>(length - 2) > text.repeats.size() - repeat)
        throw std::invalid_argument("Invalid Mandarin feature span");
      std::vector<int64_t> ids(text.tokens.begin() + token, text.tokens.begin() + token + length);
      std::vector<int64_t> counts(text.repeats.begin() + repeat, text.repeats.begin() + repeat + length - 2);
      const int64_t size = std::accumulate(counts.begin(), counts.end(), int64_t{0});
      if (size <= 0 || static_cast<size_t>(size) > phones - phone)
        throw std::invalid_argument("Invalid Mandarin phone count");
      auto features = mandarin_bert(ids, counts, size);
      std::copy_n(features.GetTensorData<float>(), size * 1024, dst + phone * 1024);
      phone += size;
      token += length;
      repeat += length - 2;
    }
  }
  if (phone != phones || token != text.tokens.size() || repeat != text.repeats.size())
    throw std::invalid_argument("Unaligned multilingual phone features");
  return output;
}

Tensor Voice::mandarin_bert(const std::vector<int64_t>& input, const std::vector<int64_t>& counts, size_t phones) {
  const int64_t tokens = input.size();
  if (!phones || tokens < 3 || tokens > 512 || counts.size() + 2 != input.size())
    throw std::invalid_argument("Invalid GPT-SoVITS text features");
  auto ids = tensor(input, {1, tokens});
  auto mask = tensor(std::vector<int64_t>(tokens, 1), {1, tokens});
  auto repeats = tensor(counts, {tokens - 2});
  auto result = evaluate(roberta_, run_, {{"input_ids", &ids}, {"attention_mask", &mask}, {"repeats", &repeats}},
                         {"phone_features"});
  auto info = result[0].GetTensorTypeAndShapeInfo();
  auto shape = info.GetShape();
  if (shape != std::vector<int64_t>{static_cast<int64_t>(phones), 1024}) throw std::runtime_error("Unexpected RoBERTa phone features");
  if (info.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) return std::move(result[0]);
  if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16) throw std::runtime_error("Invalid RoBERTa precision");
  Ort::AllocatorWithDefaultOptions allocator;
  auto fp32 = Tensor::CreateTensor<float>(allocator, shape.data(), shape.size());
  auto source = result[0].GetTensorData<Ort::Float16_t>();
  std::transform(source, source + info.GetElementCount(), fp32.GetTensorMutableData<float>(),
                 [](Ort::Float16_t x) { return x.ToFloat(); });
  return fp32;
}

std::vector<float> Voice::encode_reference(const std::vector<float>& pcm32, const std::vector<float>& pcm16) {
  auto wav16 = tensor(pcm16, {1, static_cast<int64_t>(pcm16.size())});
  auto wav32 = tensor(pcm32, {1, static_cast<int64_t>(pcm32.size())});
  Tensor ssl{nullptr};
  {
    auto hubert = load(runtime_ / "chinese-hubert-base/chinese-hubert-base.onnx", 8);
    ssl = std::move(evaluate(hubert, run_, {{"input_values", &wav16}}, {"last_hidden_state"})[0]);
  }
  Tensor speaker{nullptr};
  {
    auto sv = load(runtime_ / "speaker_encoder.onnx", 8);
    speaker = std::move(evaluate(sv, run_, {{"waveform", &wav16}}, {"embedding"})[0]);
  }
  auto graph = load(model_ / "prompt_encoder_fp32.onnx", 8);
  auto condition = evaluate(graph, run_, {{"ref_audio", &wav32}, {"sv_emb", &speaker}}, {"ge", "ge_advanced"});
  // Both V2 templates export the same audio features. Keep only these reference-dependent
  // values on disk; the text prompt and autoregressive state have different lifetimes.
  auto ssl_shape = ssl.GetTensorTypeAndShapeInfo().GetShape();
  if (ssl_shape.size() != 3 || ssl_shape[0] != 1 || ssl_shape[1] != 768 || ssl_shape[2] <= 0 ||
      condition[0].GetTensorTypeAndShapeInfo().GetShape() != std::vector<int64_t>{1, 1024, 1} ||
      condition[1].GetTensorTypeAndShapeInfo().GetShape() != std::vector<int64_t>{1, 512, 1})
    throw std::runtime_error("Invalid V2 reference features");
  std::vector<float> result;
  result.reserve(1024 + 512 + ssl_shape[2] * 768);
  for (const auto* value : {&condition[0], &condition[1], &ssl}) {
    auto info = value->GetTensorTypeAndShapeInfo();
    if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)
      throw std::runtime_error("Invalid V2 reference feature type");
    auto data = value->GetTensorData<float>();
    result.insert(result.end(), data, data + info.GetElementCount());
  }
  return result;
}

void Voice::prepare(const Text& prompt, const std::vector<float>& reference) {
  constexpr size_t speaker_size = 1024 + 512;
  if (reference.size() <= speaker_size || (reference.size() - speaker_size) % 768 != 0)
    throw std::runtime_error("Invalid V2 reference feature length");
  auto frames = static_cast<int64_t>((reference.size() - speaker_size) / 768);
  ge_ = tensor(reference.data(), 1024, {1, 1024, 1});
  advanced_ = tensor(reference.data() + 1024, 512, {1, 512, 1});
  ssl_ = tensor(reference.data() + speaker_size, reference.size() - speaker_size, {1, 768, frames});
  roberta_ = load(runtime_ / "roberta-wwm-ext-large-onnx/model.onnx", 8);
  ref_phones_ = tensor(prompt.phones, {1, static_cast<int64_t>(prompt.phones.size())});
  ref_bert_ = bert(prompt);
  encoder_ = load(model_ / "t2s_encoder_fp32.onnx", 4);
  first_ = load(model_ / "mobile-prefill.onnx", 4);
  stage_ = load(model_ / "mobile-decoder-shared.onnx", 4);
  auto stop_type = stage_.GetOutputTypeInfo(2);
  auto info = stop_type.GetTensorTypeAndShapeInfo();
  if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL || info.GetElementCount() != 1)
    throw std::runtime_error("Invalid GPT-SoVITS decoder stop flag");
}

std::vector<int64_t> Voice::semantics(const Text& text, const std::vector<float>& controls) {
  using Clock = std::chrono::steady_clock;
  auto start_time = Clock::now();
  auto phones = tensor(text.phones, {1, static_cast<int64_t>(text.phones.size())});
  auto features = bert(text);
  auto bert_end = Clock::now();
  auto encoded = evaluate(encoder_, run_, {{"ref_seq", &ref_phones_}, {"text_seq", &phones},
      {"ref_bert", &ref_bert_}, {"text_bert", &features}, {"ssl_content", &ssl_}}, {"x", "prompts"});
  auto sampling = tensor(controls, {4});
  Inputs prefill{{"x", &encoded[0]}, {"prompts", &encoded[1]}, {"sampling", &sampling}};
  auto encoder_end = Clock::now();
  auto state = evaluate(first_, run_, prefill, ports.prefill);
  encoded.clear();
  auto prefill_end = Clock::now();
  auto shape = state[2].GetTensorTypeAndShapeInfo().GetShape();
  if (shape.size() != 3 || shape[0] != heads || shape[2] != head_width || shape[1] <= 0 ||
      shape[1] > std::numeric_limits<int32_t>::max() - static_cast<int64_t>(max_steps))
    throw std::runtime_error("Invalid V2 prefill KV shape");
  for (size_t i = 2; i < state.size(); ++i) {
    auto info = state[i].GetTensorTypeAndShapeInfo();
    if (info.GetShape() != shape || info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)
      throw std::runtime_error("Inconsistent V2 prefill KV tensors");
  }
  auto length = tensor<int32_t>({static_cast<int32_t>(shape[1])}, {1});
  auto& used = *length.GetTensorMutableData<int32_t>();
  int64_t capacity = used;
  std::array<const OrtValue*, kv_count + 4> inputs{};
  std::array<OrtValue*, kv_count + 3> outputs{};
  inputs[2 + kv_count] = length;
  inputs[3 + kv_count] = sampling;
  size_t completed = 0;
  while (completed < max_steps) {
    if (used == capacity) {
      int64_t next = capacity + std::min<size_t>(256, max_steps - completed);
      for (size_t i = 0; i < kv_count; ++i) {
        reserve_kv(state[2 + i], capacity, used, next);
        inputs[2 + i] = outputs[3 + i] = state[2 + i];
      }
      capacity = next;
    }
    inputs[0] = state[0];
    inputs[1] = state[1];
    outputs[0] = outputs[1] = outputs[2] = nullptr;
    auto status = Ort::GetApi().Run(stage_, run_, ports.input_names.data(), inputs.data(), inputs.size(),
                                    ports.output_names.data(), outputs.size(), outputs.data());
    // Take ownership even when a cancelled Run has materialized partial outputs.
    Tensor y(outputs[0]), embedding(outputs[1]), stop(outputs[2]);
    Ort::ThrowOnError(status);
    state[0] = std::move(y);
    state[1] = std::move(embedding);
    ++used;
    ++completed;
    if (*stop.GetTensorData<bool>()) break;
  }
  auto ms = [](auto a, auto b) { return std::chrono::duration<double, std::milli>(b - a).count(); };
  __android_log_print(ANDROID_LOG_DEBUG, "NextVolSoVITS",
      "shared_kv steps=%zu bert_ms=%.1f encoder_ms=%.1f prefill_ms=%.1f loop_ms=%.1f",
      completed, ms(start_time, bert_end), ms(bert_end, encoder_end),
      ms(encoder_end, prefill_end), ms(prefill_end, Clock::now()));
  const auto& y = state[0];
  auto info = y.GetTensorTypeAndShapeInfo();
  size_t count = info.GetElementCount();
  if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64 || count < completed)
    throw std::runtime_error("Invalid GPT-SoVITS decoder tokens");
  auto end = y.GetTensorData<int64_t>() + count;
  auto start = end - completed;
  auto special = std::find_if(start, end, [](int64_t id) { return id >= 1024 || id < 0; });
  std::vector<int64_t> tokens(start, special);
  if (!tokens.empty() && tokens.back() == 0) tokens.pop_back();
  if (tokens.empty()) throw std::runtime_error("GPT-SoVITS decoder did not complete a segment");
  return tokens;
}

std::vector<float> Voice::condition(bool advanced) const {
  const auto& value = advanced ? advanced_ : ge_;
  auto info = value.GetTensorTypeAndShapeInfo();
  auto shape = info.GetShape();
  if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT ||
      shape.size() != 3 || shape[0] != 1 || shape[2] != 1)
    throw std::runtime_error("Unexpected V2 speaker conditioning");
  auto ptr = value.GetTensorData<float>();
  return {ptr, ptr + info.GetElementCount()};
}

std::vector<int16_t> Voice::vocode(const std::vector<int64_t>& ids, const std::vector<int64_t>& tokens, double speech_speed) {
  if (!vits_) vits_ = load(model_ / "vits_fp32.onnx", 6, true);
  auto phones = tensor(ids, {1, static_cast<int64_t>(ids.size())});
  auto semantic = tensor(tokens, {1, 1, static_cast<int64_t>(tokens.size())});
  auto speed = tensor<double>({speech_speed}, {1});
  Inputs acoustic{{"text_seq", &phones}, {"pred_semantic", &semantic}, {"ge", &ge_}, {"ge_advanced", &advanced_}, {"speed", &speed}};
  auto audio = evaluate(vits_, run_, acoustic, {"audio"});
  auto info = audio[0].GetTensorTypeAndShapeInfo();
  if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT)
    throw std::runtime_error("Invalid GPT-SoVITS audio type");
  return pcm(audio[0].GetTensorData<float>(), info.GetElementCount());
}

std::vector<int16_t> Voice::pcm(const float* samples, size_t count) {
  if (!count || count > (8 * 1024 * 1024 - 44) / 2)
    throw std::runtime_error("Invalid GPT-SoVITS audio output");
  std::vector<int16_t> pcm(count);
  bool audible = false;
  for (size_t i = 0; i < count; ++i) {
    if (!std::isfinite(samples[i])) throw std::runtime_error("Non-finite GPT-SoVITS audio");
    pcm[i] = static_cast<int16_t>(std::lround(std::clamp(samples[i], -1.f, 1.f) * 32767));
    audible |= pcm[i] != 0;
  }
  if (!audible) throw std::runtime_error("Silent GPT-SoVITS audio");
  return pcm;
}
}  // namespace sovits
