#!/bin/sh
# port-drift.sh: which ported Kotlin files changed since they were ported
# (CPP_PORT_PLAN.md §12). Exits non-zero when any did.
#
#   tools/port-drift.sh                     list the changed files
#   tools/port-drift.sh --update <file>...  record the current hash of each file
#                                           (after porting its change)
here=$(cd "$(dirname "$0")/.." && pwd)
lang=$(cd "$here/.." && pwd)
ported="$here/PORTED.txt"
if [ "$1" = --update ]; then
    shift
    for file in "$@"; do
        hash=$(shasum -a 256 "$lang/$file" | cut -d' ' -f1)
        awk -v f="$file" -v h="$hash" '$2 == f { $1 = h; sub(/^[^ ]+ /, h "  ") } { print }' "$ported" > "$ported.new" \
            && mv "$ported.new" "$ported"
    done
    exit 0
fi
changed=0
while read -r hash file rest; do
    case "$hash" in '#'*|'') continue ;; esac
    if [ ! -f "$lang/$file" ]; then
        echo "GONE    $file"
        changed=1
    elif [ "$(shasum -a 256 "$lang/$file" | cut -d' ' -f1)" != "$hash" ]; then
        echo "CHANGED $file  ($rest)"
        changed=1
    fi
done < "$ported"
[ "$changed" -eq 0 ] && echo "no drift"
exit "$changed"
