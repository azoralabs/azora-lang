#include "base/text.h"

#include "base/chars.h"

#include <charconv>

namespace azora {

// ---------------------------------------------------------------------------
// UTF-8 <-> UTF-16
// ---------------------------------------------------------------------------

static std::u16string decode(std::string_view bytes, bool generalized) {
    std::u16string out;
    out.reserve(bytes.size());
    size_t i = 0;
    size_t n = bytes.size();
    auto byte = [&](size_t k) -> uint32_t { return (uint8_t)bytes[k]; };
    auto cont = [&](size_t k) { return k < n && (byte(k) & 0xC0) == 0x80; };
    while (i < n) {
        uint32_t b0 = byte(i);
        if (b0 < 0x80) {
            out.push_back((char16_t)b0);
            i++;
            continue;
        }
        // Maximal-subpart replacement, as the JVM's decoder does.
        if (b0 >= 0xC2 && b0 <= 0xDF) {
            if (cont(i + 1)) {
                out.push_back((char16_t)(((b0 & 0x1F) << 6) | (byte(i + 1) & 0x3F)));
                i += 2;
            } else {
                out.push_back(0xFFFD);
                i += 1;
            }
            continue;
        }
        if (b0 >= 0xE0 && b0 <= 0xEF) {
            uint32_t lo = 0x80, hi = 0xBF;
            if (b0 == 0xE0) lo = 0xA0;
            if (b0 == 0xED && !generalized) hi = 0x9F;
            if (i + 1 < n && byte(i + 1) >= lo && byte(i + 1) <= hi) {
                if (cont(i + 2)) {
                    uint32_t cp = ((b0 & 0x0F) << 12) | ((byte(i + 1) & 0x3F) << 6) | (byte(i + 2) & 0x3F);
                    out.push_back((char16_t)cp);
                    i += 3;
                } else {
                    out.push_back(0xFFFD);
                    i += 2;
                }
            } else {
                out.push_back(0xFFFD);
                i += 1;
            }
            continue;
        }
        if (b0 >= 0xF0 && b0 <= 0xF4) {
            uint32_t lo = 0x80, hi = 0xBF;
            if (b0 == 0xF0) lo = 0x90;
            if (b0 == 0xF4) hi = 0x8F;
            if (i + 1 < n && byte(i + 1) >= lo && byte(i + 1) <= hi) {
                if (cont(i + 2)) {
                    if (cont(i + 3)) {
                        uint32_t cp = ((b0 & 0x07) << 18) | ((byte(i + 1) & 0x3F) << 12) |
                                      ((byte(i + 2) & 0x3F) << 6) | (byte(i + 3) & 0x3F);
                        cp -= 0x10000;
                        out.push_back((char16_t)(0xD800 + (cp >> 10)));
                        out.push_back((char16_t)(0xDC00 + (cp & 0x3FF)));
                        i += 4;
                    } else {
                        out.push_back(0xFFFD);
                        i += 3;
                    }
                } else {
                    out.push_back(0xFFFD);
                    i += 2;
                }
            } else {
                out.push_back(0xFFFD);
                i += 1;
            }
            continue;
        }
        out.push_back(0xFFFD);
        i += 1;
    }
    return out;
}

std::u16string decodeUtf8(std::string_view bytes) { return decode(bytes, false); }

std::u16string decodeWtf8(std::string_view bytes) { return decode(bytes, true); }

void appendCodePoint(std::string& out, uint32_t cp) {
    if (cp < 0x80) {
        out.push_back((char)cp);
    } else if (cp < 0x800) {
        out.push_back((char)(0xC0 | (cp >> 6)));
        out.push_back((char)(0x80 | (cp & 0x3F)));
    } else if (cp < 0x10000) {
        out.push_back((char)(0xE0 | (cp >> 12)));
        out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back((char)(0x80 | (cp & 0x3F)));
    } else {
        out.push_back((char)(0xF0 | (cp >> 18)));
        out.push_back((char)(0x80 | ((cp >> 12) & 0x3F)));
        out.push_back((char)(0x80 | ((cp >> 6) & 0x3F)));
        out.push_back((char)(0x80 | (cp & 0x3F)));
    }
}

std::string encodeUtf8(std::u16string_view text) {
    std::string out;
    out.reserve(text.size());
    for (size_t i = 0; i < text.size(); i++) {
        char16_t c = text[i];
        if (isHighSurrogate(c) && i + 1 < text.size() && isLowSurrogate(text[i + 1])) {
            uint32_t cp = 0x10000 + (((uint32_t)c - 0xD800) << 10) + ((uint32_t)text[i + 1] - 0xDC00);
            appendCodePoint(out, cp);
            i++;
        } else {
            appendCodePoint(out, c);
        }
    }
    return out;
}

int32_t u16len(std::string_view text) {
    int32_t n = 0;
    for (char c : text) {
        uint8_t b = (uint8_t)c;
        if ((b & 0xC0) == 0x80) continue;  // continuation byte
        n += (b >= 0xF0) ? 2 : 1;
    }
    return n;
}

int32_t javaHash(std::string_view text) {
    uint32_t h = 0;
    for (char16_t c : decodeWtf8(text)) h = 31 * h + c;
    return (int32_t)h;
}

int compareUtf16(std::string_view a, std::string_view b) {
    std::u16string ua = decodeWtf8(a);
    std::u16string ub = decodeWtf8(b);
    size_t n = ua.size() < ub.size() ? ua.size() : ub.size();
    for (size_t i = 0; i < n; i++) {
        if (ua[i] != ub[i]) return (int)ua[i] - (int)ub[i];
    }
    return (int)ua.size() - (int)ub.size();
}

// ---------------------------------------------------------------------------
// Kotlin String functions
// ---------------------------------------------------------------------------

std::string removePrefix(std::string_view s, std::string_view prefix) {
    return std::string(startsWith(s, prefix) ? s.substr(prefix.size()) : s);
}

std::string removeSuffix(std::string_view s, std::string_view suffix) {
    return std::string(endsWith(s, suffix) ? s.substr(0, s.size() - suffix.size()) : s);
}

std::string substringBefore(std::string_view s, std::string_view delimiter) {
    size_t at = s.find(delimiter);
    return std::string(at == std::string_view::npos ? s : s.substr(0, at));
}

std::string substringAfter(std::string_view s, std::string_view delimiter) {
    size_t at = s.find(delimiter);
    return std::string(at == std::string_view::npos ? s : s.substr(at + delimiter.size()));
}

std::string substringBeforeLast(std::string_view s, std::string_view delimiter) {
    size_t at = s.rfind(delimiter);
    return std::string(at == std::string_view::npos ? s : s.substr(0, at));
}

std::string substringAfterLast(std::string_view s, std::string_view delimiter) {
    size_t at = s.rfind(delimiter);
    return std::string(at == std::string_view::npos ? s : s.substr(at + delimiter.size()));
}

std::string replaceAll(std::string_view s, std::string_view from, std::string_view to) {
    if (from.empty()) {
        // Kotlin inserts [to] between every unit and at both ends.
        std::string out(to);
        std::u16string u = decodeWtf8(s);
        for (char16_t c : u) {
            out += encodeUtf8(std::u16string_view(&c, 1));
            out += to;
        }
        return out;
    }
    std::string out;
    size_t i = 0;
    while (true) {
        size_t at = s.find(from, i);
        if (at == std::string_view::npos) break;
        out.append(s.substr(i, at - i));
        out.append(to);
        i = at + from.size();
    }
    out.append(s.substr(i));
    return out;
}

std::vector<std::string> split(std::string_view s, std::string_view delimiter) {
    std::vector<std::string> out;
    size_t i = 0;
    while (true) {
        size_t at = s.find(delimiter, i);
        if (at == std::string_view::npos || delimiter.empty()) break;
        out.emplace_back(s.substr(i, at - i));
        i = at + delimiter.size();
    }
    out.emplace_back(s.substr(i));
    return out;
}

std::string join(const std::vector<std::string>& parts, std::string_view separator) {
    std::string out;
    for (size_t i = 0; i < parts.size(); i++) {
        if (i > 0) out.append(separator);
        out.append(parts[i]);
    }
    return out;
}

std::string repeat(std::string_view s, int count) {
    std::string out;
    for (int i = 0; i < count; i++) out.append(s);
    return out;
}

std::string trimStart(std::string_view s) {
    std::u16string u = decodeWtf8(s);
    size_t i = 0;
    while (i < u.size() && isWhitespace(u[i])) i++;
    return encodeUtf8(std::u16string_view(u).substr(i));
}

std::string trimEnd(std::string_view s) {
    std::u16string u = decodeWtf8(s);
    size_t j = u.size();
    while (j > 0 && isWhitespace(u[j - 1])) j--;
    return encodeUtf8(std::u16string_view(u).substr(0, j));
}

std::string trim(std::string_view s) {
    std::u16string u = decodeWtf8(s);
    size_t i = 0;
    size_t j = u.size();
    while (i < j && isWhitespace(u[i])) i++;
    while (j > i && isWhitespace(u[j - 1])) j--;
    return encodeUtf8(std::u16string_view(u).substr(i, j - i));
}

bool isBlank(std::string_view s) {
    for (char16_t c : decodeWtf8(s)) {
        if (!isWhitespace(c)) return false;
    }
    return true;
}

std::string uppercase(std::string_view s) {
    std::u16string u = decodeWtf8(s);
    for (char16_t& c : u) c = uppercaseChar(c);
    return encodeUtf8(u);
}

std::string lowercase(std::string_view s) {
    std::u16string u = decodeWtf8(s);
    for (char16_t& c : u) c = lowercaseChar(c);
    return encodeUtf8(u);
}

std::string str(long long value) {
    char buffer[32];
    auto result = std::to_chars(buffer, buffer + sizeof buffer, value);
    return std::string(buffer, result.ptr);
}

std::string str(unsigned long long value) {
    char buffer[32];
    auto result = std::to_chars(buffer, buffer + sizeof buffer, value);
    return std::string(buffer, result.ptr);
}

std::string toStringRadix(int64_t value, int radix) {
    char buffer[80];
    auto result = std::to_chars(buffer, buffer + sizeof buffer, (long long)value, radix);
    return std::string(buffer, result.ptr);
}

}  // namespace azora
