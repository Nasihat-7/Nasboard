# Marisa静态库
set(MARISA_SOURCES
        src/marisa/agent.cc
        src/marisa/keyset.cc
        src/marisa/trie.cc
        src/marisa/grimoire/io/mapper.cc
        src/marisa/grimoire/io/reader.cc
        src/marisa/grimoire/io/writer.cc
        src/marisa/grimoire/trie/louds-trie.cc
        src/marisa/grimoire/trie/tail.cc
        src/marisa/grimoire/vector/bit-vector.cc
        src/marisa/marisa_kazakhdict.cpp
        src/marisa/KazakhContextPredictor.cpp  # 新增文件
        src/marisa/Kazakh_User_Dict.cpp
)

add_library(marisa STATIC ${MARISA_SOURCES})

# 关键修复：设置正确的包含路径
target_include_directories(marisa PUBLIC
        ${CMAKE_CURRENT_SOURCE_DIR}/include
)

# 编译选项
target_compile_options(marisa PRIVATE
        -Wno-unused-but-set-variable
        -Wno-format
        -Wno-unused-parameter
        -fexceptions
        -frtti
)

# 预处理器定义
target_compile_definitions(marisa PRIVATE
        MARISA_HAS_CPP0X
        MARISA_HAVE_SSE2
)

# 设置输出名称
set_target_properties(marisa PROPERTIES
        OUTPUT_NAME "marisa"
        ARCHIVE_OUTPUT_NAME "marisa"
)