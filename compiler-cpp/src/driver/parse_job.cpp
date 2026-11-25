// `azora __parse-job <job>`: the C++ side of the differential checks.
//
// Runs the same commands as compiler-cpp/oracle/AstOracle.kt and writes the
// same canonical dumps, so the two output trees can be compared with cmp.
#include "driver/parse_job.h"

#include "base/dump.h"
#include "base/failure.h"
#include "base/fs.h"
#include "base/text.h"
#include "frontend/ast.h"
#include "frontend/ast_validator.h"
#include "frontend/lexer.h"
#include "frontend/macro_expander.h"
#include "frontend/parser.h"
#include "stdlib/stdlib_injector.h"

#include <algorithm>
#include <cstdio>
#include <cstdlib>
#include <functional>

namespace azora {

namespace {

const char* failureClassName(FailureKind kind) {
    switch (kind) {
        case FailureKind::IllegalState: return "IllegalStateException";
        case FailureKind::IllegalArgument: return "IllegalArgumentException";
        case FailureKind::NumberFormat: return "NumberFormatException";
        case FailureKind::IndexOutOfBounds: return "IndexOutOfBoundsException";
        case FailureKind::NoSuchElement: return "NoSuchElementException";
        case FailureKind::ClassCast: return "ClassCastException";
        case FailureKind::NullPointer: return "NullPointerException";
    }
    return "Exception";
}

/** Every `.az` file under [dir], as paths relative to [root] (Kotlin walkTopDown + relativeTo). */
void collectSources(const std::string& root, const std::string& relative, std::vector<std::string>& out) {
    std::string dir = relative.empty() ? root : root + "/" + relative;
    for (const std::string& name : listDirectory(dir)) {
        std::string rel = relative.empty() ? name : relative + "/" + name;
        std::string full = root + "/" + rel;
        if (isDirectory(full)) {
            collectSources(root, rel, out);
        } else if (isFile(full)) {
            size_t dot = name.rfind('.');
            if (dot != std::string::npos && name.substr(dot + 1) == "az") out.push_back(rel);
        }
    }
}

std::string failureText(const CompileFailure& failure) {
    Dumper d;
    d.raw("(Failure ");
    d.raw(failureClassName(failure.kind));
    d.raw(" ");
    d.quoted(failure.message);
    d.raw(")");
    return std::move(d.out);
}

struct JobRunner {
    explicit JobRunner(bool writeOutputs) : writeOutputs(writeOutputs) {}

    bool writeOutputs;
    double parseSeconds = 0;

    TypeListEnv stdLists;
    EnumEnv stdEnums;
    TypeListScopes stdListScopes;
    std::string stage = "parse";

    /** What `stage inject` dumps: the library checks a compilation runs, then the injected program. */
    struct InjectStage {
        std::vector<std::string> unusable;
        std::vector<std::string> imports;
        std::vector<std::string> cycles;
        std::vector<UnresolvedContractAccess> contracts;
        TypeAccessValidation typeAccess;
        std::vector<std::string> implPacks;
        std::vector<std::string> privateAccess;
        Program injected;
    };

    /** What a std/libraries/entries command dumps: a program, an error list (validate) or an InjectStage. */
    struct StageResult {
        std::optional<Program> program;
        std::vector<std::string> errors;
        std::optional<InjectStage> inject;
    };

    /** The injector `stage inject` uses, made on first use as the oracle's lazy one is. */
    std::optional<std::vector<LibrarySource>> injectorSources;
    std::shared_ptr<StdlibInjector> injectorStorage;

    StdlibInjector& injector() {
        if (!injectorStorage) injectorStorage = StdlibInjector::create(injectorSources ? *injectorSources : std::vector<LibrarySource>{});
        return *injectorStorage;
    }

    static void dumpInjectStage(Dumper& d, const InjectStage& v) {
        d.open("InjectStage");
        d.field("unusable", 0), dumpValue(d, v.unusable, 2);
        d.field("imports", 0), dumpValue(d, v.imports, 2);
        d.field("cycles", 0), dumpValue(d, v.cycles, 2);
        d.field("contracts", 0), dumpValue(d, v.contracts, 2);
        d.field("typeAccess", 0), dumpValue(d, v.typeAccess, 2);
        d.field("implPacks", 0), dumpValue(d, v.implPacks, 2);
        d.field("privateAccess", 0), dumpValue(d, v.privateAccess, 2);
        d.field("injected", 0), dumpValue(d, v.injected, 2);
        d.close(0);
    }

    /** The result for [program] at the current [stage]. */
    StageResult staged(Arena& arena, const Program& program) {
        if (stage == "parse") return {program, {}, std::nullopt};
        if (stage == "macros") return {MacroExpander::expand(arena, program), {}, std::nullopt};
        if (stage == "validate") {
            OrderedMap<std::string, LibraryDecorator> noLibraries;
            return {std::nullopt, AstValidator(&noLibraries).validate(MacroExpander::expand(arena, program)), std::nullopt};
        }
        if (stage == "inject") {
            StdlibInjector& libraries = injector();
            InjectStage result;
            result.unusable = libraries.unusableLibraries(program);
            result.imports = libraries.validateImports(program);
            result.cycles = libraries.validateModuleCycles(program);
            result.contracts = libraries.unresolvedContracts(program);
            result.typeAccess = libraries.validateTypeAccess(program);
            result.implPacks = libraries.validateImplPackDeclarations(program);
            result.privateAccess = libraries.validatePrivateAccess(program);
            result.injected = libraries.inject(program);
            return {std::nullopt, {}, std::move(result)};
        }
        fail("unknown stage '" + stage + "'");
    }

    std::vector<std::pair<std::string, std::string>> readList(const std::string& path) {
        std::vector<std::pair<std::string, std::string>> out;
        std::optional<std::string> text = readFile(path);
        if (!text) fail("cannot read list '" + path + "'");
        for (const std::string& line : split(*text, "\n")) {
            if (isBlank(line)) continue;
            std::vector<std::string> parts = split(line, "\t");
            out.emplace_back(parts[0], parts.size() > 1 ? parts[1] : "");
        }
        return out;
    }

    std::string source(const std::string& path) {
        std::optional<std::string> text = readFile(path);
        if (!text) fail("cannot read '" + path + "'");
        return std::move(*text);
    }

    /**
     * Lexes and parses [source] with the given environment, timing only that,
     * and writes the dump (or the oracle's `(Failure ...)` line) to [output].
     */
    void parseOne(const std::string& source, const std::string& output, TypeListEnv* lists, EnumEnv* enums,
                  TypeListScopes* scopes) {
        Arena arena;
        std::optional<StageResult> result;
        std::string failed;
        double start = nowSeconds();
        try {
            Parser parser(arena, Lexer(source).tokenize(), lists, enums, false, scopes);
            result = staged(arena, parser.parse());
        } catch (const CompileFailure& failure) {
            failed = failureText(failure);
        }
        parseSeconds += nowSeconds() - start;
        if (!writeOutputs) return;
        if (result) {
            Dumper d;
            if (result->program) {
                dumpValue(d, *result->program, 0);
            } else if (result->inject) {
                dumpInjectStage(d, *result->inject);
            } else {
                dumpValue(d, result->errors, 0);
            }
            writeFile(output, d.out + "\n");
        } else {
            writeFile(output, failed + "\n");
        }
    }

    /** AzStdlib.parse: every file in path order, one environment rebuilt from empty. */
    void std(const std::string& root, const std::string& out) {
        std::vector<std::string> files;
        collectSources(root, "", files);
        std::sort(files.begin(), files.end(),
                  [](const std::string& a, const std::string& b) { return compareUtf16(a, b) < 0; });
        stdLists = {};
        stdEnums = {};
        stdListScopes = {};
        for (const std::string& relative : files) {
            parseOne(source(root + "/" + relative), out + "/" + relative + ".ast", &stdLists, &stdEnums,
                     &stdListScopes);
        }
    }

    /** StdlibInjector.create: one shared copy of the std environment for every library. */
    void libraries(const std::string& listPath) {
        TypeListEnv lists = stdLists;
        EnumEnv enums = stdEnums;
        TypeListScopes scopes = stdListScopes;
        for (const auto& [input, output] : readList(listPath)) {
            parseOne(source(input), output, &lists, &enums, &scopes);
        }
    }

    /** Compiler.compile's entry parse: a fresh copy of the std environment per file. */
    void entries(const std::string& listPath) {
        for (const auto& [input, output] : readList(listPath)) {
            std::string text = source(input);
            FragmentMacroTables saved = Parser::fragmentTables();
            TypeListEnv lists = stdLists;
            EnumEnv enums = stdEnums;
            TypeListScopes scopes = stdListScopes;
            parseOne(text, output, &lists, &enums, &scopes);
            Parser::fragmentTables() = std::move(saved);
        }
    }

    void lex(const std::string& listPath) {
        for (const auto& [input, output] : readList(listPath)) {
            std::string text = source(input);
            std::optional<std::vector<Token>> tokens;
            std::string failed;
            double start = nowSeconds();
            try {
                tokens = Lexer(text).tokenize();
            } catch (const CompileFailure& failure) {
                failed = failureText(failure);
            }
            parseSeconds += nowSeconds() - start;
            if (!writeOutputs) continue;
            if (tokens) {
                Dumper d;
                dumpValue(d, *tokens, 0);
                writeFile(output, d.out + "\n");
            } else {
                writeFile(output, failed + "\n");
            }
        }
    }

    void run(const std::vector<std::string>& words) {
        const std::string& command = words[0];
        if (command == "reset") {
            Parser::resetFragmentMacros();
            InferredSites::reset(0);
        } else if (command == "chars") {
            // Only the oracle generates the tables.
        } else if (command == "std") {
            std(words.at(1), words.at(2));
        } else if (command == "libraries") {
            libraries(words.at(1));
        } else if (command == "entries") {
            entries(words.at(1));
        } else if (command == "lex") {
            lex(words.at(1));
        } else if (command == "stage") {
            stage = words.at(1);
        } else if (command == "injector") {
            if (injectorSources) fail("one injector per job");
            if (words.at(1) == "std") {
                injectorSources = std::vector<LibrarySource>{};
            } else if (words.at(1) == "libraries") {
                std::vector<LibrarySource> sources;
                // Absolute paths cannot spell a package's module: the path check is off, as for an editor's snapshot.
                for (const auto& [input, output] : readList(words.at(2))) sources.push_back({input, source(input), false});
                injectorSources = std::move(sources);
            } else {
                fail("unknown injector '" + words.at(1) + "'");
            }
        } else {
            fail("unknown job command '" + command + "'");
        }
    }
};

}  // namespace

int runParseJob(const std::string& jobPath) {
    std::optional<std::string> job = readFile(jobPath);
    if (!job) {
        std::fprintf(stderr, "cannot read job '%s'\n", jobPath.c_str());
        return 2;
    }
    std::vector<std::vector<std::string>> commands;
    for (const std::string& raw : split(*job, "\n")) {
        std::string line = trim(raw);
        if (line.empty() || startsWith(line, "#")) continue;
        std::vector<std::string> words;
        for (const std::string& w : split(line, " ")) {
            if (!w.empty()) words.push_back(w);
        }
        commands.push_back(std::move(words));
    }
    int repeat = 1;
    if (const char* env = std::getenv("AZORA_ORACLE_REPEAT")) repeat = std::max(1, std::atoi(env));
    for (int round = 1; round <= repeat; round++) {
        JobRunner runner(round == 1);
        try {
            for (const std::vector<std::string>& words : commands) runner.run(words);
        } catch (const CompileFailure& failure) {
            std::fprintf(stderr, "job failed: %s\n", failure.message.c_str());
            return 1;
        }
        std::printf("c++ round %d: parse %.3f s\n", round, runner.parseSeconds);
    }
    return 0;
}

}  // namespace azora
