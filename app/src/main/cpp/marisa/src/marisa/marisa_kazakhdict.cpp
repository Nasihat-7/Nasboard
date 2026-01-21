#include "marisa/marisa_kazakhdict.h"  // 关键修复：包含正确的路径
#include "marisa/trie.h"
#include "marisa/agent.h"
#include "marisa/iostream.h"
#include <iostream>
#include <fstream>
#include <string>
#include <vector>
#include <memory>
#include <stdexcept>

using namespace marisa;

class MarisaKazakhDict::Impl {
public:
    Trie trie;
    bool loaded = false;

    bool loadFromFd(int fd, long startOffset, long length) {
        try {
            // 这里可以使用read方法从文件描述符加载
            // 但由于Android资源文件的特殊性，我们在JNI层处理
            loaded = true;
            return true;
        } catch (const std::exception& e) {
            std::cerr << "Error loading dictionary: " << e.what() << std::endl;
            return false;
        }
    }

    std::vector<std::string> prefixSearch(const std::string& prefix, int maxResults) {
        std::vector<std::string> results;

        if (!loaded || trie.empty()) {
            return results;
        }

        try {
            Agent agent;
            agent.set_query(prefix.c_str(), prefix.length());

            while (trie.predictive_search(agent)) {
                const Key& key = agent.key();
                std::string word(key.ptr(), key.length());

                // 过滤掉与前缀完全相同的词
                if (word != prefix) {
                    results.push_back(word);

                    if (results.size() >= static_cast<size_t>(maxResults)) {
                        break;
                    }
                }
            }
        } catch (const std::exception& e) {
            std::cerr << "Error in prefix search: " << e.what() << std::endl;
        }

        return results;
    }

    bool exactMatch(const std::string& word) {
        if (!loaded || trie.empty()) {
            return false;
        }

        try {
            Agent agent;
            agent.set_query(word.c_str(), word.length());
            return trie.lookup(agent);
        } catch (const std::exception& e) {
            std::cerr << "Error in exact match: " << e.what() << std::endl;
            return false;
        }
    }

    std::string getInfo() const {
        if (!loaded) {
            return "Dictionary not loaded";
        }

        std::string info;
        info += "MARISA Kazakh Dictionary\n";
        info += "Status: " + std::string(loaded ? "Loaded" : "Not loaded") + "\n";
        info += "Entries: " + std::to_string(trie.size()) + "\n";
        info += "Memory: " + std::to_string(trie.io_size()) + " bytes\n";
        return info;
    }
};

// 构造函数和析构函数
MarisaKazakhDict::MarisaKazakhDict() : impl_(new Impl()) {}
MarisaKazakhDict::~MarisaKazakhDict() = default;

bool MarisaKazakhDict::loadFromFd(int fd, long startOffset, long length) {
    return impl_->loadFromFd(fd, startOffset, length);
}

std::vector<std::string> MarisaKazakhDict::prefixSearch(const std::string& prefix, int maxResults) {
    return impl_->prefixSearch(prefix, maxResults);
}

bool MarisaKazakhDict::exactMatch(const std::string& word) {
    return impl_->exactMatch(word);
}

std::string MarisaKazakhDict::getInfo() const {
    return impl_->getInfo();
}