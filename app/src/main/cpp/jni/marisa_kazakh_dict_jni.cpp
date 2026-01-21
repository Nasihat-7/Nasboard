#include <jni.h>
#include <android/log.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <string>
#include <vector>
#include <memory>
#include <cstring>
#include <cerrno>
#include <future>
#include <chrono>
#include <queue>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <string_view>

// Marisa头文件
#include "marisa/trie.h"
#include "marisa/agent.h"
#include "marisa/iostream.h"
#include "marisa/KazakhContextPredictor.h"
#include "marisa/Kazakh_User_Dict.h"

#define LOG_TAG "MarisaKazakhJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// ==================== 任务队列系统 ====================

class TaskQueue {
private:
    struct Task {
        std::function<void()> func;
        int priority; // 优先级，越小越优先
        int64_t timestamp; // 时间戳
        std::string taskId; // 任务ID（用于取消）

        bool operator<(const Task& other) const {
            if (priority != other.priority) {
                return priority > other.priority; // 最小堆
            }
            return timestamp > other.timestamp; // 更早的优先
        }
    };

    std::priority_queue<Task> queue;
    mutable std::mutex queueMutex;
    std::condition_variable condition;
    std::atomic<bool> running{true};
    std::thread workerThread;
    JavaVM* jvm;

public:
    TaskQueue(JavaVM* vm) : jvm(vm) {
        workerThread = std::thread([this]() {
            JNIEnv* env = nullptr;
            jvm->AttachCurrentThread(&env, nullptr);

            while (running.load()) {
                std::function<void()> task;
                {
                    std::unique_lock<std::mutex> lock(queueMutex);
                    condition.wait(lock, [this]() {
                        return !queue.empty() || !running.load();
                    });

                    if (!running.load() && queue.empty()) break;

                    if (!queue.empty()) {
                        task = std::move(queue.top().func);
                        queue.pop();
                    }
                }

                if (task) {
                    try {
                        task();
                    } catch (const std::exception& e) {
                        LOGE("TaskQueue exception: %s", e.what());
                    }
                }
            }

            jvm->DetachCurrentThread();
        });
    }

    ~TaskQueue() {
        {
            std::unique_lock<std::mutex> lock(queueMutex);
            running.store(false);
        }
        condition.notify_all();
        if (workerThread.joinable()) {
            workerThread.join();
        }
    }

    void postTask(std::function<void()> task, int priority = 0, const std::string& taskId = "") {
        std::unique_lock<std::mutex> lock(queueMutex);
        if (running.load()) {
            queue.push({std::move(task), priority,
                        std::chrono::steady_clock::now().time_since_epoch().count(),
                        taskId});
            condition.notify_one();
        }
    }

    void cancelTasks(const std::string& taskIdPrefix) {
        std::unique_lock<std::mutex> lock(queueMutex);
        std::priority_queue<Task> newQueue;

        while (!queue.empty()) {
            Task task = std::move(queue.top());
            queue.pop();

            if (task.taskId.find(taskIdPrefix) != 0) {
                newQueue.push(std::move(task));
            }
        }

        queue = std::move(newQueue);
    }

    void clearPendingTasks() {
        std::unique_lock<std::mutex> lock(queueMutex);
        while (!queue.empty()) {
            queue.pop();
        }
    }

    size_t pendingCount() const {
        std::unique_lock<std::mutex> lock(queueMutex);
        return queue.size();
    }
};

// 全局上下文
static marisa::KazakhContextPredictor* g_kazakh_predictor = nullptr;
static bool g_kazakh_predictor_initialized = false;
static kazakh_ime::KazakhUserDict* g_kazakh_user_dict = nullptr;
static bool g_kazakh_user_dict_initialized = false;
static JavaVM* g_jvm = nullptr;
static TaskQueue* g_task_queue = nullptr;
static std::mutex g_predictor_mutex;
static std::atomic<int> g_current_task_id{0};
static std::atomic<int64_t> g_last_input_time{0};

// ==================== JNI辅助函数 ====================

bool utf8ToUtf16SafeJNI(const std::string& utf8, std::u16string& utf16) {
    if (utf8.empty()) {
        utf16.clear();
        return true;
    }

    utf16.clear();
    utf16.reserve(utf8.size());

    size_t i = 0;
    while (i < utf8.size()) {
        uint8_t c = static_cast<uint8_t>(utf8[i++]);

        if (c <= 0x7F) {
            utf16.push_back(static_cast<char16_t>(c));
        } else if ((c & 0xE0) == 0xC0 && i < utf8.size()) {
            uint8_t c2 = static_cast<uint8_t>(utf8[i++]);
            if ((c2 & 0xC0) != 0x80) {
                LOGE("utf8ToUtf16SafeJNI: Invalid UTF-8 sequence");
                return false;
            }
            char16_t code = ((c & 0x1F) << 6) | (c2 & 0x3F);
            utf16.push_back(code);
        } else if ((c & 0xF0) == 0xE0 && i + 1 < utf8.size()) {
            uint8_t c2 = static_cast<uint8_t>(utf8[i++]);
            uint8_t c3 = static_cast<uint8_t>(utf8[i++]);
            if ((c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) {
                LOGE("utf8ToUtf16SafeJNI: Invalid UTF-8 sequence");
                return false;
            }
            char16_t code = ((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
            utf16.push_back(code);
        } else if ((c & 0xF8) == 0xF0 && i + 2 < utf8.size()) {
            uint8_t c2 = static_cast<uint8_t>(utf8[i++]);
            uint8_t c3 = static_cast<uint8_t>(utf8[i++]);
            uint8_t c4 = static_cast<uint8_t>(utf8[i++]);

            if ((c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80 || (c4 & 0xC0) != 0x80) {
                LOGE("utf8ToUtf16SafeJNI: Invalid UTF-8 sequence");
                return false;
            }

            uint32_t codePoint = ((c & 0x07) << 18) | ((c2 & 0x3F) << 12) |
                                 ((c3 & 0x3F) << 6) | (c4 & 0x3F);

            if (codePoint > 0x10FFFF) {
                LOGE("utf8ToUtf16SafeJNI: Code point out of range");
                return false;
            }

            if (codePoint > 0xFFFF) {
                codePoint -= 0x10000;
                char16_t highSurrogate = static_cast<char16_t>((codePoint >> 10) + 0xD800);
                char16_t lowSurrogate = static_cast<char16_t>((codePoint & 0x3FF) + 0xDC00);
                utf16.push_back(highSurrogate);
                utf16.push_back(lowSurrogate);
            } else {
                utf16.push_back(static_cast<char16_t>(codePoint));
            }
        } else {
            LOGE("utf8ToUtf16SafeJNI: Invalid UTF-8 lead byte");
            return false;
        }
    }

    return true;
}

bool utf16ToUtf8SafeJNI(const std::u16string& utf16, std::string& utf8) {
    if (utf16.empty()) {
        utf8.clear();
        return true;
    }

    utf8.clear();
    utf8.reserve(utf16.size() * 3);

    size_t i = 0;
    while (i < utf16.size()) {
        char16_t c = utf16[i++];

        if (c <= 0x7F) {
            // 1字节UTF-8
            utf8.push_back(static_cast<char>(c));
        } else if (c <= 0x7FF) {
            // 2字节UTF-8
            utf8.push_back(static_cast<char>(0xC0 | ((c >> 6) & 0x1F)));
            utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c < 0xD800 || c > 0xDFFF) {
            // 3字节UTF-8（不是代理对）
            utf8.push_back(static_cast<char>(0xE0 | ((c >> 12) & 0x0F)));
            utf8.push_back(static_cast<char>(0x80 | ((c >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (c & 0x3F)));
        } else if (c >= 0xD800 && c <= 0xDBFF && i < utf16.size()) {
            // 高代理项
            char16_t highSurrogate = c;
            char16_t lowSurrogate = utf16[i++];

            if (lowSurrogate < 0xDC00 || lowSurrogate > 0xDFFF) {
                LOGE("utf16ToUtf8SafeJNI: Invalid UTF-16 surrogate pair at position %zu", i - 2);
                return false;
            }

            // 计算原始码点
            uint32_t codePoint = 0x10000 + ((highSurrogate - 0xD800) << 10) +
                                 (lowSurrogate - 0xDC00);

            // 4字节UTF-8
            utf8.push_back(static_cast<char>(0xF0 | ((codePoint >> 18) & 0x07)));
            utf8.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        } else {
            LOGE("utf16ToUtf8SafeJNI: Invalid UTF-16 code unit: 0x%04X at position %zu", c, i - 1);
            return false;
        }
    }

    return true;
}

jobjectArray convertStringVectorToJavaArray(JNIEnv* env, const std::vector<std::string>& strings) {
    if (strings.empty()) {
        jclass stringClass = env->FindClass("java/lang/String");
        if (stringClass == nullptr) {
            LOGE("Failed to find String class");
            return nullptr;
        }
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    jclass stringClass = env->FindClass("java/lang/String");
    if (stringClass == nullptr) {
        LOGE("Failed to find String class");
        return nullptr;
    }

    jobjectArray array = env->NewObjectArray(strings.size(), stringClass, nullptr);
    if (array == nullptr) {
        LOGE("Failed to create String array");
        return nullptr;
    }

    for (size_t i = 0; i < strings.size(); ++i) {
        jstring str = env->NewStringUTF(strings[i].c_str());
        if (str == nullptr) {
            continue;
        }
        env->SetObjectArrayElement(array, i, str);
        env->DeleteLocalRef(str);
    }

    return array;
}

// ==================== 清理函数 ====================

void cleanupKazakhPredictor() {
    LOGD("Cleaning up Kazakh predictor resources...");

    std::unique_lock<std::mutex> lock(g_predictor_mutex);

    if (g_kazakh_predictor != nullptr) {
        g_kazakh_predictor->clear();
        delete g_kazakh_predictor;
        g_kazakh_predictor = nullptr;
    }

    g_kazakh_predictor_initialized = false;
    g_current_task_id.store(0);
    g_last_input_time.store(0);

    if (g_task_queue != nullptr) {
        g_task_queue->clearPendingTasks();
        delete g_task_queue;
        g_task_queue = nullptr;
    }
}

// 添加默认词条的辅助函数
void addDefaultWords() {
    // 添加一些基本的哈萨克语词汇
    std::vector<std::string> defaultWords = {
            "қотақба", "тіл", "әдебиет", "мәдениет", "тарих", "білім",
            "ғылым", "алма", "кiтап", "үй", "қала", "бала", "сәлем",
            "рақмет", "құрмет", "дәуір", "жаңалық", "ақпарат", "технология"
    };

    for (const auto& word : defaultWords) {
        g_kazakh_user_dict->addWord(word, 1);
    }
}

// -----------------------
// 辅助函数：清理用户词典资源
// -----------------------
void cleanupKazakhUserDict() {
    LOGD("Cleaning up Kazakh user dictionary resources...");

    if (g_kazakh_user_dict != nullptr) {
        LOGD("Shutting down user dictionary");
        g_kazakh_user_dict->shutdown();
        // 注意：KazakhUserDict 是单例，不应该删除
        g_kazakh_user_dict = nullptr;
    }

    g_kazakh_user_dict_initialized = false;
    LOGD("Kazakh user dictionary resources cleaned up");
}

// -----------------------
// 辅助函数：初始化用户词典
// -----------------------
bool initializeKazakhUserDict() {
    LOGD("initializeKazakhUserDict: Starting initialization...");

    // 如果已经初始化，直接返回
    if (g_kazakh_user_dict != nullptr && g_kazakh_user_dict_initialized) {
        LOGD("initializeKazakhUserDict: Already initialized");
        return true;
    }

    // 创建新实例
    try {
        LOGD("initializeKazakhUserDict: Getting instance...");
        g_kazakh_user_dict = &kazakh_ime::KazakhUserDict::getInstance();

        if (g_kazakh_user_dict == nullptr) {
            LOGE("initializeKazakhUserDict: Failed to get instance");
            return false;
        }

        LOGD("initializeKazakhUserDict: Instance created successfully");

        // 标记为已初始化
        g_kazakh_user_dict_initialized = true;

        return true;

    } catch (const std::exception& e) {
        LOGE("initializeKazakhUserDict: Exception creating instance: %s", e.what());
        g_kazakh_user_dict = nullptr;
        g_kazakh_user_dict_initialized = false;
        return false;
    }
}

bool loadKazakhUnigramDict(const char* filename) {
    LOGD("Loading Kazakh unigram dictionary: %s", filename);

    struct stat file_stat;
    if (stat(filename, &file_stat) != 0) {
        LOGE("Unigram file does not exist: %s", filename);
        return false;
    }

    try {
        std::unique_lock<std::mutex> lock(g_predictor_mutex);
        if (g_kazakh_predictor == nullptr) {
            g_kazakh_predictor = new marisa::KazakhContextPredictor();
        }

        bool success = g_kazakh_predictor->loadUnigramFromFile(filename);
        return success;

    } catch (const std::exception& e) {
        LOGE("Exception loading Kazakh unigram dictionary: %s", e.what());
        return false;
    }
}

bool loadKazakhBigramDict(const char* filename) {
    LOGD("Loading Kazakh bigram dictionary: %s", filename);

    struct stat file_stat;
    if (stat(filename, &file_stat) != 0) {
        LOGE("Bigram file does not exist: %s", filename);
        return false;
    }

    try {
        std::unique_lock<std::mutex> lock(g_predictor_mutex);
        if (g_kazakh_predictor == nullptr) {
            g_kazakh_predictor = new marisa::KazakhContextPredictor();
        }

        bool success = g_kazakh_predictor->loadBigramFromFile(filename);
        if (success) {
            g_kazakh_predictor_initialized = true;
        }
        return success;

    } catch (const std::exception& e) {
        LOGE("Exception loading Kazakh bigram dictionary: %s", e.what());
        return false;
    }
}

// ==================== 分级JNI函数 ====================

extern "C" {

// ==================== Stage 1: 快速预测 (<5ms) ====================
JNIEXPORT jobjectArray JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeFastPredict(
        JNIEnv* env, jobject /* this */, jstring prefix, jint maxResults) {

    auto start = std::chrono::steady_clock::now();
    g_last_input_time.store(start.time_since_epoch().count());

    if (!g_kazakh_predictor_initialized || g_kazakh_predictor == nullptr) {
        jclass stringClass = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    const char* cPrefix = env->GetStringUTFChars(prefix, nullptr);
    if (cPrefix == nullptr) {
        return nullptr;
    }

    std::vector<std::string> results;

    try {
        std::unique_lock<std::mutex> lock(g_predictor_mutex);
        results = g_kazakh_predictor->fastPredict(cPrefix, maxResults);
    } catch (const std::exception& e) {
        LOGE("Fast predict exception: %s", e.what());
        results.clear();
    }

    env->ReleaseStringUTFChars(prefix, cPrefix);

    auto end = std::chrono::steady_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
    LOGD("Fast predict took: %lldµs, results: %zu",
         (long long)duration.count(), results.size());

    return convertStringVectorToJavaArray(env, results);
}

// ==================== Stage 2: 键盘邻近纠错 (<15ms) ====================
JNIEXPORT jobjectArray JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeKeyboardCorrect(
        JNIEnv* env, jobject /* this */, jstring input, jint maxResults) {

    auto start = std::chrono::steady_clock::now();
    g_last_input_time.store(start.time_since_epoch().count());

    if (!g_kazakh_predictor_initialized || g_kazakh_predictor == nullptr) {
        jclass stringClass = env->FindClass("java/lang/String");
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    const char* cInput = env->GetStringUTFChars(input, nullptr);
    if (cInput == nullptr) {
        return nullptr;
    }

    std::vector<std::string> results;

    try {
        std::unique_lock<std::mutex> lock(g_predictor_mutex);
        results = g_kazakh_predictor->spellCorrect(cInput, maxResults);
    } catch (const std::exception& e) {
        LOGE("Keyboard correct exception: %s", e.what());
        results.clear();
    }

    env->ReleaseStringUTFChars(input, cInput);

    auto end = std::chrono::steady_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
    LOGD("Keyboard correct took: %lldµs, results: %zu",
         (long long)duration.count(), results.size());

    return convertStringVectorToJavaArray(env, results);
}

// ==================== Stage 3: 异步完整拼写纠正 ====================
JNIEXPORT void JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeHeavySpellCorrectAsync(
        JNIEnv* env, jobject /* this */, jstring input, jobject callback) {

if (!g_kazakh_predictor_initialized || g_kazakh_predictor == nullptr || g_task_queue == nullptr) {
return;
}

const char* cInput = env->GetStringUTFChars(input, nullptr);
if (cInput == nullptr) {
return;
}

// 创建全局引用
jobject globalCallback = env->NewGlobalRef(callback);
std::string inputStr(cInput);

env->ReleaseStringUTFChars(input, cInput);

// 增加任务ID，取消旧任务
int taskId = g_current_task_id.load() + 1;
g_current_task_id.store(taskId);
int64_t currentInputTime = g_last_input_time.load();

// 取消旧的heavy任务
g_task_queue->cancelTasks("heavy_");

// 提交到任务队列
g_task_queue->postTask([inputStr, globalCallback, taskId, currentInputTime]() {
JNIEnv* env = nullptr;
bool attached = false;

jint result = g_jvm->GetEnv((void**)&env, JNI_VERSION_1_6);
if (result == JNI_EDETACHED) {
if (g_jvm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
attached = true;
} else {
LOGE("Failed to attach thread");
env->DeleteGlobalRef(globalCallback);
return;
}
} else if (result != JNI_OK) {
LOGE("Failed to get JNIEnv");
env->DeleteGlobalRef(globalCallback);
return;
}

// 检查任务是否已被取消或输入已更新
if (taskId != g_current_task_id.load() || currentInputTime != g_last_input_time.load()) {
LOGD("Heavy task outdated: taskId=%d, currentId=%d, inputTime=%lld, currentInputTime=%lld",
     taskId, g_current_task_id.load(), currentInputTime, g_last_input_time.load());
env->DeleteGlobalRef(globalCallback);
if (attached) g_jvm->DetachCurrentThread();
return;
}

std::vector<std::string> heavyResults;

try {
std::unique_lock<std::mutex> lock(g_predictor_mutex);
if (g_kazakh_predictor != nullptr) {
// 使用C++线程池的异步调用
auto promise = std::make_shared<std::promise<std::vector<std::string>>>();
auto future = promise->get_future();

g_kazakh_predictor->heavySpellCorrectAsync(inputStr,
[promise](std::vector<std::string> results) {
promise->set_value(results);
});

// 等待结果（带超时）
if (future.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready) {
heavyResults = future.get();
} else {
LOGW("Heavy spell correct timeout");
}
}
} catch (const std::exception& e) {
LOGE("Heavy spell correct exception: %s", e.what());
heavyResults.clear();
}

// 再次检查任务是否仍然有效
if (taskId != g_current_task_id.load() || currentInputTime != g_last_input_time.load()) {
LOGD("Heavy task cancelled after completion");
env->DeleteGlobalRef(globalCallback);
if (attached) g_jvm->DetachCurrentThread();
return;
}

// 回调到Java层
jclass callbackClass = env->GetObjectClass(globalCallback);
if (callbackClass != nullptr) {
jmethodID methodId = env->GetMethodID(callbackClass, "onHeavyCorrectComplete", "([Ljava/lang/String;)V");
if (methodId != nullptr) {
jobjectArray javaArray = convertStringVectorToJavaArray(env, heavyResults);
if (javaArray != nullptr) {
env->CallVoidMethod(globalCallback, methodId, javaArray);
env->DeleteLocalRef(javaArray);
}
}
}

// 清理
env->DeleteGlobalRef(globalCallback);

if (attached) {
g_jvm->DetachCurrentThread();
}
}, 1, "heavy_" + std::to_string(taskId)); // 低优先级
}

// ==================== 原有JNI函数（保持兼容） ====================

// 加载unigram词典
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeLoadUnigramDictFromFile(
        JNIEnv* env, jobject /* this */, jstring filename) {

const char* cFilename = env->GetStringUTFChars(filename, nullptr);
if (cFilename == nullptr) {
return JNI_FALSE;
}

bool success = loadKazakhUnigramDict(cFilename);
env->ReleaseStringUTFChars(filename, cFilename);

return success ? JNI_TRUE : JNI_FALSE;
}

// 加载bigram词典
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeLoadBigramDictFromFile(
        JNIEnv* env, jobject /* this */, jstring filename) {

const char* cFilename = env->GetStringUTFChars(filename, nullptr);
if (cFilename == nullptr) {
return JNI_FALSE;
}

bool success = loadKazakhBigramDict(cFilename);
env->ReleaseStringUTFChars(filename, cFilename);

return success ? JNI_TRUE : JNI_FALSE;
}

// 前缀搜索（兼容旧接口）
JNIEXPORT jobjectArray JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeMarisaPrefixSearch(
        JNIEnv* env, jobject /* this */, jstring prefix, jint maxResults) {

if (!g_kazakh_predictor_initialized || g_kazakh_predictor == nullptr) {
jclass stringClass = env->FindClass("java/lang/String");
return env->NewObjectArray(0, stringClass, nullptr);
}

const char* cPrefix = env->GetStringUTFChars(prefix, nullptr);
if (cPrefix == nullptr) {
return nullptr;
}

std::vector<std::string> results;

try {
std::unique_lock<std::mutex> lock(g_predictor_mutex);
results = g_kazakh_predictor->prefixSearch(cPrefix, maxResults);
} catch (const std::exception& e) {
LOGE("Prefix search exception: %s", e.what());
results.clear();
}

env->ReleaseStringUTFChars(prefix, cPrefix);

return convertStringVectorToJavaArray(env, results);
}

// 上下文预测
JNIEXPORT jobjectArray JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeMarisaContextPredict(
        JNIEnv* env, jobject /* this */, jstring previousWord, jstring currentPrefix, jint maxResults) {

if (!g_kazakh_predictor_initialized || g_kazakh_predictor == nullptr) {
jclass stringClass = env->FindClass("java/lang/String");
return env->NewObjectArray(0, stringClass, nullptr);
}

const char* cPreviousWord = env->GetStringUTFChars(previousWord, nullptr);
const char* cCurrentPrefix = env->GetStringUTFChars(currentPrefix, nullptr);
if (cPreviousWord == nullptr || cCurrentPrefix == nullptr) {
if (cPreviousWord) env->ReleaseStringUTFChars(previousWord, cPreviousWord);
if (cCurrentPrefix) env->ReleaseStringUTFChars(currentPrefix, cCurrentPrefix);
return nullptr;
}

std::vector<std::string> results;

try {
std::unique_lock<std::mutex> lock(g_predictor_mutex);
results = g_kazakh_predictor->contextPredict(cPreviousWord, cCurrentPrefix, maxResults);
} catch (const std::exception& e) {
LOGE("Context predict exception: %s", e.what());
results.clear();
}

env->ReleaseStringUTFChars(previousWord, cPreviousWord);
env->ReleaseStringUTFChars(currentPrefix, cCurrentPrefix);

return convertStringVectorToJavaArray(env, results);
}

// 精确匹配
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeMarisaExactMatch(
        JNIEnv* env, jobject /* this */, jstring word) {

if (!g_kazakh_predictor_initialized || g_kazakh_predictor == nullptr) {
return JNI_FALSE;
}

const char* cWord = env->GetStringUTFChars(word, nullptr);
if (cWord == nullptr) {
return JNI_FALSE;
}

bool found = false;

try {
std::unique_lock<std::mutex> lock(g_predictor_mutex);
found = g_kazakh_predictor->exactMatch(cWord);
} catch (const std::exception& e) {
LOGE("Exact match exception: %s", e.what());
found = false;
}

env->ReleaseStringUTFChars(word, cWord);

return found ? JNI_TRUE : JNI_FALSE;
}

// 智能预测
JNIEXPORT jobjectArray JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeMarisaSmartPredict(
        JNIEnv* env, jobject /* this */, jstring prefix, jint maxResults) {

if (!g_kazakh_predictor_initialized || g_kazakh_predictor == nullptr) {
jclass stringClass = env->FindClass("java/lang/String");
return env->NewObjectArray(0, stringClass, nullptr);
}

const char* cPrefix = env->GetStringUTFChars(prefix, nullptr);
if (cPrefix == nullptr) {
return nullptr;
}

std::vector<std::string> results;

try {
std::unique_lock<std::mutex> lock(g_predictor_mutex);
results = g_kazakh_predictor->smartPredict(cPrefix, maxResults);
} catch (const std::exception& e) {
LOGE("Smart predict exception: %s", e.what());
results.clear();
}

env->ReleaseStringUTFChars(prefix, cPrefix);

return convertStringVectorToJavaArray(env, results);
}

// 处理词条提交
JNIEXPORT void JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeMarisaProcessWordSubmission(
        JNIEnv* env, jobject /* this */, jstring word) {

if (!g_kazakh_predictor_initialized || g_kazakh_predictor == nullptr) {
return;
}

const char* cWord = env->GetStringUTFChars(word, nullptr);
if (cWord == nullptr) {
return;
}

try {
std::unique_lock<std::mutex> lock(g_predictor_mutex);
g_kazakh_predictor->processWordSubmission(cWord);
} catch (const std::exception& e) {
LOGE("Process word submission exception: %s", e.what());
}

env->ReleaseStringUTFChars(word, cWord);
}

// 获取词典信息
JNIEXPORT jstring JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeGetMarisaDictInfo(
        JNIEnv* env, jobject /* this */) {

    if (!g_kazakh_predictor_initialized || g_kazakh_predictor == nullptr) {
        return env->NewStringUTF("Predictor not initialized");
    }

    std::string info;

    try {
        std::unique_lock<std::mutex> lock(g_predictor_mutex);
        info = g_kazakh_predictor->getInfo();
    } catch (const std::exception& e) {
        info = "Error getting predictor info: ";
        info += e.what();
    }

    return env->NewStringUTF(info.c_str());
}

// 检查字典是否初始化
JNIEXPORT jboolean JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeIsMarisaDictInitialized(
        JNIEnv* /* env */, jobject /* this */) {

    bool initialized = g_kazakh_predictor_initialized && (g_kazakh_predictor != nullptr);
    return initialized ? JNI_TRUE : JNI_FALSE;
}

// 关闭字典
JNIEXPORT void JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhDictionaryManager_nativeCloseMarisaDict(
        JNIEnv* /* env */, jobject /* this */) {

cleanupKazakhPredictor();
}

// ==================== 用户词典函数 ====================

// 加载用户词典
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeLoadUserDict(
        JNIEnv* env, jobject /* this */, jstring filepath) {

LOGD("=== nativeLoadUserDict called ===");

// 确保用户词典已初始化
if (!initializeKazakhUserDict()) {
LOGE("Failed to initialize user dictionary");
return JNI_FALSE;
}

const char* cFilepath = env->GetStringUTFChars(filepath, nullptr);
if (cFilepath == nullptr) {
LOGE("Failed to get filepath string");
return JNI_FALSE;
}

LOGD("Loading user dictionary from: %s", cFilepath);

// 检查文件是否存在和大小
struct stat file_stat;
bool file_exists = (stat(cFilepath, &file_stat) == 0);

LOGD("File exists: %s, size: %lld bytes",
     file_exists ? "YES" : "NO",
     file_exists ? (long long)file_stat.st_size : 0);

bool success = false;

try {
if (file_exists) {
// 文件存在，尝试加载
success = g_kazakh_user_dict->loadUserDict(cFilepath);
LOGD("User dictionary load result: %s", success ? "SUCCESS" : "FAILED");

// 如果加载失败，创建新词典
if (!success) {
LOGW("Loading failed, creating new empty dictionary");
g_kazakh_user_dict->clearUserDict();

// 立即保存空词典到文件
g_kazakh_user_dict->saveUserDict(cFilepath);
success = true;
}
} else {
// 文件不存在，创建新词典
LOGD("File doesn't exist, creating new user dictionary");

// 清空现有数据并创建新词典
g_kazakh_user_dict->clearUserDict();

// 立即保存到文件
success = g_kazakh_user_dict->saveUserDict(cFilepath);

// 添加一些默认词条（可选）
addDefaultWords();
}

// 如果加载成功，获取统计信息
if (success) {
std::string stats = g_kazakh_user_dict->getStats();
LOGD("User dictionary stats after loading:\n%s", stats.c_str());
}
} catch (const std::exception& e) {
LOGE("Exception in nativeLoadUserDict: %s", e.what());

// 异常时确保有有效的词典
g_kazakh_user_dict->clearUserDict();
success = true;  // 至少有空的词典
}

env->ReleaseStringUTFChars(filepath, cFilepath);

LOGD("=== nativeLoadUserDict completed: %s ===", success ? "SUCCESS" : "FAILED");
return success ? JNI_TRUE : JNI_FALSE;
}

// 保存用户词典
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeSaveUserDict(
        JNIEnv* env, jobject /* this */, jstring filepath) {

LOGD("nativeSaveUserDict called");

if (!g_kazakh_user_dict_initialized || g_kazakh_user_dict == nullptr) {
LOGE("User dictionary not initialized for save");
return JNI_FALSE;
}

const char* cFilepath = env->GetStringUTFChars(filepath, nullptr);
if (cFilepath == nullptr) {
LOGE("Failed to get filepath string");
return JNI_FALSE;
}

bool success = false;
try {
success = g_kazakh_user_dict->saveUserDict(cFilepath);
LOGD("User dictionary save result: %s", success ? "SUCCESS" : "FAILED");
} catch (const std::exception& e) {
LOGE("Exception saving user dictionary: %s", e.what());
}

env->ReleaseStringUTFChars(filepath, cFilepath);
return success ? JNI_TRUE : JNI_FALSE;
}

// 添加单词到用户词典
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeAddWord(
        JNIEnv* env, jobject /* this */, jstring word, jint frequency) {

LOGD("nativeAddWord called");

if (!initializeKazakhUserDict()) {
LOGE("Failed to initialize user dictionary");
return JNI_FALSE;
}

const char* cWord = env->GetStringUTFChars(word, nullptr);
if (cWord == nullptr) {
LOGE("Failed to get word string");
return JNI_FALSE;
}

bool success = false;
try {
success = g_kazakh_user_dict->addWord(cWord, frequency);
LOGD("Add word result: '%s' -> %s", cWord, success ? "SUCCESS" : "FAILED");
} catch (const std::exception& e) {
LOGE("Exception adding word: %s", e.what());
}

env->ReleaseStringUTFChars(word, cWord);
return success ? JNI_TRUE : JNI_FALSE;
}

// 添加上下文单词
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeAddWordWithContext(
        JNIEnv* env, jobject /* this */, jstring word, jstring contextWord, jint frequency) {

LOGD("nativeAddWordWithContext called - 快速检查");

// 快速检查：如果频率无效或字符串为空，立即返回
if (frequency <= 0) {
LOGD("频率无效，跳过添加");
return JNI_FALSE;
}

const char* cWord = env->GetStringUTFChars(word, nullptr);
const char* cContextWord = env->GetStringUTFChars(contextWord, nullptr);

if (cWord == nullptr || cContextWord == nullptr) {
LOGD("字符串获取失败，跳过添加");
if (cWord) env->ReleaseStringUTFChars(word, cWord);
if (cContextWord) env->ReleaseStringUTFChars(contextWord, cContextWord);
return JNI_FALSE;
}

// 快速检查：字符串长度
if (strlen(cWord) == 0 || strlen(cContextWord) == 0) {
LOGD("空字符串，跳过添加");
env->ReleaseStringUTFChars(word, cWord);
env->ReleaseStringUTFChars(contextWord, cContextWord);
return JNI_FALSE;
}

bool success = false;

// 关键修复：确保用户词典已初始化，但操作快速
if (!initializeKazakhUserDict()) {
LOGD("用户词典初始化失败，跳过添加");
env->ReleaseStringUTFChars(word, cWord);
env->ReleaseStringUTFChars(contextWord, cContextWord);
return JNI_FALSE;
}

try {
// 快速操作：避免复杂的锁操作
success = g_kazakh_user_dict->addWordWithContext(cWord, cContextWord, frequency);

if (success) {
LOGD("单词添加上下文成功: '%s' -> '%s' (freq: %d)",
     cWord, cContextWord, frequency);
} else {
LOGD("单词添加上下文失败: '%s' -> '%s'", cWord, cContextWord);
}
} catch (const std::exception& e) {
LOGE("添加单词上下文异常: %s", e.what());
}

env->ReleaseStringUTFChars(word, cWord);
env->ReleaseStringUTFChars(contextWord, cContextWord);

LOGD("nativeAddWordWithContext 完成");
return success ? JNI_TRUE : JNI_FALSE;
}

// 从用户词典删除单词
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeRemoveWord(
        JNIEnv* env, jobject /* this */, jstring word) {

LOGD("nativeRemoveWord called");

if (!g_kazakh_user_dict_initialized || g_kazakh_user_dict == nullptr) {
LOGE("User dictionary not initialized for remove");
return JNI_FALSE;
}

const char* cWord = env->GetStringUTFChars(word, nullptr);
if (cWord == nullptr) {
LOGE("Failed to get word string");
return JNI_FALSE;
}

bool success = false;
try {
success = g_kazakh_user_dict->removeWord(cWord);
LOGD("Remove word result: '%s' -> %s", cWord, success ? "SUCCESS" : "FAILED");
} catch (const std::exception& e) {
LOGE("Exception removing word: %s", e.what());
}

env->ReleaseStringUTFChars(word, cWord);
return success ? JNI_TRUE : JNI_FALSE;
}

// 更新单词频率
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeUpdateWordFrequency(
        JNIEnv* env, jobject /* this */, jstring word, jint delta) {

LOGD("nativeUpdateWordFrequency called");

if (!g_kazakh_user_dict_initialized || g_kazakh_user_dict == nullptr) {
LOGE("User dictionary not initialized for update");
return JNI_FALSE;
}

const char* cWord = env->GetStringUTFChars(word, nullptr);
if (cWord == nullptr) {
LOGE("Failed to get word string");
return JNI_FALSE;
}

bool success = false;
try {
success = g_kazakh_user_dict->updateWordFrequency(cWord, delta);
LOGD("Update word frequency result: '%s' delta=%d -> %s",
     cWord, delta, success ? "SUCCESS" : "FAILED");
} catch (const std::exception& e) {
LOGE("Exception updating word frequency: %s", e.what());
}

env->ReleaseStringUTFChars(word, cWord);
return success ? JNI_TRUE : JNI_FALSE;
}

// 修改用户词典前缀搜索函数
JNIEXPORT jobjectArray JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeUserDictPrefixSearch(
        JNIEnv* env, jobject /* this */, jstring prefix, jint maxResults) {

LOGD("=== User dictionary prefix search start ===");

// 添加时间戳
auto startTime = std::chrono::steady_clock::now();

// 即使没有初始化，也返回空数组而不是nullptr
if (!g_kazakh_user_dict_initialized || g_kazakh_user_dict == nullptr) {
LOGD("User dictionary not initialized, returning empty array");

// 创建空数组并返回
jclass stringClass = env->FindClass("java/lang/String");
jobjectArray emptyArray = env->NewObjectArray(0, stringClass, nullptr);
return emptyArray;
}

const char* cPrefix = env->GetStringUTFChars(prefix, nullptr);
if (cPrefix == nullptr) {
LOGE("Failed to get prefix string");
return nullptr;
}

LOGD("User dict searching prefix: '%s', maxResults: %d", cPrefix, maxResults);

std::vector<std::string> results;

try {
// 添加超时保护
auto timeout = std::chrono::milliseconds(100);
auto future = std::async(std::launch::async, [&]() {
    return g_kazakh_user_dict->searchPrefix(cPrefix, maxResults);
});

if (future.wait_for(timeout) == std::future_status::ready) {
results = future.get();
LOGD("User dict prefix search completed, found %zu results", results.size());
} else {
LOGE("User dict prefix search timeout after %lld ms",
     (long long)std::chrono::duration_cast<std::chrono::milliseconds>(timeout).count());
results.clear();
}

} catch (const std::exception& e) {
LOGE("Exception during user dict prefix search: %s", e.what());
results.clear();
}

env->ReleaseStringUTFChars(prefix, cPrefix);

// 转换为Java数组
jobjectArray array = convertStringVectorToJavaArray(env, results);

auto endTime = std::chrono::steady_clock::now();
auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);

LOGD("=== User dictionary prefix search end (took %lld ms) ===",
     (long long)duration.count());

return array;
}

// 用户词典上下文搜索
JNIEXPORT jobjectArray JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeUserDictContextSearch(
        JNIEnv* env, jobject /* this */, jstring previousWord, jstring currentPrefix, jint maxResults) {

LOGD("=== User dictionary context search start ===");

auto startTime = std::chrono::steady_clock::now();

if (!g_kazakh_user_dict_initialized || g_kazakh_user_dict == nullptr) {
LOGE("User dictionary not initialized for context search");

// 创建空数组并返回
jclass stringClass = env->FindClass("java/lang/String");
jobjectArray emptyArray = env->NewObjectArray(0, stringClass, nullptr);
return emptyArray;
}

const char* cPreviousWord = env->GetStringUTFChars(previousWord, nullptr);
const char* cCurrentPrefix = env->GetStringUTFChars(currentPrefix, nullptr);
if (cPreviousWord == nullptr || cCurrentPrefix == nullptr) {
LOGE("Failed to get input strings");
if (cPreviousWord) env->ReleaseStringUTFChars(previousWord, cPreviousWord);
if (cCurrentPrefix) env->ReleaseStringUTFChars(currentPrefix, cCurrentPrefix);

// 返回空数组
jclass stringClass = env->FindClass("java/lang/String");
jobjectArray emptyArray = env->NewObjectArray(0, stringClass, nullptr);
return emptyArray;
}

LOGD("User dict context search: previous='%s', current='%s', maxResults=%d",
     cPreviousWord, cCurrentPrefix, maxResults);

std::vector<std::string> results;

try {
// 添加超时保护
auto timeout = std::chrono::milliseconds(100);
auto future = std::async(std::launch::async, [&]() {
    return g_kazakh_user_dict->searchWithContext(cPreviousWord, cCurrentPrefix, maxResults);
});

if (future.wait_for(timeout) == std::future_status::ready) {
results = future.get();
LOGD("User dict context search completed, found %zu results", results.size());
} else {
LOGE("User dict context search timeout after %lld ms",
     (long long)std::chrono::duration_cast<std::chrono::milliseconds>(timeout).count());
results.clear();
}

} catch (const std::exception& e) {
LOGE("Exception during user dict context search: %s", e.what());
results.clear();
}

env->ReleaseStringUTFChars(previousWord, cPreviousWord);
env->ReleaseStringUTFChars(currentPrefix, cCurrentPrefix);

// 转换为Java数组
jobjectArray array = convertStringVectorToJavaArray(env, results);

auto endTime = std::chrono::steady_clock::now();
auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(endTime - startTime);

LOGD("=== User dictionary context search end (took %lld ms) ===",
     (long long)duration.count());

return array;
}

// 检查单词是否在用户词典中
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeContainsWord(
        JNIEnv* env, jobject /* this */, jstring word) {

LOGD("User dictionary contains word check");

if (!g_kazakh_user_dict_initialized || g_kazakh_user_dict == nullptr) {
LOGE("User dictionary not initialized for contains check");
return JNI_FALSE;
}

const char* cWord = env->GetStringUTFChars(word, nullptr);
if (cWord == nullptr) {
LOGE("Failed to get word string");
return JNI_FALSE;
}

bool found = false;

try {
found = g_kazakh_user_dict->containsWord(cWord);
LOGD("User dict contains word '%s': %s", cWord, found ? "YES" : "NO");
} catch (const std::exception& e) {
LOGE("Exception checking word in user dict: %s", e.what());
found = false;
}

env->ReleaseStringUTFChars(word, cWord);

return found ? JNI_TRUE : JNI_FALSE;
}

// 批量导入单词
JNIEXPORT jboolean JNICALL
        Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeImportWords(
        JNIEnv* env, jobject /* this */, jobjectArray wordsArray) {

LOGD("Import words batch to user dictionary");

if (!initializeKazakhUserDict()) {
LOGE("Failed to initialize user dictionary");
return JNI_FALSE;
}

jsize length = env->GetArrayLength(wordsArray);
if (length == 0) {
LOGD("Empty words array, nothing to import");
return JNI_TRUE;
}

std::vector<std::string> words;
words.reserve(length);

try {
for (jsize i = 0; i < length; i++) {
jstring wordObj = (jstring)env->GetObjectArrayElement(wordsArray, i);
if (wordObj != nullptr) {
const char* cWord = env->GetStringUTFChars(wordObj, nullptr);
if (cWord != nullptr) {
words.push_back(cWord);
env->ReleaseStringUTFChars(wordObj, cWord);
}
env->DeleteLocalRef(wordObj);
}
}

bool success = g_kazakh_user_dict->importWords(words);
LOGD("Import words batch result: %zu words -> %s",
     words.size(), success ? "SUCCESS" : "FAILED");

return success ? JNI_TRUE : JNI_FALSE;

} catch (const std::exception& e) {
LOGE("Exception importing words batch: %s", e.what());
return JNI_FALSE;
}
}

// 清空用户词典
JNIEXPORT jboolean JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeClearUserDict(
        JNIEnv* env, jobject /* this */) {

    LOGD("Clear user dictionary");

    if (!g_kazakh_user_dict_initialized || g_kazakh_user_dict == nullptr) {
        LOGE("User dictionary not initialized for clear");
        return JNI_FALSE;
    }

    try {
        bool success = g_kazakh_user_dict->clearUserDict();
        LOGD("Clear user dictionary result: %s", success ? "SUCCESS" : "FAILED");
        return success ? JNI_TRUE : JNI_FALSE;
    } catch (const std::exception& e) {
        LOGE("Exception clearing user dictionary: %s", e.what());
        return JNI_FALSE;
    }
}

// 获取用户词典统计信息
JNIEXPORT jstring JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeGetUserDictStats(
        JNIEnv* env, jobject /* this */) {

    LOGD("Get user dictionary stats");

    if (!g_kazakh_user_dict_initialized || g_kazakh_user_dict == nullptr) {
        return env->NewStringUTF("User dictionary not initialized");
    }

    try {
        std::string stats = g_kazakh_user_dict->getStats();
        return env->NewStringUTF(stats.c_str());
    } catch (const std::exception& e) {
        std::string error = "Error getting user dict stats: ";
        error += e.what();
        return env->NewStringUTF(error.c_str());
    }
}

// 检查用户词典是否初始化
JNIEXPORT jboolean JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeIsUserDictInitialized(
        JNIEnv* /* env */, jobject /* this */) {

    bool initialized = g_kazakh_user_dict_initialized && (g_kazakh_user_dict != nullptr);
    LOGD("nativeIsUserDictInitialized: %s", initialized ? "Yes" : "No");
    return initialized ? JNI_TRUE : JNI_FALSE;
}

// 学习输入
JNIEXPORT void JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeLearnFromInput(
        JNIEnv* env, jobject /* this */, jstring word, jstring context) {

LOGD("Learn from input");

if (!initializeKazakhUserDict()) {
LOGE("Failed to initialize user dictionary for learning");
return;
}

const char* cWord = env->GetStringUTFChars(word, nullptr);
const char* cContext = context ? env->GetStringUTFChars(context, nullptr) : nullptr;

if (cWord == nullptr) {
LOGE("Failed to get word string");
return;
}

try {
if (cContext != nullptr) {
g_kazakh_user_dict->learnFromInput(cWord, cContext);
LOGD("Learned from input with context: word='%s', context='%s'", cWord, cContext);
} else {
g_kazakh_user_dict->learnFromInput(cWord);
LOGD("Learned from input: word='%s'", cWord);
}
} catch (const std::exception& e) {
LOGE("Exception learning from input: %s", e.what());
}

env->ReleaseStringUTFChars(word, cWord);
if (cContext != nullptr) {
env->ReleaseStringUTFChars(context, cContext);
}
}

// 关闭用户词典
JNIEXPORT void JNICALL
Java_com_example_nasboard_ime_dictionary_KazakhUserDictManager_nativeCloseUserDict(
        JNIEnv* /* env */, jobject /* this */) {

LOGD("nativeCloseUserDict called");
cleanupKazakhUserDict();
}

} // extern "C"