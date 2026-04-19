# Extension with warn_on_dropped_providers=False on the configurable_target.
# No warning should be emitted about dropped providers.
genrule(
    name = "image",
    srcs = ["@lib_a//:content"],
    outs = ["image.txt"],
    cmd = "cat $(SRCS) > $@",
)

# Verify the image content matches expected.
# Fails the build with a diff if they don't match.
genrule(
    name = "verify_test",
    srcs = [":image", "expected.txt"],
    outs = ["verify_result.txt"],
    cmd = """
{
  echo "=== EXPECTED ==="
  cat $(location expected.txt)
  echo "=== ACTUAL ==="
  cat $(location :image)
  if diff $(location expected.txt) $(location :image) > /dev/null; then
    echo "=== PASS ==="
  else
    echo "=== FAIL ==="
    diff $(location expected.txt) $(location :image) || true
  fi
} > $@
diff $(location expected.txt) $(location :image)
""",
)
