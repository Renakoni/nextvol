cmake_minimum_required(VERSION 3.22)
project(nextvol_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_SKIP_RPATH ON)

set(SOUNDTOUCH vendor/soundtouch/source/SoundTouch)
add_library(nextvol-audio SHARED jni.cc
  ${SOUNDTOUCH}/AAFilter.cpp
  ${SOUNDTOUCH}/cpu_detect_x86.cpp
  ${SOUNDTOUCH}/FIFOSampleBuffer.cpp
  ${SOUNDTOUCH}/FIRFilter.cpp
  ${SOUNDTOUCH}/InterpolateCubic.cpp
  ${SOUNDTOUCH}/InterpolateLinear.cpp
  ${SOUNDTOUCH}/InterpolateShannon.cpp
  ${SOUNDTOUCH}/mmx_optimized.cpp
  ${SOUNDTOUCH}/RateTransposer.cpp
  ${SOUNDTOUCH}/SoundTouch.cpp
  ${SOUNDTOUCH}/sse_optimized.cpp
  ${SOUNDTOUCH}/TDStretch.cpp)
target_include_directories(nextvol-audio PRIVATE vendor/soundtouch/include)
target_compile_definitions(nextvol-audio PRIVATE SOUNDTOUCH_FLOAT_SAMPLES=1)
target_compile_options(nextvol-audio PRIVATE -O2 -g0 -ffunction-sections -fdata-sections -fvisibility=hidden)
target_link_options(nextvol-audio PRIVATE -Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--no-undefined)
