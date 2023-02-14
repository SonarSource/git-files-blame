/*
 * SonarQube
 * Copyright (C) 2009-2023 SonarSource SA
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
package org.sonar.scm.git.blame;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.RawTextComparator;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryBuilder;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.junit.Test;

public class BlameGeneratorTest {
  private final Path projectDir = Paths.get("/home/meneses/git/sonar-cpp").toAbsolutePath();

  @Test
  public void testNewBlameGenerator() throws IOException, GitAPIException {
    try (Repository repo = loadRepository(projectDir)) {
      RepositoryBlameCommand repoBlameCmd = new RepositoryBlameCommand(repo)
        .setTextComparator(RawTextComparator.WS_IGNORE_ALL);
      BlameResult result = repoBlameCmd.call();
      writeResults("/home/meneses/after.txt", result);
    }
  }

  private static void writeResults(String filename, BlameResult result) throws IOException {
    Map<String, BlameResult.FileBlame> ordered = new TreeMap<>();
    result.getFileBlames().forEach(f -> ordered.put(f.getPath(), f));
    Path resultFile = Paths.get(filename);

    try (Writer w = Files.newBufferedWriter(resultFile, StandardCharsets.UTF_8)) {
      for (Map.Entry<String, BlameResult.FileBlame> e : ordered.entrySet()) {
        w.write(e.getKey() + "\n");
        for (int i = 0; i < e.getValue().lines(); i++) {
          String email = e.getValue().getAuthors()[i] != null ? e.getValue().getAuthors()[i].getEmailAddress() : "null";
          String name = e.getValue().getCommits()[i] != null ? e.getValue().getCommits()[i].getName() : "null";
          w.write(email + " " + name + "\n");
        }
      }
    }
  }

  @Test
  public void testOldImplementation() throws IOException, InterruptedException {
    ExecutorService executorService = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());

    try (Repository repo = loadRepository(projectDir)) {
      Collection<String> paths = readFiles(repo);
      for (String p : paths) {
        executorService.submit(() -> {
          try {
            System.out.println(p);
            Git.wrap(repo).blame()
              // Equivalent to -w command line option
              .setTextComparator(RawTextComparator.WS_IGNORE_ALL)
              .setFilePath(p).call();
          } catch (GitAPIException e) {
            e.printStackTrace();
          }
        });
      }

      executorService.shutdown();
      executorService.awaitTermination(1, TimeUnit.HOURS);
    }
  }

  private Collection<String> readFiles(Repository repository) throws IOException {
    RevCommit head = repository.parseCommit(repository.resolve(Constants.HEAD));
    return findFiles(repository.newObjectReader(), head);
  }

  private Repository loadRepository(Path dir) throws IOException {
    return new RepositoryBuilder()
      .findGitDir(dir.toFile())
      .setMustExist(true)
      .build();
  }

  private List<String> findFiles(ObjectReader objectReader, RevCommit commit) throws IOException {
    List<String> files = new LinkedList<>();

    TreeWalk treeWalk = new TreeWalk(objectReader);
    treeWalk.setRecursive(true);
    treeWalk.reset(commit.getTree());

    while (treeWalk.next()) {
      files.add(treeWalk.getPathString());
    }
    return files;
  }
}
