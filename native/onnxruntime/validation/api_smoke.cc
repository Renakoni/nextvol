#include <iostream>
#include <string>

#include "onnxruntime_cxx_api.h"

int main() {
  const auto* base = OrtGetApiBase();
  if (!base || !base->GetApi(ORT_API_VERSION) ||
      std::string(base->GetVersionString()) != "1.17.1") {
    return 1;
  }
  Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "nextvol-ort-smoke");
  Ort::SessionOptions options;
  options.SetIntraOpNumThreads(2);
  const auto providers = Ort::GetAvailableProviders();
  if (providers.size() != 1 || providers[0] != "CPUExecutionProvider") {
    return 2;
  }
  const auto build_info = Ort::GetBuildInfoString();
  if (build_info.find("upstream-commit=8f5c79cb63f09ef1302e85081093a3fe4da1bc7d") ==
      std::string::npos) {
    return 3;
  }
  std::cout << "ONNX Runtime " << base->GetVersionString()
            << ", API " << ORT_API_VERSION << ", " << providers[0] << '\n';
  std::cout << build_info << '\n';
  return 0;
}
