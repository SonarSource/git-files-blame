/*
 * Git Files Blame
 * Copyright (C) SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.scm.git.blame.compare;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A repository plus the files to blame in it. {@code ref} must be a commit SHA or an immutable tag, never a
 * moving branch, so that only the clone is expensive and the blame itself is deterministic and fast.
 *
 * @param sparsePaths cone-mode sparse-checkout directories that must be materialized to reach {@code targetFiles}
 * @param targetFiles repository-relative paths that are actually blamed and compared
 * @param knownAmbiguousLines per-file, 1-indexed line numbers where this library and native git are both known to
 *                            produce a legitimate but different attribution (see {@link BlameComparator}), mapped
 *                            to the specific alternate this library is known to report there
 * @param unassertedFiles subset of {@code targetFiles} that are still blamed by both native git and this library
 *                        (so their clone/blame timing is still measured), but whose result isn't compared - for a
 *                        file whose native-git ground truth isn't itself reproducible run-to-run (see GFB-54)
 */
public record ComparisonScenario(String name, RepoSource source, String ref, List<String> sparsePaths, List<String> targetFiles,
  Map<String, Map<Integer, NativeLineBlame>> knownAmbiguousLines, Set<String> unassertedFiles) {

  public ComparisonScenario(String name, RepoSource source, String ref, List<String> sparsePaths, List<String> targetFiles) {
    this(name, source, ref, sparsePaths, targetFiles, Map.of(), Set.of());
  }

  public ComparisonScenario(String name, RepoSource source, String ref, List<String> sparsePaths, List<String> targetFiles,
    Map<String, Map<Integer, NativeLineBlame>> knownAmbiguousLines) {
    this(name, source, ref, sparsePaths, targetFiles, knownAmbiguousLines, Set.of());
  }
}
