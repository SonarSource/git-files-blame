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
import org.eclipse.jgit.lib.MutableObjectId;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.treewalk.TreeWalk;

public class CommitFileTreeReader {
  private final Repository repository;

  public CommitFileTreeReader(Repository repository) {
    this.repository = repository;
  }

  /**
   * Find all files in a given commit.
   */
  public List<CommitFile> findFiles(ObjectReader objectReader, RevCommit commit) throws IOException {
    MutableObjectId idBuf = new MutableObjectId();
    List<CommitFile> files = new LinkedList<>();

    TreeWalk treeWalk = new TreeWalk(repository, objectReader);
    treeWalk.setRecursive(true);
    treeWalk.addTree(commit.getTree());

    while (treeWalk.next()) {
      treeWalk.getObjectId(idBuf, 0);
      files.add(new CommitFile(treeWalk.getPathString(), idBuf.toObjectId()));
    }
    return files;
  }

  public static class CommitFile {
    private final String path;
    private final ObjectId objectId;

    public CommitFile(String path, ObjectId objectId) {
      this.path = path;
      this.objectId = objectId;
    }

    public String getPath() {
      return path;
    }

    public ObjectId getObjectId() {
      return objectId;
    }
  }
}
