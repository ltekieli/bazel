# This test expects a build failure — duplicate override_target() calls.
genrule(
    name = "image",
    srcs = ["@lib_a//:content"],
    outs = ["image.txt"],
    cmd = "cat $(SRCS) > $@",
)

genrule(
    name = "verify_test",
    srcs = [":image"],
    outs = ["verify_result.txt"],
    cmd = "echo ok > $@",
)
