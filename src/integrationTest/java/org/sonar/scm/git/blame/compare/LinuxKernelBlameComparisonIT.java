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
import org.junit.jupiter.api.Tag;

/**
 * Real-world coverage tier: clones the actual Linux kernel repository at a pinned, immutable tag and blames a
 * couple of long-lived, heavily-edited scheduler files. This is the slowest scenario in the suite - the kernel's
 * full history is multi-gigabyte - so, like {@link OpenJdkBlameComparisonIT}, it's excluded from the default
 * {@code integrationTest} task and only runs via {@code integrationTestFullQa}, gated behind the {@code full-qa}
 * PR label or a push to master.
 */
@Tag("fullQa")
class LinuxKernelBlameComparisonIT extends AbstractBlameComparisonIT {

  private static final ComparisonScenario SCENARIO = new ComparisonScenario(
    "linux-kernel",
    new RepoSource.Remote("https://github.com/torvalds/linux.git"),
    "v6.6",
    List.of("kernel/sched"),
    List.of("kernel/sched/core.c", "kernel/sched/fair.c"));

  @Override
  protected ComparisonScenario scenario() {
    return SCENARIO;
  }

  /**
   * This library has no on-demand promisor-fetch (see {@link AbstractBlameComparisonIT#supportsStrategy}), so a
   * blobless clone of the kernel's full history reliably throws {@code MissingObjectException} rather than
   * surfacing a blame divergence - documented in {@code README.md}, "Blame semantics vs native git".
   */
  @Override
  protected boolean supportsStrategy(CloneStrategy strategy) {
    return strategy != CloneStrategy.PARTIAL_SPARSE;
  }
}
