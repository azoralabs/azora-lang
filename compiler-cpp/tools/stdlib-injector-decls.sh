#!/bin/sh
# Regenerates StdlibInjector's member declarations from its four source files.
cd "$(dirname "$0")/.." && exec python3 tools/member-decls.py src/stdlib/stdlib_injector.h StdlibInjector \
    src/stdlib/stdlib_injector.cpp src/stdlib/stdlib_injector_validate.cpp \
    src/stdlib/stdlib_injector_inject.cpp src/stdlib/stdlib_injector_names.cpp
