set(JTALK_ROOT "${CMAKE_CURRENT_BINARY_DIR}/open-jtalk")
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/open-jtalk-src.zip")
file(ARCHIVE_EXTRACT INPUT "${CMAKE_CURRENT_SOURCE_DIR}/open-jtalk-src.zip" DESTINATION "${JTALK_ROOT}")
set(JTALK_PARTS jpcommon mecab/src mecab2njd njd njd2jpcommon
    njd_set_accent_phrase njd_set_accent_type njd_set_digit njd_set_long_vowel
    njd_set_pronunciation njd_set_unvoiced_vowel text2mecab)
foreach(PART ${JTALK_PARTS})
    file(GLOB PART_SOURCES CONFIGURE_DEPENDS "${JTALK_ROOT}/${PART}/*.c" "${JTALK_ROOT}/${PART}/*.cpp")
    list(APPEND JTALK_SOURCES ${PART_SOURCES})
    list(APPEND JTALK_INCLUDES "${JTALK_ROOT}/${PART}")
endforeach()
add_library(open-jtalk STATIC ${JTALK_SOURCES})
target_include_directories(open-jtalk PUBLIC ${JTALK_INCLUDES})
target_compile_definitions(open-jtalk PRIVATE HAVE_CONFIG_H DIC_VERSION=102
    MECAB_DEFAULT_RC="dummy" PACKAGE="open_jtalk" VERSION="1.11"
    CHARSET_UTF_8 MECAB_UTF8_USE_ONLY)
