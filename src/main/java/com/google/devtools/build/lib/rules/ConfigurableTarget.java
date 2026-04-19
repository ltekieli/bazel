// Copyright 2026 The Bazel Authors. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.lib.rules;

import static com.google.devtools.build.lib.packages.Attribute.attr;
import static com.google.devtools.build.lib.packages.BuildType.LABEL;
import static com.google.devtools.build.lib.packages.BuildType.LABEL_LIST;
import static com.google.devtools.build.lib.packages.Type.BOOLEAN;
import static com.google.devtools.build.lib.packages.Type.STRING;

import com.google.common.collect.ImmutableList;
import com.google.devtools.build.lib.actions.ActionConflictException;
import com.google.devtools.build.lib.actions.Artifact;
import com.google.devtools.build.lib.analysis.BaseRuleClasses;
import com.google.devtools.build.lib.analysis.ConfiguredTarget;
import com.google.devtools.build.lib.analysis.FileProvider;
import com.google.devtools.build.lib.analysis.OutputGroupInfo;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetBuilder;
import com.google.devtools.build.lib.analysis.RuleConfiguredTargetFactory;
import com.google.devtools.build.lib.analysis.RuleContext;
import com.google.devtools.build.lib.analysis.RuleDefinition;
import com.google.devtools.build.lib.analysis.RuleDefinitionEnvironment;
import com.google.devtools.build.lib.analysis.Runfiles;
import com.google.devtools.build.lib.analysis.RunfilesProvider;
import com.google.devtools.build.lib.analysis.TransitiveInfoCollection;
import com.google.devtools.build.lib.analysis.config.BuildConfigurationValue;
import com.google.devtools.build.lib.bazel.bzlmod.ConfigurableTargetOverrideInfo;
import com.google.devtools.build.lib.cmdline.Label;
import com.google.devtools.build.lib.collect.nestedset.NestedSet;
import com.google.devtools.build.lib.collect.nestedset.NestedSetBuilder;
import com.google.devtools.build.lib.collect.nestedset.Order;
import com.google.devtools.build.lib.packages.Attribute;
import com.google.devtools.build.lib.packages.Attribute.AllowedValueSet;
import com.google.devtools.build.lib.packages.Attribute.LabelLateBoundDefault;
import com.google.devtools.build.lib.packages.Attribute.LabelListLateBoundDefault;
import com.google.devtools.build.lib.packages.RawAttributeMapper;
import com.google.devtools.build.lib.packages.RuleClass;
import com.google.devtools.build.lib.packages.RuleClass.ToolchainResolutionMode;
import com.google.devtools.build.lib.skyframe.serialization.VisibleForSerialization;
import com.google.devtools.build.lib.skyframe.serialization.autocodec.SerializationConstant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Implementation of the {@code configurable_target} rule.
 *
 * <p>A configurable target acts as a configurable alias whose actual target is determined by the module
 * dependency graph. Modules can declare {@code override_target()} in their MODULE.bazel to replace
 * the default implementation, or {@code extend_target()} to contribute additional files that are
 * merged with the default.
 *
 * <p>At analysis time:
 *
 * <ul>
 *   <li>If an override exists with no extensions (or {@code extensible = False}), the configurable target
 *       forwards all providers from the replacement (pure alias).
 *   <li>If extensions exist (with or without an override), the configurable target merges {@code
 *       DefaultInfo.files}, {@code RunfilesProvider}, and {@code OutputGroupInfo} from the resolved
 *       target (override or default) and all extension targets. The merge order is controlled by
 *       the {@code merge_order} attribute.
 *   <li>Otherwise, the configurable target forwards all providers from the default target.
 * </ul>
 */
public class ConfigurableTarget implements RuleConfiguredTargetFactory {

  @SerializationConstant @VisibleForSerialization
  static final LabelLateBoundDefault<BuildConfigurationValue> RESOLVED =
      LabelLateBoundDefault.fromTargetConfigurationWithRuleBasedDefault(
          BuildConfigurationValue.class,
          (rule) -> RawAttributeMapper.of(rule).get("default", LABEL),
          (rule, attributes, configuration) -> {
            if (rule == null || configuration == null) {
              return attributes.get("default", LABEL);
            }
            ConfigurableTargetOverrideInfo override =
                configuration.getConfigurableTargetOverrides().get(rule.getLabel());
            if (override != null) {
              return override.replacement();
            }
            return attributes.get("default", LABEL);
          });

  @SerializationConstant @VisibleForSerialization
  static final LabelListLateBoundDefault<BuildConfigurationValue> EXTENSIONS =
      LabelListLateBoundDefault.fromTargetConfiguration(
          BuildConfigurationValue.class,
          (rule, attributes, configuration) -> {
            if (rule == null || configuration == null) {
              return ImmutableList.of();
            }
            // If there is a non-extensible override, suppress all extensions.
            ConfigurableTargetOverrideInfo override =
                configuration.getConfigurableTargetOverrides().get(rule.getLabel());
            if (override != null && !override.extensible()) {
              return ImmutableList.of();
            }
            ImmutableList<Label> extensions =
                configuration.getConfigurableTargetExtensions().get(rule.getLabel());
            return extensions != null ? extensions : ImmutableList.of();
          });

  /** Maps the {@code merge_order} attribute value to a {@link Order}. */
  private static Order parseMergeOrder(String mergeOrder) {
    return switch (mergeOrder) {
      case "preorder" -> Order.COMPILE_ORDER;
      case "postorder" -> Order.NAIVE_LINK_ORDER;
      case "topological" -> Order.LINK_ORDER;
      default -> Order.STABLE_ORDER;
    };
  }

  @Override
  public ConfiguredTarget create(RuleContext ruleContext)
      throws InterruptedException, RuleErrorException, ActionConflictException {
    ConfiguredTarget actual = (ConfiguredTarget) ruleContext.getPrerequisite(":alias");
    if (actual == null) {
      ruleContext.ruleError("configurable_target could not resolve to a target");
      return null;
    }

    List<? extends TransitiveInfoCollection> extensions = ruleContext.getPrerequisites(":extensions");

    // If no extensions, behave as a pure alias (forwards all providers).
    if (extensions.isEmpty()) {
      return AliasConfiguredTarget.create(ruleContext, actual, ruleContext.getVisibility());
    }

    // Emit a warning that extensions only contribute files and output groups.
    if (ruleContext.attributes().get("warn_on_dropped_providers", BOOLEAN)) {
      ruleContext.ruleWarning(
          "configurable_target is merging extensions. Only DefaultInfo.files, RunfilesProvider, and "
              + "OutputGroupInfo from extension targets are used; other providers from extension "
              + "targets are silently dropped. Set warn_on_dropped_providers = False to suppress "
              + "this warning.");
    }

    // Extensions present: merge DefaultInfo.files from resolved target + all extensions.
    String mergeOrderStr = ruleContext.attributes().get("merge_order", STRING);
    Order order = parseMergeOrder(mergeOrderStr);
    NestedSetBuilder<Artifact> mergedFiles = NestedSetBuilder.newBuilder(order);

    // Add files from the resolved target (override or default).
    FileProvider defaultProvider = actual.getProvider(FileProvider.class);
    if (defaultProvider != null) {
      mergedFiles.addTransitive(defaultProvider.getFilesToBuild());
    }

    // Add files from each extension.
    for (TransitiveInfoCollection ext : extensions) {
      FileProvider extProvider = ext.getProvider(FileProvider.class);
      if (extProvider != null) {
        if (extProvider.getFilesToBuild().isEmpty()) {
          ruleContext.ruleWarning(
              "extension target " + ext.getLabel()
                  + " has empty DefaultInfo.files; it contributes no files to the merged output.");
        }
        mergedFiles.addTransitive(extProvider.getFilesToBuild());
      } else {
        ruleContext.ruleWarning(
            "extension target " + ext.getLabel()
                + " has no DefaultInfo.files; it contributes no files to the merged output.");
      }
    }

    NestedSet<Artifact> filesToBuild = mergedFiles.build();

    // Merge runfiles: use RunfilesProvider when available, fall back to FileProvider files.
    Runfiles.Builder runfilesBuilder = new Runfiles.Builder(ruleContext.getWorkspaceName());
    RunfilesProvider baseRunfiles = actual.getProvider(RunfilesProvider.class);
    if (baseRunfiles != null) {
      runfilesBuilder.merge(baseRunfiles.getDefaultRunfiles());
    } else if (defaultProvider != null) {
      runfilesBuilder.addTransitiveArtifacts(defaultProvider.getFilesToBuild());
    }
    for (TransitiveInfoCollection ext : extensions) {
      RunfilesProvider extRunfiles = ext.getProvider(RunfilesProvider.class);
      if (extRunfiles != null) {
        runfilesBuilder.merge(extRunfiles.getDefaultRunfiles());
      } else {
        FileProvider extProvider = ext.getProvider(FileProvider.class);
        if (extProvider != null) {
          runfilesBuilder.addTransitiveArtifacts(extProvider.getFilesToBuild());
        }
      }
    }

    Runfiles runfiles = runfilesBuilder.build();

    // Merge OutputGroupInfo from the resolved target and all extensions.
    Map<String, NestedSetBuilder<Artifact>> outputGroupBuilders = new TreeMap<>();
    collectOutputGroups(OutputGroupInfo.get(actual), outputGroupBuilders, order);
    for (TransitiveInfoCollection ext : extensions) {
      collectOutputGroups(OutputGroupInfo.get(ext), outputGroupBuilders, order);
    }

    RuleConfiguredTargetBuilder builder =
        new RuleConfiguredTargetBuilder(ruleContext)
            .setFilesToBuild(filesToBuild)
            .addProvider(RunfilesProvider.simple(runfiles));

    for (Map.Entry<String, NestedSetBuilder<Artifact>> entry : outputGroupBuilders.entrySet()) {
      builder.addOutputGroup(entry.getKey(), entry.getValue().build());
    }

    return builder.build();
  }

  /** Collects output groups from a provider into the merged builders map. */
  private static void collectOutputGroups(
      OutputGroupInfo info,
      Map<String, NestedSetBuilder<Artifact>> builders,
      Order order) {
    if (info == null) {
      return;
    }
    for (String group : info) {
      // Skip "default" — already handled by setFilesToBuild.
      if (OutputGroupInfo.DEFAULT.equals(group)) {
        continue;
      }
      NestedSetBuilder<Artifact> groupBuilder =
          builders.computeIfAbsent(group, g -> NestedSetBuilder.newBuilder(order));
      groupBuilder.addTransitive(info.getOutputGroup(group));
    }
  }

  /** Rule definition for {@code configurable_target}. */
  public static class ConfigurableTargetRule implements RuleDefinition {
    @Override
    public RuleClass build(RuleClass.Builder builder, RuleDefinitionEnvironment env) {
      return builder
          .removeAttribute("licenses")
          .removeAttribute("distribs")
          .removeAttribute(":action_listener")
          .add(
              attr("default", LABEL)
                  .allowedFileTypes()
                  .allowedRuleClasses(Attribute.ANY_RULE)
                  .mandatory()
                  .nonconfigurable("configurable_target default must be a fixed label"))
          .add(
              attr("merge_order", STRING)
                  .value("default")
                  .allowedValues(new AllowedValueSet("default", "preorder", "postorder", "topological"))
                  .nonconfigurable("configurable_target merge_order is structural"))
          .add(
              attr("warn_on_dropped_providers", BOOLEAN)
                  .value(true)
                  .nonconfigurable("configurable_target warning control is structural"))
          .add(
              attr(":alias", LABEL)
                  .allowedFileTypes()
                  .allowedRuleClasses(Attribute.ANY_RULE)
                  .value(RESOLVED))
          .add(
              attr(":extensions", LABEL_LIST)
                  .allowedFileTypes()
                  .allowedRuleClasses(Attribute.ANY_RULE)
                  .value(EXTENSIONS))
          .canHaveAnyProvider()
          .toolchainResolutionMode(ToolchainResolutionMode.DISABLED)
          .build();
    }

    @Override
    public RuleDefinition.Metadata getMetadata() {
      return RuleDefinition.Metadata.builder()
          .name("configurable_target")
          .factoryClass(ConfigurableTarget.class)
          .ancestors(BaseRuleClasses.NativeBuildRule.class)
          .build();
    }
  }
}
