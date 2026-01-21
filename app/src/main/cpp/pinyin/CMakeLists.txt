# 拼音输入法动态库
set(PINYIN_SOURCES
        src/Ipicache.cpp
        src/dictbuilder.cpp
        src/dictlist.cpp
        src/dicttrie.cpp
        src/matrixsearch.cpp
        src/mystdlib.cpp
        src/ngram.cpp
        src/pinyinime.cpp
        src/searchutility.cpp
        src/spellingtable.cpp
        src/spellingtrie.cpp
        src/splparser.cpp
        src/sync.cpp
        src/userdict.cpp
        src/utf16char.cpp
        src/utf16reader.cpp
)

add_library(pinyin SHARED ${PINYIN_SOURCES})

# 头文件目录
target_include_directories(pinyin PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# 链接库 - 使用统一的关键字语法
target_link_libraries(pinyin
        PRIVATE
        marisa
        PUBLIC
        log
        android
)

# 编译选项
target_compile_options(pinyin PRIVATE
        -Wno-unused-but-set-variable
        -Wno-format
        -Wno-unused-parameter
        -fexceptions
        -frtti
)

# 预处理器定义
target_compile_definitions(pinyin PRIVATE
        PINYIN_DICT
)

# 设置输出名称
set_target_properties(pinyin PROPERTIES
        OUTPUT_NAME "pinyin"
        LIBRARY_OUTPUT_NAME "pinyin"
)