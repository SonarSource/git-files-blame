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
import java.util.Map;
import java.util.Set;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.sonar.scm.git.blame.BlameResult;
import org.sonar.scm.git.blame.RepositoryBlameCommand;

/**
 * Materializes one (scenario x clone strategy) checkout, runs native git and this library's blame on every
 * target file, asserts they agree, and records how long the clone and each blame took.
 */
public final class ScenarioRunner {

  private final Path workDir;

  public ScenarioRunner(Path workDir) {
    this.workDir = workDir;
  }

  public BlameTiming run(ComparisonScenario scenario, CloneStrategy strategy) throws IOException, GitAPIException {
    Path checkoutDir = workDir.resolve("checkout");
    GitCli git = new GitCli(workDir.resolve("home"));

    long cloneStart = System.nanoTime();
    strategy.materialize(git, scenario.source(), checkoutDir, scenario);
    long cloneMs = elapsedMs(cloneStart);

    long libraryBlameStart = System.nanoTime();
    BlameResult libraryResult = blameWithLibrary(checkoutDir, scenario);
    long libraryBlameMs = elapsedMs(libraryBlameStart);

    long nativeBlameStart = System.nanoTime();
    for (String targetFile : scenario.targetFiles()) {
      String porcelain = git.blameLinePorcelain(checkoutDir, scenario.ref(), targetFile);
      List<NativeLineBlame> nativeBlame = LinePorcelainParser.parse(porcelain);
      Map<Integer, NativeLineBlame> knownAmbiguousLines = scenario.knownAmbiguousLines().getOrDefault(targetFile, Map.of());
      BlameComparator.assertSameBlame(targetFile, libraryResult.getFileBlameByPath().get(targetFile), nativeBlame, knownAmbiguousLines);
    }
    long nativeBlameMs = elapsedMs(nativeBlameStart);

    return new BlameTiming(scenario.name(), strategy, cloneMs, nativeBlameMs, libraryBlameMs);
  }

  private BlameResult blameWithLibrary(Path checkoutDir, ComparisonScenario scenario) throws IOException, GitAPIException {
    try (Repository repository = new FileRepositoryBuilder().setGitDir(checkoutDir.resolve(".git").toFile()).build()) {
      ObjectId startCommit = repository.resolve(scenario.ref());
      return new RepositoryBlameCommand(repository)
        .setFilePaths(Set.copyOf(scenario.targetFiles()))
        .setStartCommit(startCommit)
        // Mirror the production caller (scanner engine's CompositeBlameCommand): ignore whitespace, matching
        // native git's -w. The library defaults to whitespace-sensitive; the caller opts in.
        .setTextComparator(RawTextComparator.WS_IGNORE_ALL)
        .setMultithreading(true)
        .call();
    }
  }

  private static long elapsedMs(long startNanos) {
    return (System.nanoTime() - startNanos) / 1_000_000;
  }
}
