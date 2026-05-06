#include <nlohmann/json.hpp>

#include <cctype>
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

// ── WGSL source scanning ──────────────────────────────────────────────────────

enum class BufferUsage { Uniform, StorageR, StorageRW };

// Scan source for @group(x) @binding(y) var<...> declarations and return
// a map from (group, binding) to buffer usage.
static std::map<std::pair<uint32_t,uint32_t>, BufferUsage>
detectBufferUsages(const std::string& src) {
    std::map<std::pair<uint32_t,uint32_t>, BufferUsage> result;
    size_t pos = 0;
    while (pos < src.size()) {
        size_t gPos = src.find("@group(", pos);
        if (gPos == std::string::npos) break;

        size_t gEnd = src.find(')', gPos + 7);
        if (gEnd == std::string::npos) { pos = gPos + 1; continue; }
        uint32_t group = std::stoul(src.substr(gPos + 7, gEnd - (gPos + 7)));

        size_t bPos = src.find("@binding(", gEnd);
        if (bPos == std::string::npos || bPos > gEnd + 200) { pos = gPos + 1; continue; }
        size_t bEnd = src.find(')', bPos + 9);
        if (bEnd == std::string::npos) { pos = gPos + 1; continue; }
        uint32_t binding = std::stoul(src.substr(bPos + 9, bEnd - (bPos + 9)));

        size_t vPos = src.find("var<", bEnd);
        if (vPos == std::string::npos || vPos > bEnd + 200) { pos = gPos + 1; continue; }
        size_t vEnd = src.find('>', vPos + 4);
        if (vEnd == std::string::npos) { pos = gPos + 1; continue; }
        std::string accessMode = src.substr(vPos + 4, vEnd - (vPos + 4));

        BufferUsage usage = BufferUsage::Uniform;
        if (accessMode.find("storage") != std::string::npos) {
            usage = accessMode.find("read_write") != std::string::npos
                        ? BufferUsage::StorageRW
                        : BufferUsage::StorageR;
        }
        result[{group, binding}] = usage;
        pos = gPos + 1;
    }
    return result;
}

// Scan source for the first @compute entry point name.
static std::string detectComputeEntry(const std::string& src) {
    size_t pos = src.find("@compute");
    if (pos == std::string::npos) return "";
    size_t fnPos = src.find("fn ", pos);
    if (fnPos == std::string::npos) return "";
    fnPos += 3;
    size_t end = fnPos;
    while (end < src.size() && (std::isalnum((unsigned char)src[end]) || src[end] == '_'))
        ++end;
    return src.substr(fnPos, end - fnPos);
}

// ── uniforms.json loading ─────────────────────────────────────────────────────

struct BufferData {
    uint32_t group, binding;
    std::vector<uint8_t> data;
};

static std::vector<BufferData> loadUniforms(const std::string& path) {
    std::ifstream f(path);
    if (!f) throw std::runtime_error("Cannot open uniforms file: " + path);
    json j = json::parse(f);
    std::vector<BufferData> result;
    for (const auto& entry : j) {
        BufferData bd;
        bd.group   = entry.at("group").get<uint32_t>();
        bd.binding = entry.at("binding").get<uint32_t>();
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
                       const std::vector<BufferData>& uniformData,
                       const std::map<std::pair<uint32_t,uint32_t>, BufferUsage>& usages) {
    wgpu::Queue queue = device.GetQueue();

    struct GpuBuffer {
        uint32_t group, binding;
        BufferUsage usage;
        wgpu::Buffer buf;
        uint64_t size;
    };
    std::vector<GpuBuffer> gpuBuffers;
    int maxGroup = -1;

    for (const auto& bd : uniformData) {
        auto key = std::make_pair(bd.group, bd.binding);
        BufferUsage usage = BufferUsage::Uniform;
        auto it = usages.find(key);
        if (it != usages.end()) usage = it->second;

        wgpu::BufferUsage wgpuUsage{};
        switch (usage) {
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

        uint64_t bufSize = std::max(bd.data.size(), size_t(16));
        wgpu::BufferDescriptor bufDesc{};
        bufDesc.size  = bufSize;
        bufDesc.usage = wgpuUsage;
        wgpu::Buffer gpuBuf = device.CreateBuffer(&bufDesc);
        if (!bd.data.empty())
            queue.WriteBuffer(gpuBuf, 0, bd.data.data(), bd.data.size());

        maxGroup = std::max(maxGroup, (int)bd.group);
        gpuBuffers.push_back({bd.group, bd.binding, usage, gpuBuf, bufSize});
    }

    // Compute pipeline
    wgpu::ComputePipelineDescriptor pipelineDesc{};
    pipelineDesc.compute.module     = shaderModule;
    pipelineDesc.compute.entryPoint = {entryPoint.c_str(), entryPoint.size()};
    wgpu::ComputePipeline pipeline = device.CreateComputePipeline(&pipelineDesc);
    if (!pipeline) throw std::runtime_error("Failed to create compute pipeline");

    // Bind groups
    std::map<uint32_t, std::vector<wgpu::BindGroupEntry>> entriesPerGroup;
    for (const auto& gb : gpuBuffers) {
        wgpu::BindGroupEntry e{};
        e.binding = gb.binding;
        e.buffer  = gb.buf;
        e.offset  = 0;
        e.size    = gb.size;
        entriesPerGroup[gb.group].push_back(e);
    }

    // Dispatch
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

    // Readback all storage_rw buffers
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

// ── main ──────────────────────────────────────────────────────────────────────

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

    // Set up Dawn
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
        std::cerr << compileError;
        return 1;
    }
    if (!deviceError.empty()) { std::cerr << deviceError << "\n"; return 1; }

    // Compile-only mode
    if (uniformPath.empty()) return 0;

    // Detect entry point and buffer usages from source
    std::string entryPoint = detectComputeEntry(source);
    if (entryPoint.empty()) {
        std::cerr << "Could not detect @compute entry point\n"; return 1;
    }
    auto usages = detectBufferUsages(source);

    // Load uniform/buffer data
    std::vector<BufferData> uniformData;
    try { uniformData = loadUniforms(uniformPath); }
    catch (const std::exception& e) { std::cerr << e.what() << "\n"; return 1; }

    // Run
    json result;
    try { result = runCompute(device, shaderModule, entryPoint, uniformData, usages); }
    catch (const std::exception& e) { std::cerr << e.what() << "\n"; return 1; }
    if (!deviceError.empty()) { std::cerr << deviceError << "\n"; return 1; }

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