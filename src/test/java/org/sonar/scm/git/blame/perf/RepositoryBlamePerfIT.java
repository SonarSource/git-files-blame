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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.scm.git.blame.BlameResult;
import org.sonar.scm.git.blame.RepositoryBlameCommand;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Not run as part of the default test task (see the "benchmark" Gradle task). Generates a synthetic repository
 * and times {@link RepositoryBlameCommand} against it, to measure the impact of performance changes to the
 * blame algorithm on a repository with a long history.
 */
@Tag("benchmark")
class RepositoryBlamePerfIT {
  private static final long SEED = 42L;

  private static final int WARMUP_RUNS = 1;
  private static final int MEASURED_RUNS = 5;

  @TempDir
  Path tempDir;

  /**
   * Many files, many commits, but each file is small. Representative of the per-commit tree-walk/bookkeeping
   * overhead rather than blob loading cost.
   */
  @Test
  void blame_wholeRepository_manyCommitsSmallFiles() throws IOException {
    runScenario(new SyntheticRepoGenerator.Config(300, 300, 3000, 2, SEED));
  }

  /**
   * Few files, but each one is large. Isolates the cost of loading and diffing blob content across history,
   * since the per-commit tree-walk over a handful of files is comparatively cheap.
   */
  @Test
  void blame_wholeRepository_fewCommitsLargeFiles() throws IOException {
    runScenario(new SyntheticRepoGenerator.Config(50, 5000, 2000, 3, SEED));
  }

  /**
   * Many files, each of them large, over a long history. Stresses both the per-commit tree-walk/bookkeeping
   * overhead and blob loading/diffing cost at once, closer to what a big real-world monorepo looks like.
   */
  @Test
  void blame_wholeRepository_veryLargeRepository() throws IOException {
    runScenario(new SyntheticRepoGenerator.Config(1000, 3000, 6000, 4, SEED), 1, 3);
  }

  /**
   * Periodic bursts renaming a chunk of the tracked files, each rename also rewriting a line so it can't be
   * matched by exact blob id. This forces {@code RenameDetector} into its expensive content-similarity matching
   * (an O(added x deleted) matrix build) on every burst commit, instead of the cheap linear paths exercised by
   * the other scenarios, which never add or delete files.
   */
  @Test
  void blame_wholeRepository_manyRenames() throws IOException {
    runScenario(new SyntheticRepoGenerator.Config(500, 500, 2000, 2, 40, 50, SEED));
  }

  private void runScenario(SyntheticRepoGenerator.Config config) throws IOException {
    runScenario(config, WARMUP_RUNS, MEASURED_RUNS);
  }

  private void runScenario(SyntheticRepoGenerator.Config config, int warmupRuns, int measuredRuns) throws IOException {
    Path repoDir = tempDir.resolve("repo.git");

    long generationStartNs = System.nanoTime();
    SyntheticRepoGenerator.Stats stats = SyntheticRepoGenerator.generate(repoDir, config);
    long generationMs = (System.nanoTime() - generationStartNs) / 1_000_000;
    System.out.printf("Generated repo: %d commits, %d files, %d lines/file (%d ms)%n",
      stats.numCommits(), stats.numFiles(), stats.linesPerFile(), generationMs);

    List<Long> measuredMs = new ArrayList<>();
    for (int i = 0; i < warmupRuns + measuredRuns; i++) {
      long elapsedMs = runBlameAndMeasure(repoDir, config.numFiles());
      if (i < warmupRuns) {
        System.out.printf("Warmup run: %d ms%n", elapsedMs);
      } else {
        System.out.printf("Measured run %d: %d ms%n", i - warmupRuns + 1, elapsedMs);
        measuredMs.add(elapsedMs);
      }
    }

    printSummary(measuredMs);
  }

  private long runBlameAndMeasure(Path repoDir, int expectedNumFiles) throws IOException {
    try (Repository repository = new FileRepositoryBuilder().setGitDir(repoDir.toFile()).build()) {
      long startNs = System.nanoTime();
      BlameResult result = new RepositoryBlameCommand(repository).call();
      long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
      assertThat(result.getFileBlames()).hasSize(expectedNumFiles);
      return elapsedMs;
    } catch (GitAPIException e) {
      throw new IllegalStateException(e);
    }
  }

  private static void printSummary(List<Long> measuredMs) {
    List<Long> sorted = new ArrayList<>(measuredMs);
    Collections.sort(sorted);
    long min = sorted.get(0);
    long median = sorted.get(sorted.size() / 2);
    double avg = sorted.stream().mapToLong(Long::longValue).average().orElseThrow();
    System.out.printf("Summary over %d measured runs: min=%dms median=%dms avg=%.1fms%n", sorted.size(), min, median, avg);
  }
}
