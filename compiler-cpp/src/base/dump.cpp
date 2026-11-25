#include "base/dump.h"

#include "base/chars.h"
#include "base/numbers.h"
#include "base/text.h"

#include <cstdio>

namespace azora {

static void hex4(std::string& out, unsigned code) {
    char buffer[8];
    std::snprintf(buffer, sizeof buffer, "%04X", code);
    out.append(buffer);
}

void Dumper::quoted(std::string_view text) {
    out.push_back('"');
    std::u16string u = decodeWtf8(text);
    for (size_t i = 0; i < u.size(); i++) {
        char16_t c = u[i];
        if (c == u'"') {
            out.append("\\\"");
        } else if (c == u'\\') {
            out.append("\\\\");
        } else if (c == u'\n') {
            out.append("\\n");
        } else if (c == u'\r') {
            out.append("\\r");
        } else if (c == u'\t') {
            out.append("\\t");
        } else if (c < 0x20 || c == 0x7F) {
            out.append("\\u");
            hex4(out, c);
        } else if (isHighSurrogate(c) && i + 1 < u.size() && isLowSurrogate(u[i + 1])) {
            out.append(encodeUtf8(std::u16string_view(u).substr(i, 2)));
            i++;
        } else if (isSurrogate(c)) {
            out.append("\\u");
            hex4(out, c);
        } else {
            appendCodePoint(out, c);
        }
    }
    out.push_back('"');
}

void dumpValue(Dumper& d, const std::string& v, int) { d.quoted(v); }

void dumpValue(Dumper& d, bool v, int) { d.raw(v ? "true" : "false"); }

void dumpValue(Dumper& d, int32_t v, int) { d.raw(str(v)); }

void dumpValue(Dumper& d, int64_t v, int) { d.raw(str((long long)v)); }

void dumpValue(Dumper& d, double v, int) {
    char bits[24];
    std::snprintf(bits, sizeof bits, "%llx", (unsigned long long)doubleBits(v));
    d.raw(javaDoubleToString(v));
    d.raw("#");
    d.raw(bits);
}

}  // namespace azora
