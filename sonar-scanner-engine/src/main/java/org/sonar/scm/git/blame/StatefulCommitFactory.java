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
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

public class StatefulCommitFactory {
  /**
   * Find all files in a given commit.
   */
  public StatefulCommit create(ObjectReader objectReader, RevCommit commit) throws IOException {
    MutableObjectId idBuf = new MutableObjectId();
    List<FileCandidate> files = new LinkedList<>();

    TreeWalk treeWalk = new TreeWalk(objectReader);
    treeWalk.setRecursive(true);
    treeWalk.reset(commit.getTree());

    while (treeWalk.next()) {
      treeWalk.getObjectId(idBuf, 0);
      files.add(new FileCandidate(treeWalk.getPathString(), treeWalk.getPathString(), idBuf.toObjectId()));
    }
    return new StatefulCommit(commit, files);
  }
}
