#include <jni.h>

#include <fstream>
#include <iostream>
#include <sstream>
#include <string>
#include <vector>

#include "spirv-tools/libspirv.hpp"

static bool readBinaryFile(const char* filename, std::vector<uint32_t>* out) {
    std::ifstream f(filename, std::ios::binary | std::ios::ate);
    if (!f) return false;
    auto size = f.tellg();
    if (size % 4 != 0) return false;
    out->resize(size / 4);
    f.seekg(0);
    f.read(reinterpret_cast<char*>(out->data()), size);
    return f.good();
}

// Returns empty string if valid, otherwise error messages.
static std::string validateSpirvFile(const char* filename) {
    spvtools::SpirvTools tools(SPV_ENV_UNIVERSAL_1_6);
    std::ostringstream msgs;
    tools.SetMessageConsumer([&msgs, filename](
        spv_message_level_t level, const char*,
        const spv_position_t& pos, const char* message) {
        const char* lvl = "unknown";
        switch (level) {
            case SPV_MSG_FATAL:
            case SPV_MSG_INTERNAL_ERROR:
            case SPV_MSG_ERROR:   lvl = "error";   break;
            case SPV_MSG_WARNING: lvl = "warning"; break;
            case SPV_MSG_INFO:    lvl = "info";    break;
            default: break;
        }
        msgs << lvl << ": " << filename << ":" << pos.index << ": " << message << "\n";
    });

    std::vector<uint32_t> binary;
    if (!readBinaryFile(filename, &binary))
        return std::string("error: could not read SPIR-V binary: ") + filename + "\n";

    spvtools::ValidatorOptions options;
    if (tools.Validate(binary.data(), binary.size(), options))
        return "";
    return msgs.str();
}

// ── JNI entry point ───────────────────────────────────────────────────────────

extern "C"
JNIEXPORT jstring JNICALL
Java_com_wgslspe_core_SpirvHarness_validateShader(
    JNIEnv* env, jobject,
    jstring jSpirvFilename)
{
    const char* spirvFilename = env->GetStringUTFChars(jSpirvFilename, nullptr);

    jstring jResult = nullptr;
    try {
        std::string result = validateSpirvFile(spirvFilename);
        jResult = env->NewStringUTF(result.c_str());
    } catch (const std::exception& e) {
        env->ReleaseStringUTFChars(jSpirvFilename, spirvFilename);
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), e.what());
        return nullptr;
    }

    env->ReleaseStringUTFChars(jSpirvFilename, spirvFilename);
    return jResult;
}

// ── Standalone main ───────────────────────────────────────────────────────────

int main(int argc, char* argv[]) {
    if (argc < 2 || argc > 3) {
        std::cerr << "Usage: spirv_harness <shader.spv> [output.txt]\n";
        return 1;
    }
    std::string result = validateSpirvFile(argv[1]);
    if (result.empty()) {
        std::cout << "Valid\n";
        return 0;
    }
    if (argc == 3) {
        std::ofstream out(argv[2], std::ios::app);
        if (!out) { std::cerr << "Cannot open output file: " << argv[2] << "\n"; return 1; }
        out << result;
    } else {
        std::cerr << result;
    }
    return 1;
}