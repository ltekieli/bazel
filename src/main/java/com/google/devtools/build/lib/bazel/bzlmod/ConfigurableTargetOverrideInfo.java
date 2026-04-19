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
package com.google.devtools.build.lib.bazel.bzlmod;

import com.google.devtools.build.lib.cmdline.Label;

/**
 * Post-resolution override info for a configurable target, carrying canonical {@link Label} objects and
 * the extensible flag. Stored in {@link BazelDepGraphValue} and propagated to {@link
 * com.google.devtools.build.lib.analysis.config.BuildConfigurationValue}.
 *
 * @param replacement the canonical label of the replacement target
 * @param extensible whether extensions should still be applied on top of this override
 */
public record ConfigurableTargetOverrideInfo(Label replacement, boolean extensible) {}
