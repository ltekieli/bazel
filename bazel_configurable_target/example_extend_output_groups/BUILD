# Extension with output groups.
# The configurable target merges both DefaultInfo.files and OutputGroupInfo.

# DefaultInfo merge: default content + lib_e extra
genrule(
    name = "image",
    srcs = ["@lib_a//:content"],
    outs = ["image.txt"],
    cmd = "cat $(SRCS) > $@",
)

# Extract the "extras" output group from the configurable target
filegroup(
    name = "og_files",
    srcs = ["@lib_a//:content"],
    output_group = "extras",
)

# Materialize the output group files
genrule(
    name = "image_extras",
    srcs = [":og_files"],
    outs = ["image_extras.txt"],
    cmd = "cat $(SRCS) > $@",
)

# Verify both DefaultInfo and OutputGroupInfo content
genrule(
    name = "verify_test",
    srcs = [":image", ":image_extras", "expected.txt", "expected_extras.txt"],
    outs = ["verify_result.txt"],
    cmd = """
{
  echo "=== EXPECTED (DefaultInfo) ==="
  cat $(location expected.txt)
  echo "=== ACTUAL (DefaultInfo) ==="
  cat $(location :image)
  if diff $(location expected.txt) $(location :image) > /dev/null; then
    echo "=== PASS (DefaultInfo) ==="
  else
    echo "=== FAIL (DefaultInfo) ==="
    diff $(location expected.txt) $(location :image) || true
  fi
  echo ""
  echo "=== EXPECTED (OutputGroupInfo extras) ==="
  cat $(location expected_extras.txt)
  echo "=== ACTUAL (OutputGroupInfo extras) ==="
  cat $(location :image_extras)
  if diff $(location expected_extras.txt) $(location :image_extras) > /dev/null; then
    echo "=== PASS (OutputGroupInfo) ==="
  else
    echo "=== FAIL (OutputGroupInfo) ==="
    diff $(location expected_extras.txt) $(location :image_extras) || true
  fi
} > $@
diff $(location expected.txt) $(location :image)
diff $(location expected_extras.txt) $(location :image_extras)
""",
)
