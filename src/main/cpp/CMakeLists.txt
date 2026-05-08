cmake_minimum_required(VERSION 3.20)
project(wgsl_dawn_harness CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Paths to the wgslsmith Dawn build — override with -D flags if needed
set(DAWN_SRC_DIR
    "/Users/limxinyi/Desktop/Masters/Imperial/thesis/wgslsmith/external/dawn"
    CACHE PATH "Dawn source root")
set(DAWN_LIB_DIR
    "/Users/limxinyi/Desktop/Masters/Imperial/thesis/wgslsmith/build/dawn/aarch64-apple-darwin/lib"
    CACHE PATH "Dawn lib directory (contains all .a files)")
set(DAWN_GEN_INCLUDE_DIR
    "/Users/limxinyi/Desktop/Masters/Imperial/thesis/wgslsmith/build/dawn/aarch64-apple-darwin/gen/include"
    CACHE PATH "Dawn generated include directory (webgpu.h, webgpu_cpp.h)")

# ── Shared Dawn configuration ─────────────────────────────────────────────────

set(DAWN_INCLUDES
    ${DAWN_SRC_DIR}/include
    ${DAWN_GEN_INCLUDE_DIR}
    ${DAWN_SRC_DIR}/third_party/swiftshader/third_party/json/include
)

set(DAWN_LINK_DIRS
    ${DAWN_LIB_DIR}
    "/Users/limxinyi/Desktop/Masters/Imperial/thesis/wgslsmith/target/release/build/dawn-d52d2735c6d7f502/out"
)

# Full library list derived from wgslsmith's Dawn build script output
set(DAWN_LIBS
    dawn_native
    dawn_platform
    dawn_common
    dawn_proc
    dawn_system_utils
    dawn_init
    tint_api
    tint_api_common
    tint_api_helpers
    tint_cmd_common
    tint_lang_core
    tint_lang_core_constant
    tint_lang_core_intrinsic
    tint_lang_core_ir
    tint_lang_core_ir_analysis
    tint_lang_core_ir_transform
    tint_lang_core_ir_type
    tint_lang_core_type
    tint_lang_glsl_validate
    tint_lang_hlsl
    tint_lang_hlsl_intrinsic
    tint_lang_hlsl_ir
    tint_lang_hlsl_type
    tint_lang_hlsl_validate
    tint_lang_hlsl_writer
    tint_lang_hlsl_writer_common
    tint_lang_hlsl_writer_printer
    tint_lang_hlsl_writer_raise
    tint_lang_msl
    tint_lang_msl_intrinsic
    tint_lang_msl_ir
    tint_lang_msl_type
    tint_lang_msl_validate
    tint_lang_msl_writer
    tint_lang_msl_writer_common
    tint_lang_msl_writer_printer
    tint_lang_msl_writer_raise
    tint_lang_null_writer
    tint_lang_null_writer_common
    tint_lang_null_writer_raise
    tint_lang_spirv
    tint_lang_spirv_intrinsic
    tint_lang_spirv_ir
    tint_lang_spirv_type
    tint_lang_spirv_writer
    tint_lang_spirv_writer_common
    tint_lang_spirv_writer_printer
    tint_lang_spirv_writer_raise
    tint_lang_wgsl
    tint_lang_wgsl_ast
    tint_lang_wgsl_inspector
    tint_lang_wgsl_intrinsic
    tint_lang_wgsl_ir
    tint_lang_wgsl_program
    tint_lang_wgsl_reader
    tint_lang_wgsl_reader_lower
    tint_lang_wgsl_reader_parser
    tint_lang_wgsl_reader_program_to_ir
    tint_lang_wgsl_resolver
    tint_lang_wgsl_sem
    tint_lang_wgsl_writer
    tint_lang_wgsl_writer_ast_printer
    tint_lang_wgsl_writer_common
    tint_lang_wgsl_writer_ir_to_program
    tint_lang_wgsl_writer_raise
    tint_utils
    tint_utils_command
    tint_utils_containers
    tint_utils_diagnostic
    tint_utils_file
    tint_utils_ice
    tint_utils_macros
    tint_utils_math
    tint_utils_memory
    tint_utils_rtti
    tint_utils_strconv
    tint_utils_symbol
    tint_utils_system
    tint_utils_text
    tint_utils_text_generator
    SPIRV-Tools
    glslang
    glslang-default-resource-limits
    absl_base
    absl_borrowed_fixup_buffer
    absl_city
    absl_civil_time
    absl_cord
    absl_cord_internal
    absl_cordz_functions
    absl_cordz_handle
    absl_cordz_info
    absl_crc32c
    absl_crc_cord_state
    absl_crc_cpu_detect
    absl_crc_internal
    absl_debugging_internal
    absl_decode_rust_punycode
    absl_demangle_internal
    absl_demangle_rust
    absl_exponential_biased
    absl_graphcycles_internal
    absl_hash
    absl_hashtablez_sampler
    absl_int128
    absl_kernel_timeout_internal
    absl_log_severity
    absl_malloc_internal
    absl_raw_hash_set
    absl_raw_logging_internal
    absl_spinlock_wait
    absl_stacktrace
    absl_str_format_internal
    absl_string_view
    absl_strings
    absl_strings_internal
    absl_symbolize
    absl_synchronization
    absl_throw_delegate
    absl_time
    absl_time_zone
    absl_tracing_internal
    absl_utf8_for_code_point
)

set(APPLE_FRAMEWORKS
    "-framework Metal"
    "-framework CoreGraphics"
    "-framework IOKit"
    "-framework IOSurface"
    "-framework QuartzCore"
    "-framework Foundation"
)

# ── Standalone executable (debugging / compile-only checks) ───────────────────

find_package(JNI REQUIRED)

add_executable(dawn_harness dawn_harness.cpp)
target_include_directories(dawn_harness PRIVATE ${JNI_INCLUDE_DIRS} ${DAWN_INCLUDES})
target_link_directories(dawn_harness PRIVATE ${DAWN_LINK_DIRS})
target_link_libraries(dawn_harness PRIVATE ${DAWN_LIBS})

if(APPLE)
    target_link_libraries(dawn_harness PRIVATE ${APPLE_FRAMEWORKS})
endif()
if(UNIX AND NOT APPLE)
    target_link_libraries(dawn_harness PRIVATE dl pthread)
endif()

# ── JNI shared library (loaded by Kotlin via System.loadLibrary) ──────────────

add_library(dawn_harness_jni SHARED dawn_harness.cpp)
target_include_directories(dawn_harness_jni PRIVATE
    ${JNI_INCLUDE_DIRS}
    ${DAWN_INCLUDES}
)
target_link_directories(dawn_harness_jni PRIVATE ${DAWN_LINK_DIRS})
target_link_libraries(dawn_harness_jni PRIVATE ${DAWN_LIBS})

if(APPLE)
    target_link_libraries(dawn_harness_jni PRIVATE ${APPLE_FRAMEWORKS})
    # Let the JVM resolve JNI symbols at load time instead of link time
    target_link_options(dawn_harness_jni PRIVATE "-undefined dynamic_lookup")
endif()
if(UNIX AND NOT APPLE)
    target_link_libraries(dawn_harness_jni PRIVATE dl pthread)
endif()