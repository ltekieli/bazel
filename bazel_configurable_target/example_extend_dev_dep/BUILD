# Extension with dev_dependency=True on extend_target.
# Since lib_f's extend_target is a dev dependency and lib_f is not the
# root module, the extension should be suppressed — only default content.
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
