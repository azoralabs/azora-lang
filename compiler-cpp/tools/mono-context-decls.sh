#!/bin/sh
# Regenerates MonoContext's member declarations from its two source files.
cd "$(dirname "$0")/.." && exec python3 tools/member-decls.py src/semantic/mono_context.h MonoContext \
    src/semantic/mono_context.cpp src/semantic/mono_context_rewrite.cpp
