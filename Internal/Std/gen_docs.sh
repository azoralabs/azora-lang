#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DOCS_DIR="$SCRIPT_DIR/docs"

echo "=== Azora Std Documentation Generator ==="

# Step 1: Extract documentation from .az files
echo "[1/3] Extracting documentation..."
node "$DOCS_DIR/extract-docs.mjs"

# Step 2: Install dependencies if needed
if [ ! -d "$DOCS_DIR/node_modules" ]; then
  echo "[2/3] Installing dependencies..."
  (cd "$DOCS_DIR" && npm install)
else
  echo "[2/3] Dependencies already installed."
fi

# Step 3: Build the documentation site
echo "[3/3] Building documentation site..."
(cd "$DOCS_DIR" && npx vite build)

echo ""
echo "Documentation generated in $DOCS_DIR/dist/"
echo "Run 'cd $DOCS_DIR && npx vite preview' to view."
