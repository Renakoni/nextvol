#pragma once

#include <filesystem>
#include <string>
#include <vector>
#include "onnxruntime_cxx_api.h"

namespace sovits {

struct Text {
  std::vector<int64_t> phones, tokens, repeats;
};

// One voice owns one session. Only cancel() may overlap prepare() or generate().
class Voice {
 public:
  Voice(std::filesystem::path model, std::filesystem::path runtime, std::filesystem::path speaker);
  void prepare(const Text& prompt, const std::vector<float>& pcm32, const std::vector<float>& pcm16);
  std::vector<int64_t> semantics(const Text& text);
  std::vector<float> condition(bool advanced) const;
  std::vector<int16_t> vocode(const std::vector<int64_t>& phones, const std::vector<int64_t>& tokens);
  static std::vector<int16_t> pcm(const float* samples, size_t count);
  void cancel() { run_.SetTerminate(); }

 private:
  Ort::Session load(const std::filesystem::path& path);
  Ort::Value bert(const Text& text);
  std::filesystem::path model_, runtime_, speaker_;
  Ort::SessionOptions options_;
  Ort::RunOptions run_;
  Ort::Session roberta_{nullptr}, encoder_{nullptr}, first_{nullptr}, stage_{nullptr}, vits_{nullptr};
  Ort::Value ref_phones_{nullptr}, ref_bert_{nullptr}, ssl_{nullptr}, ge_{nullptr}, advanced_{nullptr};
  std::vector<std::string> stage_inputs_, first_outputs_, stage_outputs_;
  bool first_sampling_ = false, stage_sampling_ = false, speed_input_ = false;
};
}  // namespace sovits
