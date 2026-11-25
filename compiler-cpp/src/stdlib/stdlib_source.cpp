// Port of stdlib/StdlibSource.kt and StdlibSource.macos.kt.
#include "stdlib/stdlib_source.h"

#include "base/chars.h"
#include "base/failure.h"
#include "base/fs.h"
#include "base/text.h"

#include <algorithm>
#include <functional>

namespace azora {

namespace {

/** Kotlin `trimEnd('/')`. */
std::string trimSlashes(const std::string& s) {
    size_t end = s.size();
    while (end > 0 && s[end - 1] == '/') end--;
    return s.substr(0, end);
}

}  // namespace

std::optional<StdlibPackageManifest> parseStdlibPackageManifest(const std::string& text) {
    struct ManifestToken {
        char16_t kind;
        std::u16string text;
    };
    std::u16string source = decodeUtf8(text);
    std::vector<ManifestToken> tokens;
    size_t index = 0;
    auto at = [&](size_t i) -> char16_t { return i < source.size() ? source[i] : 0; };
    while (index < source.size()) {
        char16_t character = source[index];
        if (isWhitespace(character) || character == u',') {
            index++;
        } else if (character == u'#') {
            while (index < source.size() && source[index] != u'\n') index++;
        } else if (character == u'/' && index + 1 < source.size() && at(index + 1) == u'/') {
            index += 2;
            while (index < source.size() && source[index] != u'\n') index++;
        } else if (character == u'/' && index + 1 < source.size() && at(index + 1) == u'*') {
            index += 2;
            while (index + 1 < source.size() && !(source[index] == u'*' && source[index + 1] == u'/')) index++;
            index = std::min(index + 2, source.size());
        } else if (character == u'"') {
            index++;
            std::u16string value;
            while (index < source.size() && source[index] != u'"') {
                if (source[index] == u'\\' && index + 1 < source.size()) {
                    index++;
                    char16_t escaped = source[index];
                    value.push_back(escaped == u'n' ? u'\n' : escaped == u'r' ? u'\r' : escaped == u't' ? u'\t' : escaped);
                } else {
                    value.push_back(source[index]);
                }
                index++;
            }
            if (index < source.size()) index++;
            tokens.push_back({u's', value});
        } else if (isLetter(character) || character == u'_') {
            size_t start = index++;
            while (index < source.size() &&
                   (isLetterOrDigit(source[index]) || source[index] == u'_' || source[index] == u'-')) {
                index++;
            }
            tokens.push_back({u'i', source.substr(start, index - start)});
        } else if (std::u16string_view(u":{}[]()").find(character) != std::u16string_view::npos) {
            tokens.push_back({character, std::u16string(1, character)});
            index++;
        } else {
            index++;
        }
    }
    auto kindAt = [&](size_t i) -> char16_t { return i < tokens.size() ? tokens[i].kind : 0; };
    std::optional<size_t> packageIndex;
    for (size_t i = 0; i < tokens.size(); i++) {
        if (tokens[i].text == u"package" && kindAt(i + 1) == u':' && kindAt(i + 2) == u'{') {
            packageIndex = i;
            break;
        }
    }
    if (!packageIndex) return std::nullopt;
    int depth = 1;
    std::optional<std::u16string> name;
    std::optional<std::u16string> version;
    size_t cursor = *packageIndex + 3;
    while (cursor < tokens.size() && depth > 0) {
        const ManifestToken& token = tokens[cursor];
        if (token.kind == u'{') {
            depth++;
        } else if (token.kind == u'}') {
            depth--;
        } else if (token.kind == u'i' || token.kind == u's') {
            if (depth == 1 && kindAt(cursor + 1) == u':' && kindAt(cursor + 2) == u's') {
                const std::u16string& value = tokens[cursor + 2].text;
                if (token.text == u"name") name = value;
                else if (token.text == u"version") version = value;
                cursor += 2;
            }
        }
        cursor++;
    }
    auto blank = [](const std::optional<std::u16string>& s) {
        if (!s) return true;
        for (char16_t c : *s) {
            if (!isWhitespace(c)) return false;
        }
        return true;
    };
    if (blank(name) || blank(version)) return std::nullopt;
    return StdlibPackageManifest{encodeUtf8(*name), encodeUtf8(*version)};
}

std::vector<StdlibRoot> stdlibDiskRoots() {
    std::vector<StdlibRoot> roots;
    if (std::optional<std::string> stdlib = envVar("AZORA_STDLIB"); stdlib && !isBlank(*stdlib)) {
        roots.push_back({*stdlib, "AZORA_STDLIB", true});
    }
    std::string directory = currentDirectory();
    while (true) {
        if (pathExists(directory + "/workspace.azon") || pathExists(directory + "/azora.toml") ||
            pathExists(directory + "/package.azon")) {
            if (isDirectory(directory + "/std")) roots.push_back({directory + "/std", "project-local std/"});
            break;
        }
        if (directory == "/") break;
        size_t slash = directory.rfind('/');
        directory = slash == std::string::npos ? directory : directory.substr(0, slash);
        if (directory.empty()) directory = "/";
    }
    if (std::optional<std::string> home = envVar("AZORA_HOME"); home && !isBlank(*home)) {
        roots.push_back({*home + "/std", "AZORA_HOME"});
    }
    return roots;
}

std::optional<std::vector<StdlibFile>> readStdlibTree(const std::string& root) {
    if (!isDirectory(root)) return std::nullopt;
    std::string prefix = trimSlashes(root) + "/";
    std::vector<StdlibFile> result;
    std::function<void(const std::string&)> visit = [&](const std::string& path) {
        if (!isDirectory(path)) fail("cannot read standard library directory '" + path + "'");
        for (const std::string& name : listDirectory(path)) {
            std::string child = path + "/" + name;
            if (isDirectory(child)) {
                visit(child);
            } else if (isFile(child) && endsWith(child, ".az")) {
                std::optional<std::string> text = readFile(child);
                if (!text) fail("cannot read standard library '" + child + "'");
                result.push_back({removePrefix(child, prefix), std::move(*text)});
            }
        }
    };
    visit(root);
    std::stable_sort(result.begin(), result.end(),
                     [](const StdlibFile& a, const StdlibFile& b) { return compareUtf16(a.path, b.path) < 0; });
    return result;
}

std::optional<std::string> readStdlibPackageManifest(const std::string& root) {
    std::string trimmed = trimSlashes(root);
    size_t slash = trimmed.rfind('/');
    std::string parent = slash == std::string::npos ? trimmed : trimmed.substr(0, slash);
    return readFile(parent + "/" + STDLIB_PACKAGE_MANIFEST);
}

}  // namespace azora
