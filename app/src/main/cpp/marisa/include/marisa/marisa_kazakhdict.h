#ifndef MARISA_KAZAHDICT_H
#define MARISA_KAZAHDICT_H

#include <string>
#include <vector>
#include <memory>

class MarisaKazakhDict {
public:
    MarisaKazakhDict();
    ~MarisaKazakhDict();

    // 禁止复制
    MarisaKazakhDict(const MarisaKazakhDict&) = delete;
    MarisaKazakhDict& operator=(const MarisaKazakhDict&) = delete;

    // 允许移动
    MarisaKazakhDict(MarisaKazakhDict&&) = default;
    MarisaKazakhDict& operator=(MarisaKazakhDict&&) = default;

    // 核心功能接口
    bool loadFromFd(int fd, long startOffset, long length);
    std::vector<std::string> prefixSearch(const std::string& prefix, int maxResults = 20);
    bool exactMatch(const std::string& word);
    std::string getInfo() const;

private:
    class Impl;
    std::unique_ptr<Impl> impl_;
};

#endif // MARISA_KAZAHDICT_H