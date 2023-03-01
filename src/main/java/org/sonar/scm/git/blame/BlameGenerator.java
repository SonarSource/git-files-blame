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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

public class BlameGenerator {
  private final TreeSet<StatefulCommit> queue = new TreeSet<>(StatefulCommit.TIME_COMPARATOR);
  private final FileBlamer fileBlamer;
  private final StatefulCommitFactory statefulCommitFactory;

  /**
   * Revision pool used to acquire commits from.
   */
  private final RevWalk revPool;

  public BlameGenerator(Repository repository, FileBlamer fileBlamer, StatefulCommitFactory statefulCommitFactory) {
    this.fileBlamer = fileBlamer;
    this.statefulCommitFactory = statefulCommitFactory;
    this.revPool = new RevWalk(repository);
  }

  private void prepareStartCommit(ObjectId startCommit) throws IOException {
    RevCommit startRevCommit = revPool.parseCommit(startCommit);
    StatefulCommit statefulStartCommit = statefulCommitFactory.create(revPool.getObjectReader(), startRevCommit);
    fileBlamer.initialize(revPool.getObjectReader(), statefulStartCommit);
    push(statefulStartCommit);
  }

  private void push(StatefulCommit newCommit) {
    if (queue.contains(newCommit)) {
      // this can happen when a branch forks creating another branch, and then they merge again.
      // From the merge commit, we'll traverse both branches, and we'll reach the commit before the fork twice
      // The solution is to merge all regions coming from both sides into that node.
      StatefulCommit existingCommit = queue.ceiling(newCommit);
      Map<PathAndOriginalPath, FileCandidate> newCommitFilesByPaths = newCommit.getAllFiles().stream()
        .collect(Collectors.toMap(PathAndOriginalPath::new, f -> f));

      Map<PathAndOriginalPath, FileCandidate> existingCommitFilesByPaths = existingCommit.getAllFiles().stream()
        .collect(Collectors.toMap(PathAndOriginalPath::new, f -> f));

      for (Map.Entry<PathAndOriginalPath, FileCandidate> newFiles : newCommitFilesByPaths.entrySet()) {
        if (existingCommitFilesByPaths.containsKey(newFiles.getKey())) {
          existingCommitFilesByPaths.get(newFiles.getKey()).mergeRegions(newFiles.getValue());
        } else {
          existingCommit.addFile(newFiles.getValue());
        }
      }
    } else {
      queue.add(newCommit);
    }
  }

  public void compute(ObjectId startCommit) throws IOException, NoHeadException {
    prepareStartCommit(startCommit);

    for (int i = 1; !queue.isEmpty(); i++) {
      StatefulCommit current = queue.pollFirst();
      System.out.println(i + " " + current);

      if (current.getParentCount() > 0) {
        process(current);
      } else {
        // no more parents, so blame all remaining regions to the current commit
        fileBlamer.processResult(current);
      }
    }
    close();
  }

  private void process(StatefulCommit commitCandidate) throws IOException {
    List<RevCommit> parentCommits = new ArrayList<>(commitCandidate.getParentCount());

    for (int i = 0; i < commitCandidate.getParentCount(); i++) {
      RevCommit parentCommit = commitCandidate.getParentCommit(i);
      revPool.parseHeaders(parentCommit);
      parentCommits.add(parentCommit);
    }

    List<StatefulCommit> parentStatefulCommits;
    if (parentCommits.size() > 1) {
      parentStatefulCommits = fileBlamer.blameParents(parentCommits, commitCandidate);
    } else {
      parentStatefulCommits = List.of(fileBlamer.blameParent(parentCommits.get(0), commitCandidate));
    }

    for (StatefulCommit parentStatefulCommit : parentStatefulCommits) {
      if (!parentStatefulCommit.getAllFiles().isEmpty()) {
        push(parentStatefulCommit);
      }
    }

    //Only process the result at the end, when all the regions have been assigned to each parent
    fileBlamer.processResult(commitCandidate);
  }

  private void close() {
    revPool.close();
    queue.clear();
    fileBlamer.close();
  }

  private static class PathAndOriginalPath {
    private final String path;
    private final String originalPath;

    private PathAndOriginalPath(FileCandidate file) {
      this(file.getPath(), file.getOriginalPath());
    }

    private PathAndOriginalPath(String path, String originalPath) {
      this.path = path;
      this.originalPath = originalPath;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      PathAndOriginalPath that = (PathAndOriginalPath) o;
      return Objects.equals(path, that.path) && Objects.equals(originalPath, that.originalPath);
    }

    @Override
    public int hashCode() {
      return Objects.hash(path, originalPath);
    }
  }
}
