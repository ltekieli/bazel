# Default content for the filesystem
filegroup(
    name = "default_content",
    srcs = ["message.txt"],
    visibility = ["//visibility:public"],
)

# Virtual target: can be overridden by other modules
configurable_target(
    name = "content",
    default = ":default_content",
    visibility = ["//visibility:public"],
)
