#include <jni.h>
#include <android/log.h>
#include <chrono>
#include <dlfcn.h>
#include <cstdlib>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include "onnxruntime_c_api.h"

namespace {
struct Session {
    void* library = nullptr;
    const OrtApi* api = nullptr;
    OrtEnv* env = nullptr;
    OrtSessionOptions* options = nullptr;
    OrtSession* session = nullptr;
    OrtRunOptions* run = nullptr;
    OrtMemoryInfo* memory = nullptr;
    std::string inputName;
    std::vector<int64_t> inputShape;
    size_t inputCount = 0;
    std::string outputName;
    std::vector<int64_t> outputShape;
    size_t outputCount = 0;

    void check(OrtStatus* status) {
        if (!status) return;
        std::string message = api->GetErrorMessage(status);
        api->ReleaseStatus(status);
        throw std::runtime_error(message);
    }
    ~Session() {
        if (api) {
            if (run) api->ReleaseRunOptions(run);
            if (session) api->ReleaseSession(session);
            if (options) api->ReleaseSessionOptions(options);
            if (memory) api->ReleaseMemoryInfo(memory);
            if (env) api->ReleaseEnv(env);
        }
        if (library) dlclose(library);
    }
};
std::string string(JNIEnv* env, jstring value) {
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string result(chars);
    env->ReleaseStringUTFChars(value, chars);
    return result;
}
void fail(JNIEnv* env, const std::exception& error) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}

// The QNN runtime owns ALL its Ort handles. Java/WebGPU OrtValues never cross this boundary.
extern "C" JNIEXPORT jlong JNICALL
Java_indi_renakoni_nextvol_tts_sovits_QnnSession_create(
        JNIEnv* env, jclass, jstring runtime, jstring model, jstring cache) {
    try {
        auto s = std::make_unique<Session>();
        const auto directory = string(env, runtime);
        const auto modelPath = string(env, model);
        const auto cachePath = string(env, cache);
        const auto dspPath = directory + ";/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";
        setenv("ADSP_LIBRARY_PATH", dspPath.c_str(), 1);
        s->library = dlopen((directory + "/libonnxruntime-qnn.so").c_str(), RTLD_NOW | RTLD_LOCAL);
        if (!s->library) throw std::runtime_error(dlerror());
        auto getApi = reinterpret_cast<const OrtApiBase*(*)()>(dlsym(s->library, "OrtGetApiBase"));
        if (!getApi || !(s->api = getApi()->GetApi(ORT_API_VERSION))) throw std::runtime_error("QNN ORT API unavailable");
        s->check(s->api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "NextVolQNN", &s->env));
        s->check(s->api->CreateSessionOptions(&s->options));
        s->check(s->api->SetIntraOpNumThreads(s->options, 4));
        s->check(s->api->AddSessionConfigEntry(s->options, "session.intra_op.allow_spinning", "0"));
        s->check(s->api->SetSessionGraphOptimizationLevel(s->options, ORT_ENABLE_BASIC));
        const auto backendPath = directory + "/libQnnHtp.so";
        const char* keys[] = {"backend_path", "enable_htp_fp16_precision", "htp_performance_mode",
                              "htp_graph_finalization_optimization_mode"};
        const char* values[] = {backendPath.c_str(), "1", "power_saver", "3"};
        s->check(s->api->SessionOptionsAppendExecutionProvider(s->options, "QNN", keys, values, 4));
        s->check(s->api->AddSessionConfigEntry(s->options, "session.disable_cpu_ep_fallback", "1"));
        const bool cached = std::ifstream(cachePath).good();
        if (!cached && !cachePath.empty()) {
            s->check(s->api->AddSessionConfigEntry(s->options, "ep.context_enable", "1"));
            s->check(s->api->AddSessionConfigEntry(s->options, "ep.context_embed_mode", "1"));
            s->check(s->api->AddSessionConfigEntry(s->options, "ep.context_file_path", cachePath.c_str()));
        }
        auto started = std::chrono::steady_clock::now();
        s->check(s->api->CreateSession(s->env, (cached ? cachePath : modelPath).c_str(), s->options, &s->session));
        if (!cachePath.empty()) {
            if (!cached) {
                // Keep the same compiled graph, but release the source graph and compilation session.
                s->api->ReleaseSession(s->session); s->session = nullptr;
                __android_log_print(ANDROID_LOG_INFO, "NextVolSoVITS", "qnn_compile_ms=%lld ms",
                    static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
                        std::chrono::steady_clock::now() - started).count()));
                s->check(s->api->AddSessionConfigEntry(s->options, "ep.context_enable", "0"));
                started = std::chrono::steady_clock::now();
                s->check(s->api->CreateSession(s->env, cachePath.c_str(), s->options, &s->session));
            }
            __android_log_print(ANDROID_LOG_INFO, "NextVolSoVITS", "qnn_load_ms=%lld ms",
                static_cast<long long>(std::chrono::duration_cast<std::chrono::milliseconds>(
                    std::chrono::steady_clock::now() - started).count()));
        }
        s->api->ReleaseSessionOptions(s->options); s->options = nullptr;
        s->check(s->api->CreateRunOptions(&s->run));
        s->check(s->api->AddRunConfigEntry(s->run, "qnn.htp_perf_mode", "balanced"));
        s->check(s->api->AddRunConfigEntry(s->run, "qnn.htp_perf_mode_post_run", "power_saver"));
        s->check(s->api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &s->memory));
        OrtAllocator* allocator = nullptr;
        s->check(s->api->GetAllocatorWithDefaultOptions(&allocator));
        char* outputName = nullptr;
        s->check(s->api->SessionGetOutputName(s->session, 0, allocator, &outputName));
        s->outputName = outputName; allocator->Free(allocator, outputName);
        OrtTypeInfo* outputInfo = nullptr;
        s->check(s->api->SessionGetOutputTypeInfo(s->session, 0, &outputInfo));
        const auto releaseType = [api = s->api](OrtTypeInfo* info) { api->ReleaseTypeInfo(info); };
        const std::unique_ptr<OrtTypeInfo, decltype(releaseType)> ownedOutputInfo(outputInfo, releaseType);
        const OrtTensorTypeAndShapeInfo* outputTensor = nullptr;
        s->check(s->api->CastTypeInfoToTensorInfo(outputInfo, &outputTensor));
        s->check(s->api->GetTensorShapeElementCount(outputTensor, &s->outputCount));
        size_t outputRank = 0;
        s->check(s->api->GetDimensionsCount(outputTensor, &outputRank));
        s->outputShape.resize(outputRank);
        s->check(s->api->GetDimensions(outputTensor, s->outputShape.data(), outputRank));
        size_t count = 0;
        s->check(s->api->SessionGetInputCount(s->session, &count));
        if (count != 1) throw std::runtime_error("Expected one V2 HTP input");
        char* name = nullptr;
        s->check(s->api->SessionGetInputName(s->session, 0, allocator, &name));
        s->inputName = name; allocator->Free(allocator, name);
        OrtTypeInfo* info = nullptr;
        s->check(s->api->SessionGetInputTypeInfo(s->session, 0, &info));
        const std::unique_ptr<OrtTypeInfo, decltype(releaseType)> ownedInfo(info, releaseType);
        const OrtTensorTypeAndShapeInfo* tensor = nullptr;
        s->check(s->api->CastTypeInfoToTensorInfo(info, &tensor));
        size_t rank = 0;
        s->check(s->api->GetDimensionsCount(tensor, &rank));
        s->inputShape.resize(rank);
        s->check(s->api->GetDimensions(tensor, s->inputShape.data(), rank));
        s->check(s->api->GetTensorShapeElementCount(tensor, &s->inputCount));
        return reinterpret_cast<jlong>(s.release());
    } catch (const std::exception& error) { fail(env, error); return 0; }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_sovits_QnnSession_run(
        JNIEnv* env, jclass, jlong handle, jobject buffer, jobject output) {
    auto& s = *reinterpret_cast<Session*>(handle);
    OrtValue* input = nullptr;
    OrtValue* result = nullptr;
    try {
        auto data = env->GetDirectBufferAddress(buffer);
        if (!data || env->GetDirectBufferCapacity(buffer) != static_cast<jlong>(s.inputCount))
            throw std::runtime_error("QNN input buffer shape");
        s.check(s.api->CreateTensorWithDataAsOrtValue(s.memory, data, s.inputCount * sizeof(float),
            s.inputShape.data(), s.inputShape.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &input));
        const char* inputName = s.inputName.c_str();
        const OrtValue* inputs[] = {input};
        const char* outputName = s.outputName.c_str();
        auto destination = env->GetDirectBufferAddress(output);
        if (!destination || env->GetDirectBufferCapacity(output) != static_cast<jlong>(s.outputCount)) throw std::runtime_error("QNN output buffer shape");
        s.check(s.api->CreateTensorWithDataAsOrtValue(s.memory, destination, s.outputCount * sizeof(float),
            s.outputShape.data(), s.outputShape.size(), ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &result));
        s.check(s.api->Run(s.session, s.run, &inputName, inputs, 1, &outputName, 1, &result));
    } catch (const std::exception& error) { fail(env, error); }
    if (result) s.api->ReleaseValue(result);
    if (input) s.api->ReleaseValue(input);
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_sovits_QnnSession_terminate(JNIEnv* env, jclass, jlong handle) {
    auto& s = *reinterpret_cast<Session*>(handle);
    try { s.check(s.api->RunOptionsSetTerminate(s.run)); }
    catch (const std::exception& error) { fail(env, error); }
}

extern "C" JNIEXPORT void JNICALL
Java_indi_renakoni_nextvol_tts_sovits_QnnSession_destroy(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
