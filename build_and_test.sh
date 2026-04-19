#!/bin/bash
# Builds Bazel from source, then runs all configurable_target integration tests.
#
# Usage: ./build_and_test.sh
#
# The script expects to live in the root of the Bazel source tree,
# with the examples under bazel_configurable_target/.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "========================================"
echo " Building Bazel"
echo "========================================"
cd "$REPO_ROOT"
bazel build //src:bazel
BAZEL="$REPO_ROOT/bazel-bin/src/bazel"
echo "Built: $BAZEL"
echo ""

exec "$REPO_ROOT/bazel_configurable_target/run_all_tests.sh" "$BAZEL"
