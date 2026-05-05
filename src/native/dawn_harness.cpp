#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "dawn/dawn_proc.h"
#include "dawn/native/DawnNative.h"
#include "dawn/webgpu_cpp.h"

int main(int argc, char* argv[]) {
    if (argc != 2) {
        std::cerr << "Usage: dawn_harness <file.wgsl>\n";
        return 1;
    }

    std::ifstream file(argv[1]);
    if (!file) {
        std::cerr << "Cannot open file: " << argv[1] << "\n";
        return 1;
    }
    std::ostringstream ss;
    ss << file.rdbuf();
    std::string shaderSource = ss.str();

    // Wire up the Dawn native proc table (required when not going through a browser)
    dawnProcSetProcs(&dawn::native::GetProcs());

    // Create a Dawn native instance
    dawn::native::Instance nativeInstance;

    // Prefer the Null backend — no real GPU needed, just compilation/validation
    wgpu::RequestAdapterOptions adapterOptions = {};
//    adapterOptions.backendType = wgpu::BackendType::Null;

    auto adapters = nativeInstance.EnumerateAdapters(&adapterOptions);
    if (adapters.empty()) {
        // Fall back to whatever is available
        adapters = nativeInstance.EnumerateAdapters();
    }
    if (adapters.empty()) {
        std::cerr << "No WebGPU adapters found\n";
        return 1;
    }

    wgpu::Adapter adapter(adapters[0].Get());

    std::string deviceError;
    wgpu::DeviceDescriptor deviceDesc {};
    deviceDesc.SetUncapturedErrorCallback(
        [](const wgpu::Device&, wgpu::ErrorType, wgpu::StringView message, std::string* userdata) {
            *userdata = std::string(message.data, message.length);
        },
        &deviceError);

    wgpu::Device device = adapter.CreateDevice(&deviceDesc);
    if (!device) {
        std::cerr << "Failed to create WebGPU device\n";
        return 1;
    }



    // Build the shader module descriptor
    wgpu::ShaderModuleDescriptor moduleDesc {};
    wgpu::ShaderSourceWGSL wgslDesc {};
    wgslDesc.code = {shaderSource.c_str(), strlen(shaderSource.c_str())};
    moduleDesc.nextInChain = &wgslDesc;
    wgpu::ShaderModule shaderModule = device.CreateShaderModule(&moduleDesc);

    // GetCompilationInfo is async — collect results via callback, then tick
    struct Result {
        bool done = false;
        bool success = true;
        std::string message;
    } result;

    shaderModule.GetCompilationInfo(
        wgpu::CallbackMode::AllowSpontaneous,
        [](wgpu::CompilationInfoRequestStatus status,
           const wgpu::CompilationInfo* info,
           Result* r) {
            r->done = true;
            if (status != wgpu::CompilationInfoRequestStatus::Success) {
                r->success = false;
                r->message = "Compilation info request failed";
                return;
            }
            for (size_t i = 0; i < info->messageCount; i++) {
                const auto& msg = info->messages[i];
                if (msg.type == wgpu::CompilationMessageType::Error) {
                    r->success = false;
                    r->message += std::string(msg.message.data, msg.message.length);
                    r->message += "\n";
                }
            }
        },
        &result);

    // Flush async callbacks
    while (!result.done) {
        device.Tick();
    }

    if (!deviceError.empty()) {
        std::cerr << deviceError << "\n";
        return 1;
    }
    if (!result.success) {
        std::cerr << result.message;
        return 1;
    }

    return 0;
}
