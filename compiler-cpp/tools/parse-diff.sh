#!/bin/sh
# parse-diff.sh: proves the first slice (CPP_PORT_PLAN.md §13).
#
# Builds the C++ release binary, has the Kotlin oracle (AstOracle, a Gradle test)
# and `azora __parse-job` lex and parse every file of tests/corpus.txt, compares
# each pair of dumps with cmp, prints the mismatches and both timings, and exits
# non-zero on any difference.
#
#   tools/parse-diff.sh             one Kotlin round, one C++ round
#   REPEAT=5 tools/parse-diff.sh    five rounds each, for the speed report
#   SKIP_ORACLE=1 tools/parse-diff.sh   reuse the last Kotlin dumps
set -e
here=$(cd "$(dirname "$0")/.." && pwd)
lang=$(cd "$here/.." && pwd)
top=$(cd "$lang/.." && pwd)
work="$here/build/diff/parse"
repeat=${REPEAT:-1}

"$here/build.sh" release >/dev/null

mkdir -p "$work"
rm -rf "$work/cpp" "$work/lists"
[ -n "$SKIP_ORACLE" ] || rm -rf "$work/kotlin"
mkdir -p "$work/lists"

# Every .az under the given dirs, absolute, in byte order (= UTF-16 order for
# these ASCII paths), without build output.
sources() {
    for dir in "$@"; do
        [ -d "$dir" ] || continue
        find "$(cd "$dir" && pwd)" -name '*.az' -type f \
            -not -path '*/build/*' -not -path '*/.azora-build/*'
    done | LC_ALL=C sort
}

# Writes one list per side: `<input>\t<side dir>/<path below azora/>.ast`.
# The inputs come from `sources`, or from stdin when the name ends in -recorded.
write_lists() {
    name=$1; shift
    case "$name" in
        *-recorded) find "$(cd "$1" && pwd)" -name '*.az' -type f | LC_ALL=C sort > "$work/lists/$name.inputs" ;;
        *) sources "$@" > "$work/lists/$name.inputs" ;;
    esac
    for side in kotlin cpp; do
        while IFS= read -r input; do
            printf '%s\t%s/%s/%s.ast\n' "$input" "$work" "$side" "${input#"$top"/}"
        done < "$work/lists/$name.inputs" > "$work/lists/$name.$side"
    done
}

: > "$work/kotlin.job"
: > "$work/cpp.job"
echo reset >> "$work/kotlin.job"
echo reset >> "$work/cpp.job"
n=0
cd "$lang"
grep -v '^#' "$here/tests/corpus.txt" | while read -r command dirs; do
    [ -n "$command" ] || continue
    n=$((n + 1))
    case "$command" in
        std)
            root=$(cd "$dirs" && pwd)
            echo "std $root $work/kotlin/${root#"$top"/}" >> "$work/kotlin.job"
            echo "std $root $work/cpp/${root#"$top"/}" >> "$work/cpp.job"
            ;;
        libraries|entries)
            # shellcheck disable=SC2086
            write_lists "$n-$command" $dirs
            echo "$command $work/lists/$n-$command.kotlin" >> "$work/kotlin.job"
            echo "$command $work/lists/$n-$command.cpp" >> "$work/cpp.job"
            ;;
        recorded)
            if [ ! -d "$dirs" ]; then
                echo "no recorded sources in $dirs (tools/record-sources.sh); skipped" >&2
                continue
            fi
            write_lists "$n-recorded" "$dirs"
            echo "entries $work/lists/$n-recorded.kotlin" >> "$work/kotlin.job"
            echo "entries $work/lists/$n-recorded.cpp" >> "$work/cpp.job"
            ;;
        *) echo "corpus.txt: unknown command '$command'" >&2; exit 2 ;;
    esac
done

if [ -z "$SKIP_ORACLE" ]; then
    AZORA_ORACLE_JOB="$work/kotlin.job" AZORA_ORACLE_REPEAT=$repeat \
        ./gradlew :compiler:desktopTest --offline --console=plain --rerun \
        --tests 'org.azora.lang.oracle.AstOracle' > "$work/gradle.log" 2>&1 \
        || { tail -40 "$work/gradle.log"; exit 1; }
    grep 'oracle round' "$work/gradle.log" | sed 's/^ *//'
fi
AZORA_ORACLE_REPEAT=$repeat "$here/build/release/azora" __parse-job "$work/cpp.job"

total=0
bad=0
cd "$work/kotlin"
for file in $(find . -name '*.ast' -type f | LC_ALL=C sort); do
    total=$((total + 1))
    if ! cmp -s "$file" "$work/cpp/$file"; then
        bad=$((bad + 1))
        echo "DIFF ${file#./}"
    fi
done
extra=$(cd "$work/cpp" && find . -name '*.ast' -type f | while read -r f; do [ -f "$work/kotlin/$f" ] || echo "$f"; done)
[ -z "$extra" ] || { echo "only in C++:"; echo "$extra"; bad=$((bad + 1)); }
echo "$total files, $bad different"
[ "$bad" -eq 0 ]
