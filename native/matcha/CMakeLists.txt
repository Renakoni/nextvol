cmake_minimum_required(VERSION 3.26)
project(nextvol_matcha LANGUAGES C CXX)

if(NOT ANDROID OR NOT ANDROID_ABI STREQUAL "arm64-v8a" OR NOT ANDROID_PLATFORM STREQUAL "android-26")
  message(FATAL_ERROR "The offline TTS backend targets Android API 26, arm64-v8a")
endif()
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
set(CMAKE_SKIP_RPATH ON)
add_compile_options(-O2 -g0 -DNDEBUG -ffunction-sections -fdata-sections -fvisibility=hidden)
add_link_options(-Wl,--gc-sections -Wl,-z,max-page-size=16384 -Wl,--no-undefined)

add_library(onnxruntime SHARED IMPORTED)
set_target_properties(onnxruntime PROPERTIES IMPORTED_LOCATION "${ORT_INSTALL}/lib/libonnxruntime.so")
include_directories("${ORT_INSTALL}/include" vendor/sherpa vendor/openfst/src/include
  vendor/kaldifst vendor/cppjieba/include vendor/cppjieba/deps/limonp/include)

set(FST_SOURCES compat flags fst properties symbol-table util symbol-table-ops mapped-file weight)
foreach(name IN LISTS FST_SOURCES)
  list(APPEND FST_FILES vendor/openfst/src/lib/${name}.cc)
endforeach()
add_library(fst STATIC ${FST_FILES} vendor/openfst/src/extensions/far/stlist.cc
  vendor/openfst/src/extensions/far/sttable.cc)
set(KALDI_SOURCES context-fst kaldi-fst-io kaldi-holder kaldi-io kaldi-math
  kaldi-semaphore kaldi-table parse-options text-normalizer text-utils)
foreach(name IN LISTS KALDI_SOURCES)
  list(APPEND KALDI_FILES vendor/kaldifst/kaldifst/csrc/${name}.cc)
endforeach()
add_library(kaldifst STATIC ${KALDI_FILES})
target_link_libraries(kaldifst PUBLIC fst)
include(phonemize.cmake)
add_library(nextvol-espeak SHARED espeak_jni.cc)
target_link_libraries(nextvol-espeak PRIVATE espeak-ng)
# Keep eSpeak's global state private to this library, separate from Sherpa's copy.
target_link_options(nextvol-espeak PRIVATE -Wl,--exclude-libs,ALL)
include(sources.cmake)
add_library(nextvol-matcha SHARED jni.cc name_model.cc ${SHERPA_SOURCES})
target_link_libraries(nextvol-matcha PRIVATE kaldifst piper_phonemize offline-istft onnxruntime android log)

set(SOVITS_AAR "${CMAKE_CURRENT_SOURCE_DIR}/../sovits/onnxruntime-webgpu.aar")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${SOVITS_AAR}")
file(ARCHIVE_EXTRACT INPUT "${SOVITS_AAR}" DESTINATION "${CMAKE_CURRENT_BINARY_DIR}/sovits-ort"
  PATTERNS "jni/arm64-v8a/libortwebgpu.so")
add_library(sovits-ort SHARED IMPORTED)
set_target_properties(sovits-ort PROPERTIES IMPORTED_LOCATION "${CMAKE_CURRENT_BINARY_DIR}/sovits-ort/jni/arm64-v8a/libortwebgpu.so")
include(open-jtalk.cmake)
add_library(nextvol-sovits SHARED sovits.cc sovits_jni.cc qnn_session.cpp japanese_frontend.cpp)
target_link_libraries(nextvol-sovits PRIVATE open-jtalk sovits-ort log dl)
