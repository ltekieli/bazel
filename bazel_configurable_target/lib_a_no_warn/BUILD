# Default content for the filesystem
filegroup(
    name = "default_content",
    srcs = ["message.txt"],
    visibility = ["//visibility:public"],
)

# Virtual target with warn_on_dropped_providers disabled
configurable_target(
    name = "content",
    default = ":default_content",
    warn_on_dropped_providers = False,
    visibility = ["//visibility:public"],
)
