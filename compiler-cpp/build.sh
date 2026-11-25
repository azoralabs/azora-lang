#!/bin/sh
# make -j<cores> wrapper: build.sh [release|debug|asan|clean]
set -e
cd "$(dirname "$0")"
mode=${1:-release}
if [ "$mode" = clean ]; then
    exec /usr/bin/make clean
fi
exec /usr/bin/make -j"$(sysctl -n hw.ncpu)" BUILD="$mode"
