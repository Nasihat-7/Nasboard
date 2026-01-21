#ifndef MARISA_KAZAKH_CONTEXT_PREDICTOR_H
#define MARISA_KAZAKH_CONTEXT_PREDICTOR_H

#include <string>
#include <vector>
#include <memory>
#include <functional>

namespace marisa {

    class KazakhContextPredictor {
    public:
        KazakhContextPredictor();
        ~KazakhContextPredictor();

        // 禁止复制
        KazakhContextPredictor(const KazakhContextPredictor&) = delete;
        KazakhContextPredictor& operator=(const KazakhContextPredictor&) = delete;

        // 允许移动
        KazakhContextPredictor(KazakhContextPredictor&&) = default;
        KazakhContextPredictor& operator=(KazakhContextPredictor&&) = default;

        // 加载词典
        bool loadUnigramFromFile(const char* filename);
        bool loadBigramFromFile(const char* filename);

        // 搜索功能
        std::vector<std::string> prefixSearch(const std::string& prefix, int maxResults = 20);
        std::vector<std::string> contextPredict(const std::string& previousWord, const std::string& currentPrefix, int maxResults = 15);
        std::vector<std::string> pureContextPredict(const std::string& previousWord, int maxResults = 10);
        bool exactMatch(const std::string& word);

        // 拼写纠正功能
        std::vector<std::string> spellCorrect(const std::string& input, int maxResults = 10);

        // 智能预测（包含拼写纠正）
        std::vector<std::string> smartPredict(const std::string& prefix, int maxResults = 15);

        // 处理词条提交
        void processWordSubmission(const std::string& word);

        // 信息获取
        std::string getInfo() const;
        bool isUnigramLoaded() const;
        bool isBigramLoaded() const;

        // 清理
        void clear();

        // ==================== 新增：分级预测接口 ====================
        // Stage 1: 快速预测 (<5ms)
        std::vector<std::string> fastPredict(const std::string& prefix, int maxResults = 10);

        // Stage 3: 异步完整拼写纠正
        void heavySpellCorrectAsync(const std::string& input,
                                    std::function<void(std::vector<std::string>)> callback);

    private:
        class Impl;
        std::unique_ptr<Impl> impl_;
    };

} // namespace marisa

#endif // MARISA_KAZAKH_CONTEXT_PREDICTOR_H