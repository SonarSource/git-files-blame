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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.FileMode;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.TreeFormatter;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;

/**
 * Generates a synthetic bare repository directly through JGit's low-level object database APIs (no working tree,
 * no porcelain add/commit), so that repositories with thousands of commits can be built in a few seconds.
 * Each commit modifies a handful of existing files by rewriting one line, which is representative of the
 * incremental history a real blame run has to walk.
 */
public final class SyntheticRepoGenerator {

  private SyntheticRepoGenerator() {
  }

  /**
   * @param filesRenamedPerCommit how many files get renamed (with a one-line content change, so the rename can't be
   *                              matched by exact blob id and forces content-similarity matching) on commits where a
   *                              rename burst happens
   * @param renameBurstEveryNCommits a rename burst happens every N commits; ignored if filesRenamedPerCommit is 0
   */
  public record Config(int numFiles, int linesPerFile, int numCommits, int filesModifiedPerCommit, int filesRenamedPerCommit, int renameBurstEveryNCommits,
    long seed) {

    public Config(int numFiles, int linesPerFile, int numCommits, int filesModifiedPerCommit, long seed) {
      this(numFiles, linesPerFile, numCommits, filesModifiedPerCommit, 0, 1, seed);
    }
  }

  /**
   * Statistics about the generated repository, returned so a benchmark can print context alongside timings.
   */
  public record Stats(int numFiles, int linesPerFile, int numCommits, ObjectId head) {
  }

  public static Stats generate(Path bareRepoDir, Config config) throws IOException {
    Repository repository = new FileRepositoryBuilder().setGitDir(bareRepoDir.toFile()).build();
    repository.create(true);

    try (repository; ObjectInserter inserter = repository.newObjectInserter()) {
      Random random = new Random(config.seed());

      List<String> paths = new ArrayList<>(List.of(buildSortedFileNames(config.numFiles())));
      Map<String, String[]> linesByPath = new HashMap<>();
      Map<String, ObjectId> blobByPath = new HashMap<>();
      for (String path : paths) {
        String[] lines = initialLines(path, config.linesPerFile());
        linesByPath.put(path, lines);
        blobByPath.put(path, insertBlob(inserter, lines));
      }
      AtomicInteger renameCounter = new AtomicInteger();

      ObjectId parentCommit = null;
      Instant commitTime = Instant.parse("2015-01-01T00:00:00Z");
      for (int commitIdx = 0; commitIdx < config.numCommits(); commitIdx++) {
        for (int modIdx = 0; modIdx < config.filesModifiedPerCommit(); modIdx++) {
          String path = paths.get(random.nextInt(paths.size()));
          rewriteRandomLine(inserter, linesByPath, blobByPath, path, random, commitIdx);
        }

        if (config.filesRenamedPerCommit() > 0 && commitIdx % config.renameBurstEveryNCommits() == 0) {
          for (int renIdx = 0; renIdx < config.filesRenamedPerCommit(); renIdx++) {
            renameRandomFile(inserter, paths, linesByPath, blobByPath, random, commitIdx, renameCounter);
          }
        }

        ObjectId treeId = buildTree(inserter, paths, blobByPath);
        PersonIdent ident = new PersonIdent("generator", "generator@example.com", commitTime, ZoneOffset.UTC);
        parentCommit = createCommit(inserter, treeId, parentCommit, ident, "commit " + commitIdx);
        commitTime = commitTime.plusSeconds(60);
      }

      inserter.flush();
      pointHeadTo(repository, parentCommit);
      return new Stats(config.numFiles(), config.linesPerFile(), config.numCommits(), parentCommit);
    }
  }

  /**
   * @param numDomains number of top-level directories the repository is split into, each getting its own subtree -
   *                    mirrors a monorepo's directory layout, so a domain nobody touched in a given commit keeps
   *                    the exact same subtree object id across commits, which is what lets a path-scoped tree diff
   *                    skip over it without even looking inside
   * @param filesPerDomain files per domain; {@code numDomains * filesPerDomain} files in total
   * @param filesModifiedPerCommit how many files, picked at random from across ALL domains (not just the tracked
   *                                one), get a one-line rewrite on each commit
   */
  public record MonorepoConfig(int numDomains, int filesPerDomain, int linesPerFile, int numCommits, int filesModifiedPerCommit, long seed) {
    public int totalFiles() {
      return numDomains * filesPerDomain;
    }
  }

  /**
   * @param trackedFiles every path belonging to the one domain a benchmark should pass to
   *                      {@code RepositoryBlameCommand.setFilePaths(...)}, simulating analyzing a single
   *                      subdirectory of a much larger monorepo
   */
  public record MonorepoStats(int numDomains, int filesPerDomain, int numCommits, ObjectId head, Set<String> trackedFiles) {
    public int totalFiles() {
      return numDomains * filesPerDomain;
    }
  }

  public static MonorepoStats generateMonorepo(Path bareRepoDir, MonorepoConfig config) throws IOException {
    Repository repository = new FileRepositoryBuilder().setGitDir(bareRepoDir.toFile()).build();
    repository.create(true);

    try (repository; ObjectInserter inserter = repository.newObjectInserter()) {
      Random random = new Random(config.seed());

      String[] domainNames = buildDomainNames(config.numDomains());
      List<String> paths = new ArrayList<>(config.totalFiles());
      Map<String, String[]> linesByPath = new HashMap<>();
      Map<String, ObjectId> blobByPath = new HashMap<>();
      Map<String, Set<String>> pathsByDomain = new HashMap<>();
      for (String domain : domainNames) {
        Set<String> domainPaths = new HashSet<>();
        for (String fileName : buildSortedFileNames(config.filesPerDomain())) {
          String path = domain + "/" + fileName;
          paths.add(path);
          domainPaths.add(path);
          String[] lines = initialLines(path, config.linesPerFile());
          linesByPath.put(path, lines);
          blobByPath.put(path, insertBlob(inserter, lines));
        }
        pathsByDomain.put(domain, domainPaths);
      }

      Map<String, ObjectId> domainTreeIdByDomain = new HashMap<>();
      for (String domain : domainNames) {
        domainTreeIdByDomain.put(domain, buildDomainTree(inserter, pathsByDomain.get(domain), blobByPath));
      }

      ObjectId parentCommit = null;
      Instant commitTime = Instant.parse("2015-01-01T00:00:00Z");
      for (int commitIdx = 0; commitIdx < config.numCommits(); commitIdx++) {
        Set<String> touchedDomains = new HashSet<>();
        for (int modIdx = 0; modIdx < config.filesModifiedPerCommit(); modIdx++) {
          String path = paths.get(random.nextInt(paths.size()));
          rewriteRandomLine(inserter, linesByPath, blobByPath, path, random, commitIdx);
          touchedDomains.add(domainOf(path));
        }

        for (String domain : touchedDomains) {
          domainTreeIdByDomain.put(domain, buildDomainTree(inserter, pathsByDomain.get(domain), blobByPath));
        }

        ObjectId treeId = buildRootTree(inserter, domainNames, domainTreeIdByDomain);
        PersonIdent ident = new PersonIdent("generator", "generator@example.com", commitTime, ZoneOffset.UTC);
        parentCommit = createCommit(inserter, treeId, parentCommit, ident, "commit " + commitIdx);
        commitTime = commitTime.plusSeconds(60);
      }

      inserter.flush();
      pointHeadTo(repository, parentCommit);
      return new MonorepoStats(config.numDomains(), config.filesPerDomain(), config.numCommits(), parentCommit, pathsByDomain.get(domainNames[0]));
    }
  }

  private static String[] buildDomainNames(int numDomains) {
    String[] names = new String[numDomains];
    int digits = Integer.toString(Math.max(numDomains - 1, 1)).length();
    for (int i = 0; i < numDomains; i++) {
      names[i] = "domain" + zeroPad(i, digits);
    }
    return names;
  }

  private static String domainOf(String path) {
    return path.substring(0, path.indexOf('/'));
  }

  private static ObjectId buildDomainTree(ObjectInserter inserter, Set<String> domainPaths, Map<String, ObjectId> blobByPath) throws IOException {
    String[] sortedFileNames = domainPaths.stream().map(p -> p.substring(p.indexOf('/') + 1)).sorted().toArray(String[]::new);
    String domain = domainOf(domainPaths.iterator().next());

    TreeFormatter treeFormatter = new TreeFormatter();
    for (String fileName : sortedFileNames) {
      treeFormatter.append(fileName, FileMode.REGULAR_FILE, blobByPath.get(domain + "/" + fileName));
    }
    return inserter.insert(treeFormatter);
  }

  private static ObjectId buildRootTree(ObjectInserter inserter, String[] domainNames, Map<String, ObjectId> domainTreeIdByDomain) throws IOException {
    String[] sortedDomains = domainNames.clone();
    Arrays.sort(sortedDomains);

    TreeFormatter treeFormatter = new TreeFormatter();
    for (String domain : sortedDomains) {
      treeFormatter.append(domain, FileMode.TREE, domainTreeIdByDomain.get(domain));
    }
    return inserter.insert(treeFormatter);
  }

  private static void rewriteRandomLine(ObjectInserter inserter, Map<String, String[]> linesByPath, Map<String, ObjectId> blobByPath, String path,
    Random random, int commitIdx) throws IOException {
    String[] lines = linesByPath.get(path);
    int lineIdx = random.nextInt(lines.length);
    lines[lineIdx] = "commit " + commitIdx + " rewrote line " + lineIdx + " of " + path;
    blobByPath.put(path, insertBlob(inserter, lines));
  }

  /**
   * Renames a randomly picked file to a brand-new path, rewriting one of its lines at the same time so the new
   * blob can't be matched to the old one by exact id, forcing the (expensive) content-similarity rename matching.
   */
  private static void renameRandomFile(ObjectInserter inserter, List<String> paths, Map<String, String[]> linesByPath, Map<String, ObjectId> blobByPath,
    Random random, int commitIdx, AtomicInteger renameCounter) throws IOException {
    int idx = random.nextInt(paths.size());
    String oldPath = paths.get(idx);
    String[] lines = linesByPath.remove(oldPath);
    blobByPath.remove(oldPath);

    String newPath = String.format("renamed-%06d.txt", renameCounter.getAndIncrement());
    int lineIdx = random.nextInt(lines.length);
    lines[lineIdx] = "commit " + commitIdx + " renamed " + oldPath + " to " + newPath + " and rewrote line " + lineIdx;

    paths.set(idx, newPath);
    linesByPath.put(newPath, lines);
    blobByPath.put(newPath, insertBlob(inserter, lines));
  }

  private static String[] buildSortedFileNames(int numFiles) {
    String[] names = new String[numFiles];
    int digits = Integer.toString(Math.max(numFiles - 1, 1)).length();
    for (int i = 0; i < numFiles; i++) {
      names[i] = "file" + zeroPad(i, digits) + ".txt";
    }
    return names;
  }

  private static String zeroPad(int value, int digits) {
    String raw = Integer.toString(value);
    return "0".repeat(Math.max(0, digits - raw.length())) + raw;
  }

  private static String[] initialLines(String path, int linesPerFile) {
    String[] lines = new String[linesPerFile];
    for (int i = 0; i < linesPerFile; i++) {
      lines[i] = path + " initial line " + i;
    }
    return lines;
  }

  private static ObjectId insertBlob(ObjectInserter inserter, String[] lines) throws IOException {
    byte[] content = (String.join("\n", lines) + "\n").getBytes(StandardCharsets.UTF_8);
    return inserter.insert(Constants.OBJ_BLOB, content);
  }

  private static ObjectId buildTree(ObjectInserter inserter, List<String> paths, Map<String, ObjectId> blobByPath) throws IOException {
    String[] sortedPaths = paths.toArray(new String[0]);
    Arrays.sort(sortedPaths);

    TreeFormatter treeFormatter = new TreeFormatter();
    for (String path : sortedPaths) {
      treeFormatter.append(path, FileMode.REGULAR_FILE, blobByPath.get(path));
    }
    return inserter.insert(treeFormatter);
  }

  private static ObjectId createCommit(ObjectInserter inserter, ObjectId treeId, ObjectId parentId, PersonIdent ident, String message) throws IOException {
    CommitBuilder commitBuilder = new CommitBuilder();
    commitBuilder.setTreeId(treeId);
    if (parentId != null) {
      commitBuilder.addParentId(parentId);
    }
    commitBuilder.setAuthor(ident);
    commitBuilder.setCommitter(ident);
    commitBuilder.setMessage(message);
    return inserter.insert(commitBuilder);
  }

  private static void pointHeadTo(Repository repository, ObjectId head) throws IOException {
    String branchRef = Constants.R_HEADS + Constants.MASTER;

    RefUpdate branchUpdate = repository.updateRef(branchRef);
    branchUpdate.setNewObjectId(head);
    branchUpdate.forceUpdate();

    RefUpdate headUpdate = repository.updateRef(Constants.HEAD);
    headUpdate.link(branchRef);
  }
}
