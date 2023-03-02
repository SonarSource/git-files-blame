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
import java.util.Set;
import javax.annotation.Nullable;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

import static org.eclipse.jgit.lib.FileMode.TYPE_FILE;
import static org.eclipse.jgit.lib.FileMode.TYPE_MASK;

public class StatefulCommitFactory {
  private final Set<String> filePathsToBlame;

  public StatefulCommitFactory(@Nullable Set<String> filePathsToBlame) {
    this.filePathsToBlame = filePathsToBlame;
  }

  /**
   * Find all files in a given commit, filtered by {@link #filePathsToBlame}, if it's set.
   *
   * @return a {link StatefulCommit} for the given commit and the files found.
   */
  public StatefulCommit create(TreeWalk treeWalk, RevCommit commit) throws IOException {
    MutableObjectId idBuf = new MutableObjectId();
    List<FileCandidate> files = new ArrayList<>();

    treeWalk.setRecursive(true);
    treeWalk.reset(commit.getTree());

    while (treeWalk.next()) {
      if (filePathsToBlame != null && !filePathsToBlame.contains(treeWalk.getPathString())) {
        continue;
      }
      if (!isFile(treeWalk.getRawMode(0))) {
        continue;
      }
      treeWalk.getObjectId(idBuf, 0);
      files.add(new FileCandidate(treeWalk.getPathString(), treeWalk.getPathString(), idBuf.toObjectId()));
    }
    return new StatefulCommit(commit, files);
  }

  /**
   * Checks if the tree node represents a file. Symlinks, for example, will return false.
   */
  private static boolean isFile(int rawMode) {
    return (rawMode & TYPE_MASK) == TYPE_FILE;
  }
}
