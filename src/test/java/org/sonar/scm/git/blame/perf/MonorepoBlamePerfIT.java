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
import java.util.Set;
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
 * Not run as part of the default test task (see the "benchmark" Gradle task). Reproduces a customer-reported
 * scenario: a large monorepo split into many domains (top-level directories), where only one domain's files are
 * actually being analyzed via {@code RepositoryBlameCommand.setFilePaths(...)}, while most commits touch files in
 * other, unrelated domains. Isolates the cost of computing the tree diff and rename detection over the WHOLE
 * repository at every commit, instead of scoping it to the files actually being blamed.
 */
@Tag("benchmark")
class MonorepoBlamePerfIT {
  private static final long SEED = 42L;
  private static final int WARMUP_RUNS = 1;
  private static final int MEASURED_RUNS = 5;

  @TempDir
  Path tempDir;

  /**
   * 50 domains x 500 files (25 000 files total), only one domain (500 files) is blamed. Each commit touches 5
   * random files across all domains, so the large majority of commits don't touch the blamed domain at all.
   */
  @Test
  void blame_oneDomainOfManyInAMonorepo() throws IOException {
    runScenario(new SyntheticRepoGenerator.MonorepoConfig(50, 500, 200, 3000, 5, SEED));
  }

  /**
   * Same tracked domain (500 files), same history length and commit shape, but with no other domains at all - a
   * control to isolate exactly how much the other 49 (untracked) domains cost in the scenario above.
   */
  @Test
  void blame_singleDomainNoNoise_control() throws IOException {
    runScenario(new SyntheticRepoGenerator.MonorepoConfig(1, 500, 200, 3000, 5, SEED));
  }

  private void runScenario(SyntheticRepoGenerator.MonorepoConfig config) throws IOException {
    Path repoDir = tempDir.resolve("repo.git");

    long generationStartNs = System.nanoTime();
    SyntheticRepoGenerator.MonorepoStats stats = SyntheticRepoGenerator.generateMonorepo(repoDir, config);
    long generationMs = (System.nanoTime() - generationStartNs) / 1_000_000;
    System.out.printf("Generated repo: %d commits, %d domains x %d files (%d total, %d tracked) (%d ms)%n",
      stats.numCommits(), stats.numDomains(), stats.filesPerDomain(), stats.totalFiles(), stats.trackedFiles().size(), generationMs);

    List<Long> measuredMs = new ArrayList<>();
    for (int i = 0; i < WARMUP_RUNS + MEASURED_RUNS; i++) {
      long elapsedMs = runBlameAndMeasure(repoDir, stats.trackedFiles());
      if (i < WARMUP_RUNS) {
        System.out.printf("Warmup run: %d ms%n", elapsedMs);
      } else {
        System.out.printf("Measured run %d: %d ms%n", i - WARMUP_RUNS + 1, elapsedMs);
        measuredMs.add(elapsedMs);
      }
    }

    printSummary(measuredMs);
  }

  private long runBlameAndMeasure(Path repoDir, Set<String> trackedFiles) throws IOException {
    try (Repository repository = new FileRepositoryBuilder().setGitDir(repoDir.toFile()).build()) {
      long startNs = System.nanoTime();
      BlameResult result = new RepositoryBlameCommand(repository).setFilePaths(trackedFiles).setMultithreading(true).call();
      long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
      assertThat(result.getFileBlames()).hasSize(trackedFiles.size());
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
