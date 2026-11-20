#include <jni.h>
#include <vulkan/vulkan.h>
#include <dlfcn.h>
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace {
std::string gpuInfo() {
    VkInstanceCreateInfo createInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    VkInstance instance = VK_NULL_HANDLE;
    if (vkCreateInstance(&createInfo, nullptr, &instance) != VK_SUCCESS) return {};
    const auto destroy = [](VkInstance value) { vkDestroyInstance(value, nullptr); };
    const std::unique_ptr<VkInstance_T, decltype(destroy)> owned(instance, destroy);
    uint32_t count = 0;
    if (vkEnumeratePhysicalDevices(instance, &count, nullptr) != VK_SUCCESS) return {};
    std::vector<VkPhysicalDevice> devices(count);
    if (vkEnumeratePhysicalDevices(instance, &count, devices.data()) != VK_SUCCESS) return {};
    std::string result;
    for (uint32_t i = 0; i < count; ++i) {
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(devices[i], &properties);
        if (properties.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU || !properties.deviceName[0]) continue;
        if (!result.empty()) result += " / ";
        result += std::string(properties.deviceName) + " · Vulkan " +
            std::to_string(VK_VERSION_MAJOR(properties.apiVersion)) + "." +
            std::to_string(VK_VERSION_MINOR(properties.apiVersion)) + "." +
            std::to_string(VK_VERSION_PATCH(properties.apiVersion));
    }
    return result;
}

// Minimal ABI declarations for the bundled QNN 2.42 / core API 2.32.
// Only device metadata is accessed; the SDK's headers are not redistributed.
// Match QnnInterface.h, QnnDevice.h and HTP/QnnHtpDevice.h when upgrading QNN.
namespace qnn {
struct Version { uint32_t major, minor, patch; };
struct OnChip { size_t vtcmSize; uint32_t socModel; bool signedPd, dlbc; int32_t arch; };
struct Extension { int32_t type; OnChip onChip; };
struct HardwareV1 { uint32_t id, type, coreCount; void* cores; Extension* extension; };
struct Hardware { int32_t version; HardwareV1 v1; };
struct PlatformV1 { uint32_t count; Hardware* devices; };
struct Platform { int32_t version; PlatformV1 v1; };
struct Interface {
    uint32_t backendId;
    const char* name;
    Version core, backend;
    // property(1), backend(8), context(6), graph(8), tensor(2), log(3), profile(7), memory(2).
    void (*unused[37])();
    uint64_t (*getPlatformInfo)(void*, const Platform**);
    uint64_t (*freePlatformInfo)(void*, const Platform*);
};
}

std::string npuInfo(const std::string& directory) {
    const std::unique_ptr<void, decltype(&dlclose)> library(
        dlopen((directory + "/libQnnHtp.so").c_str(), RTLD_NOW | RTLD_LOCAL), dlclose);
    if (!library) return {};
    const auto providers = reinterpret_cast<uint64_t (*)(const qnn::Interface***, uint32_t*)>(
        dlsym(library.get(), "QnnInterface_getProviders"));
    const qnn::Interface** interfaces = nullptr;
    uint32_t count = 0;
    if (!providers || providers(&interfaces, &count) != 0) return {};
    for (uint32_t i = 0; i < count; ++i) {
        const auto& api = *interfaces[i];
        if (api.core.major != 2 || api.core.minor != 32 || !api.getPlatformInfo || !api.freePlatformInfo) continue;
        const qnn::Platform* platform = nullptr;
        if (api.getPlatformInfo(nullptr, &platform) != 0 || !platform) continue;
        const auto release = [&api](const qnn::Platform* value) { api.freePlatformInfo(nullptr, value); };
        const std::unique_ptr<const qnn::Platform, decltype(release)> owned(platform, release);
        if (platform->version != 1) continue;
        std::string result;
        for (uint32_t d = 0; d < platform->v1.count; ++d) {
            const auto& device = platform->v1.devices[d];
            if (device.version != 1) continue;
            const auto* extension = device.v1.extension;
            if (!extension || extension->type != 0) continue;
            const int32_t arch = extension->onChip.arch;
            if (arch <= 0 || arch == INT32_MAX) continue;
            if (!result.empty()) result += " / ";
            result += "Qualcomm HTP V" + std::to_string(arch);
        }
        return result;
    }
    return {};
}
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_indi_renakoni_nextvol_tts_sovits_DeviceAcceleration_hardwareInfo(
        JNIEnv* env, jclass, jstring runtime, jboolean queryNpu) {
    const char* directory = env->GetStringUTFChars(runtime, nullptr);
    if (!directory) return nullptr;
    std::string npu;
    std::string gpu;
    try {
        if (queryNpu) npu = npuInfo(directory);
        gpu = gpuInfo();
    } catch (const std::exception&) {
        // Missing metadata must not change the inference probe's capability result.
    }
    env->ReleaseStringUTFChars(runtime, directory);
    auto result = env->NewObjectArray(2, env->FindClass("java/lang/String"), nullptr);
    if (!result) return nullptr;
    env->SetObjectArrayElement(result, 0, env->NewStringUTF(gpu.c_str()));
    env->SetObjectArrayElement(result, 1, env->NewStringUTF(npu.c_str()));
    return result;
}
