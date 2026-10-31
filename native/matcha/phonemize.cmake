# Only the phonemizer and inverse STFT needed by offline TTS are built.
# Model-supplied espeak data stays in the managed voice installation.
set(HAVE_MKSTEMP 1)
set(PROJECT_VERSION 1.52.0.1)
set(ESPEAK_CONFIG_DIR "${CMAKE_CURRENT_BINARY_DIR}/espeak-config")
configure_file(vendor/espeak-ng/src/libespeak-ng/config.h.in "${ESPEAK_CONFIG_DIR}/config.h")
set(ESPEAK_SOURCES common mnemonics error ieee80 compiledata compiledict dictionary
  encoding intonation langopts numbers phoneme phonemelist readclause setlengths
  soundicon spect ssml synthdata synthesize tr_languages translate translateword voices
  wavegen speech espeak_api)
foreach(name IN LISTS ESPEAK_SOURCES)
  list(APPEND ESPEAK_FILES vendor/espeak-ng/src/libespeak-ng/${name}.c)
endforeach()
set(UCD_SOURCES case categories ctype proplist scripts tostring)
foreach(name IN LISTS UCD_SOURCES)
  list(APPEND UCD_FILES vendor/espeak-ng/src/ucd-tools/src/${name}.c)
endforeach()
add_library(espeak-ng STATIC ${ESPEAK_FILES} ${UCD_FILES})
target_compile_options(espeak-ng PRIVATE -fwrapv)
target_compile_definitions(espeak-ng PRIVATE LIBESPEAK_NG_EXPORT=1)
target_include_directories(espeak-ng PRIVATE "${ESPEAK_CONFIG_DIR}"
  vendor/espeak-ng/src/include/compat vendor/espeak-ng/src/ucd-tools/src/include)
target_include_directories(espeak-ng PUBLIC vendor/espeak-ng/src/include)

add_library(piper_phonemize STATIC vendor/piper-phonemize/src/phonemize.cpp
  vendor/piper-phonemize/src/phoneme_ids.cpp vendor/piper-phonemize/src/shared.cpp)
target_include_directories(piper_phonemize PUBLIC vendor/piper-phonemize/src)
target_link_libraries(piper_phonemize PUBLIC espeak-ng)

add_library(kissfft STATIC vendor/kissfft/kiss_fft.c vendor/kissfft/kiss_fftr.c)
target_include_directories(kissfft PUBLIC vendor/kissfft)
set(ISTFT_SOURCES feature-window feature-functions kaldi-math rfft stft istft)
foreach(name IN LISTS ISTFT_SOURCES)
  list(APPEND ISTFT_FILES vendor/kaldi-native-fbank/kaldi-native-fbank/csrc/${name}.cc)
endforeach()
add_library(offline-istft STATIC ${ISTFT_FILES})
target_include_directories(offline-istft PUBLIC vendor/kaldi-native-fbank)
target_link_libraries(offline-istft PRIVATE kissfft)
