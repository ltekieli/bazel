# Default content for the filesystem
filegroup(
    name = "default_content",
    srcs = ["message.txt"],
    visibility = ["//visibility:public"],
)

# Virtual target with explicit merge_order
configurable_target(
    name = "content",
    default = ":default_content",
    merge_order = "preorder",
    visibility = ["//visibility:public"],
)
