#pragma once

#include <filesystem>
#include <string>
#include <vector>
#include "onnxruntime_cxx_api.h"

namespace sovits {

struct Text {
  std::vector<int64_t> phones, tokens, repeats, bert_runs;
};

// One voice owns one session. Only cancel() may overlap prepare() or generate().
class Voice {
 public:
  Voice(std::filesystem::path model, std::filesystem::path runtime);
  std::vector<float> encode_reference(const std::vector<float>& pcm32, const std::vector<float>& pcm16);
  void prepare(const Text& prompt, const std::vector<float>& reference);
  std::vector<int64_t> semantics(const Text& text, const std::vector<float>& sampling);
  std::vector<float> condition(bool advanced) const;
  std::vector<int16_t> vocode(const std::vector<int64_t>& phones, const std::vector<int64_t>& tokens, double speed);
  static std::vector<int16_t> pcm(const float* samples, size_t count);
  void cancel() { run_.SetTerminate(); }

 private:
  Ort::Session load(const std::filesystem::path& path, int thread_limit, bool vocoder = false);
  Ort::Value bert(const Text& text);
  Ort::Value mandarin_bert(const std::vector<int64_t>& ids, const std::vector<int64_t>& repeats, size_t phones);
  std::filesystem::path model_, runtime_;
  Ort::RunOptions run_;
  Ort::Session roberta_{nullptr}, encoder_{nullptr}, first_{nullptr}, stage_{nullptr}, vits_{nullptr};
  Ort::Value ref_phones_{nullptr}, ref_bert_{nullptr}, ssl_{nullptr}, ge_{nullptr}, advanced_{nullptr};
};
}  // namespace sovits
