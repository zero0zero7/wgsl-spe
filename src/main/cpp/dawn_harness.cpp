#include <jni.h>
#include <nlohmann/json.hpp>

#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "dawn/dawn_proc.h"
#include "dawn/native/DawnNative.h"
#include "dawn/webgpu_cpp.h"

using json = nlohmann::json;

// ── Buffer types ──────────────────────────────────────────────────────────────

enum class BufferUsage { Uniform, StorageR, StorageRW };

struct BufferData {
    uint32_t group, binding;
    BufferUsage usage;
    std::vector<uint8_t> data;
};

// Parse [{group, binding, bufferType, data}] JSON produced by Kotlin.
// bufferType is "uniform", "storage_r", or "storage_rw".
// data bytes are already sized correctly by Kotlin's bufferSizeInBytes().
static std::vector<BufferData> parseBuffers(const std::string& buffersJson) {
    json j = json::parse(buffersJson);
    std::vector<BufferData> result;
    for (const auto& entry : j) {
        BufferData bd;
        bd.group   = entry.at("group").get<uint32_t>();
        bd.binding = entry.at("binding").get<uint32_t>();
        std::string bt = entry.at("bufferType").get<std::string>();
        if (bt == "storage_rw")     bd.usage = BufferUsage::StorageRW;
        else if (bt == "storage_r") bd.usage = BufferUsage::StorageR;
        else                         bd.usage = BufferUsage::Uniform;
        for (int b : entry.at("data")) bd.data.push_back(static_cast<uint8_t>(b));
        result.push_back(std::move(bd));
    }
    return result;
}

// ── Device + shader helpers ───────────────────────────────────────────────────

static wgpu::Device makeDevice(wgpu::Adapter& adapter, std::string& outError) {
    wgpu::DeviceDescriptor desc{};
    desc.SetUncapturedErrorCallback(
        [](const wgpu::Device&, wgpu::ErrorType, wgpu::StringView msg, std::string* err) {
            *err = std::string(msg.data, msg.length);
        },
        &outError);
    return adapter.CreateDevice(&desc);
}

static bool compileShader(wgpu::Device& device, const std::string& source,
                          wgpu::ShaderModule& outModule, std::string& outError) {
    wgpu::ShaderSourceWGSL wgslDesc{};
    wgslDesc.code = {source.c_str(), source.size()};
    wgpu::ShaderModuleDescriptor moduleDesc{};
    moduleDesc.nextInChain = &wgslDesc;
    outModule = device.CreateShaderModule(&moduleDesc);

    struct R { bool done = false; bool ok = true; std::string msg; };
    R r;
    outModule.GetCompilationInfo(
        wgpu::CallbackMode::AllowSpontaneous,
        [](wgpu::CompilationInfoRequestStatus status,
           const wgpu::CompilationInfo* info, R* r) {
            r->done = true;
            if (status != wgpu::CompilationInfoRequestStatus::Success) {
                r->ok = false; r->msg = "Compilation info request failed"; return;
            }
            for (size_t i = 0; i < info->messageCount; i++) {
                if (info->messages[i].type == wgpu::CompilationMessageType::Error) {
                    r->ok = false;
                    r->msg += std::string(info->messages[i].message.data,
                                         info->messages[i].message.length) + "\n";
                }
            }
        },
        &r);
    while (!r.done) device.Tick();
    outError = r.msg;
    return r.ok;
}

// ── Compute execution + readback ──────────────────────────────────────────────

static json runCompute(wgpu::Device& device,
                       wgpu::ShaderModule& shaderModule,
                       const std::string& entryPoint,
                       const std::vector<BufferData>& buffers) {
    wgpu::Queue queue = device.GetQueue();

    struct GpuBuffer {
        uint32_t group, binding;
        BufferUsage usage;
        wgpu::Buffer buf;
        uint64_t size;
    };
    std::vector<GpuBuffer> gpuBuffers;
    int maxGroup = -1;

    for (const auto& bd : buffers) {
        wgpu::BufferUsage wgpuUsage{};
        switch (bd.usage) {
            case BufferUsage::Uniform:
                wgpuUsage = wgpu::BufferUsage::Uniform | wgpu::BufferUsage::CopyDst;
                break;
            case BufferUsage::StorageR:
                wgpuUsage = wgpu::BufferUsage::Storage | wgpu::BufferUsage::CopyDst;
                break;
            case BufferUsage::StorageRW:
                wgpuUsage = wgpu::BufferUsage::Storage
                          | wgpu::BufferUsage::CopyDst
                          | wgpu::BufferUsage::CopySrc;
                break;
        }

        // data is already correctly sized by Kotlin; 16 is the WebGPU minimum.
        uint64_t bufSize = std::max(bd.data.size(), size_t(16));
        wgpu::BufferDescriptor bufDesc{};
        bufDesc.size  = bufSize;
        bufDesc.usage = wgpuUsage;
        wgpu::Buffer gpuBuf = device.CreateBuffer(&bufDesc);
        if (!bd.data.empty())
            queue.WriteBuffer(gpuBuf, 0, bd.data.data(), bd.data.size());

        maxGroup = std::max(maxGroup, (int)bd.group);
        gpuBuffers.push_back({bd.group, bd.binding, bd.usage, gpuBuf, bufSize});
    }

    wgpu::ComputePipelineDescriptor pipelineDesc{};
    pipelineDesc.compute.module     = shaderModule;
    pipelineDesc.compute.entryPoint = {entryPoint.c_str(), entryPoint.size()};
    wgpu::ComputePipeline pipeline = device.CreateComputePipeline(&pipelineDesc);
    if (!pipeline) throw std::runtime_error("Failed to create compute pipeline");

    std::map<uint32_t, std::vector<wgpu::BindGroupEntry>> entriesPerGroup;
    for (const auto& gb : gpuBuffers) {
        wgpu::BindGroupEntry e{};
        e.binding = gb.binding;
        e.buffer  = gb.buf;
        e.offset  = 0;
        e.size    = gb.size;
        entriesPerGroup[gb.group].push_back(e);
    }

    wgpu::CommandEncoder encoder = device.CreateCommandEncoder(nullptr);
    wgpu::ComputePassEncoder pass = encoder.BeginComputePass(nullptr);
    pass.SetPipeline(pipeline);
    for (int g = 0; g <= maxGroup; g++) {
        auto it = entriesPerGroup.find(g);
        if (it == entriesPerGroup.end()) continue;
        wgpu::BindGroupDescriptor bgd{};
        bgd.layout     = pipeline.GetBindGroupLayout(g);
        bgd.entryCount = it->second.size();
        bgd.entries    = it->second.data();
        pass.SetBindGroup(g, device.CreateBindGroup(&bgd));
    }
    pass.DispatchWorkgroups(1, 1, 1);
    pass.End();
    wgpu::CommandBuffer dispatchCmd = encoder.Finish();
    queue.Submit(1, &dispatchCmd);

    json result = json::array();
    for (const auto& gb : gpuBuffers) {
        if (gb.usage != BufferUsage::StorageRW) continue;

        wgpu::BufferDescriptor rbDesc{};
        rbDesc.size  = gb.size;
        rbDesc.usage = wgpu::BufferUsage::CopyDst | wgpu::BufferUsage::MapRead;
        wgpu::Buffer rbBuf = device.CreateBuffer(&rbDesc);

        wgpu::CommandEncoder cpEnc = device.CreateCommandEncoder(nullptr);
        cpEnc.CopyBufferToBuffer(gb.buf, 0, rbBuf, 0, gb.size);
        wgpu::CommandBuffer cpCmd = cpEnc.Finish();
        queue.Submit(1, &cpCmd);

        struct MapRes { bool done = false; bool ok = false; };
        MapRes mr;
        rbBuf.MapAsync(
            wgpu::MapMode::Read, 0, gb.size,
            wgpu::CallbackMode::AllowSpontaneous,
            [](wgpu::MapAsyncStatus status, wgpu::StringView, MapRes* r) {
                r->done = true; r->ok = (status == wgpu::MapAsyncStatus::Success);
            },
            &mr);
        while (!mr.done) device.Tick();
        if (!mr.ok) throw std::runtime_error("Buffer mapping failed");

        const uint8_t* ptr = static_cast<const uint8_t*>(
            rbBuf.GetConstMappedRange(0, gb.size));
        json dataArr = json::array();
        for (uint64_t i = 0; i < gb.size; i++) dataArr.push_back(ptr[i]);
        rbBuf.Unmap();

        result.push_back({{"group", gb.group}, {"binding", gb.binding}, {"data", dataArr}});
    }
    return result;
}

// ── Shared execution core ─────────────────────────────────────────────────────

static json executeShaderInternal(const std::string& source,
                                  const std::string& entryPoint,
                                  const std::string& buffersJson) {
    dawnProcSetProcs(&dawn::native::GetProcs());
    dawn::native::Instance instance;
    auto adapters = instance.EnumerateAdapters();
    if (adapters.empty()) throw std::runtime_error("No WebGPU adapters found");
    wgpu::Adapter adapter(adapters[0].Get());

    std::string deviceError;
    wgpu::Device device = makeDevice(adapter, deviceError);
    if (!device) throw std::runtime_error("Failed to create device");

    wgpu::ShaderModule shaderModule;
    std::string compileError;
    if (!compileShader(device, source, shaderModule, compileError))
        throw std::runtime_error(compileError);
    if (!deviceError.empty()) throw std::runtime_error(deviceError);

    return runCompute(device, shaderModule, entryPoint, parseBuffers(buffersJson));
}

// ── JNI entry point ───────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jstring JNICALL
Java_com_wgslspe_core_DawnHarness_executeShader(
    JNIEnv* env, jobject,
    jstring jShaderSource, jstring jEntryPoint, jstring jBuffersJson)
{
    const char* src   = env->GetStringUTFChars(jShaderSource, nullptr);
    const char* entry = env->GetStringUTFChars(jEntryPoint,   nullptr);
    const char* bufs  = env->GetStringUTFChars(jBuffersJson,  nullptr);

    jstring jResult = nullptr;
    try {
        std::string resultStr = executeShaderInternal(src, entry, bufs).dump();
        jResult = env->NewStringUTF(resultStr.c_str());
    } catch (const std::exception& e) {
        env->ReleaseStringUTFChars(jShaderSource, src);
        env->ReleaseStringUTFChars(jEntryPoint,   entry);
        env->ReleaseStringUTFChars(jBuffersJson,  bufs);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return nullptr;
    }

    env->ReleaseStringUTFChars(jShaderSource, src);
    env->ReleaseStringUTFChars(jEntryPoint,   entry);
    env->ReleaseStringUTFChars(jBuffersJson,  bufs);
    return jResult;
}

// ── Standalone main (for debugging) ──────────────────────────────────────────
// Expects uniforms JSON: {"entryPoint":"...", "buffers":[{group,binding,bufferType,data}]}

int main(int argc, char* argv[]) {
    if (argc < 2 || argc > 4) {
        std::cerr << "Usage: dawn_harness <shader.wgsl> [uniforms.json [output.json]]\n";
        return 1;
    }

    std::string shaderPath  = argv[1];
    std::string uniformPath = argc >= 3 ? argv[2] : "";
    std::string outputPath  = argc >= 4 ? argv[3] : "";

    std::ifstream f(shaderPath);
    if (!f) { std::cerr << "Cannot open: " << shaderPath << "\n"; return 1; }
    std::ostringstream ss; ss << f.rdbuf();
    std::string source = ss.str();

    // Compile-only mode
    if (uniformPath.empty()) {
        dawnProcSetProcs(&dawn::native::GetProcs());
        dawn::native::Instance instance;
        auto adapters = instance.EnumerateAdapters();
        if (adapters.empty()) { std::cerr << "No WebGPU adapters found\n"; return 1; }
        wgpu::Adapter adapter(adapters[0].Get());
        std::string deviceError;
        wgpu::Device device = makeDevice(adapter, deviceError);
        if (!device) { std::cerr << "Failed to create device\n"; return 1; }
        wgpu::ShaderModule shaderModule;
        std::string compileError;
        if (!compileShader(device, source, shaderModule, compileError)) {
            std::cerr << compileError; return 1;
        }
        if (!deviceError.empty()) { std::cerr << deviceError << "\n"; return 1; }
        return 0;
    }

    std::ifstream uf(uniformPath);
    if (!uf) { std::cerr << "Cannot open: " << uniformPath << "\n"; return 1; }
    json uniformsJson;
    try { uniformsJson = json::parse(uf); }
    catch (const std::exception& e) { std::cerr << "JSON parse error: " << e.what() << "\n"; return 1; }

    std::string entryPoint  = uniformsJson.at("entryPoint").get<std::string>();
    std::string buffersJson = uniformsJson.at("buffers").dump();

    json result;
    try { result = executeShaderInternal(source, entryPoint, buffersJson); }
    catch (const std::exception& e) { std::cerr << e.what() << "\n"; return 1; }
    std::string resultStr = result.dump(2);
    if (outputPath.empty()) {
        std::cout << resultStr << "\n";
    } else {
        std::ofstream out(outputPath);
        if (!out) { std::cerr << "Cannot write: " << outputPath << "\n"; return 1; }
        out << resultStr << "\n";
        std::cout << "Result written to " << outputPath << "\n";
    }
    return 0;
}
