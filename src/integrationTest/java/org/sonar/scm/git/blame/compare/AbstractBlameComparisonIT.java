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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * Runs a concrete {@link ComparisonScenario} through all three {@link CloneStrategy} values and asserts native
 * git and this library agree on every one - which transitively proves all combinations of
 * {native-git, this-library} x {full clone, cone-mode sparse-checkout, partial/blobless clone} agree, since
 * native-git-on-a-full-clone is the common ground truth every other cell is checked against.
 */
public abstract class AbstractBlameComparisonIT {

  @TempDir
  Path tempDir;

  protected abstract ComparisonScenario scenario();

  @ParameterizedTest
  @EnumSource(CloneStrategy.class)
  void nativeGitAndLibraryProduceTheSameBlame(CloneStrategy strategy) throws IOException, GitAPIException {
    ComparisonScenario scenario = scenario();
    Path workDir = tempDir.resolve(strategy.name());
    BlameTiming timing = new ScenarioRunner(workDir).run(scenario, strategy);
    TimingReporter.print(timing);
  }
}
