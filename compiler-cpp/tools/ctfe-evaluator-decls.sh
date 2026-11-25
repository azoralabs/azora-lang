#!/bin/sh
# Regenerates CtfeEvaluator's member declarations from its two source files.
cd "$(dirname "$0")/.." && exec python3 tools/member-decls.py src/semantic/ctfe_evaluator.h CtfeEvaluator \
    src/semantic/ctfe_evaluator.cpp src/semantic/ctfe_evaluator_fold.cpp
