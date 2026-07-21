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
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
 * Not run as part of the default test task (see the "benchmark" Gradle task). Reproduces the situation observed on
 * the Linux kernel: blaming a handful of files in one subfolder of a very large repository is dominated not by the
 * per-commit tree diff (already scoped to the subfolder) but by the sheer number of commits the blame walks - the
 * blamed subfolder is touched by only a small fraction of them, yet every ancestor is still visited.
 *
 * <p>The {@code commit iterations} reported here is the number of commits the walk actually processes (via the
 * progress callback). The path-scoped walk should cut it down to roughly the number of commits that touch the
 * blamed subfolder, and the wall-clock time with it. Both runs must produce identical blame.
 */
@Tag("benchmark")
class PathScopedWalkPerfIT {
  private static final long SEED = 42L;
  private static final int WARMUP_RUNS = 1;
  private static final int MEASURED_RUNS = 3;

  @TempDir
  Path tempDir;

  /**
   * 50 domains x 200 files (10 000 files), 6000 commits each touching 5 random files across all domains, so only
   * ~1 commit in 10 touches the single blamed domain. A deep history where the blamed subfolder is a small slice.
   */
  @Test
  void blame_subfolderOfDeepHistory() throws IOException {
    Path repoDir = tempDir.resolve("repo.git");
    SyntheticRepoGenerator.MonorepoStats stats = SyntheticRepoGenerator.generateMonorepo(repoDir,
      new SyntheticRepoGenerator.MonorepoConfig(50, 200, 200, 6000, 5, SEED));
    System.out.printf("Generated repo: %d commits, %d domains x %d files (%d total, %d tracked)%n",
      stats.numCommits(), stats.numDomains(), stats.filesPerDomain(), stats.totalFiles(), stats.trackedFiles().size());

    Result result = measure(repoDir, stats.trackedFiles());

    assertThat(result.blamedFiles).isEqualTo(stats.trackedFiles().size());
    System.out.printf("path-scoped blame: %d commit iterations, best %d ms over %d runs%n", result.iterations, result.bestMs, MEASURED_RUNS);
  }

  private static Result measure(Path repoDir, Set<String> trackedFiles) throws IOException {
    long bestMs = Long.MAX_VALUE;
    int iterations = 0;
    int blamedFiles = 0;
    for (int i = 0; i < WARMUP_RUNS + MEASURED_RUNS; i++) {
      AtomicInteger iterationCounter = new AtomicInteger();
      try (Repository repository = new FileRepositoryBuilder().setGitDir(repoDir.toFile()).build()) {
        long startNs = System.nanoTime();
        BlameResult result = new RepositoryBlameCommand(repository)
          .setFilePaths(trackedFiles)
          .setMultithreading(true)
          .setProgressCallBack((iterationNb, commitHash) -> iterationCounter.incrementAndGet())
          .call();
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000;
        if (i >= WARMUP_RUNS) {
          bestMs = Math.min(bestMs, elapsedMs);
        }
        iterations = iterationCounter.get();
        blamedFiles = result.getFileBlames().size();
      } catch (GitAPIException e) {
        throw new IllegalStateException(e);
      }
    }
    return new Result(iterations, bestMs, blamedFiles);
  }

  private record Result(int iterations, long bestMs, int blamedFiles) {
  }
}
