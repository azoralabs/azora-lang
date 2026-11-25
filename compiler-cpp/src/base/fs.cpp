#include "base/fs.h"

#include "base/failure.h"

#include <cerrno>
#include <cstring>
#include <cstdlib>
#include <ctime>
#include <dirent.h>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace azora {

std::optional<std::string> readFile(const std::string& path) {
    int fd = ::open(path.c_str(), O_RDONLY);
    if (fd < 0) return std::nullopt;
    std::string out;
    struct stat info;
    if (::fstat(fd, &info) == 0 && info.st_size > 0) out.reserve((size_t)info.st_size);
    char buffer[1 << 16];
    while (true) {
        ssize_t n = ::read(fd, buffer, sizeof buffer);
        if (n < 0) {
            if (errno == EINTR) continue;
            ::close(fd);
            return std::nullopt;
        }
        if (n == 0) break;
        out.append(buffer, (size_t)n);
    }
    ::close(fd);
    return out;
}

void makeDirectories(const std::string& path) {
    if (path.empty() || isDirectory(path)) return;
    size_t slash = path.find_last_of('/');
    if (slash != std::string::npos && slash > 0) makeDirectories(path.substr(0, slash));
    if (::mkdir(path.c_str(), 0755) != 0 && errno != EEXIST) {
        fail("cannot create directory '" + path + "': " + std::strerror(errno));
    }
}

void writeFile(const std::string& path, const std::string& bytes) {
    size_t slash = path.find_last_of('/');
    if (slash != std::string::npos && slash > 0) makeDirectories(path.substr(0, slash));
    int fd = ::open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) fail("cannot write '" + path + "': " + std::strerror(errno));
    size_t written = 0;
    while (written < bytes.size()) {
        ssize_t n = ::write(fd, bytes.data() + written, bytes.size() - written);
        if (n < 0) {
            if (errno == EINTR) continue;
            ::close(fd);
            fail("cannot write '" + path + "': " + std::strerror(errno));
        }
        written += (size_t)n;
    }
    ::close(fd);
}

bool isDirectory(const std::string& path) {
    struct stat info;
    return ::stat(path.c_str(), &info) == 0 && S_ISDIR(info.st_mode);
}

bool isFile(const std::string& path) {
    struct stat info;
    return ::stat(path.c_str(), &info) == 0 && S_ISREG(info.st_mode);
}

std::vector<std::string> listDirectory(const std::string& path) {
    std::vector<std::string> out;
    DIR* dir = ::opendir(path.c_str());
    if (dir == nullptr) return out;
    while (dirent* entry = ::readdir(dir)) {
        if (std::strcmp(entry->d_name, ".") == 0 || std::strcmp(entry->d_name, "..") == 0) continue;
        out.emplace_back(entry->d_name);
    }
    ::closedir(dir);
    return out;
}

bool pathExists(const std::string& path) {
    struct stat info;
    return ::stat(path.c_str(), &info) == 0;
}

std::string currentDirectory() {
    char buffer[4096];
    if (::getcwd(buffer, sizeof buffer) == nullptr) return ".";
    return buffer;
}

std::optional<std::string> envVar(const std::string& name) {
    const char* value = ::getenv(name.c_str());
    if (value == nullptr) return std::nullopt;
    return std::string(value);
}

double nowSeconds() {
    timespec ts;
    ::clock_gettime(CLOCK_MONOTONIC, &ts);
    return (double)ts.tv_sec + (double)ts.tv_nsec * 1e-9;
}

}  // namespace azora
