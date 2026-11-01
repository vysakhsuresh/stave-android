cmake_minimum_required(VERSION 3.22.1)
project(stave LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Oboe arrives as a prefab AAR (com.google.oboe:oboe in libs.versions.toml; prefab = true in the
# app's buildFeatures), so find_package sees it with no path of our own.
find_package(oboe REQUIRED CONFIG)

# Rubber Band (GPL): one translation unit with its own FFT and resampler, fetched at configure
# time. The tarball is pinned by version; a bump is a licence check and a listen.
include(FetchContent)
FetchContent_Declare(
    rubberband
    # GitHub's tag archive, not breakfastquay.com's tarball: CMake 3.22's curl on Windows
    # (schannel) fails the TLS handshake with breakfastquay.com, so the laptop could not
    # configure. The two archives' sources were compared file by file on 1 November 2026 and
    # are identical (the official one adds .hg_archival.txt, GitHub's adds otherbuilds/deploy).
    URL https://github.com/breakfastquay/rubberband/archive/refs/tags/v4.0.0.tar.gz
    URL_HASH SHA256=24300f48a8014b7c863b573a9647e61b1b19b37875e2cdd92005e64c6424d266
)
FetchContent_MakeAvailable(rubberband)

# libstave.so: the render graph, the recorder, Rubber Band and TinySoundFont behind one JNI surface.
add_library(stave SHARED
    graph/Engine.cpp
    graph/Snapshot.cpp
    graph/Dsp.cpp
    graph/Recorder.cpp
    graph/LiveTune.cpp
    graph/SoundFont.cpp
    graph/Stretch.cpp
    jni/stave_jni.cpp
    ${rubberband_SOURCE_DIR}/single/RubberBandSingle.cpp
)

target_include_directories(stave PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${rubberband_SOURCE_DIR}
    ${rubberband_SOURCE_DIR}/rubberband
    ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/tinysoundfont
)

target_compile_definitions(stave PRIVATE
    # Rubber Band's single-file build: its own FFT and resampler, no external dependency.
    USE_BUILTIN_FFT=1 USE_BQRESAMPLER=1 NO_THREADING=1 NDEBUG=1
    # The NDK has no <sys/types.h> aliases for these.
    _USE_MATH_DEFINES=1
)

target_compile_options(stave PRIVATE -O2 -ffast-math -fno-exceptions -fvisibility=hidden -Wall -Wno-unused-parameter)
# Rubber Band wants exceptions.
set_source_files_properties(${rubberband_SOURCE_DIR}/single/RubberBandSingle.cpp graph/Stretch.cpp PROPERTIES COMPILE_OPTIONS "-fexceptions;-Wno-everything")

target_link_libraries(stave PRIVATE oboe::oboe log android)

# Only the JNI entry points and JNI_OnLoad leave the library.
set_target_properties(stave PROPERTIES LINK_FLAGS "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/jni/exports.map -Wl,--gc-sections -Wl,-z,max-page-size=16384")
