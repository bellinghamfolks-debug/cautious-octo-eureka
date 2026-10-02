#!/usr/bin/env bash
# Checks everything that does not need a Mac: the C++ DSP core (normal and
# with AddressSanitizer + UndefinedBehaviorSanitizer), the Arabic/English text
# tables, and that the committed audio fixtures match their generator.
set -euo pipefail
cd "$(dirname "$0")/.."

cmake -S Core -B build/core -DCMAKE_BUILD_TYPE=Release
cmake --build build/core -j
./build/core/maqam_core_tests

if [[ "$(uname)" == "Linux" ]]; then
  cmake -S Core -B build/core-asan -DCMAKE_BUILD_TYPE=RelWithDebInfo \
    -DCMAKE_CXX_FLAGS="-fsanitize=address,undefined -fno-omit-frame-pointer -fno-sanitize-recover=all"
  cmake --build build/core-asan -j
  ./build/core-asan/maqam_core_tests
fi

python3 tools/check_localization.py

python3 tools/make_fixtures.py >/dev/null
git diff --exit-code -- Tests/Fixtures
