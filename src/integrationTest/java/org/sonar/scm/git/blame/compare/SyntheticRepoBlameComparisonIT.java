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

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.scm.git.blame.perf.SyntheticRepoGenerator;
import org.sonar.scm.git.blame.perf.SyntheticRepoGenerator.MonorepoConfig;
import org.sonar.scm.git.blame.perf.SyntheticRepoGenerator.MonorepoStats;

/**
 * Fast, fully offline tier: builds a small monorepo-shaped bare repository directly through JGit (no network),
 * then uses it as a local {@code git clone} source so the rest of the harness exercises the exact same
 * clone/sparse-checkout/blame code path the OpenJDK and Linux kernel scenarios use. Runs on every PR.
 */
class SyntheticRepoBlameComparisonIT extends AbstractBlameComparisonIT {

  private static final long SEED = 42L;

  private ComparisonScenario scenario;

  @TempDir
  Path repoDir;

  @BeforeEach
  void generateRepo() throws IOException {
    Path bareRepoDir = repoDir.resolve("origin.git");
    MonorepoStats stats = SyntheticRepoGenerator.generateMonorepo(bareRepoDir,
      new MonorepoConfig(3, 5, 8, 40, 3, SEED));

    List<String> targetFiles = stats.trackedFiles().stream().sorted().toList();
    scenario = new ComparisonScenario("synthetic-monorepo", new RepoSource.Local(bareRepoDir), stats.head().getName(),
      List.of("domain0"), targetFiles);
  }

  @Override
  protected ComparisonScenario scenario() {
    return scenario;
  }
}
