#include <jni.h>
#include <array>
#include <memory>
#include <sstream>
#include <stdexcept>
#include <vector>
#include "onnxruntime_cxx_api.h"

namespace {
Ort::Env &Environment() {
  static Ort::Env environment(ORT_LOGGING_LEVEL_WARNING, "NextVolNames");
  return environment;
}

void Fail(JNIEnv *env, const std::exception &error) {
  if (!env->ExceptionCheck())
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_indi_renakoni_nextvol_tts_names_NameModelNative_create(JNIEnv *env, jobject, jbyteArray model) {
  try {
    std::vector<uint8_t> bytes(env->GetArrayLength(model));
    env->GetByteArrayRegion(model, 0, bytes.size(), reinterpret_cast<jbyte *>(bytes.data()));
    if (env->ExceptionCheck()) return 0;
    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(1);
    return reinterpret_cast<jlong>(new Ort::Session(Environment(), bytes.data(), bytes.size(), options));
  } catch (const std::exception &error) { Fail(env, error); return 0; }
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_indi_renakoni_nextvol_tts_names_NameModelNative_transitions(JNIEnv *env, jobject, jlong handle) {
  try {
    auto &session = *reinterpret_cast<Ort::Session *>(handle);
    Ort::AllocatorWithDefaultOptions allocator;
    auto metadata = session.GetModelMetadata().LookupCustomMetadataMapAllocated("transition_matrix", allocator);
    if (!metadata) throw std::runtime_error("NER model: missing transition_matrix");
    std::istringstream stream(metadata.get());
    std::array<double, 9> values{};
    std::string part;
    for (auto &value : values) {
      if (!std::getline(stream, part, ',')) throw std::runtime_error("NER model: incomplete transition_matrix");
      value = std::stod(part);
    }
    auto result = env->NewDoubleArray(values.size());
    if (result) env->SetDoubleArrayRegion(result, 0, values.size(), values.data());
    return result;
  } catch (const std::exception &error) { Fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT jfloatArray JNICALL
Java_indi_renakoni_nextvol_tts_names_NameModelNative_emissions(JNIEnv *env, jobject, jlong handle, jintArray input) {
  try {
    std::array<int32_t, 30> ids{};
    env->GetIntArrayRegion(input, 0, ids.size(), ids.data());
    if (env->ExceptionCheck()) return nullptr;
    std::array<int64_t, 2> shape{1, 30};
    auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    auto tensor = Ort::Value::CreateTensor<int32_t>(memory, ids.data(), ids.size(), shape.data(), shape.size());
    const char *inputs[] = {"input_int32"};
    const char *outputs[] = {"output_float"};
    auto values = reinterpret_cast<Ort::Session *>(handle)->Run(Ort::RunOptions{nullptr}, inputs, &tensor, 1, outputs, 1);
    if (values[0].GetTensorTypeAndShapeInfo().GetShape() != std::vector<int64_t>{1, 30, 3})
      throw std::runtime_error("NER model: expected output_float [1,30,3]");
    auto result = env->NewFloatArray(90);
    if (result) env->SetFloatArrayRegion(result, 0, 90, values[0].GetTensorData<float>());
    return result;
  } catch (const std::exception &error) { Fail(env, error); return nullptr; }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_names_NameModelNative_destroy(JNIEnv *, jobject, jlong handle) {
  delete reinterpret_cast<Ort::Session *>(handle);
}
