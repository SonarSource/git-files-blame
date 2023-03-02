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
import java.util.List;
import java.util.stream.Collectors;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.revwalk.RevCommit;

public class StatefulCommitFactory {
  private final CommitFileTreeReader commitFileTree;

  public StatefulCommitFactory(CommitFileTreeReader commitFileTree) {
    this.commitFileTree = commitFileTree;
  }

  public StatefulCommit create(ObjectReader objectReader, RevCommit commit) throws IOException {
    List<FileCandidate> commitFiles = commitFileTree.findFiles(objectReader, commit)
      .stream()
      .map(f -> new FileCandidate(commit, f.getPath(), f.getObjectId()))
      .collect(Collectors.toList());
    return new StatefulCommit(commit, commitFiles);
  }
}
