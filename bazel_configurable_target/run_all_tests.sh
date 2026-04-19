#!/bin/bash
# Runs all configurable_target example tests.
# Each example builds a verify_test target that diffs image.txt
# against expected.txt and fails the build on mismatch.
#
# Usage: ./run_all_tests.sh [/path/to/bazel]
#   If no path is given, "bazel" from $PATH is used.

set -euo pipefail

BAZEL="${1:-bazel}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
PASSED=0
FAILED=0
ERRORS=""

# All tests require --experimental_configurable_targets unless they test the
# behavior without it. Per-example overrides are read from bazel_flags.txt.
DEFAULT_FLAGS="--experimental_configurable_targets"

EXAMPLES=(
  example_default
  example_extend
  example_override
  example_override_only
  example_override_not_extensible
  example_multi_extend
  example_override_multi_extend
  example_extend_no_warn
  example_extend_merge_order
  example_extend_output_groups
  example_extend_dev_dep
  example_error_nonroot_override
  example_error_duplicate_override
  example_error_flag_disabled
  example_error_not_configurable
)

echo "========================================"
echo " configurable_target integration tests"
echo "========================================"
echo "Bazel: $BAZEL"
echo ""

for example in "${EXAMPLES[@]}"; do
  echo "----------------------------------------"
  echo " $example"
  echo "----------------------------------------"

  if ! cd "$SCRIPT_DIR/$example" 2>/dev/null; then
    echo "SKIP (directory not found)"
    continue
  fi

  BUILD_LOG=$(mktemp)

  # Read per-example flags from bazel_flags.txt, or use defaults.
  if [ -f bazel_flags.txt ]; then
    EXAMPLE_FLAGS=$(cat bazel_flags.txt)
  else
    EXAMPLE_FLAGS="$DEFAULT_FLAGS"
  fi

  # If expected_failure.txt exists, the build SHOULD fail and its output
  # must contain each line from the file.
  if [ -f expected_failure.txt ]; then
    if "$BAZEL" build $EXAMPLE_FLAGS //:verify_test >"$BUILD_LOG" 2>&1; then
      echo "FAIL: build succeeded but expected failure"
      FAILED=$((FAILED + 1))
      ERRORS="${ERRORS}\n  ${example}"
    else
      FAIL_OK=true
      while IFS= read -r line; do
        [ -z "$line" ] && continue
        if ! grep -qF "$line" "$BUILD_LOG"; then
          echo "FAIL: expected error not found: $line"
          FAIL_OK=false
        fi
      done < expected_failure.txt
      if "$FAIL_OK"; then
        echo "PASS (expected failure)"
        PASSED=$((PASSED + 1))
      else
        echo "FAIL (wrong error message)"
        FAILED=$((FAILED + 1))
        ERRORS="${ERRORS}\n  ${example}"
      fi
    fi
    rm -f "$BUILD_LOG"
    echo ""
    continue
  fi

  if "$BAZEL" build $EXAMPLE_FLAGS //:verify_test 2>&1 | tee "$BUILD_LOG" | tail -3; then
    cat bazel-bin/verify_result.txt

    # Check expected_warning.txt: each line must appear in build output.
    WARN_OK=true
    if [ -f expected_warning.txt ]; then
      while IFS= read -r line; do
        [ -z "$line" ] && continue
        if ! grep -qF "$line" "$BUILD_LOG"; then
          echo "FAIL: expected warning not found: $line"
          WARN_OK=false
        fi
      done < expected_warning.txt
    fi

    # Check unexpected_warning.txt: none of these lines may appear.
    if [ -f unexpected_warning.txt ]; then
      while IFS= read -r line; do
        [ -z "$line" ] && continue
        if grep -qF "$line" "$BUILD_LOG"; then
          echo "FAIL: unexpected warning found: $line"
          WARN_OK=false
        fi
      done < unexpected_warning.txt
    fi

    if "$WARN_OK"; then
      PASSED=$((PASSED + 1))
    else
      FAILED=$((FAILED + 1))
      ERRORS="${ERRORS}\n  ${example}"
    fi
  else
    echo "FAIL (build error)"
    FAILED=$((FAILED + 1))
    ERRORS="${ERRORS}\n  ${example}"
  fi
  rm -f "$BUILD_LOG"
  echo ""
done

echo ""
echo "========================================"
echo " Results: $PASSED passed, $FAILED failed"
echo "========================================"

if [ "$FAILED" -gt 0 ]; then
  printf "\nFailed tests:%b\n" "$ERRORS"
  exit 1
fi
