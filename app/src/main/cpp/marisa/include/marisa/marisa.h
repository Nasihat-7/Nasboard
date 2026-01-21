#ifndef MARISA_MARISA_H_
#define MARISA_MARISA_H_

// 简化的marisa头文件，只包含我们需要的内容
#include <cstddef>
#include <memory>
#include <string>
#include <vector>

namespace marisa {

// 前向声明
    class Trie;
    class Agent;
    class Key;
    class Keyset;
    class Query;

// Trie类
    class Trie {
    public:
        Trie();
        ~Trie();

        void mmap(const char *filename);
        void map(const void *ptr, std::size_t size);
        bool lookup(Agent &agent) const;
        bool predictive_search(Agent &agent) const;

        std::size_t num_keys() const;
        std::size_t num_nodes() const;
        std::size_t total_size() const;

    private:
        class Impl;
        std::unique_ptr<Impl> impl_;
    };

// Agent类
    class Agent {
    public:
        Agent();
        ~Agent();

        void set_query(const char *ptr, std::size_t length);
        const Key& key() const;

    private:
        class Impl;
        std::unique_ptr<Impl> impl_;
    };

// Key类
    class Key {
    public:
        const char* ptr() const;
        std::size_t length() const;
        std::size_t id() const;
    };

}  // namespace marisa

#endif  // MARISA_MARISA_H_