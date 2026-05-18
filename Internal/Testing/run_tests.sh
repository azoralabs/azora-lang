#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

cd "$PROJECT_ROOT"

echo "Running all .az tests from Internal/Testing..."
echo ""

./gradlew :azora-sdk:script:cleanDesktopTest :azora-sdk:script:desktopTest --tests "com.doublegarts.azora.script.InternalAzTestRunner" --no-build-cache "$@"
