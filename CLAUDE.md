# Configurable Targets Feature (branch: lt_configurable_targets)

## Commit workflow — IMPORTANT

This branch maintains a structured commit history. **Every change must go into the correct commit:**

| Order | Content | What goes here |
|-------|---------|----------------|
| 1st (oldest) | Design document | Any change to `configurable_targets_design.md` |
| 2nd | Implementation | Any change to Java source files under `src/` |
| 3rd | Integration tests | Any change to `bazel_configurable_target/`, `build_and_test.sh`, `.bazelignore` |
| 4th (newest) | Project conventions | Any change to `CLAUDE.md` |

**How to amend the correct commit:**

1. Stage your changes with `git add`.
2. Use `git commit --fixup=<SHA>` targeting the appropriate commit.
3. After all changes are staged and committed, run `git rebase --autosquash HEAD~4` (or appropriate range) to fold fixups into the right commits.

Alternatively, use interactive rebase (`git rebase -i`) to reorder and squash manually.

**Never mix categories** — do not put design doc edits into the implementation commit or test changes into the design commit. If a change spans categories (e.g., new feature requires design + implementation + tests), create separate fixup commits for each.

## What this feature does

Configurable targets are a native Bazel mechanism that lets modules declare targets whose implementation
can be **overridden** (fully replaced) or **extended** (composed with additional contributions) by
other modules in the dependency graph. Resolution happens at analysis time. Analogous to Yocto's
variable override system (`override_target` ~ assignment, `extend_target` ~ `:append`), with the
module dependency graph determining priority.

## Key concepts

- **`configurable_target()` rule** — native rule in BUILD files; acts as a configurable proxy with a
  default implementation that can be replaced or extended by consumer modules.
- **`override_target()` directive** — MODULE.bazel directive (root module only) to fully replace
  a configurable target's implementation.
- **`extend_target()` directive** — MODULE.bazel directive (any module) to contribute additional
  files merged with the base.
- **`extensible` flag** — on `override_target()`; when `False`, all extensions are suppressed
  (pure alias to replacement).
- **Merge behavior** — when extensions exist, `DefaultInfo.files`, `RunfilesProvider`, and
  `OutputGroupInfo` are merged via depset union. Other providers forwarded from base only.
- **`merge_order` attribute** — controls depset order: `"default"`, `"preorder"`, `"postorder"`,
  `"topological"`.
- **`warn_on_dropped_providers` attribute** — boolean (default `True`) on `configurable_target()`;
  emits a warning when extensions are merged and non-mergeable providers are dropped.

## Resolution rules

1. If `override_target()` exists → use replacement as base.
2. If `extensible = False` on override → pure alias to replacement (no extensions).
3. If override with extensions → merge `DefaultInfo.files` + `RunfilesProvider` + `OutputGroupInfo` from override + all extensions.
4. If no override but extensions exist → merge `DefaultInfo.files` + `RunfilesProvider` + `OutputGroupInfo` from default + all extensions.
5. If no override and no extensions → pure alias to default.

Only the root module can `override_target()`. Non-root modules use `extend_target()`.

## Data flow pipeline

```
MODULE.bazel  (override_target / extend_target)
  → ModuleFileGlobals.java   (parses directives, stores string labels)
  → ModuleThreadContext.java  (collects directives, enforces no-duplicate overrides)
  → InterimModule / ModuleBase  (holds ConfigurableTargetOverrideSpec / extension specs)
  → BazelDepGraphFunction.java  (resolves strings → canonical Labels, BFS order, root wins)
  → BazelDepGraphValue.java  (carries resolved override/extension maps between SkyFunctions)
  → BuildConfigurationFunction.java  (injects maps into BuildConfigurationValue)
  → BuildConfigurationValue.java  (stores resolved maps, available at analysis time)
  → ConfigurableTarget.java  (LabelLateBoundDefault resolvers pick override/extensions, merges providers)
```

## Implementation files

| File | Role |
|------|------|
| `src/main/java/.../rules/ConfigurableTarget.java` | Rule factory: late-bound resolution, provider merging (DefaultInfo, Runfiles, OutputGroupInfo), rule class definition |
| `src/main/java/.../bzlmod/ConfigurableTargetOverrideInfo.java` | Post-resolution record: `(Label replacement, boolean extensible)` |
| `src/main/java/.../bzlmod/ConfigurableTargetOverrideSpec.java` | Pre-resolution record: `(String replacement, boolean extensible)` |
| `src/main/java/.../bzlmod/ModuleFileGlobals.java` | Starlark callables `override_target()` and `extend_target()` |
| `src/main/java/.../bzlmod/BazelDepGraphFunction.java` | Resolves string labels to canonical Labels; aggregates extensions in BFS order |
| `src/main/java/.../bzlmod/BazelDepGraphValue.java` | Carries resolved override/extension maps between SkyFunctions |
| `src/main/java/.../bzlmod/ModuleBase.java` | Stores override/extension specs on the module |
| `src/main/java/.../bzlmod/InterimModule.java` | Builder-phase module carrying override/extension specs (immutable AutoValue) |
| `src/main/java/.../bzlmod/Module.java` | Resolved module carrying override/extension specs (immutable AutoValue, extends ModuleBase) |
| `src/main/java/.../bzlmod/ModuleThreadContext.java` | Thread-local context for MODULE.bazel evaluation; collects directives |
| `src/main/java/.../analysis/config/BuildConfigurationValue.java` | Stores `configurableTargetOverrides` and `configurableTargetExtensions` maps; accessed at analysis time |
| `src/main/java/.../skyframe/config/BuildConfigurationFunction.java` | Injects resolved configurable target mappings into BuildConfigurationValue |
| `src/main/java/.../bazel/rules/GenericRules.java` | Registers the configurable_target rule class |

## Design document

`configurable_targets_design.md` at the repo root is the source of truth for the design. When changing
behavior, update both the design doc and the implementation, in their respective commits.

## Integration tests

Located in `bazel_configurable_target/`. Each subdirectory is a self-contained multi-module workspace
with `MODULE.bazel`, `BUILD`, and `expected.txt`. Run all tests:

```sh
./bazel_configurable_target/run_all_tests.sh [/path/to/bazel]
```

Test scenarios: `example_default`, `example_extend`, `example_override`,
`example_override_only`, `example_override_not_extensible`,
`example_multi_extend`, `example_override_multi_extend`,
`example_extend_no_warn`, `example_extend_merge_order`,
`example_extend_output_groups`, `example_extend_dev_dep`,
`example_error_nonroot_override`, `example_error_duplicate_override`.

Shared library modules: `lib_a`, `lib_a_merge_order`, `lib_a_no_warn`,
`lib_b`, `lib_c`, `lib_d`, `lib_e`, `lib_f`, `lib_g`.

To build Bazel from source and run all tests:

```sh
./build_and_test.sh
```

## Conventions

- Java records for simple data classes (`ConfigurableTargetOverrideSpec`, `ConfigurableTargetOverrideInfo`).
- `LabelLateBoundDefault` / `LabelListLateBoundDefault` for analysis-time resolution from
  `BuildConfigurationValue`.
- BFS ordering over the module dependency graph; only the root module can override
  (duplicate overrides are an error); extensions are aggregated from all modules in BFS order.
- `AliasConfiguredTarget` for pure-alias path (no extensions); `RuleConfiguredTargetBuilder` for
  merged path.
- Each integration test example is a standalone Bazel workspace with local module overrides
  pointing to sibling `lib_*` directories.
