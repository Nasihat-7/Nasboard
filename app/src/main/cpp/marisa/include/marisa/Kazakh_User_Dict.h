#ifndef KAZAKH_USER_DICT_H
#define KAZAKH_USER_DICT_H

#include <string>
#include <vector>
#include <memory>
#include <unordered_map>
#include <functional>
#include <atomic>
#include <mutex>
#include <shared_mutex>
#include <chrono>
#include <queue>
#include <unordered_set>
#include <optional>
#include <thread>
#include <condition_variable>
#include <cstdint>

namespace kazakh_ime {

// 哈萨克文用户词典类
    class KazakhUserDict {
    public:
        static KazakhUserDict& getInstance();

        // 停止后台线程（需要在程序退出前调用）
        void shutdown();

        // ========== 词典操作 ==========
        bool loadUserDict(const std::string& filepath);
        bool saveUserDict(const std::string& filepath);
        bool clearUserDict();

        // ========== 词条操作 ==========
        bool addWord(const std::string& word, int frequency = 1);
        bool addWordWithContext(const std::string& word,
                                const std::string& contextWord,
                                int frequency = 1);
        bool removeWord(const std::string& word);
        bool updateWordFrequency(const std::string& word, int delta);

        // ========== 搜索操作 ==========
        std::vector<std::string> searchPrefix(const std::string& prefix,
                                              int maxResults = 20);
        std::vector<std::string> searchWithContext(const std::string& previousWord,
                                                   const std::string& currentPrefix,
                                                   int maxResults = 15);
        bool containsWord(const std::string& word);

        // ========== 批量操作 ==========
        bool importWords(const std::vector<std::string>& words);
        bool exportWords(const std::string& filepath);

        // ========== 统计信息 ==========
        int getWordCount() const;
        int getTotalFrequency() const;
        std::string getStats() const;

        // ========== 学习功能 ==========
        void learnFromInput(const std::string& word, const std::string& context = "");
        void decayOldEntries();

        // ========== 内存管理 ==========
        bool flushToDisk();
        bool isDirty() const;

        // ========== 性能监控 ==========
        struct PerformanceStats {
            size_t snapshotBuildCount = 0;
            size_t snapshotReadCount = 0;
            size_t writeOperationCount = 0;
            uint64_t lastSnapshotBuildTime = 0;
            size_t pendingSnapshotUpdates = 0;
            size_t mergedSnapshotUpdates = 0;
            size_t debouncedSnapshotUpdates = 0;
            size_t utf8ToUtf16Calls = 0;
            size_t utf16ToUtf8Calls = 0;
        };

        PerformanceStats getPerformanceStats() const;

    private:
        KazakhUserDict();
        ~KazakhUserDict();

        // 禁止复制
        KazakhUserDict(const KazakhUserDict&) = delete;
        KazakhUserDict& operator=(const KazakhUserDict&) = delete;

        // ========== 数据结构 ==========
        struct UserDictEntry {
            std::string word;
            std::string normalizedWord;
            int frequency;
            std::vector<std::string> contexts;
            uint64_t lastUsed;
            uint64_t created;

            UserDictEntry(const std::string& w, const std::string& normW, int freq = 1)
                    : word(w), normalizedWord(normW), frequency(freq), lastUsed(0), created(0) {}
        };

        // Snapshot中的Trie节点
        struct SnapshotTrieNode {
            std::unordered_map<char16_t, std::shared_ptr<SnapshotTrieNode>> children;
            std::vector<std::shared_ptr<UserDictEntry>> entries;
            bool isEndOfWord = false;
        };

        // 完整的只读快照
        struct Snapshot {
            std::shared_ptr<SnapshotTrieNode> trieRoot;
            std::unordered_map<std::string, std::shared_ptr<UserDictEntry>> wordMap;
            std::unordered_map<std::string, std::shared_ptr<UserDictEntry>> normalizedWordMap;
            std::unordered_map<std::string, std::vector<std::shared_ptr<UserDictEntry>>> contextMap;

            std::unordered_map<std::string, std::vector<std::shared_ptr<UserDictEntry>>> prefixMap;

            int wordCount = 0;
            int totalFrequency = 0;

            uint64_t timestamp = 0;
            size_t version = 0;

            std::string buildInfo() const {
                return "Snapshot v" + std::to_string(version) +
                       " (words=" + std::to_string(wordCount) +
                       ", time=" + std::to_string(timestamp) + ")";
            }
        };

        // 工作数据结构
        struct WorkingData {
            std::shared_ptr<SnapshotTrieNode> trieRoot;
            std::unordered_map<std::string, std::shared_ptr<UserDictEntry>> wordMap;
            std::unordered_map<std::string, std::shared_ptr<UserDictEntry>> normalizedWordMap;
            std::unordered_map<std::string, std::vector<std::shared_ptr<UserDictEntry>>> contextMap;

            int wordCount = 0;
            int totalFrequency = 0;
            bool dirty = false;
        };

        // ========== 成员变量 ==========
        std::unique_ptr<WorkingData> workingData_;
        mutable std::shared_mutex workingDataMutex_;

        // 修复：使用指针+原子标记，避免原子shared_ptr的问题
        std::shared_ptr<Snapshot> currentSnapshot_;
        mutable std::mutex snapshotPtrMutex_;

        std::atomic<bool> shutdownFlag_{false};
        std::atomic<bool> snapshotDirty_{false};
        std::atomic<size_t> snapshotVersion_{0};
        std::atomic<size_t> pendingUpdateCount_{0};
        std::mutex snapshotMutex_;
        std::condition_variable snapshotCV_;
        std::thread snapshotThread_;

        mutable std::mutex statsMutex_;
        mutable PerformanceStats performanceStats_;

        static const uint32_t FILE_FORMAT_VERSION = 3;

        // ========== 私有方法 ==========
        // UTF转换函数
        bool utf8ToUtf16Safe(const std::string& utf8, std::u16string& utf16) const;
        bool utf16ToUtf8Safe(const std::u16string& utf16, std::string& utf8) const;
        std::u16string utf8ToUtf16(const std::string& str) const;
        std::string utf16ToUtf8(const std::u16string& str) const;

        // 时间函数
        uint64_t getCurrentTimestamp();

        // 工作数据操作
        bool addWordToWorkingData(const std::string& word, int frequency,
                                  bool checkExists = true);
        bool addWordWithContextToWorkingData(const std::string& word,
                                             const std::string& contextWord,
                                             int frequency);
        bool removeWordFromWorkingData(const std::string& word);
        void updateWorkingDataTrie(const std::string& word,
                                   const std::string& normalizedWord,
                                   std::shared_ptr<UserDictEntry> entry,
                                   bool add);
        std::shared_ptr<SnapshotTrieNode> findOrCreateNode(
                std::shared_ptr<SnapshotTrieNode> root,
                const std::u16string& path);

        // 规范化
        char16_t normalizeChar(char16_t ch) const;
        std::u16string normalizeString(const std::string& str) const;
        std::string normalizeAndConvertToString(const std::string& str) const;

        // 快照构建
        std::shared_ptr<Snapshot> buildSnapshotFromWorkingData();
        std::shared_ptr<SnapshotTrieNode> cloneTrieNode(
                const std::shared_ptr<SnapshotTrieNode>& src);
        void populatePrefixMap(Snapshot& snapshot);

        // 搜索辅助
        std::vector<std::shared_ptr<UserDictEntry>>
        searchPrefixInSnapshot(const std::shared_ptr<Snapshot>& snapshot,
                               const std::string& normalizedPrefix,
                               int maxResults);

        std::vector<std::shared_ptr<UserDictEntry>>
        searchWithContextInSnapshot(const std::shared_ptr<Snapshot>& snapshot,
                                    const std::string& normalizedPreviousWord,
                                    const std::string& normalizedCurrentPrefix,
                                    int maxResults);

        // 文件操作
        bool saveWorkingDataToFile(const std::string& filepath);
        bool loadWorkingDataFromFile(const std::string& filepath);

        // 快照后台线程
        void snapshotWorkerThread();
        void requestSnapshotUpdate();
        void updateSnapshotInternal();

        // 快照访问辅助函数
        std::shared_ptr<Snapshot> getCurrentSnapshot() const;
        void setCurrentSnapshot(const std::shared_ptr<Snapshot>& snapshot);
    };

} // namespace kazakh_ime

#endif // KAZAKH_USER_DICT_H