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

# Size matters more than the last few percent of speed for a shipped assistive
# app, and -O3 on ggml kernels is where the throughput actually comes from.
set(CMAKE_C_FLAGS_RELEASE "-O3 -DNDEBUG -fvisibility=hidden -ffunction-sections -fdata-sections")
set(CMAKE_CXX_FLAGS_RELEASE "-O3 -DNDEBUG -fvisibility=hidden -fvisibility-inlines-hidden -ffunction-sections -fdata-sections -fno-rtti")
set(CMAKE_SHARED_LINKER_FLAGS_RELEASE "${CMAKE_SHARED_LINKER_FLAGS_RELEASE} -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,-z,max-page-size=16384")

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
set(LLAMA_BUILD_COMMON   ON  CACHE BOOL "" FORCE)
# libmtmd (vision projector + image tokenization) lives under tools/.
set(LLAMA_BUILD_TOOLS    ON  CACHE BOOL "" FORCE)

set(GGML_OPENMP          OFF CACHE BOOL "" FORCE)  # NDK has no libomp by default
set(GGML_LLAMAFILE       OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE          OFF CACHE BOOL "" FORCE)  # cross-compiling
set(GGML_BACKEND_DL      OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS    OFF CACHE BOOL "" FORCE)  # static-link into one .so

# Armv8.2 dot-product and fp16 arithmetic roughly double quantized matmul
# throughput. Every arm64 SoC that can realistically hold a VLM in RAM has
# them; the minSdk 26 floor does not guarantee it, so this is a deliberate
# narrowing of supported hardware, documented in the setup manual.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)

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

find_library(ANDROID_LOG_LIB log)

target_link_libraries(visionbridge_vlm PRIVATE
    mtmd
    llama
    common
    ggml
    ${ANDROID_LOG_LIB}
)
