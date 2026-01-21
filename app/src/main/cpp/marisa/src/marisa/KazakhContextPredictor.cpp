#include "marisa/KazakhContextPredictor.h"
#include "marisa/trie.h"
#include "marisa/agent.h"
#include "marisa/iostream.h"
#include <iostream>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>
#include <map>
#include <algorithm>
#include <memory>
#include <stdexcept>
#include <cstring>
#include <unordered_map>
#include <queue>
#include <set>
#include <cmath>
#include <locale>
#include <codecvt>
#include <unordered_set>
#include <chrono>
#include <mutex>
#include <atomic>
#include <thread>
#include <list>
#include <future>
#include <string_view>

namespace marisa {

    // 线程池实现（简单版）
    class ThreadPool {
    private:
        std::vector<std::thread> workers;
        std::queue<std::function<void()>> tasks;
        std::mutex queueMutex;
        std::condition_variable condition;
        std::atomic<bool> stop;

    public:
        ThreadPool(size_t threads = 2) : stop(false) {
            for (size_t i = 0; i < threads; ++i) {
                workers.emplace_back([this] {
                    while (true) {
                        std::function<void()> task;
                        {
                            std::unique_lock<std::mutex> lock(queueMutex);
                            condition.wait(lock, [this] {
                                return stop.load() || !tasks.empty();
                            });

                            if (stop.load() && tasks.empty()) return;

                            task = std::move(tasks.front());
                            tasks.pop();
                        }

                        if (task) task();
                    }
                });
            }
        }

        ~ThreadPool() {
            {
                std::lock_guard<std::mutex> lock(queueMutex);
                stop.store(true);
            }
            condition.notify_all();
            for (std::thread &worker : workers) {
                if (worker.joinable()) worker.join();
            }
        }

        template<class F>
        void enqueue(F&& f) {
            {
                std::lock_guard<std::mutex> lock(queueMutex);
                if (stop.load()) return;
                tasks.emplace(std::forward<F>(f));
            }
            condition.notify_one();
        }
    };

    // LRU缓存实现（共享指针版）
    template<typename K, typename V>
    class LRUCache {
    private:
        struct Node {
            K key;
            V value;
            Node* prev;
            Node* next;
        };

        size_t capacity;
        std::unordered_map<K, Node*> cacheMap;
        Node* head;
        Node* tail;
        mutable std::mutex cacheMutex;

        void moveToHead(Node* node) {
            if (node == head) return;

            // 从当前位置移除
            if (node->prev) node->prev->next = node->next;
            if (node->next) node->next->prev = node->prev;

            // 如果是尾节点
            if (node == tail) {
                tail = node->prev;
            }

            // 移动到头部
            node->next = head;
            node->prev = nullptr;
            if (head) head->prev = node;
            head = node;

            // 如果这是第一个节点
            if (!tail) tail = head;
        }

        void removeTail() {
            if (!tail) return;

            cacheMap.erase(tail->key);

            if (tail->prev) {
                tail->prev->next = nullptr;
            } else {
                head = nullptr;
            }

            Node* old = tail;
            tail = tail->prev;
            delete old;
        }

    public:
        LRUCache(size_t cap = 1000) : capacity(cap), head(nullptr), tail(nullptr) {}

        ~LRUCache() {
            clear();
        }

        void clear() {
            std::lock_guard<std::mutex> lock(cacheMutex);
            while (head) {
                Node* next = head->next;
                delete head;
                head = next;
            }
            cacheMap.clear();
            head = tail = nullptr;
        }

        bool get(const K& key, V& value) {
            std::lock_guard<std::mutex> lock(cacheMutex);
            auto it = cacheMap.find(key);
            if (it == cacheMap.end()) {
                return false;
            }

            Node* node = it->second;
            value = node->value;
            moveToHead(node);
            return true;
        }

        void put(const K& key, const V& value) {
            std::lock_guard<std::mutex> lock(cacheMutex);

            auto it = cacheMap.find(key);
            if (it != cacheMap.end()) {
                Node* node = it->second;
                node->value = value;
                moveToHead(node);
                return;
            }

            Node* node = new Node{key, value, nullptr, head};
            if (head) head->prev = node;
            head = node;
            if (!tail) tail = head;

            cacheMap[key] = node;

            if (cacheMap.size() > capacity) {
                removeTail();
            }
        }

        size_t size() const {
            std::lock_guard<std::mutex> lock(cacheMutex);
            return cacheMap.size();
        }
    };

    // 批量Trie查找器
    class BatchTrieLookup {
    private:
        Trie* trie;
        std::unique_ptr<Agent> agent;
        std::mutex lookupMutex;

    public:
        BatchTrieLookup(Trie* t) : trie(t), agent(std::make_unique<Agent>()) {}

        bool exactMatch(const std::string_view& word) {
            std::lock_guard<std::mutex> lock(lookupMutex);
            agent->set_query(word.data(), word.length());
            return trie->lookup(*agent);
        }

        std::vector<std::string> prefixSearch(const std::string_view& prefix, int maxResults) {
            std::lock_guard<std::mutex> lock(lookupMutex);
            std::vector<std::string> results;

            if (!trie || trie->empty() || prefix.empty()) {
                return results;
            }

            agent->set_query(prefix.data(), prefix.length());

            int count = 0;
            while (trie->predictive_search(*agent)) {
                const Key& key = agent->key();
                std::string word(key.ptr(), key.length());

                if (word != prefix) {
                    results.push_back(word);
                    if (++count >= maxResults) break;
                }
            }

            return results;
        }
    };

    class KazakhContextPredictor::Impl {
    private:
        // UTF-32缓存
        LRUCache<std::string, std::vector<char32_t>> utf32Cache{5000};

        // 线程池
        std::unique_ptr<ThreadPool> threadPool;

        // Trie查找器
        std::unique_ptr<BatchTrieLookup> unigramLookup;
        std::unique_ptr<BatchTrieLookup> bigramLookup;

        // 线程安全控制
        mutable std::mutex predictMutex;
        std::atomic<bool> cancelHeavyTask{false};
        std::atomic<int> heavyTaskId{0};

        // 布隆过滤器模拟（快速排除不在词典中的词）
        std::unordered_set<std::string> fastRejectSet;
        const size_t MAX_FAST_REJECT_SIZE = 10000;

        // 快速编辑距离计算（优化版）
        static int calculateEditDistanceSimple(const std::vector<char32_t>& s1,
                                               const std::vector<char32_t>& s2,
                                               int maxDist = 3) {
            size_t n = s1.size();
            size_t m = s2.size();

            // 快速长度检查
            int lenDiff = std::abs(static_cast<int>(n) - static_cast<int>(m));
            if (lenDiff > maxDist) {
                return maxDist + 1;
            }

            // 对非常短的词使用快速检查
            if (n <= 3 && m <= 3) {
                return calculateEditDistanceTiny(s1, s2);
            }

            // 对短词使用完整DP
            if (n <= 6 && m <= 6) {
                return calculateEditDistanceFull(s1, s2);
            }

            // 对长词使用带剪枝的DP
            return calculateEditDistanceLimited(s1, s2, maxDist);
        }

        static int calculateEditDistanceTiny(const std::vector<char32_t>& s1,
                                             const std::vector<char32_t>& s2) {
            size_t n = s1.size();
            size_t m = s2.size();

            if (n == 0) return static_cast<int>(m);
            if (m == 0) return static_cast<int>(n);

            // 非常短的词直接计算
            if (n == 1 && m == 1) return (s1[0] == s2[0]) ? 0 : 1;

            // 简单比较
            if (n == m) {
                int diff = 0;
                for (size_t i = 0; i < n; i++) {
                    if (s1[i] != s2[i]) diff++;
                }
                return diff;
            }

            // 长度差1
            if (n + 1 == m) return isOneEditApart(s2, s1) ? 1 : 2;
            if (m + 1 == n) return isOneEditApart(s1, s2) ? 1 : 2;

            return 2; // 长度差大于1
        }

        static int calculateEditDistanceFull(const std::vector<char32_t>& s1,
                                             const std::vector<char32_t>& s2) {
            size_t len1 = s1.size();
            size_t len2 = s2.size();

            std::vector<std::vector<int>> dp(len1 + 1, std::vector<int>(len2 + 1, 0));

            for (size_t i = 0; i <= len1; i++) {
                dp[i][0] = static_cast<int>(i);
            }
            for (size_t j = 0; j <= len2; j++) {
                dp[0][j] = static_cast<int>(j);
            }

            for (size_t i = 1; i <= len1; i++) {
                for (size_t j = 1; j <= len2; j++) {
                    int cost = (s1[i-1] == s2[j-1]) ? 0 : 1;
                    dp[i][j] = std::min({
                                                dp[i-1][j] + 1,
                                                dp[i][j-1] + 1,
                                                dp[i-1][j-1] + cost
                                        });

                    if (i > 1 && j > 1 && s1[i-1] == s2[j-2] && s1[i-2] == s2[j-1]) {
                        dp[i][j] = std::min(dp[i][j], dp[i-2][j-2] + 1);
                    }
                }
            }

            return dp[len1][len2];
        }

        static int calculateEditDistanceLimited(const std::vector<char32_t>& s1,
                                                const std::vector<char32_t>& s2,
                                                int maxDist) {
            size_t n = s1.size();
            size_t m = s2.size();

            if (std::abs(static_cast<int>(n) - static_cast<int>(m)) > maxDist) {
                return maxDist + 1;
            }

            std::vector<int> prev(m + 1);
            std::vector<int> curr(m + 1);

            for (size_t j = 0; j <= m; j++) {
                prev[j] = static_cast<int>(j);
            }

            for (size_t i = 1; i <= n; i++) {
                curr[0] = static_cast<int>(i);
                int rowMin = curr[0];

                for (size_t j = 1; j <= m; j++) {
                    int cost = (s1[i-1] == s2[j-1]) ? 0 : 1;
                    curr[j] = std::min({
                                               prev[j] + 1,
                                               curr[j-1] + 1,
                                               prev[j-1] + cost
                                       });
                    rowMin = std::min(rowMin, curr[j]);
                }

                // 剪枝
                if (rowMin > maxDist) {
                    return maxDist + 1;
                }

                std::swap(prev, curr);
            }

            return prev[m];
        }

        // 快速判断编辑距离是否为1
        static bool isEditDistanceOne(const std::vector<char32_t>& s1,
                                      const std::vector<char32_t>& s2) {
            size_t n = s1.size();
            size_t m = s2.size();

            if (std::abs(static_cast<int>(n) - static_cast<int>(m)) > 1) {
                return false;
            }

            if (n == m) {
                // 检查是否只有一个字符不同
                int diffCount = 0;
                for (size_t i = 0; i < n; i++) {
                    if (s1[i] != s2[i]) {
                        diffCount++;
                        if (diffCount > 1) return false;
                    }
                }
                return diffCount == 1;
            } else if (n > m) {
                // s1比s2长1，检查是否删除一个字符
                return isOneEditApart(s1, s2);
            } else {
                // s2比s1长1，检查是否插入一个字符
                return isOneEditApart(s2, s1);
            }
        }

        static bool isOneEditApart(const std::vector<char32_t>& longer,
                                   const std::vector<char32_t>& shorter) {
            size_t i = 0, j = 0;
            bool foundDifference = false;

            while (i < longer.size() && j < shorter.size()) {
                if (longer[i] != shorter[j]) {
                    if (foundDifference) return false;
                    foundDifference = true;
                    i++;
                } else {
                    i++;
                    j++;
                }
            }

            return true;
        }

        // 批量检查单词是否在词典中
        std::vector<std::string> batchExactMatch(const std::vector<std::string>& candidates) {
            std::vector<std::string> validWords;
            validWords.reserve(candidates.size());

            // 使用线程池并行检查
            std::vector<std::future<bool>> futures;
            std::mutex resultsMutex;

            for (const auto& candidate : candidates) {
                futures.push_back(std::async(std::launch::async, [this, &candidate, &validWords, &resultsMutex]() {
                    // 快速拒绝：检查布隆过滤器
                    if (fastRejectSet.find(candidate) != fastRejectSet.end()) {
                        return false;
                    }

                    bool exists = unigramLookup->exactMatch(candidate);

                    if (!exists) {
                        // 添加到快速拒绝集
                        std::lock_guard<std::mutex> lock(resultsMutex);
                        if (fastRejectSet.size() < MAX_FAST_REJECT_SIZE) {
                            fastRejectSet.insert(candidate);
                        }
                    }

                    if (exists) {
                        std::lock_guard<std::mutex> lock(resultsMutex);
                        validWords.push_back(candidate);
                    }

                    return exists;
                }));
            }

            // 等待所有任务完成
            for (auto& future : futures) {
                future.wait();
            }

            return validWords;
        }

        // 标准化缓存键
        std::string makeCacheKey(const std::string& type, const std::string& key, int maxResults = 0) {
            if (maxResults > 0) {
                return type + ":" + key + ":" + std::to_string(maxResults);
            }
            return type + ":" + key;
        }

    public:
        // 单字词典
        Trie unigramTrie;
        bool unigramLoaded = false;

        // 双字词典
        Trie bigramTrie;
        bool bigramLoaded = false;

        // 结果缓存（不同大小）
        LRUCache<std::string, std::vector<std::string>> prefixCache{500};   // 前缀缓存
        LRUCache<std::string, std::vector<std::string>> spellCache{2000};   // 拼写缓存
        LRUCache<std::string, std::vector<std::string>> contextCache{3000}; // 上下文缓存

        // 最后处理的词
        std::string lastWord;

        // 哈萨克语语言学规则：音位等价类
        static const std::unordered_map<char32_t, std::vector<char32_t>> PHONETIC_CLASSES;

        // UTF-8解码和编码辅助函数
        static std::vector<char32_t> utf8_to_utf32(const std::string& utf8) {
            std::vector<char32_t> utf32;
            if (utf8.empty()) return utf32;

            size_t i = 0;
            while (i < utf8.size()) {
                unsigned char c = static_cast<unsigned char>(utf8[i]);

                if (c <= 0x7F) {
                    utf32.push_back(static_cast<char32_t>(c));
                    i += 1;
                } else if ((c & 0xE0) == 0xC0) {
                    if (i + 1 >= utf8.size()) break;
                    char32_t code = ((c & 0x1F) << 6) | (utf8[i+1] & 0x3F);
                    utf32.push_back(code);
                    i += 2;
                } else if ((c & 0xF0) == 0xE0) {
                    if (i + 2 >= utf8.size()) break;
                    char32_t code = ((c & 0x0F) << 12) | ((utf8[i+1] & 0x3F) << 6) | (utf8[i+2] & 0x3F);
                    utf32.push_back(code);
                    i += 3;
                } else if ((c & 0xF8) == 0xF0) {
                    if (i + 3 >= utf8.size()) break;
                    char32_t code = ((c & 0x07) << 18) | ((utf8[i+1] & 0x3F) << 12) |
                                    ((utf8[i+2] & 0x3F) << 6) | (utf8[i+3] & 0x3F);
                    utf32.push_back(code);
                    i += 4;
                } else {
                    i += 1;
                }
            }

            return utf32;
        }

        static std::string utf32_to_utf8(const std::vector<char32_t>& utf32) {
            std::string utf8;
            utf8.reserve(utf32.size() * 3);

            for (char32_t code : utf32) {
                if (code <= 0x7F) {
                    utf8.push_back(static_cast<char>(code));
                } else if (code <= 0x7FF) {
                    utf8.push_back(static_cast<char>(0xC0 | ((code >> 6) & 0x1F)));
                    utf8.push_back(static_cast<char>(0x80 | (code & 0x3F)));
                } else if (code <= 0xFFFF) {
                    utf8.push_back(static_cast<char>(0xE0 | ((code >> 12) & 0x0F)));
                    utf8.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
                    utf8.push_back(static_cast<char>(0x80 | (code & 0x3F)));
                } else if (code <= 0x10FFFF) {
                    utf8.push_back(static_cast<char>(0xF0 | ((code >> 18) & 0x07)));
                    utf8.push_back(static_cast<char>(0x80 | ((code >> 12) & 0x3F)));
                    utf8.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
                    utf8.push_back(static_cast<char>(0x80 | (code & 0x3F)));
                }
            }

            return utf8;
        }

        // 获取缓存的UTF-32表示（共享指针版）
        std::vector<char32_t> getUtf32Cached(const std::string& word) {
            std::vector<char32_t> result;
            if (!utf32Cache.get(word, result)) {
                result = utf8_to_utf32(word);
                utf32Cache.put(word, result);
            }
            return result;
        }

        // 预先转换为UTF-32
        std::vector<char32_t> preConvertToUtf32(const std::string& word) {
            return getUtf32Cached(word);
        }

        // 哈萨克语键盘邻近映射（优化版）
        static const std::unordered_map<char32_t, std::vector<char32_t>> KEYBOARD_NEIGHBORS;

        // 构造函数
        Impl() : threadPool(std::make_unique<ThreadPool>(2)) {}

        // 从文件加载unigram词典
        bool loadUnigramFromFile(const char* filename) {
            try {
                std::cout << "Loading unigram dictionary: " << filename << std::endl;

                std::lock_guard<std::mutex> lock(predictMutex);
                if (unigramLoaded) {
                    unigramTrie.clear();
                }

                unigramTrie.load(filename);
                unigramLoaded = true;

                // 创建查找器
                unigramLookup = std::make_unique<BatchTrieLookup>(&unigramTrie);

                std::cout << "Unigram dictionary loaded successfully" << std::endl;
                return true;
            } catch (const std::exception& e) {
                std::cerr << "Error loading unigram dictionary: " << e.what() << std::endl;
                return false;
            }
        }

        // 从文件加载bigram词典
        bool loadBigramFromFile(const char* filename) {
            try {
                std::cout << "Loading bigram dictionary: " << filename << std::endl;

                std::lock_guard<std::mutex> lock(predictMutex);
                if (bigramLoaded) {
                    bigramTrie.clear();
                }

                bigramTrie.load(filename);
                bigramLoaded = true;

                // 创建查找器
                bigramLookup = std::make_unique<BatchTrieLookup>(&bigramTrie);

                std::cout << "Bigram dictionary loaded successfully" << std::endl;
                return true;
            } catch (const std::exception& e) {
                std::cerr << "Error loading bigram dictionary: " << e.what() << std::endl;
                return false;
            }
        }

        // ==================== 分级预测系统 ====================

        // Stage 1: 快速前缀搜索（< 5ms）
        std::vector<std::string> fastPrefixSearch(const std::string& prefix, int maxResults) {
            auto start = std::chrono::steady_clock::now();
            std::vector<std::string> results;

            if (!unigramLoaded || unigramTrie.empty() || prefix.empty()) {
                return results;
            }

            // 检查缓存
            std::string cacheKey = makeCacheKey("prefix", prefix, maxResults);
            std::vector<std::string> cachedResults;
            if (prefixCache.get(cacheKey, cachedResults)) {
                auto end = std::chrono::steady_clock::now();
                auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
                std::cout << "Fast prefix cache hit: " << duration.count() << "µs" << std::endl;
                return cachedResults;
            }

            try {
                results = unigramLookup->prefixSearch(prefix, maxResults);

                // 缓存结果
                prefixCache.put(cacheKey, results);

                auto end = std::chrono::steady_clock::now();
                auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
                std::cout << "Fast prefix search: " << duration.count() << "µs, results: " << results.size() << std::endl;

            } catch (const std::exception& e) {
                std::cerr << "Error in fast prefix search: " << e.what() << std::endl;
            }

            return results;
        }

        // Stage 2: 键盘邻近纠错（< 15ms）
        std::vector<std::string> keyboardNeighborCorrect(const std::string& input, int maxResults) {
            auto start = std::chrono::steady_clock::now();
            std::vector<std::string> results;

            if (!unigramLoaded || input.empty() || input.length() > 10) {
                return results;
            }

            // 检查缓存
            std::string cacheKey = makeCacheKey("keyboard", input, maxResults);
            std::vector<std::string> cachedResults;
            if (spellCache.get(cacheKey, cachedResults)) {
                auto end = std::chrono::steady_clock::now();
                auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
                std::cout << "Keyboard cache hit: " << duration.count() << "µs" << std::endl;
                return cachedResults;
            }

            // 预先转换为UTF-32
            auto utf32 = preConvertToUtf32(input);

            std::unordered_set<std::string> candidateSet;
            std::vector<std::string> allCandidates;

            // 限制每个位置只生成最可能的2-3个候选
            const size_t MAX_CANDIDATES_PER_POSITION = 2;
            const size_t MAX_TOTAL_CANDIDATES = maxResults * 5;

            for (size_t i = 0; i < utf32.size() && candidateSet.size() < MAX_TOTAL_CANDIDATES; i++) {
                // 键盘邻近替换（只选最可能的2个）
                auto it = KEYBOARD_NEIGHBORS.find(utf32[i]);
                if (it != KEYBOARD_NEIGHBORS.end()) {
                    const auto& neighbors = it->second;
                    size_t limit = std::min(neighbors.size(), MAX_CANDIDATES_PER_POSITION);

                    for (size_t j = 0; j < limit; j++) {
                        if (candidateSet.size() >= MAX_TOTAL_CANDIDATES) break;

                        std::vector<char32_t> candidate_utf32 = utf32;
                        candidate_utf32[i] = neighbors[j];
                        std::string candidate = utf32_to_utf8(candidate_utf32);

                        if (candidateSet.insert(candidate).second) {
                            allCandidates.push_back(candidate);
                        }
                    }
                }

                // 删除一个字符（只对长度>1的词）
                if (utf32.size() > 1 && candidateSet.size() < MAX_TOTAL_CANDIDATES) {
                    std::vector<char32_t> candidate_utf32 = utf32;
                    candidate_utf32.erase(candidate_utf32.begin() + i);
                    std::string candidate = utf32_to_utf8(candidate_utf32);

                    if (candidateSet.insert(candidate).second) {
                        allCandidates.push_back(candidate);
                    }
                }
            }

            // 音位等价类替换（语言学规则）
            for (size_t i = 0; i < utf32.size() && candidateSet.size() < MAX_TOTAL_CANDIDATES; i++) {
                auto it = PHONETIC_CLASSES.find(utf32[i]);
                if (it != PHONETIC_CLASSES.end()) {
                    const auto& phonetics = it->second;
                    size_t limit = std::min(phonetics.size(), MAX_CANDIDATES_PER_POSITION);

                    for (size_t j = 0; j < limit; j++) {
                        if (candidateSet.size() >= MAX_TOTAL_CANDIDATES) break;

                        std::vector<char32_t> candidate_utf32 = utf32;
                        candidate_utf32[i] = phonetics[j];
                        std::string candidate = utf32_to_utf8(candidate_utf32);

                        if (candidateSet.insert(candidate).second) {
                            allCandidates.push_back(candidate);
                        }
                    }
                }
            }

            // 交换相邻字符（只对长度>1的词）
            for (size_t i = 0; i < utf32.size() - 1 && candidateSet.size() < MAX_TOTAL_CANDIDATES; i++) {
                std::vector<char32_t> candidate_utf32 = utf32;
                std::swap(candidate_utf32[i], candidate_utf32[i+1]);
                std::string candidate = utf32_to_utf8(candidate_utf32);

                if (candidateSet.insert(candidate).second) {
                    allCandidates.push_back(candidate);
                }
            }

            // 批量检查哪些候选词在词典中
            auto validCandidates = batchExactMatch(allCandidates);

            // 计算编辑距离并排序（使用部分排序）
            std::vector<std::pair<std::string, int>> scoredCandidates;
            scoredCandidates.reserve(validCandidates.size());

            auto inputUtf32 = utf32;

            for (const auto& candidate : validCandidates) {
                auto candUtf32 = getUtf32Cached(candidate);
                int distance = calculateEditDistanceSimple(inputUtf32, candUtf32, 2);
                if (distance <= 2) {
                    scoredCandidates.emplace_back(candidate, distance);
                }
            }

            // 部分排序：只排序前maxResults个
            std::partial_sort(
                    scoredCandidates.begin(),
                    scoredCandidates.begin() + std::min(static_cast<size_t>(maxResults), scoredCandidates.size()),
                    scoredCandidates.end(),
                    [](const auto& a, const auto& b) { return a.second < b.second; }
            );

            // 提取结果
            for (size_t i = 0; i < std::min(static_cast<size_t>(maxResults), scoredCandidates.size()); i++) {
                results.push_back(scoredCandidates[i].first);
            }

            // 缓存结果
            spellCache.put(cacheKey, results);

            auto end = std::chrono::steady_clock::now();
            auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
            std::cout << "Keyboard neighbor correct: " << duration.count() << "µs, candidates: "
                      << allCandidates.size() << ", valid: " << validCandidates.size()
                      << ", results: " << results.size() << std::endl;

            return results;
        }

        // Stage 3: 完整编辑距离（异步）
        std::vector<std::string> heavySpellCorrect(const std::string& input, int maxResults, int taskId) {
            if (taskId != heavyTaskId.load()) {
                return {}; // 任务已被取消
            }

            auto start = std::chrono::steady_clock::now();
            std::vector<std::string> results;

            // 检查缓存
            std::string cacheKey = makeCacheKey("heavy", input, maxResults);
            std::vector<std::string> cachedResults;
            if (spellCache.get(cacheKey, cachedResults)) {
                return cachedResults;
            }

            // 预先转换为UTF-32
            auto utf32 = preConvertToUtf32(input);

            std::unordered_set<std::string> candidateSet;
            std::vector<std::string> allCandidates;

            // 限制候选生成数量
            const size_t MAX_TOTAL_CANDIDATES = maxResults * 10;

            // 生成更多候选（比Stage 2更多）
            for (size_t i = 0; i < utf32.size() && candidateSet.size() < MAX_TOTAL_CANDIDATES; i++) {
                if (taskId != heavyTaskId.load()) return {};

                // 键盘邻近替换（全部）
                auto it = KEYBOARD_NEIGHBORS.find(utf32[i]);
                if (it != KEYBOARD_NEIGHBORS.end()) {
                    for (char32_t neighbor : it->second) {
                        if (candidateSet.size() >= MAX_TOTAL_CANDIDATES) break;

                        std::vector<char32_t> candidate_utf32 = utf32;
                        candidate_utf32[i] = neighbor;
                        std::string candidate = utf32_to_utf8(candidate_utf32);

                        if (candidateSet.insert(candidate).second) {
                            allCandidates.push_back(candidate);
                        }
                    }
                }

                // 删除
                if (utf32.size() > 1 && candidateSet.size() < MAX_TOTAL_CANDIDATES) {
                    std::vector<char32_t> candidate_utf32 = utf32;
                    candidate_utf32.erase(candidate_utf32.begin() + i);
                    std::string candidate = utf32_to_utf8(candidate_utf32);

                    if (candidateSet.insert(candidate).second) {
                        allCandidates.push_back(candidate);
                    }
                }

                // 音位替换（全部）
                auto pit = PHONETIC_CLASSES.find(utf32[i]);
                if (pit != PHONETIC_CLASSES.end()) {
                    for (char32_t phonetic : pit->second) {
                        if (candidateSet.size() >= MAX_TOTAL_CANDIDATES) break;

                        std::vector<char32_t> candidate_utf32 = utf32;
                        candidate_utf32[i] = phonetic;
                        std::string candidate = utf32_to_utf8(candidate_utf32);

                        if (candidateSet.insert(candidate).second) {
                            allCandidates.push_back(candidate);
                        }
                    }
                }

                // 交换相邻字符
                if (i < utf32.size() - 1 && candidateSet.size() < MAX_TOTAL_CANDIDATES) {
                    std::vector<char32_t> candidate_utf32 = utf32;
                    std::swap(candidate_utf32[i], candidate_utf32[i+1]);
                    std::string candidate = utf32_to_utf8(candidate_utf32);

                    if (candidateSet.insert(candidate).second) {
                        allCandidates.push_back(candidate);
                    }
                }
            }

            // 批量检查候选词
            auto validCandidates = batchExactMatch(allCandidates);

            // 计算编辑距离并排序
            std::vector<std::pair<std::string, int>> scoredCandidates;
            scoredCandidates.reserve(validCandidates.size());

            for (const auto& candidate : validCandidates) {
                auto candUtf32 = getUtf32Cached(candidate);
                int distance = calculateEditDistanceSimple(utf32, candUtf32, 3);
                if (distance <= 3) {
                    scoredCandidates.emplace_back(candidate, distance);
                }
            }

            // 部分排序
            std::partial_sort(
                    scoredCandidates.begin(),
                    scoredCandidates.begin() + std::min(static_cast<size_t>(maxResults), scoredCandidates.size()),
                    scoredCandidates.end(),
                    [](const auto& a, const auto& b) { return a.second < b.second; }
            );

            // 提取结果
            for (size_t i = 0; i < std::min(static_cast<size_t>(maxResults), scoredCandidates.size()); i++) {
                results.push_back(scoredCandidates[i].first);
            }

            // 缓存结果
            spellCache.put(cacheKey, results);

            auto end = std::chrono::steady_clock::now();
            auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
            std::cout << "Heavy spell correct: " << duration.count() << "ms, candidates: "
                      << allCandidates.size() << ", valid: " << validCandidates.size()
                      << ", results: " << results.size() << std::endl;

            return results;
        }

        // ==================== 上下文预测（优化版） ====================

        std::vector<std::string> contextPredict(const std::string& previousWord, const std::string& currentPrefix, int maxResults) {
            auto start = std::chrono::steady_clock::now();

            // 构建缓存键
            std::string cacheKey = makeCacheKey("context", previousWord + "|" + currentPrefix, maxResults);

            // 检查缓存
            std::vector<std::string> results;
            std::vector<std::string> cachedResults;
            if (contextCache.get(cacheKey, cachedResults)) {
                auto end = std::chrono::steady_clock::now();
                auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
                std::cout << "Context cache hit: " << duration.count() << "µs" << std::endl;
                return cachedResults;
            }

            if (!bigramLoaded || bigramTrie.empty() || previousWord.empty()) {
                results = fastPrefixSearch(currentPrefix, maxResults);
                contextCache.put(cacheKey, results);
                return results;
            }

            try {
                // 构建搜索前缀
                std::string searchPrefix = previousWord + " " + currentPrefix;
                Agent agent;
                agent.set_query(searchPrefix.c_str(), searchPrefix.length());

                int count = 0;
                while (bigramTrie.predictive_search(agent) && count < maxResults * 2) {
                    const Key& key = agent.key();
                    std::string fullKey(key.ptr(), key.length());

                    size_t spacePos = fullKey.find(' ');
                    if (spacePos != std::string::npos) {
                        std::string secondWord = fullKey.substr(spacePos + 1);
                        results.push_back(secondWord);
                        count++;
                    }
                }

                // 限制结果数量
                if (results.size() > static_cast<size_t>(maxResults)) {
                    results.resize(maxResults);
                }

                // 补充前缀搜索结果
                if (results.size() < static_cast<size_t>(maxResults)) {
                    auto prefixResults = fastPrefixSearch(currentPrefix, maxResults - results.size());
                    for (const auto& word : prefixResults) {
                        if (std::find(results.begin(), results.end(), word) == results.end()) {
                            results.push_back(word);
                        }
                    }
                }

                // 缓存结果
                contextCache.put(cacheKey, results);

                auto end = std::chrono::steady_clock::now();
                auto duration = std::chrono::duration_cast<std::chrono::microseconds>(end - start);
                std::cout << "Context predict: " << duration.count() << "µs, results: " << results.size() << std::endl;

            } catch (const std::exception& e) {
                std::cerr << "Error in context predict: " << e.what() << std::endl;
                results = fastPrefixSearch(currentPrefix, maxResults);
            }

            return results;
        }

        std::vector<std::string> pureContextPredict(const std::string& previousWord, int maxResults) {
            std::vector<std::string> results;

            if (!bigramLoaded || bigramTrie.empty() || previousWord.empty()) {
                return results;
            }

            try {
                std::string searchPrefix = previousWord + " ";
                Agent agent;
                agent.set_query(searchPrefix.c_str(), searchPrefix.length());

                int count = 0;
                while (bigramTrie.predictive_search(agent) && count < maxResults) {
                    const Key& key = agent.key();
                    std::string fullKey(key.ptr(), key.length());

                    size_t spacePos = fullKey.find(' ');
                    if (spacePos != std::string::npos) {
                        std::string secondWord = fullKey.substr(spacePos + 1);
                        results.push_back(secondWord);
                        count++;
                    }
                }

            } catch (const std::exception& e) {
                std::cerr << "Error in pure context predict: " << e.what() << std::endl;
            }

            return results;
        }

        // ==================== 辅助函数 ====================

        bool exactMatch(const std::string& word) {
            return unigramLookup->exactMatch(word);
        }

        double getWordWeight(const std::string& word) {
            // 简单权重计算
            return 1.0 / (word.length() * 0.5 + 1);
        }

        // ==================== 旧接口兼容 ====================

        std::vector<std::string> prefixSearch(const std::string& prefix, int maxResults) {
            return fastPrefixSearch(prefix, maxResults);
        }

        std::vector<std::string> spellCorrect(const std::string& input, int maxResults) {
            return keyboardNeighborCorrect(input, maxResults);
        }

        std::vector<std::string> smartPredict(const std::string& prefix, int maxResults) {
            // 1. 精确匹配
            if (exactMatch(prefix)) {
                return {prefix};
            }

            // 2. 快速前缀搜索
            auto results = fastPrefixSearch(prefix, maxResults);

            // 3. 如果不足，添加键盘纠错
            if (results.size() < static_cast<size_t>(maxResults)) {
                auto corrections = keyboardNeighborCorrect(prefix, maxResults - results.size());
                for (const auto& word : corrections) {
                    if (std::find(results.begin(), results.end(), word) == results.end()) {
                        results.push_back(word);
                    }
                }
            }

            return results;
        }

        void processWordSubmission(const std::string& word) {
            lastWord = word;
            // 清理相关缓存
            // 简化处理：不实现具体遍历，因为LRU会自动清理
        }

        std::string getInfo() const {
            std::string info;
            info += "=== Kazakh Context Predictor Info ===\n";
            info += "Unigram status: " + std::string(unigramLoaded ? "Loaded" : "Not loaded") + "\n";
            if (unigramLoaded) {
                info += "  Keys: " + std::to_string(unigramTrie.num_keys()) + "\n";
            }
            info += "Bigram status: " + std::string(bigramLoaded ? "Loaded" : "Not loaded") + "\n";
            if (bigramLoaded) {
                info += "  Keys: " + std::to_string(bigramTrie.num_keys()) + "\n";
            }
            info += "Cache stats:\n";
            info += "  UTF-32 cache: " + std::to_string(utf32Cache.size()) + " entries\n";
            info += "  Prefix cache: " + std::to_string(prefixCache.size()) + " entries\n";
            info += "  Spell cache: " + std::to_string(spellCache.size()) + " entries\n";
            info += "  Context cache: " + std::to_string(contextCache.size()) + " entries\n";
            info += "  Fast reject set: " + std::to_string(fastRejectSet.size()) + " entries\n";
            info += "Performance: Multi-stage with LRU caching & thread pool\n";
            return info;
        }

        void clear() {
            std::lock_guard<std::mutex> lock(predictMutex);
            if (unigramLoaded) {
                unigramTrie.clear();
                unigramLoaded = false;
            }
            if (bigramLoaded) {
                bigramTrie.clear();
                bigramLoaded = false;
            }

            utf32Cache.clear();
            prefixCache.clear();
            spellCache.clear();
            contextCache.clear();
            fastRejectSet.clear();

            unigramLookup.reset();
            bigramLookup.reset();

            lastWord.clear();
            heavyTaskId++;
        }

        // ==================== 新增：分级预测接口 ====================

        std::vector<std::string> fastPredict(const std::string& prefix, int maxResults) {
            return fastPrefixSearch(prefix, maxResults);
        }

        void heavySpellCorrectAsync(const std::string& input,
                                    std::function<void(std::vector<std::string>)> callback) {
            // 取消之前的任务
            heavyTaskId++;
            int currentTaskId = heavyTaskId.load();

            // 使用线程池提交任务
            threadPool->enqueue([this, input, callback, currentTaskId]() {
                auto results = heavySpellCorrect(input, 10, currentTaskId);
                if (currentTaskId == heavyTaskId.load()) {
                    callback(results);
                }
            });
        }
    };

// 哈萨克语音位等价类
    const std::unordered_map<char32_t, std::vector<char32_t>> KazakhContextPredictor::Impl::PHONETIC_CLASSES = {
            // ә ↔ а
            {0x44D, {0x430}}, // ә → а
            {0x430, {0x44D}}, // а → ә
            // ң ↔ н
            {0x4A3, {0x43D}}, // ң → н
            {0x43D, {0x4A3}}, // н → ң
            // і ↔ и
            {0x456, {0x438}}, // і → и
            {0x438, {0x456}}, // и → і
            // қ ↔ к
            {0x49B, {0x43A}}, // қ → к
            {0x43A, {0x49B}}, // к → қ
            // ғ ↔ г
            {0x493, {0x433}}, // ғ → г
            {0x433, {0x493}}, // г → ғ
            // ү ↔ у
            {0x4AF, {0x443}}, // ү → у
            {0x443, {0x4AF}}, // у → ү
            // ө ↔ о
            {0x4E9, {0x43E}}, // ө → о
            {0x43E, {0x4E9}}, // о → ө
            // һ ↔ х
            {0x4BB, {0x445}}, // һ → х
            {0x445, {0x4BB}}, // х → һ
    };

// 键盘邻近映射（优化版）
    const std::unordered_map<char32_t, std::vector<char32_t>> KazakhContextPredictor::Impl::KEYBOARD_NEIGHBORS = {
            // 只保留最可能的邻近键
            {0x430, {0x444, 0x441}}, // а → ф, с
            {0x431, {0x438, 0x44E}}, // б → и, ю
            {0x432, {0x446, 0x444}}, // в → ц, ф
            {0x433, {0x440, 0x442}}, // г → р, т
            {0x493, {0x440, 0x442}}, // ғ → р, т
            {0x434, {0x43B, 0x448}}, // д → л, ш
            {0x435, {0x43A, 0x43D}}, // е → к, н
            {0x436, {0x44D, 0x437}}, // ж → э, з
            {0x437, {0x436, 0x44A}}, // з → ж, ъ
            {0x438, {0x448, 0x449}}, // и → ш, щ
            {0x439, {0x444, 0x44B}}, // й → ф, ы
            {0x43A, {0x43B, 0x435}}, // к → л, е
            {0x49B, {0x43B, 0x448}}, // қ → л, ш
            {0x43B, {0x434, 0x43A}}, // л → д, к
            {0x43C, {0x44C, 0x442}}, // м → ь, т
            {0x43D, {0x442, 0x435}}, // н → т, е
            {0x4A3, {0x442, 0x435}}, // ң → т, е
            {0x43E, {0x430, 0x43B}}, // о → а, л
            {0x4E9, {0x43B, 0x434}}, // ө → л, д
            {0x43F, {0x437, 0x44D}}, // п → з, э
            {0x440, {0x43A, 0x435}}, // р → к, е
            {0x441, {0x44B, 0x432}}, // с → ы, в
            {0x442, {0x43D, 0x43C}}, // т → н, м
            {0x443, {0x433, 0x448}}, // у → г, ш
            {0x4B1, {0x433, 0x448}}, // ұ → г, ш
            {0x4AF, {0x433, 0x448}}, // ү → г, ш
            {0x444, {0x430, 0x432}}, // ф → а, в
            {0x445, {0x44A, 0x437}}, // х → ъ, з
            {0x4BB, {0x44A, 0x437}}, // һ → ъ, з
            {0x446, {0x443, 0x43A}}, // ц → у, к
            {0x447, {0x441, 0x43C}}, // ч → с, м
            {0x448, {0x449, 0x438}}, // ш → щ, и
            {0x449, {0x448, 0x438}}, // щ → ш, и
            {0x44A, {0x44D, 0x436}}, // ъ → э, ж
            {0x44B, {0x444, 0x432}}, // ы → ф, в
            {0x456, {0x448, 0x449}}, // і → ш, щ
            {0x44C, {0x431, 0x44E}}, // ь → б, ю
            {0x44D, {0x44A, 0x436}}, // э → ъ, ж
            {0x44E, {0x46A, 0x431}}, // ю → ., б
            {0x44F, {0x444, 0x446}}  // я → ф, ц
    };

// ==================== 公共接口实现 ====================

    KazakhContextPredictor::KazakhContextPredictor() : impl_(new Impl()) {}
    KazakhContextPredictor::~KazakhContextPredictor() = default;

    bool KazakhContextPredictor::loadUnigramFromFile(const char* filename) {
        return impl_->loadUnigramFromFile(filename);
    }

    bool KazakhContextPredictor::loadBigramFromFile(const char* filename) {
        return impl_->loadBigramFromFile(filename);
    }

    std::vector<std::string> KazakhContextPredictor::prefixSearch(const std::string& prefix, int maxResults) {
        return impl_->prefixSearch(prefix, maxResults);
    }

    std::vector<std::string> KazakhContextPredictor::contextPredict(const std::string& previousWord, const std::string& currentPrefix, int maxResults) {
        return impl_->contextPredict(previousWord, currentPrefix, maxResults);
    }

    std::vector<std::string> KazakhContextPredictor::pureContextPredict(const std::string& previousWord, int maxResults) {
        return impl_->pureContextPredict(previousWord, maxResults);
    }

    bool KazakhContextPredictor::exactMatch(const std::string& word) {
        return impl_->exactMatch(word);
    }

    std::vector<std::string> KazakhContextPredictor::spellCorrect(const std::string& input, int maxResults) {
        return impl_->spellCorrect(input, maxResults);
    }

    std::vector<std::string> KazakhContextPredictor::smartPredict(const std::string& prefix, int maxResults) {
        return impl_->smartPredict(prefix, maxResults);
    }

    void KazakhContextPredictor::processWordSubmission(const std::string& word) {
        impl_->processWordSubmission(word);
    }

    std::string KazakhContextPredictor::getInfo() const {
        return impl_->getInfo();
    }

    void KazakhContextPredictor::clear() {
        impl_->clear();
    }

    bool KazakhContextPredictor::isUnigramLoaded() const {
        return impl_->unigramLoaded;
    }

    bool KazakhContextPredictor::isBigramLoaded() const {
        return impl_->bigramLoaded;
    }

// ==================== 新增：分级预测接口 ====================

    std::vector<std::string> KazakhContextPredictor::fastPredict(const std::string& prefix, int maxResults) {
        return impl_->fastPredict(prefix, maxResults);
    }

    void KazakhContextPredictor::heavySpellCorrectAsync(const std::string& input,
                                                        std::function<void(std::vector<std::string>)> callback) {
        impl_->heavySpellCorrectAsync(input, callback);
    }

} // namespace marisa