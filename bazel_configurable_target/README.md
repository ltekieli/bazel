# Virtual Targets Example

Demonstrates the `configurable_target`, `override_target`, and `extend_target` Bazel features.

## Prerequisites

Build Bazel from the `lt_configurable_targets` branch of the sibling `bazel` repo:

```sh
cd ../bazel
bazel build //src:bazel
```

Set the path to the built Bazel:

```sh
BAZEL=$(realpath ../bazel/bazel-bin/src/bazel)
```

## Repository Structure

```
lib_a/   -- declares configurable_target "content" with a default filegroup
lib_b/   -- provides a custom_content target (used as override replacement)
lib_c/   -- extend_target(@lib_a//:content) with extra content

example_override/  -- root overrides @lib_a//:content with @lib_b//:custom_content
example_extend/    -- root with lib_a + lib_c (extensions merge with default)
example_default/   -- root with lib_a only (default used as-is)
```

Only the root module can use `override_target()`. Non-root modules
(like `lib_c`) use `extend_target()` to contribute to configurable targets.
Extensions are always applied, even when an override is present.

## Scenarios

### 1. Override + extensions (override replaces default, extensions merge on top)

The root module uses `override_target()` to replace `@lib_a//:content`
with `@lib_b//:custom_content`. Extensions from `lib_c` are merged on
top of the override.

```sh
cd example_override
$BAZEL build //:image
cat bazel-bin/image.txt
```

Expected output (override + extension merged):
```
This is the CUSTOM content from lib_b, overriding lib_a's default.
This is EXTRA content from lib_c, extending lib_a's default.
```

### 2. Extend (merges with default)

Only `lib_c` (extend) is present. The configurable target merges `DefaultInfo.files`
from the default and the extension via depset union.

```sh
cd example_extend
$BAZEL build //:image
cat bazel-bin/image.txt
```

Expected output (both files merged):
```
This is the DEFAULT content from lib_a.
This is EXTRA content from lib_c, extending lib_a's default.
```

### 3. Default (no override, no extensions)

Only `lib_a` is present. The configurable target resolves to its default.

```sh
cd example_default
$BAZEL build //:image
cat bazel-bin/image.txt
```

Expected output:
```
This is the DEFAULT content from lib_a.
```
