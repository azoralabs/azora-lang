// Port of UseAsTemplate.kt.
#include "frontend/use_as_template.h"

#include "base/chars.h"
#include "base/failure.h"
#include "base/text.h"
#include "frontend/ast_util.h"

namespace azora {
namespace UseAsTemplate {

static std::optional<size_t> findPlaceholderEnd(const std::u16string& templateText, size_t start) {
    for (size_t i = start; i < templateText.size(); i++) {
        if (templateText[i] == u'}') return i;
    }
    return std::nullopt;
}

static std::string resolvePlaceholder(const std::string& expr, const std::vector<std::string>& typeParams,
                                      const std::vector<TypeRef>& traitArgs, Arena& scratch) {
    std::vector<std::string> parts;
    for (const std::string& part : split(expr, ".")) {
        if (!isBlank(part)) parts.push_back(part);
    }
    if (parts.empty()) fail("Empty placeholder in spec 'use as' template");
    const std::string& param = parts[0];
    int index = -1;
    for (size_t i = 0; i < typeParams.size(); i++) {
        if (typeParams[i] == param) {
            index = (int)i;
            break;
        }
    }
    if (index < 0) fail("Unknown type parameter '" + param + "' in spec 'use as' template");
    TypeRef type;
    if ((size_t)index < traitArgs.size()) {
        type = traitArgs[(size_t)index];
    } else {
        TypeRefNamed value;
        value.name = "Value";
        type = make(scratch, value);
    }
    if (parts.size() == 1) return typeMemberSuffix(type);
    std::string property = join(std::vector<std::string>(parts.begin() + 1, parts.end()), ".");
    if (property == "name" || property == "typeName") return typeMemberSuffix(type);
    if (property == "rawName" || property == "displayName") return displayName(type);
    fail("Unknown type template property '" + property + "' in spec 'use as' template");
}

std::string expand(const std::string& templateText, const std::vector<std::string>& typeParams,
                   const std::vector<TypeRef>& traitArgs) {
    if (!contains(templateText, '$')) return templateText;
    Arena scratch;
    std::u16string t = decodeWtf8(templateText);
    std::string out;
    size_t i = 0;
    while (i < t.size()) {
        char16_t ch = t[i];
        if (ch == u'$' && i + 1 < t.size() && t[i + 1] == u'{') {
            std::optional<size_t> end = findPlaceholderEnd(t, i + 2);
            if (!end) fail("Unclosed placeholder in spec 'use as' template: " + templateText);
            std::string inner = trim(encodeUtf8(t.substr(i + 2, *end - (i + 2))));
            out += resolvePlaceholder(inner, typeParams, traitArgs, scratch);
            i = *end + 1;
        } else {
            out += encodeUtf8(std::u16string_view(&t[i], 1));
            i++;
        }
    }
    return out;
}

std::string typeMemberSuffix(const TypeRef& type) {
    std::string rawText;
    if (TypeRefNamed* named = as<TypeRefNamed>(type)) rawText = named->name;
    else rawText = displayName(type);
    std::u16string raw;
    for (char16_t c : decodeWtf8(rawText)) {
        if (isLetterOrDigit(c)) raw.push_back(c);
    }
    if (raw.empty()) raw = u"Value";
    raw[0] = uppercaseChar(raw[0]);
    return encodeUtf8(raw);
}

}  // namespace UseAsTemplate

std::string callbackMethodBaseName(const std::string& traitName) {
    if (traitName.empty()) return "callback";
    std::u16string u = decodeWtf8(traitName);
    u[0] = lowercaseChar(u[0]);
    return encodeUtf8(u);
}

}  // namespace azora
