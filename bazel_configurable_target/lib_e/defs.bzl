"""Rule that provides both DefaultInfo and OutputGroupInfo."""

def _extra_with_groups_impl(ctx):
    return [
        DefaultInfo(files = depset(ctx.files.srcs)),
        OutputGroupInfo(extras = depset(ctx.files.extras)),
    ]

extra_with_groups = rule(
    implementation = _extra_with_groups_impl,
    attrs = {
        "srcs": attr.label_list(allow_files = True),
        "extras": attr.label_list(allow_files = True),
    },
)
