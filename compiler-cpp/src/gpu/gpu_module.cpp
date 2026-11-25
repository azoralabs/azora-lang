// Port of gpu/GpuModule.kt. Kotlin matches a regex anchored at the start:
//
//   \A trivia @Execution \s* ( \s* "GPU" \s* ) trivia module \s+ ([A-Za-z_]\w*(?:\.[A-Za-z_]\w*)*)
//   trivia = (?:[\s﻿] | //[^\r\n]*(?=[\r\n]|\z) | /\*(?:[^*]|\*(?!/))*\*/)*
//
// This is the same match written as a scanner. Java's \s is [ \t\n\x0B\f\r]
// and \w is [A-Za-z0-9_] (no UNICODE_CHARACTER_CLASS flag).
#include "gpu/gpu_module.h"

#include "base/text.h"

namespace azora {
namespace GpuModule {

namespace {

bool isSpace(char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\x0B' || c == '\f' || c == '\r'; }
bool isWordStart(char c) { return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_'; }
bool isWord(char c) { return isWordStart(c) || (c >= '0' && c <= '9'); }

/** Skips the trivia the regex allows; a block comment counts only when it is closed. */
size_t skipTrivia(const std::string& s, size_t i) {
    while (i < s.size()) {
        if (isSpace(s[i])) {
            i++;
        } else if (s.compare(i, 3, "\xEF\xBB\xBF") == 0) {
            i += 3;
        } else if (s.compare(i, 2, "//") == 0) {
            i += 2;
            while (i < s.size() && s[i] != '\r' && s[i] != '\n') i++;
        } else if (s.compare(i, 2, "/*") == 0) {
            size_t end = s.find("*/", i + 2);
            if (end == std::string::npos) return i;
            i = end + 2;
        } else {
            break;
        }
    }
    return i;
}

size_t skipSpaces(const std::string& s, size_t i) {
    while (i < s.size() && isSpace(s[i])) i++;
    return i;
}

bool expect(const std::string& s, size_t& i, const char* text) {
    std::string t = text;
    if (s.compare(i, t.size(), t) != 0) return false;
    i += t.size();
    return true;
}

}  // namespace

std::optional<std::string> moduleName(const std::string& source) {
    if (!contains(source, "@Execution")) return std::nullopt;
    size_t i = skipTrivia(source, 0);
    if (!expect(source, i, "@Execution")) return std::nullopt;
    i = skipSpaces(source, i);
    if (!expect(source, i, "(")) return std::nullopt;
    i = skipSpaces(source, i);
    if (!expect(source, i, "\"GPU\"")) return std::nullopt;
    i = skipSpaces(source, i);
    if (!expect(source, i, ")")) return std::nullopt;
    i = skipTrivia(source, i);
    if (!expect(source, i, "module")) return std::nullopt;
    size_t afterKeyword = i;
    i = skipSpaces(source, i);
    if (i == afterKeyword) return std::nullopt;
    size_t start = i;
    while (true) {
        if (i >= source.size() || !isWordStart(source[i])) return std::nullopt;
        while (i < source.size() && isWord(source[i])) i++;
        if (i + 1 < source.size() && source[i] == '.' && isWordStart(source[i + 1])) {
            i++;
            continue;
        }
        break;
    }
    return source.substr(start, i - start);
}

bool isGpuSource(const std::string& source) { return moduleName(source).has_value(); }

}  // namespace GpuModule
}  // namespace azora
