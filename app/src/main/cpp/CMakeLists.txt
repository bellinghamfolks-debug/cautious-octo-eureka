cmake_minimum_required(VERSION 3.22.1)
project(visionbridge_vlm CXX C)

# ---------------------------------------------------------------------------
# 64-bit only, enforced at configure time.
#
# abiFilters in Gradle already restricts packaging, but a stray command-line
# build or an IDE sync can still invoke CMake for another ABI. Failing here
# guarantees no 32-bit or x86 object is ever produced, rather than produced and
# then dropped.
# ---------------------------------------------------------------------------
if(NOT ANDROID)
    message(FATAL_ERROR "visionbridge_vlm is an Android-only target.")
endif()
if(NOT ANDROID_ABI STREQUAL "arm64-v8a")
    message(FATAL_ERROR
        "Unsupported ABI '${ANDROID_ABI}'. This project is arm64-v8a exclusive; "
        "armeabi-v7a, x86 and x86_64 are deliberately unsupported.")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# -O3 on the ggml kernels is where throughput actually comes from.
#
# Only optimization level belongs in the global flags. Anything stronger is
# inherited by the llama.cpp subproject and breaks it: -fno-rtti here made
# llama-context.cpp fail to compile because it uses dynamic_cast on the KV
# cache. Visibility, section splitting and RTTI choices are applied to this
# project's own target further down instead.
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG")

include(FetchContent)

# ---------------------------------------------------------------------------
# llama.cpp, pinned.
#
# The multimodal surface (libmtmd) is the fastest-moving part of this project,
# so the tag is pinned rather than tracked. vlm_bridge.cpp is written against
# exactly this tag's mtmd API; moving the tag means re-checking that file.
# ---------------------------------------------------------------------------
set(VISIONBRIDGE_LLAMA_TAG "b6089" CACHE STRING "Pinned llama.cpp release tag")

# Only what an on-device VLM needs. Everything else is dead weight in an APK.
set(LLAMA_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL           OFF CACHE BOOL "" FORCE)
# libmtmd (vision projector + image tokenization) lives under tools/, and
# llama.cpp gates `add_subdirectory(tools)` on BOTH of these being ON. Neither
# can be turned off without losing the mtmd target. The CLI executables they
# also define are never compiled, because Gradle restricts the build to the
# visionbridge_vlm target.
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS    ON  CACHE BOOL "" FORCE)

set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # NDK has no libomp by default
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)  # cross-compiling
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)  # static-link into one .so

# GGML_CPU_ARM_ARCH is deliberately NOT set.
#
# Forcing "armv8.2-a+dotprod+fp16" made ggml's ARM feature probe fail: the probe
# compiles a test with the host compiler, which rejected an AArch64 -mcpu value
# outright, and ggml then reported "Failed to get ARM features". Left alone,
# ggml detects dot-product and fp16 support for the aarch64-linux-android
# target itself and compiles its ARM quantization and repack kernels, which is
# what the build log confirms it does. Do not re-add this override.

FetchContent_Declare(
    llama_cpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG        ${VISIONBRIDGE_LLAMA_TAG}
    GIT_SHALLOW    TRUE
)
FetchContent_MakeAvailable(llama_cpp)

add_library(visionbridge_vlm SHARED vlm_bridge.cpp)

target_include_directories(visionbridge_vlm PRIVATE
    ${llama_cpp_SOURCE_DIR}/include
    ${llama_cpp_SOURCE_DIR}/ggml/include
    ${llama_cpp_SOURCE_DIR}/common
    ${llama_cpp_SOURCE_DIR}/tools/mtmd
)

# Applied to this target only, never to the llama.cpp subproject. The JNI entry
# points carry JNIEXPORT, which is explicit default visibility, so they survive
# -fvisibility=hidden while everything else stays internal.
target_compile_options(visionbridge_vlm PRIVATE
    -fvisibility=hidden
    -fvisibility-inlines-hidden
    -ffunction-sections
    -fdata-sections
)

# max-page-size keeps the library loadable on Android 15 devices that use
# 16 KB memory pages.
target_link_options(visionbridge_vlm PRIVATE
    -Wl,--gc-sections
    -Wl,--exclude-libs,ALL
    -Wl,-z,max-page-size=16384
)

find_library(ANDROID_LOG_LIB log)

# mtmd links ggml and llama publicly and needs nothing from `common`, so `common`
# is deliberately absent here: naming it would make ninja compile a static
# library this app never calls.
target_link_libraries(visionbridge_vlm PRIVATE
    mtmd
    llama
    ggml
    ${ANDROID_LOG_LIB}
)
