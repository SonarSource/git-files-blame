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
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sonar.scm.git.blame.BlameResult;
import org.sonar.scm.git.blame.BoundaryBlame;
import org.sonar.scm.git.blame.RepositoryBlameCommand;

/**
 * Not run as part of the default test task (see the "benchmark" Gradle task). Reports how big a {@link BoundaryBlame}
 * cache would be for each of the synthetic scenarios in {@link RepositoryBlamePerfIT}, if it were captured at HEAD
 * and persisted (e.g. in a sensor cache) for reuse by the next analysis.
 */
@Tag("benchmark")
class BoundaryBlameCacheSizeIT {
  private static final long SEED = 42L;

  @TempDir
  Path tempDir;

  @Test
  void cacheSize_manyCommitsSmallFiles() throws IOException {
    reportCacheSize(new SyntheticRepoGenerator.Config(300, 300, 3000, 2, SEED));
  }

  @Test
  void cacheSize_fewCommitsLargeFiles() throws IOException {
    reportCacheSize(new SyntheticRepoGenerator.Config(50, 5000, 2000, 3, SEED));
  }

  @Test
  void cacheSize_veryLargeRepository() throws IOException {
    reportCacheSize(new SyntheticRepoGenerator.Config(1000, 3000, 6000, 4, SEED));
  }

  @Test
  void cacheSize_manyRenames() throws IOException {
    reportCacheSize(new SyntheticRepoGenerator.Config(500, 500, 2000, 2, 40, 50, SEED));
  }

  private void reportCacheSize(SyntheticRepoGenerator.Config config) throws IOException {
    Path repoDir = tempDir.resolve("repo.git");
    SyntheticRepoGenerator.Stats stats = SyntheticRepoGenerator.generate(repoDir, config);

    try (Repository repository = new FileRepositoryBuilder().setGitDir(repoDir.toFile()).build()) {
      BlameResult blameAtHead = new RepositoryBlameCommand(repository).call();
      BoundaryBlame boundary = BoundaryBlame.capture(stats.head(), blameAtHead);
      BoundaryBlame.SerializedSize size = boundary.computeSerializedSize();

      long totalLines = (long) stats.numFiles() * stats.linesPerFile();
      double runsPerFile = (double) size.runCount() / size.pathCount();
      double bytesPerLine = (double) size.bytes() / totalLines;

      System.out.printf("Scenario: %d commits, %d files, %d lines/file (%d total lines)%n",
        stats.numCommits(), stats.numFiles(), stats.linesPerFile(), totalLines);
      System.out.printf("Cache: %d paths, %d runs (%.1f runs/file), %d distinct commits referenced%n",
        size.pathCount(), size.runCount(), runsPerFile, size.distinctCommitCount());
      System.out.printf("Serialized size: %.1f KB (%.3f bytes/line)%n%n", size.bytes() / 1024.0, bytesPerLine);
    } catch (GitAPIException e) {
      throw new IllegalStateException(e);
    }
  }
}
