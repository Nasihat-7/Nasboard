// pinyin_decoder_jni.cpp - 修复头文件包含
#include <jni.h>
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <stdlib.h>
#include <string.h>
#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>

// 修正头文件包含路径
// 根据您的目录结构，应该包含pinyinime.h
// 由于pinyin/CMakeLists.txt已经设置了包含目录，这里可以使用相对路径
#include "pinyinime.h"

// 如果上述不行，尝试：
// #include "../pinyin/include/pinyinime.h"

// 定义最大预测数量（如果头文件中没有定义）
#ifndef kMaxPredicts
#define kMaxPredicts 50
#endif

using namespace ime_pinyin;

#define TAG "PinyinDecoderJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// 全局变量
static bool g_decoder_initialized = false;

// 预测缓冲区（用于存储预测结果）
static char16 (*predict_buf)[kMaxPredictSize + 1] = NULL;
static size_t predict_len;

// 声明哈萨克语词库清理函数（在kazakh_dict_jni.cpp中定义）
#ifdef __cplusplus
extern "C" {
#endif

#ifdef __cplusplus
}
#endif

#ifdef __cplusplus
extern "C" {
#endif

/**
 * 打开拼音解码器（通过文件描述符）- 主要方法
 */
JNIEXPORT jboolean JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImOpenDecoderFd(
        JNIEnv* env, jobject thiz, jobject fd_sys_dict, jlong startoffset,
        jlong length, jbyteArray fn_usr_dict) {

    LOGD("nativeImOpenDecoderFd: Starting decoder initialization...");

    if (g_decoder_initialized) {
        LOGD("nativeImOpenDecoderFd: Decoder already initialized, closing first...");
        im_close_decoder();
        g_decoder_initialized = false;
    }

    // 获取文件描述符
    jclass fd_class = env->GetObjectClass(fd_sys_dict);
    jfieldID fd_field = env->GetFieldID(fd_class, "descriptor", "I");
    if (fd_field == NULL) {
        LOGE("Failed to get descriptor field from FileDescriptor");
        return JNI_FALSE;
    }

    jint fd = env->GetIntField(fd_sys_dict, fd_field);
    LOGD("nativeImOpenDecoderFd: Got file descriptor: %d", fd);

    // 复制文件描述符，因为原始文件描述符可能被关闭
    int new_fd = dup(fd);
    if (new_fd < 0) {
        LOGE("Failed to dup file descriptor: %s", strerror(errno));
        return JNI_FALSE;
    }

    LOGD("nativeImOpenDecoderFd: Duplicated fd: %d", new_fd);

    // 获取用户词典路径
    const char* usr_dict_path = NULL;
    if (fn_usr_dict != NULL) {
        jbyte* usr_dict_bytes = env->GetByteArrayElements(fn_usr_dict, NULL);
        if (usr_dict_bytes != NULL) {
            usr_dict_path = (const char*)usr_dict_bytes;
            LOGD("nativeImOpenDecoderFd: User dict path: %s", usr_dict_path);
        }
    }

    LOGD("nativeImOpenDecoderFd: Opening decoder with fd=%d, start=%ld, length=%ld",
         new_fd, (long)startoffset, (long)length);

    // 初始化解码器 - 使用文件描述符版本
    bool result = im_open_decoder_fd(new_fd, startoffset, length, usr_dict_path);

    // 关闭复制的文件描述符
    close(new_fd);

    if (fn_usr_dict != NULL && usr_dict_path != NULL) {
        env->ReleaseByteArrayElements(fn_usr_dict, (jbyte*)usr_dict_path, 0);
    }

    if (result) {
        g_decoder_initialized = true;
        LOGD("nativeImOpenDecoderFd: Pinyin decoder initialized successfully");

        // 测试解码器
        const char* test_pinyin = "nihao";
        size_t cand_num = im_search(test_pinyin, strlen(test_pinyin));
        LOGD("nativeImOpenDecoderFd: Test pinyin '%s' found %zu candidates", test_pinyin, cand_num);

        return JNI_TRUE;
    } else {
        LOGE("nativeImOpenDecoderFd: Failed to initialize pinyin decoder");
        return JNI_FALSE;
    }
}

/**
 * 打开拼音解码器（旧方法，保留但不推荐使用）
 */
JNIEXPORT jboolean JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImOpenDecoder(
        JNIEnv* env, jobject thiz, jobject asset_manager, jstring usr_dict_path) {

    LOGD("nativeImOpenDecoder: Using deprecated method, recommend using nativeImOpenDecoderFd instead");

    // 为了向后兼容，返回false
    LOGE("nativeImOpenDecoder: This method is deprecated. Please use nativeImOpenDecoderFd instead.");
    return JNI_FALSE;
}

/**
 * 关闭拼音解码器
 */
JNIEXPORT void JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImCloseDecoder(
        JNIEnv* env, jobject thiz) {

    if (g_decoder_initialized) {
        im_close_decoder();
        g_decoder_initialized = false;
        LOGD("nativeImCloseDecoder: Pinyin decoder closed");
    }

    // 释放预测缓冲区
    if (predict_buf != NULL) {
        delete[] predict_buf;
        predict_buf = NULL;
        predict_len = 0;
    }
}

/**
 * 搜索拼音
 */
JNIEXPORT jint JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImSearch(
        JNIEnv* env, jobject thiz, jbyteArray py_buf, jint py_len) {

    if (!g_decoder_initialized) {
        LOGE("nativeImSearch: Decoder not initialized");
        return 0;
    }

    jbyte* array_body = env->GetByteArrayElements(py_buf, NULL);
    if (array_body == NULL) {
        LOGE("nativeImSearch: Failed to get byte array elements");
        return 0;
    }

    // 复制数据到本地缓冲区
    char* py_str = new char[py_len + 1];
    memcpy(py_str, array_body, py_len);
    py_str[py_len] = '\0';

    jint result = im_search(py_str, py_len);

    LOGD("nativeImSearch: Search for '%s' (len=%d) returned %d candidates", py_str, py_len, result);

    delete[] py_str;
    env->ReleaseByteArrayElements(py_buf, array_body, 0);

    return result;
}

/**
 * 获取候选词
 */
JNIEXPORT jstring JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImGetChoice(
        JNIEnv* env, jobject thiz, jint candidateId) {

    if (!g_decoder_initialized) {
        LOGE("nativeImGetChoice: Decoder not initialized");
        return env->NewStringUTF("");
    }

    char16 buf[256];
    char16* result = im_get_candidate(candidateId, buf, 255);

    if (result != NULL && result[0] != 0) {
        // 计算长度
        size_t len = 0;
        while (len < 255 && buf[len] != 0) {
            len++;
        }

        LOGD("nativeImGetChoice: Got candidate %d, length=%zu", candidateId, len);
        return env->NewString((const jchar*)buf, len);
    }

    LOGD("nativeImGetChoice: No candidate found for id %d", candidateId);
    return env->NewStringUTF("");
}

/**
 * 重置搜索
 */
JNIEXPORT void JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImResetSearch(
        JNIEnv* env, jobject thiz) {

    if (g_decoder_initialized) {
        im_reset_search();
        LOGD("nativeImResetSearch: Search reset");
    }
}

/**
 * 设置最大长度
 */
JNIEXPORT void JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImSetMaxLens(
        JNIEnv* env, jobject thiz, jint max_sps_len, jint max_hzs_len) {

    if (g_decoder_initialized) {
        im_set_max_lens(max_sps_len, max_hzs_len);
        LOGD("nativeImSetMaxLens: Set max lens - sps=%d, hzs=%d", max_sps_len, max_hzs_len);
    }
}

/**
 * 删除搜索
 */
JNIEXPORT jint JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImDelSearch(
        JNIEnv* env, jobject thiz, jint pos, jboolean is_pos_in_splid,
        jboolean clear_fixed_this_step) {

    if (!g_decoder_initialized) {
        LOGE("nativeImDelSearch: Decoder not initialized");
        return 0;
    }

    return im_delsearch(pos, is_pos_in_splid, clear_fixed_this_step);
}

/**
 * 添加字母
 */
JNIEXPORT jint JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImAddLetter(
        JNIEnv* env, jobject thiz, jbyte ch) {

    if (!g_decoder_initialized) {
        LOGE("nativeImAddLetter: Decoder not initialized");
        return 0;
    }

    return im_add_letter(ch);
}

/**
 * 获取拼音字符串
 */
JNIEXPORT jstring JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImGetPyStr(
        JNIEnv* env, jobject thiz, jboolean decoded) {

    if (!g_decoder_initialized) {
        LOGE("nativeImGetPyStr: Decoder not initialized");
        return env->NewStringUTF("");
    }

    size_t py_len;
    const char* py = im_get_sps_str(&py_len);

    if (!py) {
        LOGE("nativeImGetPyStr: Failed to get pinyin string");
        return env->NewStringUTF("");
    }

    if (!decoded) {
        py_len = strlen(py);
    }

    return env->NewStringUTF(py);
}

/**
 * 获取拼音字符串长度
 */
JNIEXPORT jint JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImGetPyStrLen(
        JNIEnv* env, jobject thiz, jboolean decoded) {

    if (!g_decoder_initialized) {
        LOGE("nativeImGetPyStrLen: Decoder not initialized");
        return 0;
    }

    size_t py_len;
    const char* py = im_get_sps_str(&py_len);

    if (!py) {
        return 0;
    }

    if (!decoded) {
        py_len = strlen(py);
    }

    return (jint)py_len;
}

/**
 * 获取音节分割点
 */
JNIEXPORT jintArray JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImGetSplStart(
        JNIEnv* env, jobject thiz) {

    if (!g_decoder_initialized) {
        LOGE("nativeImGetSplStart: Decoder not initialized");
        return NULL;
    }

    const unsigned short* spl_start;
    size_t len = im_get_spl_start_pos(spl_start);

    if (len <= 0) {
        return NULL;
    }

    jintArray arr = env->NewIntArray(len + 1);
    jint* arr_body = env->GetIntArrayElements(arr, NULL);

    if (arr_body) {
        for (size_t i = 0; i <= len; i++) {
            arr_body[i] = spl_start[i];
        }
        env->ReleaseIntArrayElements(arr, arr_body, 0);
    }

    return arr;
}

/**
 * 选择候选词
 */
JNIEXPORT jint JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImChoose(
        JNIEnv* env, jobject thiz, jint choiceId) {

    if (!g_decoder_initialized) {
        LOGE("nativeImChoose: Decoder not initialized");
        return 0;
    }

    return im_choose(choiceId);
}

/**
 * 取消最后的选择
 */
JNIEXPORT jint JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImCancelLastChoice(
        JNIEnv* env, jobject thiz) {

    if (!g_decoder_initialized) {
        LOGE("nativeImCancelLastChoice: Decoder not initialized");
        return 0;
    }

    return im_cancel_last_choice();
}

/**
 * 获取固定长度
 */
JNIEXPORT jint JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImGetFixedLen(
        JNIEnv* env, jobject thiz) {

    if (!g_decoder_initialized) {
        LOGE("nativeImGetFixedLen: Decoder not initialized");
        return 0;
    }

    return im_get_fixed_len();
}

/**
 * 取消输入
 */
JNIEXPORT jboolean JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImCancelInput(
        JNIEnv* env, jobject thiz) {

    if (!g_decoder_initialized) {
        LOGE("nativeImCancelInput: Decoder not initialized");
        return JNI_FALSE;
    }

    return im_cancel_input() ? JNI_TRUE : JNI_FALSE;
}

/**
 * 刷新缓存
 */
JNIEXPORT void JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImFlushCache(
        JNIEnv* env, jobject thiz) {

    if (g_decoder_initialized) {
        im_flush_cache();
        LOGD("nativeImFlushCache: Cache flushed");
    }
}

/**
 * 获取预测数量
 */
JNIEXPORT jint JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImGetPredictsNum(
        JNIEnv* env, jobject thiz, jstring fixed_str) {

    if (!g_decoder_initialized) {
        LOGE("nativeImGetPredictsNum: Decoder not initialized");
        return 0;
    }

    const char16* fixed_ptr = (char16*)env->GetStringChars(fixed_str, NULL);
    if (!fixed_ptr) {
        LOGE("nativeImGetPredictsNum: Failed to get string chars");
        return 0;
    }

    size_t fixed_len = (size_t)env->GetStringLength(fixed_str);

    char16 fixed_buf[kMaxPredictSize + 1];

    // 如果固定字符串长度超过最大值，只取最后的部分
    if (fixed_len > kMaxPredictSize) {
        fixed_ptr += fixed_len - kMaxPredictSize;
        fixed_len = kMaxPredictSize;
    }

    // 复制固定字符串到缓冲区
    for (size_t i = 0; i < fixed_len; i++) {
        fixed_buf[i] = fixed_ptr[i];
    }
    fixed_buf[fixed_len] = (char16)'\0';

    // 分配预测缓冲区（如果尚未分配）
    if (predict_buf == NULL) {
        predict_buf = new char16[kMaxPredicts][kMaxPredictSize + 1];
        if (predict_buf == NULL) {
            LOGE("nativeImGetPredictsNum: Failed to allocate predict buffer");
            env->ReleaseStringChars(fixed_str, (const jchar*)fixed_ptr);
            return 0;
        }
    }

    // 获取预测结果
    predict_len = im_get_predicts(fixed_buf, predict_buf);

    env->ReleaseStringChars(fixed_str, (const jchar*)fixed_ptr);

    LOGD("nativeImGetPredictsNum: Got %zu predictions for fixed string", predict_len);
    return (jint)predict_len;
}

/**
 * 获取预测项
 */
JNIEXPORT jstring JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImGetPredictItem(
        JNIEnv* env, jobject thiz, jint predict_no) {

    if (!g_decoder_initialized) {
        LOGE("nativeImGetPredictItem: Decoder not initialized");
        return env->NewStringUTF("");
    }

    if (predict_no < 0 || (size_t)predict_no >= predict_len || predict_buf == NULL) {
        return env->NewStringUTF("");
    }

    // 从预测缓冲区获取预测项
    const char16* predict_item = predict_buf[predict_no];
    size_t len = 0;

    // 计算字符串长度
    while (len < kMaxPredictSize && predict_item[len] != 0) {
        len++;
    }

    if (len == 0) {
        return env->NewStringUTF("");
    }

    LOGD("nativeImGetPredictItem: Returning prediction %d, length=%zu", predict_no, len);
    return env->NewString((const jchar*)predict_item, len);
}

/**
 * 检查是否已初始化
 */
JNIEXPORT jboolean JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImIsInitialized(
        JNIEnv* env, jobject thiz) {

    LOGD("nativeImIsInitialized: Returning %s", g_decoder_initialized ? "true" : "false");
    return g_decoder_initialized ? JNI_TRUE : JNI_FALSE;
}

/**
 * 获取版本信息
 */
JNIEXPORT jstring JNICALL Java_com_example_nasboard_ime_dictionary_PinyinDecoder_nativeImGetVersion(
        JNIEnv* env, jobject thiz) {

    const char* version = "PinyinIME Engine 1.0 (Debug Build)";
    LOGD("nativeImGetVersion: Returning version: %s", version);
    return env->NewStringUTF(version);
}

/**
 * JNI库加载时的初始化函数
 * 注意：这个函数是库的唯一入口点，负责所有模块的初始化
 */
JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnLoad: nasboard-pinyin JNI library loaded (包含拼音解码器和哈萨克语词库)");
    return JNI_VERSION_1_6;
}

/**
 * JNI库卸载时的清理函数
 * 注意：这个函数负责清理所有模块的资源
 */
JNIEXPORT void JNI_OnUnload(JavaVM* vm, void* reserved) {
    LOGD("JNI_OnUnload: Cleaning up nasboard-pinyin JNI library (清理拼音解码器和哈萨克语词库)");

    // 1. 清理拼音解码器资源
    // 释放预测缓冲区
    if (predict_buf != NULL) {
        delete[] predict_buf;
        predict_buf = NULL;
        predict_len = 0;
        LOGD("JNI_OnUnload: Pinyin decoder prediction buffer cleaned up");
    }

    // 关闭拼音解码器
    if (g_decoder_initialized) {
        im_close_decoder();
        g_decoder_initialized = false;
        LOGD("JNI_OnUnload: Pinyin decoder closed");
    }

    LOGD("JNI_OnUnload: All resources cleaned up successfully");
}

#ifdef __cplusplus
}
#endif

// 拼音解码器资源清理函数（如果需要单独调用）
void cleanupPinyinDecoderResources() {
    LOGD("Cleaning up pinyin decoder resources");

    // 释放预测缓冲区
    if (predict_buf != NULL) {
        delete[] predict_buf;
        predict_buf = NULL;
        predict_len = 0;
    }

    if (g_decoder_initialized) {
        im_close_decoder();
        g_decoder_initialized = false;
        LOGD("Pinyin decoder resources cleaned up");
    }
}