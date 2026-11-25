#include "base/numbers.h"

#include "base/chars.h"
#include "base/failure.h"
#include "base/text.h"

#include <charconv>
#include <cmath>
#include <cstring>
#include <limits>

namespace azora {

uint64_t doubleBits(double d) {
    uint64_t bits;
    std::memcpy(&bits, &d, sizeof bits);
    return bits;
}

uint32_t floatBits(float f) {
    uint32_t bits;
    std::memcpy(&bits, &f, sizeof bits);
    return bits;
}

namespace {

/** Splits `d.ddde±x` (to_chars scientific) into its digits and decimal exponent. */
void splitScientific(const char* begin, const char* end, std::string& digits, int& exponent) {
    digits.clear();
    const char* p = begin;
    if (*p == '-') p++;
    while (p < end && *p != 'e') {
        if (*p != '.') digits.push_back(*p);
        p++;
    }
    exponent = 0;
    if (p < end) {
        p++;  // 'e'
        bool negative = false;
        if (*p == '+' || *p == '-') negative = *p++ == '-';
        int value = 0;
        while (p < end) value = value * 10 + (*p++ - '0');
        exponent = negative ? -value : value;
    }
    while (digits.size() > 1 && digits.back() == '0') digits.pop_back();
}

/** Java's layout for a decimal `0.d1..dn × 10^(exponent+1)`, i.e. d1.d2..dn × 10^exponent. */
std::string javaLayout(bool negative, const std::string& digits, int exponent) {
    std::string out = negative ? "-" : "";
    if (exponent >= -3 && exponent < 7) {
        if (exponent >= 0) {
            size_t whole = (size_t)exponent + 1;
            if (digits.size() <= whole) {
                out += digits;
                out.append(whole - digits.size(), '0');
                out += ".0";
            } else {
                out += digits.substr(0, whole);
                out += '.';
                out += digits.substr(whole);
            }
        } else {
            out += "0.";
            out.append((size_t)(-exponent - 1), '0');
            out += digits;
        }
    } else {
        out += digits[0];
        out += '.';
        out += digits.size() > 1 ? digits.substr(1) : "0";
        out += 'E';
        out += str(exponent);
    }
    return out;
}

template <class F>
std::string javaToString(F value) {
    if (std::isnan(value)) return "NaN";
    if (std::isinf(value)) return value < 0 ? "-Infinity" : "Infinity";
    bool negative = std::signbit(value);
    if (value == 0) return negative ? "-0.0" : "0.0";
    F magnitude = negative ? -value : value;
    char buffer[64];
    auto shortest = std::to_chars(buffer, buffer + sizeof buffer, magnitude, std::chars_format::scientific);
    std::string digits;
    int exponent = 0;
    splitScientific(buffer, shortest.ptr, digits, exponent);
    if (digits.size() == 1) {
        // Java: when the shortest decimal has one digit, the closest decimal of
        // length 1 or 2 is chosen (Double.MIN_VALUE prints as 4.9E-324).
        char two[64];
        auto twoEnd = std::to_chars(two, two + sizeof two, magnitude, std::chars_format::scientific, 1);
        F back = 0;
        std::from_chars(two, twoEnd.ptr, back);
        if (back == magnitude) splitScientific(two, twoEnd.ptr, digits, exponent);
    }
    return javaLayout(negative, digits, exponent);
}

/** Java's NumberFormatException text for an integer parse. */
[[noreturn]] void integerFormatFailure(std::string_view s, int radix) {
    std::string message = "For input string: \"" + std::string(s) + "\"";
    if (radix != 10) message += " under radix " + str(radix);
    failWith(FailureKind::NumberFormat, message);
}

}  // namespace

std::string javaDoubleToString(double d) { return javaToString(d); }

std::string javaFloatToString(float f) { return javaToString(f); }

double javaParseDouble(std::string_view s) {
    double value = 0;
    auto result = std::from_chars(s.data(), s.data() + s.size(), value);
    bool sawDigit = false;
    for (char c : s) {
        if (c >= '0' && c <= '9') sawDigit = true;
        if (c == 'e' || c == 'E') break;
    }
    if (s.empty() || !sawDigit || result.ec == std::errc::invalid_argument || result.ptr != s.data() + s.size()) {
        failWith(FailureKind::NumberFormat, "For input string: \"" + std::string(s) + "\"");
    }
    if (result.ec == std::errc::result_out_of_range) {
        // Java rounds to infinity or zero rather than failing.
        bool tiny = false;
        size_t e = s.find_first_of("eE");
        if (e != std::string_view::npos && e + 1 < s.size() && s[e + 1] == '-') tiny = true;
        value = tiny ? 0.0 : std::numeric_limits<double>::infinity();
    }
    return value;
}

std::optional<int64_t> toLongOrNull(std::string_view s, int radix) {
    std::u16string u = decodeUtf8(s);
    size_t length = u.size();
    if (length == 0) return std::nullopt;
    size_t start;
    bool isNegative;
    int64_t limit;
    char16_t firstChar = u[0];
    if (firstChar < u'0') {
        if (length == 1) return std::nullopt;
        start = 1;
        if (firstChar == u'-') {
            isNegative = true;
            limit = std::numeric_limits<int64_t>::min();
        } else if (firstChar == u'+') {
            isNegative = false;
            limit = -std::numeric_limits<int64_t>::max();
        } else {
            return std::nullopt;
        }
    } else {
        start = 0;
        isNegative = false;
        limit = -std::numeric_limits<int64_t>::max();
    }
    int64_t limitForMaxRadix = (-std::numeric_limits<int64_t>::max()) / 36;
    int64_t limitBeforeMul = limitForMaxRadix;
    int64_t result = 0;
    for (size_t i = start; i < length; i++) {
        int digit = digitValue(u[i], radix);
        if (digit < 0) return std::nullopt;
        if (result < limitBeforeMul) {
            if (limitBeforeMul == limitForMaxRadix) {
                limitBeforeMul = limit / radix;
                if (result < limitBeforeMul) return std::nullopt;
            } else {
                return std::nullopt;
            }
        }
        result *= radix;
        if (result < limit + digit) return std::nullopt;
        result -= digit;
    }
    return isNegative ? result : -result;
}

std::optional<uint64_t> toULongOrNull(std::string_view s, int radix) {
    std::u16string u = decodeUtf8(s);
    size_t length = u.size();
    if (length == 0) return std::nullopt;
    uint64_t limit = std::numeric_limits<uint64_t>::max();
    size_t start;
    char16_t firstChar = u[0];
    if (firstChar < u'0') {
        if (length == 1 || firstChar != u'+') return std::nullopt;
        start = 1;
    } else {
        start = 0;
    }
    uint64_t limitForMaxRadix = std::numeric_limits<uint64_t>::max() / 36;
    uint64_t limitBeforeMul = limitForMaxRadix;
    uint64_t uradix = (uint64_t)radix;
    uint64_t result = 0;
    for (size_t i = start; i < length; i++) {
        int digit = digitValue(u[i], radix);
        if (digit < 0) return std::nullopt;
        if (result > limitBeforeMul) {
            if (limitBeforeMul == limitForMaxRadix) {
                limitBeforeMul = limit / uradix;
                if (result > limitBeforeMul) return std::nullopt;
            } else {
                return std::nullopt;
            }
        }
        result *= uradix;
        uint64_t beforeAdding = result;
        result += (uint64_t)digit;
        if (result < beforeAdding) return std::nullopt;
    }
    return result;
}

std::optional<int32_t> toIntOrNull(std::string_view s, int radix) {
    std::optional<int64_t> wide = toLongOrNull(s, radix);
    if (!wide || *wide < std::numeric_limits<int32_t>::min() || *wide > std::numeric_limits<int32_t>::max()) {
        return std::nullopt;
    }
    return (int32_t)*wide;
}

int32_t javaParseInt(std::string_view s, int radix) {
    std::optional<int32_t> value = toIntOrNull(s, radix);
    if (!value) integerFormatFailure(s, radix);
    return *value;
}

int64_t javaParseLong(std::string_view s, int radix) {
    std::optional<int64_t> value = toLongOrNull(s, radix);
    if (!value) integerFormatFailure(s, radix);
    return *value;
}

}  // namespace azora
