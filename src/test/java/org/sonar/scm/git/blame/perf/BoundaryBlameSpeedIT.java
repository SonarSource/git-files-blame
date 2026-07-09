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
package org.sonar.scm.git.blame.perf;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.scm.git.blame.BlameResult;
import org.sonar.scm.git.blame.BoundaryBlame;
import org.sonar.scm.git.blame.RepositoryBlameCommand;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Not run as part of the default test task (see the "benchmark" Gradle task). Compares blaming a repository from
 * scratch against blaming it with a {@link BoundaryBlame} cache captured a few commits earlier, to measure whether
 * the short-circuit actually pays off once a handful of new commits land on top of an already-blamed history.
 */
@Tag("benchmark")
class BoundaryBlameSpeedIT {
  private static final long SEED = 42L;
  private static final long APPEND_SEED = 4242L;
  private static final int WARMUP_RUNS = 1;
  private static final int MEASURED_RUNS = 5;

  @TempDir
  Path tempDir;

  @Test
  void speed_manyCommitsSmallFiles_fewExtraCommits() throws IOException, GitAPIException {
    runScenario(new SyntheticRepoGenerator.Config(300, 300, 3000, 2, SEED), 20);
  }

  @Test
  void speed_veryLargeRepository_fewExtraCommits() throws IOException, GitAPIException {
    runScenario(new SyntheticRepoGenerator.Config(1000, 3000, 6000, 4, SEED), 20);
  }

  private void runScenario(SyntheticRepoGenerator.Config config, int extraCommits) throws IOException, GitAPIException {
    Path repoDir = tempDir.resolve("repo.git");
    SyntheticRepoGenerator.Stats boundaryStats = SyntheticRepoGenerator.generate(repoDir, config);

    BoundaryBlame boundary;
    try (Repository repository = new FileRepositoryBuilder().setGitDir(repoDir.toFile()).build()) {
      BlameResult blameAtBoundary = new RepositoryBlameCommand(repository).call();
      boundary = BoundaryBlame.capture(boundaryStats.head(), blameAtBoundary);
    }

    SyntheticRepoGenerator.appendCommits(repoDir, config, extraCommits, APPEND_SEED);
    System.out.printf("Scenario: %d boundary commits + %d extra commits, %d files, %d lines/file%n",
      boundaryStats.numCommits(), extraCommits, config.numFiles(), config.linesPerFile());

    Measurement fullBlame = measure(repoDir, config.numFiles(), null);
    Measurement incrementalBlame = measure(repoDir, config.numFiles(), boundary);

    assertThat(incrementalBlame.lastResult().getFileBlameByPath())
      .usingRecursiveComparison()
      .isEqualTo(fullBlame.lastResult().getFileBlameByPath());

    printSummary("Full blame       ", fullBlame.timingsMs());
    printSummary("Incremental blame", incrementalBlame.timingsMs());
    double speedup = median(fullBlame.timingsMs()) / (double) median(incrementalBlame.timingsMs());
    System.out.printf("Speedup: %.1fx%n%n", speedup);
  }

  private Measurement measure(Path repoDir, int expectedNumFiles, @Nullable BoundaryBlame boundary) throws IOException {
    List<Long> measuredMs = new ArrayList<>();
    BlameResult lastResult = null;
    for (int i = 0; i < WARMUP_RUNS + MEASURED_RUNS; i++) {
      try (Repository repository = new FileRepositoryBuilder().setGitDir(repoDir.toFile()).build()) {
        RepositoryBlameCommand command = new RepositoryBlameCommand(repository);
        if (boundary != null) {
          command.setBoundaryBlame(boundary);
        }
        long startNs = System.nanoTime();
        lastResult = command.call();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        assertThat(lastResult.getFileBlames()).hasSize(expectedNumFiles);
        if (i >= WARMUP_RUNS) {
          measuredMs.add(elapsedMs);
        }
      } catch (GitAPIException e) {
        throw new IllegalStateException(e);
      }
    }
    return new Measurement(measuredMs, lastResult);
  }

  private static void printSummary(String label, List<Long> measuredMs) {
    List<Long> sorted = new ArrayList<>(measuredMs);
    Collections.sort(sorted);
    long min = sorted.get(0);
    long median = median(sorted);
    double avg = sorted.stream().mapToLong(Long::longValue).average().orElseThrow();
    System.out.printf("%s over %d measured runs: min=%dms median=%dms avg=%.1fms%n", label, sorted.size(), min, median, avg);
  }

  private static long median(List<Long> measuredMs) {
    List<Long> sorted = new ArrayList<>(measuredMs);
    Collections.sort(sorted);
    return sorted.get(sorted.size() / 2);
  }

  private record Measurement(List<Long> timingsMs, BlameResult lastResult) {
  }
}
