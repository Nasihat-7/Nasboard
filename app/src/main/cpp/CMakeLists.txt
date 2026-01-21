cmake_minimum_required(VERSION 3.22.1)
project("nasboard-pinyin" VERSION 1.0.0 LANGUAGES C CXX)

# C++标准
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# 全局编译选项
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -fPIC -Wall -fexceptions")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} -DDEBUG")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} -O3")

# Android NDK配置
set(CMAKE_ANDROID_NDK_TOOLCHAIN_VERSION clang)
set(CMAKE_ANDROID_STL_TYPE c++_shared)

# 添加包含路径 - 关键修复
include_directories(
        ${CMAKE_CURRENT_SOURCE_DIR}/marisa/include
        ${CMAKE_CURRENT_SOURCE_DIR}/pinyin/include
)

# 子模块顺序很重要：marisa → pinyin → jni
add_subdirectory(marisa)
add_subdirectory(pinyin)
add_subdirectory(jni)

# 输出目录（Android特定）
if(ANDROID)
    set(CMAKE_LIBRARY_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../jniLibs/${ANDROID_ABI})
    set(CMAKE_ARCHIVE_OUTPUT_DIRECTORY ${CMAKE_CURRENT_SOURCE_DIR}/../../libs/${ANDROID_ABI})
endif()