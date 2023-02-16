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
  // TODO is it worth to use its object reader everywhere or only in certain cases? What exactly is cached?
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

  private void push(StatefulCommit commitCandidate) {
    if (queue.contains(commitCandidate)) {
      StatefulCommit existingCommit = queue.stream()
        .filter(e -> e.getCommit().equals(commitCandidate.getCommit()))
        .findFirst().orElseThrow();


      Map<String, List<FileCandidate>> commitFileCandidateByOriginalPath = commitCandidate.getAllFiles().stream().collect(Collectors.groupingBy(FileCandidate::getPath));


      for (FileCandidate file : existingCommit.getAllFiles()) {
        List<FileCandidate> fileCandidates = commitFileCandidateByOriginalPath.get(file.getOriginalPath());

        if (fileCandidates != null) {
          for (FileCandidate fileCandidate : fileCandidates) {
            if (fileCandidate != null && file.getPath().equals(fileCandidate.getPath())) {
              file.mergeRegions(fileCandidate);
            }
          }
        }
      }

      List<FileCandidate> remainingFiles = commitCandidate.getAllFiles().stream()
        .filter(f -> f.getRegionList() != null)
        .collect(Collectors.toList());

      if (!remainingFiles.isEmpty()) {
        queue.remove(existingCommit);
        List<FileCandidate> newFiles = new ArrayList<>(existingCommit.getAllFiles());
        newFiles.addAll(remainingFiles);
        StatefulCommit updatedCandidate = new StatefulCommit(commitCandidate.getCommit(), newFiles);
        queue.add(updatedCandidate);
      }
      return;
    }
    queue.add(commitCandidate);
  }

  public void compute(ObjectId startCommit) throws IOException, NoHeadException {
    prepareStartCommit(startCommit);

    for (int i = 1; !queue.isEmpty(); i++) {
      StatefulCommit current = queue.pollFirst();
      System.out.println(i + " " + current);

      int pCnt = current.getParentCount();
      if (pCnt == 1) {
        processOne(current);
      } else if (pCnt > 1) {
        processMerge(current);
      } else {
        // no more parents, so blame all remaining regions to the current commit
        fileBlamer.processResult(current);
      }
    }
    close();
  }

  private void processOne(StatefulCommit current) throws IOException {
    processCommit(current, 0);
    fileBlamer.processResult(current);
  }

  private void processCommit(StatefulCommit current, int parentNumber) throws IOException {
    RevCommit parentCommit = current.getParentCommit(parentNumber);
    revPool.parseHeaders(parentCommit);
    StatefulCommit parent = fileBlamer.blame(revPool.getObjectReader(), parentCommit, current);

    if (!parent.getAllFiles().isEmpty()) {
      push(parent);
    }

    if (current.getCommit() == null) {
      // TODO not sure in what situation this can happen
    }
  }

  private void processMerge(StatefulCommit commitCandidate) throws IOException {
    int parentCount = commitCandidate.getParentCount();
    for (int i = 0; i < parentCount; i++) {
      processCommit(commitCandidate, i);
    }

    //Only process the result at the end, when all the regions have been assigned to each parent
    fileBlamer.processResult(commitCandidate);
  }

  private void close() {
    revPool.close();
    queue.clear();
  }
}
