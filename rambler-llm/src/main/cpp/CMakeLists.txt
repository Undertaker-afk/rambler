cmake_minimum_required(VERSION 3.22.1)
project(ramblerllm CXX C)

if(NOT (ANDROID_ABI STREQUAL "arm64-v8a" OR ANDROID_ABI STREQUAL "x86_64"))
    add_library(ramblerllm SHARED llama_jni_stub.cpp)
    return()
endif()

include(FetchContent)

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    # Dot-product instructions give a big speedup for quantised models; every
    # arm64 phone from ~2018 on has them.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod" CACHE STRING "" FORCE)
endif()

FetchContent_Declare(
    llama
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b12020
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(llama)

add_library(ramblerllm SHARED llama_jni.cpp engine.cpp)
target_link_libraries(ramblerllm llama log)

# The keyboard's voice input ships its own (different) ggml. Keep ours private so the two
# copies can never clash at load time.
target_compile_options(ramblerllm PRIVATE -fvisibility=hidden)
target_link_options(ramblerllm PRIVATE -Wl,--exclude-libs,ALL)
