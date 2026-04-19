# This test expects a build failure — extend_target() on a non-configurable_target.
filegroup(
    name = "extra_content",
    srcs = ["extra.txt"],
    visibility = ["//visibility:public"],
)

genrule(
    name = "image",
    srcs = ["@lib_b//:custom_content"],
    outs = ["image.txt"],
    cmd = "cat $(SRCS) > $@",
)

genrule(
    name = "verify_test",
    srcs = [":image"],
    outs = ["verify_result.txt"],
    cmd = "echo ok > $@",
)
