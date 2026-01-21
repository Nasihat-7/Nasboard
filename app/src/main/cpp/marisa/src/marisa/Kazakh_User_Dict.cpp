#include "marisa/Kazakh_User_Dict.h"
#include <fstream>
#include <sstream>
#include <iostream>
#include <algorithm>
#include <chrono>
#include <iomanip>
#include <cstring>
#include <android/log.h>
#include <sys/stat.h>
#include <unistd.h>
#include <queue>
#include <unordered_set>
#include <unordered_map>
#include <memory>
#include <algorithm>
#include <future>
#include <shared_mutex>
#include <thread>
#include <condition_variable>

// 日志宏定义
#define LOG_TAG "KazakhUserDict"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace kazakh_ime {

// 哈萨克语字符规范化表
    static const std::unordered_map<char16_t, char16_t> kazakhCharNormalization = {
            {0x0410, 0x0430}, // А -> а
            {0x0411, 0x0431}, // Б -> б
            {0x0412, 0x0432}, // В -> в
            {0x0413, 0x0433}, // Г -> г
            {0x0414, 0x0434}, // Д -> д
            {0x0415, 0x0435}, // Е -> е
            {0x0416, 0x0436}, // Ж -> ж
            {0x0417, 0x0437}, // З -> з
            {0x0418, 0x0438}, // И -> и
            {0x0419, 0x0439}, // Й -> й
            {0x041a, 0x043a}, // К -> к
            {0x041b, 0x043b}, // Л -> л
            {0x041c, 0x043c}, // М -> м
            {0x041d, 0x043d}, // Н -> н
            {0x041e, 0x043e}, // О -> о
            {0x041f, 0x043f}, // П -> п
            {0x0420, 0x0440}, // Р -> р
            {0x0421, 0x0441}, // С -> с
            {0x0422, 0x0442}, // Т -> т
            {0x0423, 0x0443}, // У -> у
            {0x0424, 0x0444}, // Ф -> ф
            {0x0425, 0x0445}, // Х -> х
            {0x0426, 0x0446}, // Ц -> ц
            {0x0427, 0x0447}, // Ч -> ч
            {0x0428, 0x0448}, // Ш -> ш
            {0x0429, 0x0449}, // Щ -> щ
            {0x042a, 0x044a}, // Ъ -> ъ
            {0x042b, 0x044b}, // Ы -> ы
            {0x042c, 0x044c}, // Ь -> ь
            {0x042d, 0x044d}, // Э -> э
            {0x042e, 0x044e}, // Ю -> ю
            {0x042f, 0x044f}, // Я -> я
            {0x0492, 0x0493}, // Ғ -> ғ
            {0x049a, 0x049b}, // Қ -> қ
            {0x04e8, 0x04e9}, // Ө -> ө
            {0x04ae, 0x04af}, // Ү -> ү
            {0x04d8, 0x04d9}, // Ә -> ә
            {0x0406, 0x0456}, // I -> i
            {0x04a2, 0x04a3}, // Ң -> ң
            {0x04b0, 0x04b1}, // Һ -> һ
    };

// ========== 快照访问辅助函数 ==========
    std::shared_ptr<KazakhUserDict::Snapshot> KazakhUserDict::getCurrentSnapshot() const {
        std::lock_guard<std::mutex> lock(snapshotPtrMutex_);
        return currentSnapshot_;
    }

    void KazakhUserDict::setCurrentSnapshot(const std::shared_ptr<Snapshot>& snapshot) {
        std::lock_guard<std::mutex> lock(snapshotPtrMutex_);
        currentSnapshot_ = snapshot;
    }

// ========== 单例实现 ==========
    KazakhUserDict& KazakhUserDict::getInstance() {
        static KazakhUserDict instance;
        return instance;
    }

    KazakhUserDict::KazakhUserDict() {
        // 初始化工作数据
        workingData_ = std::make_unique<WorkingData>();
        workingData_->trieRoot = std::make_shared<SnapshotTrieNode>();

        // 创建初始空快照
        auto emptySnapshot = std::make_shared<Snapshot>();
        emptySnapshot->trieRoot = std::make_shared<SnapshotTrieNode>();
        emptySnapshot->timestamp = getCurrentTimestamp();
        emptySnapshot->version = 0;
        setCurrentSnapshot(emptySnapshot);

        // 启动快照后台线程
        snapshotThread_ = std::thread(&KazakhUserDict::snapshotWorkerThread, this);

        LOGD("KazakhUserDict: Initialized with background snapshot thread");
    }

    KazakhUserDict::~KazakhUserDict() {
        shutdown();
    }

    void KazakhUserDict::shutdown() {
        if (shutdownFlag_.exchange(true)) {
            return;
        }

        // 通知后台线程退出
        {
            std::lock_guard<std::mutex> lock(snapshotMutex_);
            snapshotCV_.notify_all();
        }

        // 等待后台线程结束
        if (snapshotThread_.joinable()) {
            snapshotThread_.join();
        }

        // 保存数据
        flushToDisk();

        LOGD("KazakhUserDict: Shutdown complete");
    }

// ========== UTF转换函数（安全实现） ==========
    bool KazakhUserDict::utf8ToUtf16Safe(const std::string& utf8, std::u16string& utf16) const {
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
                // 1字节UTF-8 (0xxxxxxx)
                utf16.push_back(static_cast<char16_t>(c));
            } else if ((c & 0xE0) == 0xC0 && i < utf8.size()) {
                // 2字节UTF-8 (110xxxxx 10xxxxxx)
                uint8_t c2 = static_cast<uint8_t>(utf8[i++]);
                if ((c2 & 0xC0) != 0x80) {
                    LOGE("utf8ToUtf16Safe: Invalid UTF-8 sequence at position %zu", i - 2);
                    return false;
                }
                char16_t code = ((c & 0x1F) << 6) | (c2 & 0x3F);
                utf16.push_back(code);
            } else if ((c & 0xF0) == 0xE0 && i + 1 < utf8.size()) {
                // 3字节UTF-8 (1110xxxx 10xxxxxx 10xxxxxx)
                uint8_t c2 = static_cast<uint8_t>(utf8[i++]);
                uint8_t c3 = static_cast<uint8_t>(utf8[i++]);
                if ((c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80) {
                    LOGE("utf8ToUtf16Safe: Invalid UTF-8 sequence at position %zu", i - 3);
                    return false;
                }
                char16_t code = ((c & 0x0F) << 12) | ((c2 & 0x3F) << 6) | (c3 & 0x3F);
                utf16.push_back(code);
            } else if ((c & 0xF8) == 0xF0 && i + 2 < utf8.size()) {
                // 4字节UTF-8 (11110xxx 10xxxxxx 10xxxxxx 10xxxxxx) - 需要代理对
                uint8_t c2 = static_cast<uint8_t>(utf8[i++]);
                uint8_t c3 = static_cast<uint8_t>(utf8[i++]);
                uint8_t c4 = static_cast<uint8_t>(utf8[i++]);

                if ((c2 & 0xC0) != 0x80 || (c3 & 0xC0) != 0x80 || (c4 & 0xC0) != 0x80) {
                    LOGE("utf8ToUtf16Safe: Invalid UTF-8 sequence at position %zu", i - 4);
                    return false;
                }

                uint32_t codePoint = ((c & 0x07) << 18) | ((c2 & 0x3F) << 12) |
                                     ((c3 & 0x3F) << 6) | (c4 & 0x3F);

                if (codePoint > 0x10FFFF) {
                    LOGE("utf8ToUtf16Safe: Code point out of range: U+%06X", codePoint);
                    return false;
                }

                if (codePoint > 0xFFFF) {
                    // 转换为UTF-16代理对
                    codePoint -= 0x10000;
                    char16_t highSurrogate = static_cast<char16_t>((codePoint >> 10) + 0xD800);
                    char16_t lowSurrogate = static_cast<char16_t>((codePoint & 0x3FF) + 0xDC00);
                    utf16.push_back(highSurrogate);
                    utf16.push_back(lowSurrogate);
                } else {
                    utf16.push_back(static_cast<char16_t>(codePoint));
                }
            } else {
                LOGE("utf8ToUtf16Safe: Invalid UTF-8 lead byte: 0x%02X at position %zu", c, i - 1);
                return false;
            }
        }

        return true;
    }

    bool KazakhUserDict::utf16ToUtf8Safe(const std::u16string& utf16, std::string& utf8) const {
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
                    LOGE("utf16ToUtf8Safe: Invalid UTF-16 surrogate pair at position %zu", i - 2);
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
                LOGE("utf16ToUtf8Safe: Invalid UTF-16 code unit: 0x%04X at position %zu", c, i - 1);
                return false;
            }
        }

        return true;
    }

    std::u16string KazakhUserDict::utf8ToUtf16(const std::string& str) const {
        std::u16string result;
        if (utf8ToUtf16Safe(str, result)) {
            {
                std::lock_guard<std::mutex> lock(statsMutex_);
                performanceStats_.utf8ToUtf16Calls++;
            }
            return result;
        }

        LOGE("utf8ToUtf16: Conversion failed for string: %s", str.c_str());
        return std::u16string();
    }

    std::string KazakhUserDict::utf16ToUtf8(const std::u16string& str) const {
        std::string result;
        if (utf16ToUtf8Safe(str, result)) {
            {
                std::lock_guard<std::mutex> lock(statsMutex_);
                performanceStats_.utf16ToUtf8Calls++;
            }
            return result;
        }

        LOGE("utf16ToUtf8: Conversion failed");
        return std::string();
    }

// ========== 时间函数（使用system_clock） ==========
    uint64_t KazakhUserDict::getCurrentTimestamp() {
        auto now = std::chrono::system_clock::now();
        auto duration = now.time_since_epoch();

        // 返回毫秒数（更精确，兼容秒数）
        return std::chrono::duration_cast<std::chrono::milliseconds>(duration).count();

        // 如果只需要秒数，使用：
        // return std::chrono::duration_cast<std::chrono::seconds>(duration).count();
    }

// ========== 规范化函数 ==========
    char16_t KazakhUserDict::normalizeChar(char16_t ch) const {
        auto it = kazakhCharNormalization.find(ch);
        return it != kazakhCharNormalization.end() ? it->second : ch;
    }

    std::u16string KazakhUserDict::normalizeString(const std::string& str) const {
        std::u16string u16str = utf8ToUtf16(str);
        if (u16str.empty() && !str.empty()) {
            return u16str;
        }

        std::u16string result;
        result.reserve(u16str.length());
        for (char16_t ch : u16str) {
            result.push_back(normalizeChar(ch));
        }
        return result;
    }

    std::string KazakhUserDict::normalizeAndConvertToString(const std::string& str) const {
        std::u16string normalized = normalizeString(str);
        if (normalized.empty() && !str.empty()) {
            LOGE("normalizeAndConvertToString: Normalization failed for: %s", str.c_str());
            return str;
        }
        return utf16ToUtf8(normalized);
    }

// ========== 性能统计 ==========
    KazakhUserDict::PerformanceStats KazakhUserDict::getPerformanceStats() const {
        std::lock_guard<std::mutex> lock(statsMutex_);
        return performanceStats_;
    }

// ========== 快照后台线程 ==========
    void KazakhUserDict::snapshotWorkerThread() {
        LOGD("snapshotWorkerThread: Started");

        while (!shutdownFlag_.load()) {
            std::unique_lock<std::mutex> lock(snapshotMutex_);

            snapshotCV_.wait_for(lock, std::chrono::milliseconds(100), [this] {
                return snapshotDirty_.load() || shutdownFlag_.load();
            });

            if (shutdownFlag_.load()) {
                break;
            }

            if (snapshotDirty_.load()) {
                snapshotDirty_.store(false);
                size_t pending = pendingUpdateCount_.exchange(0);

                {
                    std::lock_guard<std::mutex> statsLock(statsMutex_);
                    performanceStats_.pendingSnapshotUpdates = pending;
                    performanceStats_.mergedSnapshotUpdates++;
                }

                LOGD("snapshotWorkerThread: Processing %zu pending updates", pending);

                lock.unlock();
                updateSnapshotInternal();

                LOGD("snapshotWorkerThread: Snapshot updated to v%zu", snapshotVersion_.load());
            }
        }

        LOGD("snapshotWorkerThread: Stopped");
    }

    void KazakhUserDict::requestSnapshotUpdate() {
        pendingUpdateCount_++;
        snapshotDirty_.store(true);

        {
            std::lock_guard<std::mutex> lock(snapshotMutex_);
            snapshotCV_.notify_one();
        }

        {
            std::lock_guard<std::mutex> lock(statsMutex_);
            performanceStats_.debouncedSnapshotUpdates++;
        }
    }

// ========== 快照构建 ==========
    std::shared_ptr<KazakhUserDict::Snapshot>
    KazakhUserDict::buildSnapshotFromWorkingData() {
        auto startTime = std::chrono::steady_clock::now();

        auto snapshot = std::make_shared<Snapshot>();

        {
            std::shared_lock<std::shared_mutex> lock(workingDataMutex_);

            snapshot->wordCount = workingData_->wordCount;
            snapshot->totalFrequency = workingData_->totalFrequency;

            for (const auto& pair : workingData_->wordMap) {
                auto newEntry = std::make_shared<UserDictEntry>(*pair.second);
                snapshot->wordMap[pair.first] = newEntry;
                snapshot->normalizedWordMap[newEntry->normalizedWord] = newEntry;
            }

            for (const auto& pair : workingData_->contextMap) {
                std::vector<std::shared_ptr<UserDictEntry>> entries;
                entries.reserve(pair.second.size());

                for (const auto& entry : pair.second) {
                    auto it = snapshot->wordMap.find(entry->word);
                    if (it != snapshot->wordMap.end()) {
                        entries.push_back(it->second);
                    }
                }

                if (!entries.empty()) {
                    snapshot->contextMap[pair.first] = std::move(entries);
                }
            }

            snapshot->trieRoot = cloneTrieNode(workingData_->trieRoot);
        }

        populatePrefixMap(*snapshot);
        snapshot->timestamp = getCurrentTimestamp();
        snapshot->version = snapshotVersion_.fetch_add(1) + 1;

        auto endTime = std::chrono::steady_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(
                endTime - startTime);

        {
            std::lock_guard<std::mutex> lock(statsMutex_);
            performanceStats_.snapshotBuildCount++;
            performanceStats_.lastSnapshotBuildTime = duration.count();
        }

        LOGD("buildSnapshotFromWorkingData: Built snapshot v%zu with %d words in %lld ms",
             snapshot->version, snapshot->wordCount, (long long)duration.count());

        return snapshot;
    }

    std::shared_ptr<KazakhUserDict::SnapshotTrieNode>
    KazakhUserDict::cloneTrieNode(const std::shared_ptr<SnapshotTrieNode>& src) {
        if (!src) return nullptr;

        auto dst = std::make_shared<SnapshotTrieNode>();
        dst->isEndOfWord = src->isEndOfWord;

        for (const auto& pair : src->children) {
            auto clonedChild = cloneTrieNode(pair.second);
            if (clonedChild) {
                dst->children[pair.first] = clonedChild;
            }
        }

        dst->entries = src->entries;

        return dst;
    }

    void KazakhUserDict::populatePrefixMap(Snapshot& snapshot) {
        auto startTime = std::chrono::steady_clock::now();

        snapshot.prefixMap.clear();

        for (const auto& pair : snapshot.normalizedWordMap) {
            const std::string& normalizedWord = pair.first;
            const auto& entry = pair.second;

            for (size_t len = 1; len <= normalizedWord.length(); ++len) {
                std::string prefix = normalizedWord.substr(0, len);
                snapshot.prefixMap[prefix].push_back(entry);
            }
        }

        auto endTime = std::chrono::steady_clock::now();
        auto duration = std::chrono::duration_cast<std::chrono::microseconds>(
                endTime - startTime);

        LOGD("populatePrefixMap: Built prefix map for %zu words in %lld µs",
             snapshot.wordMap.size(), (long long)duration.count());
    }

    void KazakhUserDict::updateSnapshotInternal() {
        auto newSnapshot = buildSnapshotFromWorkingData();
        setCurrentSnapshot(newSnapshot);

        {
            std::lock_guard<std::mutex> lock(statsMutex_);
            performanceStats_.snapshotReadCount = 0;
        }
    }

// ========== 工作数据操作 ==========
    bool KazakhUserDict::addWordToWorkingData(const std::string& word, int frequency,
                                              bool checkExists) {
        LOGD("addWordToWorkingData: Adding word '%s' with frequency %d",
             word.c_str(), frequency);

        std::string normalizedWord = normalizeAndConvertToString(word);
        if (normalizedWord.empty() && !word.empty()) {
            LOGE("addWordToWorkingData: Normalization failed for word: %s", word.c_str());
            return false;
        }

        if (checkExists) {
            auto it = workingData_->normalizedWordMap.find(normalizedWord);
            if (it != workingData_->normalizedWordMap.end()) {
                it->second->frequency += frequency;
                workingData_->totalFrequency += frequency;
                it->second->lastUsed = getCurrentTimestamp();
                workingData_->dirty = true;

                LOGD("addWordToWorkingData: Updated existing word '%s' (normalized: '%s') to frequency %d",
                     word.c_str(), normalizedWord.c_str(), it->second->frequency);
                return true;
            }
        }

        auto entry = std::make_shared<UserDictEntry>(word, normalizedWord, frequency);
        entry->created = getCurrentTimestamp();
        entry->lastUsed = entry->created;

        workingData_->wordMap[word] = entry;
        workingData_->normalizedWordMap[normalizedWord] = entry;
        workingData_->wordCount++;
        workingData_->totalFrequency += frequency;
        workingData_->dirty = true;

        updateWorkingDataTrie(word, normalizedWord, entry, true);

        LOGD("addWordToWorkingData: Added new word '%s' (normalized: '%s'), total words: %d",
             word.c_str(), normalizedWord.c_str(), workingData_->wordCount);

        return true;
    }

    bool KazakhUserDict::addWordWithContextToWorkingData(const std::string& word,
                                                         const std::string& contextWord,
                                                         int frequency) {
        std::string normalizedContext = normalizeAndConvertToString(contextWord);
        if (normalizedContext.empty() && !contextWord.empty()) {
            LOGE("addWordWithContextToWorkingData: Normalization failed for context: %s",
                 contextWord.c_str());
            return false;
        }

        if (!addWordToWorkingData(word, frequency, true)) {
            return false;
        }

        std::string normalizedWord = normalizeAndConvertToString(word);
        auto entry = workingData_->normalizedWordMap[normalizedWord];

        if (std::find(entry->contexts.begin(), entry->contexts.end(), normalizedContext)
            == entry->contexts.end()) {
            entry->contexts.push_back(normalizedContext);
            workingData_->contextMap[normalizedContext].push_back(entry);
        }

        workingData_->dirty = true;
        return true;
    }

    bool KazakhUserDict::removeWordFromWorkingData(const std::string& word) {
        std::string normalizedWord = normalizeAndConvertToString(word);
        if (normalizedWord.empty() && !word.empty()) {
            LOGE("removeWordFromWorkingData: Normalization failed for word: %s", word.c_str());
            return false;
        }

        auto it = workingData_->normalizedWordMap.find(normalizedWord);
        if (it == workingData_->normalizedWordMap.end()) {
            return false;
        }

        auto entry = it->second;
        updateWorkingDataTrie(entry->word, normalizedWord, entry, false);

        for (const auto& context : entry->contexts) {
            auto& contextList = workingData_->contextMap[context];
            contextList.erase(
                    std::remove_if(contextList.begin(), contextList.end(),
                                   [&entry](const std::shared_ptr<UserDictEntry>& e) {
                                       return e->normalizedWord == entry->normalizedWord;
                                   }),
                    contextList.end()
            );

            if (contextList.empty()) {
                workingData_->contextMap.erase(context);
            }
        }

        workingData_->wordMap.erase(entry->word);
        workingData_->normalizedWordMap.erase(normalizedWord);
        workingData_->totalFrequency -= entry->frequency;
        workingData_->wordCount--;
        workingData_->dirty = true;

        LOGD("removeWordFromWorkingData: Removed word '%s' (normalized: '%s'), remaining words: %d",
             word.c_str(), normalizedWord.c_str(), workingData_->wordCount);

        return true;
    }

    std::shared_ptr<KazakhUserDict::SnapshotTrieNode>
    KazakhUserDict::findOrCreateNode(std::shared_ptr<SnapshotTrieNode> root,
                                     const std::u16string& path) {
        auto node = root;

        for (char16_t ch : path) {
            auto& children = node->children;
            auto it = children.find(ch);
            if (it == children.end()) {
                auto newNode = std::make_shared<SnapshotTrieNode>();
                children[ch] = newNode;
                node = newNode;
            } else {
                node = it->second;
            }
        }

        return node;
    }

    void KazakhUserDict::updateWorkingDataTrie(const std::string& word,
                                               const std::string& normalizedWord,
                                               std::shared_ptr<UserDictEntry> entry,
                                               bool add) {
        std::u16string u16word = utf8ToUtf16(normalizedWord);
        if (u16word.empty() && !normalizedWord.empty()) {
            LOGE("updateWorkingDataTrie: Failed to convert normalized word: %s", normalizedWord.c_str());
            return;
        }

        auto node = findOrCreateNode(workingData_->trieRoot, u16word);

        if (add) {
            node->isEndOfWord = true;

            bool exists = false;
            for (const auto& existingEntry : node->entries) {
                if (existingEntry && existingEntry->normalizedWord == normalizedWord) {
                    exists = true;
                    break;
                }
            }

            if (!exists && entry) {
                node->entries.push_back(entry);
            }
        } else {
            if (entry) {
                auto it = std::remove_if(node->entries.begin(), node->entries.end(),
                                         [&entry](const std::shared_ptr<UserDictEntry>& e) {
                                             return e && e->normalizedWord == entry->normalizedWord;
                                         });
                node->entries.erase(it, node->entries.end());
            }

            if (node->entries.empty()) {
                node->isEndOfWord = false;
            }
        }
    }

// ========== 搜索内部方法 ==========
    std::vector<std::shared_ptr<KazakhUserDict::UserDictEntry>>
    KazakhUserDict::searchPrefixInSnapshot(const std::shared_ptr<Snapshot>& snapshot,
                                           const std::string& normalizedPrefix,
                                           int maxResults) {
        std::vector<std::shared_ptr<UserDictEntry>> results;

        if (!snapshot || maxResults <= 0) {
            return results;
        }

        if (!snapshot->prefixMap.empty()) {
            auto it = snapshot->prefixMap.find(normalizedPrefix);
            if (it != snapshot->prefixMap.end()) {
                const auto& entries = it->second;

                std::vector<std::shared_ptr<UserDictEntry>> sortedEntries = entries;

                if (sortedEntries.size() > static_cast<size_t>(maxResults)) {
                    std::partial_sort(sortedEntries.begin(),
                                      sortedEntries.begin() + maxResults,
                                      sortedEntries.end(),
                                      [](const auto& a, const auto& b) {
                                          if (a->frequency != b->frequency) {
                                              return a->frequency > b->frequency;
                                          }
                                          return a->lastUsed > b->lastUsed;
                                      });

                    results.assign(sortedEntries.begin(),
                                   sortedEntries.begin() + maxResults);
                } else {
                    std::sort(sortedEntries.begin(), sortedEntries.end(),
                              [](const auto& a, const auto& b) {
                                  if (a->frequency != b->frequency) {
                                      return a->frequency > b->frequency;
                                  }
                                  return a->lastUsed > b->lastUsed;
                              });

                    results = std::move(sortedEntries);
                }

                return results;
            }
        }

        auto node = snapshot->trieRoot;
        if (!node) {
            return results;
        }

        std::u16string u16prefix = utf8ToUtf16(normalizedPrefix);
        if (u16prefix.empty() && !normalizedPrefix.empty()) {
            return results;
        }

        for (char16_t ch : u16prefix) {
            auto it = node->children.find(ch);
            if (it == node->children.end()) {
                return results;
            }
            node = it->second;
        }

        std::vector<std::shared_ptr<UserDictEntry>> foundEntries;
        std::queue<std::pair<std::shared_ptr<SnapshotTrieNode>, std::u16string>> bfsQueue;
        bfsQueue.push({node, u16prefix});

        int visitedCount = 0;
        const int maxVisitedNodes = 200;

        while (!bfsQueue.empty() && visitedCount < maxVisitedNodes) {
            auto [currentNode, currentWord] = bfsQueue.front();
            bfsQueue.pop();
            visitedCount++;

            if (currentNode->isEndOfWord && !currentNode->entries.empty()) {
                for (const auto& entry : currentNode->entries) {
                    if (entry) {
                        foundEntries.push_back(entry);
                    }
                }
            }

            for (const auto& [ch, childNode] : currentNode->children) {
                std::u16string nextWord = currentWord;
                nextWord.push_back(ch);
                bfsQueue.push({childNode, nextWord});
            }
        }

        if (foundEntries.size() > static_cast<size_t>(maxResults)) {
            std::partial_sort(foundEntries.begin(),
                              foundEntries.begin() + maxResults,
                              foundEntries.end(),
                              [](const auto& a, const auto& b) {
                                  if (a->frequency != b->frequency) {
                                      return a->frequency > b->frequency;
                                  }
                                  return a->lastUsed > b->lastUsed;
                              });

            results.assign(foundEntries.begin(), foundEntries.begin() + maxResults);
        } else {
            std::sort(foundEntries.begin(), foundEntries.end(),
                      [](const auto& a, const auto& b) {
                          if (a->frequency != b->frequency) {
                              return a->frequency > b->frequency;
                          }
                          return a->lastUsed > b->lastUsed;
                      });

            results = std::move(foundEntries);
        }

        return results;
    }

    std::vector<std::shared_ptr<KazakhUserDict::UserDictEntry>>
    KazakhUserDict::searchWithContextInSnapshot(const std::shared_ptr<Snapshot>& snapshot,
                                                const std::string& normalizedPreviousWord,
                                                const std::string& normalizedCurrentPrefix,
                                                int maxResults) {
        std::vector<std::shared_ptr<UserDictEntry>> results;

        if (!snapshot || normalizedPreviousWord.empty()) {
            return results;
        }

        auto contextIt = snapshot->contextMap.find(normalizedPreviousWord);
        if (contextIt == snapshot->contextMap.end()) {
            return results;
        }

        const auto& contextEntries = contextIt->second;
        std::vector<std::shared_ptr<UserDictEntry>> filteredEntries;

        for (const auto& entry : contextEntries) {
            if (normalizedCurrentPrefix.empty() ||
                entry->normalizedWord.find(normalizedCurrentPrefix) == 0) {
                filteredEntries.push_back(entry);
            }
        }

        if (filteredEntries.size() > static_cast<size_t>(maxResults)) {
            std::partial_sort(filteredEntries.begin(),
                              filteredEntries.begin() + maxResults,
                              filteredEntries.end(),
                              [](const auto& a, const auto& b) {
                                  if (a->frequency != b->frequency) {
                                      return a->frequency > b->frequency;
                                  }
                                  return a->lastUsed > b->lastUsed;
                              });

            results.assign(filteredEntries.begin(),
                           filteredEntries.begin() + maxResults);
        } else {
            std::sort(filteredEntries.begin(), filteredEntries.end(),
                      [](const auto& a, const auto& b) {
                          if (a->frequency != b->frequency) {
                              return a->frequency > b->frequency;
                          }
                          return a->lastUsed > b->lastUsed;
                      });

            results = std::move(filteredEntries);
        }

        return results;
    }

// ========== 公有方法实现 ==========
    bool KazakhUserDict::loadUserDict(const std::string& filepath) {
        std::unique_lock<std::shared_mutex> lock(workingDataMutex_);

        if (!loadWorkingDataFromFile(filepath)) {
            return false;
        }

        requestSnapshotUpdate();

        LOGD("loadUserDict: Loaded %d words from %s",
             workingData_->wordCount, filepath.c_str());

        return true;
    }

    bool KazakhUserDict::saveUserDict(const std::string& filepath) {
        std::shared_lock<std::shared_mutex> lock(workingDataMutex_);
        return saveWorkingDataToFile(filepath);
    }

    bool KazakhUserDict::clearUserDict() {
        std::unique_lock<std::shared_mutex> lock(workingDataMutex_);

        LOGD("clearUserDict: Clearing all user dictionary data");

        workingData_ = std::make_unique<WorkingData>();
        workingData_->trieRoot = std::make_shared<SnapshotTrieNode>();

        requestSnapshotUpdate();

        LOGD("clearUserDict: Successfully cleared user dictionary");
        return true;
    }

    bool KazakhUserDict::addWord(const std::string& word, int frequency) {
        if (word.empty() || frequency <= 0) {
            LOGD("addWord: Invalid word or frequency");
            return false;
        }

        std::unique_lock<std::shared_mutex> lock(workingDataMutex_);

        bool success = addWordToWorkingData(word, frequency, true);

        if (success) {
            {
                std::lock_guard<std::mutex> statsLock(statsMutex_);
                performanceStats_.writeOperationCount++;
            }

            requestSnapshotUpdate();
        }

        return success;
    }

    bool KazakhUserDict::addWordWithContext(const std::string& word,
                                            const std::string& contextWord,
                                            int frequency) {
        if (word.empty() || contextWord.empty()) {
            return false;
        }

        std::unique_lock<std::shared_mutex> lock(workingDataMutex_);

        bool success = addWordWithContextToWorkingData(word, contextWord, frequency);

        if (success) {
            {
                std::lock_guard<std::mutex> statsLock(statsMutex_);
                performanceStats_.writeOperationCount++;
            }

            requestSnapshotUpdate();
        }

        return success;
    }

    bool KazakhUserDict::removeWord(const std::string& word) {
        if (word.empty()) {
            return false;
        }

        std::unique_lock<std::shared_mutex> lock(workingDataMutex_);

        bool success = removeWordFromWorkingData(word);

        if (success) {
            {
                std::lock_guard<std::mutex> statsLock(statsMutex_);
                performanceStats_.writeOperationCount++;
            }

            requestSnapshotUpdate();
        }

        return success;
    }

    bool KazakhUserDict::updateWordFrequency(const std::string& word, int delta) {
        if (word.empty()) {
            return false;
        }

        std::unique_lock<std::shared_mutex> lock(workingDataMutex_);

        std::string normalizedWord = normalizeAndConvertToString(word);
        auto it = workingData_->normalizedWordMap.find(normalizedWord);
        if (it == workingData_->normalizedWordMap.end()) {
            return false;
        }

        int newFreq = it->second->frequency + delta;
        if (newFreq <= 0) {
            bool success = removeWordFromWorkingData(word);

            if (success) {
                {
                    std::lock_guard<std::mutex> statsLock(statsMutex_);
                    performanceStats_.writeOperationCount++;
                }

                requestSnapshotUpdate();
            }

            return success;
        }

        workingData_->totalFrequency += delta;
        it->second->frequency = newFreq;
        it->second->lastUsed = getCurrentTimestamp();
        workingData_->dirty = true;

        {
            std::lock_guard<std::mutex> statsLock(statsMutex_);
            performanceStats_.writeOperationCount++;
        }

        requestSnapshotUpdate();

        return true;
    }

// ========== 搜索方法（完全无锁） ==========
    std::vector<std::string> KazakhUserDict::searchPrefix(const std::string& prefix,
                                                          int maxResults) {
        if (prefix.empty() || maxResults <= 0) {
            return {};
        }

        auto snapshot = getCurrentSnapshot();
        if (!snapshot || snapshot->wordCount == 0) {
            return {};
        }

        {
            std::lock_guard<std::mutex> statsLock(statsMutex_);
            performanceStats_.snapshotReadCount++;
        }

        std::vector<std::string> results;

        try {
            std::string normalizedPrefix = normalizeAndConvertToString(prefix);
            if (normalizedPrefix.empty() && !prefix.empty()) {
                LOGD("searchPrefix: Normalization failed for prefix: %s", prefix.c_str());
                return {};
            }

            auto entries = searchPrefixInSnapshot(snapshot, normalizedPrefix, maxResults);

            results.reserve(entries.size());
            for (const auto& entry : entries) {
                if (entry) {
                    results.push_back(entry->word);
                }
            }

            LOGD("searchPrefix: Found %zu results for prefix '%s' (normalized: '%s', snapshot v%zu)",
                 results.size(), prefix.c_str(), normalizedPrefix.c_str(), snapshot->version);
        } catch (const std::exception& e) {
            LOGE("searchPrefix: Exception: %s", e.what());
            results.clear();
        }

        return results;
    }

    std::vector<std::string> KazakhUserDict::searchWithContext(
            const std::string& previousWord,
            const std::string& currentPrefix,
            int maxResults) {

        if (previousWord.empty() || maxResults <= 0) {
            return {};
        }

        auto snapshot = getCurrentSnapshot();
        if (!snapshot || snapshot->wordCount == 0) {
            return {};
        }

        {
            std::lock_guard<std::mutex> statsLock(statsMutex_);
            performanceStats_.snapshotReadCount++;
        }

        std::vector<std::string> results;

        try {
            std::string normalizedPrev = normalizeAndConvertToString(previousWord);
            std::string normalizedCurrentPrefix = normalizeAndConvertToString(currentPrefix);

            if (normalizedPrev.empty() && !previousWord.empty()) {
                LOGD("searchWithContext: Normalization failed for previous word: %s", previousWord.c_str());
                return {};
            }

            auto entries = searchWithContextInSnapshot(snapshot, normalizedPrev,
                                                       normalizedCurrentPrefix, maxResults);

            results.reserve(entries.size());
            for (const auto& entry : entries) {
                if (entry) {
                    results.push_back(entry->word);
                }
            }

            LOGD("searchWithContext: Found %zu results (snapshot v%zu)",
                 results.size(), snapshot->version);
        } catch (const std::exception& e) {
            LOGE("searchWithContext: Exception: %s", e.what());
            results.clear();
        }

        return results;
    }

    bool KazakhUserDict::containsWord(const std::string& word) {
        if (word.empty()) {
            return false;
        }

        auto snapshot = getCurrentSnapshot();
        if (!snapshot) {
            return false;
        }

        std::string normalizedWord = normalizeAndConvertToString(word);
        return snapshot->normalizedWordMap.find(normalizedWord) != snapshot->normalizedWordMap.end();
    }

// ========== 批量操作 ==========
    bool KazakhUserDict::importWords(const std::vector<std::string>& words) {
        std::unique_lock<std::shared_mutex> lock(workingDataMutex_);

        bool success = true;
        for (const auto& word : words) {
            if (!word.empty()) {
                if (!addWordToWorkingData(word, 1, true)) {
                    success = false;
                }
            }
        }

        if (success) {
            {
                std::lock_guard<std::mutex> statsLock(statsMutex_);
                performanceStats_.writeOperationCount++;
            }

            requestSnapshotUpdate();
        }

        return success;
    }

    bool KazakhUserDict::exportWords(const std::string& filepath) {
        return saveUserDict(filepath);
    }

// ========== 统计信息 ==========
    int KazakhUserDict::getWordCount() const {
        auto snapshot = getCurrentSnapshot();
        return snapshot ? snapshot->wordCount : 0;
    }

    int KazakhUserDict::getTotalFrequency() const {
        auto snapshot = getCurrentSnapshot();
        return snapshot ? snapshot->totalFrequency : 0;
    }

    std::string KazakhUserDict::getStats() const {
        auto snapshot = getCurrentSnapshot();

        std::stringstream ss;
        ss << "=== Kazakh User Dictionary Stats ===\n";
        ss << "Snapshot version: " << (snapshot ? snapshot->version : 0) << "\n";
        ss << "Snapshot timestamp: " << (snapshot ? snapshot->timestamp : 0) << "\n";
        ss << "Total words: " << (snapshot ? snapshot->wordCount : 0) << "\n";
        ss << "Total frequency: " << (snapshot ? snapshot->totalFrequency : 0) << "\n";

        std::lock_guard<std::mutex> statsLock(statsMutex_);
        PerformanceStats stats = performanceStats_;

        ss << "\nPerformance Stats:\n";
        ss << "  Snapshot builds: " << stats.snapshotBuildCount << "\n";
        ss << "  Snapshot reads: " << stats.snapshotReadCount << "\n";
        ss << "  Write operations: " << stats.writeOperationCount << "\n";
        ss << "  Pending updates: " << stats.pendingSnapshotUpdates << "\n";
        ss << "  Merged updates: " << stats.mergedSnapshotUpdates << "\n";
        ss << "  Debounced updates: " << stats.debouncedSnapshotUpdates << "\n";
        ss << "  UTF-8→UTF-16 calls: " << stats.utf8ToUtf16Calls << "\n";
        ss << "  UTF-16→UTF-8 calls: " << stats.utf16ToUtf8Calls << "\n";
        ss << "  Last build time: " << stats.lastSnapshotBuildTime << " ms\n";

        return ss.str();
    }

// ========== 学习功能 ==========
    void KazakhUserDict::learnFromInput(const std::string& word, const std::string& context) {
        if (word.empty()) {
            return;
        }

        std::unique_lock<std::shared_mutex> lock(workingDataMutex_);

        if (context.empty()) {
            addWordToWorkingData(word, 1, true);
        } else {
            addWordWithContextToWorkingData(word, context, 1);
        }

        {
            std::lock_guard<std::mutex> statsLock(statsMutex_);
            performanceStats_.writeOperationCount++;
        }

        requestSnapshotUpdate();
    }

    void KazakhUserDict::decayOldEntries() {
        std::unique_lock<std::shared_mutex> lock(workingDataMutex_);

        uint64_t now = getCurrentTimestamp();
        uint64_t oneMonthAgo = now - (30 * 24 * 60 * 60 * 1000ULL);

        bool hasChanges = false;

        for (auto& pair : workingData_->wordMap) {
            auto& entry = pair.second;
            if (entry->lastUsed < oneMonthAgo) {
                if (entry->frequency > 1) {
                    entry->frequency--;
                    workingData_->totalFrequency--;
                    hasChanges = true;
                }
            }
        }

        if (hasChanges) {
            workingData_->dirty = true;

            {
                std::lock_guard<std::mutex> statsLock(statsMutex_);
                performanceStats_.writeOperationCount++;
            }

            requestSnapshotUpdate();
        }
    }

// ========== 内存管理 ==========
    bool KazakhUserDict::flushToDisk() {
        std::shared_lock<std::shared_mutex> lock(workingDataMutex_);
        return workingData_->dirty;
    }

    bool KazakhUserDict::isDirty() const {
        std::shared_lock<std::shared_mutex> lock(workingDataMutex_);
        return workingData_->dirty;
    }

// ========== 文件操作 ==========
    bool KazakhUserDict::saveWorkingDataToFile(const std::string& filepath) {
        std::ofstream file(filepath, std::ios::binary);
        if (!file.is_open()) {
            LOGE("Failed to open file for writing: %s", filepath.c_str());
            return false;
        }

        try {
            uint32_t version = FILE_FORMAT_VERSION;
            file.write(reinterpret_cast<const char*>(&version), sizeof(version));

            uint32_t count = static_cast<uint32_t>(workingData_->wordMap.size());
            file.write(reinterpret_cast<const char*>(&count), sizeof(count));

            for (const auto& pair : workingData_->wordMap) {
                const auto& entry = pair.second;

                uint32_t wordLen = static_cast<uint32_t>(entry->word.size());
                file.write(reinterpret_cast<const char*>(&wordLen), sizeof(wordLen));
                file.write(entry->word.c_str(), wordLen);

                uint32_t normLen = static_cast<uint32_t>(entry->normalizedWord.size());
                file.write(reinterpret_cast<const char*>(&normLen), sizeof(normLen));
                file.write(entry->normalizedWord.c_str(), normLen);

                int32_t freq = entry->frequency;
                file.write(reinterpret_cast<const char*>(&freq), sizeof(freq));

                file.write(reinterpret_cast<const char*>(&entry->created), sizeof(entry->created));
                file.write(reinterpret_cast<const char*>(&entry->lastUsed), sizeof(entry->lastUsed));

                uint32_t contextCount = static_cast<uint32_t>(entry->contexts.size());
                file.write(reinterpret_cast<const char*>(&contextCount), sizeof(contextCount));

                for (const auto& context : entry->contexts) {
                    uint32_t ctxLen = static_cast<uint32_t>(context.size());
                    file.write(reinterpret_cast<const char*>(&ctxLen), sizeof(ctxLen));
                    file.write(context.c_str(), ctxLen);
                }
            }

            file.close();
            workingData_->dirty = false;

            LOGD("Saved user dictionary to %s (%d entries)", filepath.c_str(), count);
            return true;

        } catch (const std::exception& e) {
            LOGE("Error saving user dictionary: %s", e.what());
            file.close();
            return false;
        }
    }

// ========== 文件加载（完整实现） ==========
    bool KazakhUserDict::loadWorkingDataFromFile(const std::string& filepath) {
        LOGD("loadWorkingDataFromFile: Attempting to load from: %s", filepath.c_str());

        // 检查文件是否存在
        struct stat file_stat;
        if (stat(filepath.c_str(), &file_stat) != 0) {
            LOGD("loadWorkingDataFromFile: File does not exist, creating empty dictionary");
            workingData_ = std::make_unique<WorkingData>();
            workingData_->trieRoot = std::make_shared<SnapshotTrieNode>();
            return true;
        }

        LOGD("loadWorkingDataFromFile: File exists, size: %lld bytes", (long long)file_stat.st_size);

        // 如果文件存在但为空，创建空词典
        if (file_stat.st_size == 0) {
            LOGD("loadWorkingDataFromFile: File is empty, creating empty dictionary");
            workingData_ = std::make_unique<WorkingData>();
            workingData_->trieRoot = std::make_shared<SnapshotTrieNode>();
            return true;
        }

        std::ifstream file(filepath, std::ios::binary);
        if (!file.is_open()) {
            LOGE("loadWorkingDataFromFile: Failed to open file for reading: %s", filepath.c_str());
            return false;
        }

        // 创建新的工作数据对象
        auto newWorkingData = std::make_unique<WorkingData>();
        newWorkingData->trieRoot = std::make_shared<SnapshotTrieNode>();

        try {
            // 读取文件头
            uint32_t version = 0;
            file.read(reinterpret_cast<char*>(&version), sizeof(version));

            LOGD("loadWorkingDataFromFile: File format version: %u", version);

            // 检查版本兼容性
            if (version != FILE_FORMAT_VERSION) {
                LOGW("loadWorkingDataFromFile: Version mismatch (%u != %u), creating empty dictionary",
                     version, FILE_FORMAT_VERSION);
                file.close();

                // 创建空词典
                workingData_ = std::move(newWorkingData);
                return true;
            }

            // 读取词条数量
            uint32_t count = 0;
            file.read(reinterpret_cast<char*>(&count), sizeof(count));

            LOGD("loadWorkingDataFromFile: Loading %u entries", count);

            // 读取每个词条
            for (uint32_t i = 0; i < count; i++) {
                // 读取单词长度
                uint32_t wordLen = 0;
                if (!file.read(reinterpret_cast<char*>(&wordLen), sizeof(wordLen))) {
                    LOGE("loadWorkingDataFromFile: Failed to read word length at entry %u", i);
                    break;
                }

                // 读取单词内容
                std::string word(wordLen, '\0');
                if (!file.read(&word[0], wordLen)) {
                    LOGE("loadWorkingDataFromFile: Failed to read word at entry %u", i);
                    break;
                }

                // 读取规范化单词长度
                uint32_t normLen = 0;
                if (!file.read(reinterpret_cast<char*>(&normLen), sizeof(normLen))) {
                    LOGE("loadWorkingDataFromFile: Failed to read normalized word length at entry %u", i);
                    break;
                }

                // 读取规范化单词内容
                std::string normalizedWord(normLen, '\0');
                if (!file.read(&normalizedWord[0], normLen)) {
                    LOGE("loadWorkingDataFromFile: Failed to read normalized word at entry %u", i);
                    break;
                }

                // 读取频率
                int32_t freq = 0;
                if (!file.read(reinterpret_cast<char*>(&freq), sizeof(freq))) {
                    LOGE("loadWorkingDataFromFile: Failed to read frequency at entry %u", i);
                    break;
                }

                // 读取创建时间戳
                uint64_t created = 0;
                if (!file.read(reinterpret_cast<char*>(&created), sizeof(created))) {
                    LOGE("loadWorkingDataFromFile: Failed to read created timestamp at entry %u", i);
                    break;
                }

                // 读取最后使用时间戳
                uint64_t lastUsed = 0;
                if (!file.read(reinterpret_cast<char*>(&lastUsed), sizeof(lastUsed))) {
                    LOGE("loadWorkingDataFromFile: Failed to read lastUsed timestamp at entry %u", i);
                    break;
                }

                // 创建词条对象
                auto entry = std::make_shared<UserDictEntry>(word, normalizedWord, freq);
                entry->created = created;
                entry->lastUsed = lastUsed;

                // 添加到单词映射
                newWorkingData->wordMap[word] = entry;

                // 添加到规范化单词映射
                newWorkingData->normalizedWordMap[normalizedWord] = entry;

                // 更新统计
                newWorkingData->wordCount++;
                newWorkingData->totalFrequency += freq;

                // 将单词添加到Trie树
                updateWorkingDataTrie(word, normalizedWord, entry, true);

                // 读取上下文数量
                uint32_t contextCount = 0;
                if (!file.read(reinterpret_cast<char*>(&contextCount), sizeof(contextCount))) {
                    LOGE("loadWorkingDataFromFile: Failed to read context count at entry %u", i);
                    break;
                }

                // 读取每个上下文
                for (uint32_t j = 0; j < contextCount; j++) {
                    // 读取上下文长度
                    uint32_t ctxLen = 0;
                    if (!file.read(reinterpret_cast<char*>(&ctxLen), sizeof(ctxLen))) {
                        LOGE("loadWorkingDataFromFile: Failed to read context length at entry %u, context %u", i, j);
                        break;
                    }

                    // 读取上下文内容
                    std::string context(ctxLen, '\0');
                    if (!file.read(&context[0], ctxLen)) {
                        LOGE("loadWorkingDataFromFile: Failed to read context at entry %u, context %u", i, j);
                        break;
                    }

                    // 添加上下文（已经是规范化形式）
                    entry->contexts.push_back(context);

                    // 添加上下文映射
                    newWorkingData->contextMap[context].push_back(entry);
                }
            }

            // 关闭文件
            file.close();

            // 标记数据为干净（刚加载，尚未修改）
            newWorkingData->dirty = false;

            // 用新加载的数据替换当前工作数据
            workingData_ = std::move(newWorkingData);

            LOGD("loadWorkingDataFromFile: Successfully loaded %u entries, total words: %d",
                 count, workingData_->wordCount);
            return true;

        } catch (const std::exception& e) {
            LOGE("loadWorkingDataFromFile: Exception: %s", e.what());
            file.close();

            // 异常时创建空词典
            workingData_ = std::make_unique<WorkingData>();
            workingData_->trieRoot = std::make_shared<SnapshotTrieNode>();

            LOGD("loadWorkingDataFromFile: Exception occurred, created empty dictionary");
            return true;
        }
    }

} // namespace kazakh_ime