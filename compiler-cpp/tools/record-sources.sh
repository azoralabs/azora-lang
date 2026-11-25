#!/bin/sh
# record-sources.sh: runs the Kotlin compiler suite with the Lexer's recorder
# hook on, saving every source the suite lexes to build/recorded/<sha256>.az
# (CPP_PORT_PLAN.md §13). parse-diff.sh then parses them as entries. Takes
# about as long as the suite (~5 min); rerun after the suite gains tests.
set -e
here=$(cd "$(dirname "$0")/.." && pwd)
lang=$(cd "$here/.." && pwd)
out="$here/build/recorded"
rm -rf "$out"
mkdir -p "$out"
cd "$lang"
# Test failures do not matter here: every source lexed is recorded either way.
AZORA_RECORD_SOURCES="$out" ./gradlew :compiler:desktopTest --offline --console=plain --rerun \
    > "$here/build/record-sources.log" 2>&1 || true
echo "$(ls "$out" | wc -l | tr -d ' ') sources recorded in $out"
