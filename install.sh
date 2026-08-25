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

    # Language server for Azora Studio (auto-discovered at ~/.azora/azls/azls.jar)
    echo "  Installing the Azora Language Server (azls)..."
    "$SCRIPT_DIR/gradlew" :azls:installAzls -q

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
#
# A release archive ships a ready-made `Internal/`. The git repository keeps the
# standard library in `std/` instead, so a source install assembles the same
# `Internal/Std` layout from it. Tooling depends on these sources being present:
# the IDE plugin indexes `Internal/Std` for stdlib completion and navigation, so
# an install without them looks like a standard library with no symbols.
# ---------------------------------------------------------------------------
if [ -d "$INTERNAL_DIR" ]; then
    echo "  Copying standard library..."
    cp -R "$INTERNAL_DIR" "$INSTALL_DIR/Internal"
elif [ -d "$SCRIPT_DIR/std" ]; then
    echo "  Copying standard library (from std/)..."
    mkdir -p "$INSTALL_DIR/Internal"
    cp -R "$SCRIPT_DIR/std" "$INSTALL_DIR/Internal/Std"
else
    echo "  Warning: no standard library sources found; IDE tooling will not"
    echo "           be able to resolve std symbols."
fi
if [ -f "$SCRIPT_DIR/package.azon" ]; then
    mkdir -p "$INSTALL_DIR/Internal"
    cp "$SCRIPT_DIR/package.azon" "$INSTALL_DIR/Internal/package.azon"
fi
rm -rf "$INSTALL_DIR/Internal/Std/docs/node_modules" 2>/dev/null || true
rm -rf "$INSTALL_DIR/Internal/Std/docs/dist" 2>/dev/null || true

# ---------------------------------------------------------------------------
# Install the standard library where the compiler reads it
#
# `std/` sits beside `lib/` in the install prefix; the compiler resolves it from
# there (see AzStdlib's resolution order). This is the copy that is *compiled
# against* - `Internal/Std` above exists for IDE indexing. Shipping the sources
# is what makes the standard library readable and patchable without a compiler
# rebuild, so a missing tree is a hard failure rather than a warning.
# ---------------------------------------------------------------------------
if [ -d "$SCRIPT_DIR/std" ]; then
    STD_SRC="$SCRIPT_DIR/std"
elif [ -d "$INSTALL_DIR/Internal/Std" ]; then
    STD_SRC="$INSTALL_DIR/Internal/Std"
else
    echo "  Error: no std/ tree to install; the compiler would fall back to its"
    echo "         bundled copy and users could not read or patch the library."
    exit 1
fi
if [ -f "$SCRIPT_DIR/package.azon" ]; then
    STDLIB_MANIFEST_SRC="$SCRIPT_DIR/package.azon"
elif [ -f "$INSTALL_DIR/Internal/package.azon" ]; then
    STDLIB_MANIFEST_SRC="$INSTALL_DIR/Internal/package.azon"
else
    echo "  Error: package.azon is missing; the standard library package has no metadata."
    exit 1
fi
echo "  Installing standard library sources..."
rm -rf "$INSTALL_DIR/std"
cp -R "$STD_SRC" "$INSTALL_DIR/std"
cp "$STDLIB_MANIFEST_SRC" "$INSTALL_DIR/package.azon"

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
