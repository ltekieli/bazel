# Design Document: Configurable Targets

**Author:** TBD

**Date:** 2026-04-16

**Status:** Draft

**Reviewers:** TBD

---

## Abstract

This document proposes a native Bazel mechanism called **configurable targets**
that allows a module to declare targets whose implementation can be
**overridden** (fully replaced) or **extended** (composed with additional
contributions) by other modules in the dependency graph. Resolution happens
at analysis time, so the declaring module can consume its own configurable
targets transparently. The mechanism is analogous to Yocto's variable override
system (`override_target` ≈ direct assignment, `extend_target` ≈ `:append`)
and fills a gap in Bazel's
current composition model: there is no
native way for one module to inject or customize a target defined by another
module without reimplementing the entire build subgraph.

---

## Background and Motivation

### The Problem

Consider a module `filesystem` that defines a set of rules and targets to
build an embedded Linux root filesystem. The filesystem is assembled from
many components: base system files, init scripts, configuration, and
application binaries. Another module `my_product` depends on `filesystem`
and wants to:

1. **Add** extra files to the filesystem (extend).
2. **Replace** the default init system with a custom one (override).

Today, `my_product` has no clean way to do this. The available workarounds
are:

- **Fork and modify** the `filesystem` module. Defeats the purpose of
  modular reuse.
- **Reimplement** the build targets in `my_product`. Duplicates complex
  build logic.
- **Module extensions** that aggregate contributions. A module extension
  can collect contributions from other modules, but the module that
  *defines* the extension cannot use the aggregated result in its own
  `BUILD` targets. This makes it impossible for `filesystem` to both
  define the extension *and* consume the collected output in its own
  build rules.

### Analogy: Yocto Variable Overrides

In Yocto/BitBake, recipes define [variables][yocto-variables] like
`SRC_URI`, `RDEPENDS`, and `EXTRA_OEMAKE`. Other [layers][yocto-layers]
can **override** (assign) or **extend** ([`:append`][yocto-overrides],
`:remove`) these variables without modifying the original recipe:

```bitbake
# In a higher-priority layer
SRC_URI:append = " file://custom-init.sh"
RDEPENDS:${PN}:remove = "sysvinit"
RDEPENDS:${PN}:append = " systemd"
```

This works because Yocto's variables are mutable and evaluated lazily —
the override and append operations happen at the variable level, not by
replacing entire recipes. Bazel targets are immutable and eagerly defined,
so a different mechanism is needed to achieve similar per-target
composability. Configurable targets bring this variable-level override/append
pattern to Bazel: `override_target` ≈ assignment, `extend_target` ≈
`:append`, and the module dependency graph determines priority (analogous
to [layer priority][yocto-layers] in Yocto).

[yocto-variables]: https://docs.yoctoproject.org/ref-manual/variables.html
[yocto-layers]: https://docs.yoctoproject.org/dev-manual/layers.html
[yocto-overrides]: https://docs.yoctoproject.org/bitbake/bitbake-user-manual/bitbake-user-manual-metadata.html#appending-and-prepending-override-style-syntax

### Why Existing Bazel Mechanisms Are Insufficient

| Mechanism | Limitation |
|-----------|-----------|
| **Module extensions** | A module extension can collect contributions from other modules, but the module that *defines* the extension cannot use the aggregated result in its own `BUILD` targets. For example, `filesystem` could define an extension that lets other modules contribute files, but `filesystem`'s own build rules have no way to depend on the collected output. |
| **`label_flag` / `label_setting`** | The closest viable alternative — a `label_flag` can redirect a dependency via `--@filesystem//:rootfs_impl=//product:custom_rootfs`, which works in both WORKSPACE and bzlmod. However, it only supports single-target override (one flag = one label), so there is no way for multiple modules to independently contribute extensions. Overrides are passed as command-line flags or in `.bazelrc`, not declared in `MODULE.bazel`, so the composition is not part of the module graph. |

---

## Proposed Design

### Overview

The design introduces three new constructs:

1. **`configurable_target()` rule** -- a native rule declared in a BUILD file
   that acts as a configurable proxy. It has a default implementation
   that can be overridden or extended by other modules.

2. **`override_target()` directive** -- a MODULE.bazel directive that
   declares a full replacement of a configurable target.

3. **`extend_target()` directive** -- a MODULE.bazel directive that
   declares additional contributions to a configurable target, merged via
   built-in depset union of `DefaultInfo.files`, `RunfilesProvider`, and
   `OutputGroupInfo`.

Resolution happens during the **analysis phase**. Consumers of the configurable
target (including the declaring module itself) simply reference it by its
label and receive the resolved implementation transparently.

### Why Directives Live in MODULE.bazel, Not BUILD Files

Override and extension declarations must be in MODULE.bazel rather than
BUILD files. This is a consequence of Bazel's lazy loading architecture.

**The discoverability problem:** When Bazel analyzes a `configurable_target`
in Module A, it needs to know about all overrides and extensions from
other modules. However, BUILD files are loaded **on demand** — only when
something depends on targets in that package. There is no dependency edge
from Module A's configurable target to Module B's override declaration; the
relationship is in the reverse direction. If the override were declared
in a BUILD file, it would never be loaded unless some other target
happened to depend on that package.

MODULE.bazel files, by contrast, are **always processed** for every
module in the dependency graph during module resolution, which happens
before any BUILD file is loaded. This makes them the natural place for
global registrations that must be visible across the entire build.

This is the same reason `register_toolchains()` is a MODULE.bazel
directive: the toolchain resolution machinery needs to know about all
registered toolchains before analyzing any target, and it cannot rely on
BUILD files being loaded in the right order.

**The split between MODULE.bazel and BUILD:**

- **MODULE.bazel** declares the **wiring**: "I want to override/extend
  configurable target X with target Y." This is the registration step —
  lightweight, always visible.
- **BUILD files** define the **implementation**: "Here is what target Y
  actually builds." This is the actual build logic — loaded on demand
  when the resolved configurable target is analyzed.

```python
# MODULE.bazel — registration (always processed, globally visible)
override_target(
    target = "@filesystem//:rootfs",
    replacement = "//product:custom_rootfs",  # label pointing to a BUILD target
    extensible = True,                        # allow extensions on top (default)
)

# product/BUILD — implementation (loaded when the configurable target resolves)
assemble_filesystem(
    name = "custom_rootfs",
    ...
)
```

The only alternative would be an "eager scan" that loads all BUILD files
in the dependency graph upfront to discover override declarations. This
would be a major performance regression and a fundamental break with
Bazel's demand-driven loading architecture.

### Conceptual Flow

```
  Module A (declares):
    configurable_target(name = "rootfs", default = ":rootfs_default")

  Module B (extends):
    extend_target(target = "@A//:rootfs", extra = "//my_module:extra_configs")

  Root module (overrides):
    override_target(target = "@A//:rootfs", replacement = "//my_module:custom_rootfs",
                     extensible = True)  # default; set False to suppress extensions

  Resolution (analysis time):
    1. Determine base: override target if present, otherwise default target
    2. If override has extensible = False: forward all providers from base (pure alias)
    3. If extensions exist: merge DefaultInfo.files from base + all extensions
    4. If no extensions:    forward all providers from base (pure alias)
```

---

## Detailed Design

### 1. The `configurable_target()` Rule

A new **native rule** that acts as a configurable alias, resolved at
analysis time based on the module graph.

```python
configurable_target(
    name = "rootfs",
    default = ":rootfs_default",          # Label: the default implementation
    merge_order = "default",              # Depset order: "default", "preorder", "postorder", "topological"
    warn_on_dropped_providers = True,     # Warn when extensions are merged (providers are dropped)
)
```

**Attributes:**

| Attribute | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Target name |
| `default` | label | yes | Default implementation target |
| `merge_order` | string | no | Controls the depset order used when merging extension files. Values: `"default"` (stable order), `"preorder"`, `"postorder"`, `"topological"`. Default: `"default"`. |
| `warn_on_dropped_providers` | bool | no | When `True` (default), emits a generic warning per configurable target during analysis noting that only `DefaultInfo.files`, `RunfilesProvider`, and `OutputGroupInfo` from extension targets are used and other providers are dropped. |

**Behavior at analysis time:**

- If no override or extension is registered: forwards all providers from
  `default`.
- If an override is registered with `extensible = True` (default):
  replaces the default target. Extensions are still applied on top of the
  override (merged via depset union). If no extensions exist, the override
  acts as a pure alias.
- If an override is registered with `extensible = False`: replaces the
  default target and **suppresses all extensions**. The override acts as
  a pure alias regardless of any `extend_target()` declarations.
- If extensions are registered (and no override): merges
  `DefaultInfo.files` from the default and all extension targets
  (filegroup-like depset union).

The forwarding mechanism follows the `AliasConfiguredTarget` pattern:
the configurable target acts as a transparent proxy, forwarding all providers
from the resolved implementation.

### 2. Extension Merging (Built-in)

When extensions are registered via `extend_target()`, the `configurable_target`
rule performs a **filegroup-like merge** at analysis time: it creates a
union of `DefaultInfo.files` and `OutputGroupInfo` from the default target
and all extension targets. No user-defined composer function is needed.

This covers the most common extension case — aggregating files from
multiple modules. More complex composition strategies (e.g.,
concatenating configs, merging JSON, or custom provider merging)
are out of scope for the initial design and may be addressed by a
future custom composer mechanism.

**Merge behavior:**
- `DefaultInfo.files`: depset union of default + all extensions. The
  depset order is controlled by the `merge_order` attribute on the
  `configurable_target` rule (default: `"default"` = stable insertion order).
- `RunfilesProvider`: depset union of runfiles from the default/override
  base and all extension targets, so that consumers get the combined
  runfiles of all contributors. When a target or extension does not
  provide `RunfilesProvider`, its `FileProvider.getFilesToBuild()` files
  are added as transitive artifacts to the merged runfiles instead.
- `OutputGroupInfo`: depset union per output group name across default +
  all extensions. Uses the same `merge_order` as `DefaultInfo.files`.
  The `"default"` output group is excluded from `OutputGroupInfo`
  merging — it is already handled via `DefaultInfo.files` /
  `setFilesToBuild`. Output groups that only appear in some targets
  are still included (the union covers all group names across all
  sources).
- Other providers from the base target (default or override) are
  **forwarded as-is**. Providers from extension targets other than
  `DefaultInfo`, `RunfilesProvider`, and `OutputGroupInfo` are **dropped**.
  When `warn_on_dropped_providers` is `True` (default), a generic warning
  is emitted **once per configurable target** during analysis noting that
  only `DefaultInfo.files`, `RunfilesProvider`, and `OutputGroupInfo` from
  extensions are used. If an extension needs to contribute other
  providers (e.g., `CcInfo`), a future custom composer mechanism
  could address this use case.
- **Important:** When extensions are present, the configurable target
  constructs a new `DefaultInfo` (the merged depset). This means
  that even the base target's `DefaultInfo.default_outputs` may
  differ from the merged result. Consumers that depend on exact
  `DefaultInfo` structure should be aware of this.

**When an override is present:** The override replaces the default as
the base target. If the override has `extensible = True` (default),
extensions are still merged on top of the override via depset union.
If the override has `extensible = False`, all extensions are suppressed
and the override acts as a pure alias forwarding all providers.
If no extensions exist, the override acts as a pure alias regardless
of the `extensible` flag.

### 3. MODULE.bazel Directives

#### `override_target()`

Declares a full replacement of a configurable target.

```python
override_target(
    target = "@filesystem//:rootfs",     # Label of the configurable_target to override
    replacement = "//my_module:custom_rootfs",  # Label of the replacement target
    extensible = True,                    # Whether extensions still apply (default: True)
    dev_dependency = False,               # If True, ignored when --ignore_dev_dependency is set
)
```

**Semantics:**
- Only the **root module** can use `override_target()`. Non-root modules
  should use `extend_target()` to contribute to configurable targets.
- The replacement target must provide at least the same providers as the
  original default (or consumers will fail at analysis time with a clear
  error).
- Since only the root module can override, there are no multi-override
  conflicts to resolve.
- When `extensible = True` (default): extensions registered via
  `extend_target()` are still applied on top of the override.
- When `extensible = False`: all extensions are suppressed. The override
  acts as a complete replacement, and no `extend_target()` contributions
  are merged. This is useful when the override is a fully self-contained
  replacement that should not be modified by other modules.
- When `dev_dependency = True`: the override is ignored when
  `--ignore_dev_dependency` is enabled (same semantics as `bazel_dep`'s
  `dev_dependency`).

#### `extend_target()`

Declares additional contributions to a configurable target.

```python
extend_target(
    target = "@filesystem//:rootfs",       # Label of the configurable_target to extend
    extra = "//my_module:extra_configs",     # Label of the extension target
    dev_dependency = False,                  # If True, ignored when not root or --ignore_dev_dependency is set
)
```

**Semantics:**
- Multiple modules can extend the same configurable target. Extensions are
  aggregated in the iteration order of the resolved dependency graph
  (`depGraph`), which follows the BFS-based insertion order established
  by bzlmod's module resolution. The resolution code relies on this
  implicit ordering rather than performing an explicit BFS traversal.
- A module can only extend a configurable target in a module it transitively
  depends on via `bazel_dep`. The `@module` label in the `target`
  attribute must be resolvable in the extending module's repository
  mapping — this is guaranteed by the `bazel_dep` relationship. An
  `extend_target()` referencing an unknown module label is an error
  during module resolution.
- The **root module** can use both `override_target()` and
  `extend_target()` on the same configurable target. In this case, the
  override replaces the default, and the root module's extension is
  merged on top alongside any other extensions.
- Extensions are aggregated: all modules' contributions are collected
  into a single list.
- Merging is **built-in**: `DefaultInfo.files`, `RunfilesProvider`, and
  `OutputGroupInfo` from the default and all extensions are combined via
  depset union (filegroup-like behavior). Other providers are dropped
  from extension targets.
- An override **replaces the default**: extensions are still applied
  on top of the override target via depset union.
- **Caveat:** Extensions are unaware of whether the base target has been
  overridden. Extension authors should not assume specific properties
  of the base implementation, as it may be replaced by the root module.
  Extensions that contribute standalone files (configs, binaries) are
  safe; extensions that depend on internal structure of the base target
  are fragile and should be avoided.
- When `dev_dependency = True`: the extension is ignored when dev
  dependencies are suppressed (i.e., the current module is not the root
  module, or `--ignore_dev_dependency` is enabled).

### 4. Resolution Semantics

#### Priority Model

```
Priority (highest to lowest):
  1. Root module override (only root can override)
  2. Extensions (all applied, aggregated in BFS order)
  3. Default implementation
```

#### Conflict Detection

| Situation | Behavior |
|-----------|----------|
| Override (root module), `extensible = True`, extensions present | Override replaces default; extensions merge on top via depset union |
| Override (root module), `extensible = True`, no extensions | Override acts as a pure alias |
| Override (root module), `extensible = False` | Override replaces default; all extensions suppressed (pure alias) |
| No override, extensions present | Default used as base; extensions merge on top via depset union |
| Multiple extensions | All applied in BFS order |
| No override, no extensions | Default used as a pure alias |

#### Cycle Prevention

A configurable target's override/extension targets must not transitively depend
on the configurable target itself. This would create a dependency cycle. The
resolution function detects this by tracing the **full transitive closure**
of the override/extension target's dependencies — not just immediate deps.

For example, if `@A//:vt` is overridden with `//product:custom`, and
`//product:custom` depends on `@A//:other`, which in turn depends on
`@A//:vt`, this is a cycle even though `//product:custom` does not
directly reference `@A//:vt`. The cycle is detected during analysis
when the configured target graph is constructed, and reported as an
error with the full cycle path.

**Note:** Cycles through the configurable target's `default` attribute are
always safe (the default is only used when no override is present).
Cycle detection only applies to the resolved override/extension targets.

#### Interaction with `multiple_version_override`

When `multiple_version_override` is used, multiple versions of the same
module coexist in the dependency graph (e.g., `filesystem@1.0` and
`filesystem@2.0`). Each version is a separate canonical repo with its
own targets. If both versions declare a `configurable_target` with the same
name, these are **distinct configurable targets** (`@@filesystem~1.0//:rootfs`
vs `@@filesystem~2.0//:rootfs`).

`override_target()` and `extend_target()` use apparent labels
(`@filesystem//:rootfs`), which are resolved using each module's own
repository mapping. This means:

- A module that depends on `filesystem@1.0` and calls
  `extend_target(target = "@filesystem//:rootfs", ...)` extends only
  the `@1.0` configurable target.
- A module that depends on `filesystem@2.0` extends only the `@2.0`
  configurable target.
- The root module's `override_target()` applies to whichever version
  its own `bazel_dep(name = "filesystem")` resolves to — the other
  version's configurable target remains unaffected.

This is consistent with how all apparent label resolution works in
bzlmod. No special handling is needed.

#### Error Cases

The following error conditions are detected and reported with clear
error messages:

| Error | When Detected | Message |
|-------|---------------|---------|
| `override_target()` used in a non-root module | Module file parsing | "override_target() is only allowed in the root module. Non-root modules can use extend_target() to contribute to configurable targets." |
| `override_target()` or `extend_target()` targets a label that is not a `configurable_target` rule | Analysis time | "Label @foo//:bar is not a configurable_target. override_target()/extend_target() can only reference configurable_target rules." |
| Multiple `override_target()` calls for the same configurable target in the root module | Module file parsing | "multiple overrides for configurable target @foo//:bar found: //old:target and //new:target" |
| `extend_target()` references a module not in the dependency graph | Label resolution (module resolution) | "invalid label in extend_target in module foo@1.0" (underlying cause: unresolvable repo name) |
| Override/extension target not visible to the declaring module's package | Analysis time | "Target //product:custom is not visible to @foo//:bar. Override/extension targets must be visible to the configurable target's package." |
| Override/extension target causes a dependency cycle | Analysis time | "Dependency cycle detected: @foo//:bar -> //product:custom -> @foo//:other -> @foo//:bar" |
| Override target does not provide required providers | Analysis time | "Override target //product:custom does not provide FooInfo, which is required by consumers of @foo//:bar." |
| Extension target has empty `DefaultInfo.files` | Analysis time | **No error** — this is a valid no-op. The extension simply contributes no files to the merged depset. A debug message is logged at `--verbosity=DEBUG` level. |

### 5. Implementation Architecture

The implementation follows the `label_flag` / `LateBoundAlias` pattern:
registration in MODULE.bazel, collection during module resolution,
injection into build configuration, resolution via late-bound attributes
during analysis.

#### 5.1 MODULE.bazel Processing (`ModuleFileGlobals.java`)

Two new directives are added:

```java
@StarlarkMethod(name = "override_target", ...)
public void overrideTarget(String target, String replacement, boolean extensible, ...) {
    context.addConfigurableTargetOverride(targetLabel, replacementLabel, extensible);
}

@StarlarkMethod(name = "extend_target", ...)
public void extendTarget(String target, String extra, ...) {
    context.addConfigurableTargetExtension(targetLabel, extraLabel);
}
```

The `context` object is a `ModuleThreadContext`, which is the thread-local
context for MODULE.bazel evaluation. It collects directives into mutable
maps (`configurableTargetOverrides` and `configurableTargetExtensions`)
and enforces constraints such as rejecting duplicate overrides for the
same configurable target. When MODULE.bazel evaluation completes,
`ModuleThreadContext.buildModule()` copies the collected directives into
an immutable `InterimModule`.

#### 5.2 Module Data Model (`ModuleBase.java`)

Fields carry configurable target directives through the module resolution
pipeline. Overrides use `ConfigurableTargetOverrideSpec` to carry both the
replacement label and the `extensible` flag:

```java
public abstract ImmutableMap<String, ConfigurableTargetOverrideSpec> getConfigurableTargetOverrides();
public abstract ImmutableMap<String, ImmutableList<String>> getConfigurableTargetExtensions();
```

#### 5.3 Override/Extension Resolution (`BazelDepGraphFunction.java`)

String labels are resolved to canonical `Label` objects using each
module's `RepositoryMapping` via `LabelConverter`. Override specs
(`ConfigurableTargetOverrideSpec`) are converted to `ConfigurableTargetOverrideInfo`
records carrying canonical labels and the `extensible` flag.

Results are stored in `BazelDepGraphValue`:

```java
public abstract ImmutableMap<Label, ConfigurableTargetOverrideInfo> getConfigurableTargetOverrides();
public abstract ImmutableMap<Label, ImmutableList<Label>> getConfigurableTargetExtensions();
```

Since only the root module can call `override_target()`, only one override per
configurable target is possible; a duplicate is reported as an error during module
resolution (see Error Cases). Extensions aggregate all contributions in
BFS order.

#### 5.4 Configuration Injection (`BuildConfigurationFunction.java`)

`BuildConfigurationFunction` reads overrides and extensions from
`BazelDepGraphValue` and passes them to `BuildConfigurationValue`,
making them available to all rules during analysis.

**Scaling consideration:** Configurable target mappings are stored in
`BuildConfigurationValue`, which means they are part of every
configured target's configuration key. This has two implications:

1. **Memory:** Each configurable target mapping adds to the configuration
   object size. For projects with a small number of configurable targets
   (expected common case), this is negligible. Projects with hundreds
   of configurable targets should be aware of the overhead.
2. **Cache invalidation:** Any change to configurable target mappings
   (adding/removing an override or extension) changes the
   configuration identity (`equals()` / `hashCode()` include the
   override and extension maps), which invalidates **all** configured
   targets in the build graph, forcing re-analysis. This is the same
   trade-off made by `label_flag`.

This is acceptable for the initial implementation. If configurable targets
see widespread adoption with large mapping counts, a future
optimization could move resolution to a separate SkyFunction
(`ConfigurableTargetResolutionFunction`) that only `configurable_target` rules
depend on, decoupling the mappings from the general build
configuration.

#### 5.5 Analysis-Time Resolution (`ConfigurableTarget.java`)

The rule uses two late-bound attributes and two user-facing attributes:

- **`:alias`** (`LabelLateBoundDefault`): resolves to override target
  or falls back to `default` attribute.
- **`:extensions`** (`LabelListLateBoundDefault`): resolves to the
  list of extension targets from configuration. Returns empty list
  when the override has `extensible = False`.
- **`merge_order`** (string): controls the `NestedSet` order used for
  merging extension files. Values: `"default"`, `"preorder"`,
  `"postorder"`, `"topological"`.
- **`warn_on_dropped_providers`** (bool): when `True` (default), emits
  a warning when extensions are merged.

```java
// Override resolution
static final LabelLateBoundDefault<BuildConfigurationValue> RESOLVED = ...
    (rule, attributes, configuration) -> {
        ConfigurableTargetOverrideInfo override =
            configuration.getConfigurableTargetOverrides().get(rule.getLabel());
        return override != null ? override.replacement() : attributes.get("default", LABEL);
    };

// Extension resolution (respects extensible flag)
static final LabelListLateBoundDefault<BuildConfigurationValue> EXTENSIONS = ...
    (rule, attributes, configuration) -> {
        ConfigurableTargetOverrideInfo override =
            configuration.getConfigurableTargetOverrides().get(rule.getLabel());
        if (override != null && !override.extensible()) {
            return ImmutableList.of();  // extensions suppressed
        }
        ImmutableList<Label> extensions =
            configuration.getConfigurableTargetExtensions().get(rule.getLabel());
        return extensions != null ? extensions : ImmutableList.of();
    };
```

The factory merges `DefaultInfo.files`, `RunfilesProvider` (runfiles),
and `OutputGroupInfo` from `:alias` + `:extensions` when extensions are
present, using the `merge_order` attribute to control depset order for
`DefaultInfo.files` and `OutputGroupInfo`. Runfiles are merged via
standard runfiles merging (depset union of the runfiles trees from the
base and all extension targets); targets lacking `RunfilesProvider`
contribute their `FileProvider.getFilesToBuild()` files as transitive
artifacts instead. The `"default"` output group is excluded from
`OutputGroupInfo` merging (already handled by `setFilesToBuild`).
When no extensions are present, it delegates to `AliasConfiguredTarget`
(pure alias).

#### 5.6 Queryability

**`bazel query`:** The `configurable_target` rule appears as a regular target.
Its `default` dependency is visible. Override/extension relationships are
not visible (they are analysis-time constructs).

**`bazel cquery`:** After analysis, the resolved target is known. A new
output format or provider should expose:

```
//filesystem:rootfs (configurable_target)
  resolved to: //my_product:custom_rootfs (override from root module)
  extensions:
    //module_b:extra_files (from module "module_b")
```

This could be implemented as (future work, not part of the initial
implementation):
- A `ConfigurableTargetResolutionInfo` provider attached to the configured
  target, queryable via `cquery --output=starlark`.
- A dedicated `cquery` output format `--output=configurable_targets`.

The initial implementation exposes resolution information via debug
logging (`--verbosity=DEBUG`), which is sufficient for early adopters.
Structured query support will be added based on user feedback.

---

## Examples

### Example 1: Filesystem with Override

**Module `filesystem` (declares the configurable target):**

```python
# filesystem/BUILD
load(":rules.bzl", "assemble_filesystem")

# The default filesystem implementation
assemble_filesystem(
    name = "rootfs_default",
    base_packages = [":base", ":utils", ":init_sysv"],
)

# The configurable target -- other modules can override this
configurable_target(
    name = "rootfs",
    default = ":rootfs_default",
)

# Other targets in this module consume the configurable target normally
package_image(
    name = "image",
    filesystem = ":rootfs",  # Gets the resolved version at analysis time
)
```

**Root module `my_product` (overrides the filesystem):**

```python
# MODULE.bazel
bazel_dep(name = "filesystem", version = "1.0")

override_target(
    target = "@filesystem//:rootfs",
    replacement = "//product:custom_rootfs",
)
```

```python
# product/BUILD
load("@filesystem//:rules.bzl", "assemble_filesystem")

assemble_filesystem(
    name = "custom_rootfs",
    base_packages = ["@filesystem//:base", "@filesystem//:utils"],
    init_system = ":init_systemd",
    extra_packages = [":product_binaries", ":product_configs"],
)
```

**Result:** When `@filesystem//:image` is built, its `filesystem`
attribute resolves to `//product:custom_rootfs` instead of
`@filesystem//:rootfs_default`.

### Example 2: Filesystem with Extensions

**Module `filesystem` (declares the configurable target):**

```python
# filesystem/BUILD
filegroup(
    name = "base_files",
    srcs = ["base.tar", "utils.tar"],
)

configurable_target(
    name = "rootfs_files",
    default = ":base_files",
)
```

**Module `networking` (extends the filesystem):**

```python
# MODULE.bazel
bazel_dep(name = "filesystem", version = "1.0")

extend_target(
    target = "@filesystem//:rootfs_files",
    extra = "//networking:network_files",
)
```

```python
# networking/BUILD
filegroup(
    name = "network_files",
    srcs = ["interfaces.conf", "dhclient"],
)
```

**Module `monitoring` (also extends the filesystem):**

```python
# MODULE.bazel
bazel_dep(name = "filesystem", version = "1.0")

extend_target(
    target = "@filesystem//:rootfs_files",
    extra = "//monitoring:monitoring_files",
)
```

```python
# monitoring/BUILD
filegroup(
    name = "monitoring_files",
    srcs = ["prometheus.conf", "node_exporter"],
)
```

**Result:** The configurable target merges `DefaultInfo.files` from the
default (`base_files`) and both extensions (`network_files`,
`monitoring_files`) via depset union. Consumers of
`@filesystem//:rootfs_files` see all files combined. The order is
determined by BFS order in the module dependency graph.

---

## Alternatives Considered

### A. Module Extensions with Generated Repositories

The defining module creates a module extension that collects tags from
all dependent modules and generates a repository with the aggregated
target.

**Why rejected:**
- The defining module cannot consume targets from repositories generated
  by its own extension (chicken-and-egg problem in the loading phase).
- Requires creating intermediate repositories, adding complexity and
  reducing transparency.
- Composition logic runs during repository generation, not analysis,
  limiting access to configuration and platform information.

### B. Label Flags (`label_flag` / `label_setting`)

Use `--@filesystem//:rootfs_impl=//product:custom_rootfs` on the command
line to redirect a label. This is the closest viable alternative and
works in both WORKSPACE and bzlmod setups.

**Why not sufficient:**
- Only supports override (one flag = one replacement label), not
  extension. Multiple modules cannot independently contribute files
  to the same configurable target.
- Overrides must be passed as command-line flags or in `.bazelrc`,
  not declared in `MODULE.bazel`. This means the composition is not
  part of the module graph and is invisible to `bazel mod` tooling.

---

## Backward Compatibility

- **No breaking changes.** Configurable targets are a new opt-in mechanism.
  Existing BUILD files and MODULE.bazel files continue to work unchanged.
- **Additive Starlark API.** Two new MODULE.bazel directives and one new
  native rule are added. No existing APIs are modified.
- **Flag-gated rollout.** The feature should be gated behind
  `--experimental_configurable_targets` during initial development and
  `--incompatible_configurable_targets` if it ever needs to change defaults.
  **Note:** The flag is not yet implemented in the current prototype;
  the feature is currently always enabled.
- **Bzlmod only.** This feature is only available with bzlmod enabled
  (`--enable_bzlmod`). It has no WORKSPACE equivalent, which is
  acceptable since WORKSPACE is deprecated.

---

## Testing Strategy

### Unit Tests

**Note:** Unit tests are not yet implemented. The following describes the
planned unit test coverage for future implementation.

- **`ModuleFileGlobals` tests:** Verify that `override_target()` and
  `extend_target()` correctly store directives in the module context.
  Test error cases: `override_target()` in non-root module, duplicate
  overrides for the same configurable target, invalid label syntax.
- **`BazelDepGraphFunction` tests:** Verify label resolution of configurable
  target directives using each module's `RepositoryMapping`. Test that
  extensions are collected in BFS order (breadth-first from root),
  matching the standard bzlmod module iteration order.
- **`BuildConfigurationFunction` tests:** Verify that resolved overrides
  and extensions are correctly injected into `BuildConfigurationValue`.

### Integration Tests (Analysis-Phase)

- **Pure alias (no override, no extension):** `configurable_target` forwards
  all providers from `default`. Verify provider identity.
- **Override only:** `override_target()` replaces `default`. Verify the
  resolved target is the replacement. Test both `extensible = True` and
  `extensible = False`.
- **Extensions only:** Multiple `extend_target()` calls from different
  modules. Verify merged `DefaultInfo.files`, `RunfilesProvider`, and
  `OutputGroupInfo` contain contributions from all extensions in
  BFS order.
- **Override + extensions:** Override replaces default; extensions merge
  on top. Verify with `extensible = True` (extensions applied) and
  `extensible = False` (extensions suppressed).
- **Dropped provider warning:** Extension target provides `CcInfo`.
  Verify warning is emitted when `warn_on_dropped_providers = True` and
  suppressed when `False`.
- **Cycle detection:** Override/extension target transitively depends on
  the configurable target. Verify a clear error with the full cycle path.
- **Error cases:** All entries in the Error Cases table should have
  corresponding test cases verifying the exact error message.

### Shell Tests (End-to-End)

- Multi-module workspace with `filesystem`/`networking`/`monitoring`
  modules (matching Example 2). Verify `bazel build` produces the
  correct merged output.
- Override scenario (matching Example 1). Verify the overridden
  implementation is used.
- `bazel cquery` output includes configurable target resolution information
  at `--verbosity=DEBUG`.
- `--experimental_configurable_targets` flag gating: verify the feature is
  unavailable when the flag is off.
- `dev_dependency = True` with `--ignore_dev_dependency`: verify
  overrides and extensions are correctly ignored.

---

## Rollout Plan

### Phase 1: Experimental

- Gate behind `--experimental_configurable_targets` (default `false`).
- Implement core functionality: `configurable_target` rule, `override_target`
  and `extend_target` directives, analysis-time resolution with
  `DefaultInfo`/`RunfilesProvider`/`OutputGroupInfo` merging.
- Internal dogfooding with the `filesystem` use case from the
  motivation section.
- No stability guarantees; API may change based on feedback.

### Phase 2: Stabilization

- Address feedback from Phase 1, fix edge cases.
- Add structured `cquery` output for configurable target resolution
  (`ConfigurableTargetResolutionInfo` provider or dedicated output format).
- Move flag to `--experimental_configurable_targets` (default `true`) or
  promote to non-experimental.
- Document in the official Bazel bzlmod guide.

### Phase 3: General Availability

- Remove the experimental flag or gate behind
  `--incompatible_configurable_targets` if any default behavior needs to
  change.
- Evaluate performance with large-scale adoption (see scaling
  consideration in Section 5.4); implement
  `ConfigurableTargetResolutionFunction` optimization if needed.

---

## Future Work

The following items are explicitly **out of scope** for the initial
implementation but are anticipated extensions based on known use cases:

- **Custom composer functions:** The current design only supports
  filegroup-like merging (`DefaultInfo.files`, `RunfilesProvider`,
  `OutputGroupInfo` via depset union). More complex composition
  strategies — such as concatenating configuration files, merging JSON
  documents, or combining custom providers like `CcInfo` — would
  require a user-defined composer function. This could be a Starlark
  callback registered on the `configurable_target` rule.
- **Structured `cquery` output:** A `ConfigurableTargetResolutionInfo`
  provider or dedicated `--output=configurable_targets` format to expose
  resolution details (base target, active override, list of extensions)
  in a machine-readable way.
- **`ConfigurableTargetResolutionFunction` optimization:** If configurable targets
  see widespread adoption with large mapping counts, resolution could be
  moved from `BuildConfigurationValue` to a dedicated `SkyFunction` to
  avoid cache invalidation of all configured targets when mappings
  change (see Section 5.4).
- **Visibility refinements:** Currently, override/extension targets must
  be visible to the configurable target's package. Future work could explore
  a dedicated visibility model for configurable target contributions.

---

