# JNI共享库，最终导出给Android使用
add_library(nasboard-pinyin SHARED
        marisa_kazakh_dict_jni.cpp
        pinyin_decoder_jni.cpp
)

# 关键修复：设置正确的包含路径
target_include_directories(nasboard-pinyin PRIVATE
        ${CMAKE_CURRENT_SOURCE_DIR}/../marisa/include
        ${CMAKE_CURRENT_SOURCE_DIR}/../pinyin/include
)

target_link_libraries(nasboard-pinyin
        PRIVATE pinyin marisa
        PUBLIC log android
)

target_compile_options(nasboard-pinyin PRIVATE
        -Wno-unused-but-set-variable
        -Wno-format
        -Wno-unused-parameter
        -fexceptions
        -frtti
)

# 添加编译定义
target_compile_definitions(nasboard-pinyin PRIVATE
        JNI_VERSION=JNI_VERSION_1_6
)

set_target_properties(nasboard-pinyin PROPERTIES
        VERSION 1.0.0
        SOVERSION 1
        OUTPUT_NAME "nasboard-pinyin"
        LIBRARY_OUTPUT_NAME "nasboard-pinyin"
)