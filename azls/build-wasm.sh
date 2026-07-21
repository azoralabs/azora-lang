#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPOSITORY_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_FILE="$SCRIPT_DIR/src/main/azora/AzoraLanguageServer.az"
VERSION="${AZORA_VERSION:-0.0.4}"
OUTPUT_DIR="${1:-$SCRIPT_DIR/build/wasm/$VERSION}"
TEMP_DIR="$(mktemp -d)"

cleanup() {
    rm -rf "$TEMP_DIR"
}
trap cleanup EXIT

mkdir -p "$OUTPUT_DIR"

(
    cd "$REPOSITORY_ROOT"
    ./gradlew -q :app:run \
        --args="compile wasm ../azls/src/main/azora/AzoraLanguageServer.az --debug"
) > "$TEMP_DIR/azls.wat"

if ! head -n 1 "$TEMP_DIR/azls.wat" | grep -q '^(module'; then
    echo "AZLS compilation did not produce a WebAssembly text module." >&2
    cat "$TEMP_DIR/azls.wat" >&2
    exit 1
fi

if ! grep -q '(export "azlsDefinition"' "$TEMP_DIR/azls.wat"; then
    echo "AZLS WebAssembly exports are incomplete." >&2
    exit 1
fi

npx --yes -p wabt wat2wasm "$TEMP_DIR/azls.wat" -o "$OUTPUT_DIR/azls.wasm"
cp "$TEMP_DIR/azls.wat" "$OUTPUT_DIR/azls.wat"
cp "$SOURCE_FILE" "$OUTPUT_DIR/AzoraLanguageServer.az"

echo "Built Azora Language Server $VERSION in $OUTPUT_DIR"
