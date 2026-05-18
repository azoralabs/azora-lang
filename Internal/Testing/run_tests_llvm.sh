#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$PROJECT_ROOT"

echo "=== LLVM IR Code Generator Tests ==="
echo ""
echo "--- Unit Tests ---"
./gradlew :azora-sdk:script:desktopTest --tests "com.doublegarts.azora.script.codegen.LlvmIrCodeGeneratorTest" "$@"

echo ""
echo "--- Integration Tests (Internal/Testing .az files) ---"
./gradlew :azora-sdk:script:desktopTest --tests "com.doublegarts.azora.script.codegen.InternalAzLlvmCodegenRunner" "$@"
