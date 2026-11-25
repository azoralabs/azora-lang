// `azora __parse-job <job>`: the C++ side of the differential checks.
//
// Runs the same commands as compiler-cpp/oracle/AstOracle.kt and writes the
// same canonical dumps, so the two output trees can be compared with cmp.
#include "driver/parse_job.h"

#include "base/dump.h"
#include "base/failure.h"
#include "base/fs.h"
#include "base/text.h"
#include "frontend/lexer.h"

#include <algorithm>
#include <cstdio>
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
    }
    return "Exception";
}

struct JobRunner {
    double parseSeconds = 0;

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

    /** Runs [body], timing it; a failure becomes the oracle's `(Failure ...)` text. */
    std::string timed(const std::function<std::string()>& body) {
        double start = nowSeconds();
        std::string result;
        try {
            Dumper d;
            result = body();
        } catch (const CompileFailure& failure) {
            Dumper d;
            d.raw("(Failure ");
            d.raw(failureClassName(failure.kind));
            d.raw(" ");
            d.quoted(failure.message);
            d.raw(")");
            result = std::move(d.out);
        }
        parseSeconds += nowSeconds() - start;
        return result + "\n";
    }

    void lex(const std::string& listPath) {
        for (const auto& [input, output] : readList(listPath)) {
            std::optional<std::string> source = readFile(input);
            if (!source) fail("cannot read '" + input + "'");
            std::string text = timed([&] {
                std::vector<Token> tokens = Lexer(*source).tokenize();
                Dumper d;
                dumpValue(d, tokens, 0);
                return std::move(d.out);
            });
            writeFile(output, text);
        }
    }

    void run(const std::string& line) {
        std::vector<std::string> words;
        for (const std::string& w : split(line, " ")) {
            if (!w.empty()) words.push_back(w);
        }
        if (words.empty()) return;
        const std::string& command = words[0];
        if (command == "reset") {
            // Fragment macros and InferredSites arrive with the parser.
        } else if (command == "chars") {
            // Only the oracle generates the tables.
        } else if (command == "lex") {
            lex(words.at(1));
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
    JobRunner runner;
    try {
        for (const std::string& raw : split(*job, "\n")) {
            std::string line = trim(raw);
            if (line.empty() || startsWith(line, "#")) continue;
            runner.run(line);
        }
    } catch (const CompileFailure& failure) {
        std::fprintf(stderr, "job failed: %s\n", failure.message.c_str());
        return 1;
    }
    std::printf("c++ round 1: parse %.3f s\n", runner.parseSeconds);
    return 0;
}

}  // namespace azora
