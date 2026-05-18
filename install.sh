#!/bin/bash
set -euo pipefail

# Azora Language Installer
#
# Two modes of operation:
#   1. From source  - when run from the git repository (gradlew present)
#   2. From archive - when run from an extracted distribution (bin/ and lib/ present)

INSTALL_DIR="${AZORA_HOME:-$HOME/.azoralang}"
BIN_LINK_DIR="/usr/local/bin"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo ""
echo "  Azora Language Installer"
echo "  ========================"
echo ""

# ---------------------------------------------------------------------------
# Detect mode
# ---------------------------------------------------------------------------
if [ -f "$SCRIPT_DIR/gradlew" ]; then
    MODE="source"
    echo "  Mode:    Build from source"
elif [ -d "$SCRIPT_DIR/lib" ] && [ -d "$SCRIPT_DIR/bin" ]; then
    MODE="archive"
    echo "  Mode:    Install from distribution archive"
else
    echo "  Error: Cannot determine installation mode."
    echo ""
    echo "  Run this script from either:"
    echo "    - The azora-lang git repository  (contains gradlew)"
    echo "    - An extracted release archive    (contains bin/ and lib/)"
    exit 1
fi

echo "  Target:  $INSTALL_DIR"
echo ""

# ---------------------------------------------------------------------------
# Detect platform
# ---------------------------------------------------------------------------
OS="$(uname -s | tr '[:upper:]' '[:lower:]')"
ARCH="$(uname -m)"

case "$OS" in
    darwin)          PLATFORM_SUFFIX="macosx"   ;;
    linux)
        if [ -n "${ANDROID_ROOT:-}" ]; then
            PLATFORM_SUFFIX="android"
        else
            PLATFORM_SUFFIX="linux"
        fi
        ;;
    mingw*|msys*|cygwin*)
        echo "  For Windows, use install.ps1 or install.bat instead."
        exit 1
        ;;
    *) echo "  Unsupported OS: $OS"; exit 1 ;;
esac

case "$ARCH" in
    arm64|aarch64) ARCH_SUFFIX="arm64"  ;;
    x86_64|amd64)  ARCH_SUFFIX="x86_64" ;;
    *)             echo "  Unsupported architecture: $ARCH"; exit 1 ;;
esac

PLATFORM="${PLATFORM_SUFFIX}-${ARCH_SUFFIX}"
echo "  Platform: $PLATFORM"

# ---------------------------------------------------------------------------
# Check Java
# ---------------------------------------------------------------------------
if ! command -v java &>/dev/null; then
    echo ""
    echo "  Error: Java is not installed or not in PATH."
    echo "  Azora requires JDK 17 or later."
    echo ""
    if [ "$OS" = "darwin" ]; then
        echo "  Install with Homebrew:"
        echo "    brew install openjdk@17"
    else
        echo "  Install with your package manager:"
        echo "    sudo apt install openjdk-17-jdk      # Debian / Ubuntu"
        echo "    sudo dnf install java-17-openjdk      # Fedora"
        echo "    sudo pacman -S jdk17-openjdk           # Arch"
    fi
    exit 1
fi

JAVA_VERSION=$(java -version 2>&1 | head -1 | cut -d'"' -f2 | cut -d'.' -f1)
if [ "$JAVA_VERSION" -lt 17 ] 2>/dev/null; then
    echo ""
    echo "  Error: Java $JAVA_VERSION detected, but Azora requires JDK 17+."
    exit 1
fi
echo "  Java:     $JAVA_VERSION"

# ---------------------------------------------------------------------------
# Build from source (if applicable)
# ---------------------------------------------------------------------------
if [ "$MODE" = "source" ]; then
    echo ""
    echo "  Building Azora (this may take a minute)..."
    "$SCRIPT_DIR/gradlew" :app:installDist :build-tool:installDist -q

    DIST_BASE="$SCRIPT_DIR/app/build/install/azora"
    BT_DIST_BASE="$SCRIPT_DIR/build-tool/build/install/azora-build"
    INTERNAL_DIR="$SCRIPT_DIR/Internal"
    VERSION_SOURCE="source"
else
    DIST_BASE="$SCRIPT_DIR"
    BT_DIST_BASE="$SCRIPT_DIR"
    INTERNAL_DIR="$SCRIPT_DIR/Internal"
    VERSION_SOURCE="archive"
fi

# ---------------------------------------------------------------------------
# Clean previous installation
# ---------------------------------------------------------------------------
if [ -d "$INSTALL_DIR" ]; then
    echo "  Removing previous installation..."
    rm -rf "$INSTALL_DIR"
fi

mkdir -p "$INSTALL_DIR/bin"
mkdir -p "$INSTALL_DIR/lib"

# ---------------------------------------------------------------------------
# Copy JARs (filter out other-platform native JARs)
# ---------------------------------------------------------------------------
echo "  Copying libraries..."

copy_jars() {
    local src_dir="$1"
    for jar in "$src_dir/"*.jar; do
        [ -f "$jar" ] || continue
        local basename
        basename="$(basename "$jar")"

        # Skip platform-specific JARs that are not for this platform
        if echo "$basename" | grep -qE '(android|ios|linux|macosx|windows)-'; then
            if ! echo "$basename" | grep -q "$PLATFORM"; then
                continue
            fi
        fi

        # Deduplicate
        if [ ! -f "$INSTALL_DIR/lib/$basename" ]; then
            cp "$jar" "$INSTALL_DIR/lib/"
        fi
    done
}

if [ "$MODE" = "source" ]; then
    copy_jars "$DIST_BASE/lib"
    copy_jars "$BT_DIST_BASE/lib"
else
    copy_jars "$DIST_BASE/lib"
fi

# ---------------------------------------------------------------------------
# Copy Internal directory (stdlib, engine, tests)
# ---------------------------------------------------------------------------
if [ -d "$INTERNAL_DIR" ]; then
    echo "  Copying standard library..."
    cp -R "$INTERNAL_DIR" "$INSTALL_DIR/Internal"
    rm -rf "$INSTALL_DIR/Internal/Std/docs/node_modules" 2>/dev/null || true
    rm -rf "$INSTALL_DIR/Internal/Std/docs/dist" 2>/dev/null || true
fi

# ---------------------------------------------------------------------------
# Write VERSION
# ---------------------------------------------------------------------------
VERSION="unknown"
if [ "$VERSION_SOURCE" = "source" ]; then
    VERSION=$(grep 'const val VERSION' "$SCRIPT_DIR/build-config/src/commonMain/kotlin/dev/azora/lang/BuildConfig.kt" \
        | head -1 | sed 's/.*"\(.*\)".*/\1/')
elif [ -f "$SCRIPT_DIR/VERSION" ]; then
    VERSION=$(cat "$SCRIPT_DIR/VERSION")
fi
echo "$VERSION" > "$INSTALL_DIR/VERSION"

# ---------------------------------------------------------------------------
# Create wrapper scripts
# ---------------------------------------------------------------------------
cat > "$INSTALL_DIR/bin/azora" << 'WRAPPER'
#!/bin/bash
set -euo pipefail

AZORA_HOME="${AZORA_HOME:-$HOME/.azoralang}"

if [ ! -d "$AZORA_HOME" ]; then
    echo "Error: Azora is not installed. Expected installation at $AZORA_HOME" >&2
    exit 1
fi

CLASSPATH=""
for jar in "$AZORA_HOME/lib/"*.jar; do
    [ -f "$jar" ] || continue
    if [ -n "$CLASSPATH" ]; then
        CLASSPATH="$CLASSPATH:$jar"
    else
        CLASSPATH="$jar"
    fi
done

export AZORA_HOME

exec java \
    -cp "$CLASSPATH" \
    -Dazora.home="$AZORA_HOME" \
    -Dazora.internal="$AZORA_HOME/Internal" \
    ${AZORA_JAVA_OPTS:-} \
    dev.azora.lang.MainKt "$@"
WRAPPER

cat > "$INSTALL_DIR/bin/azora-build" << 'WRAPPER'
#!/bin/bash
set -euo pipefail

AZORA_HOME="${AZORA_HOME:-$HOME/.azoralang}"

if [ ! -d "$AZORA_HOME" ]; then
    echo "Error: Azora is not installed. Expected installation at $AZORA_HOME" >&2
    exit 1
fi

CLASSPATH=""
for jar in "$AZORA_HOME/lib/"*.jar; do
    [ -f "$jar" ] || continue
    if [ -n "$CLASSPATH" ]; then
        CLASSPATH="$CLASSPATH:$jar"
    else
        CLASSPATH="$jar"
    fi
done

export AZORA_HOME

exec java \
    -cp "$CLASSPATH" \
    -Dazora.home="$AZORA_HOME" \
    -Dazora.internal="$AZORA_HOME/Internal" \
    ${AZORA_JAVA_OPTS:-} \
    dev.azora.build.MainKt "$@"
WRAPPER

chmod +x "$INSTALL_DIR/bin/azora"
chmod +x "$INSTALL_DIR/bin/azora-build"

# ---------------------------------------------------------------------------
# Symlink into PATH
# ---------------------------------------------------------------------------
if [ -d "$BIN_LINK_DIR" ]; then
    echo "  Creating symlinks in $BIN_LINK_DIR..."
    if [ -w "$BIN_LINK_DIR" ]; then
        ln -sf "$INSTALL_DIR/bin/azora"       "$BIN_LINK_DIR/azora"
        ln -sf "$INSTALL_DIR/bin/azora-build" "$BIN_LINK_DIR/azora-build"
    else
        sudo ln -sf "$INSTALL_DIR/bin/azora"       "$BIN_LINK_DIR/azora"
        sudo ln -sf "$INSTALL_DIR/bin/azora-build" "$BIN_LINK_DIR/azora-build"
    fi
else
    echo ""
    echo "  Note: $BIN_LINK_DIR does not exist."
    echo "  Add Azora to your PATH manually:"
    echo ""
    echo "    export PATH=\"$INSTALL_DIR/bin:\$PATH\""
    echo ""
    echo "  Add that line to ~/.bashrc, ~/.zshrc, or ~/.profile."
fi

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
LIB_SIZE=$(du -sh "$INSTALL_DIR/lib" 2>/dev/null | cut -f1 || echo "?")

echo ""
echo "  Installation complete!"
echo ""
echo "  Version:       $VERSION"
echo "  azora          $INSTALL_DIR/bin/azora"
echo "  azora-build    $INSTALL_DIR/bin/azora-build"
echo "  Internal/      $INSTALL_DIR/Internal/"
echo "  Library size:  $LIB_SIZE"
echo ""
echo "  Get started:"
echo "    azora version"
echo "    azora run hello.az"
echo "    azora-build init"
echo ""
