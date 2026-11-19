#include "sovits.h"

#include <algorithm>
#include <cmath>
#include <stdexcept>

namespace sovits {
namespace {
using Tensor = Ort::Value;
using Inputs = std::vector<std::pair<std::string, const Tensor*>>;

template <typename T>
Tensor tensor(const std::vector<T>& data, std::initializer_list<int64_t> shape) {
  Ort::AllocatorWithDefaultOptions allocator;
  Tensor value = Tensor::CreateTensor<T>(allocator, shape.begin(), shape.size());
  if (value.GetTensorTypeAndShapeInfo().GetElementCount() != data.size())
    throw std::invalid_argument("Invalid GPT-SoVITS tensor shape");
  std::copy(data.begin(), data.end(), value.GetTensorMutableData<T>());
  return value;
}

std::vector<std::string> names(const Ort::Session& session, bool inputs) {
  Ort::AllocatorWithDefaultOptions allocator;
  std::vector<std::string> result;
  for (size_t i = 0; i < (inputs ? session.GetInputCount() : session.GetOutputCount()); ++i) {
    auto name = inputs ? session.GetInputNameAllocated(i, allocator) : session.GetOutputNameAllocated(i, allocator);
    result.emplace_back(name.get());
  }
  return result;
}

bool contains(const std::vector<std::string>& names, const std::string& key) {
  return std::find(names.begin(), names.end(), key) != names.end();
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

size_t position(const std::vector<std::string>& names, const std::string& key) {
  auto found = std::find(names.begin(), names.end(), key);
  if (found == names.end()) throw std::invalid_argument("Missing GPT-SoVITS decoder state: " + key);
  return found - names.begin();
}

std::vector<size_t> feedback(const std::vector<std::string>& inputs, const std::vector<std::string>& outputs) {
  std::vector<size_t> indices;
  for (const auto& input : inputs) {
    if (input == "iy") indices.push_back(position(outputs, "y"));
    else if (input == "iy_emb") indices.push_back(position(outputs, "y_emb"));
    else if (input.compare(0, 5, "past_") == 0) indices.push_back(position(outputs, "present_" + input.substr(5)));
    else throw std::invalid_argument("Unsupported GPT-SoVITS decoder input: " + input);
  }
  return indices;
}
}  // namespace

Voice::Voice(std::filesystem::path model, std::filesystem::path runtime)
    : model_(std::move(model)), runtime_(std::move(runtime)) {
  options_.SetIntraOpNumThreads(4);
  options_.SetInterOpNumThreads(1);
  options_.DisableCpuMemArena();
  options_.AddConfigEntry("session.intra_op.allow_spinning", "0");
}

Ort::Session Voice::load(const std::filesystem::path& path) {
  static Ort::Env environment(ORT_LOGGING_LEVEL_WARNING, "NextVolSoVITS");
  return Ort::Session(environment, path.c_str(), options_);
}

Tensor Voice::bert(const Text& text) {
  const int64_t tokens = text.tokens.size(), phones = text.phones.size();
  if (!phones || tokens < 3 || tokens > 512 || text.repeats.size() + 2 != text.tokens.size())
    throw std::invalid_argument("Invalid GPT-SoVITS text features");
  auto ids = tensor(text.tokens, {1, tokens});
  auto mask = tensor(std::vector<int64_t>(tokens, 1), {1, tokens});
  auto repeats = tensor(text.repeats, {tokens - 2});
  auto result = evaluate(roberta_, run_, {{"input_ids", &ids}, {"attention_mask", &mask}, {"repeats", &repeats}},
                         {"phone_features"});
  auto info = result[0].GetTensorTypeAndShapeInfo();
  auto shape = info.GetShape();
  if (shape != std::vector<int64_t>{phones, 1024}) throw std::runtime_error("Unexpected RoBERTa phone features");
  if (info.GetElementType() == ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT) return std::move(result[0]);
  if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT16) throw std::runtime_error("Invalid RoBERTa precision");
  Ort::AllocatorWithDefaultOptions allocator;
  auto fp32 = Tensor::CreateTensor<float>(allocator, shape.data(), shape.size());
  auto source = result[0].GetTensorData<Ort::Float16_t>();
  std::transform(source, source + info.GetElementCount(), fp32.GetTensorMutableData<float>(),
                 [](Ort::Float16_t x) { return x.ToFloat(); });
  return fp32;
}

void Voice::prepare(const Text& prompt, const std::vector<float>& pcm32, const std::vector<float>& pcm16) {
  auto wav16 = tensor(pcm16, {1, static_cast<int64_t>(pcm16.size())});
  auto wav32 = tensor(pcm32, {1, static_cast<int64_t>(pcm32.size())});
  {
    auto hubert = load(runtime_ / "chinese-hubert-base/chinese-hubert-base.onnx");
    ssl_ = std::move(evaluate(hubert, run_, {{"input_values", &wav16}}, {"last_hidden_state"})[0]);
  }
  Tensor speaker{nullptr};
  {
    auto sv = load(runtime_ / "speaker_encoder.onnx");
    speaker = std::move(evaluate(sv, run_, {{"waveform", &wav16}}, {"embedding"})[0]);
  }
  {
    auto graph = load(model_ / "prompt_encoder_fp32.onnx");
    auto condition = evaluate(graph, run_, {{"ref_audio", &wav32}, {"sv_emb", &speaker}}, {"ge", "ge_advanced"});
    ge_ = std::move(condition[0]);
    advanced_ = std::move(condition[1]);
  }
  roberta_ = load(runtime_ / "roberta-wwm-ext-large-onnx/model.onnx");
  ref_phones_ = tensor(prompt.phones, {1, static_cast<int64_t>(prompt.phones.size())});
  ref_bert_ = bert(prompt);
  encoder_ = load(model_ / "t2s_encoder_fp32.onnx");
  first_ = load(model_ / "t2s_first_stage_decoder_fp32.onnx");
  stage_ = load(model_ / "t2s_stage_decoder_fp32.onnx");
  first_sampling_ = contains(names(first_, true), "sampling");
  stage_inputs_ = names(stage_, true);
  stage_sampling_ = contains(stage_inputs_, "sampling");
  stage_inputs_.erase(std::remove(stage_inputs_.begin(), stage_inputs_.end(), "sampling"), stage_inputs_.end());
  first_outputs_ = names(first_, false);
  stage_outputs_ = names(stage_, false);
  first_map_ = feedback(stage_inputs_, first_outputs_);
  stage_map_ = feedback(stage_inputs_, stage_outputs_);
  stop_index_ = position(stage_outputs_, "stop_condition_tensor");
  y_index_ = position(stage_outputs_, "y");
  auto stop_type = stage_.GetOutputTypeInfo(stop_index_);
  auto info = stop_type.GetTensorTypeAndShapeInfo();
  if (info.GetElementType() != ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL || info.GetElementCount() != 1)
    throw std::runtime_error("Invalid GPT-SoVITS decoder stop flag");
}

std::vector<int64_t> Voice::semantics(const Text& text) {
  auto phones = tensor(text.phones, {1, static_cast<int64_t>(text.phones.size())});
  auto features = bert(text);
  auto encoded = evaluate(encoder_, run_, {{"ref_seq", &ref_phones_}, {"text_seq", &phones},
      {"ref_bert", &ref_bert_}, {"text_bert", &features}, {"ssl_content", &ssl_}}, {"x", "prompts"});
  auto sampling = tensor<float>({1.f, 15.f, 1.f, 1.35f}, {4});
  Inputs prefill{{"x", &encoded[0]}, {"prompts", &encoded[1]}};
  if (first_sampling_) prefill.emplace_back("sampling", &sampling);
  auto state = evaluate(first_, run_, prefill, first_outputs_);
  encoded.clear();
  size_t completed = 0;
  // V2 semantics run at 25 Hz. The cap is below NextVol's 8 MiB PCM clip limit.
  constexpr size_t max_steps = 1500;
  while (completed < max_steps) {
    Inputs inputs;
    const auto& indices = completed == 0 ? first_map_ : stage_map_;
    for (size_t i = 0; i < indices.size(); ++i) inputs.emplace_back(stage_inputs_[i], &state[indices[i]]);
    if (stage_sampling_) inputs.emplace_back("sampling", &sampling);
    state = evaluate(stage_, run_, inputs, stage_outputs_);
    const auto& stop = state[stop_index_];
    ++completed;
    if (*stop.GetTensorData<bool>()) break;
  }
  const auto& y = state[y_index_];
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

std::vector<int16_t> Voice::vocode(const std::vector<int64_t>& ids, const std::vector<int64_t>& tokens) {
  if (!vits_) {
    vits_ = load(model_ / "vits_fp32.onnx");
    speed_input_ = contains(names(vits_, true), "speed");
  }
  auto phones = tensor(ids, {1, static_cast<int64_t>(ids.size())});
  auto semantic = tensor(tokens, {1, 1, static_cast<int64_t>(tokens.size())});
  auto speed = tensor<double>({1.0}, {1});
  Inputs acoustic{{"text_seq", &phones}, {"pred_semantic", &semantic}, {"ge", &ge_}, {"ge_advanced", &advanced_}};
  if (speed_input_) acoustic.emplace_back("speed", &speed);
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
