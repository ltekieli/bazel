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

/**
 * Pre-resolution override specification for a configurable target, carrying string labels and the
 * extensible flag. This is stored in {@link ModuleBase} and converted to {@link
 * ConfigurableTargetOverrideInfo} (with canonical {@code Label} objects) during module resolution.
 *
 * @param replacement the string label of the replacement target
 * @param extensible whether extensions should still be applied on top of this override
 */
public record ConfigurableTargetOverrideSpec(String replacement, boolean extensible) {}
